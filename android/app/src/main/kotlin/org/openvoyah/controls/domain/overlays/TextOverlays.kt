package org.openvoyah.controls.domain.overlays

/**
 * Text overlays for the factory apps the Frida agent cannot attach to (32-bit processes): one resource overlay per app
 * and language, built by tools/build_text_overlays.py and bundled under assets/text-overlays/<language>/. The device
 * side is tools/text-overlays.sh, the same script the PC uses; this file only builds the root commands and reads the
 * answers. See docs/FIRMWARE.md, "Resource overlays for 32-bit apps".
 *
 * The root channel sends a command through a Telnet PTY (lines up to 4 KiB) and waits at most 30 s for it, so every
 * line built here stays below [MAX_LINE] and every change runs as a detached job that later calls only read back.
 */
data class BundledOverlay(
    val file: String, val pkg: String, val target: String, val sha256: String, val targetSha256: String, val texts: Int,
)

/** [fingerprint] and [buildDate] are the firmware build the set was made for ("-" when it was not pinned). */
data class BundledSet(
    val language: String, val fingerprint: String, val buildDate: String, val overlays: List<BundledOverlay>,
) {
    val byPackage: Map<String, BundledOverlay> get() = overlays.associateBy { it.pkg }
    /** An unpinned set ("-") is never taken for a head unit: it could meet another build of the factory apps. */
    fun builtFor(fingerprint: String, buildDate: String): Boolean =
        this.fingerprint != "-" && this.buildDate != "-" && this.fingerprint == fingerprint && this.buildDate == buildDate
}

/** One of this project's overlay files in /vendor/overlay; [state] is the overlay manager's, empty when it is unknown. */
data class DeviceOverlay(val pkg: String, val sha256: String, val state: String, val enabled: Boolean) {
    val known: Boolean get() = state.isNotEmpty()
    /** Android applies the overlay: enabled, and its target is installed and matched. */
    val active: Boolean get() = state == "STATE_ENABLED"
}

data class DeviceOverlays(
    val writable: Boolean, val overlays: List<DeviceOverlay>, val rollbackAvailable: Boolean,
    /** The set was placed during the current start; Android has not read it yet. */
    val installedThisBoot: Boolean = false,
    /** An earlier change stopped in the middle; installing again or rolling back cleans up. */
    val interrupted: Boolean = false,
) {
    /** The language of the installed set, or null when none or a mixed set is in place. */
    val language: String? get() = overlays.map { TextOverlayCatalog.languageOf(it.pkg) }.distinct().singleOrNull()
}

enum class OverlayWarning(val marker: String) {
    /** A failed install could not put the previous set back; some or none of the overlays are in place. */
    RESTORE_FAILED("OVC_OVERLAY_RESTORE_FAILED"),
    /** The set in place did not match its record, so the rollback copy was kept instead of replaced. */
    PREVIOUS_KEPT("OVC_OVERLAY_PREVIOUS_KEPT"),
    /** /vendor stayed writable after the change (an app still maps a deleted overlay); the next start fixes it. */
    VENDOR_STILL_WRITABLE("OVC_OVERLAY_VENDOR_STILL_WRITABLE"),
    /** An earlier change stopped in the middle. */
    INTERRUPTED("OVC_OVERLAY_INTERRUPTED"),
}

enum class OverlayPhase {
    /** No overlay of this project is in place. */
    NONE,
    /** Files are in place, but the package manager reads them only at the next start. */
    NEEDS_RESTART,
    /** Android did not take some overlays at the last start; reinstalling or removing them is the way out. */
    IGNORED,
    /** Known to Android, but some overlays the owner wants are still off (the first start after an install). */
    NEEDS_ENABLE,
    /** Every overlay the owner wants is on. */
    ACTIVE,
}

data class OverlaySummary(
    val language: String?, val phase: OverlayPhase, val total: Int, val active: Int,
    /** The bundled set of the installed language differs from the files in place (Controls was updated). */
    val updateAvailable: Boolean,
)

