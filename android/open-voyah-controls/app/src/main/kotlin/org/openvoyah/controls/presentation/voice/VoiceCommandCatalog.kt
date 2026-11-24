package org.openvoyah.controls.presentation.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitModal
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTextField

@Composable
internal fun VoiceCatalogFilters(
    query: String,
    onQuery: (String) -> Unit,
    categories: List<VoiceGuideCategory>,
    selected: VoiceGuideCategory?,
    compact: Boolean = false,
    select: (VoiceGuideCategory?) -> Unit,
) {
    val searchField: @Composable (Modifier) -> Unit = { modifier ->
        CockpitTextField(query, onQuery, UiText.get(R.string.ui_185), modifier,
            trailingIcon = if (query.isEmpty()) null else {
                {
                    val clear = UiText.get(R.string.apps_grid_clear_search)
                    IconButton({ onQuery("") }, Modifier.semantics { contentDescription = clear }) {
                        CockpitGlyph(CockpitIcon.CLOSE)
                    }
                }
            })
    }
    if (compact) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            searchField(Modifier.weight(1f))
            VoiceCategoryMenu(categories, selected, select)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val title = UiText.get(R.string.ui_34)
            if (maxWidth >= 500.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(.38f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    searchField(Modifier.weight(.62f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    searchField(Modifier.fillMaxWidth())
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected == null, { select(null) }, label = { Text(UiText.get(R.string.voice_guide_all)) })
            for (category in categories) {
                FilterChip(selected == category, { select(category) }, label = { Text(UiText.get(category.title)) })
            }
        }
    }
}

@Composable
private fun VoiceCategoryMenu(categories: List<VoiceGuideCategory>, selected: VoiceGuideCategory?,
                              select: (VoiceGuideCategory?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun title(category: VoiceGuideCategory?) = UiText.get(category?.title ?: R.string.voice_guide_all)
    Box {
        OutlinedButton({ expanded = true }, Modifier.widthIn(max = 160.dp).heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.medium) {
            Text(title(selected), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            CockpitGlyph(CockpitIcon.EXPAND, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            for (category in listOf(null) + categories) {
                DropdownMenuItem(text = { Text(title(category)) }, onClick = { expanded = false; select(category) })
            }
        }
    }
}

internal fun LazyListScope.voiceCatalogRows(
    groups: Map<VoiceGuideCategory, List<VoiceCommandGuide>>,
    open: (VoiceCatalog.Command) -> Unit,
) {
    if (groups.isEmpty()) item {
        Text(UiText.get(R.string.voice_no_commands), Modifier.padding(vertical = 18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    for ((category, commands) in groups) {
        item(key = category.name, contentType = "category") {
            VoiceCategoryHeading(category)
        }
        items(commands, key = { it.command.id() }, contentType = { "command" }) { command ->
            VoiceCatalogRow(command, { open(command.command) })
        }
    }
}

@Composable
internal fun VoiceCategoryHeading(category: VoiceGuideCategory) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CockpitGlyph(category.icon, tint = colors.primary)
            Column(Modifier.weight(1f)) {
                Text(UiText.get(category.title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text(UiText.get(category.summary), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VoiceCatalogRow(guide: VoiceCommandGuide, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(role = Role.Button, onClick = open)
            .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(guide.command.title(), style = MaterialTheme.typography.titleMedium)
                Text(guide.effect, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            CockpitGlyph(CockpitIcon.ARROW, tint = colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

@Composable
internal fun VoiceCommandDetails(guide: VoiceCommandGuide, close: () -> Unit, configure: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val phrases = guide.command.phrases().distinct().drop(1)
    var variantLimit by rememberSaveable(guide.command.id()) { mutableIntStateOf(5) }
    val category = UiText.get(guide.category.title)
    CockpitModal(category, headerAction = { CockpitTextAction(UiText.get(R.string.ui_172), close) }, footer = if (guide.canAssignApp) {
        { CockpitAction(UiText.get(R.string.ui_188), configure) }
    } else null) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.tertiaryContainer,
            border = BorderStroke(1.dp, colors.outlineVariant)) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CockpitGlyph(CockpitIcon.VOICE, tint = colors.onTertiaryContainer)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(UiText.get(R.string.voice_guide_say), color = colors.onTertiaryContainer,
                        style = MaterialTheme.typography.labelLarge)
                    Text(guide.command.title(), color = colors.onTertiaryContainer, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(UiText.get(R.string.voice_guide_effect), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(guide.effect, style = MaterialTheme.typography.bodyLarge)
            guide.note?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        }
        if (phrases.isNotEmpty()) {
            HorizontalDivider(color = colors.outlineVariant)
            Text(UiText.get(R.string.voice_guide_variants), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            for (phrase in phrases.take(variantLimit)) {
                Box(Modifier.padding(horizontal = 4.dp)) { Text(phrase, color = colors.onSurfaceVariant) }
            }
            if (variantLimit < phrases.size) CockpitTextAction(UiText.get(R.string.voice_guide_more)) { variantLimit += 15 }
        }
    }
}
