package org.openvoyah.controls.platform.display

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.display.DisplayEventBus
import org.openvoyah.controls.presentation.display.DisplayPreviewScreen
import org.openvoyah.controls.presentation.display.DisplayPreviewState
import org.openvoyah.controls.presentation.theme.ControlsTheme

/** Owns the private Android display and its native surface while the preview is visible. */
internal class VirtualDisplayPreview(private val activity: ComponentActivity) : SurfaceHolder.Callback {
    sealed interface Status {
        data object Waiting : Status
        data object Unavailable : Status
        data class Ready(val width: Int, val height: Int, val dpi: Int) : Status
    }

    private var holder: SurfaceHolder? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private val content = MutableStateFlow(DisplayPreviewState())
    private var snapshot: DisplayEventBus.Snapshot? = null
    private var active = false
    private var density = 0
    private val current = MutableStateFlow<Status>(Status.Waiting)
    val status = current.asStateFlow()

    fun start(dpi: Int, data: DisplayEventBus.Snapshot) {
        active = true
        density = dpi
        snapshot = data
        rebuild()
    }

    fun stop() {
        active = false
        closeDisplay()
        current.value = Status.Waiting
    }

    fun density(value: Int) {
        if (density == value) return
        density = value
        rebuild()
    }

    fun update(data: DisplayEventBus.Snapshot) {
        snapshot = data
        val track = data.media()
        val trackText = if (track.available()) listOf(track.title(), track.artist()).filter { it.isNotBlank() }.joinToString("\n")
            else UiText.get(R.string.display_no_media)
        val navigation = data.navigation()
        val distance = navigation.meters().takeIf { it >= 0 }?.let { "$it m" }.orEmpty()
        val detail = listOf(navigation.nextRoad(), distance).filter { it.isNotBlank() }.joinToString(" · ")
        val routeText = listOf(navigation.road(), detail).filter { it.isNotBlank() }.joinToString("\n")
        content.value = DisplayPreviewState(trackText, routeText.ifBlank { UiText.get(R.string.display_no_route) })
    }

    private fun rebuild() {
        val target = holder ?: return
        val frame = target.surfaceFrame
        if (!active || !target.surface.isValid || frame.width() <= 0 || frame.height() <= 0) return
        val dpi = density.takeIf { it > 0 } ?: activity.resources.displayMetrics.densityDpi
        val ready = Status.Ready(frame.width(), frame.height(), dpi)
        if (display != null && current.value == ready) return
        closeDisplay()
        try {
            val manager = activity.getSystemService(DisplayManager::class.java)
            val virtual = checkNotNull(manager?.createVirtualDisplay("Open Voyah Controls", ready.width, ready.height, dpi,
                target.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION))
            display = virtual
            presentation = Presentation(activity, virtual.display, R.style.AppTheme).apply {
                window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                setContentView(ComposeView(context).apply {
                    setViewTreeLifecycleOwner(activity)
                    setViewTreeSavedStateRegistryOwner(activity)
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                    setContent { ControlsTheme { DisplayPreviewScreen(content.collectAsStateWithLifecycle().value) } }
                })
                show()
            }
            snapshot?.let(::update)
            current.value = ready
        } catch (_: RuntimeException) {
            closeDisplay()
            current.value = Status.Unavailable
        }
    }

    private fun closeDisplay() {
        try { presentation?.dismiss() }
        finally {
            presentation = null
            display?.release()
            display = null
        }
    }

    override fun surfaceCreated(target: SurfaceHolder) { holder = target; rebuild() }
    override fun surfaceChanged(target: SurfaceHolder, format: Int, width: Int, height: Int) { holder = target; rebuild() }
    override fun surfaceDestroyed(target: SurfaceHolder) {
        if (holder !== target) return
        holder = null
        closeDisplay()
        current.value = Status.Waiting
    }
}
