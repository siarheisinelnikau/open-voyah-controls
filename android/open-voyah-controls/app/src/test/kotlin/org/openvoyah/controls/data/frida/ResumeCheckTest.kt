package org.openvoyah.controls.data.frida

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.openvoyah.controls.data.frida.ResumeCheck.Result

class ResumeCheckTest {
    private val signer = "c826609d87a0f9959750a43595b9e813d90f99f475d6c8e68c57097e738f78ec"
    private val row = ProductionRootAccess.signerRow("org.openvoyah.controls", signer, 25)

    @Test fun theSignerRowHasTheShapeTheSupervisorAccepts() {
        assertEquals("signer\torg.openvoyah.controls\t$signer\t25", row)
        assertThrows(IllegalArgumentException::class.java) { ProductionRootAccess.signerRow("com.example", signer, 25) }
        assertThrows(IllegalArgumentException::class.java) { ProductionRootAccess.signerRow("org.openvoyah.controls", signer.uppercase(), 25) }
        assertThrows(IllegalArgumentException::class.java) { ProductionRootAccess.signerRow("org.openvoyah.controls", signer, 0) }
        assertThrows(IllegalArgumentException::class.java) { ProductionRootAccess.signerRow("org.openvoyah.controls", signer, 1_000_000_000) }
    }

    @Test fun anUnknownOrMissingAnswerIsStale() {
        assertEquals(Result.CURRENT, ResumeCheck.parse("noise\nOVC_RESUME current\n"))
        assertEquals(Result.RESEAL, ResumeCheck.parse("OVC_RESUME reseal"))
        assertEquals(Result.STALE, ResumeCheck.parse(""))
        assertEquals(Result.STALE, ResumeCheck.parse("OVC_RESUME maybe"))
    }

    @Test fun unexpectedPathsAndRowsAreRefusedBeforeAnyScript() {
        val pins = mapOf("launcher-buttons.js" to signer)
        assertThrows(IllegalArgumentException::class.java) { ResumeCheck.script("/data/adb/../x", pins, row) }
        assertThrows(IllegalArgumentException::class.java) { ResumeCheck.script("/data/adb/ovc", mapOf("../x" to signer), row) }
        assertThrows(IllegalArgumentException::class.java) { ResumeCheck.script("/data/adb/ovc", pins, "signer\t'x\ty\t1") }
    }

    @Test fun theScriptRunsInAPosixShell() {
        val shell = listOf("/bin/sh", "C:/Program Files/Git/bin/sh.exe", "C:/Program Files/Git/usr/bin/sh.exe")
            .map(::File).firstOrNull { it.canExecute() }
        assumeTrue("No POSIX shell on this machine", shell != null)
        // Under the build directory: the script accepts only plain absolute paths, which a Windows temp folder is not.
        val base = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "ovc-resume").toFile()
        try {
            val agent = File(base, "launcher-buttons.js").apply { writeText("agent v2\n") }
            File(base, "enabled").writeText("")
            // A boot entry that answers "waiting" twice (Android has not recorded the new APK yet), then verifies.
            File(base, "boot-start.sh").writeText(
                "n=\$(cat '${path(base)}/tries' 2>/dev/null || echo 0); echo \$((n+1)) > '${path(base)}/tries'\n" +
                    "[ \"\$n\" -ge 2 ] || exit 2\necho OVC_BOOT_VERIFIED\n")
            val trust = File(base, "trust.tsv")
            val pins = mapOf("launcher-buttons.js" to sha256(agent))
            fun check() = ResumeCheck.parse(run(shell!!, ResumeCheck.script(path(base), pins, row)
                .replace("/system/bin/sh", "sh").replace("sleep 2", ":")))

            trust.writeText("signer\torg.openvoyah.controls\t$signer\t24\n")
            assertEquals("Sealed by an older version: reseal", Result.RESEAL, check())
            assertEquals("3", File(base, "tries").readText().trim())
            trust.writeText("$row\n")
            assertEquals(Result.CURRENT, check())
            agent.writeText("agent v1\n")
            assertEquals("A staged agent differs from this APK", Result.STALE, check())
            agent.writeText("agent v2\n")
            File(base, "boot-start.sh").writeText("exit 1\n")
            assertEquals("The deployment does not verify", Result.STALE, check())
        } finally {
            base.deleteRecursively()
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** The path as the shell sees it; Git for Windows maps C:/x to /c/x. */
    private fun path(file: File): String {
        val plain = file.canonicalPath.replace('\\', '/')
        return Regex("^([A-Za-z]):/").find(plain)?.let { "/${it.groupValues[1].lowercase()}/" + plain.substring(3) } ?: plain
    }

    private fun run(shell: File, script: String): String {
        val file = File.createTempFile("ovc-resume", ".sh").apply { deleteOnExit(); writeText(script) }
        val process = ProcessBuilder(shell.path, file.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        return output
    }
}
