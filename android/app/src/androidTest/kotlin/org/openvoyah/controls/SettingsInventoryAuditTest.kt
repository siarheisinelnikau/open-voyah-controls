package org.openvoyah.controls

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.test.InstrumentationTestCase
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.SecretKey
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.dock.QuickActionPreferences
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.settings.LocalSettingsArchive
import org.openvoyah.controls.data.vehicle.LocalHeadlightHistory
import org.openvoyah.controls.data.voice.CloudVoiceSettings
import org.openvoyah.controls.domain.vehicle.ExteriorLamp
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.domain.vehicle.HeadlightObservation
import org.openvoyah.controls.domain.vehicle.LampActivity
import org.openvoyah.controls.domain.vehicle.LampObservation

class SettingsInventoryAuditTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext.applicationContext

    fun testBothProfilesRestoreEveryPortableFamilyAndKeepDeviceDataLocal() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val receipt = File(app.filesDir, "settings-inventory-audit.json")
        if (receipt.exists()) check(receipt.delete())
        val fixture = instrumentation.context.assets.open("settings-inventory-audit.json").use { it.readBytes() }
        val inventory = JSONObject(fixture.decodeToString())
        val rows = inventory.getJSONArray("families")
        val families = List(rows.length()) { rows.getJSONObject(it) }
        val paths = families.map { it.getString("path") }
        val names = families.map { it.getString("area") to it.getString("name") }
        assertEquals("Each archived family has one path", paths.size, paths.toSet().size)
        assertEquals("Each archived family has one name per area", names.size, names.toSet().size)

        val archive = LocalSettingsArchive(app)
        val initialJson = archive.exportJson()
        val initial = archive.prepare(initialJson)
        val sections = JSONObject(initialJson).keys().asSequence().toSet() - setOf("format", "schema")
        val profiles = inventory.getJSONObject("profiles")
        val profileA = profiles.getJSONObject("a")
        val profileB = profiles.getJSONObject("b")
        assertTrue("Both profiles declare all adjustable archive sections", listOf(profileA, profileB).all {
            it.keys().asSequence().toSet() == sections
        })
        val mappedSections = paths.map { it.split('/')[1] }.toSet()
        assertEquals("Each adjustable section is represented in the inventory", sections, mappedSections)
        for (path in paths + SAVED_ROUTINE_PALETTE_PATH) {
            assertTrue("A and B must exercise different values at $path", profileA.valueAt(path) != profileB.valueAt(path))
        }
        for (profile in listOf(profileA, profileB)) {
            val selection = profile.valueAt("/routines/entries/0/desktopLayoutId")
            assertEquals("The selected layout must exist in the same profile",
                profile.valueAt("/desktopLayouts/presets/0/id"), selection)
            val routines = profile.getJSONObject("routines").getJSONArray("entries")
            val shortcut = profile.valueAt("/quickActions/routineShortcutId")
            assertTrue("The dock shortcut must name a routine in the same profile", (0 until routines.length()).any { index ->
                routines.getJSONObject(index).getString("id") == shortcut
            })
        }

        val snapshots = listOf(
            PreferenceSnapshot(preferences(LocalHeadlightHistory.STORE)),
            PreferenceSnapshot(preferences("localization"), setOf("previous")),
            PreferenceSnapshot(preferences(QuickActionPreferences.STORE)),
        )
        val originalDeviceSettings = DeviceSettingsSnapshot.capture(app)
        val deviceA = DeviceProfile(
            settings = DeviceSettingsProfile.A,
            mode = HeadlightMode.AUTOMATIC, time = 1000L, headlamps = LampActivity.ON, highBeam = LampActivity.OFF,
        )
        val deviceB = DeviceProfile(
            settings = DeviceSettingsProfile.B,
            mode = HeadlightMode.OFF, time = 2000L, headlamps = LampActivity.OFF, highBeam = LampActivity.ON,
        )

        try {
            fun installProfile(profile: JSONObject, device: DeviceProfile, label: String): String {
                device.write(app)
                val input = JSONObject(initialJson)
                for (section in sections) input.put(section, profile.get(section))
                archive.apply(archive.prepare(input.toString()))
                device.verify(app, label)
                return LocalSettingsArchive(app).exportJson().also { exported ->
                    assertProfile(profile, JSONObject(exported), paths, label)
                }
            }

            val exportedA = installProfile(profileA, deviceA, "Export A")
            val exportedB = installProfile(profileB, deviceB, "Export B")

            archive.apply(archive.prepare(exportedA))
            assertRestored(profileA, exportedA, paths, "B to A")
            deviceB.verify(app, "B to A")

            deviceA.write(app)
            archive.apply(archive.prepare(exportedB))
            assertRestored(profileB, exportedB, paths, "A to B")
            deviceA.verify(app, "A to B")
        } finally {
            try {
                archive.apply(initial)
            } finally {
                try {
                    originalDeviceSettings.restore(app)
                } finally {
                    snapshots.forEach { it.restore() }
                }
            }
        }

        val fixtureHash = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        receipt.writeText(JSONObject()
            .put("api_level", Build.VERSION.SDK_INT)
            .put("fixture_sha256", fixtureHash)
            .put("portable_families", paths.size)
            .put("portable_families_by_area", JSONObject(names.groupingBy { it.first }.eachCount()))
            .put("verified_families", rows)
            .put("verified_saved_routine_palette", SAVED_ROUTINE_PALETTE_PATH)
            .put("verified_restore_directions", JSONArray(listOf("B to A", "A to B")))
            .put("device_only_families", JSONArray(DEVICE_ONLY_FAMILIES))
            .put("device_only_headlight_observations", JSONArray(listOf("selector", "headlamps", "high beam")))
            .toString(2))
    }

    fun testRoutineLayoutSelectionSurvivesAnOlderArchiveAndClearsOnExplicitNull() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val archive = LocalSettingsArchive(app)
        val originalJson = archive.exportJson()
        val original = archive.prepare(originalJson)
        val fixture = instrumentation.context.assets.open("settings-inventory-audit.json").use {
            JSONObject(it.readBytes().decodeToString())
        }
        val profile = fixture.getJSONObject("profiles").getJSONObject("a")
        val selection = profile.valueAt("/routines/entries/0/desktopLayoutId")
        val key = "desktopLayoutId"
        fun JSONObject.routines() = getJSONObject("routines").getJSONArray("entries")

        try {
            val current = JSONObject(originalJson)
            for (section in listOf("desktopLayouts", "routines")) current.put(section, profile.getJSONObject(section))
            archive.apply(archive.prepare(current.toString()))
            val legacy = JSONObject(archive.exportJson())
            val entries = legacy.routines()
            val reordered = List(entries.length()) { index -> entries.getJSONObject(index).apply { remove(key) } }.reversed()
            val entry = reordered.last()
            val updatedName = "Imported morning grid"
            entry.put("name", updatedName)
            legacy.getJSONObject("routines").put("entries", JSONArray(reordered))
            archive.apply(archive.prepare(legacy.toString()))

            val restored = JSONObject(archive.exportJson()).routines()
            val retained = restored.getJSONObject(reordered.lastIndex)
            assertEquals(entry.getString("id"), retained.getString("id"))
            assertEquals(updatedName, retained.getString("name"))
            assertEquals(selection, retained.getString(key))
            assertTrue("The selection belongs to the matching routine ID", restored.getJSONObject(0).isNull(key))

            entry.put(key, JSONObject.NULL)
            archive.apply(archive.prepare(legacy.toString()))
            val cleared = JSONObject(archive.exportJson()).routines().getJSONObject(reordered.lastIndex)
            assertTrue("An explicit clear must remain in later exports", cleared.has(key) && cleared.isNull(key))
        } finally {
            archive.apply(original)
        }
    }

    fun testQuickActionsIdleCollapseAcceptsLegacySettingsAndRejectsInvalidIntervals() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val store = preferences(QuickActionPreferences.STORE)
        val original = PreferenceSnapshot(store)
        val dock = QuickActionPreferences(app)
        val key = "idleCollapseSeconds"
        val legacy = JSONObject().put("enabled", true).put("collapseAfterUse", false)
        try {
            check(store.edit().putString("settings", legacy.toString()).commit())
            assertEquals(0, dock.exportSettings().getInt(key))
            dock.restoreSettings(JSONObject().put(key, 30))
            dock.restoreSettings(QuickActionPreferences.validateSettings(legacy))
            val retained = dock.exportSettings()
            assertEquals(30, retained.getInt(key))
            assertFalse(retained.getBoolean("collapseAfterUse"))
            for (invalid in listOf(-1, 20, 10.5, "10", false, JSONObject.NULL)) {
                val input = JSONObject().put("enabled", false).put(key, invalid)
                try {
                    QuickActionPreferences.validateSettings(input)
                    fail("Accepted invalid idle collapse interval $invalid")
                } catch (_: IllegalArgumentException) { }
                try {
                    dock.restoreSettings(input)
                    fail("Restored invalid idle collapse interval $invalid")
                } catch (_: IllegalArgumentException) { }
                assertEquals(retained.toString(), dock.exportSettings().toString())
            }
            dock.restoreSettings(JSONObject().put(key, 0))
            assertEquals(0, dock.exportSettings().getInt(key))
        } finally { original.restore() }
    }

    fun testQuickActionsStartupAndCompactLabelsPreserveLegacyChoices() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val store = preferences(QuickActionPreferences.STORE)
        val original = PreferenceSnapshot(store)
        val dock = QuickActionPreferences(app)
        val legacy = JSONObject().put("collapseAfterUse", false).put("showLabels", true)
        try {
            check(store.edit().putString("settings", legacy.toString()).commit())
            val defaults = dock.exportSettings()
            assertEquals("default", defaults.getString("startState"))
            assertEquals("default", defaults.getString("compactLabels"))
            dock.restoreSettings(JSONObject().put("startState", "collapsed").put("compactLabels", "icons"))
            dock.restoreSettings(QuickActionPreferences.validateSettings(legacy))
            val retained = dock.exportSettings()
            assertEquals("collapsed", retained.getString("startState"))
            assertEquals("icons", retained.getString("compactLabels"))
            assertFalse(retained.getBoolean("collapseAfterUse"))
            assertTrue(retained.getBoolean("showLabels"))
            for ((key, value) in listOf("startState" to "icons", "compactLabels" to "collapsed", "startState" to 1, "compactLabels" to false)) {
                try {
                    dock.restoreSettings(JSONObject().put("showLabels", false).put(key, value))
                    fail("Accepted invalid $key")
                } catch (_: IllegalArgumentException) { }
                assertEquals(retained.toString(), dock.exportSettings().toString())
            }
        } finally { original.restore() }
    }

    private fun assertRestored(expected: JSONObject, exported: String, paths: List<String>, label: String) {
        assertArchiveMatches(expected, exported, LocalSettingsArchive(app).exportJson(), paths, label)
    }

    private fun preferences(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    private class PreferenceSnapshot(private val preferences: SharedPreferences, private val keys: Set<String>? = null) {
        private val values = preferences.all.filterKeys { keys == null || it in keys }

        fun restore() {
            val editor = preferences.edit()
            if (keys == null) editor.clear() else keys.forEach { editor.remove(it) }
            for ((key, value) in values) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    else -> error("Unexpected preference type for $key")
                }
            }
            check(editor.commit())
        }
    }

    private class DeviceProfile(
        private val settings: DeviceSettingsProfile,
        mode: HeadlightMode,
        time: Long,
        headlamps: LampActivity,
        highBeam: LampActivity,
    ) {
        private val selector = HeadlightObservation(mode, time)
        private val lamps = mapOf(
            ExteriorLamp.HEADLIGHTS to LampObservation(headlamps, time + 1),
            ExteriorLamp.HIGH_BEAM to LampObservation(highBeam, time + 2),
        )

        fun write(app: Context) {
            settings.write(app)
            val history = LocalHeadlightHistory(app)
            check(history.save(selector))
            for ((lamp, observation) in lamps) check(history.saveLamp(lamp, observation))
        }

        fun verify(app: Context, label: String) {
            settings.verify(app, label)
            val history = LocalHeadlightHistory(app)
            assertEquals("$label changed confirmed selector history", selector, history.read())
            assertEquals("$label changed confirmed lamp history", lamps, history.readLamps())
        }
    }
}