object TextOverlayCatalog {
    const val PREFIX = "org.openvoyah.controls.overlay."
    const val STATE = "/data/adb/open-voyah-controls-overlays"
    /** The longest line a command may carry; the PTY drops what goes past 4095 bytes. */
    const val MAX_LINE = 1024
    const val EXIT = "OVC_TEXT_OVERLAYS_EXIT="
    private const val END = "OVC_TEXT_OVERLAYS"
    private const val MANIFEST_END = "OVC_TEXT_OVERLAYS_MANIFEST"
    private const val ARGS_END = "OVC_TEXT_OVERLAYS_ARGS"
    private val language = Regex("[a-z]{2,3}")
    private val packageName = Regex("org\\.openvoyah\\.controls\\.overlay\\.[a-z]{2,3}(\\.[a-z0-9]+)+")
    private val target = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val sha = Regex("[0-9a-f]{64}")
    private val fingerprintText = Regex("-|[A-Za-z0-9_.:/+,-]{1,200}")
    private val buildDateText = Regex("-|[0-9]{1,12}")
    private val folderText = Regex("/[A-Za-z0-9_./-]+")
    val writeModes = setOf("install", "enable", "disable", "rollback", "remove")

    fun validLanguage(value: String) = language.matches(value)
    fun validPackage(value: String) = packageName.matches(value)
    fun languageOf(pkg: String): String = pkg.removePrefix(PREFIX).substringBefore('.')

    /**
     * Reads overlays.tsv: a "pin <fingerprint> <build date>" line, then one row per overlay with file, package, target,
     * SHA-256, target APK SHA-256 and number of texts. Any malformed line rejects the whole set.
     */
    fun parse(language: String, table: String): BundledSet {
        require(validLanguage(language)) { "Unexpected overlay language" }
        val lines = table.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        val pin = lines.firstOrNull()?.split(' ')
        require(pin != null && pin.size == 3 && pin[0] == "pin" && fingerprintText.matches(pin[1]) && buildDateText.matches(pin[2])) {
            "The overlay table names no firmware build"
        }
        val overlays = lines.drop(1).map { line ->
            val cells = line.split('\t')
            require(cells.size == 6) { "Unexpected overlay row" }
            val (file, pkg, app, digest, appDigest) = cells
            require(validPackage(pkg) && file == "$pkg.apk" && languageOf(pkg) == language) { "Unexpected overlay $pkg" }
            require(target.matches(app) && sha.matches(digest) && sha.matches(appDigest)) { "Unexpected overlay row for $pkg" }
            val texts = requireNotNull(cells[5].toIntOrNull()?.takeIf { it > 0 }) { "Unexpected overlay row for $pkg" }
            BundledOverlay(file, pkg, app, digest, appDigest, texts)
        }
        require(overlays.map { it.pkg }.toSet().size == overlays.size) { "Duplicate overlay" }
        return BundledSet(language, pin[1], pin[2], overlays)
    }

    /** Reads the "status" answer of text-overlays.sh. */
    fun parseStatus(reply: String): DeviceOverlays {
        var writable = false
        var rollback = false
        var thisBoot = false
        var interrupted = false
        val files = linkedMapOf<String, String>()
        val states = mutableMapOf<String, Pair<String, Boolean>>()
        for (line in reply.lineSequence().map { it.trim() }) {
            val parts = line.split(' ')
            when {
                line == "OVC_OVERLAY_DIR_WRITABLE=yes" -> writable = true
                line == "OVC_OVERLAY_ROLLBACK_AVAILABLE" -> rollback = true
                line == "OVC_OVERLAY_INSTALLED_THIS_BOOT" -> thisBoot = true
                line == "OVC_OVERLAY_INTERRUPTED" -> interrupted = true
                parts[0] == "OVC_VENDOR_OVERLAY_FILE" && parts.size >= 3 -> {
                    val pkg = parts[1].removeSuffix(".apk")
                    if (parts[1].endsWith(".apk") && validPackage(pkg)) files[pkg] = parts[2].removePrefix("sha256=")
                }
                parts[0] == "OVC_OVERLAY" && parts.size >= 4 && validPackage(parts[1]) ->
                    states[parts[1]] = parts[2].removePrefix("state=") to (parts[3] == "enabled=true")
            }
        }
        val overlays = files.map { (pkg, digest) ->
            val (state, enabled) = states[pkg] ?: ("" to false)
            DeviceOverlay(pkg, digest, state, enabled)
        }
        return DeviceOverlays(writable, overlays, rollback, thisBoot, interrupted)
    }

