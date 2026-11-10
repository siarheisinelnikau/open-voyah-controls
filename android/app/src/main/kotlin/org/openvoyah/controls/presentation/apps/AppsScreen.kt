package org.openvoyah.controls.presentation.apps

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitHeading
import org.openvoyah.controls.presentation.theme.CockpitIcon
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppsScreen(
    state: AppsViewModel.State,
    back: () -> Unit,
    install: () -> Unit,
    canInstall: () -> Boolean,
    allowInstalls: () -> Unit,
    showSettings: (LocalApp) -> Unit,
    open: (LocalApp) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var allow by rememberSaveable { mutableStateOf(false) }
    val visible = remember(state.apps, query) {
        val search = query.trim().lowercase(Locale.ROOT)
        if (search.isEmpty()) state.apps else state.apps.filter {
            it.label.lowercase(Locale.ROOT).contains(search) || it.packageName.lowercase(Locale.ROOT).contains(search)
        }
    }
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.background) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .padding(horizontal = 22.dp, vertical = 17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CockpitHeading(text(R.string.installed_apps), text(R.string.apps_subtitle), back)
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium, singleLine = true,
                    label = { Text(text(R.string.ui_113)) },
                )
                Button(
                    onClick = { if (canInstall()) install() else allow = true },
                    modifier = Modifier.heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium,
                ) {
                    Text(text(R.string.install_local_apk), style = MaterialTheme.typography.labelLarge)
                }
            }
            AnimatedVisibility(state.status != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
                state.status?.let { message ->
                    Surface(
                        shape = MaterialTheme.shapes.medium, color = colors.surfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(text(message), Modifier.padding(horizontal = 13.dp, vertical = 8.dp))
                    }
                }
            }
            val listMessage = when {
                state.loading -> R.string.apps_loading
                visible.isEmpty() -> R.string.apps_empty
                else -> null
            }
            AnimatedContent(
                listMessage,
                Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(170)).togetherWith(fadeOut(tween(100))) },
                label = "app list",
            ) { message ->
                if (message != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text(message), color = colors.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        GridCells.Adaptive(265.dp),
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(visible, key = { it.id }) { app ->
                            AppTile(app, {
                                if (app.launchEntry == null) showSettings(app) else open(app)
                            }, { showSettings(app) }, Modifier.animateItem(
                                fadeInSpec = tween(150), placementSpec = tween(190), fadeOutSpec = tween(110),
                            ))
                        }
                    }
                }
            }
        }
    }
    if (allow) {
        AlertDialog(
            onDismissRequest = { allow = false },
            title = { Text(text(R.string.install_local_apk)) },
            text = { Text(text(R.string.allow_apk_install)) },
            confirmButton = {
                Button({ allow = false; allowInstalls() }, Modifier.heightIn(min = 50.dp)) {
                    Text(text(R.string.ui_61))
                }
            },
            dismissButton = {
                TextButton({ allow = false }, Modifier.heightIn(min = 50.dp)) { Text(text(R.string.ui_89)) }
            },
        )
    }
}

@Composable
private fun AppTile(app: LocalApp, open: () -> Unit, settings: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Surface(modifier = modifier, shape = shape, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.fillMaxWidth().clip(shape), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 84.dp).combinedClickable(
                    role = Role.Button, onClick = open, onLongClick = settings, onLongClickLabel = text(R.string.app_settings_title),
                ).padding(start = 13.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app, Modifier.size(42.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (app.managed) text(R.string.apps_managed) else app.packageName,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (app.managed) colors.primary else colors.onSurfaceVariant)
                }
            }
            val name = text(R.string.app_settings_for).format(app.label)
            IconButton(settings, Modifier.size(56.dp).semantics { contentDescription = name }) {
                CockpitGlyph(CockpitIcon.SETTINGS, Modifier.size(22.dp))
            }
        }
    }
}

@Composable
internal fun AppIcon(app: LocalApp, modifier: Modifier) {
    val icon = remember(app.icon) { app.icon?.asImageBitmap() }
    if (icon != null) Image(icon, null, modifier)
    else CockpitGlyph(CockpitIcon.APPS, modifier)
}

private fun text(id: Int) = UiText.get(id)
