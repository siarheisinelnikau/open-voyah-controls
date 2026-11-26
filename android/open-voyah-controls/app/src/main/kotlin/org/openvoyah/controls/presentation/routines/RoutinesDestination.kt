package org.openvoyah.controls.presentation.routines

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences
import org.openvoyah.controls.data.routines.RoutinePreferences
import org.openvoyah.controls.domain.apps.AppGridDesktopNames
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.routines.RoutineOrder
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.platform.apps.LauncherShortcuts
import org.openvoyah.controls.presentation.apps.desktopLabel
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion
import org.openvoyah.controls.presentation.theme.cockpitSwap

internal val RoutineTarget.route: ControlsRoute? get() = when (this) {
    RoutineTarget.CURRENT -> null
    RoutineTarget.APP_GRID, RoutineTarget.FAVORITES_ONE, RoutineTarget.FAVORITES_TWO -> ControlsRoute.APPS
    RoutineTarget.SETTINGS -> ControlsRoute.SETTINGS
    RoutineTarget.BUTTONS -> ControlsRoute.BUTTONS
    RoutineTarget.VOICE -> ControlsRoute.VOICE
    RoutineTarget.QUICK_ACTIONS -> ControlsRoute.QUICK_ACTIONS
}

internal fun RoutineTarget.label(names: AppGridDesktopNames): String = when (this) {
    RoutineTarget.CURRENT -> UiText.get(R.string.routines_current_page)
    RoutineTarget.APP_GRID -> UiText.get(R.string.apps_desktop_all)
    RoutineTarget.FAVORITES_ONE, RoutineTarget.FAVORITES_TWO -> requireNotNull(desktop).desktopLabel(names, includeSlot = true)
    RoutineTarget.SETTINGS -> UiText.get(R.string.cockpit_settings)
    RoutineTarget.BUTTONS -> UiText.get(R.string.cockpit_buttons)
    RoutineTarget.VOICE -> UiText.get(R.string.voice_options_title)
    RoutineTarget.QUICK_ACTIONS -> UiText.get(R.string.quick_actions_title)
}

internal fun routineTheme(palette: AppPalette?): String = UiText.get(when (palette) {
    AppPalette.DARK -> R.string.appearance_palette_dark
    AppPalette.DAY -> R.string.appearance_palette_day
    else -> R.string.routines_current_theme
})

private fun RoutineOrder.label(): String = UiText.get(when (this) {
    RoutineOrder.NEWEST -> R.string.routines_sort_newest
    RoutineOrder.NAME -> R.string.routines_sort_name
})

private fun canRunInForeground(activity: ComponentActivity, lifecycle: Lifecycle) =
    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus() &&
        !activity.isFinishing && !activity.isDestroyed

internal fun routineAppLabel(component: String, labels: Map<String, String>?): String =
    labels?.get(component) ?: if (labels == null) component else UiText.get(R.string.routines_app_unavailable).format(component)

internal fun RoutinesUiState.layoutLabel(id: String, summary: Boolean = false): String {
    val library = layouts ?: return UiText.get(R.string.desktop_layouts_loading)
    val entries = library.getOrNull() ?: return UiText.get(R.string.desktop_layouts_load_failed)
    val name = entries.firstOrNull { it.id == id }?.name ?: return UiText.get(R.string.routines_layout_missing)
    return if (summary) UiText.get(R.string.routines_layout_summary).format(name) else name
}

