package org.openvoyah.controls.presentation.apps

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.DesktopWallpaperPreferences
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.platform.apps.DesktopWallpaperImages
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitBackdrop
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.LocalCockpitWallpaperScrim

internal class DesktopWallpaperViewModel(private val context: Context) : ViewModel() {
    data class State(val custom: Set<AppGridDesktop> = emptySet(), val saving: Boolean = false, val failed: Boolean = false)

    private data class Write(val saving: Boolean = false, val failed: Boolean = false)

    private val preferences = DesktopWallpaperPreferences(context)
    private val write = MutableStateFlow(Write())
    val state = combine(preferences.observeCustomDesktops(), write) { custom, result ->
        State(custom, result.saving, result.failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), State())

    fun select(desktop: AppGridDesktop, uri: Uri) = update(desktop) { DesktopWallpaperImages.read(context, uri) }

    fun reset(desktop: AppGridDesktop) = update(desktop) { null }

    fun pickerUnavailable() { write.value = Write(failed = true) }

    private fun update(desktop: AppGridDesktop, image: () -> ByteArray?) {
        val before = write.value
        if (before.saving || !write.compareAndSet(before, Write(saving = true))) return
        viewModelScope.launch {
            write.value = try {
                withContext(Dispatchers.IO) { preferences.set(desktop, image()) }
                Write()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Write(failed = true)
            }
        }
    }
}

@Composable
internal fun DesktopWallpaperSettings(extraDesktops: Boolean, desktopName: (AppGridDesktop) -> String) {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "Wallpaper settings require a ComponentActivity host" }
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { DesktopWallpaperViewModel(activity.applicationContext) }
        })[DesktopWallpaperViewModel::class.java]
    }
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateAsState()
    val state by model.state.collectAsStateWithLifecycle(lifecycleOwner = owner)
    var show by rememberSaveable { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val desktop = AppGridDesktop.entries.firstOrNull { it.storedValue == target }
        target = null
        if (desktop != null && uri != null) model.select(desktop, uri)
    }

    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val available = active && !state.saving && target == null
    CockpitChoice(text(R.string.apps_wallpaper_title), enabled = active) { show = true }
    if (show && active) {
        CockpitAlertDialog(
            onDismissRequest = { show = false },
            title = { Text(text(R.string.apps_wallpaper_title)) },
            confirmButton = { CockpitTextAction(text(R.string.apps_desktop_done)) { show = false } },
            text = {
                Column(Modifier.heightIn(max = 290.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val desktops = if (extraDesktops) AppGridDesktop.entries else listOf(AppGridDesktop.MAIN)
                    for (desktop in desktops) {
                        val custom = desktop in state.custom
                        val name = desktopName(desktop)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CockpitChoice(name, text(if (custom) R.string.apps_wallpaper_custom else R.string.apps_wallpaper_default),
                                enabled = available, modifier = Modifier.weight(1f)) {
                                if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !available) return@CockpitChoice
                                target = desktop.storedValue
                                try { openImage.launch(arrayOf("image/*")) }
                                catch (_: RuntimeException) {
                                    target = null
                                    model.pickerUnavailable()
                                }
                            }
                            if (custom) {
                                val resetDescription = text(R.string.apps_wallpaper_reset_desktop).format(name)
                                CockpitAction(text(R.string.apps_wallpaper_reset),
                                    { if (owner.lifecycle.currentState == Lifecycle.State.RESUMED) model.reset(desktop) },
                                    Modifier.semantics { contentDescription = resetDescription }, enabled = available, destructive = true)
                            }
                        }
                    }
                    if (state.failed) CockpitFeedback(text(R.string.apps_wallpaper_failed), error = true)
                }
            },
        )
    }
}

@Composable
internal fun DesktopWallpaperBackdrop(desktop: AppGridDesktop, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext
    val preferences = remember(app) { DesktopWallpaperPreferences(app) }
    CockpitBackdrop {
        Box(Modifier.fillMaxSize()) {
            // Only the image collector restarts on a desktop change. Each grid keeps its own scroll state.
            key(desktop) {
                val image by remember(preferences, desktop) { preferences.observeImage(desktop) }
                    .collectAsStateWithLifecycle(initialValue = null)
                image?.let { bitmap ->
                    Image(remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = null,
                        modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.matchParentSize().background(LocalCockpitWallpaperScrim.current))
                }
            }
            content()
        }
    }
}

private fun text(id: Int): String = UiText.get(id)
