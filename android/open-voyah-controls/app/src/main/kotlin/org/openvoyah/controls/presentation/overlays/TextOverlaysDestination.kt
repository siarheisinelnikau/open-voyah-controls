package org.openvoyah.controls.presentation.overlays

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.overlays.TextOverlayManager
import org.openvoyah.controls.domain.overlays.OverlayPhase
import org.openvoyah.controls.domain.permissions.ActionCatalog
import org.openvoyah.controls.presentation.home.withUnverifiedNote
import org.openvoyah.controls.presentation.maintenance.MaintenanceViewModel
import org.openvoyah.controls.presentation.maintenance.MaintenanceWork
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitDialogs
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

/** What the page shows: the bundled sets, the overlays in place and the factory apps' names. */
internal data class TextOverlayView(
    val snapshot: TextOverlayManager.Snapshot?, val error: String?, val labels: Map<String, String>,
    /** Languages whose bundled set was built for the firmware this head unit runs. */
    val fitting: Set<String> = emptySet(),
)

internal enum class TextOverlayAction { RESTART, TURN_ON, UPDATE, ROLLBACK, REFRESH }

/** Settings → Factory apps language. Every change is confirmed; a running change outlives the page. */
@Composable
internal fun TextOverlaysDestination(back: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.componentActivity() }
    val app = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember(app) { TextOverlayManager(app) }
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { MaintenanceViewModel(overlayWork) }
        }).get("text-overlays", MaintenanceViewModel::class.java)
    }
    val dialogs = remember(activity) { CockpitDialogs(activity) }
    DisposableEffect(lifecycleOwner, dialogs) {
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) dialogs.dismiss()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            dialogs.dismiss()
        }
    }
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
    val lifecycle by lifecycleOwner.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    var refresh by remember { mutableIntStateOf(0) }
    val view by produceState(TextOverlayView(null, null, emptyMap()), active, state.working, refresh) {
        if (!active || state.working) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                val snapshot = manager.snapshot()
                val targets = snapshot.bundled.values.flatMap { set -> set.overlays.map { it.target } }.toSet()
                TextOverlayView(snapshot, null, targets.associateWith { app.packageManager.label(it) },
                    snapshot.bundled.filterValues(manager::builtForThisUnit).keys)
            } catch (error: Exception) {
                TextOverlayView(null, error.message ?: UiText.get(R.string.ui_85), emptyMap())
            }
        }
    }

    fun started() = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    fun confirm(title: String, message: String, operation: () -> String) {
        dialogs.message(title, withUnverifiedNote(context, ActionCatalog.TEXT_OVERLAYS, message), title, Runnable {
            if (started()) model.run(operation)
        }, UiText.get(R.string.ui_89))
    }

    fun chooseLanguage(language: String?) {
        if (model.state.value.working || !started()) return
        val snapshot = view.snapshot ?: return
        if (language == null) {
            if (snapshot.device.overlays.isEmpty()) return
            confirm(text(R.string.text_overlays_off), text(R.string.text_overlays_remove_confirm), manager::remove)
            return
        }
        val set = snapshot.bundled[language] ?: return
        if (language !in view.fitting) {
            model.report(text(R.string.text_overlays_other_firmware))
            return
        }
        confirm(TextOverlayManager.languageName(language),
            text(R.string.text_overlays_install_confirm).format(TextOverlayManager.languageName(language), set.overlays.size)) {
            manager.install(language)
        }
    }

    fun select(action: TextOverlayAction) {
        if (model.state.value.working || !started()) return
        when (action) {
            TextOverlayAction.RESTART -> confirm(text(R.string.text_overlays_restart), text(R.string.text_overlays_restart_confirm),
                manager::restart)
            TextOverlayAction.TURN_ON -> model.run {
                text(R.string.text_overlays_turned_on).format(manager.reconcile())
            }
            TextOverlayAction.UPDATE -> view.snapshot?.summary?.language?.let(::chooseLanguage)
            TextOverlayAction.ROLLBACK -> confirm(text(R.string.text_overlays_rollback), text(R.string.text_overlays_rollback_confirm),
                manager::rollback)
            TextOverlayAction.REFRESH -> refresh++
        }
    }

    fun switchApp(target: String, enabled: Boolean) {
        if (!model.state.value.working && started()) model.run { manager.setApp(target, enabled) }
    }

    TextOverlaysScreen(view, state, back, ::chooseLanguage, ::select, ::switchApp)
}

