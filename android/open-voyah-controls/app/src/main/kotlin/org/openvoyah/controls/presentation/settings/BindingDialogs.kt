package org.openvoyah.controls.presentation.settings

import android.app.Dialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.UUID
import java.util.function.BiPredicate
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.IntConsumer
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.Action
import org.openvoyah.controls.core.Binding
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAppPicker
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitColumns
import org.openvoyah.controls.presentation.theme.CockpitDialogs
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitModal
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.CockpitMotionSettings
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTextField
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion

class BindingDialogs(private val dialogs: CockpitDialogs) {
    fun manual(next: IntConsumer): Dialog = dialogs.show { close ->
        var code by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        CockpitModal(label(R.string.ui_92), footer = {
            CockpitTextAction(label(R.string.ui_89), close)
            CockpitAction(label(R.string.ui_93), {
                val key = code.toIntOrNull()
                if (key == null || key !in 1..65535 || key == 3 || key == 26) invalid = true
                else { close(); next.accept(key) }
            }, primary = true)
        }) {
            NumberField(R.string.ui_91, code, invalid) { code = it; invalid = false }
            if (invalid) CockpitFeedback(label(R.string.ui_94), error = true)
        }
    }

    fun timings(holdMs: Int, debounceMs: Int, save: BiPredicate<Int, Int>): Dialog = dialogs.show { close ->
        var hold by remember { mutableStateOf(holdMs.toString()) }
        var debounce by remember { mutableStateOf(debounceMs.toString()) }
        var invalid by remember { mutableStateOf(false) }
        CockpitModal(label(R.string.ui_117), footer = {
            CockpitTextAction(label(R.string.ui_89), close)
            CockpitAction(label(R.string.ui_104), {
                val h = hold.toIntOrNull()
                val d = debounce.toIntOrNull()
                if (h == null || h !in 250..2000 || d == null || d !in 0..500) invalid = true
                else if (save.test(h, d)) close()
            }, primary = true)
        }) {
            NumberField(R.string.ui_115, hold, invalid) { hold = it; invalid = false }
            NumberField(R.string.ui_116, debounce, invalid) { debounce = it; invalid = false }
            if (invalid) CockpitFeedback(label(R.string.ui_118), error = true)
        }
    }

    fun choose(routines: List<Pair<String, String>>? = null, selected: Consumer<Action>): Dialog = dialogs.show { close ->
        ActionPicker(close, routines = routines) { close(); selected.accept(it) }
    }

    fun edit(code: Int, descriptor: String, initialName: String, existing: Binding?,
             save: Function<Binding, String?>, remove: Runnable, test: Consumer<Action>): Dialog = dialogs.show { close ->
        BindingEditor(code, descriptor, initialName, existing, close, save::apply, remove::run, test::accept)
    }
}

private enum class BindingPage { FORM, SHORT, LONG, REMOVE }

