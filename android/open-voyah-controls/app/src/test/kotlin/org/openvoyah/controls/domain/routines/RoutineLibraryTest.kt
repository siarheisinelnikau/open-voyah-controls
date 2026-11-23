package org.openvoyah.controls.domain.routines

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.settings.AppPalette

class RoutineLibraryTest {
    private val first = NamedRoutine("10000000-0000-4000-8000-000000000001", "Morning", RoutinePlan(RoutineTarget.FAVORITES_TWO, AppPalette.DAY))
    private val second = NamedRoutine("20000000-0000-4000-8000-000000000002", "Settings", RoutinePlan(RoutineTarget.SETTINGS))
    private val monday = LocalDateTime.of(2026, 11, 16, 0, 0)

    @Test fun sortingAndRenamingDoNotMoveCreationOrderOrReassignTheStartupRoutine() {
        val original = RoutineLibrary(listOf(second, first)).chooseStartup(first)
        assertEquals(listOf(second, first), original.ordered(Locale.ENGLISH))
        val byName = original.copy(order = RoutineOrder.NAME)
        assertEquals(listOf(first, second), byName.ordered(Locale.ENGLISH))
        val renamed = byName.save(first, first.id, "  Zen  ", RoutineTarget.CURRENT, AppPalette.DARK)
        assertEquals(listOf("Settings", "Zen"), renamed.entries.map { it.name })
        assertEquals(first.id, renamed.startupId)
        assertEquals(RoutinePlan(RoutineTarget.CURRENT, AppPalette.DARK), renamed.startupPlan(monday))
        assertEquals(AppGridDesktop.FAVORITES_TWO, original.planFor(first).target.desktop)
        assertNull(renamed.remove(renamed.entries.last()).startupId)
        assertNull(renamed.chooseStartup(null).startupPlan(monday))
    }

    @Test fun staleEditorsAndConcurrentCreationsCannotOverwriteOtherRoutines() {
        val initial = RoutineLibrary(listOf(first))
        val changed = initial.save(first, first.id, "Changed", RoutineTarget.BUTTONS, null)
        val created = changed.save(null, second.id, second.name, second.plan.target, null)
        val staleChanges = listOf(
            { created.save(first, first.id, "Stale", RoutineTarget.SETTINGS, null) },
            { created.save(null, second.id, "Other", RoutineTarget.APP_GRID, null) },
            { created.remove(first) }, { created.chooseStartup(first) }, { created.planFor(first) },
        )
        staleChanges.forEach { change ->
            assertEquals(RoutineProblem.CHANGED, assertThrows(RoutineEditException::class.java) { change() }.problem)
        }
        assertEquals(listOf("Settings", "Changed"), created.entries.map { it.name })
    }

    @Test fun anyActionCanRunAloneAndAnEmptyActionIsRejected() {
        val library = RoutineLibrary()
        val app = "org.example.navigation/org.example.navigation.MainActivity"
        assertNull(library.startupPlan(monday))
        assertEquals(RoutineTarget.SETTINGS, library.save(null, first.id, first.name, RoutineTarget.SETTINGS, null).entries.single().plan.target)
        assertEquals(AppPalette.DAY, library.save(null, first.id, first.name, RoutineTarget.CURRENT, AppPalette.DAY).entries.single().plan.palette)
        val appOnly = library.save(null, first.id, first.name, RoutineTarget.CURRENT, null, installedApp = app).entries.single()
        val prepared = library.copy(entries = listOf(appOnly)).save(appOnly, first.id, first.name, RoutineTarget.SETTINGS, AppPalette.DAY)
        assertEquals(RoutinePlan(RoutineTarget.CURRENT, installedApp = app), appOnly.plan)
        assertEquals(RoutinePlan(RoutineTarget.SETTINGS, AppPalette.DAY, app), prepared.entries.single().plan)
        assertThrows(IllegalArgumentException::class.java) {
            RoutinePlan(RoutineTarget.CURRENT, installedApp = "org.openvoyah.controls/org.openvoyah.controls.MainActivity")
        }
        assertEquals(RoutineProblem.NO_ACTION, assertThrows(RoutineEditException::class.java) {
            library.save(null, first.id, first.name, RoutineTarget.CURRENT, null)
        }.problem)
    }

