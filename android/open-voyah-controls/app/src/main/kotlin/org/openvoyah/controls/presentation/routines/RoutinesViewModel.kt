package org.openvoyah.controls.presentation.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.DayOfWeek
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.domain.apps.AppGridConfiguration
import org.openvoyah.controls.domain.apps.AppGridDesktopNames
import org.openvoyah.controls.domain.apps.DesktopLayoutPreset
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineEditException
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.routines.RoutineOrder
import org.openvoyah.controls.domain.routines.RoutinePlan
import org.openvoyah.controls.domain.routines.RoutineProblem
import org.openvoyah.controls.domain.routines.RoutineStartupDelay
import org.openvoyah.controls.domain.routines.RoutineStartupFrequency
import org.openvoyah.controls.domain.routines.RoutineStartupSchedule
import org.openvoyah.controls.domain.routines.RoutineStep
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.routines.RoutineTimeWindow
import org.openvoyah.controls.domain.routines.normalizeRoutinePhrase
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.platform.apps.LauncherShortcuts

internal data class RoutineDraft(
    val original: NamedRoutine?,
    val id: String,
    val name: String,
    val target: RoutineTarget,
    val palette: AppPalette?,
    val installedApp: String? = null,
    val desktopLayoutId: String? = null,
    val originallyPinned: Boolean = false,
    val pinned: Boolean = originallyPinned,
    val steps: List<RoutineStep> = emptyList(),
    val originalVoicePhrase: String = "",
    val voicePhrase: String = originalVoicePhrase,
) {
    val validName: Boolean get() = NamedRoutine.normalizeName(name) != null
    val hasAction: Boolean get() = RoutinePlan.hasAction(target, palette, installedApp, desktopLayoutId, steps)
    val validVoicePhrase: Boolean get() = voicePhrase.isBlank() || normalizeRoutinePhrase(voicePhrase) != null
}

internal data class RoutineScheduleDraft(
    val routine: NamedRoutine,
    val original: RoutineStartupSchedule,
    val days: Set<DayOfWeek> = original.days,
    val limited: Boolean = original.window != null,
    val start: Int = original.window?.startMinute ?: 7 * 60,
    val end: Int = original.window?.endMinute ?: 10 * 60,
    val frequency: RoutineStartupFrequency = original.frequency,
    val delay: RoutineStartupDelay = original.delay,
) {
    val valid get() = days.isNotEmpty() && (!limited || start != end)
}

internal data class RoutinesForm(
    val draft: RoutineDraft? = null,
    val schedule: RoutineScheduleDraft? = null,
    val saving: Boolean = false,
    val running: NamedRoutine? = null,
    val error: Int? = null,
    val ran: String? = null,
) {
    val busy: Boolean get() = saving || running != null
}

internal data class RoutinesUiState(
    val library: RoutineLibrary? = null,
    val loadFailed: Boolean = false,
    val names: AppGridDesktopNames = AppGridDesktopNames(),
    val favoritesEnabled: Boolean = false,
    val layouts: Result<List<DesktopLayoutPreset>>? = null,
    val form: RoutinesForm = RoutinesForm(),
)

