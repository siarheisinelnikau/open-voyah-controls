package org.openvoyah.controls.presentation.apps

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.DesktopLayoutsPreferences
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.apps.DesktopLayoutException
import org.openvoyah.controls.domain.apps.DesktopLayoutPreset
import org.openvoyah.controls.domain.apps.DesktopLayoutProblem
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTextField

private sealed interface LayoutPage {
    data object Library : LayoutPage
    data class Actions(val preset: DesktopLayoutPreset) : LayoutPage
    data class Name(val action: LayoutNaming, val value: String = action.initialValue) : LayoutPage
    data class Confirm(val preset: DesktopLayoutPreset, val action: LayoutAction) : LayoutPage
}

private sealed class LayoutNaming(
    val title: Int, val success: Int, val preset: DesktopLayoutPreset? = null, val initialValue: String = "",
) {
    data object SaveCurrent : LayoutNaming(R.string.desktop_layouts_save_current, R.string.desktop_layouts_saved)
    data class Rename(val source: DesktopLayoutPreset) :
        LayoutNaming(R.string.desktop_layouts_rename, R.string.desktop_layouts_renamed, source, source.name)
    data class Duplicate(val source: DesktopLayoutPreset) :
        LayoutNaming(R.string.desktop_layouts_duplicate, R.string.desktop_layouts_duplicated, source)
}

private enum class LayoutAction(val title: Int, val confirm: Int, val description: Int, val success: Int) {
    APPLY(R.string.desktop_layouts_apply, R.string.desktop_layouts_apply_confirm_action,
        R.string.desktop_layouts_apply_confirm, R.string.desktop_layouts_applied),
    DELETE(R.string.desktop_layouts_delete, R.string.desktop_layouts_delete_confirm_action,
        R.string.desktop_layouts_delete_confirm, R.string.desktop_layouts_deleted),
}

private data class LayoutForm(
    val page: LayoutPage = LayoutPage.Library,
    val working: Boolean = false,
    val message: Int? = null,
    val error: Boolean = false,
)

private data class LayoutState(
    val presets: List<DesktopLayoutPreset>? = null,
    val loadFailed: Boolean = false,
    val form: LayoutForm = LayoutForm(),
)

private class DesktopLayoutsViewModel(private val layouts: DesktopLayoutsPreferences) : ViewModel() {
    private val form = MutableStateFlow(LayoutForm())
    val state = combine(layouts.observe(), form) { presets, editor ->
        LayoutState(presets.getOrNull(), presets.isFailure, editor)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), LayoutState())

    fun show(page: LayoutPage) = form.update { if (it.working) it else LayoutForm(page) }

    fun name(value: String) = form.update {
        val page = it.page as? LayoutPage.Name
        if (it.working || page == null || !DesktopLayoutPreset.validInput(value)) it
        else LayoutForm(page.copy(value = value))
    }

    fun save() {
        val page = form.value.page as? LayoutPage.Name ?: return
        val name = DesktopLayoutPreset.normalizeName(page.value) ?: return
        update(page.action.success) {
            when (val action = page.action) {
                LayoutNaming.SaveCurrent -> layouts.save(name)
                is LayoutNaming.Rename -> layouts.rename(action.source.id, name)
                is LayoutNaming.Duplicate -> layouts.duplicate(action.source.id, name)
            }
        }
    }

    fun confirm() {
        val page = form.value.page as? LayoutPage.Confirm ?: return
        update(page.action.success) {
            when (page.action) {
                LayoutAction.APPLY -> layouts.apply(page.preset.id)
                LayoutAction.DELETE -> layouts.delete(page.preset.id)
            }
        }
    }

    private fun update(success: Int, action: suspend () -> Unit) {
        val before = form.value
        if (before.working || !form.compareAndSet(before, before.copy(working = true, message = null, error = false))) return
        viewModelScope.launch {
            form.value = try {
                action()
                LayoutForm(message = success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                val message = when ((exception as? DesktopLayoutException)?.problem) {
                    DesktopLayoutProblem.INVALID_NAME -> R.string.desktop_layouts_invalid_name
                    DesktopLayoutProblem.DUPLICATE_NAME -> R.string.desktop_layouts_duplicate_name
                    DesktopLayoutProblem.FULL -> R.string.desktop_layouts_full
                    DesktopLayoutProblem.SIZE_LIMIT -> R.string.desktop_layouts_too_large
                    DesktopLayoutProblem.MISSING -> R.string.desktop_layouts_changed
                    null -> {
                        ControlsLog.warn("desktop_layouts", "outcome", "operation_failed", "error", exception)
                        R.string.desktop_layouts_update_failed
                    }
                }
                if (message == R.string.desktop_layouts_changed) LayoutForm(message = message, error = true)
                else before.copy(working = false, message = message, error = true)
            }
        }
    }
}

@Composable
internal fun DesktopLayoutsSettings(editable: Boolean) {
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var show by rememberSaveable { mutableStateOf(false) }
    CockpitChoice(UiText.get(R.string.desktop_layouts_settings), enabled = editable && resumed) {
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) show = true
    }
    if (!show || !resumed) return
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity)
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { DesktopLayoutsViewModel(DesktopLayoutsPreferences(activity.applicationContext)) }
        })[DesktopLayoutsViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    DesktopLayoutsDialog(state, model, canEdit = { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }) {
        model.show(LayoutPage.Library)
        show = false
    }
}

