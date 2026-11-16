package org.openvoyah.controls.data.frida

import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.test.AndroidTestCase
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native shell/filesystem checks on synthetic private data; no OEM file, property or API is used. */
class GuestAccountStateTest : AndroidTestCase() {
    private val fixtureBase = "/data/local/tmp/open-voyah-controls"
    private val original = "Original Driver%0%1%fixture-account%123\n".toByteArray()
    private val properties = listOf("qinggan.account.aid", "qinggan.account.uid", "qinggan.account.phone", "com.qinggan.user_started")

    override fun setUp() {
        super.setUp()
        assertTrue("An explicitly selected isolated API 28/30 emulator is required", FridaTransport.canUseFixture(context))
    }

    private inner class Fixture : AutoCloseable {
        val root = File(context.cacheDir, "guest-state-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
        val base = File(root, "base").apply { mkdir() }
        val token = File(root, "token").apply { mkdir() }
        val props = File(root, "properties").apply { mkdir() }
        val account = File(token, "accountInfo")
        val backup = File(base, "profile-backup")
        val journal = File(base, "profile-transaction")
        private val uid = Os.getuid()
        private val gid = Os.getgid()

        init {
            // Android cache directories can inherit a separate cache GID and setgid bit.
            // Normalize this synthetic tree to the identity used by its root-guard projection.
            listOf(root, base, token, props).forEach {
                Os.chown(it.path, uid, gid)
                Os.chmod(it.path, 0x1c0)
            }
            check(token.canonicalPath == token.path && OsConstants.S_ISDIR(Os.lstat(token.path).st_mode))
            account.writeBytes(original)
            Os.chmod(account.path, 0x1a0)
            File(props, "ro.product.name").writeText("f0506h\n")
            File(props, "ro.build.fingerprint").writeText("fixture/guest-record/native:9/test\n")
            File(props, "ro.serialno").writeText("OVC_FIXTURE_UNIT_1\n")
            property("qinggan.account.aid", "fixture-account")
            property("qinggan.account.uid", "123")
            property("qinggan.account.phone", "")
            property("com.qinggan.user_started", "  literal '\$(ignored)' ; % \\ tail  ")
        }

        fun property(name: String, value: String) = File(props, name).writeText("$value\n")
        fun property(name: String): String = File(props, name).readText()
        fun marker(name: String) = File(root, name).writeText("1")
        fun prepare() = execute(GuestAccountState.prepare(fixtureBase))
        fun restore() = execute(GuestAccountState.restore(fixtureBase))
        fun switch(slot: Int) = execute(GuestAccountState.switchTo(fixtureBase,
            "Driver $slot%0%1%fixture-driver-$slot%1000000$slot", "fixture-driver-$slot", "1000000$slot"))

        fun execute(production: String): Result {
            // Only tests map fixed paths and root ownership to the app's private fixture. The
            // deployed command has no path/identity override and still requires root ownership.
            // Kernel locking is qualified separately through the root broker. These tests execute
            // the exact enclosed state operation in a nonroot app-owned native filesystem.
            val operation = production.substringAfter("<<'OVC_GUEST_COMMAND'\n").substringBefore("\nOVC_GUEST_COMMAND\n")
            val projected = operation.replace(fixtureBase, base.path)
                .replace("/private/configs/token", token.path)
                .replace("0:0:600", "$uid:$gid:600").replace("0:0:700", "$uid:$gid:700")
                .replace("[ \"\$(id -u)\" = 0 ]", "[ \"\$(id -u)\" = $uid ]")
                .replace("chown system:system", "chown $uid:$gid").replace("1000:1000:444", "$uid:$gid:444")
            // API 28's nonroot toybox cannot canonicalize through private app ancestors.
            // Java/lstat proved this one newly created token directory above; the separate
            // root-wrapper harness exercises the real readlink guard on both Android versions.
            val pathObservation = if (Build.VERSION.SDK_INT == 28) """
                readlink() {
                    if [ "${'$'}#" = 2 ] && [ "${'$'}1" = -f ] && [ "${'$'}2" = '${token.path}' ]; then
                        printf '%s\n' '${token.path}'
                    else /system/bin/readlink "${'$'}@"; fi
                }
            """.trimIndent() else ""
            val boundary = """
                fixture='${root.path}'
                getprop() {
                    if [ "${'$'}1" = com.qinggan.user_started ] && [ -e "${'$'}fixture/mutate-source" ]; then
                        printf 'changed source\n' > '${account.path}'
                        rm "${'$'}fixture/mutate-source"
                    fi
                    cat "${'$'}fixture/properties/${'$'}1"
                    if [ "${'$'}1" = qinggan.account.aid ] && [ -e "${'$'}fixture/mutate-property" ]; then
                        printf 'changed-property\n' > "${'$'}fixture/properties/${'$'}1"
                        rm "${'$'}fixture/mutate-property"
                    fi
                }
                setprop() {
                    if [ "${'$'}1" = qinggan.account.uid ] && [ -e "${'$'}fixture/drop-write" ]; then return 0; fi
                    printf '%s\n' "${'$'}2" > "${'$'}fixture/properties/${'$'}1"
                }
                chcon() { [ "${'$'}1" = "${'$'}(ls -Zd "${'$'}2" | awk '{print ${'$'}1}')" ]; }
                am() {
                    if [ -e "${'$'}fixture/mutate-after-broadcast" ]; then
                        printf 'changed after broadcast\n' > '${account.path}'
                        rm "${'$'}fixture/mutate-after-broadcast"
                    fi
                }
            """.trimIndent() + "\n" + pathObservation
            val trace = File(root, "last-synthetic-shell-trace")
            val process = ProcessBuilder("/system/bin/sh", "-x").redirectError(trace).start()
            process.outputStream.use { it.write((boundary + "\n" + projected + "\n").toByteArray()) }
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("Synthetic guest-state shell exceeded its bounded lifetime")
            }
            val status = process.exitValue()
            val output = process.inputStream.bufferedReader().readText().trim()
            return Result(status, if (status == 0) output else output + "\nSynthetic fixture trace:\n" + trace.readText().takeLast(12000))
        }

        fun changedState() {
            Os.chmod(account.path, 0x180)
            account.writeText("Replacement account bytes\n")
            properties.forEach { property(it, "replacement-$it") }
        }

        override fun close() { root.deleteRecursively() }
    }