internal class RoutinesViewModel(
    private val store: RoutinePreferences,
    grid: Flow<Result<AppGridConfiguration>>,
    layouts: Flow<Result<List<DesktopLayoutPreset>>>,
    private val apps: LauncherShortcuts,
    private val matchesExistingCommand: suspend (String) -> Boolean = { false },
) : ViewModel() {
    private val form = MutableStateFlow(RoutinesForm())
    val state = combine(store.observe(), grid, layouts.onStart<Result<List<DesktopLayoutPreset>>?> { emit(null) }, form) {
        library, gridResult, desktopLayouts, editor ->
        val desktops = gridResult.getOrNull()
        RoutinesUiState(
            library = library.getOrNull(),
            loadFailed = library.isFailure,
            names = desktops?.desktopNames ?: AppGridDesktopNames(),
            favoritesEnabled = desktops?.desktops?.enabled == true,
            layouts = desktopLayouts,
            form = editor,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), RoutinesUiState())

    private val refreshApps = MutableStateFlow(0)
    private val appComponents = state.map { current ->
        current.library?.entries.orEmpty().mapNotNull { it.plan.installedApp }.toSet() +
            listOfNotNull(current.form.draft?.installedApp)
    }.distinctUntilChanged()
    val appLabels = combine(appComponents, refreshApps) { components, _ ->
        components.mapNotNull { id -> apps.find(id)?.let { id to it.label() } }.toMap()
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)

    fun refreshAppLabels() = refreshApps.update { it + 1 }

    fun create() {
        val current = state.value.library ?: return
        if (current.entries.size >= RoutineLibrary.MAX_ROUTINES || form.value.busy) return
        form.value = RoutinesForm(draft = RoutineDraft(null, UUID.randomUUID().toString(), "", RoutineTarget.APP_GRID, null))
    }

    fun edit(routine: NamedRoutine) {
        if (form.value.busy) return
        val library = state.value.library ?: return
        form.value = RoutinesForm(draft = RoutineDraft(routine, routine.id, routine.name, routine.plan.target, routine.plan.palette,
            installedApp = routine.plan.installedApp,
            desktopLayoutId = routine.plan.desktopLayoutId,
            originallyPinned = routine.id in library.pinnedIds, steps = routine.plan.steps,
            originalVoicePhrase = library.voicePhrases[routine.id].orEmpty()))
    }

    fun closeEditor() {
        if (!form.value.busy) form.value = RoutinesForm()
    }

    fun name(name: String) = changeDraft { copy(name = name) }
    fun target(target: RoutineTarget) = changeDraft { copy(target = target) }
    fun palette(palette: AppPalette?) = changeDraft { copy(palette = palette) }
    fun installedApp(component: String?) = changeDraft { copy(installedApp = component) }
    fun desktopLayout(id: String?) = changeDraft { copy(desktopLayoutId = id) }
    fun pin(pinned: Boolean) = changeDraft { copy(pinned = pinned) }
    fun voicePhrase(phrase: String) = changeDraft { copy(voicePhrase = phrase) }
    fun addStep(step: RoutineStep) = changeDraft {
        if (steps.size >= RoutineStep.LIMIT) this else copy(steps = steps + step)
    }
    fun removeStep(index: Int) = changeDraft { copy(steps = steps.filterIndexed { position, _ -> position != index }) }
    fun moveStepEarlier(index: Int) = changeDraft {
        if (index !in 1..steps.lastIndex) this else copy(steps = steps.toMutableList().apply {
            add(index - 1, removeAt(index))
        })
    }

    private fun changeDraft(edit: RoutineDraft.() -> RoutineDraft) = form.update { state ->
        if (state.busy) state else state.copy(draft = state.draft?.edit(), error = null)
    }

    fun save() {
        val draft = form.value.draft ?: return
        change(validate = {
            val phrase = normalizeRoutinePhrase(draft.voicePhrase)
            if (!draft.validVoicePhrase || phrase != null && phrase != draft.originalVoicePhrase && matchesExistingCommand(phrase))
                throw RoutineEditException(RoutineProblem.VOICE_PHRASE)
        }) { library ->
            if (draft.original != null && library.voicePhrases[draft.id].orEmpty() != draft.originalVoicePhrase)
                throw RoutineEditException(RoutineProblem.CHANGED)
            library.save(draft.original, draft.id, draft.name, draft.target, draft.palette,
                pinChange = draft.pinned.takeIf { pinned -> pinned != draft.originallyPinned }, installedApp = draft.installedApp,
                desktopLayoutId = draft.desktopLayoutId, steps = draft.steps, voicePhrase = draft.voicePhrase)
        }
    }

    fun remove() {
        val original = form.value.draft?.original ?: return
        change { it.remove(original) }
    }

    fun chooseStartup(routine: NamedRoutine?) = change { it.chooseStartup(routine) }
    fun setStartupEnabled(id: String, enabled: Boolean) = change { it.setStartupEnabled(id, enabled) }
    fun sort(order: RoutineOrder) = change { it.copy(order = order) }

    fun editSchedule() {
        if (form.value.busy) return
        val library = state.value.library ?: return
        val routine = library.entries.firstOrNull { it.id == library.startupId } ?: return
        form.value = RoutinesForm(schedule = RoutineScheduleDraft(routine, library.schedule))
    }

    fun toggleDay(day: DayOfWeek) = changeSchedule { copy(days = if (day in days) days - day else days + day) }
    fun limitTime(enabled: Boolean) = changeSchedule { copy(limited = enabled) }
    fun frequency(value: RoutineStartupFrequency) = changeSchedule { copy(frequency = value) }
    fun startupDelay(value: RoutineStartupDelay) = changeSchedule { copy(delay = value) }
    fun chooseTime(starting: Boolean, minutes: Int) = changeSchedule {
        if (minutes !in 0..1439) this else if (starting) copy(start = minutes) else copy(end = minutes)
    }

    private fun changeSchedule(edit: RoutineScheduleDraft.() -> RoutineScheduleDraft) = form.update { state ->
        if (state.busy) state else state.copy(schedule = state.schedule?.edit(), error = null)
    }

    fun saveSchedule() {
        val draft = form.value.schedule?.takeIf { it.valid } ?: return
        val next = RoutineStartupSchedule(draft.days, if (draft.limited) RoutineTimeWindow(draft.start, draft.end) else null,
            draft.frequency, draft.delay)
        change { it.reschedule(draft.routine, draft.original, next) }
    }

    suspend fun run(routine: NamedRoutine, dispatch: suspend () -> Int?) {
        val before = form.value
        if (before.busy || before.draft != null || before.schedule != null) return
        val running = RoutinesForm(running = routine)
        if (!form.compareAndSet(before, running)) return
        try {
            val error = dispatch()
            val ran = routine.name.takeIf { error == null && routine.plan.installedApp == null }
            form.compareAndSet(running, RoutinesForm(error = error, ran = ran))
        } catch (cancelled: CancellationException) {
            form.compareAndSet(running, RoutinesForm())
            throw cancelled
        } catch (ex: Exception) {
            val error = if (ex is RoutineEditException) R.string.routines_changed else R.string.routines_load_failed
            form.compareAndSet(running, RoutinesForm(error = error))
        }
    }

    private fun change(validate: suspend () -> Unit = {}, edit: (RoutineLibrary) -> RoutineLibrary) {
        val before = form.value
        if (state.value.library == null || before.busy || !form.compareAndSet(before, before.copy(saving = true, error = null))) return
        viewModelScope.launch {
            try {
                validate()
                store.update(edit)
                form.value = RoutinesForm()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ex: Exception) {
                val error = when ((ex as? RoutineEditException)?.problem) {
                    RoutineProblem.NAME -> R.string.routines_invalid_name
                    RoutineProblem.DUPLICATE -> R.string.routines_duplicate_name
                    RoutineProblem.FULL -> R.string.routines_full
                    RoutineProblem.CHANGED -> R.string.routines_changed
                    RoutineProblem.NO_ACTION -> R.string.routines_no_action
                    RoutineProblem.VOICE_PHRASE -> R.string.routine_voice_invalid
                    null -> R.string.routines_save_failed
                }
                form.value = before.copy(saving = false, error = error)
            }
        }
    }
}
