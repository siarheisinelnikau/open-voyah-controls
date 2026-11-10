package org.openvoyah.controls.presentation.home

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitHeading
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.ControlsTheme

/** Compose navigation around the existing Android settings actions. */
class ControlsHome(
    activity: ComponentActivity,
    version: String,
    open: (Destination) -> Unit,
    buttons: () -> View,
    settings: () -> View,
) {
    enum class Destination { APPS, VOICE, DISPLAY, FIRMWARE, LANGUAGE, SYSTEM }

    private var revision by mutableIntStateOf(0)

    init {
        activity.setContent {
            ControlsTheme { HomeScreen(version, revision, open, buttons, settings) }
        }
    }

    fun refresh() {
        revision += 1
    }

    @Composable
    private fun HomeScreen(
        version: String,
        revision: Int,
        open: (Destination) -> Unit,
        buttons: () -> View,
        settings: () -> View,
    ) {
        var page by rememberSaveable { mutableStateOf(Page.OVERVIEW) }
        BackHandler(page != Page.OVERVIEW) { page = Page.OVERVIEW }
        Surface(color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val wide = maxWidth >= 740.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide) {
                        Surface(
                            Modifier.width(204.dp).fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.63f),
                        ) {
                            Column(
                                Modifier.padding(horizontal = 14.dp, vertical = 22.dp).selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("OPEN VOYAH", Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                Text("Controls", Modifier.padding(start = 12.dp, bottom = 17.dp),
                                    style = MaterialTheme.typography.headlineSmall)
                                Page.entries.forEach { entry -> NavItem(entry, page == entry) { page = entry } }
                                Spacer(Modifier.weight(1f))
                                Text("v$version", Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        if (!wide) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Page.entries.forEach { entry ->
                                    NavItem(entry, page == entry, Modifier.weight(1f), compact = true) { page = entry }
                                }
                            }
                        }
                        AnimatedContent(
                            targetState = page,
                            transitionSpec = {
                                (fadeIn(tween(210)) + slideInVertically(tween(210, easing = FastOutSlowInEasing)) { 12 })
                                    .togetherWith(fadeOut(tween(110)))
                            },
                            label = "cockpit page",
                        ) { target ->
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                    .padding(horizontal = if (wide) 30.dp else 16.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                when (target) {
                                    Page.OVERVIEW -> Overview(wide, open) { page = Page.BUTTONS }
                                    Page.BUTTONS -> {
                                        CockpitHeading(UiText.get(target.label))
                                        key(revision) { AndroidView({ buttons() }, Modifier.fillMaxWidth()) }
                                    }
                                    Page.SETTINGS -> {
                                        CockpitHeading(UiText.get(target.label))
                                        SettingsLinks(wide, open)
                                        key(revision) { AndroidView({ settings() }, Modifier.fillMaxWidth()) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Overview(wide: Boolean, open: (Destination) -> Unit, buttons: () -> Unit) {
        CockpitHeading(UiText.get(R.string.cockpit_overview))
        Hero(wide)
        val tiles = listOf(
            Tile(CockpitIcon.APPS, R.string.installed_apps, R.string.cockpit_apps_description, Destination.APPS),
            Tile(CockpitIcon.VOICE, R.string.voice_options_title, R.string.cockpit_voice_description, Destination.VOICE),
            Tile(CockpitIcon.DISPLAY, R.string.virtual_display, R.string.cockpit_display_description, Destination.DISPLAY),
        )
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.forEach { tile -> FeatureTile(tile, Modifier.weight(1f), open) }
            }
        } else {
            tiles.forEach { tile -> FeatureTile(tile, Modifier.fillMaxWidth(), open) }
        }
        ActionRow(UiText.get(R.string.cockpit_panel), UiText.get(R.string.cockpit_panel_description), CockpitIcon.BUTTONS, buttons)
    }

    @Composable
    private fun SettingsLinks(wide: Boolean, open: (Destination) -> Unit) {
        val language = if (UiText.language() == "ru") "Русский" else "English"
        val links = listOf(
            Tile(CockpitIcon.VOICE, R.string.voice_options_title, R.string.cockpit_voice_description, Destination.VOICE),
            Tile(CockpitIcon.DISPLAY, R.string.virtual_display, R.string.cockpit_display_description, Destination.DISPLAY),
            Tile(CockpitIcon.SETTINGS, R.string.firmware_settings, R.string.cockpit_firmware_description, Destination.FIRMWARE),
            Tile(CockpitIcon.SETTINGS, R.string.ui_61, R.string.cockpit_system_description, Destination.SYSTEM),
        )
        if (wide) {
            links.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { tile ->
                        ActionRow(UiText.get(tile.title), UiText.get(tile.description), tile.icon, { open(tile.destination) },
                            Modifier.weight(1f))
                    }
                }
            }
        } else {
            links.forEach { tile ->
                ActionRow(UiText.get(tile.title), UiText.get(tile.description), tile.icon, { open(tile.destination) })
            }
        }
        ActionRow(UiText.get(R.string.cockpit_language), language, CockpitIcon.SETTINGS, { open(Destination.LANGUAGE) })
    }
}

private enum class Page(val label: Int, val icon: CockpitIcon) {
    OVERVIEW(R.string.cockpit_overview, CockpitIcon.HOME),
    BUTTONS(R.string.cockpit_buttons, CockpitIcon.BUTTONS),
    SETTINGS(R.string.cockpit_settings, CockpitIcon.SETTINGS),
}

private data class Tile(val icon: CockpitIcon, val title: Int, val description: Int, val destination: ControlsHome.Destination)

@Composable
private fun NavItem(
    page: Page,
    selected: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSelect: () -> Unit,
) {
    val opacity by animateFloatAsState(if (selected) 1f else 0f, tween(170), label = "navigation highlight")
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Color.Transparent) {
        Row(
            Modifier.background(colors.primaryContainer.copy(alpha = opacity))
                .selectable(selected = selected, role = Role.Tab, onClick = onSelect)
                .heightIn(min = 52.dp).padding(horizontal = if (compact) 7.dp else 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) CockpitGlyph(page.icon)
            Text(UiText.get(page.label), style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hero(wide: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 128.dp).drawWithCache {
                val tint = Brush.horizontalGradient(listOf(Color(0xFF1A2532), Color(0xFF273D55), Color(0xFF142133)))
                val base = size.height * .78f
                val dashboard = Path().apply {
                    moveTo(size.width * .55f, base)
                    cubicTo(size.width * .65f, base, size.width * .68f, base * .38f, size.width * .81f, base * .43f)
                    lineTo(size.width, base * .67f)
                }
                val line = Brush.horizontalGradient(
                    .53f to Color.Transparent, .85f to Color(0xFF789DBE).copy(alpha = .6f),
                    1f to Color(0xFF789DBE).copy(alpha = .15f),
                )
                onDrawBehind {
                    drawRect(tint)
                    if (wide) {
                        drawPath(dashboard, line, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        drawLine(line, Offset(size.width * .54f, base + 9.dp.toPx()), Offset(size.width, base + 9.dp.toPx()),
                            strokeWidth = 1.dp.toPx())
                    }
                }
            },
        ) {
            Column(
                Modifier.fillMaxWidth(if (wide) .69f else 1f).padding(horizontal = 22.dp, vertical = 19.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(UiText.get(R.string.cockpit_headline), style = MaterialTheme.typography.headlineLarge)
                Text(UiText.get(R.string.cockpit_subtitle), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun FeatureTile(tile: Tile, modifier: Modifier, open: (ControlsHome.Destination) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { open(tile.destination) },
        modifier = modifier,
        color = colors.surface,
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 125.dp).padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CockpitGlyph(tile.icon, Modifier.size(26.dp))
                CockpitGlyph(CockpitIcon.ARROW, Modifier.size(20.dp))
            }
            Text(UiText.get(tile.title), style = MaterialTheme.typography.titleLarge)
            Text(UiText.get(tile.description), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, icon: CockpitIcon, open: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = open, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(horizontal = 15.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CockpitGlyph(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                }
            }
            CockpitGlyph(CockpitIcon.ARROW, Modifier.size(20.dp))
        }
    }
}
