package org.openvoyah.controls.presentation.apps

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitBackdrop
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.rememberPressMotion
import java.util.Locale

/** The launcher root contains apps and Controls shortcuts without navigation chrome. */
@Composable
internal fun AppsScreen(
    state: AppsViewModel.State,
    entry: Long,
    navigate: (ControlsRoute) -> Unit,
    listen: () -> Unit,
    system: () -> Unit,
    install: () -> Unit,
    canInstall: () -> Boolean,
    allowInstalls: () -> Unit,
    installEnabled: Boolean,
    discardInstall: (() -> Unit)?,
    showSettings: (LocalApp) -> Unit,
    open: (LocalApp) -> Unit,
) {
    var query by rememberSaveable(entry) { mutableStateOf("") }
    var search by rememberSaveable(entry) { mutableStateOf(false) }
    var allow by rememberSaveable(entry) { mutableStateOf(false) }
    var savedEntry by rememberSaveable { mutableStateOf(entry) }
    // Saved input keys are not validated when an absent route is restored from its registry.
    if (savedEntry != entry) {
        query = ""
        search = false
        allow = false
        savedEntry = entry
    }
    val visible = remember(state.apps, query) {
        val filter = query.trim().lowercase(Locale.ROOT)
        state.apps.filter { it.packageName != "org.openvoyah.controls" &&
            (filter.isEmpty() || it.label.lowercase(Locale.ROOT).contains(filter) || it.packageName.lowercase(Locale.ROOT).contains(filter)) }
    }
    BackHandler(search) { search = false; query = "" }
    val colors = MaterialTheme.colorScheme
    CockpitBackdrop {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedVisibility(search, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                        label = { Text(text(R.string.ui_113)) }, shape = MaterialTheme.shapes.medium)
                    TextButton({ search = false; query = "" }, Modifier.heightIn(min = 52.dp)) { Text(text(R.string.ui_89)) }
                }
            }
            AnimatedVisibility(state.status != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
                state.status?.let { message ->
                    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text(message), Modifier.weight(1f))
                            discardInstall?.let { discard ->
                                TextButton(discard, Modifier.heightIn(min = 48.dp)) { Text(text(R.string.apps_install_discard)) }
                            }
                        }
                    }
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(148.dp), Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "controls-settings") { Shortcut(R.string.ui_111, CockpitIcon.SETTINGS) { navigate(ControlsRoute.SETTINGS) } }
                item(key = "controls-listen") { Shortcut(R.string.quick_voice_title, CockpitIcon.VOICE, open = listen) }
                items(visible, key = { "app:${it.id}" }) { app ->
                    LauncherTile(app.label, { if (app.launchEntry == null) showSettings(app) else open(app) },
                        { showSettings(app) }, Modifier.animateItem(tween(150), tween(190), tween(110))) {
                        AppIcon(app, Modifier.size(60.dp))
                    }
                }
                item(key = "controls-buttons") { Shortcut(R.string.cockpit_buttons, CockpitIcon.BUTTONS) { navigate(ControlsRoute.BUTTONS) } }
                item(key = "controls-display") { Shortcut(R.string.virtual_display, CockpitIcon.DISPLAY) { navigate(ControlsRoute.DISPLAY) } }
                item(key = "controls-vehicle") { Shortcut(R.string.vehicle_settings_title, CockpitIcon.CAR) { navigate(ControlsRoute.VEHICLE) } }
                item(key = "controls-voice") { Shortcut(R.string.voice_options_title, CockpitIcon.VOICE) { navigate(ControlsRoute.VOICE) } }
                item(key = "controls-updates") { Shortcut(R.string.updates_title, CockpitIcon.DOWNLOAD) { navigate(ControlsRoute.UPDATES) } }
                item(key = "controls-system") { Shortcut(R.string.ui_61, CockpitIcon.SETTINGS, open = system) }
                item(key = "controls-install") {
                    Shortcut(R.string.install_local_apk, CockpitIcon.DOWNLOAD, enabled = installEnabled) {
                        if (canInstall()) install() else allow = true
                    }
                }
                item(key = "controls-search") { Shortcut(R.string.ui_113, CockpitIcon.APPS) { search = true } }
                if (state.loading || visible.isEmpty()) item(key = "app-list-state") {
                    Text(text(if (state.loading) R.string.apps_loading else R.string.apps_empty),
                        Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
    if (allow) AlertDialog(onDismissRequest = { allow = false },
        title = { Text(text(R.string.install_local_apk)) }, text = { Text(text(R.string.allow_apk_install)) },
        confirmButton = { Button({ allow = false; allowInstalls() }, Modifier.heightIn(min = 50.dp)) { Text(text(R.string.ui_61)) } },
        dismissButton = { TextButton({ allow = false }, Modifier.heightIn(min = 50.dp)) { Text(text(R.string.ui_89)) } })
}

@Composable
private fun Shortcut(label: Int, icon: CockpitIcon, enabled: Boolean = true, open: () -> Unit) {
    LauncherTile(text(label), open, enabled = enabled) {
        CockpitGlyph(icon, Modifier.size(60.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherTile(label: String, open: () -> Unit, settings: (() -> Unit)? = null,
                         modifier: Modifier = Modifier, enabled: Boolean = true, icon: @Composable () -> Unit) {
    val press = rememberPressMotion()
    Column(modifier.then(press.modifier).fillMaxWidth().heightIn(min = 144.dp)
            .combinedClickable(enabled = enabled, interactionSource = press.source,
                indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button,
                onLongClick = settings, onLongClickLabel = settings?.let { text(R.string.app_settings_title) }, onClick = open)
            .padding(horizontal = 10.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            icon()
            Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
    }
}

@Composable
internal fun AppIcon(app: LocalApp, modifier: Modifier) {
    val icon = remember(app.icon) { app.icon?.asImageBitmap() }
    if (icon != null) Image(icon, null, modifier) else CockpitGlyph(CockpitIcon.APPS, modifier)
}

private fun text(id: Int) = UiText.get(id)