    /** [disabled] holds the target packages the owner turned off. */
    fun summarize(device: DeviceOverlays, bundled: Map<String, BundledSet>, disabled: Set<String>): OverlaySummary {
        if (device.overlays.isEmpty()) return OverlaySummary(null, OverlayPhase.NONE, 0, 0, false)
        val language = device.language
        val set = language?.let { bundled[it] }
        val phase = when {
            device.installedThisBoot -> OverlayPhase.NEEDS_RESTART
            // Placed before this start, yet unknown to Android: it skipped them (another firmware build, a bad file).
            device.overlays.any { !it.known } -> OverlayPhase.IGNORED
            wanted(device, set, disabled).isNotEmpty() -> OverlayPhase.NEEDS_ENABLE
            else -> OverlayPhase.ACTIVE
        }
        // Apps whose factory APK did not match were skipped on purpose; only the overlays in place are compared.
        val outdated = set != null && device.overlays.any { set.byPackage[it.pkg]?.sha256 != it.sha256 }
        return OverlaySummary(language, phase, device.overlays.size, device.overlays.count { it.active }, outdated)
    }

    /** Overlays Android knows that should be on and are not. */
    fun wanted(device: DeviceOverlays, set: BundledSet?, disabled: Set<String>): List<String> =
        if (device.installedThisBoot) emptyList()
        else device.overlays.filter { it.known && !it.enabled && target(it.pkg, set) !in disabled }.map { it.pkg }

    /** Overlays that are on although the owner turned their app off. */
    fun unwanted(device: DeviceOverlays, set: BundledSet?, disabled: Set<String>): List<String> =
        device.overlays.filter { it.known && it.enabled && target(it.pkg, set) in disabled }.map { it.pkg }

    fun target(pkg: String, set: BundledSet?): String? = set?.byPackage?.get(pkg)?.target

    private fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"

    private fun heredoc(path: String, end: String, body: String): String {
        val text = body.replace("\r", "").trimEnd('\n')
        require(text.lines().none { it == end }) { "Unexpected overlay script" }
        return "cat > $path <<'$end'\n$text\n$end\n"
    }

    private fun failed(message: String) = "{ echo ${quote(message)}; echo \"${EXIT}1\"; exit 0; }"

    /** The state folder, root's own: the staged files cannot be swapped by another user between copy and check. */
    private val stateFolder = "umask 077\n[ -d $STATE ] || mkdir -m 700 $STATE\n" +
        "[ ! -L $STATE ] && [ \"\$(stat -c %u:%a $STATE)\" = 0:700 ] || ${failed("Unexpected $STATE.")}\n"

    /**
     * Refuses while a job runs. job.pid holds the pid and the boot id it was started in: a pid from an earlier start,
     * one that has exited, or one that now belongs to another program does not block anything.
     */
    private val alive = "ovc_boot=\$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\novc_alive=\n" +
        "if [ -f $STATE/job.pid ]; then\n" +
        "read -r ovc_pid ovc_pid_boot < $STATE/job.pid || true\n" +
        "case \"\$ovc_pid\" in ''|*[!0-9]*) ovc_pid= ;; esac\n" +
        "if [ -n \"\$ovc_pid\" ] && [ \"\$ovc_pid_boot\" = \"\$ovc_boot\" ] && kill -0 \"\$ovc_pid\" 2>/dev/null && " +
        "cat /proc/\$ovc_pid/cmdline 2>/dev/null | tr '\\0' ' ' | grep -q text-overlays.sh; then ovc_alive=1; fi\nfi\n"
    private val notRunning = alive +
        "if [ -n \"\$ovc_alive\" ] && [ ! -f $STATE/job.status ]; then ${failed("Another overlay change is still running.")}; fi\n"

    /**
     * The read-only state query. It reads "dumpsys overlay" once and hashes the small overlay files, well within the
     * channel's time; the firmware is not compared here (Controls runs on the unit it asks about).
     */
    fun status(script: String, fingerprint: String): String {
        val body = script.replace("\r", "").trimEnd('\n')
        require(body.lines().none { it == END }) { "Unexpected overlay script" }
        return "sh -s -- status ${quote(fingerprint)} - 2>&1 <<'$END'\n$body\n$END\necho \"$EXIT\$?\"\n"
    }

    /**
     * Copies a set from the app's storage ([folder]) into root's staging folder with its manifest. The script checks
     * every SHA-256 there, after the copy, and compares the manifest's pin with the firmware build. Refused while a
     * job runs, which may be reading the staging folder.
     */
    fun stage(set: BundledSet, folder: String): String {
        require(set.overlays.isNotEmpty()) { "Empty overlay set" }
        require(folderText.matches(folder)) { "Unexpected overlay folder" }
        val manifest = buildString {
            append("pin ").append(set.fingerprint).append(' ').append(set.buildDate).append('\n')
            for (overlay in set.overlays) {
                append("overlay ${overlay.sha256} ${overlay.file} ${overlay.target} ${overlay.targetSha256}\n")
            }
        }
        val copies = set.overlays.joinToString("") { overlay ->
            "cp ${quote("$folder/${overlay.file}")} $STATE/stage/${overlay.file} || ${failed("${overlay.file} could not be staged.")}\n"
        }
        return stateFolder + notRunning +
            "rm -rf $STATE/stage && mkdir -m 700 $STATE/stage || ${failed("Could not prepare the staging folder.")}\n" +
            heredoc("$STATE/stage/manifest", MANIFEST_END, manifest) + copies + "echo \"${EXIT}0\"\n"
    }

