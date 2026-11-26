package org.openvoyah.controls.domain.routines

import java.text.Collator
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.Locale
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.AppGridPins
import org.openvoyah.controls.domain.apps.DesktopLayoutPreset
import org.openvoyah.controls.domain.settings.AppPalette

internal enum class RoutineTarget(val key: String, val desktop: AppGridDesktop? = null) {
    CURRENT("current"),
    APP_GRID("apps", AppGridDesktop.MAIN),
    FAVORITES_ONE("favorites1", AppGridDesktop.FAVORITES_ONE),
    FAVORITES_TWO("favorites2", AppGridDesktop.FAVORITES_TWO),
    SETTINGS("settings"),
    BUTTONS("buttons"),
    VOICE("voice"),
    QUICK_ACTIONS("quickActions");

    val isFavorite: Boolean get() = desktop == AppGridDesktop.FAVORITES_ONE || desktop == AppGridDesktop.FAVORITES_TWO
}

internal enum class RoutineOrder(val key: String) { NEWEST("newest"), NAME("name") }

internal enum class RoutineStartupFrequency(val key: String) { EVERY_ENTRY("every"), ONCE_DAILY("daily") }
internal enum class RoutineStartupDelay(val seconds: Int) { IMMEDIATE(0), THREE_SECONDS(3), EIGHT_SECONDS(8) }

internal data class RoutineTimeWindow(val startMinute: Int, val endMinute: Int) {
    init { require(startMinute in 0..1439 && endMinute in 0..1439 && startMinute != endMinute) }
}

internal data class RoutineStartupSchedule(
    val days: Set<DayOfWeek> = EVERY_DAY,
    val window: RoutineTimeWindow? = null,
    val frequency: RoutineStartupFrequency = RoutineStartupFrequency.EVERY_ENTRY,
    val delay: RoutineStartupDelay = RoutineStartupDelay.IMMEDIATE,
) {
    init { require(days.isNotEmpty()) }

    fun allows(at: LocalDateTime): Boolean {
        val range = window ?: return at.dayOfWeek in days
        val minute = at.hour * 60 + at.minute
        if (range.startMinute < range.endMinute)
            return at.dayOfWeek in days && minute in range.startMinute until range.endMinute
        return when {
            minute >= range.startMinute -> at.dayOfWeek in days
            minute < range.endMinute -> at.dayOfWeek.minus(1) in days
            else -> false
        }
    }

    companion object { val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.values().toSet() }
}

internal data class RoutinePlan(
    val target: RoutineTarget,
    val palette: AppPalette? = null,
    val installedApp: String? = null,
    val desktopLayoutId: String? = null,
    val steps: List<RoutineStep> = emptyList(),
) {
    init {
        require(hasAction(target, palette, installedApp, desktopLayoutId, steps))
        require(palette == null || palette == AppPalette.DAY || palette == AppPalette.DARK)
        require(installedApp == null || AppGridPins.supports(installedApp))
        require(desktopLayoutId == null || DesktopLayoutPreset.validId(desktopLayoutId))
        require(steps.size <= RoutineStep.LIMIT)
    }

    companion object {
        fun hasAction(
            target: RoutineTarget, palette: AppPalette?, installedApp: String?, desktopLayoutId: String? = null,
            steps: List<RoutineStep> = emptyList(),
        ): Boolean = target != RoutineTarget.CURRENT || palette != null || installedApp != null || desktopLayoutId != null ||
            steps.any { it.delayMillis == 0L }
    }
}

internal data class RoutineStartupRequest(val routine: NamedRoutine, val schedule: RoutineStartupSchedule)

internal data class NamedRoutine(val id: String, val name: String, val plan: RoutinePlan) {
    init {
        require(validId(id))
        require(normalizeName(name) == name)
    }

    companion object {
        const val MAX_NAME = 40
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun validId(value: String): Boolean = value.length == 36 && idPattern.matches(value)

        fun normalizeName(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_NAME * 2) return null
            if (value.any { it.isISOControl() || it in '\u2028'..'\u202e' || it in '\u2066'..'\u2069' }) return null
            val name = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
            val hasVisibleBase = name.codePoints().anyMatch { point ->
                when (Character.getType(point)) {
                    Character.FORMAT.toInt(), Character.SPACE_SEPARATOR.toInt(), Character.NON_SPACING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> false
                    else -> true
                }
            }
            return name.takeIf { hasVisibleBase && it.codePointCount(0, it.length) <= MAX_NAME &&
                it.codePoints().noneMatch { point -> point in 0xd800..0xdfff } }
        }
    }
}

internal enum class RoutineProblem { NAME, DUPLICATE, FULL, CHANGED, NO_ACTION }
internal class RoutineEditException(val problem: RoutineProblem) : IllegalArgumentException(problem.name)