    private data class Result(val status: Int, val output: String)
    private fun verified(result: Result, action: String) {
        assertEquals("Native shell failed: ${result.output}", 0, result.status)
        GuestAccountState.requireReceipt(result.output, action)
    }

    fun testSnapshotRestoresExactBytesMetadataAndLiteralPropertyValues() = Fixture().use { f ->
        val values = properties.associateWith(f::property)
        val metadata = Os.stat(f.account.path)
        verified(f.prepare(), "prepare")
        val ready = File(f.backup, "ready").readBytes()
        f.changedState()
        verified(f.restore(), "restore")
        assertTrue(original.contentEquals(f.account.readBytes()))
        val restored = Os.stat(f.account.path)
        assertEquals(metadata.st_uid, restored.st_uid)
        assertEquals(metadata.st_gid, restored.st_gid)
        assertEquals(metadata.st_mode, restored.st_mode)
        values.forEach { (name, value) -> assertEquals(value, f.property(name)) }
        assertEquals("OVC_GUEST_STATE_V2 restore verified\n", f.journal.readText())
        f.changedState()
        verified(f.prepare(), "prepare")
        assertTrue(original.contentEquals(File(f.backup, "accountInfo").readBytes()))
        assertTrue(ready.contentEquals(File(f.backup, "ready").readBytes()))
    }

    fun testEverySnapshotMemberIsValidatedBeforeRestorationWrites() {
        val corruptions: List<(Fixture) -> Unit> = listOf(
            { File(it.backup, "qinggan.account.phone").delete() },
            { File(it.backup, "context").delete() },
            { File(it.backup, "ready").writeText("incomplete\n") },
            { File(it.backup, "accountInfo").appendText("tampered") },
            { Os.chmod(File(it.backup, "metadata").path, 0x1a4) },
            { val member = File(it.backup, "qinggan.account.uid"); member.delete(); Os.symlink(File(it.props, "qinggan.account.uid").path, member.path) },
            { File(it.backup, "manifest").writeText("x".repeat(8193)) },
            { it.property("ro.build.fingerprint", "different-firmware") },
            { it.property("ro.serialno", "OVC_FIXTURE_UNIT_2") },
        )
        for (corrupt in corruptions) Fixture().use { f ->
            verified(f.prepare(), "prepare")
            f.changedState()
            val before = f.account.readBytes()
            val values = properties.associateWith(f::property)
            corrupt(f)
            assertTrue("An invalid snapshot must fail before writes", f.restore().status != 0)
            assertTrue(before.contentEquals(f.account.readBytes()))
            values.forEach { (name, value) -> assertEquals(value, f.property(name)) }
            assertFalse(f.journal.exists())
        }
    }

