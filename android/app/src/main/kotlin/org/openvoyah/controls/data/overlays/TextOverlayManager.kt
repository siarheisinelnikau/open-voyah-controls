package org.openvoyah.controls.data.overlays

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.overlays.BundledSet
import org.openvoyah.controls.domain.overlays.DeviceOverlays
import org.openvoyah.controls.domain.overlays.OverlaySummary
import org.openvoyah.controls.domain.overlays.OverlayWarning
import org.openvoyah.controls.domain.overlays.TextOverlayCatalog

/**
 * Settings → Factory apps language: the text overlay sets bundled in this APK (assets/text-overlays/<language>/) and
 * the overlays in /vendor/overlay. The language and the apps the owner turned off are device-bound choices kept here;
 * the overlay manager's own state is read back each time. Changes run as detached jobs of text-overlays.sh (see
 * [TextOverlayCatalog.start]) that this class polls until they finish.
 */
class TextOverlayManager(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("text_overlays", Context.MODE_PRIVATE)

    data class Snapshot(
        val bundled: Map<String, BundledSet>, val device: DeviceOverlays, val summary: OverlaySummary,
        val disabled: Set<String>, val lastBoot: String?,
    ) {
        /** The set of the installed language, or null when this build does not carry it. */
        val installedSet: BundledSet? get() = summary.language?.let { bundled[it] }
    }

    /** Languages this build carries, in a stable order. A set that cannot be read is left out. */
    fun bundled(): Map<String, BundledSet> {
        val languages = app.assets.list(ASSETS).orEmpty().filter(TextOverlayCatalog::validLanguage).sorted()
        return languages.mapNotNull { language ->
            try {
                val table = app.assets.open("$ASSETS/$language/overlays.tsv").use { it.readBytes().decodeToString() }
                TextOverlayCatalog.parse(language, table).takeIf { it.overlays.isNotEmpty() }
            } catch (error: Exception) {
                ControlsLog.warn("text_overlays", "outcome", "bundle_unreadable", "language", language)
                null
            }
        }.associateBy { it.language }
    }

    /** True when [set] was built for the firmware build this head unit runs. */
    fun builtForThisUnit(set: BundledSet): Boolean = set.builtFor(Build.FINGERPRINT, (Build.TIME / 1000).toString())

    fun disabledApps(): Set<String> = prefs.getStringSet(DISABLED, emptySet()).orEmpty().toSet()

    @Throws(IOException::class)
    fun snapshot(): Snapshot {
        val bundled = bundled()
        val device = TextOverlayCatalog.parseStatus(checked(bridge().textOverlayStatus()))
        val disabled = disabledApps()
        return Snapshot(bundled, device, TextOverlayCatalog.summarize(device, bundled, disabled), disabled,
            prefs.getString(BOOT_RESULT, null))
    }

    /** Places the [language] set in /vendor/overlay; Android reads it at the next start. */
    @Throws(IOException::class)
    fun install(language: String): String {
        val set = bundled()[language] ?: throw IOException(UiText.get(R.string.text_overlays_none_bundled))
        if (!builtForThisUnit(set)) throw IOException(UiText.get(R.string.text_overlays_other_firmware))
        val folder = File(app.filesDir, "$ASSETS/${set.language}")
        try {
            extract(set, folder)
            checked(bridge().textOverlayStage(set, folder.absolutePath))
        } finally {
            folder.deleteRecursively()
        }
        val answer = job("install")
        prefs.edit().putString(LANGUAGE, language).remove(BOOT_RESULT).apply()
        val skipped = TextOverlayCatalog.skipped(answer)
        ControlsLog.info("text_overlays", "outcome", "installed", "language", language,
            "count", set.overlays.size - skipped.size, "skipped", skipped.size)
        val placed = UiText.get(R.string.text_overlays_installed).format(set.overlays.size - skipped.size)
        val parts = listOf(placed) + (if (skipped.isEmpty()) emptyList()
            else listOf(UiText.get(R.string.text_overlays_skipped).format(skipped.size))) + warnings(answer)
        return parts.joinToString("\n")
    }

    /** Turns every overlay off and deletes this project's files from /vendor/overlay. */
    @Throws(IOException::class)
    fun remove(): String {
        val answer = job("remove")
        prefs.edit().remove(LANGUAGE).remove(BOOT_RESULT).apply()
        ControlsLog.info("text_overlays", "outcome", "removed")
        return (listOf(UiText.get(R.string.text_overlays_removed)) + warnings(answer)).joinToString("\n")
    }

    /** Puts back the set that was in place before the last install. */
    @Throws(IOException::class)
    fun rollback(): String {
        val answer = job("rollback")
        ControlsLog.info("text_overlays", "outcome", "rolled_back")
        return (listOf(UiText.get(R.string.text_overlays_rolled_back)) + warnings(answer)).joinToString("\n")
    }

    /** Turns the overlay of one factory app on or off at once, and remembers the choice for later installs. */
    @Throws(IOException::class)
    fun setApp(target: String, enabled: Boolean): String {
        val snapshot = snapshot()
        val overlay = snapshot.device.overlays.firstOrNull { TextOverlayCatalog.target(it.pkg, snapshot.installedSet) == target }
        // The switch changes Android first; the remembered choice follows only when that worked.
        if (overlay != null && overlay.known && !snapshot.device.installedThisBoot) {
            job(if (enabled) "enable" else "disable", listOf(overlay.pkg))
        }
        val disabled = disabledApps().toMutableSet()
        if (enabled) disabled.remove(target) else disabled.add(target)
        prefs.edit().putStringSet(DISABLED, disabled).apply()
        return UiText.get(if (enabled) R.string.text_overlays_app_on else R.string.text_overlays_app_off)
    }

    /**
     * Brings the overlay manager in line with the owner's choices: overlays Android knows are turned on, except for
     * the apps the owner turned off. Runs from its own job after each start (the first start after an install finds
     * them off) and from the settings page. Returns the number of overlays changed.
     */
    @Throws(IOException::class)
    fun reconcile(): Int {
        val snapshot = snapshot()
        val set = snapshot.installedSet
        val on = TextOverlayCatalog.wanted(snapshot.device, set, snapshot.disabled)
        val off = TextOverlayCatalog.unwanted(snapshot.device, set, snapshot.disabled)
        if (on.isNotEmpty()) job("enable", on)
        if (off.isNotEmpty()) job("disable", off)
        if (on.isNotEmpty() || off.isNotEmpty()) {
            ControlsLog.info("text_overlays", "outcome", "reconciled", "enabled", on.size, "disabled", off.size)
        }
        return on.size + off.size
    }

    /** Remembers how the job after a start went, for the settings page; null clears it. */
    fun recordBootResult(failure: String?) {
        prefs.edit().apply { if (failure == null) remove(BOOT_RESULT) else putString(BOOT_RESULT, failure) }.apply()
    }

    /** Restarts the head unit through the parked-only system restart. */
    @Throws(IOException::class)
    fun restart(): String {
        bridge().rebootSystem()
        return UiText.get(R.string.text_overlays_restarting)
    }

    private fun bridge() = FridaBridge(app)

    /** The owner's words for the warnings a finished change reported. */
    private fun warnings(answer: String): List<String> = TextOverlayCatalog.warnings(answer).mapNotNull {
        when (it) {
            OverlayWarning.PREVIOUS_KEPT -> UiText.get(R.string.text_overlays_previous_kept)
            OverlayWarning.VENDOR_STILL_WRITABLE -> UiText.get(R.string.text_overlays_vendor_writable)
            OverlayWarning.INTERRUPTED -> UiText.get(R.string.text_overlays_interrupted_cleaned)
            OverlayWarning.RESTORE_FAILED -> null
        }
    }

    /** Starts [mode] on the head unit and waits for its answer; each request stays far below the channel's deadline. */
    @Throws(IOException::class)
    private fun job(mode: String, packages: List<String> = emptyList()): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        val started = checked(bridge().textOverlayStart(mode, token, packages))
        if (!started.contains("OVC_TEXT_OVERLAYS_STARTED")) throw IOException(TextOverlayCatalog.failure(started))
        val deadline = SystemClock.elapsedRealtime() + JOB_LIMIT_MILLIS
        while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("Interrupted")
            val (status, text) = TextOverlayCatalog.outcome(bridge().textOverlayPoll(token))
            if (status >= 0) {
                if (status == 0) return text
                // A failed install that could not restore leaves an incomplete set: say that, not the last line.
                if (OverlayWarning.RESTORE_FAILED in TextOverlayCatalog.warnings(text)) {
                    throw IOException(UiText.get(R.string.text_overlays_restore_failed))
                }
                throw IOException(TextOverlayCatalog.failure(text))
            }
            if (SystemClock.elapsedRealtime() > deadline) throw IOException(UiText.get(R.string.text_overlays_job_timeout))
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    @Throws(IOException::class)
    private fun checked(reply: String): String {
        val (status, text) = TextOverlayCatalog.outcome(reply)
        if (status != 0) throw IOException(TextOverlayCatalog.failure(text))
        return text
    }

    /** Copies one set to the app's storage, where the root channel copies it from, and checks every SHA-256. */
    @Throws(IOException::class)
    private fun extract(set: BundledSet, folder: File) {
        folder.deleteRecursively()
        if (!folder.mkdirs()) throw IOException("Cannot prepare $folder")
        for (overlay in set.overlays) {
            val file = File(folder, overlay.file)
            app.assets.open("$ASSETS/${set.language}/${overlay.file}").use { input -> file.outputStream().use { input.copyTo(it) } }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            if (digest != overlay.sha256) throw IOException("${overlay.file} does not match its SHA-256")
        }
    }

    companion object {
        const val ASSETS = "text-overlays"
        private const val DISABLED = "disabled_apps"
        private const val LANGUAGE = "language"
        private const val BOOT_RESULT = "boot_result"
        private const val POLL_MILLIS = 1_000L
        /** Hashing every factory APK and writing the set takes well under a minute on the 97C. */
        private const val JOB_LIMIT_MILLIS = 180_000L

        /** The language's own name, capitalised ("Русский", "English", "Қазақ тілі"). */
        fun languageName(language: String): String {
            val locale = Locale(language)
            return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
        }
    }
}