@Composable
internal fun RoutinesDestination(back: () -> Unit, run: suspend (NamedRoutine) -> Int?) {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity)
    val model = remember(activity) {
        val app = activity.applicationContext
        ViewModelProvider(activity, viewModelFactory { initializer {
            RoutinesViewModel(RoutinePreferences(app), AppGridPreferences(app).observeSettings(),
                DesktopLayoutsPreferences(app).observe(), LauncherShortcuts(app))
        } })[RoutinesViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle()
    val appLabels by model.appLabels.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<Job?>(null) }
    LifecycleResumeEffect(lifecycle) {
        model.refreshAppLabels()
        onPauseOrDispose { opening?.cancel() }
    }
    fun runRoutine(routine: NamedRoutine) {
        if (!canRunInForeground(activity, lifecycle) || opening?.isActive == true) return
        opening = scope.launch {
            model.run(routine) {
                if (!canRunInForeground(activity, lifecycle)) throw CancellationException()
                run(routine)
            }
        }
    }
    val draft = state.form.draft
    val schedule = state.form.schedule
    BackHandler(enabled = draft != null || schedule != null, onBack = model::closeEditor)
    val motion = LocalCockpitMotion.current
    val offset = with(LocalDensity.current) { 20.dp.roundToPx() }
    // The whole state is the target so that the view sliding out keeps the content it showed, such as a draft just saved.
    AnimatedContent(state, transitionSpec = { cockpitSwap(motion, offset, forward = targetState.view != RoutinesView.LIST) },
        label = "routine views", contentKey = { it.view }) { shown ->
        val shownSchedule = shown.form.schedule
        val shownDraft = shown.form.draft
        if (shownSchedule != null) {
            RoutineScheduleEditor(shownSchedule, shown, model)
        } else if (shownDraft != null) {
            RoutineEditor(shownDraft, shown, model, appLabels)
        } else {
            CockpitDetail(UiText.get(R.string.routines_title), UiText.get(R.string.routines_summary), back) {
                if (shown.loadFailed) CockpitFeedback(UiText.get(R.string.routines_load_failed), error = true)
                else if (shown.library == null) CockpitFeedback(UiText.get(R.string.flows_loading))
                shown.library?.let { library ->
                    val language = UiText.language()
                    val sorted = remember(library, language) { library.ordered(Locale.forLanguageTag(language)) }
                    RoutineToolbar(library, sorted, !shown.form.busy, model)
                    if (sorted.isEmpty()) Text(UiText.get(R.string.routines_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sorted.size == RoutineLibrary.MAX_ROUTINES) Text(UiText.get(R.string.routines_full))
                    if (sorted.isNotEmpty()) Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column {
                            sorted.forEachIndexed { index, routine -> key(routine.id) {
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                RoutineRow(routine, library.startupId == routine.id, shown, appLabels,
                                    edit = { model.edit(routine) }, run = { runRoutine(routine) })
                            } }
                        }
                    }
                    val error = shown.form.error
                    CockpitFeedback(error?.let(UiText::get) ?: shown.form.ran?.let { UiText.get(R.string.routines_ran).format(it) },
                        error = error != null)
                }
            }
        }
    }
}

private enum class RoutinesView { LIST, EDITOR, SCHEDULE }

private val RoutinesUiState.view: RoutinesView get() = when {
    form.schedule != null -> RoutinesView.SCHEDULE
    form.draft != null -> RoutinesView.EDITOR
    else -> RoutinesView.LIST
}

private enum class RoutineListDialog { SORT, STARTUP }

@Composable
private fun RoutineToolbar(library: RoutineLibrary, sorted: List<NamedRoutine>, enabled: Boolean, model: RoutinesViewModel) {
    var dialog by rememberSaveable { mutableStateOf<RoutineListDialog?>(null) }
    val spacing = Arrangement.spacedBy(LocalCockpitLayout.current.spacing(8.dp))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    FlowRow(horizontalArrangement = spacing, verticalArrangement = spacing) {
        CockpitAction(UiText.get(R.string.routines_create), model::create,
            enabled = enabled && library.entries.size < RoutineLibrary.MAX_ROUTINES, primary = true)
        if (library.entries.isNotEmpty()) CockpitAction(UiText.get(R.string.routines_startup),
            onClick = { dialog = RoutineListDialog.STARTUP }, enabled = enabled)
        if (library.startupId != null) CockpitAction(UiText.get(R.string.routines_schedule), model::editSchedule, enabled = enabled)
        if (library.entries.size > 1) {
            val order = library.order.label()
            val description = UiText.get(R.string.routines_sort_action).format(order)
            CockpitAction(order, { dialog = RoutineListDialog.SORT }, Modifier.semantics { contentDescription = description }, enabled)
        }
    }
    library.startupId?.let { id ->
        CockpitSwitch(R.string.routines_startup_enabled, library.startupEnabled, enabled) { runAtStartup ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.setStartupEnabled(id, runAtStartup)
        }
    }
    when (dialog) {
        RoutineListDialog.SORT -> RoutinePicker(R.string.routines_sort, RoutineOrder.entries, library.order,
            label = { it.label() }, dismiss = { dialog = null }) { order ->
            dialog = null
            if (library.order != order) model.sort(order)
        }
        RoutineListDialog.STARTUP -> RoutinePicker(R.string.routines_startup, listOf(null) + sorted,
            library.entries.firstOrNull { it.id == library.startupId },
            label = { it?.name ?: UiText.get(R.string.routines_startup_none) },
            hint = R.string.routines_startup_hint, dismiss = { dialog = null }) { choice ->
            dialog = null
            if (choice?.id != library.startupId) model.chooseStartup(choice)
        }
        null -> Unit
    }
}

