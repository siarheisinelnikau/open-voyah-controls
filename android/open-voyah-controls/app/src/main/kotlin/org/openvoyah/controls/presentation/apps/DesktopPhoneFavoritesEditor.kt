package org.openvoyah.controls.presentation.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.settings.VoiceSettings.LocalContact
import org.openvoyah.controls.domain.apps.MAX_DESKTOP_PHONE_FAVORITES
import org.openvoyah.controls.domain.apps.PhoneFavoriteEdit
import org.openvoyah.controls.domain.apps.validPhoneFavoriteId
import org.openvoyah.controls.presentation.dock.ReorderButton
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitTextAction

@Composable
internal fun DesktopPhoneFavoritesEditor(
    saved: List<String>?,
    contacts: List<LocalContact>?,
    enabled: Boolean,
    saveFailed: Boolean,
    back: () -> Unit,
    edit: (String, PhoneFavoriteEdit) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val eligible = remember(contacts) { contacts.orEmpty().filter { validPhoneFavoriteId(it.id()) } }
    val byId = remember(eligible) { eligible.associateBy(LocalContact::id) }
    val candidates = remember(eligible, saved) { eligible.filter { it.id() !in saved.orEmpty() } }
    val close = { if (choosing) choosing = false else back() }
    CockpitAlertDialog(
        onDismissRequest = close,
        title = { Text(UiText.get(if (choosing) R.string.desktop_info_phone_add else R.string.desktop_info_phone)) },
        confirmButton = { CockpitTextAction(UiText.get(R.string.apps_desktop_done), close) },
        text = {
            if (choosing) {
                if (candidates.isEmpty()) Text(UiText.get(R.string.desktop_info_phone_contacts_empty))
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(candidates, key = { it.id() }) { contact ->
                        CockpitChoice(contact.name(), enabled = enabled) {
                            edit(contact.id(), PhoneFavoriteEdit.ADD)
                            choosing = false
                        }
                    }
                }
            } else Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (saved != null && contacts != null) {
                    for ((index, id) in saved.withIndex()) {
                        val name = byId[id]?.name() ?: UiText.get(R.string.desktop_info_phone_missing)
                        FavoriteEditorRow(name, enabled, index > 0, index < saved.lastIndex) { action -> edit(id, action) }
                    }
                    if (saved.isEmpty()) Text(UiText.get(if (eligible.isEmpty()) R.string.desktop_info_phone_contacts_empty
                        else R.string.desktop_info_phone_favorites_empty))
                    if (saved.size < MAX_DESKTOP_PHONE_FAVORITES && candidates.isNotEmpty()) {
                        CockpitChoice(UiText.get(R.string.desktop_info_phone_add), enabled = enabled) { choosing = true }
                    }
                }
                if (saveFailed) CockpitFeedback(UiText.get(R.string.desktop_info_settings_failed), error = true)
            }
        },
    )
}

@Composable
private fun FavoriteEditorRow(name: String, enabled: Boolean, canMoveUp: Boolean, canMoveDown: Boolean,
                              edit: (PhoneFavoriteEdit) -> Unit) {
    val remove = UiText.get(R.string.desktop_info_phone_remove).format(name)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            ReorderButton(UiText.get(R.string.desktop_info_phone_up).format(name), earlier = true, enabled = enabled && canMoveUp) {
                edit(PhoneFavoriteEdit.MOVE_UP)
            }
            ReorderButton(UiText.get(R.string.desktop_info_phone_down).format(name), earlier = false, enabled = enabled && canMoveDown) {
                edit(PhoneFavoriteEdit.MOVE_DOWN)
            }
            IconButton(onClick = { edit(PhoneFavoriteEdit.REMOVE) }, enabled = enabled,
                modifier = Modifier.size(48.dp).semantics { contentDescription = remove }) {
                CockpitGlyph(CockpitIcon.CLOSE, tint = LocalContentColor.current)
            }
        }
    }
}
