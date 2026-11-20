package org.openvoyah.controls.presentation.apps

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.settings.VoiceContacts
import org.openvoyah.controls.data.settings.VoiceSettings.LocalContact
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.platform.phone.openPhoneDialer
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

@Composable
internal fun DesktopPhoneFavoritesCard(ids: List<String>, visible: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(UiText.get(R.string.desktop_info_phone), style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (visible) {
            if (ids.isEmpty()) Text(UiText.get(R.string.desktop_info_phone_card_empty),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            else VisiblePhoneFavorites(ids)
        }
    }
}

@Composable
private fun VisiblePhoneFavorites(ids: List<String>) {
    val app = LocalContext.current.applicationContext
    val source = remember(app) { VoiceContacts(app).observe() }
    val contacts by source.collectAsStateWithLifecycle(initialValue = null, minActiveState = Lifecycle.State.RESUMED)
    val available = remember(ids, contacts) {
        contacts?.associateBy(LocalContact::id)?.let { byId -> ids.mapNotNull(byId::get) }
    } ?: return
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember(available) { mutableStateOf(false) }
    if (available.isEmpty()) {
        Text(UiText.get(R.string.desktop_info_phone_card_unavailable), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val layout = LocalCockpitLayout.current
    val twoColumnsAt = layout.columnsMinimum(236.dp, LocalDensity.current.fontScale)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        FlowRow(maxItemsInEachRow = if (maxWidth >= twoColumnsAt) 2 else 1,
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (contact in available) {
                val name = contact.name()
                val description = UiText.get(R.string.desktop_info_phone_dial).format(name)
                OutlinedButton(onClick = {
                    if (activity != null && !activity.isDestroyed && !activity.isFinishing &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        failed = try {
                            openPhoneDialer(activity, contact.phone())
                            false
                        } catch (error: RuntimeException) {
                            ControlsLog.warn("phone_favorite_dial", "outcome", "unavailable", "type", error)
                            true
                        }
                    }
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = description },
                    shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (failed) CockpitFeedback(UiText.get(R.string.desktop_info_phone_dial_failed), error = true)
}