/** Each request is run by a newly started app process in a disposable emulator. */
class SettingsInventoryColdStartTest : InstrumentationTestCase() {
    private enum class Step(val id: String, val readProfile: String? = null, val restoreProfile: String? = null) {
        SEED("seed"), RESTORE_A("restore-a", "b", "a"), RESTORE_B("restore-b", "a", "b"),
        FINISH("finish", "b"), CLEANUP("cleanup"),
    }

    fun testColdProcessStep() {
        val app = instrumentation.targetContext.applicationContext
        val request = File(app.filesDir, "settings-inventory-cold.request")
        // The host only arms this test when it can start and verify each new process.
        if (!request.isFile) return
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_") &&
            Build.VERSION.SDK_INT in setOf(28, 30))
        check(request.length() in 1..64)
        val parts = request.readText().trim().split(' ')
        check(parts.size == 2 && parts[0].matches(Regex("[a-z0-9-]{1,32}")))
        val run = parts[0]
        val step = Step.entries.single { it.id == parts[1] }
        check(request.delete())

        val archive = LocalSettingsArchive(app)
        val checkpoint = AtomicFile(File(app.filesDir, "settings-inventory-cold.checkpoint"))
        val deviceCheckpoint = AtomicFile(File(app.filesDir, "settings-inventory-cold.device-state"))
        val fixture = instrumentation.context.assets.open("settings-inventory-audit.json").use { it.readBytes() }
        val inventory = JSONObject(fixture.decodeToString())
        val families = inventory.getJSONArray("families")
        val paths = List(families.length()) { families.getJSONObject(it).getString("path") }
        val profiles = inventory.getJSONObject("profiles")
        val digest = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        val preservedOnImport = mutableListOf<String>()
        var deviceColdRead: String? = null
        var deviceRestored: String? = null

