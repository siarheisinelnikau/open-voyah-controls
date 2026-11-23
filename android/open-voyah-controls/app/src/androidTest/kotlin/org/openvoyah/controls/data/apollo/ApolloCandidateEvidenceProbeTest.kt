package org.openvoyah.controls.data.apollo

import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.test.AndroidTestCase
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.openvoyah.controls.domain.apollo.ApolloCacheStatus
import org.openvoyah.controls.domain.apollo.ApolloCandidatePlan

@Suppress("DEPRECATION")
class ApolloCandidateEvidenceProbeTest : AndroidTestCase() {
    fun testReportIsReadOnlyAndNeverClaimsActivation() = runBlocking {
        val report = JSONObject(ApolloCandidateEvidenceProbe(context).report())
        assertEquals(2, report.getInt("schema"))
        assertEquals("read_only", report.getString("mode"))
        assertFalse(report.getBoolean("production_activation_available"))
        assertFalse(report.getJSONObject("provider").getBoolean("content_queried"))
        assertTrue(report.getJSONArray("missing_evidence").length() >= 7)
        // Elapsed times restart with the head unit, so a wall clock and its plausibility are recorded as well.
        Instant.parse(report.getString("capture_time_utc"))
        assertEquals("device_wall_clock", report.getString("capture_clock"))
        assertTrue(report.has("capture_clock_plausible"))
        val identity = report.getJSONObject("identity")
        assertEquals(Build.MODEL, identity.getString("model"))
        assertEquals(Build.DISPLAY, identity.getString("display_version"))
        val packages = report.getJSONArray("packages")
        assertTrue(packages.length() >= 2)
        assertEquals("com.qinggan.app.vehiclesetting", packages.getJSONObject(0).getString("package"))
        val cache = report.getJSONObject("factory_request_cache")
        assertEquals("not_requested", cache.getString("state"))
        assertFalse(cache.getBoolean("controller_feedback"))
        assertFalse(cache.getBoolean("usable_as_rollback"))
        assertEquals(18, cache.getInt("expected_fields"))
        assertFalse(report.getJSONObject("factory_menu").getBoolean("opened_by_probe"))
    }

    fun testExplicitCheckOnAnEmulatorRecordsTheMissingSourceAndRecovery() = runBlocking {
        check(Build.HARDWARE in setOf("goldfish", "ranchu"))
        val result = ApolloCandidateEvidenceProbe(context).capture(includeFactoryCache = true)
        assertEquals(ApolloCacheStatus.PROVIDER_UNAVAILABLE, result.cacheStatus)
        assertEquals(0, result.cachedFields)
        val report = JSONObject(result.json)
        assertEquals("not_verified", report.getJSONObject("recovery").getString("independent_restore"))
        assertFalse(report.getBoolean("production_activation_available"))
        assertFalse(report.getJSONObject("provider").getBoolean("content_queried"))
    }

