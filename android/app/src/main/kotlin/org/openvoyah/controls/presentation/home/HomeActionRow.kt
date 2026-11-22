package org.openvoyah.controls.presentation.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.cockpitEntrance
import org.openvoyah.controls.presentation.theme.rememberEntranceOrder
import org.openvoyah.controls.presentation.theme.rememberPressMotion
import org.openvoyah.controls.presentation.theme.scaledText

@Composable
internal fun HomeActionRow(title: String, subtitle: String, icon: CockpitIcon, open: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val layout = LocalCockpitLayout.current
    val labelStyle = MaterialTheme.typography.titleMedium.scaledText(layout.navigationLabelScale / layout.textScale)
    val press = rememberPressMotion()
    val arrival = rememberEntranceOrder()?.let { Modifier.cockpitEntrance(it) } ?: Modifier
    Surface(onClick = open, modifier = modifier.fillMaxWidth().then(arrival).then(press.modifier), shape = MaterialTheme.shapes.medium,
        interactionSource = press.source, color = press.color, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(horizontal = layout.spacing(15.dp), vertical = layout.spacing(8.dp)),
            horizontalArrangement = Arrangement.spacedBy(layout.spacing(14.dp)), verticalAlignment = Alignment.CenterVertically) {
            CockpitGlyph(icon, Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = labelStyle)
                if (subtitle.isNotBlank()) Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
            CockpitGlyph(CockpitIcon.ARROW, Modifier.size(20.dp))
        }
    }
}