        fun installDeviceSettings(profile: DeviceSettingsProfile) {
            val portableBefore = canonical(JSONObject(archive.exportJson()))
            profile.write(app)
            profile.verify(app, "Device settings for next process")
            assertTrue("The device-only settings must stay out of the portable archive",
                portableBefore == canonical(JSONObject(archive.exportJson())))
        }

        fun applyPortable(json: String, expectedDeviceSettings: DeviceSettingsProfile?) {
            archive.apply(archive.prepare(json))
            if (expectedDeviceSettings != null) {
                expectedDeviceSettings.verify(app, "Portable import during ${step.id}")
                preservedOnImport.add(expectedDeviceSettings.id)
            }
        }

        if (step == Step.SEED) {
            check(!checkpoint.baseFile.exists())
            val original = archive.exportJson()
            val state = JSONObject().put("run", run).put("fixture", digest).put("original", original)
                .put("device_only_rollback", true)
            // Keep the device-bound encrypted value out of the host-readable marker and receipts.
            writeCheckpoint(deviceCheckpoint, DeviceSettingsSnapshot.capture(app).encode()
                .put("run", run).put("fixture", digest))
            writeCheckpoint(checkpoint, state)
            for (deviceSettings in DeviceSettingsProfile.entries) {
                val name = deviceSettings.id
                installDeviceSettings(deviceSettings)
                val profile = profiles.getJSONObject(name)
                val input = JSONObject(original)
                for (section in profile.keys()) input.put(section, profile.get(section))
                applyPortable(input.toString(), deviceSettings)
                val exported = archive.exportJson()
                assertProfile(profile, JSONObject(exported), paths, "Seed $name")
                state.put(name, exported)
            }
            writeCheckpoint(checkpoint, state)
            deviceRestored = "b"
        } else {
            val state = JSONObject(checkpoint.readFully().decodeToString())
            check(state.getString("run") == run && state.getString("fixture") == digest)
            val originalDeviceSettings = if (state.optBoolean("device_only_rollback")) {
                val deviceState = JSONObject(deviceCheckpoint.readFully().decodeToString())
                check(deviceState.getString("run") == run && deviceState.getString("fixture") == digest)
                DeviceSettingsSnapshot.decode(deviceState)
            } else null
            val observedDeviceSettings = if (originalDeviceSettings != null) {
                step.readProfile?.let { name -> DeviceSettingsProfile.entries.single { it.id == name } }
            } else null
            step.readProfile?.let { name ->
                assertArchiveMatches(profiles.getJSONObject(name), state.getString(name), archive.exportJson(), paths,
                    "Cold read of $name")
            }
            observedDeviceSettings?.let {
                it.verify(app, "Cold read of ${it.id}")
                deviceColdRead = it.id
            }
            val toRestore = state.getString(step.restoreProfile ?: "original")
            if (step.restoreProfile != null) {
                applyPortable(toRestore, observedDeviceSettings)
                if (originalDeviceSettings != null) {
                    val next = DeviceSettingsProfile.entries.single { it.id == step.restoreProfile }
                    installDeviceSettings(next)
                    deviceRestored = next.id
                }
            } else {
                try {
                    applyPortable(toRestore, observedDeviceSettings)
                } finally {
                    originalDeviceSettings?.restore(app)
                }
                assertTrue("The emulator's original archive must be restored",
                    canonical(JSONObject(toRestore)) == canonical(JSONObject(archive.exportJson())))
                checkpoint.delete()
                if (originalDeviceSettings != null) {
                    deviceCheckpoint.delete()
                    deviceRestored = "original"
                }
            }
        }

