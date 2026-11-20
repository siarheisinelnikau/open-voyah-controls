package org.openvoyah.controls.presentation.theme

import android.view.ViewParent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText

/** The width of every app dialog, so a dialog opened from Compose matches one hosted by [CockpitDialogs]. */
internal val CockpitDialogWidth = 620.dp

/**
 * A dialog opened from Compose. It has the same surface, border, width and bounded window motion as the dialogs
 * hosted by [CockpitDialogs]: the Material alert container used a raised tone without a border and a narrower
 * width, so two dialogs of the same app looked like different products.
 */
@Composable
internal fun CockpitAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
) {
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 24).coerceAtLeast(1).dp
    // The platform default width is narrower than the app dialogs; the cap below replaces it.
    val window = DialogProperties(properties.dismissOnBackPress, properties.dismissOnClickOutside, properties.securePolicy,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = properties.decorFitsSystemWindows)
    Dialog(onDismissRequest, window) {
        CockpitDialogWindowMotion()
        Surface(
            modifier.padding(horizontal = 16.dp).widthIn(max = CockpitDialogWidth).fillMaxWidth().heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.padding(layout.spacing(20.dp)), verticalArrangement = Arrangement.spacedBy(layout.spacing(14.dp))) {
                Box(Modifier.semantics { heading() }) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                }
                // The body scrolls on its own when it needs to; callers that scroll themselves keep working.
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodyLarge, text)
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.spacing(8.dp), Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(layout.spacing(4.dp)),
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/** Gives the hosting dialog window the cockpit enter and exit motion. */
@Composable
internal fun CockpitDialogWindowMotion() {
    val view = LocalView.current
    DisposableEffect(view) {
        var parent: ViewParent? = view.parent
        while (parent != null && parent !is DialogWindowProvider) parent = parent.parent
        (parent as? DialogWindowProvider)?.window?.setWindowAnimations(R.style.CockpitDialogMotion)
        // This window belongs to the dialog. Keep the exit animation until it is dismissed.
        onDispose { }
    }
}

/**
 * Picks one of a few options. The current value is marked, a tap chooses and closes, and every row is a full
 * touch target. This replaces the per-screen radio dialogs, which differed in row height and buttons.
 */
@Composable
internal fun <T> CockpitOptionDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    detail: ((T) -> String?)? = null,
) {
    CockpitAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { CockpitTextAction(UiText.get(R.string.ui_89), onDismiss) },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                options.forEach { option ->
                    CockpitOptionRow(label(option), detail?.invoke(option), option == selected, enabled) {
                        onDismiss()
                        if (option != selected) onSelect(option)
                    }
                }
            }
        },
    )
}

@Composable
internal fun CockpitOptionRow(title: String, detail: String?, selected: Boolean, enabled: Boolean = true, choose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val press = rememberPressMotion(enabled)
    Surface(
        Modifier.fillMaxWidth().then(press.modifier).heightIn(min = CockpitTouch)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, interactionSource = press.source,
                indication = LocalIndication.current, onClick = choose),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else press.color,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = .78f)) }
            }
        }
    }
}
