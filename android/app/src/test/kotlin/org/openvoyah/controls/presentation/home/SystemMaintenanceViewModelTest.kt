package org.openvoyah.controls.presentation.home

import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openvoyah.controls.data.frida.SystemRestartScripts

class SystemMaintenanceViewModelTest {
    private val dispatcher = Dispatchers.Unconfined
    private fun model(restarts: SystemRestarts) = SystemMaintenanceViewModel(restarts, dispatcher, CoroutineScope(dispatcher))
    private val restarted = SystemRestartScripts.MediaRestart(listOf("com.qinggan.media"), emptyList(), true, 2)

    private class Fake(var media: () -> SystemRestartScripts.MediaRestart, var reboot: () -> Unit = {}) : SystemRestarts {
        var mediaCalls = 0
        var rebootCalls = 0
        override fun restartMultimedia(): SystemRestartScripts.MediaRestart { mediaCalls++; return media() }
        override fun rebootSystem() { rebootCalls++; reboot() }
    }

    @Test fun aMultimediaRestartReportsItsResult() {
        val fake = Fake({ restarted })
        val model = model(fake)

        model.restartMultimedia()

        assertEquals(1, fake.mediaCalls)
        assertEquals(SystemMaintenanceViewModel.State(notice = SystemNotice.MediaRestarted(restarted)), model.state.value)
        model.acknowledge()
        assertNull(model.state.value.notice)
    }

    @Test fun aRefusedRebootShowsTheBridgeMessage() {
        val fake = Fake({ restarted }, { throw IOException("Shift to P first") })
        val model = model(fake)

        model.rebootSystem()

        assertEquals(1, fake.rebootCalls)
        assertEquals(SystemNotice.Failed(SystemOperation.REBOOT, "Shift to P first"), model.state.value.notice)
        assertNull(model.state.value.working)
    }

    @Test fun aRequestedRebootIsReported() {
        val model = model(Fake({ restarted }))
        model.rebootSystem()
        assertEquals(SystemNotice.RebootRequested, model.state.value.notice)
    }

    @Test fun aSecondTapWhileWorkingIsIgnored() {
        lateinit var model: SystemMaintenanceViewModel
        val fake = Fake({
            // The owner taps again while the first restart still runs.
            model.restartMultimedia()
            model.rebootSystem()
            assertEquals(SystemOperation.MEDIA, model.state.value.working)
            restarted
        })
        model = model(fake)

        model.restartMultimedia()

        assertEquals(1, fake.mediaCalls)
        assertEquals(0, fake.rebootCalls)
        assertNull(model.state.value.working)
    }

    @Test fun theVersionNamesTheBuildCommitAndInstallTime() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val info = AppVersionInfo("0.20.1", 24, "local-vehicle", "3752fe3a+", 1_795_000_000_000L)
            val lines = info.lines("Build", "commit", "Installed", Locale.US)
            assertEquals("Open Voyah Controls 0.20.1 (24)", lines[0])
            assertEquals("Build: local-vehicle · commit 3752fe3a+", lines[1])
            assertEquals(3, lines.size)
            assertEquals(true, lines[2].startsWith("Installed: "))
            assertEquals(listOf("Open Voyah Controls 0.20.1 (24)", "Build: release"),
                AppVersionInfo("0.20.1", 24, "release", "", null).lines("Build", "commit", "Installed", Locale.US))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test fun theBuildTypeNamesTheCarAndEmulatorBuilds() {
        assertEquals("local-vehicle", AppVersionInfo.buildType(debug = true, buildType = "debug", localVehicle = true, emulatorFixture = false))
        assertEquals("debug · emulator", AppVersionInfo.buildType(debug = true, buildType = "debug", localVehicle = false, emulatorFixture = true))
        assertEquals("debug", AppVersionInfo.buildType(debug = true, buildType = "debug", localVehicle = false, emulatorFixture = false))
        assertEquals("release", AppVersionInfo.buildType(debug = false, buildType = "release", localVehicle = true, emulatorFixture = false))
    }
}