        File(app.filesDir, "settings-inventory-cold.json").writeText(JSONObject()
            .put("run", run).put("step", step.id).put("pid", Process.myPid()).put("sdk", Build.VERSION.SDK_INT)
            .put("fixture_sha256", digest).put("portable_families", paths.size)
            .put("cold_read", step.readProfile ?: JSONObject.NULL)
            .put("restored", if (step == Step.SEED) "b" else step.restoreProfile ?: "original")
            .put("device_only_families", JSONArray(if (deviceRestored != null) DEVICE_ONLY_FAMILIES else emptyList<String>()))
            .put("device_only_cold_read", deviceColdRead ?: JSONObject.NULL)
            .put("device_only_preserved_on_import", JSONArray(preservedOnImport))
            .put("device_only_restored", deviceRestored ?: JSONObject.NULL)
            .toString(2))
    }

    private fun writeCheckpoint(file: AtomicFile, value: JSONObject) {
        val bytes = value.toString().encodeToByteArray()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        check(file.readFully().contentEquals(bytes)) { "The fixture checkpoint could not be verified" }
    }
}

private const val BRIDGE_PREFS = "bridge"
private const val WINDOW_HOOK = "windowHook"
private const val NAVIGATION_HOOK = "navigationHook"
private const val WINDOW_STRIP = "windowSystemInsets"
private const val CLOUD_VOICE_PREFS = "cloud_voice"
private const val CLOUD_CREDENTIAL = "credential"
private const val CLOUD_KEY_ALIAS = "org.openvoyah.controls.openrouter"
private val DEVICE_ONLY_FAMILIES = listOf("optional online fallback", "navigation feed", "window service", "system panel strip")