    @Test fun namesAndKeysAreBoundedAndNormalizedBeforeComparing() {
        val library = RoutineLibrary().save(null, first.id, "Cafe\u0301", RoutineTarget.APP_GRID, null)
        assertEquals("Café", library.entries.single().name)
        assertEquals(RoutineProblem.DUPLICATE, assertThrows(RoutineEditException::class.java) {
            library.save(null, second.id, " CAFÉ ", RoutineTarget.SETTINGS, null)
        }.problem)
        listOf("", "   ", "line\nfeed", "bad\u202Ename", "\uD83D", "a".repeat(41)).forEach { assertNull(NamedRoutine.normalizeName(it)) }
        listOf("\u200B", "\u200D \uFE0F", "\u0301").forEach { assertNull(NamedRoutine.normalizeName(it)) }
        listOf("Evening", "Вечер", "👩‍🚀").forEach { assertEquals(it, NamedRoutine.normalizeName(it)) }
        val astral = "🌙".repeat(NamedRoutine.MAX_NAME)
        assertEquals(astral, NamedRoutine.normalizeName(astral))
        assertTrue(!NamedRoutine.validId("1-1-1-1-1"))
        val full = RoutineLibrary((1..RoutineLibrary.MAX_ROUTINES).map { i ->
            NamedRoutine("00000000-0000-4000-8000-" + i.toString().padStart(12, '0'), "Slot $i", second.plan)
        })
        assertEquals(RoutineProblem.FULL, assertThrows(RoutineEditException::class.java) {
            full.save(null, second.id, second.name, second.plan.target, null)
        }.problem)
    }

    @Test fun aSavedLayoutCanRunAloneAndIsRetainedWhenEditingOtherActions() {
        val id = "30000000-0000-4000-8000-000000000003"
        val original = RoutineLibrary().save(null, first.id, first.name, RoutineTarget.CURRENT, null, desktopLayoutId = id)
        val selected = original.entries.single()
        assertEquals(RoutinePlan(RoutineTarget.CURRENT, desktopLayoutId = id), selected.plan)

        val edited = original.save(selected, first.id, "At night", RoutineTarget.APP_GRID, AppPalette.DARK)
        assertEquals(RoutinePlan(RoutineTarget.APP_GRID, AppPalette.DARK, desktopLayoutId = id), edited.entries.single().plan)
        val cleared = edited.save(edited.entries.single(), first.id, first.name, RoutineTarget.SETTINGS, null,
            desktopLayoutId = null)
        assertNull(cleared.entries.single().plan.desktopLayoutId)
        assertThrows(IllegalArgumentException::class.java) {
            RoutinePlan(RoutineTarget.APP_GRID, desktopLayoutId = "layout-name")
        }
    }

    @Test fun normalLaunchHonorsDaysAndMinuteBoundariesWhileManualRunsStayAvailable() {
        val selected = RoutineLibrary(listOf(first)).chooseStartup(first)
        val days = RoutineStartupSchedule(setOf(DayOfWeek.MONDAY))
        val dayOnly = selected.copy(schedule = days)
        assertEquals(first.plan, dayOnly.startupPlan(monday.withHour(23)))
        assertNull(dayOnly.startupPlan(monday.plusDays(1)))
        val timed = dayOnly.copy(schedule = days.copy(window = RoutineTimeWindow(7 * 60, 9 * 60)))
        assertNull(timed.startupPlan(monday.withHour(6).withMinute(59)))
        assertEquals(first.plan, timed.startupPlan(monday.withHour(7)))
        assertEquals(first.plan, timed.startupPlan(monday.withHour(8).withMinute(59).withSecond(59)))
        assertNull(timed.startupPlan(monday.withHour(9)))
        assertEquals(first.plan, timed.planFor(first))
        assertEquals(first.plan, selected.startupPlan(monday.plusDays(6)))
        val paused = timed.setStartupEnabled(first.id, false)
        assertNull(paused.startupPlan(monday.withHour(7)))
        assertEquals(first.plan, paused.planFor(first))
        assertEquals(timed, paused.setStartupEnabled(first.id, true))
        assertEquals(RoutineProblem.CHANGED, assertThrows(RoutineEditException::class.java) {
            paused.setStartupEnabled(second.id, true)
        }.problem)
    }

