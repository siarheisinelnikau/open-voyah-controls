package org.openvoyah.controls.presentation.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.apps.AppGridPins
import org.openvoyah.controls.domain.routines.RoutineTarget
import org.openvoyah.controls.domain.routines.RoutineStep
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitAppPicker
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitOptionDialog
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTextField
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

private enum class EditorDialog { STEP, PAGE, LAYOUT, THEME, APP, DELETE }

@Composable
internal fun RoutineEditor(draft: RoutineDraft, state: RoutinesUiState, model: RoutinesViewModel, appLabels: Map<String, String>?) {
    var dialog by rememberSaveable(draft.id) { mutableStateOf<EditorDialog?>(null) }
    val focus = LocalFocusManager.current
    val enabled = !state.form.busy && state.library != null
    val savedLayouts = state.layouts?.getOrNull()
    val currentLayout = draft.desktopLayoutId?.let { state.layoutLabel(it) } ?: UiText.get(R.string.routines_layout_keep)
    val problem = when {
        state.loadFailed -> R.string.routines_load_failed
        state.form.error != null -> state.form.error
        draft.name.isNotEmpty() && !draft.validName -> R.string.routines_invalid_name
        !draft.validVoicePhrase -> R.string.routine_voice_invalid
        !draft.hasAction -> R.string.routines_no_action
        else -> null
    }
    val title = if (draft.original == null) R.string.routines_create else R.string.routines_edit_title
    CockpitDetail(UiText.get(title), null, model::closeEditor, nested = true) {
        CockpitTextField(draft.name, model::name, UiText.get(R.string.routines_name), Modifier.fillMaxWidth(), enabled = enabled, guideId = R.string.routines_name,
            error = draft.name.isNotEmpty() && !draft.validName || state.form.error == R.string.routines_duplicate_name,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        CockpitSection(UiText.get(R.string.routine_steps_title), CockpitIcon.BUTTONS,
            UiText.get(R.string.routine_steps_hint)) {
            draft.steps.forEachIndexed { index, step ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${step.label()}", modifier = Modifier.weight(1f))
                    if (index > 0) CockpitAction(UiText.get(R.string.routine_step_move), {
                        model.moveStepEarlier(index)
                    }, enabled = enabled)
                    CockpitAction(UiText.get(R.string.routine_step_remove), { model.removeStep(index) },
                        enabled = enabled)
                }
            }
            if (draft.steps.size < RoutineStep.LIMIT) {
                CockpitAction(UiText.get(R.string.routine_step_add), {
                    focus.clearFocus()
                    dialog = EditorDialog.STEP
                }, enabled = enabled)
            }
        }
        CockpitColumns(first = {
            CockpitChoice(R.string.routines_page, draft.target.label(state.names), enabled) {
                focus.clearFocus()
                dialog = EditorDialog.PAGE
            }
            if (draft.target.isFavorite && !state.favoritesEnabled)
                Text(UiText.get(R.string.routines_favorites_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
            CockpitChoice(R.string.routines_layout, currentLayout, enabled) {
                focus.clearFocus()
                dialog = EditorDialog.LAYOUT
            }
            if (draft.desktopLayoutId != null && savedLayouts?.none { it.id == draft.desktopLayoutId } == true) {
                Text(UiText.get(R.string.routines_layout_repair), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }, second = {
            CockpitChoice(R.string.routines_theme, routineTheme(draft.palette), enabled) {
                focus.clearFocus()
                dialog = EditorDialog.THEME
            }
            if (draft.palette != null)
                Text(UiText.get(R.string.routines_theme_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val app = draft.installedApp?.let { routineAppLabel(it, appLabels) } ?: UiText.get(R.string.routines_app_none)
            CockpitChoice(R.string.routines_app, app, enabled) {
                focus.clearFocus()
                dialog = EditorDialog.APP
            }
        }, minimumWidth = 570.dp)
        CockpitTextField(draft.voicePhrase, model::voicePhrase, UiText.get(R.string.routine_voice_phrase),
            Modifier.fillMaxWidth(), enabled = enabled, guideId = R.string.routine_voice_phrase,
            error = !draft.validVoicePhrase || state.form.error == R.string.routine_voice_invalid,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        Text(UiText.get(R.string.routine_voice_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        problem?.let { CockpitFeedback(UiText.get(it), error = true) }
        CockpitColumns(first = {
            CockpitSwitch(R.string.routines_pin, draft.pinned, enabled, model::pin)
        }, second = {
            val gap = LocalCockpitLayout.current.spacing(8.dp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(gap)) {
                if (draft.original != null) CockpitAction(UiText.get(R.string.routines_delete),
                    { focus.clearFocus(); dialog = EditorDialog.DELETE }, enabled = enabled, destructive = true)
                CockpitAction(UiText.get(R.string.routines_save), model::save,
                    enabled = enabled && draft.validName && draft.hasAction && draft.validVoicePhrase, primary = true)
            }
        }, minimumWidth = 570.dp)
    }
    when (dialog) {
        EditorDialog.STEP -> RoutinePicker(R.string.routine_step_add, RoutineStep.entries, null, RoutineStep::label,
            dismiss = { dialog = null }) { step ->
            dialog = null
            model.addStep(step)
        }
        EditorDialog.PAGE -> RoutinePicker(R.string.routines_page, RoutineTarget.entries, draft.target,
            label = { it.label(state.names) }, dismiss = { dialog = null }) { target ->
            dialog = null
            model.target(target)
        }
        EditorDialog.LAYOUT -> {
            val hint = when {
                state.layouts == null -> R.string.desktop_layouts_loading
                state.layouts.isFailure -> R.string.desktop_layouts_load_failed
                savedLayouts.isNullOrEmpty() -> R.string.routines_layout_empty
                else -> R.string.routines_layout_hint
            }
            RoutinePicker(R.string.routines_layout, listOf(null) + savedLayouts.orEmpty().map { it.id }, draft.desktopLayoutId,
                label = { id -> id?.let { state.layoutLabel(it) } ?: UiText.get(R.string.routines_layout_keep) },
                hint = hint, dismiss = { dialog = null }) { id ->
                dialog = null
                model.desktopLayout(id)
            }
        }
        EditorDialog.THEME -> RoutinePicker(R.string.routines_theme, listOf(null, AppPalette.DAY, AppPalette.DARK), draft.palette,
            label = ::routineTheme, hint = R.string.routines_theme_hint, dismiss = { dialog = null }) { palette ->
            dialog = null
            model.palette(palette)
        }
        EditorDialog.APP -> Dialog(onDismissRequest = { dialog = null }) {
            CockpitAppPicker(UiText.get(R.string.routines_app), { dialog = null }, { entry ->
                dialog = null
                model.installedApp(entry?.component()?.flattenToString())
            }, defaultChoice = UiText.get(R.string.routines_app_none), eligible = AppGridPins::supports)
        }
        EditorDialog.DELETE -> CockpitAlertDialog(onDismissRequest = { dialog = null },
            title = { Text(UiText.get(R.string.routines_delete_prompt).format(draft.original?.name)) },
            text = { Text(UiText.get(R.string.routines_delete_detail)) },
            confirmButton = {
                CockpitAction(UiText.get(R.string.routines_delete), { dialog = null; model.remove() }, primary = true, destructive = true)
            },
            dismissButton = { CockpitTextAction(UiText.get(R.string.ui_89)) { dialog = null } })
        null -> Unit
    }
}

internal fun RoutineStep.label(): String = if (delayMillis > 0) {
    UiText.get(R.string.routine_step_wait).format(delayMillis / 1_000)
} else UiText.get(when (this) {
    RoutineStep.MEDIA_PLAY -> R.string.routine_step_play
    RoutineStep.MEDIA_PAUSE -> R.string.routine_step_pause
    RoutineStep.MEDIA_NEXT -> R.string.routine_step_next
    RoutineStep.MEDIA_PREVIOUS -> R.string.routine_step_previous
    else -> error("Unknown routine action")
})

/** [choose] runs only for a value other than [selected]; the dialog closes on any choice. */
@Composable
internal fun <T> RoutinePicker(
    title: Int,
    choices: List<T>,
    selected: T?,
    label: (T) -> String,
    hint: Int? = null,
    dismiss: () -> Unit,
    choose: (T) -> Unit,
) {
    CockpitOptionDialog(UiText.get(title), choices, selected, label, onSelect = choose, onDismiss = dismiss,
        description = hint?.let(UiText::get), guideId = title)
}
