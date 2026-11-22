package org.openvoyah.controls.domain.audio

/** What can lower media: the voice popup while the microphone records, and a phone call. */
enum class DuckTrigger { LISTENING, CALL }

/** The owner's choice in Voice settings → Sound. [levelPercent] is the share of the current media level that stays. */
data class DuckSettings(val listening: Boolean, val calls: Boolean, val levelPercent: Int) {
    fun allows(trigger: DuckTrigger): Boolean = when (trigger) {
        DuckTrigger.LISTENING -> listening
        DuckTrigger.CALL -> calls
    }

    companion object {
        @JvmField val LEVELS: List<Int> = listOf(0, 10, 20, 35)
        const val DEFAULT_LEVEL = 20
        @JvmField val DEFAULT = DuckSettings(listening = true, calls = true, levelPercent = DEFAULT_LEVEL)

        @JvmStatic fun validLevel(value: Int): Boolean = value in LEVELS
    }
}

/** The media level Controls sets once per head unit boot, as a share of the stream's range. */
data class StartupVolume(val enabled: Boolean, val percent: Int) {
    companion object {
        const val DEFAULT_PERCENT = 10
        const val MIN_PERCENT = 0
        const val MAX_PERCENT = 100
        @JvmField val DEFAULT = StartupVolume(enabled = true, percent = DEFAULT_PERCENT)

        @JvmStatic fun validPercent(value: Int): Boolean = value in MIN_PERCENT..MAX_PERCENT
    }
}

/**
 * How long after boot the startup level may still be set. Controls starts with the boot broadcast, well inside it;
 * a later start in the same boot would override a level the owner has already chosen.
 */
const val STARTUP_VOLUME_WINDOW_MS = 3 * 60_000L

fun startupLevel(minimum: Int, maximum: Int, percent: Int): Int =
    (minimum + Math.round((maximum - minimum) * percent / 100f)).coerceIn(minimum, maximum)

/** Once per boot ([bootCount] from `Settings.Global.BOOT_COUNT`), and only shortly after it. */
fun startupVolumeDue(settings: StartupVolume, bootCount: Int?, appliedBoot: Int?, uptimeMs: Long): Boolean =
    settings.enabled && bootCount != null && bootCount != appliedBoot && uptimeMs <= STARTUP_VOLUME_WINDOW_MS

/** Written before the first volume change, so a process killed while media is low can put it back on the next start. */
data class DuckRecord(val saved: Int, val ducked: Int)

interface DuckStore {
    fun load(): DuckRecord?
    fun save(record: DuckRecord)
    fun clear()
}

/**
 * The media level while listening or calling, as a strong share of the current level. A level above the minimum never
 * drops to the minimum unless the owner chose silence (0 %), so a ducked stream still reads as "not muted".
 */
fun duckedLevel(current: Int, minimum: Int, levelPercent: Int): Int {
    if (current <= minimum) return current
    if (levelPercent <= 0) return minimum
    val scaled = minimum + (current - minimum) * levelPercent / 100
    return scaled.coerceAtLeast(minimum + 1).coerceAtMost(current)
}

/** Intermediate levels of a short fade from [from] to [to], excluding [from] and ending with [to]. */
fun rampLevels(from: Int, to: Int, maxSteps: Int): List<Int> {
    if (from == to) return emptyList()
    val distance = kotlin.math.abs(to - from)
    val steps = maxSteps.coerceIn(1, distance)
    return (1..steps).map { step -> from + (to - from) * step / steps }.distinct()
}

/** The OEM Bluetooth phone writes its HFP call state to `Settings.Global phone_call_status`; 131 means no call. */
fun phoneCallActive(status: Int): Boolean = status in 128..135 && status != PHONE_CALL_TERMINATED

const val PHONE_CALL_TERMINATED = 131

/**
 * The single owner of a media duck. It never fights the owner: a level it did not set means someone changed the
 * volume (a knob, a steering wheel key, a voice command), and that level stays. Not thread-safe; the platform
 * controller calls it from one thread.
 *
 * Every method that returns a level asks the caller to fade media to it and to report each level it sets through
 * [applied], and the final one through [reached].
 */
class MediaDuckMachine(private val store: DuckStore) {
    private val active = mutableSetOf<DuckTrigger>()
    private var record: DuckRecord? = null
    private var lastSet: Int? = null
    private var restoring = false
    /** Someone set their own level during this episode; nothing ducks again until every trigger has ended. */
    private var userOwned = false

    val ducked: Boolean get() = record != null
    val triggers: Set<DuckTrigger> get() = active.toSet()

    fun start(trigger: DuckTrigger, current: Int, minimum: Int, settings: DuckSettings): Int? {
        if (!settings.allows(trigger) || !active.add(trigger) || userOwned) return null
        val existing = record
        if (existing != null) {
            // An overlapping trigger, or a new one while the level was coming back up.
            if (!owns(current)) return null
            restoring = false
            return existing.ducked.takeIf { it != current }
        }
        val target = duckedLevel(current, minimum, settings.levelPercent)
        if (target >= current) return null
        val created = DuckRecord(current, target)
        store.save(created)
        record = created
        lastSet = current
        return target
    }

    fun stop(trigger: DuckTrigger, current: Int): Int? {
        if (!active.remove(trigger) || active.isNotEmpty()) return null
        userOwned = false
        val existing = record ?: return null
        if (!owns(current)) return null
        restoring = true
        if (existing.saved == current) {
            clear()
            return null
        }
        return existing.saved
    }

    /** The caller just set [level]. */
    fun applied(level: Int) {
        if (record != null) lastSet = level
    }

    /** A fade ended at [level]. */
    fun reached(level: Int) {
        val existing = record ?: return
        if (restoring && level == existing.saved) clear()
    }

    /** False, and the duck is given up, when [current] is not the level last set here. Checked before every fade step. */
    fun owns(current: Int): Boolean {
        if (record == null) return true
        val expected = lastSet
        if (expected == null || expected == current) return true
        giveUp()
        return false
    }

    /**
     * A voice volume command is about to run. Its target wins, so the duck ends without a restore afterwards. A
     * relative command ("louder", "quieter", "mute") applies to the owner's own level: the returned level is set
     * first, without a fade, and the command then steps from it.
     */
    fun command(relative: Boolean, current: Int): Int? {
        val existing = record ?: return null
        val ours = current == lastSet
        giveUp()
        return existing.saved.takeIf { relative && ours && it != current }
    }

    /** Process start: a record left by a killed process. */
    fun recover(current: Int, callActive: Boolean, settings: DuckSettings): Int? {
        val stored = store.load() ?: return null
        if (current !in minOf(stored.ducked, stored.saved)..maxOf(stored.ducked, stored.saved)) {
            // Changed by someone since; their level stays.
            store.clear()
            return null
        }
        record = stored
        lastSet = current
        if (callActive && settings.calls) {
            active += DuckTrigger.CALL
            return stored.ducked.takeIf { it != current }
        }
        restoring = true
        if (stored.saved == current) {
            clear()
            return null
        }
        return stored.saved
    }

    /**
     * The startup level replaces whatever a killed process left: the saved level belongs to an earlier drive and is
     * never restored over it. Active triggers stay, so a call already ringing lowers media from the startup level.
     */
    fun discard() {
        clear()
        userOwned = false
    }

    private fun giveUp() {
        clear()
        if (active.isNotEmpty()) userOwned = true
    }

    private fun clear() {
        record = null
        lastSet = null
        restoring = false
        store.clear()
    }
}
