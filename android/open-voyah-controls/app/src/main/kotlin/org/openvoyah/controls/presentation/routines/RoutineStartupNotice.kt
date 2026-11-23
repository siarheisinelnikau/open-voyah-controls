package org.openvoyah.controls.presentation.routines

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.theme.CockpitTextAction

/** Local virtual-view actions can bypass Activity.onUserInteraction. Forward all events normally. */
internal class RoutineInteractionFrame(context: Context, private val interacted: () -> Unit) : FrameLayout(context) {
    override fun onRequestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> interacted()
        }
        return super.onRequestSendAccessibilityEvent(child, event)
    }
}

@Composable
internal fun RoutineStartupNotice(name: String, seconds: Int, cancel: () -> Unit) {
    val message = if (seconds == 0) UiText.get(R.string.routines_applying_layout)
        else UiText.get(R.string.routines_pending).format(name, seconds)
    RoutineNotice(message, R.string.routines_stay, cancel)
}

@Composable
internal fun RoutineAppNotice(message: String, dismiss: () -> Unit) {
    RoutineNotice(message, R.string.routines_app_dismiss_notice, dismiss, announce = true)
}

@Composable
private fun RoutineNotice(message: String, action: Int, onAction: () -> Unit, announce: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val textModifier = Modifier.weight(1f).then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            Text(message, textModifier,
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            CockpitTextAction(UiText.get(action), onAction)
        }
    }
}