@Composable
private fun BindingEditor(code: Int, descriptor: String, initialName: String, existing: Binding?, close: () -> Unit,
                          save: (Binding) -> String?, remove: () -> Unit, test: (Action) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var shortPress by remember { mutableStateOf(existing?.shortPress ?: Action.NONE) }
    var longPress by remember { mutableStateOf(existing?.longPress ?: Action.NONE) }
    var allDevices by remember { mutableStateOf(descriptor.isEmpty()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(BindingPage.FORM) }
    BackHandler(page != BindingPage.FORM) { page = BindingPage.FORM }
    val motion = LocalCockpitMotion.current

    AnimatedContent(page, transitionSpec = { stepTransition(motion) }, label = "button editor step") { current ->
        when (current) {
            BindingPage.FORM -> CockpitModal(label(if (existing == null) R.string.ui_102 else R.string.ui_103), footer = {
                if (existing != null) CockpitTextAction(label(R.string.ui_105)) { page = BindingPage.REMOVE }
                if (shortPress.isAssigned()) CockpitTextAction(label(R.string.ui_101)) { test(shortPress) }
                CockpitTextAction(label(R.string.ui_89), close)
                CockpitAction(label(R.string.ui_104), {
                    val title = name.trim()
                    if (title.length !in 1..120) error = label(R.string.ui_106)
                    else {
                        val device = if (allDevices) "" else descriptor
                        val binding = Binding(existing?.id ?: UUID.randomUUID().toString(), title, device, code, shortPress, longPress, enabled)
                        error = save(binding)
                        if (error == null) close()
                    }
                }, primary = true)
            }) {
                CockpitTextField(name, { name = it; error = null }, label(R.string.ui_95), Modifier.fillMaxWidth(), error = error != null, guideId = R.string.ui_95)
                CockpitFeedback(error ?: label(R.string.ui_96) + code, error != null)
                CockpitColumns(first = {
                    CockpitChoice(R.string.oem_short, actionLabel(shortPress)) { page = BindingPage.SHORT }
                }, second = {
                    CockpitChoice(R.string.oem_hold, actionLabel(longPress)) { page = BindingPage.LONG }
                }, minimumWidth = 480.dp)
                CockpitSwitch(R.string.ui_98, allDevices, descriptor.isNotEmpty()) { allDevices = it; error = null }
                CockpitSwitch(R.string.ui_99, enabled) { enabled = it }
                CockpitFeedback(label(R.string.ui_100))
            }
            BindingPage.SHORT, BindingPage.LONG -> ActionPicker({ page = BindingPage.FORM }, active = current == page) { action ->
                if (current == BindingPage.SHORT) shortPress = action else longPress = action
                page = BindingPage.FORM
            }
            BindingPage.REMOVE -> CockpitModal(label(R.string.ui_105), footer = {
                CockpitTextAction(label(R.string.ui_89)) { page = BindingPage.FORM }
                CockpitAction(label(R.string.ui_105), { close(); remove() }, primary = true, destructive = true)
            }) { Text(label(R.string.binding_delete_confirm)) }
        }
    }
}

@Composable
private fun ActionPicker(
    back: () -> Unit, active: Boolean = true, routines: List<Pair<String, String>>? = null, select: (Action) -> Unit,
) {
    var page by remember { mutableStateOf(ActionPickerPage.ACTION) }
    val actionList = { page = ActionPickerPage.ACTION }
    BackHandler(page != ActionPickerPage.ACTION && active, onBack = actionList)
    val motion = LocalCockpitMotion.current
    AnimatedContent(page, transitionSpec = { stepTransition(motion) }, label = "action picker step") { current ->
        when (current) {
            ActionPickerPage.APP -> CockpitAppPicker(label(R.string.ui_114), actionList, { entry ->
                if (entry != null) select(Action(Action.Kind.LAUNCH_APP, entry.component().flattenToString(), entry.label()))
            })
            ActionPickerPage.ROUTINE -> CockpitModal(label(R.string.routine_steering_action),
                footer = { CockpitTextAction(label(R.string.ui_89), actionList) }) {
                if (routines.isNullOrEmpty()) Text(label(R.string.routines_empty))
                routines.orEmpty().forEach { (id, name) ->
                    CockpitChoice(name) { select(Action(Action.Kind.RUN_ROUTINE, id, name)) }
                }
            }
            ActionPickerPage.ACTION -> CockpitModal(label(R.string.ui_108),
                footer = { CockpitTextAction(label(R.string.ui_89), back) }) {
                CockpitChoice(label(R.string.ui_109)) { select(Action.NONE) }
                CockpitChoice(label(R.string.ui_110)) { page = ActionPickerPage.APP }
                CockpitChoice(label(R.string.ui_61)) { select(Action(Action.Kind.SYSTEM_SETTINGS, "", label(R.string.ui_61))) }
                CockpitChoice(label(R.string.ui_111)) { select(Action(Action.Kind.CONTROLS_SETTINGS, "", label(R.string.ui_111))) }
                CockpitChoice(label(R.string.controls_apps)) { select(Action(Action.Kind.CONTROLS_APPS, "", label(R.string.controls_apps))) }
                CockpitChoice(label(R.string.quick_voice_title)) { select(Action(Action.Kind.START_VOICE, "", label(R.string.quick_voice_title))) }
                if (routines != null) CockpitChoice(label(R.string.routine_steering_action)) { page = ActionPickerPage.ROUTINE }
            }
        }
    }
}

private enum class ActionPickerPage { ACTION, APP, ROUTINE }

/** A short crossfade between the steps of one dialog; the window resizes with it over the same duration. */
private fun stepTransition(motion: CockpitMotionSettings): ContentTransform = if (motion.enabled) {
    ContentTransform(fadeIn(CockpitMotion.change()), fadeOut(CockpitMotion.exit()),
        sizeTransform = SizeTransform { _, _ -> CockpitMotion.change() })
} else ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)

@Composable
private fun NumberField(title: Int, value: String, invalid: Boolean, change: (String) -> Unit) {
    CockpitTextField(value, change, label(title), Modifier.fillMaxWidth(), error = invalid, guideId = title,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

private fun actionLabel(action: Action): String = when {
    action.kind == Action.Kind.START_VOICE -> label(R.string.quick_voice_title)
    action.kind == Action.Kind.CONTROLS_APPS -> label(R.string.controls_apps)
    action.kind == Action.Kind.CONTROLS_SETTINGS -> label(R.string.ui_111)
    action.isAssigned() -> action.label
    else -> label(R.string.ui_66)
}

private fun label(id: Int): String = UiText.get(id)
