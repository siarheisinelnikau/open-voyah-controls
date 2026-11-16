package org.openvoyah.controls.presentation.settings

import android.app.Dialog
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.settings.FridaSettingsArchiveRuntime
import org.openvoyah.controls.data.settings.LocalSettingsArchiveDocuments
import org.openvoyah.controls.presentation.settings.SettingsArchiveViewModel.State
import org.openvoyah.controls.presentation.settings.SettingsArchiveViewModel.Work
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitDialogs
import org.openvoyah.controls.presentation.theme.CockpitModal
import org.openvoyah.controls.presentation.theme.CockpitTextAction

/** Dialog delivers onDismiss through its Handler, after the lifecycle's dismiss call returns. */
internal class ArchiveDialogDismissal(private val userDismissed: () -> Unit) {
    private var retained = false
    fun retain() { retained = true }
    fun deliver() { if (!retained) userDismissed() }
}

/** The lifecycle collector is the only archive owner allowed to open or refresh Activity UI. */
class SettingsArchiveDialogs(
    private val activity: ComponentActivity,
    private val dialogs: CockpitDialogs,
    private val restored: Runnable,
) {
    private val model = ViewModelProvider(activity, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsArchiveViewModel(
            LocalSettingsArchiveDocuments(activity.application),
            FridaSettingsArchiveRuntime(activity.application),
        ) as T
    })[SettingsArchiveViewModel::class.java]
    private var dialog: Dialog? = null
    private var shown: State? = null
    private var dismissal: ArchiveDialogDismissal? = null
    private var recreating = false

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try { model.state.collect(::render) }
                finally { dismiss() }
            }
        }
    }

    fun export(document: Uri) = model.export(document.toString())
    fun prepare(document: Uri) = model.prepare(document.toString())

    private fun dismiss() {
        dismissal?.retain()
        dismissal = null
        dialog?.dismiss()
        dialog = null
        shown = null
    }

    private fun render(state: State) {
        if (recreating || shown == state) return
        dismiss()
        if (state == State.Idle) return
        if (state is State.Completed && state.refreshPending) {
            model.acknowledgeRefresh(state)
            restored.run()
            if (state.languageChanged) {
                recreating = true
                activity.recreate()
                return
            }
            render(model.state.value)
            return
        }
        shown = state
        val applying = state is State.Working && state.work == Work.APPLY
        val event = ArchiveDialogDismissal { if (model.state.value == state) model.cancel() }
        dismissal = event
        dialog = dialogs.show(cancelable = !applying, onDismiss = event::deliver) { close ->
            val title = when (state) {
                is State.Confirmation -> R.string.ui_126
                is State.Failed -> R.string.ui_84
                else -> R.string.settings_archive_title
            }
            CockpitModal(UiText.get(title), footer = {
                when (state) {
                    is State.Working -> if (!applying) CockpitTextAction(UiText.get(R.string.ui_89)) {
                        model.cancel(); close()
                    }
                    is State.Confirmation -> {
                        CockpitTextAction(UiText.get(R.string.ui_89)) { model.cancel(); close() }
                        CockpitAction(UiText.get(R.string.ui_129), {
                            model.confirm(); close()
                        }, primary = true)
                    }
                    else -> CockpitAction(UiText.get(R.string.ui_83), {
                        model.cancel(); close()
                    }, primary = true)
                }
            }) {
                val message = when (state) {
                    is State.Working -> UiText.get(when (state.work) {
                        Work.EXPORT -> R.string.settings_archive_exporting
                        Work.PREPARE -> R.string.settings_archive_reading
                        Work.APPLY -> R.string.settings_archive_applying
                    })
                    is State.Confirmation -> UiText.get(R.string.ui_127) + state.restore.bindingCount +
                        UiText.get(R.string.ui_128) + if (state.restore.complete)
                            "\n\n" + UiText.get(R.string.settings_archive_scope) else ""
                    is State.Completed -> if (state.exported) UiText.get(R.string.ui_124)
                        else UiText.get(R.string.settings_archive_restored) +
                            (state.runtimeError?.let { "\n\n" + UiText.get(R.string.settings_archive_runtime_failed) + it } ?: "")
                    is State.Failed -> UiText.get(R.string.ui_130) +
                        (state.message ?: UiText.get(R.string.settings_archive_save_failed))
                    else -> ""
                }
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