@Composable
private fun RoutineRow(routine: NamedRoutine, startup: Boolean, state: RoutinesUiState, appLabels: Map<String, String>?,
                       edit: () -> Unit, run: () -> Unit) {
    val layout = LocalCockpitLayout.current
    val plan = routine.plan
    val page = plan.target.label(state.names)
    val theme = plan.palette?.let { UiText.get(R.string.routines_theme_value).format(routineTheme(it)) }
    val description = when {
        plan.target == RoutineTarget.CURRENT && theme == null -> null
        theme == null -> page
        plan.target == RoutineTarget.CURRENT -> theme
        else -> UiText.get(R.string.routines_page_and_theme).format(page, theme)
    }
    val text: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(layout.spacing(3.dp))) {
            Text(routine.name, style = MaterialTheme.typography.titleMedium)
            if (plan.steps.isNotEmpty()) Text(plan.steps.joinToString(" → ") { it.label() },
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            plan.desktopLayoutId?.let { id ->
                val missing = state.layouts?.getOrNull()?.none { it.id == id } == true
                Text(state.layoutLabel(id, summary = true), color = if (missing) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            plan.installedApp?.let { component ->
                Text(UiText.get(R.string.routines_app_summary).format(routineAppLabel(component, appLabels)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.library?.pinnedIds?.contains(routine.id) == true)
                Text(UiText.get(R.string.routines_pinned), style = MaterialTheme.typography.labelLarge)
            if (startup) state.library?.let { library ->
                val status = if (library.startupEnabled) R.string.routines_on_startup else R.string.routines_startup_paused
                val colors = MaterialTheme.colorScheme
                Text(UiText.get(status), style = MaterialTheme.typography.labelLarge,
                    color = if (library.startupEnabled) colors.primary else colors.onSurfaceVariant)
                routineScheduleLabel(library.schedule)?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (plan.target.isFavorite && !state.favoritesEnabled) Text(UiText.get(R.string.routines_favorites_off),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (plan.desktopLayoutId != null && plan.steps.isEmpty() && state.form.running?.id == routine.id) {
                CockpitFeedback(UiText.get(R.string.routines_applying_layout))
            }
        }
    }
    val buttons: @Composable () -> Unit = {
        val editName = UiText.get(R.string.routines_edit_named).format(routine.name)
        val runName = UiText.get(R.string.routines_run_named).format(routine.name)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(layout.spacing(6.dp))) {
            CockpitAction(UiText.get(R.string.routines_run), run, Modifier.semantics { contentDescription = runName },
                enabled = !state.form.busy)
            CockpitAction(UiText.get(R.string.routines_edit), edit, Modifier.semantics { contentDescription = editName },
                enabled = !state.form.busy)
        }
    }
    val wide = layout.columnsMinimum(450.dp, LocalDensity.current.fontScale)
    val gap = Arrangement.spacedBy(layout.spacing(10.dp))
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = layout.spacing(16.dp), vertical = layout.spacing(10.dp))) {
        if (maxWidth >= wide) Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
            text(Modifier.weight(1f))
            buttons()
        } else Column(verticalArrangement = gap) {
            text(Modifier.fillMaxWidth())
            buttons()
        }
    }
}