/** Entries retain creation order. Renaming, sorting and startup selection never change a routine's identity. */
internal data class RoutineLibrary(
    val entries: List<NamedRoutine> = emptyList(),
    val order: RoutineOrder = RoutineOrder.NEWEST,
    val startupId: String? = null,
    val schedule: RoutineStartupSchedule = RoutineStartupSchedule(),
    val pinnedIds: Set<String> = emptySet(),
    val startupEnabled: Boolean = true,
) {
    init {
        require(entries.size <= MAX_ROUTINES && entries.distinctBy { it.id }.size == entries.size)
        require(entries.indices.none { index ->
            entries.take(index).any { it.name.equals(entries[index].name, ignoreCase = true) }
        })
        require(startupId == null || entries.any { it.id == startupId })
        require(pinnedIds.all { id -> entries.any { it.id == id } })
    }

    fun ordered(locale: Locale): List<NamedRoutine> {
        val sorted = when (order) {
            RoutineOrder.NEWEST -> entries
            RoutineOrder.NAME -> {
                val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
                entries.sortedWith { left, right -> collator.compare(left.name, right.name) }
            }
        }
        if (pinnedIds.isEmpty()) return sorted
        val (pinned, other) = sorted.partition { it.id in pinnedIds }
        return pinned + other
    }

    fun startupRequest(at: LocalDateTime, ranToday: Boolean = false): RoutineStartupRequest? {
        if (!startupEnabled || !schedule.allows(at)) return null
        if (schedule.frequency == RoutineStartupFrequency.ONCE_DAILY && ranToday) return null
        return entries.firstOrNull { it.id == startupId }?.let { RoutineStartupRequest(it, schedule) }
    }

    fun startupPlan(at: LocalDateTime) = startupRequest(at)?.routine?.plan

    fun reschedule(routine: NamedRoutine, original: RoutineStartupSchedule, next: RoutineStartupSchedule): RoutineLibrary {
        verify(routine)
        if (routine.id != startupId || schedule != original) throw RoutineEditException(RoutineProblem.CHANGED)
        return copy(schedule = next)
    }

    fun planFor(routine: NamedRoutine): RoutinePlan {
        verify(routine)
        return routine.plan
    }

    fun save(original: NamedRoutine?, id: String, name: String, target: RoutineTarget, palette: AppPalette?,
             pinChange: Boolean? = null, installedApp: String? = original?.plan?.installedApp,
             desktopLayoutId: String? = original?.plan?.desktopLayoutId,
             steps: List<RoutineStep> = original?.plan?.steps.orEmpty()): RoutineLibrary {
        if (original == null) {
            if (entries.any { it.id == id }) throw RoutineEditException(RoutineProblem.CHANGED)
            if (entries.size >= MAX_ROUTINES) throw RoutineEditException(RoutineProblem.FULL)
        } else {
            require(original.id == id)
            verify(original)
        }
        val normalized = NamedRoutine.normalizeName(name) ?: throw RoutineEditException(RoutineProblem.NAME)
        if (entries.any { it.id != id && it.name.equals(normalized, ignoreCase = true) })
            throw RoutineEditException(RoutineProblem.DUPLICATE)
        if (!RoutinePlan.hasAction(target, palette, installedApp, desktopLayoutId, steps))
            throw RoutineEditException(RoutineProblem.NO_ACTION)
        val next = NamedRoutine(id, normalized, RoutinePlan(target, palette, installedApp, desktopLayoutId, steps))
        val updated = if (original == null) listOf(next) + entries else entries.map { if (it.id == id) next else it }
        val pins = when (pinChange) { true -> pinnedIds + id; false -> pinnedIds - id; null -> pinnedIds }
        return copy(entries = updated, pinnedIds = pins)
    }

    fun remove(original: NamedRoutine): RoutineLibrary {
        verify(original)
        return copy(entries = entries.filterNot { it.id == original.id }, startupId = startupId?.takeUnless { it == original.id },
            pinnedIds = pinnedIds - original.id)
    }

    fun chooseStartup(routine: NamedRoutine?): RoutineLibrary {
        routine?.let(::verify)
        return copy(startupId = routine?.id)
    }

    fun setStartupEnabled(expectedId: String, enabled: Boolean): RoutineLibrary {
        if (startupId != expectedId) throw RoutineEditException(RoutineProblem.CHANGED)
        return copy(startupEnabled = enabled)
    }

    private fun verify(original: NamedRoutine) {
        if (entries.firstOrNull { it.id == original.id } != original) throw RoutineEditException(RoutineProblem.CHANGED)
    }

    companion object { const val MAX_ROUTINES = 12 }
}
