package org.openvoyah.controls.presentation.theme

import android.app.Dialog
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import java.util.function.IntConsumer
import kotlin.math.roundToInt

/** Hosts app-owned dialogs with the same window motion and theme as the cockpit. */
class CockpitDialogs(private val activity: ComponentActivity) {
    private val open = mutableSetOf<ComponentDialog>()

    fun dismiss() { open.toList().asReversed().forEach { it.dismiss() } }

    @JvmOverloads
    fun message(title: String, body: String, confirm: String?, action: Runnable? = null,
                cancel: String? = null, cancelable: Boolean = true, onDismiss: Runnable? = null): Dialog =
        show(cancelable, { onDismiss?.run() }) { close ->
            CockpitModal(title, footer = {
                cancel?.let { CockpitTextAction(it, close) }
                confirm?.let { CockpitAction(it, { close(); action?.run() }, primary = true) }
            }) {
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

    @JvmOverloads
    fun choices(title: String, choices: Array<String>, selected: IntConsumer,
                extra: String? = null, extraAction: Runnable? = null): Dialog = show { close ->
        CockpitModal(title, footer = {
            extra?.let { CockpitTextAction(it, { close(); extraAction?.run() }) }
            CockpitTextAction(UiText.get(R.string.ui_89), close)
        }) {
            choices.forEachIndexed { index, choice ->
                CockpitChoice(choice.substringBefore('\n'), choice.substringAfter('\n', "").ifBlank { null }) {
                    close()
                    selected.accept(index)
                }
            }
        }
    }

    internal fun show(cancelable: Boolean = true, onDismiss: () -> Unit = {},
                      content: @Composable (close: () -> Unit) -> Unit): Dialog {
        val dialog = ComponentDialog(activity, R.style.CockpitDialogTheme)
        val view = ComposeView(dialog.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ControlsTheme { content(dialog::dismiss) } }
        }
        dialog.setContentView(view)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            open.remove(dialog)
            onDismiss()
        }
        val metrics = activity.resources.displayMetrics
        val available = activity.window.decorView.width.takeIf { it > 0 } ?: metrics.widthPixels
        val width = minOf((620 * metrics.density).roundToInt(), available - (32 * metrics.density).roundToInt())
        dialog.window?.apply {
            setDimAmount(.58f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout(width.coerceAtLeast(1), WRAP_CONTENT)
        }
        open.add(dialog)
        dialog.show()
        return dialog
    }
}

@Composable
internal fun CockpitModal(title: String, footer: (@Composable () -> Unit)? = null, scroll: Boolean = true,
                          headerAction: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 24).coerceAtLeast(1).dp
    val compact = headerAction != null
    Surface(
        Modifier.fillMaxWidth().heightIn(max = maxHeight), shape = MaterialTheme.shapes.extraLarge, color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 14.dp)) {
            if (headerAction == null) Text(title, style = MaterialTheme.typography.headlineSmall)
            else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).padding(start = 5.dp), style = MaterialTheme.typography.headlineSmall)
                headerAction()
            }
            val body = Modifier.fillMaxWidth().weight(1f, fill = false)
            Column(
                if (scroll) body.verticalScroll(rememberScrollState()) else body,
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
            )
            if (footer != null) FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { footer() }
        }
    }
}

@Composable
internal fun CockpitChoice(title: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = MaterialTheme.shapes.medium,
        color = colors.surfaceVariant) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            }
            CockpitGlyph(CockpitIcon.ARROW)
        }
    }
}

@Composable
internal fun CockpitTextAction(title: String, action: () -> Unit) {
    TextButton(action, Modifier.heightIn(min = 48.dp)) { Text(title) }
}

@Composable
internal fun CockpitSwitch(title: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).toggleable(checked, enabled = enabled, role = Role.Switch,
        onValueChange = change), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
