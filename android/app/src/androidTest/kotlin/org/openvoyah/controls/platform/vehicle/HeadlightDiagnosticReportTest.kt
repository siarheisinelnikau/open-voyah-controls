package org.openvoyah.controls.platform.vehicle

import android.content.pm.PackageManager
import android.test.InstrumentationTestCase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.openvoyah.controls.domain.vehicle.EarlierHeadlightReport
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightFeedback
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.HeadlightSource
import org.openvoyah.controls.domain.vehicle.HeadlightStatus
import org.openvoyah.controls.domain.vehicle.LampActivity
import org.openvoyah.controls.domain.vehicle.LampObservation
import org.openvoyah.controls.domain.vehicle.LampStatus

class HeadlightDiagnosticReportTest : InstrumentationTestCase() {
    fun testVisiblePackageSuppliesItsAuthenticSignerAndBoundedBaseApkDigest() = runBlocking {
        val fixture = instrumentation.context
        val manager = fixture.packageManager
        val info = manager.getPackageInfo(fixture.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val row = readVehiclePackageEvidence(manager, fixture.packageName, fingerprintBase = true)
        assertEquals("visible", row.getString("status"))
        assertEquals(info.longVersionCode, row.getLong("version_code"))
        val signers = checkNotNull(info.signingInfo?.apkContentsSigners).map { sha256(it.toByteArray()) }.sorted()
        val reportedSigners = row.getJSONArray("current_signer_sha256")
        assertEquals(signers, List(reportedSigners.length(), reportedSigners::getString))
        assertTrue(signers.isNotEmpty())
        val basePath = checkNotNull(info.applicationInfo?.sourceDir)
        assertEquals("read", row.getString("base_apk_fingerprint_status"))
        assertEquals(sha256(File(basePath).readBytes()), row.getString("base_apk_sha256"))
        assertFalse(row.toString().contains(basePath))

        val absent = readVehiclePackageEvidence(manager, "org.example.openvoyah.absent")
        assertEquals("not_visible_or_absent", absent.getString("status"))
        assertEquals(1, absent.length())
    }

    fun testLiveEarlierAndStoredReadingsAreExportedSeparately() = runBlocking {
        val highBeam = LampObservation(LampActivity.OFF, 400, HeadlightSource.ANDROID_AUTOMOTIVE)
        val status = HeadlightStatus(
            feedback = HeadlightFeedback.Confirmed(HeadlightObservation(HeadlightMode.LOW_BEAM, 500, HeadlightSource.FACTORY), 1),
            lastConfirmed = HeadlightObservation(HeadlightMode.POSITION, 200, HeadlightSource.FACTORY),
            saveFailed = true,
            lamps = mapOf(
                ExteriorLamp.HEADLIGHTS to LampStatus(earlierReport = EarlierHeadlightReport(LampActivity.ON, 100)),
                ExteriorLamp.HIGH_BEAM to LampStatus(
                    confirmation = HeadlightFeedback.ActivityConfirmed(ExteriorLamp.HIGH_BEAM, highBeam, 1),
                    lastConfirmed = highBeam,
                ),
            ),
            earlierReport = EarlierHeadlightReport(HeadlightMode.AUTOMATIC, 150),
        )
        val report = JSONObject(headlightDiagnosticReport(instrumentation.targetContext, status))
        val live = report.getJSONObject("current")
        assertFalse(live.has("headlights"))
        assertTrue(live.getJSONObject("selector").getBoolean("history_write_failed"))
        assertEquals("[\"selector\"]", report.getJSONArray("history_write_failures").toString())
        assertEquals("low_beam", live.getJSONObject("selector").getString("value"))
        assertEquals(500L, live.getJSONObject("selector").getLong("confirmed_at_ms"))
        assertEquals("android_automotive", live.getJSONObject("high_beam").getString("source"))
        val earlier = report.getJSONObject("earlier_android_reports")
        assertEquals(100L, earlier.getJSONObject("headlights").getLong("reported_at_ms"))
        assertEquals("automatic", earlier.getJSONObject("selector").getString("value"))
        assertFalse(earlier.getJSONObject("selector").has("confirmed_at_ms"))
        val last = report.getJSONObject("last_confirmed")
        assertEquals("position", last.getJSONObject("selector").getString("value"))
        assertEquals(200L, last.getJSONObject("selector").getLong("confirmed_at_ms"))
    }

    fun testStorageFailuresAreStillReportableWithoutLiveReadings() = runBlocking {
        val status = HeadlightStatus(
            feedback = HeadlightFeedback.Unavailable,
            lamps = mapOf(
                ExteriorLamp.HEADLIGHTS to LampStatus(
                    lastConfirmed = LampObservation(LampActivity.OFF, 400, HeadlightSource.ANDROID_AUTOMOTIVE),
                    saveFailed = true,
                ),
                ExteriorLamp.HIGH_BEAM to LampStatus(saveFailed = true),
            ),
        )
        val report = JSONObject(headlightDiagnosticReport(instrumentation.targetContext, status))
        assertEquals(0, report.getJSONObject("current").length())
        val last = report.getJSONObject("last_confirmed")
        assertEquals("off", last.getJSONObject("headlights").getString("value"))
        assertFalse(last.has("high_beam"))
        assertEquals("[\"headlights\",\"high_beam\"]", report.getJSONArray("history_write_failures").toString())
    }

    fun testAutomotiveReportSeparatesRequestedAndEffectivePermissions() = runBlocking {
        val app = instrumentation.targetContext
        val report = JSONObject(headlightDiagnosticReport(app, HeadlightStatus()))
        assertEquals(2, report.getInt("report_version"))
        val automotive = report.getJSONObject("android_automotive")
        val requests = automotive.getJSONObject("requested_permissions")
        val effective = automotive.getJSONObject("permissions")
        val declared = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        for (permission in listOf(HEADLIGHT_SWITCH_PERMISSION, HEADLIGHT_ACTIVITY_PERMISSION,
            "android.car.permission.CONTROL_CAR_SEATS")) {
            assertEquals(permission, permission in declared, requests.getBoolean(permission))
            assertEquals(permission, app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
                effective.getBoolean(permission))
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