@Composable
private fun DesktopLayoutsDialog(
    state: LayoutState,
    model: DesktopLayoutsViewModel,
    canEdit: () -> Boolean,
    close: () -> Unit,
) {
    val page = state.form.page
    val presets = state.presets
    val available = presets != null && !state.form.working
    val full = presets?.size == DesktopLayoutPreset.MAX_PRESETS
    val title = when (page) {
        LayoutPage.Library -> UiText.get(R.string.desktop_layouts_settings)
        is LayoutPage.Actions -> page.preset.name
        is LayoutPage.Name -> UiText.get(page.action.title)
        is LayoutPage.Confirm -> UiText.get(page.action.title)
    }
    val primary = when (page) {
        LayoutPage.Library -> R.string.desktop_layouts_save_current
        is LayoutPage.Actions -> R.string.desktop_layouts_apply
        is LayoutPage.Name -> R.string.apps_grid_names_save
        is LayoutPage.Confirm -> page.action.confirm
    }
    val enabled = available && when (page) {
        LayoutPage.Library -> !full
        is LayoutPage.Name -> DesktopLayoutPreset.normalizeName(page.value) != null
        else -> true
    }
    fun back() {
        if (state.form.working) return
        when (page) {
            LayoutPage.Library -> close()
            is LayoutPage.Name -> model.show(page.action.preset?.let { LayoutPage.Actions(it) } ?: LayoutPage.Library)
            is LayoutPage.Confirm -> model.show(LayoutPage.Actions(page.preset))
            is LayoutPage.Actions -> model.show(LayoutPage.Library)
        }
    }
    fun proceed() {
        if (!enabled || !canEdit()) return
        when (page) {
            LayoutPage.Library -> model.show(LayoutPage.Name(LayoutNaming.SaveCurrent))
            is LayoutPage.Actions -> model.show(LayoutPage.Confirm(page.preset, LayoutAction.APPLY))
            is LayoutPage.Name -> model.save()
            is LayoutPage.Confirm -> model.confirm()
        }
    }
    CockpitAlertDialog(
        onDismissRequest = ::back,
        title = { Text(title) },
        confirmButton = {
            CockpitAction(UiText.get(primary), ::proceed, enabled = enabled, primary = true,
                destructive = page is LayoutPage.Confirm && page.action == LayoutAction.DELETE)
        },
        dismissButton = {
            val label = when (page) {
                LayoutPage.Library -> R.string.apps_desktop_done
                is LayoutPage.Actions -> R.string.cockpit_back
                else -> R.string.ui_89
            }
            CockpitTextAction(UiText.get(label), ::back)
        },
        text = {
            key(page.javaClass) {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    when (page) {
                        LayoutPage.Library -> {
                            if (presets == null) {
                                val message = if (state.loadFailed) R.string.desktop_layouts_load_failed else R.string.desktop_layouts_loading
                                CockpitFeedback(UiText.get(message), error = state.loadFailed)
                            } else {
                                if (presets.isEmpty()) Text(UiText.get(R.string.desktop_layouts_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                presets.forEach { preset ->
                                    key(preset.id) { CockpitChoice(preset.name, enabled = available) {
                                        model.show(LayoutPage.Actions(preset))
                                    } }
                                }
                                if (full) Text(UiText.get(R.string.desktop_layouts_full).format(DesktopLayoutPreset.MAX_PRESETS),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        is LayoutPage.Actions -> {
                            CockpitChoice(UiText.get(R.string.desktop_layouts_rename), enabled = available) {
                                model.show(LayoutPage.Name(LayoutNaming.Rename(page.preset)))
                            }
                            CockpitChoice(UiText.get(R.string.desktop_layouts_duplicate), enabled = available && !full) {
                                model.show(LayoutPage.Name(LayoutNaming.Duplicate(page.preset)))
                            }
                            CockpitChoice(UiText.get(R.string.desktop_layouts_delete), enabled = available) {
                                model.show(LayoutPage.Confirm(page.preset, LayoutAction.DELETE))
                            }
                            if (full) Text(UiText.get(R.string.desktop_layouts_full).format(DesktopLayoutPreset.MAX_PRESETS),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is LayoutPage.Name -> CockpitTextField(page.value, model::name, UiText.get(R.string.desktop_layouts_name),
                            Modifier.fillMaxWidth(), enabled = available, error = state.form.error)
                        is LayoutPage.Confirm -> Text(UiText.get(page.action.description).format(page.preset.name),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val message = if (state.form.working) R.string.desktop_layouts_working else state.form.message
                    if (message != null) {
                        val text = UiText.get(message)
                        val display = when (message) {
                            R.string.desktop_layouts_full -> text.format(DesktopLayoutPreset.MAX_PRESETS)
                            R.string.desktop_layouts_invalid_name -> text.format(DesktopLayoutPreset.MAX_NAME_LENGTH)
                            else -> text
                        }
                        CockpitFeedback(display, state.form.error)
                    }
                }
            }
        },
    )
}
