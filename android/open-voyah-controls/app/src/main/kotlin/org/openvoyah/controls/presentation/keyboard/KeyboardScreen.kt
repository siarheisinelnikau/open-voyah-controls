package org.openvoyah.controls.presentation.keyboard

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.RootKeyboardPort
import org.openvoyah.controls.domain.keyboard.KeyboardAvailability
import org.openvoyah.controls.domain.keyboard.KeyboardFallback
import org.openvoyah.controls.platform.keyboard.InstalledKeyboard
import org.openvoyah.controls.platform.keyboard.InstalledKeyboards
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitTouch
import org.openvoyah.controls.presentation.theme.rememberPressMotion

internal const val KEYBOARD_FACTORY_TAG = "keyboardFactory"
internal fun keyboardOptionTag(id: String) = "keyboardOption:$id"

/** Chooses the central display's keyboard; every change is read back from Android before the page shows it. */
@Composable
internal fun KeyboardDestination(back: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.keyboardActivity() }
    val app = activity.applicationContext
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory { initializer {
            KeyboardViewModel(RootKeyboardPort(app), object : KeyboardCatalog {
                override fun keyboards() = InstalledKeyboards.list(app)
                override fun labels() = InstalledKeyboards.labels(app)
            })
        } })["keyboard.destination", KeyboardViewModel::class.java]
    }
    val owner = LocalLifecycleOwner.current
    // A keyboard installed, removed or switched off elsewhere shows when the page returns to the foreground.
    LaunchedEffect(owner) {
        var first = true
        owner.lifecycle.currentStateFlow.collect {
            if (it == Lifecycle.State.RESUMED) { if (!first) model.refresh(); first = false }
        }
    }
    val state by model.state.collectAsStateWithLifecycle()
    KeyboardScreen(state, back, model::select, model::restoreFactory)
}

@Composable
internal fun KeyboardScreen(state: KeyboardViewModel.State, back: () -> Unit, select: (String) -> Unit, factory: () -> Unit) {
    val status = state.status
    CockpitDetail(UiText.get(R.string.keyboard_title), UiText.get(R.string.keyboard_page_summary), back) {
        CockpitSection(UiText.get(R.string.keyboard_current_section), CockpitIcon.KEYBOARD) {
            when {
                status != null -> {
                    Text(UiText.get(R.string.keyboard_active).format(state.label(status.active) ?: UiText.get(R.string.keyboard_none)))
                    status.passenger?.let { Text(UiText.get(R.string.keyboard_passenger).format(state.label(it) ?: it),
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (status.choice == null && status.fallback != null) {
                        val name = state.label(status.fallbackId) ?: ""
                        Text(UiText.get(if (status.fallback == KeyboardFallback.CRASHED) R.string.keyboard_fallback_crashed
                            else R.string.keyboard_fallback_unavailable).format(name), color = MaterialTheme.colorScheme.error)
                    }
                }
                state.loading -> Text(UiText.get(R.string.keyboard_reading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            availabilityText(state.availability)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        CockpitSection(UiText.get(R.string.keyboard_choice_section), CockpitIcon.KEYBOARD, UiText.get(R.string.keyboard_choice_info),
            guideId = R.string.keyboard_choice_section) {
            val factoryEnabled = !state.loading && !state.working && status != null
            KeyboardRow(UiText.get(R.string.keyboard_factory), UiText.get(R.string.keyboard_factory_detail), null,
                selected = status != null && status.choice == null, enabled = factoryEnabled, tag = KEYBOARD_FACTORY_TAG,
                choose = factory)
            state.keyboards.forEach { keyboard ->
                KeyboardRow(keyboard.option.label, keyboard.option.packageName, keyboard,
                    selected = status?.choice == keyboard.option.id, enabled = state.canChoose,
                    tag = keyboardOptionTag(keyboard.option.id)) { select(keyboard.option.id) }
            }
            if (!state.loading && state.keyboards.isEmpty()) {
                Text(UiText.get(R.string.keyboard_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status?.choice != null || status?.fallback != null) {
                CockpitAction(UiText.get(R.string.keyboard_factory), factory, enabled = factoryEnabled)
            }
        }
        CockpitFeedback(noticeText(state), error = state.notice?.kind.let {
            it == KeyboardNotice.Kind.FAILED || it == KeyboardNotice.Kind.READ_FAILED || it == KeyboardNotice.Kind.NOT_CONFIRMED ||
                it == KeyboardNotice.Kind.NOT_ENABLED
        })
    }
}

@Composable
private fun KeyboardRow(title: String, detail: String, keyboard: InstalledKeyboard?, selected: Boolean, enabled: Boolean, tag: String,
    choose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val press = rememberPressMotion(enabled)
    val icon = remember(keyboard?.icon) { keyboard?.icon?.asImageBitmap() }
    Surface(
        Modifier.fillMaxWidth().testTag(tag).then(press.modifier).heightIn(min = CockpitTouch)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, interactionSource = press.source,
                indication = LocalIndication.current, onClick = choose),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else press.color,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
                else CockpitGlyph(CockpitIcon.KEYBOARD, Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = .78f))
            }
        }
    }
}

private fun availabilityText(availability: KeyboardAvailability?): String? = when (availability) {
    KeyboardAvailability.WINDOW_SERVICE_OFF -> UiText.get(R.string.keyboard_window_service_off)
    KeyboardAvailability.SETUP_REQUIRED -> UiText.get(R.string.keyboard_setup_required)
    KeyboardAvailability.UNSUPPORTED -> UiText.get(R.string.keyboard_unsupported)
    KeyboardAvailability.READY, null -> null
}

private fun noticeText(state: KeyboardViewModel.State): String? {
    if (state.working) return UiText.get(R.string.keyboard_working)
    val notice = state.notice ?: return null
    val name = state.label(notice.id) ?: ""
    return when (notice.kind) {
        KeyboardNotice.Kind.APPLIED -> UiText.get(R.string.keyboard_applied).format(name)
        KeyboardNotice.Kind.NOT_CONFIRMED -> UiText.get(R.string.keyboard_not_confirmed).format(name)
        KeyboardNotice.Kind.NOT_ENABLED -> UiText.get(R.string.keyboard_not_enabled).format(name)
        KeyboardNotice.Kind.FACTORY -> UiText.get(R.string.keyboard_factory_restored)
        KeyboardNotice.Kind.FAILED -> UiText.get(R.string.keyboard_failed)
        KeyboardNotice.Kind.READ_FAILED -> UiText.get(R.string.keyboard_read_failed)
    }
}

private tailrec fun Context.keyboardActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.keyboardActivity()
    else -> error("Keyboard destination requires a ComponentActivity")
}
