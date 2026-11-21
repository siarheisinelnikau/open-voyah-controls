package org.openvoyah.controls.presentation.settings

import android.app.Dialog
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.WeakHashMap
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
class SettingsArchiveDialogs @JvmOverloads constructor(
    private val activity: ComponentActivity,
    private val dialogs: CockpitDialogs,
    private val restored: Runnable,
    private val owner: LifecycleOwner = activity,
) {
    private val model = ViewModelProvider(activity, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsArchiveViewModel(
            LocalSettingsArchiveDocuments(activity.application),
            FridaSettingsArchiveRuntime(activity.application),
        ) as T
    })["controls.settings.archive", SettingsArchiveViewModel::class.java]
    private val ownership = Any().also { activeOwners[model] = it }
    private var dialog: Dialog? = null
    private var shown: State? = null
    private var dismissal: ArchiveDialogDismissal? = null
    private var recreating = false
    private var disposed = false

    private val observation = owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { model.state.collect(::render) }
            finally { dismiss() }
        }
    }

    fun export(document: Uri) { if (ownsModel()) model.export(document.toString()) }
    fun prepare(document: Uri) { if (ownsModel()) model.prepare(document.toString()) }

    /** Closing a route invalidates its observer; an already authorized apply still finishes safely. */
    fun dispose() {
        if (disposed) return
        disposed = true
        observation.cancel()
        dismiss()
        if (activeOwners[model] === ownership) {
            activeOwners.remove(model)
            if (!activity.isChangingConfigurations) model.cancel()
        }
    }

    private fun ownsModel(): Boolean = !disposed && activeOwners[model] === ownership

    private fun dismiss() {
        dismissal?.retain()
        dismissal = null
        dialog?.dismiss()
        dialog = null
        shown = null
    }

    private fun render(state: State) {
        if (!ownsModel() || recreating || shown == state) return
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
        val event = ArchiveDialogDismissal { if (ownsModel() && model.state.value == state) model.cancel() }
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
                        if (ownsModel()) model.cancel(); close()
                    }
                    is State.Confirmation -> {
                        CockpitTextAction(UiText.get(R.string.ui_89)) { if (ownsModel()) model.cancel(); close() }
                        CockpitAction(UiText.get(R.string.ui_129), {
                            if (ownsModel()) model.confirm(); close()
                        }, primary = true)
                    }
                    else -> CockpitAction(UiText.get(R.string.ui_83), {
                        if (ownsModel()) model.cancel(); close()
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
                // Each state gets its own dialog, so the bar leaves together with the work.
                if (state is State.Working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    private companion object {
        // Tokens do not retain Activities or models after their ViewModelStore is cleared.
        val activeOwners = WeakHashMap<SettingsArchiveViewModel, Any>()
    }
}