    private val token = Regex("[0-9a-f]{16,64}")

    /**
     * Writes the script next to the state and starts [mode] detached, so a slow step (hashing the factory APKs, the
     * writes in /vendor) is not cut off by the channel's deadline. The job ends by writing "<[job]> <exit status>";
     * [poll] with the same [job] token reads it back, so an answer is never taken from another change.
     */
    fun start(script: String, mode: String, fingerprint: String, job: String, packages: List<String> = emptyList()): String {
        require(mode in writeModes) { "Unexpected overlay mode" }
        require(token.matches(job)) { "Unexpected job token" }
        require(packages.all(::validPackage)) { "Unexpected overlay argument" }
        require(mode == "enable" || mode == "disable" || packages.isEmpty()) { "Unexpected overlay argument" }
        val runner = "$STATE/text-overlays.sh"
        // Package names go one per line into a file: 33 of them on the command line would pass the PTY's limit.
        val run = "sh $runner \"\$@\" \$(cat $STATE/job.args) > $STATE/job.log 2>&1; echo \"$job \$?\" > $STATE/job.status"
        return stateFolder + notRunning + heredoc(runner, END, script) + "chmod 700 $runner\n" +
            heredoc("$STATE/job.args", ARGS_END, packages.joinToString("\n")) +
            "rm -f $STATE/job.log $STATE/job.status $STATE/job.pid\n" +
            "nohup sh -c '$run' ovc $mode ${quote(fingerprint)} - </dev/null >/dev/null 2>&1 &\n" +
            "echo \"\$! \$ovc_boot\" > $STATE/job.pid\necho OVC_TEXT_OVERLAYS_STARTED\n"
    }

    /**
     * The [job]'s answer once it has finished: the status is read first, then the log it completed, then the exit
     * marker. While it runs only OVC_TEXT_OVERLAYS_RUNNING comes back; a status of another job is reported as such.
     */
    fun poll(job: String): String {
        require(token.matches(job)) { "Unexpected job token" }
        return alive + "if [ -f $STATE/job.status ]; then\n" +
            "read -r ovc_job ovc_status < $STATE/job.status || true\n" +
            "if [ \"\$ovc_job\" = $job ]; then cat $STATE/job.log 2>/dev/null; echo \"$EXIT\$ovc_status\"; " +
            "else echo OVC_TEXT_OVERLAYS_REPLACED; echo 'Another overlay change replaced this one.'; echo \"${EXIT}1\"; fi\n" +
            "elif [ -n \"\$ovc_alive\" ]; then echo OVC_TEXT_OVERLAYS_RUNNING\n" +
            "else echo 'The overlay change stopped before it finished; check the state before repeating it.'; echo \"${EXIT}1\"; fi\n"
    }

    /** The exit status (-1 while the marker is missing) and the answer without the marker. */
    fun outcome(reply: String): Pair<Int, String> {
        val lines = reply.lines()
        val marker = lines.indexOfLast { it.trim().startsWith(EXIT) }
        val status = if (marker < 0) -1 else lines[marker].trim().removePrefix(EXIT).toIntOrNull() ?: -1
        return status to lines.filterIndexed { index, _ -> index != marker }.joinToString("\n").trim()
    }

    /** The line to show when the script refused or failed. */
    fun failure(text: String): String =
        text.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("OVC_") } ?: text.trim()

    /** Apps the install left out because their factory APK is missing or another build. */
    /** Markers of text-overlays.sh that the owner must hear about, success or not. */
    fun warnings(text: String): Set<OverlayWarning> {
        val lines = text.lines().map { it.trim() }.toSet()
        return OverlayWarning.entries.filter { it.marker in lines }.toSet()
    }

    fun skipped(text: String): List<String> = text.lines().map { it.trim().split(' ') }
        .filter { it.size >= 2 && it[0] == "OVC_OVERLAY_SKIPPED" }.map { it[1].removeSuffix(".apk") }
}
