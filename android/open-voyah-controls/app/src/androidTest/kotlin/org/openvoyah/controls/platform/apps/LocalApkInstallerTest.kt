package org.openvoyah.controls.platform.apps

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.test.InstrumentationTestCase
import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openvoyah.controls.R
import org.openvoyah.controls.BuildConfig
import org.openvoyah.controls.platform.packages.PackageSessionRemoval
import org.openvoyah.controls.platform.packages.PackageSessionRemoval.Observation
import org.openvoyah.controls.platform.updates.AndroidUpdatePackageSessions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Sessions are fixtures. Archive parsing is real in the installed Controls/test-APK regressions. */
class LocalApkInstallerTest : InstrumentationTestCase() {
    fun testAndroidEmptyForeignSessionMetadataAllowsExactOwnedAbandonment() {
        requireEmulator()
        val app = instrumentation.targetContext.applicationContext
        val platform = app.packageManager.packageInstaller
        val sessions = AndroidLocalApkSessions(app)
        val target = "org.example.localapkfixture"
        val id = sessions.create(target, 1)
        try {
            val current = checkNotNull(sessions.current().singleOrNull { it.id == id })
            assertEquals(app.packageName, current.installer)
            assertEquals(target, current.packageName)
            assertFalse(current.sealed)
            assertTrue(sessions.abandon(id, target))
            assertNull(platform.getSessionInfo(id))
        } finally {
            // This exact ID was created empty by this test. Never enumerate or abandon other sessions.
            if (platform.getSessionInfo(id) != null) platform.abandonSession(id)
            awaitFixtureRemoval(platform, id)
        }
    }