    fun testLegacyTornAndOversizedSourcesAreNeverPromoted() {
        for (legacy in listOf(true, false)) Fixture().use { f ->
            f.backup.mkdir()
            Os.chmod(f.backup.path, 0x1c0)
            if (legacy) { File(f.backup, "ready").writeText(""); File(f.backup, "accountInfo").writeText("legacy") }
            assertTrue(f.prepare().status != 0)
            assertFalse(File(f.backup, "schema").exists())
            assertTrue(original.contentEquals(f.account.readBytes()))
        }
        Fixture().use { f ->
            f.account.writeBytes(ByteArray(8193) { 65 })
            assertTrue(f.prepare().status != 0)
            assertFalse(f.backup.exists())
        }
        Fixture().use { f ->
            f.property("ro.serialno", "")
            assertTrue(f.prepare().status != 0)
            assertFalse(f.backup.exists())
        }
    }

    fun testChangingSourceOrPropertiesDoNotPublishASnapshot() {
        for (marker in listOf("mutate-source", "mutate-property")) Fixture().use { f ->
            f.marker(marker)
            assertTrue(f.prepare().status != 0)
            assertFalse(f.backup.exists())
            assertFalse(f.journal.exists())
        }
    }

    fun testFailedReadbackRetainsRecoveryAndBlocksSwitchUntilExplicitRestore() = Fixture().use { f ->
        verified(f.prepare(), "prepare")
        val ready = File(f.backup, "ready").readBytes()
        f.changedState()
        f.marker("drop-write")
        assertTrue(f.restore().status != 0)
        assertEquals("OVC_GUEST_STATE_V2 restore pending\n", f.journal.readText())
        val partial = f.account.readBytes()
        val values = properties.associateWith(f::property)
        assertTrue(f.prepare().status != 0)
        assertTrue(f.switch(1).status != 0)
        assertTrue(partial.contentEquals(f.account.readBytes()))
        values.forEach { (name, value) -> assertEquals(value, f.property(name)) }
        File(f.root, "drop-write").delete()
        verified(f.restore(), "restore")
        assertTrue(ready.contentEquals(File(f.backup, "ready").readBytes()))
    }

    fun testReadbackRunsAgainAfterOemBroadcasts() = Fixture().use { f ->
        verified(f.prepare(), "prepare")
        f.changedState()
        f.marker("mutate-after-broadcast")
        assertTrue(f.restore().status != 0)
        assertEquals("OVC_GUEST_STATE_V2 restore pending\n", f.journal.readText())
        verified(f.restore(), "restore")
    }

    fun testMixedOrCorruptJournalCannotAuthorizeAnotherSwitchOrNewSnapshot() {
        for (journal in listOf("unrecognized\n", "OVC_GUEST_STATE_V2 switch verified\nOVC_GUEST_STATE_V2 switch pending\n",
            "OVC_GUEST_STATE_V2 switch verified\n", "OVC_GUEST_STATE_V2 restore verified\n")) Fixture().use { f ->
            f.journal.writeText(journal)
            Os.chmod(f.journal.path, 0x180)
            assertTrue(f.prepare().status != 0)
            assertFalse(f.backup.exists())
            assertTrue(original.contentEquals(f.account.readBytes()))
        }
    }

    fun testGuestSwitchVerifiesFileModeAndAllProperties() = Fixture().use { f ->
        verified(f.prepare(), "prepare")
        verified(f.switch(2), "switch")
        assertEquals("Driver 2%0%1%fixture-driver-2%10000002", f.account.readText())
        assertEquals(0x124, Os.stat(f.account.path).st_mode and 0x1ff)
        assertEquals("fixture-driver-2\n", f.property("qinggan.account.aid"))
        assertEquals("10000002\n", f.property("qinggan.account.uid"))
        assertEquals("0\n", f.property("qinggan.account.phone"))
        assertEquals("closeLoginView\n", f.property("com.qinggan.user_started"))
        f.marker("drop-write")
        assertTrue(f.switch(3).status != 0)
        assertEquals("OVC_GUEST_STATE_V2 switch pending\n", f.journal.readText())
        assertTrue(original.contentEquals(File(f.backup, "accountInfo").readBytes()))
    }

    fun testVerifiedSwitchAndRestoreJournalsAllowTheNextExplicitSwitch() = Fixture().use { f ->
        verified(f.prepare(), "prepare")
        verified(f.switch(1), "switch")
        verified(f.prepare(), "prepare")
        verified(f.switch(2), "switch")
        verified(f.restore(), "restore")
        verified(f.prepare(), "prepare")
        verified(f.switch(3), "switch")
        assertEquals("10000003\n", f.property("qinggan.account.uid"))
    }

    fun testACompletionWithoutAnExactScopedReceiptIsNotVerified() {
        for (reply in listOf("", "OVC_GUEST_STATE_V2 restore verified\nextra", "OVC_GUEST_STATE_V2 switch verified")) {
            try { GuestAccountState.requireReceipt(reply, "restore"); fail() } catch (_: IOException) { }
        }
    }
}