@Composable
internal fun TextOverlaysScreen(
    view: TextOverlayView, state: MaintenanceWork.State, back: () -> Unit, chooseLanguage: (String?) -> Unit,
    select: (TextOverlayAction) -> Unit, switchApp: (String, Boolean) -> Unit,
) {
    val enabled = !state.working
    val snapshot = view.snapshot
    val summary = snapshot?.summary
    CockpitDetail(text(R.string.text_overlays_title), text(R.string.text_overlays_info), back) {
        CockpitColumns(first = {
            CockpitSection(text(R.string.text_overlays_status_heading), CockpitIcon.SETTINGS) {
                CockpitFeedback(view.error ?: snapshot?.let(::statusText), view.error != null)
                snapshot?.lastBoot?.let { CockpitFeedback(text(R.string.text_overlays_boot_failed) + it, true) }
                if (snapshot?.device?.interrupted == true) CockpitFeedback(text(R.string.text_overlays_interrupted), true)
                state.message?.let { CockpitFeedback(it, state.failed) }
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (summary?.phase == OverlayPhase.NEEDS_RESTART) {
                        CockpitAction(text(R.string.text_overlays_restart), { select(TextOverlayAction.RESTART) }, enabled = enabled,
                            primary = true)
                    }
                    if (summary?.phase == OverlayPhase.NEEDS_ENABLE) {
                        CockpitAction(text(R.string.text_overlays_turn_on), { select(TextOverlayAction.TURN_ON) }, enabled = enabled,
                            primary = true)
                    }
                    if (summary?.updateAvailable == true) {
                        CockpitAction(text(R.string.text_overlays_update), { select(TextOverlayAction.UPDATE) }, enabled = enabled)
                    }
                    if (snapshot?.device?.rollbackAvailable == true) {
                        CockpitAction(text(R.string.text_overlays_rollback), { select(TextOverlayAction.ROLLBACK) }, enabled = enabled)
                    }
                    CockpitAction(text(R.string.text_overlays_refresh), { select(TextOverlayAction.REFRESH) }, enabled = enabled)
                }
            }
            CockpitSection(text(R.string.text_overlays_language_heading), CockpitIcon.LANGUAGE, guideId = R.string.text_overlays_title) {
                if (snapshot != null && snapshot.bundled.isEmpty()) CockpitFeedback(text(R.string.text_overlays_none_bundled))
                val current = summary?.language
                CockpitChoice(R.string.text_overlays_off, selected(summary?.phase == OverlayPhase.NONE), enabled && snapshot != null) {
                    chooseLanguage(null)
                }
                snapshot?.bundled?.values?.forEach { set ->
                    val subtitle = if (set.language !in view.fitting) text(R.string.text_overlays_other_firmware)
                        else selected(current == set.language)
                    CockpitChoice(TextOverlayManager.languageName(set.language), subtitle,
                        enabled && set.language in view.fitting) { chooseLanguage(set.language) }
                }
            }
        }, second = {
            val set = snapshot?.installedSet
            CockpitSection(text(R.string.text_overlays_apps_heading), CockpitIcon.APPS, text(R.string.text_overlays_apps_info)) {
                if (set == null) CockpitFeedback(text(R.string.text_overlays_apps_empty))
                set?.overlays?.sortedBy { view.labels[it.target] ?: it.target }?.forEach { overlay ->
                    val device = snapshot.device.overlays.firstOrNull { it.pkg == overlay.pkg }
                    val wanted = overlay.target !in snapshot.disabled
                    // Before the first start Android does not know the overlay yet; the switch then shows the choice.
                    val checked = if (device?.known == true && !snapshot.device.installedThisBoot) device.enabled else wanted
                    val label = view.labels[overlay.target] ?: overlay.target
                    CockpitSwitch(label, checked, enabled && device != null, guideId = R.string.text_overlays_apps_heading) {
                        switchApp(overlay.target, it)
                    }
                    when {
                        device == null -> CockpitFeedback(text(R.string.text_overlays_app_skipped))
                        device.known && device.enabled && !device.active ->
                            CockpitFeedback(text(R.string.text_overlays_app_state) + device.state, true)
                    }
                }
            }
        })
    }
}

private fun statusText(snapshot: TextOverlayManager.Snapshot): String {
    val summary = snapshot.summary
    val name = summary.language?.let(TextOverlayManager::languageName)
    val base = when {
        summary.phase == OverlayPhase.NONE -> text(R.string.text_overlays_state_none)
        name == null -> text(R.string.text_overlays_state_mixed)
        summary.phase == OverlayPhase.NEEDS_RESTART -> text(R.string.text_overlays_state_restart).format(name)
        summary.phase == OverlayPhase.IGNORED -> text(R.string.text_overlays_state_ignored).format(name)
        summary.phase == OverlayPhase.NEEDS_ENABLE ->
            text(R.string.text_overlays_state_enable).format(name, summary.active, summary.total)
        else -> text(R.string.text_overlays_state_active).format(name, summary.active, summary.total)
    }
    return if (summary.updateAvailable) base + "\n" + text(R.string.text_overlays_state_update) else base
}

private fun selected(value: Boolean): String? = if (value) text(R.string.text_overlays_current) else null

private fun PackageManager.label(target: String): String = try {
    getApplicationLabel(getApplicationInfo(target, 0)).toString()
} catch (_: PackageManager.NameNotFoundException) {
    target
}

private fun text(id: Int): String = UiText.get(id)

private val overlayWork = MaintenanceWork(null, { UiText.get(R.string.text_overlays_working) }, {
    UiText.get(R.string.text_overlays_error) + it.message.orEmpty()
})

private tailrec fun Context.componentActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> error("Text overlay destination requires a ComponentActivity")
}
