package org.openvoyah.controls.data.frida

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class KeyboardSelectionTest {
    private val yandex = "ru.yandex.androidkeyboard/com.android.inputmethod.latin.LatinIME"
    private val aosp = "com.android.inputmethod.latin/.LatinIME"
    private val oem = "com.qinggan.app.qgime/.QingganIME"
    private val passenger = "com.qinggan.app.qgime.second/com.qinggan.app.qgime.QingganIME"

    @Test fun refusesOemKeyboardsAndMalformedIds() {
        assertThrows(IllegalArgumentException::class.java) { KeyboardSelection.selectScript(passenger) }
        assertThrows(IllegalArgumentException::class.java) { KeyboardSelection.selectScript(oem) }
        assertThrows(IllegalArgumentException::class.java) { KeyboardSelection.selectScript("a/b'; reboot; '") }
        assertThrows(IllegalArgumentException::class.java) { KeyboardSelection.releaseScript("x") }
        assertTrue(KeyboardSelection.validId(aosp))
        assertTrue(KeyboardSelection.validId(yandex))
    }

    @Test fun noScriptDisablesAKeyboardOrUsesImeSet() {
        for (script in listOf(KeyboardSelection.readScript(), KeyboardSelection.selectScript(yandex), KeyboardSelection.factoryScript(),
            KeyboardSelection.releaseScript(yandex))) {
            assertFalse(script.contains("ime disable"))
            assertFalse(script.contains("pm disable"))
            assertFalse(script.contains("ime set"))
        }
    }

    @Test fun parsesTheReadBack() {
        val snapshot = KeyboardSelection.parse("""
            noise
            OVC_KEYBOARD result=applied
            OVC_KEYBOARD choice=$yandex
            OVC_KEYBOARD active=$yandex
            OVC_KEYBOARD passenger=$passenger
            OVC_KEYBOARD fallback=
        """.trimIndent())
        assertEquals(KeyboardSelection.Outcome.APPLIED, snapshot.outcome)
        assertEquals(yandex, snapshot.choice)
        assertEquals(yandex, snapshot.active)
        assertEquals(passenger, snapshot.passenger)
        assertNull(snapshot.fallback)
    }

    @Test fun parsesAnUnboundServiceAndAFallback() {
        val snapshot = KeyboardSelection.parse("""
            OVC_KEYBOARD result=read
            OVC_KEYBOARD choice=
            OVC_KEYBOARD active=null
            OVC_KEYBOARD passenger=
            OVC_KEYBOARD fallback=crashed $yandex
        """.trimIndent())
        assertNull(snapshot.choice)
        assertNull(snapshot.active)
        assertNull(snapshot.passenger)
        assertEquals(KeyboardSelection.Fallback(KeyboardSelection.Reason.CRASHED, yandex), snapshot.fallback)
    }

    @Test fun aMissingResultIsAnError() {
        assertThrows(IOException::class.java) { KeyboardSelection.parse("OVC_KEYBOARD choice=$yandex") }
        assertThrows(IOException::class.java) { KeyboardSelection.parse("OVC_KEYBOARD result=maybe") }
    }

    // The scripts themselves, run by a POSIX shell against a model of this firmware's input method service.

    @Test fun withTheAgentTheChoiceIsAppliedAndReadBack() = firmware(agent = true) { car ->
        val snapshot = car.run(KeyboardSelection.selectScript(yandex))
        assertEquals(KeyboardSelection.Outcome.APPLIED, snapshot.outcome)
        assertEquals(yandex, snapshot.choice)
        assertEquals(yandex, snapshot.active)
        assertEquals(passenger, snapshot.passenger)
        assertTrue(car.enabled().contains(oem))
    }

    @Test fun withoutTheAgentAChoiceIsNotKept() = firmware(agent = false) { car ->
        val snapshot = car.run(KeyboardSelection.selectScript(yandex))
        assertEquals(KeyboardSelection.Outcome.NOT_CONFIRMED, snapshot.outcome)
        assertNull(snapshot.choice)
        assertEquals(aosp, snapshot.active)
    }

    @Test fun aKeyboardAndroidDoesNotEnableIsNotChosen() = firmware(agent = true, installed = listOf(oem, aosp, passenger)) { car ->
        val snapshot = car.run(KeyboardSelection.selectScript(yandex))
        assertEquals(KeyboardSelection.Outcome.NOT_ENABLED, snapshot.outcome)
        assertNull(snapshot.choice)
        assertEquals(aosp, snapshot.active)
    }

    @Test fun theFactoryKeyboardReturnsInOneStep() = firmware(agent = true) { car ->
        car.run(KeyboardSelection.selectScript(yandex))
        val snapshot = car.run(KeyboardSelection.factoryScript())
        assertEquals(KeyboardSelection.Outcome.FACTORY, snapshot.outcome)
        assertNull(snapshot.choice)
        assertEquals(aosp, snapshot.active)
    }

    @Test fun aSelectionAppliesAlsoWhenTheSelectedKeyboardValueIsAbsent() = firmware(agent = true, pick = "") { car ->
        // The firmware's fixed keyboard is disabled: Android binds none and leaves default_input_method absent.
        car.run(KeyboardSelection.factoryScript())
        assertNull(car.run(KeyboardSelection.readScript()).active)
        val snapshot = car.run(KeyboardSelection.selectScript(yandex))
        assertEquals(KeyboardSelection.Outcome.APPLIED, snapshot.outcome)
        assertEquals(yandex, snapshot.active)
    }

    @Test fun releasingAGoneKeyboardRecordsWhy() = firmware(agent = true) { car ->
        car.run(KeyboardSelection.selectScript(yandex))
        val snapshot = car.run(KeyboardSelection.releaseScript(yandex))
        assertNull(snapshot.choice)
        assertEquals(KeyboardSelection.Fallback(KeyboardSelection.Reason.UNAVAILABLE, yandex), snapshot.fallback)
        assertEquals(KeyboardSelection.Outcome.FACTORY, car.run(KeyboardSelection.factoryScript()).outcome)
        assertNull(car.run(KeyboardSelection.readScript()).fallback)
    }

    private class Car(private val shell: String, private val state: File, private val stubs: String) {
        fun run(script: String): KeyboardSelection.Snapshot {
            // A file, not `sh -c`: Windows command lines do not carry nested quotes intact.
            val file = File(state, "script.sh").apply { writeText(stubs + script) }
            val process = ProcessBuilder(shell, file.name).directory(state).redirectErrorStream(true)
                .apply { environment()["STATE"] = state.absolutePath.replace('\\', '/') }.start()
            process.outputStream.close()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue("script timed out: $output", finished)
            return try {
                KeyboardSelection.parse(output)
            } catch (error: IOException) {
                throw AssertionError("unexpected script output: $output", error)
            }
        }

        fun enabled(): String = File(state, "s.enabled_input_methods").readText()
    }

    private fun firmware(agent: Boolean, installed: List<String> = listOf(oem, aosp, passenger, yandex), pick: String = aosp,
        test: (Car) -> Unit) {
        val shell = listOf("/bin/sh", "C:/Program Files/Git/bin/sh.exe").firstOrNull { File(it).canExecute() }
        assumeTrue("A POSIX shell runs these checks", shell != null)
        val state = Files.createTempDirectory("ovc-keyboard").toFile()
        try {
            File(state, "installed").writeText(installed.joinToString("\n", postfix = "\n"))
            File(state, "pick").writeText(pick)
            File(state, "current").writeText(pick)
            File(state, "s.default_input_method").writeText(pick)
            File(state, "s.enabled_input_methods").writeText("$oem:$aosp:$passenger;-1")
            if (agent) File(state, "agent").writeText("")
            test(Car(shell!!, state, STUBS))
        } finally {
            state.deleteRecursively()
        }
    }

    private companion object {
        /**
         * The firmware re-reads its keyboard settings whenever `default_input_method` changes and binds its fixed
         * keyboard [pick], or, with the agent, the stored choice while it is installed and enabled. A missing value
         * is a missing file; an empty value is an empty file.
         */
        val STUBS = """
            sleep() { :; }
            ovc_get() { if [ -f "${'$'}STATE/s.${'$'}1" ]; then cat "${'$'}STATE/s.${'$'}1"; echo; else echo null; fi; }
            ovc_reread() {
                ovc_c=${'$'}(cat "${'$'}STATE/s.open_voyah_controls_input_method" 2>/dev/null)
                ovc_e=":${'$'}(cat "${'$'}STATE/s.enabled_input_methods"):"
                if [ -f "${'$'}STATE/agent" ] && [ -n "${'$'}ovc_c" ] && grep -qxF "${'$'}ovc_c" "${'$'}STATE/installed" &&
                    case "${'$'}ovc_e" in *":${'$'}ovc_c:"*|*":${'$'}ovc_c;"*) true;; *) false;; esac; then
                    printf '%s' "${'$'}ovc_c" > "${'$'}STATE/current"; printf '%s' "${'$'}ovc_c" > "${'$'}STATE/s.default_input_method"
                else
                    ovc_p=${'$'}(cat "${'$'}STATE/pick"); printf '%s' "${'$'}ovc_p" > "${'$'}STATE/current"
                    if [ -n "${'$'}ovc_p" ]; then printf '%s' "${'$'}ovc_p" > "${'$'}STATE/s.default_input_method"; fi
                fi
            }
            settings() {
                ovc_before=${'$'}(ovc_get "${'$'}3")
                case "${'$'}1" in
                    get) ovc_get "${'$'}3"; return 0;;
                    put) printf '%s' "${'$'}4" > "${'$'}STATE/s.${'$'}3";;
                    delete) rm -f "${'$'}STATE/s.${'$'}3";;
                esac
                if [ "${'$'}3" = default_input_method ] && [ "${'$'}(ovc_get "${'$'}3")" != "${'$'}ovc_before" ]; then ovc_reread; fi
            }
            ime() {
                [ "${'$'}1" = enable ] && grep -qxF "${'$'}2" "${'$'}STATE/installed" || return 1
                case ":${'$'}(cat "${'$'}STATE/s.enabled_input_methods"):" in *":${'$'}2:"*|*":${'$'}2;"*) return 0;; esac
                printf ':%s' "${'$'}2" >> "${'$'}STATE/s.enabled_input_methods"
            }
            dumpsys() {
                case "${'$'}1" in
                    input_method) ovc_cur=${'$'}(cat "${'$'}STATE/current"); echo "  mCurMethodId=${'$'}{ovc_cur:-null}";;
                    input_method_ext) echo "  mCurMethodId=com.qinggan.app.qgime.second/com.qinggan.app.qgime.QingganIME";;
                esac
            }

        """.trimIndent() + "\n"
    }
}
