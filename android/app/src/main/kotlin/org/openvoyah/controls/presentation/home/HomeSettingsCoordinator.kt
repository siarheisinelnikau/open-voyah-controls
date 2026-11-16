package org.openvoyah.controls.presentation.home

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.openvoyah.controls.ButtonCapture
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.Binding
import org.openvoyah.controls.core.PanelControls
import org.openvoyah.controls.platform.input.ButtonInputAdapter
import org.openvoyah.controls.platform.ui.ActionExecutor
import org.openvoyah.controls.presentation.settings.BindingDialogs
import org.openvoyah.controls.presentation.settings.SettingsArchiveDialogs
import org.openvoyah.controls.presentation.theme.CockpitDialogs

/** Owns only foreground windows, native handoffs and capture lifetime; settings belong to the model. */
internal class HomeSettingsCoordinator(
    private val activity: ComponentActivity,
    private val model: HomeControlsViewModel,
    private val lifecycleOwner: LifecycleOwner,
) : HomeActions {
    private val dialogs = CockpitDialogs(activity)
    private val bindings = BindingDialogs(dialogs)
    private val capture = ButtonCapture()
    private var archives: SettingsArchiveDialogs? = null
    private var progress: Dialog? = null
    private var notice: Dialog? = null
    private var learning: Dialog? = null
    private var learningGeneration = 0L
    private var owner = 0L
    private var attached = false
    private var foreground = false
    private var launchExport: (() -> Unit)? = null
    private var launchImport: (() -> Unit)? = null
    private val active: Boolean get() = attached && foreground && !activity.isFinishing && !activity.isDestroyed

    fun documents(export: () -> Unit, importDocument: () -> Unit) { launchExport = export; launchImport = importDocument }

    fun attach() {
        attached = true
        owner = model.attach()
        archives = SettingsArchiveDialogs(activity, dialogs, Runnable(model::refresh), lifecycleOwner)
    }

    fun resume() { foreground = true; model.refresh(); render(model.state.value) }

    fun pause() {
        foreground = false
        cancelLearning()
        progress?.dismiss()
        progress = null
    }

    fun dispose() {
        if (!attached) return
        attached = false
        pause()
        archives?.dispose()
        archives = null
        dialogs.dismiss()
        notice = null
        model.detach(owner)
        launchExport = null
        launchImport = null
    }

    fun render(state: HomeControlsState) {
        if (!active) return
        if (state.busy) {
            if (progress == null) progress = dialogs.message(text(R.string.ui_79), text(R.string.ui_80), null,
                cancelable = false)
            return
        }
        progress?.dismiss()
        progress = null
        if (notice != null) return
        val next = state.notices.firstOrNull() ?: return
        model.acknowledgeNotice(next.id)
        notice = dialogs.message(next.title, next.message, text(R.string.ui_83), onDismiss = Runnable {
            notice = null
            if (active) render(model.state.value)
        })
    }

    fun exportDocument(document: Uri) {
        if (attached && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) archives?.export(document)
    }
    fun importDocument(document: Uri) {
        if (attached && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) archives?.prepare(document)
    }

    fun open(destination: HomeDestination, navigate: (HomeDestination) -> Unit) {
        if (!active) return
        if (destination == HomeDestination.SYSTEM) external(Intent(Settings.ACTION_SETTINGS)) else navigate(destination)
    }

    fun language() {
        if (!active) return
        dialogs.choices(text(R.string.cockpit_language), arrayOf("Русский", "English"), { index ->
            if (active) { UiText.language(if (index == 0) "ru" else "en"); activity.recreate() }
        })
    }

    override fun buttonsEnabled(enabled: Boolean) { if (active) model.buttonsEnabled(enabled) }
    override fun panelEnabled(enabled: Boolean) { if (active) model.panelEnabled(enabled) }
    override fun choosePanel(slot: String) {
        if (!active || model.state.value.panels.none { it.slot == slot }) return
        bindings.choose { if (active) model.choosePanel(slot, it) }
    }
    override fun editBinding(id: String) {
        if (!active) return
        val binding = model.binding(id)
        if (binding == null) error(text(R.string.binding_changed)) else edit(binding.keyCode, binding.device, binding.label, binding)
    }

    override fun command(command: HomeCommand) {
        if (!active) return
        when (command) {
            HomeCommand.ACCESSIBILITY -> external(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            HomeCommand.CAPTURE -> learn()
            HomeCommand.MANUAL -> bindings.manual { code -> if (active) edit(code, "", KeyEvent.keyCodeToString(code), null) }
            HomeCommand.STEERING -> steering()
            HomeCommand.TIMINGS -> bindings.timings(model.state.value.holdMs, model.state.value.debounceMs) { hold, debounce ->
                active && model.timings(hold, debounce)
            }
            HomeCommand.RESET -> dialogs.message(text(R.string.ui_119), text(R.string.ui_120), text(R.string.ui_121),
                Runnable { if (active) model.reset() }, text(R.string.ui_89))
            HomeCommand.EXPORT -> document(launchExport)
            HomeCommand.IMPORT -> document(launchImport)
            HomeCommand.CONNECT -> model.run(PanelControls.Operation.CONNECT)
            HomeCommand.STATUS -> model.run(PanelControls.Operation.STATUS)
            HomeCommand.DISCONNECT -> model.run(PanelControls.Operation.DISCONNECT)
        }
    }

    private fun steering() {
        val rows = model.state.value.steering
        dialogs.choices(text(R.string.oem_steering), rows.map { it.title + "\n" + it.action }.toTypedArray(), { index ->
            if (active) rows.getOrNull(index)?.let { row -> bindings.choose { if (active) model.chooseSteering(row.slot, it) } }
        }, text(R.string.oem_restore_keys), Runnable {
            if (active) dialogs.message(text(R.string.oem_restore_keys), text(R.string.oem_restore_keys_info), text(R.string.ui_187),
                Runnable { if (active) model.clearSteering() }, text(R.string.ui_89))
        })
    }

    private fun learn() {
        cancelLearning()
        val request = ++learningGeneration
        learning = dialogs.message(text(R.string.ui_87), text(R.string.ui_88), null, cancel = text(R.string.ui_89),
            onDismiss = Runnable { if (request == learningGeneration) cancelLearning() })
        val started = capture.begin(object : ButtonInputAdapter.Capture {
            override fun captured(code: Int, descriptor: String, deviceName: String) {
                if (!active || request != learningGeneration) return
                finishLearning()
                edit(code, descriptor, KeyEvent.keyCodeToString(code) + " · " + deviceName, null)
            }
            override fun expired() {
                if (!active || request != learningGeneration) return
                finishLearning()
                error(text(R.string.ui_90))
            }
        })
        if (!started) { cancelLearning(); error(text(R.string.ui_86)) }
    }

    private fun finishLearning() {
        learningGeneration += 1
        val previous = learning
        learning = null
        previous?.dismiss()
    }

    private fun cancelLearning() { capture.cancel(); finishLearning() }

    private fun edit(code: Int, descriptor: String, label: String, existing: Binding?) {
        bindings.edit(code, descriptor, label, existing,
            { binding -> if (active) model.saveBinding(binding, existing?.id) else text(R.string.binding_changed) },
            { if (active && existing != null) model.removeBinding(existing.id) },
            { action -> if (active) try { ActionExecutor(activity).execute(action) } catch (_: RuntimeException) { error(text(R.string.ui_85)) } })
    }

    private fun document(launch: (() -> Unit)?) {
        try { if (launch == null) error(text(R.string.ui_122)) else launch() }
        catch (_: RuntimeException) { error(text(R.string.ui_122)) }
    }
    private fun external(intent: Intent) {
        try { activity.startActivity(intent) } catch (_: RuntimeException) { error(text(R.string.ui_85)) }
    }
    private fun error(message: String) { if (active) dialogs.message(text(R.string.ui_84), message, text(R.string.ui_83)) }
    private fun text(id: Int): String = UiText.get(id)
}
