package org.openvoyah.controls.presentation.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.AppIconPreferences
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.platform.apps.AppIconImages
import org.openvoyah.controls.platform.apps.LocalApp
import org.openvoyah.controls.platform.apps.appIcon
import org.openvoyah.controls.platform.apps.styledIcon
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitSwitch

/**
 * The app's icon in the app grid and on the launcher panel: a picture the driver chooses, or the launcher icon
 * without its white plate (experimental). The panel receives the change at once when the launcher agent runs.
 */
@Composable
internal fun AppIconSettings(app: LocalApp) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { AppIconPreferences(context) }
    val scope = rememberCoroutineScope()
    var choice by remember(app.packageName) { mutableStateOf<AppIconPreferences.Choice?>(null) }
    var preview by remember(app.packageName) { mutableStateOf<Bitmap?>(null) }
    var plainFound by remember(app.packageName) { mutableStateOf(true) }
    var busy by remember(app.packageName) { mutableStateOf(false) }
    var message by remember(app.packageName) { mutableStateOf<Int?>(null) }

    suspend fun show(next: AppIconPreferences.Choice) {
        val (bitmap, found) = withContext(Dispatchers.IO) {
            val drawable = launcherIcon(context, app)
            val styled = drawable?.let { styledIcon(it, next, PREVIEW_PIXELS) }
            (styled ?: drawable?.let { context.appIcon(it) }) to (!next.plain || next.custom != null || styled != null)
        }
        choice = next
        preview = bitmap
        plainFound = found
    }

    fun change(failure: Int, write: () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { write() }
                show(withContext(Dispatchers.IO) { store.choice(app.packageName) })
                FridaBridge(context).publishAsync { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = failure
            } finally {
                busy = false
            }
        }
    }

    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) change(R.string.app_icon_image_failed) {
            store.setCustom(app.packageName, AppIconImages.read(context, uri))
        }
    }

    LaunchedEffect(app.packageName) { show(withContext(Dispatchers.IO) { store.choice(app.packageName) }) }

    val current = choice ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        preview?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(64.dp)) }
        Text(text(if (current.custom != null) R.string.app_icon_custom else R.string.app_icon_launcher),
            style = MaterialTheme.typography.bodyLarge)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CockpitAction(text(R.string.app_icon_choose), {
            try { openImage.launch(arrayOf("image/*")) } catch (_: RuntimeException) { message = R.string.app_icon_picker_unavailable }
        }, enabled = !busy, guideId = R.string.app_icon_title)
        if (current.custom != null) {
            CockpitAction(text(R.string.app_icon_reset), {
                change(R.string.app_icon_save_failed) { store.setCustom(app.packageName, null) }
            }, enabled = !busy, guideId = R.string.app_icon_reset)
        }
    }
    CockpitSwitch(R.string.app_icon_plain, current.plain, enabled = !busy) { plain ->
        change(R.string.app_icon_save_failed) { store.setPlain(app.packageName, plain) }
    }
    Text(text(R.string.app_icon_plain_hint), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (current.plain && current.custom == null && !plainFound) CockpitFeedback(text(R.string.app_icon_plain_not_found))
    message?.let { CockpitFeedback(text(it), error = true) }
}

private fun launcherIcon(context: Context, app: LocalApp): Drawable? = try {
    val packages = context.packageManager
    app.launchEntry?.let { packages.getActivityIcon(it.component()) } ?: packages.getApplicationIcon(app.packageName)
} catch (_: Exception) {
    null
}

private const val PREVIEW_PIXELS = 128

private fun text(id: Int) = UiText.get(id)