    fun testExportsExactlyTheCapturedSnapshotToTheChosenDocument() = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runBlocking
        check(Build.HARDWARE in setOf("goldfish", "ranchu"))
        val probe = ApolloCandidateEvidenceProbe(context)
        val evidence = probe.capture(includeFactoryCache = true)
        val resolver = context.contentResolver
        val destination = download("apollo-test-${System.nanoTime()}.json")
        try {
            probe.export(destination, evidence)
            assertEquals(evidence.json, resolver.openInputStream(destination)?.bufferedReader()?.use { it.readText() })
        } finally {
            resolver.delete(destination, null, null)
        }
    }

    /** The driver's note about the factory page leaves with the document and is marked as entered by hand. */
    fun testOwnerNoteIsSavedWithTheReportAsTheDriversOwnWords() = runBlocking {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runBlocking
        check(Build.HARDWARE in setOf("goldfish", "ranchu"))
        val probe = ApolloCandidateEvidenceProbe(context)
        val evidence = probe.capture(includeFactoryCache = true)
        val resolver = context.contentResolver
        val destination = download("apollo-note-${System.nanoTime()}.json")
        try {
            probe.export(destination, evidence, "  NOA stays gray: sign in with the vehicle account.  ")
            val saved = JSONObject(checkNotNull(resolver.openInputStream(destination))
                .bufferedReader().use { it.readText() })
            val note = saved.getJSONObject("owner_note")
            assertEquals("NOA stays gray: sign in with the vehicle account.", note.getString("text"))
            assertTrue(note.getBoolean("entered_by_driver"))
            Instant.parse(note.getString("recorded_at_utc"))
            assertEquals(JSONObject(evidence.json).getJSONObject("identity").getString("fingerprint"),
                saved.getJSONObject("identity").getString("fingerprint"))
        } finally {
            resolver.delete(destination, null, null)
        }
    }

    fun testSupportSummaryQuotesTheInstalledSoftwareWithoutAccountData() = runBlocking {
        check(Build.HARDWARE in setOf("goldfish", "ranchu"))
        val summary = ApolloCandidateEvidenceProbe(context).capture(includeFactoryCache = false).supportSummary
        assertTrue(summary.contains("Apollo diagnostics"))
        assertTrue(summary.contains("Head unit ${Build.MODEL}"))
        assertTrue(summary.contains("Android build ${Build.DISPLAY}"))
        assertTrue(summary.contains(Build.PRODUCT))
        assertTrue(summary.contains(Build.FINGERPRINT))
        assertTrue(summary.lineSequence().any { it == "Factory settings not_visible" })
        assertTrue(summary.contains("Factory request cache"))
    }

    fun testOwnerNoteIsTrimmedAndABlankNoteLeavesTheReportUnchanged() {
        val report = JSONObject().put("schema", 2).toString()
        assertEquals(report, ApolloReportNote.attach(report, "   ", Instant.now()))
        val noted = JSONObject(ApolloReportNote.attach(report, "TLC is off: sign in first.", Instant.now()))
        assertEquals("TLC is off: sign in first.", noted.getJSONObject("owner_note").getString("text"))
        val long = JSONObject(ApolloReportNote.attach(report, "x".repeat(ApolloReportNote.MAX_CHARS * 2), Instant.now()))
        assertEquals(ApolloReportNote.MAX_CHARS, long.getJSONObject("owner_note").getString("text").length)
    }

    private fun download(name: String): Uri = checkNotNull(context.contentResolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        },
    ))

    fun testOnlyExactCacheKeysAndNumericUnambiguousRowsLeaveTheReader() {
        val keys = mutableListOf<String>()
        val cursors = mutableListOf<MatrixCursor>()
        val signal = CancellationSignal()
        val rows = ApolloFactoryCacheReader.readFields(signal) { uri, columns, selection, args, stop ->
            assertSame(signal, stop)
            assertEquals("content://qinggan.settings/system", uri.toString())
            assertEquals(listOf("value"), columns.toList())
            assertEquals("name=?", selection)
            val name = args.single()
            keys += name
            val cursor = MatrixCursor(columns)
            cursors += cursor
            when (name) {
                "TLC_FUNC_ENABLE" -> cursor.addRow(arrayOf("0"))
                "NOA_FUNC_ENABLE" -> cursor.addRow(arrayOf(1))
                "ISA_FUNC_ENABLE_SA" -> cursor.addRow(arrayOf("non-numeric value"))
                "HAVP_FUNC_ENABLE_SA" -> repeat(2) { cursor.addRow(arrayOf("1")) }
            }
            cursor
        }
        assertEquals(ApolloCandidatePlan.AFFECTED_FLAGS, keys.toSet())
        assertEquals(18, keys.size)
        assertTrue(cursors.all { it.isClosed })
        assertEquals(ApolloCacheField("observed", 0), rows["TLC_FUNC_ENABLE"])
        assertEquals(ApolloCacheField("observed", 1), rows["NOA_FUNC_ENABLE"])
        assertEquals(ApolloCacheField("missing"), rows["RPA_FUNC_ENABLE"])
        assertEquals(ApolloCacheField("invalid"), rows["ISA_FUNC_ENABLE_SA"])
        assertEquals(ApolloCacheField("ambiguous"), rows["HAVP_FUNC_ENABLE_SA"])
    }

    fun testCancelledCacheCheckStopsBeforeAnotherField() {
        val signal = CancellationSignal()
        var calls = 0
        try {
            ApolloFactoryCacheReader.readFields(signal) { _, _, _, _, _ ->
                calls++
                signal.cancel()
                null
            }
            fail("Cancelled cache read continued")
        } catch (_: android.os.OperationCanceledException) {
            assertEquals(1, calls)
        }
    }

    fun testSystemSourceSelectionLeavesPathSpecificPermissionsToAndroid() {
        val provider = ProviderInfo().apply {
            packageName = "org.example.factorysettings"
            authority = "org.example.other;qinggan.settings"
            exported = true
            enabled = true
            readPermission = "org.example.factory.READ"
            applicationInfo = ApplicationInfo().apply { enabled = true; flags = ApplicationInfo.FLAG_SYSTEM }
        }
        assertTrue(ApolloFactoryCacheReader.isSystemSource(provider))
        provider.applicationInfo.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        assertTrue(ApolloFactoryCacheReader.isSystemSource(provider))
        provider.applicationInfo.flags = 0
        assertFalse(ApolloFactoryCacheReader.isSystemSource(provider))
        provider.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM
        provider.exported = false
        assertFalse(ApolloFactoryCacheReader.isSystemSource(provider))
        provider.exported = true
        provider.enabled = false
        assertFalse(ApolloFactoryCacheReader.isSystemSource(provider))
        provider.enabled = true
        provider.authority = "other.qinggan.settings"
        assertFalse(ApolloFactoryCacheReader.isSystemSource(provider))
    }

    fun testMetadataProbeLeavesExistingAppPreferencesUntouched() = runBlocking {
        val preferences = context.getSharedPreferences("apollo_probe_test_guard", 0)
        assertTrue(preferences.edit().putString("sentinel", "preserve-existing-settings").commit())
        try {
            val before = preferenceHashes()
            ApolloCandidateEvidenceProbe(context).report()
            assertEquals(before, preferenceHashes())
            assertEquals("preserve-existing-settings", preferences.getString("sentinel", null))
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    private fun preferenceHashes(): Map<String, List<Byte>> =
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.isFile }.associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
}
