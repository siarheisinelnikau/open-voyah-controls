package org.openvoyah.controls.data.frida

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.openvoyah.controls.data.frida.VoiceRequestScript.Outcome

class VoiceRequestScriptTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test fun theReplyOfThisRequestIsTheLastResultLine() {
        val reply = "OVC_VOICE_RESULT {\"id\":\"$token\",\"status\":\"accepted\",\"message\":\"ok\"}"
        assertEquals(Outcome.Reply(reply), VoiceRequestScript.outcome("${VoiceRequestScript.PUBLISHED}\n$reply\n"))
    }

    @Test fun aRefusedPrecheckNeverReadsAsAReply() {
        assertEquals(Outcome.NotReady, VoiceRequestScript.outcome(VoiceRequestScript.NOT_READY))
        assertEquals(Outcome.NoRoot, VoiceRequestScript.outcome("noise\n${VoiceRequestScript.NO_ROOT}\n"))
        assertEquals(Outcome.NoReply, VoiceRequestScript.outcome(VoiceRequestScript.PUBLISHED))
        assertEquals(Outcome.NoReply, VoiceRequestScript.outcome(""))
    }

    @Test fun theRequestIsPublishedOnlyAfterTheRootAndAgentChecks() {
        val script = VoiceRequestScript.build("ovc_probe() { :; }", "ovc_ready", "ovc_publish", "/hook.log", token)
        val root = script.indexOf(VoiceRequestScript.NO_ROOT)
        val ready = script.indexOf(VoiceRequestScript.NOT_READY)
        val publish = script.indexOf("ovc_publish")
        assertTrue(script.startsWith("ovc_probe() { :; }\n"))
        assertTrue(root in 0 until ready && ready < publish)
        // Nothing in the script publishes twice or retries after a lost reply.
        assertEquals(1, Regex("ovc_publish").findAll(script).count())
        assertTrue("sleep ${VoiceRequestScript.POLL_SECONDS}" in script)
        assertFalse("sleep 1;" in script)
    }

    @Test fun malformedIdsAndPathsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            VoiceRequestScript.build("", "true", "true", "/hook.log", "x'; reboot; '")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceRequestScript.build("", "true", "true", "/a'b", token)
        }
    }

    @Test fun theScriptRunsInAPosixShell() {
        val shell = listOf("/system/bin/sh", "/bin/sh", "C:/Program Files/Git/bin/sh.exe", "C:/Program Files/Git/usr/bin/sh.exe")
            .map(::File).firstOrNull { it.canExecute() }
        assumeTrue("No POSIX shell on this machine", shell != null)
        val directory = Files.createTempDirectory("ovc-voice").toFile()
        try {
            val log = File(directory, "hook.log")
            val published = File(directory, "published")
            val reply = "OVC_VOICE_RESULT {\"id\":\"$token\",\"status\":\"accepted\",\"message\":\"ok\"}"
            log.writeText("OVC_VOICE_RESULT {\"id\":\"ffffffffffffffffffffffffffffffff\",\"status\":\"blocked\"}\n")
            // The fake agent answers right after the request is published.
            val publish = "{ : > '${path(published)}' && printf '%s\\n' '$reply' >> '${path(log)}'; }"
            val root = "[ \"\$(id -u)\" = 0 ]"
            val ready = VoiceRequestScript.build("", "true", publish, path(log), token).replace(root, "true")
            val started = System.nanoTime()
            val output = run(shell!!, ready)
            assertEquals(output, Outcome.Reply(reply), VoiceRequestScript.outcome(output))
            assertTrue("A reply already in the log is read without a one-second wait",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 900)
            assertTrue(published.isFile)

            published.delete()
            val notReady = VoiceRequestScript.build("", "false", publish, path(log), token).replace(root, "true")
            assertEquals(Outcome.NotReady, VoiceRequestScript.outcome(run(shell, notReady)))
            assertFalse("An agent that is not live never receives the request", published.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun path(file: File) = file.absolutePath.replace('\\', '/')

    private fun run(shell: File, script: String): String {
        // A file, not -c: Windows command lines drop the script's double quotes.
        val file = File.createTempFile("ovc-voice", ".sh").apply { deleteOnExit(); writeText(script) }
        val process = ProcessBuilder(shell.path, file.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        return output
    }
}
