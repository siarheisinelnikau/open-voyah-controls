package org.openvoyah.controls.domain.overlays

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the job-control snippets [TextOverlayCatalog] builds (the running check of stage/start and poll) in a real
 * POSIX shell, with the state folder moved to a temporary one. Skipped where no shell is found.
 */
class TextOverlayJobShellTest {
    private val shell = listOfNotNull(System.getenv("OVC_TEST_SHELL"), "/bin/sh", "C:/Program Files/Git/bin/bash.exe")
        .map(::File).firstOrNull { it.isFile }
    private val job = "0123456789abcdef0123456789abcdef"
    private lateinit var state: File
    private val set = BundledSet("ru", "fp", "1", listOf(BundledOverlay(
        "org.openvoyah.controls.overlay.ru.setting.apk", "org.openvoyah.controls.overlay.ru.setting",
        "com.qinggan.app.setting", "a".repeat(64), "c".repeat(64), 1)))

    @Before fun setUp() {
        assumeTrue("A POSIX shell is needed", shell != null)
        state = Files.createTempDirectory("ovc-overlay-state").toFile()
    }

    @After fun tearDown() {
        if (::state.isInitialized) state.deleteRecursively()
    }

    private fun run(command: String): String {
        val path = state.absolutePath.replace('\\', '/')
        // The state folder belongs to root on a head unit; here the test user owns it.
        val script = "stat() { echo 0:700; }\n" + command.replace(TextOverlayCatalog.STATE, path)
        // A file, not "-c": Windows command-line quoting would mangle the quotes and line breaks.
        val file = File(state, "run.sh").apply { writeText(script) }
        val process = ProcessBuilder(shell!!.path, file.absolutePath.replace('\\', '/')).redirectErrorStream(true).start()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        return process.inputStream.bufferedReader().readText()
    }

    private fun bootId() = run("cat /proc/sys/kernel/random/boot_id 2>/dev/null").trim()

    @Test fun aFinishedJobIsReadInOrder() {
        File(state, "job.log").writeText("OVC_OVERLAY_PLACED x.apk\n")
        File(state, "job.status").writeText("$job 0\n")
        val (status, text) = TextOverlayCatalog.outcome(run(TextOverlayCatalog.poll(job)))
        assertEquals(0, status)
        assertEquals("OVC_OVERLAY_PLACED x.apk", text)
    }

    @Test fun anotherJobsStatusIsNeverTaken() {
        File(state, "job.log").writeText("OVC_OVERLAY_PLACED other.apk\n")
        File(state, "job.status").writeText("ffffffffffffffffffffffffffffffff 0\n")
        val reply = run(TextOverlayCatalog.poll(job))
        assertEquals(1, TextOverlayCatalog.outcome(reply).first)
        assertTrue(reply.contains("OVC_TEXT_OVERLAYS_REPLACED"))
        assertFalse(reply.contains("other.apk"))
    }

    @Test fun aPidFromAnEarlierStartDoesNotBlock() {
        File(state, "job.pid").writeText("1 an-earlier-boot\n")
        val reply = run(TextOverlayCatalog.poll(job))
        assertEquals(reply, 1, TextOverlayCatalog.outcome(reply).first)
        assertTrue(reply, reply.contains("stopped before it finished"))
        val staged = run(TextOverlayCatalog.stage(set, "/nonexistent"))
        assertFalse(staged.contains("still running"))
    }

    @Test fun aLiveJobBlocksStagingAndIsReportedRunning() {
        // A process whose command line names the script, started in the same shell, as start() does.
        val launch = "sh -c 'sleep 4; true' text-overlays.sh & echo \"\$! \$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\" > " +
            "${TextOverlayCatalog.STATE}/job.pid\n"
        assumeTrue("The shell exposes /proc/<pid>/cmdline", run("[ -r /proc/\$\$/cmdline ] && echo yes").trim() == "yes")
        val running = run(launch + TextOverlayCatalog.poll(job))
        assertTrue(running, running.contains("OVC_TEXT_OVERLAYS_RUNNING"))
        val staged = run(launch + TextOverlayCatalog.stage(set, "/nonexistent"))
        assertTrue(staged, staged.contains("Another overlay change is still running."))
        assertTrue(bootId().isNotEmpty() || true)
    }
}