private enum class DeviceSettingsProfile(
    val id: String,
    private val windowHook: Boolean,
    private val navigationHook: Boolean,
    private val windowStrip: Boolean,
    private val credential: String?,
) {
    A("a", windowHook = false, navigationHook = true, windowStrip = true, credential = "AuditOnlySyntheticCredentialA2026"),
    B("b", windowHook = true, navigationHook = false, windowStrip = false, credential = null);

    fun write(app: Context) {
        // Direct preference writes keep this fixture independent of each optional service's lifecycle.
        val bridge = app.getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE)
        check(bridge.edit().putBoolean(WINDOW_HOOK, windowHook).putBoolean(NAVIGATION_HOOK, navigationHook)
            .putBoolean(WINDOW_STRIP, windowStrip).commit())
        CloudVoiceSettings(app).save(credential != null, credential.orEmpty())
    }

    fun verify(app: Context, label: String) {
        assertEquals("$label changed window service", windowHook, FridaBridge.windowHookEnabled(app))
        assertEquals("$label changed navigation feed", navigationHook, FridaBridge.navigationHookEnabled(app))
        assertEquals("$label changed the system panel strip", windowStrip, FridaBridge.windowStripEnabled(app))
        val voice = CloudVoiceSettings(app)
        assertTrue("$label changed the device-bound voice setting",
            voice.configured() == (credential != null) && voice.key() == credential)
    }
}