    @Test fun dailyLaunchUsesTheCalendarDateAndNeverCountsManualOrIneligibleRuns() {
        val every = RoutineLibrary(listOf(first)).chooseStartup(first)
        assertEquals(first, every.startupRequest(monday, ranToday = true)?.routine)
        assertEquals(RoutineStartupDelay.IMMEDIATE, every.startupRequest(monday)?.schedule?.delay)
        val daily = every.copy(schedule = RoutineStartupSchedule(setOf(DayOfWeek.SUNDAY), RoutineTimeWindow(22 * 60, 6 * 60),
            RoutineStartupFrequency.ONCE_DAILY, RoutineStartupDelay.EIGHT_SECONDS))
        val request = daily.startupRequest(monday)
        assertEquals(first, request?.routine)
        assertEquals(RoutineStartupDelay.EIGHT_SECONDS, request?.schedule?.delay)
        assertNull(daily.startupRequest(monday, ranToday = true))
        assertNull(daily.startupRequest(monday.withHour(6)))
        assertEquals(first.plan, daily.planFor(first))
    }

    @Test fun overnightWindowUsesTheDayItStartedIncludingAcrossTheWeekBoundary() {
        val schedule = RoutineStartupSchedule(setOf(DayOfWeek.SUNDAY), RoutineTimeWindow(22 * 60, 6 * 60))
        assertTrue(schedule.allows(monday.minusDays(1).withHour(22)))
        assertTrue(schedule.allows(monday.withHour(5).withMinute(59)))
        assertTrue(!schedule.allows(monday.withHour(6)))
        assertTrue(!schedule.allows(monday.withHour(23)))
        assertTrue(!schedule.allows(monday.minusDays(1).withHour(5)))
        assertThrows(IllegalArgumentException::class.java) { RoutineTimeWindow(600, 600) }
        assertThrows(IllegalArgumentException::class.java) { RoutineTimeWindow(-1, 1439) }
        assertThrows(IllegalArgumentException::class.java) { RoutineTimeWindow(1439, 1440) }
        assertThrows(IllegalArgumentException::class.java) { RoutineStartupSchedule(emptySet()) }
    }

    @Test fun pinsUseStableIdsAndRespectTheChosenSortWithinEachGroup() {
        val third = NamedRoutine("30000000-0000-4000-8000-000000000003", "After work", second.plan)
        val library = RoutineLibrary(listOf(second, first, third), pinnedIds = setOf(first.id, third.id))
        assertEquals(listOf(first, third, second), library.ordered(Locale.ENGLISH))
        assertEquals(listOf(third, first, second), library.copy(order = RoutineOrder.NAME).ordered(Locale.ENGLISH))
        val renamed = library.save(first, first.id, "Renamed", first.plan.target, first.plan.palette)
        assertEquals(library.pinnedIds, renamed.pinnedIds)
        val removed = renamed.remove(third)
        assertEquals(setOf(first.id), removed.pinnedIds)
        assertThrows(IllegalArgumentException::class.java) { removed.copy(pinnedIds = setOf(third.id)) }
        val startup = library.chooseStartup(first)
        val pending = RoutineStartupSchedule(setOf(DayOfWeek.FRIDAY))
        assertThrows(RoutineEditException::class.java) { startup.chooseStartup(second).reschedule(first, startup.schedule, pending) }
        assertThrows(RoutineEditException::class.java) { startup.copy(schedule = pending).reschedule(first, startup.schedule, pending) }
    }
}