    fun testAndroidEmptySelfUpdateSessionIsPreservedByGenericAdapter() {
        requireEmulator()
        val app = instrumentation.targetContext.applicationContext
        val platform = app.packageManager.packageInstaller
        val sessions = AndroidLocalApkSessions(app)
        val id = platform.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(app.packageName); setSize(1) })
        try {
            val current = checkNotNull(sessions.current().singleOrNull { it.id == id })
            assertEquals(app.packageName, current.installer)
            assertEquals(app.packageName, current.packageName)
            assertFalse(current.sealed)
            assertFalse(sessions.abandon(id, app.packageName))
            assertFalse(sessions.abandon(id))
            assertNotNull(platform.getSessionInfo(id))
            assertTrue(AndroidUpdatePackageSessions(app).abandon(id))
            assertNull(platform.getSessionInfo(id))
        } finally {
            // No APK bytes are written or committed; only this test's own empty ID may be removed.
            if (platform.getSessionInfo(id) != null) platform.abandonSession(id)
            awaitFixtureRemoval(platform, id)
        }
    }

    @Suppress("DEPRECATION")
    fun testArchiveSigningFlagsMatchOnInstalledAndPrivatePaths() = runBlocking {
        val original = File(instrumentation.context.applicationInfo.sourceDir)
        val packages = instrumentation.targetContext.packageManager
        val flags = linkedMapOf("none" to 0, "modern" to PackageManager.GET_SIGNING_CERTIFICATES,
            "legacy" to PackageManager.GET_SIGNATURES,
            "combined" to (PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES))
        val originals = flags.mapValues { (_, value) -> packages.getPackageArchiveInfo(original.path, value) }
        fixture(source = { original.inputStream() }, inspect = { privateFile ->
            val copied = flags.mapValues { (_, value) -> packages.getPackageArchiveInfo(privateFile.path, value) }
            fun describe(info: PackageInfo?): String = "package=${info?.packageName},app=${info?.applicationInfo != null}," +
                "current=${info?.signingInfo?.apkContentsSigners?.size ?: 0},legacy=${info?.signatures?.size ?: 0}"
            val report = flags.keys.joinToString("; ") { key -> "$key original[${describe(originals[key])}] copied[${describe(copied[key])}]" }
            Log.i("LocalApkArchiveTest", report)
            val originalInfo = checkNotNull(originals["combined"]) { report }
            val copiedInfo = checkNotNull(copied["combined"]) { report }
            assertEquals(report, instrumentation.context.packageName, copiedInfo.packageName)
            assertTrue(report, copiedInfo.applicationInfo != null)
            assertTrue(report, !copiedInfo.signingInfo?.apkContentsSigners.isNullOrEmpty())
            assertEquals(report, originalInfo.signingInfo?.apkContentsSigners?.map { it.toCharsString() },
                copiedInfo.signingInfo?.apkContentsSigners?.map { it.toCharsString() })
            copiedInfo.packageName
        }) {
            installer.stage(URI)
            assertEquals(1, sessions.created)
            assertEquals(1, sessions.committed)
            assertNoOwnedFiles()
        }
    }

    fun testInstalledSignedControlsArchiveIsRejectedBeforeCreatingSession() = runBlocking {
        val apk = File(instrumentation.targetContext.applicationInfo.sourceDir)
        fixture(source = { apk.inputStream() }, inspect = null) {
            installer.stage(URI)
            assertEquals(R.string.apps_install_self_update, installer.state.value.message)
            assertEquals(0, sessions.created)
            assertEquals(0, sessions.committed)
            assertNoOwnedFiles()
        }
    }

    fun testValidForeignTestApkStillCopiesExactBytesAndCommitsFixtureSession() = runBlocking {
        val apk = File(instrumentation.context.applicationInfo.sourceDir)
        fixture(source = { apk.inputStream() }, inspect = null) {
            installer.stage(URI)
            assertEquals(1, sessions.created)
            assertEquals(1, sessions.committed)
            assertEquals(instrumentation.context.packageName, sessions.packageName)
            assertTrue(apk.readBytes().contentEquals(sessions.output.toByteArray()))
            assertEquals(apk.length(), preferences.getLong("bytes", 0))
            assertNoOwnedFiles()
        }
    }

    fun testUnknownAndTruncatedArchivesNeverCreateSessionOrLeaveOwnedFile() = runBlocking {
        val apk = File(instrumentation.context.applicationInfo.sourceDir).readBytes()
        for (bytes in listOf(ByteArray(0), "not an APK".toByteArray(), apk.copyOf(apk.size / 2))) {
            fixture(source = { ByteArrayInputStream(bytes) }, inspect = null) {
                installer.stage(URI)
                assertEquals(R.string.apps_install_invalid_apk, installer.state.value.message)
                assertEquals(0, sessions.created)
                assertEquals(0, sessions.committed)
                assertNoOwnedFiles()
            }
        }
    }

    fun testOwnPackageReportedByParserIsRejectedBeforeAnySessionHandoff() = runBlocking {
        fixture(inspect = { instrumentation.targetContext.packageName }) {
            installer.stage(URI)
            assertEquals(R.string.apps_install_self_update, installer.state.value.message)
            assertEquals(0, sessions.created)
            assertEquals(0, sessions.committed)
            assertNoOwnedFiles()
        }
    }

    fun testSnapshotChangedAfterInspectionCannotCommitAndOwnedSessionIsAbandoned() = runBlocking {
        fixture {
            sessions.beforeWrite = {
                val staged = checkNotNull(stageDirectory.listFiles()?.singleOrNull())
                check(staged.setWritable(true, true))
                staged.writeBytes("different bytes".toByteArray())
            }
            installer.stage(URI)
            assertEquals(1, sessions.created)
            assertEquals(0, sessions.committed)
            assertEquals(1, sessions.abandoned)
            assertEquals(R.string.apps_install_changed_apk, installer.state.value.message)
            assertFalse(preferences.contains("session"))
            assertNoOwnedFiles()
        }
    }

    fun testCreationIntentIsDurableBeforeAndroidAllocatesSession() = runBlocking {
        fixture {
            sessions.beforeCreate = {
                assertEquals("org.example.fixture", preferences.getString("creating_package", null))
                assertFalse(preferences.getString("token", null).isNullOrBlank())
                assertFalse(preferences.contains("session"))
            }
            installer.stage(URI)
            assertEquals(1, sessions.committed)
            assertFalse(preferences.contains("creating_package"))
            assertEquals("org.example.fixture", preferences.getString("package", null))
        }
    }

    fun testSessionAllocatedBeforeCreateThrowsIsFoundAndAbandonedWithoutCommit() = runBlocking {
        fixture {
            sessions.throwAfterCreate = true
            installer.stage(URI)
            assertEquals(1, sessions.created)
            assertEquals(0, sessions.committed)
            assertEquals(1, sessions.abandoned)
            assertTrue(sessions.current().isEmpty())
            assertFalse(preferences.contains("creating_package"))
            assertFalse(installer.state.value.interrupted)
            assertNoOwnedFiles()
        }
    }

    fun testReopenFindsCreationGapAndPreservesSelfUpdateAndOtherTargets() = runBlocking {
        fixture {
            creationIntent()
            sessions.pending[42] = LocalApkSessionInfo(42, context.packageName, "org.example.fixture", false)
            sessions.pending[43] = LocalApkSessionInfo(43, context.packageName, context.packageName, false)
            sessions.pending[44] = LocalApkSessionInfo(44, context.packageName, "org.example.other", false)
            sessions.pending[45] = LocalApkSessionInfo(45, "org.example.installer", "org.example.another", false)
            LocalApkInstaller(context, stager, sessions).recover()
            assertEquals(setOf(43, 44, 45), sessions.pending.keys)
            assertEquals(1, sessions.abandoned)
            assertEquals(0, sessions.committed)
            assertFalse(preferences.contains("creating_package"))
            assertFalse(installer.state.value.interrupted)
        }
    }

    fun testFailedCreationCleanupRetainsIntentForExplicitRetry() = runBlocking {
        fixture {
            sessions.throwAfterCreate = true
            sessions.abandonAllowed = false
            installer.stage(URI)
            assertTrue(preferences.contains("creating_package"))
            assertTrue(installer.state.value.interrupted)
            assertEquals(0, sessions.committed)
            sessions.abandonAllowed = true
            val reopened = LocalApkInstaller(context, stager, sessions)
            reopened.discardInterrupted()
            assertTrue(sessions.current().isEmpty())
            assertFalse(preferences.contains("creating_package"))
            assertFalse(reopened.state.value.interrupted)
            assertNoOwnedFiles()
        }
    }

    fun testSealedOrUnattributedCreationMatchesRemainInterruptedAndAreNeverReplayed() = runBlocking {
        for (candidate in listOf(LocalApkSessionInfo(42, instrumentation.targetContext.packageName, "org.example.fixture", true),
            LocalApkSessionInfo(42, "org.example.installer", "org.example.fixture", false),
            LocalApkSessionInfo(42, instrumentation.targetContext.packageName, null, false))) {
            fixture {
                creationIntent()
                sessions.pending[42] = candidate
                val reopened = LocalApkInstaller(context, stager, sessions)
                reopened.recover()
                reopened.discardInterrupted()
                assertEquals(candidate, sessions.pending[42])
                assertEquals(0, sessions.abandoned)
                assertEquals(0, sessions.committed)
                assertTrue(preferences.contains("creating_package"))
                assertTrue(reopened.state.value.interrupted)
            }
        }
    }

    fun testExcessiveOwnedSessionListCannotBePartiallyDiscarded() = runBlocking {
        fixture {
            creationIntent()
            for (id in 0..LocalApkSessions.MAX_SESSION_SCAN)
                sessions.pending[id] = LocalApkSessionInfo(id, context.packageName, "org.example.fixture", false)
            installer.recover()
            assertEquals(0, sessions.abandoned)
            assertTrue(preferences.contains("creating_package"))
            assertTrue(installer.state.value.interrupted)
        }
    }

    fun testFailedPrecreateWritePreventsAnySessionAllocation() = runBlocking {
        fixture(failPreferenceCommits = 1) {
            installer.stage(URI)
            assertEquals(0, sessions.created)
            assertEquals(0, sessions.committed)
            assertNoOwnedFiles()
        }
    }

    fun testFailedClearKeepsCreationIntentAvailableForLaterRecovery() = runBlocking {
        fixture(failPreferenceCommits = 0) {
            creationIntent()
            preferencesFailure.remaining = 1
            installer.recover()
            assertTrue(preferences.contains("creating_package"))
            assertTrue(installer.state.value.interrupted)
            val reopened = LocalApkInstaller(context, stager, sessions)
            reopened.recover()
            assertFalse(preferences.contains("creating_package"))
            assertFalse(reopened.state.value.interrupted)
            assertEquals(0, sessions.created)
            assertEquals(0, sessions.committed)
        }
    }

    fun testCancellingProviderReadClosesInputAndLeavesNoOwnedFileOrSession() = runBlocking {
        val input = ControlledInput()
        fixture(source = { input }) {
            val work = launch(Dispatchers.IO) { installer.stage(URI) }
            assertTrue(input.reading.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { work.cancelAndJoin() }
            assertTrue(input.closed)
            assertEquals(0, sessions.created)
            assertEquals(0, sessions.committed)
            assertFalse(installer.state.value.busy)
            assertNoOwnedFiles()
        }
    }

    fun testInterruptedCleanupWaitsForLiveStagingAndPreservesUnrelatedFiles() = runBlocking {
        val input = ControlledInput()
        fixture(source = { input }) {
            val work = launch(Dispatchers.IO) { installer.stage(URI) }
            assertTrue(input.reading.await(3, TimeUnit.SECONDS))
            val owned = checkNotNull(stageDirectory.listFiles()?.singleOrNull())
            val unrelated = File(stageDirectory, "notes.txt").apply { writeText("keep") }
            // UNDISTPATCHED reaches the shared mutex synchronously; no timing-based negative assertion is needed.
            val recovery = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { installer.recover() }
            assertFalse(recovery.isCompleted)
            assertTrue(owned.isFile)
            input.release.countDown()
            withTimeout(3_000) { work.join(); recovery.join() }
            assertEquals(1, sessions.committed)
            assertEquals("keep", unrelated.readText())
            assertNoOwnedFiles()
        }
    }

    fun testRecoveryRemovesOnlyOwnedInterruptedSnapshot() = runBlocking {
        fixture {
            val staged = stager.stage(URI)
            val unrelated = File(stageDirectory, "notes.txt").apply { writeText("keep") }
            assertTrue(staged.file.isFile)
            installer.recover()
            assertFalse(staged.file.exists())
            assertEquals("keep", unrelated.readText())
            assertNoOwnedFiles()
        }
    }

    fun testCleanupRefusesSymlinkAndLeavesItsTargetUntouched() = runBlocking {
        fixture {
            val staged = stager.stage(URI)
            stager.discard(staged)
            val target = File(context.cacheDir, "outside.apk").apply { writeBytes(BYTES) }
            val link = File(stageDirectory, "local-${UUID.randomUUID()}.apk")
            Os.symlink(target.path, link.path)
            installer.stage(URI)
            assertEquals(0, sessions.created)
            assertEquals(0, sessions.committed)
            assertTrue(BYTES.contentEquals(target.readBytes()))
            assertTrue(link.delete())
        }
    }

    private suspend fun fixture(
        source: () -> InputStream = { ByteArrayInputStream(BYTES) },
        inspect: ((File) -> String?)? = { "org.example.fixture" },
        failPreferenceCommits: Int = 0,
        test: suspend Fixture.() -> Unit,
    ) {
        val base = instrumentation.targetContext
        val prefix = "local_apk_test_${UUID.randomUUID()}_"
        val cache = File(base.cacheDir, prefix)
        assertTrue(cache.mkdir())
        val failures = PreferenceFailure(failPreferenceCommits)
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cache
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = super.getSharedPreferences(prefix + name, mode)
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = actual.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                                apply { editor.putString(key, value) }
                            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                                apply { editor.putInt(key, value) }
                            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                                apply { editor.putLong(key, value) }
                            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                                apply { editor.putBoolean(key, value) }
                            override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }
                            override fun commit(): Boolean {
                                val saved = editor.commit()
                                if (failures.remaining == 0) return saved
                                failures.remaining -= 1
                                return false
                            }
                        }
                    }
                }
            }
        }
        val stager = if (inspect == null) LocalApkStager(isolated, open = { _, _ -> source() })
            else LocalApkStager(isolated, open = { _, _ -> source() }, inspect = inspect)
        val sessions = RecordingSessions(isolated.packageName)
        val installer = LocalApkInstaller(isolated, stager, sessions)
        val value = Fixture(isolated, stager, sessions, installer, failures)
        try { value.test() }
        finally {
            value.finishPending()
            base.deleteSharedPreferences(prefix + "apk_install_session")
            check(cache.canonicalFile.parentFile == base.cacheDir.canonicalFile && cache.name == prefix)
            cache.deleteRecursively()
        }
    }

    private class Fixture(val context: Context, val stager: LocalApkStager,
                          val sessions: RecordingSessions, val installer: LocalApkInstaller,
                          val preferencesFailure: PreferenceFailure) {
        val stageDirectory get() = File(context.cacheDir, LocalApkStager.DIRECTORY)
        val preferences get() = context.getSharedPreferences("apk_install_session", Context.MODE_PRIVATE)
        fun assertNoOwnedFiles() {
            junit.framework.Assert.assertFalse(stageDirectory.listFiles().orEmpty().any { it.name.startsWith("local-") })
        }
        fun creationIntent() {
            check(preferences.edit().putString("creating_package", "org.example.fixture")
                .putString("token", UUID.randomUUID().toString()).commit())
        }
        fun finishPending() {
            val id = preferences.getInt("session", -1)
            val token = preferences.getString("token", null)
            if (id >= 0 && token != null) {
                installer.handleResult(Intent("org.openvoyah.controls.action.APK_INSTALL_RESULT")
                    .setData(Uri.fromParts("ovc-apk-install", token, null))
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                    .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED))
            }
        }
    }

    private class PreferenceFailure(var remaining: Int)

    private fun awaitFixtureRemoval(platform: PackageInstaller, id: Int) {
        val started = SystemClock.elapsedRealtime()
        val removed = PackageSessionRemoval.await {
            if (platform.getSessionInfo(id) == null && platform.mySessions.none { it.sessionId == id })
                Observation.ABSENT else Observation.PRESENT
        }
        assertTrue("Empty fixture session $id was not removed after ${SystemClock.elapsedRealtime() - started} ms; " +
            "queryPresent=${platform.getSessionInfo(id) != null}, listed=${platform.mySessions.any { it.sessionId == id }}", removed)
    }

    private class RecordingSessions(private val owner: String) : LocalApkSessions {
        var created = 0
        var committed = 0
        var abandoned = 0
        var packageName: String? = null
        var beforeCreate: () -> Unit = {}
        var beforeWrite: () -> Unit = {}
        var throwAfterCreate = false
        var abandonAllowed = true
        val pending = mutableMapOf<Int, LocalApkSessionInfo>()
        val output = ByteArrayOutputStream()
        override fun canInstall() = true
        override fun create(packageName: String, bytes: Long): Int {
            beforeCreate()
            this.packageName = packageName
            created++
            pending[42] = LocalApkSessionInfo(42, owner, packageName, false)
            if (throwAfterCreate) throw IOException("Fixture allocated a session but lost its create response")
            return 42
        }
        override fun open(id: Int) = object : LocalApkSessions.Session {
            override fun openWrite(bytes: Long): OutputStream { beforeWrite(); return output }
            override fun fsync(output: OutputStream) { }
            override fun commit(sender: IntentSender) {
                committed++
                pending[id] = checkNotNull(pending[id]).copy(sealed = true)
            }
            override fun close() { }
        }
        override fun current(): List<LocalApkSessionInfo> = pending.values.toList()
        override fun abandon(id: Int, expectedPackage: String?): Boolean {
            abandoned++
            if (!abandonAllowed) return false
            val session = pending[id] ?: return true
            if (session.installer != owner || session.packageName == owner ||
                expectedPackage != null && session.packageName != expectedPackage) return false
            pending.remove(id)
            return true
        }
    }

    private class ControlledInput : InputStream() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var closed = false
        private val bytes = ByteArrayInputStream(BYTES)
        override fun read(): Int = error("Use buffered reads")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reading.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            if (closed) throw IOException("Fixture closed")
            return bytes.read(buffer, offset, length)
        }
        override fun close() { closed = true; release.countDown() }
    }

    private companion object {
        fun requireEmulator() {
            check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        }
        val URI: Uri = Uri.parse("content://org.example.fixture/selected.apk")
        val BYTES = "a bounded foreign APK fixture".toByteArray()
    }
}