private data class DeviceSettingsSnapshot(
    private val windowHook: Boolean?,
    private val navigationHook: Boolean?,
    private val windowStrip: Boolean?,
    private val encryptedCredential: String?,
    private val cloudKeyExisted: Boolean,
) {
    fun encode() = JSONObject()
        .put(WINDOW_HOOK, windowHook ?: JSONObject.NULL)
        .put(NAVIGATION_HOOK, navigationHook ?: JSONObject.NULL)
        .put(WINDOW_STRIP, windowStrip ?: JSONObject.NULL)
        .put(CLOUD_CREDENTIAL, encryptedCredential ?: JSONObject.NULL)
        .put("cloudKeyExisted", cloudKeyExisted)

    fun restore(app: Context) {
        val bridge = app.getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE).edit()
        for ((key, value) in listOf(WINDOW_HOOK to windowHook, NAVIGATION_HOOK to navigationHook, WINDOW_STRIP to windowStrip)) {
            if (value == null) bridge.remove(key) else bridge.putBoolean(key, value)
        }
        check(bridge.commit())
        val voice = app.getSharedPreferences(CLOUD_VOICE_PREFS, Context.MODE_PRIVATE).edit()
        if (encryptedCredential == null) voice.remove(CLOUD_CREDENTIAL) else voice.putString(CLOUD_CREDENTIAL, encryptedCredential)
        check(voice.commit())
        val keyStore = openKeyStore()
        if (!cloudKeyExisted && keyStore.containsAlias(CLOUD_KEY_ALIAS)) keyStore.deleteEntry(CLOUD_KEY_ALIAS)
        check(capture(app) == this) { "The original device-only settings must be restored" }
    }

    companion object {
        fun capture(app: Context): DeviceSettingsSnapshot {
            val bridge = app.getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE).all
            val voice = app.getSharedPreferences(CLOUD_VOICE_PREFS, Context.MODE_PRIVATE).all
            val keyStore = openKeyStore()
            val keyExisted = keyStore.containsAlias(CLOUD_KEY_ALIAS)
            check(!keyExisted || keyStore.getKey(CLOUD_KEY_ALIAS, null) is SecretKey) {
                "The existing voice key cannot be preserved by the fixture"
            }
            return DeviceSettingsSnapshot(
                optionalValue(bridge[WINDOW_HOOK], WINDOW_HOOK),
                optionalValue(bridge[NAVIGATION_HOOK], NAVIGATION_HOOK),
                optionalValue(bridge[WINDOW_STRIP], WINDOW_STRIP),
                optionalValue(voice[CLOUD_CREDENTIAL], CLOUD_CREDENTIAL),
                keyExisted,
            )
        }

        fun decode(value: JSONObject) = DeviceSettingsSnapshot(
            optionalValue(value.get(WINDOW_HOOK), WINDOW_HOOK),
            optionalValue(value.get(NAVIGATION_HOOK), NAVIGATION_HOOK),
            optionalValue(value.opt(WINDOW_STRIP), WINDOW_STRIP),
            optionalValue(value.get(CLOUD_CREDENTIAL), CLOUD_CREDENTIAL),
            value.getBoolean("cloudKeyExisted"),
        )

        private fun openKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private inline fun <reified T> optionalValue(value: Any?, key: String): T? =
            if (value == null || value === JSONObject.NULL) null
            else checkNotNull(value as? T) { "Unexpected preference type for $key" }
    }
}

private fun assertArchiveMatches(expected: JSONObject, exported: String, read: String, paths: List<String>, label: String) {
    val before = JSONObject(exported)
    val actual = JSONObject(read)
    assertProfile(expected, actual, paths, label)
    val changed = (before.keys().asSequence() + actual.keys().asSequence()).toSet().filter { section ->
        canonical(before.opt(section)) != canonical(actual.opt(section))
    }
    assertTrue("$label changed exported sections: $changed", changed.isEmpty())
}

private const val SAVED_ROUTINE_PALETTE_PATH = "/routines/entries/1/palette"

private fun assertProfile(expected: JSONObject, actual: JSONObject, paths: List<String>, label: String) {
    val changed = (paths + SAVED_ROUTINE_PALETTE_PATH).filter { expected.valueAt(it) != actual.valueAt(it) }
    assertTrue("$label did not match the fixture at $changed", changed.isEmpty())
}

private fun JSONObject.valueAt(path: String): Any? {
    require(path.startsWith('/'))
    val value = path.substring(1).split('/').fold(this as Any) { parent, element ->
        val token = element.replace("~1", "/").replace("~0", "~")
        when (parent) {
            is JSONObject -> checkNotNull(parent.opt(token)) { "Missing member at $path" }
            is JSONArray -> checkNotNull(parent.opt(token.toInt())) { "Missing item at $path" }
            else -> error("Invalid parent at $path")
        }
    }
    return canonical(value)
}

private fun canonical(value: Any?): Any? = when (value) {
    is JSONObject -> value.keys().asSequence().associateWith { canonical(value.get(it)) }
    is JSONArray -> List(value.length()) { canonical(value.get(it)) }
    JSONObject.NULL -> null
    else -> value
}
