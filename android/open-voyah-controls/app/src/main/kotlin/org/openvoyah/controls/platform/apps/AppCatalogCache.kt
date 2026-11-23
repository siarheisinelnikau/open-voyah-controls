package org.openvoyah.controls.platform.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openvoyah.controls.data.apps.AppGridPreferences
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.apps.AppGridConfiguration

/**
 * The app grid's catalog and settings, kept warm for the life of the process: loaded off the main thread when the
 * process starts (also after boot), and loaded again when Android adds, removes or changes a package. The grid shows
 * this snapshot on its first frame and refreshes it quietly.
 */
internal object AppCatalogCache {
    private val apps = MutableStateFlow<List<LocalApp>?>(null)
    private val grid = MutableStateFlow<AppGridConfiguration?>(null)
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reload: Job? = null

    /** The last loaded catalog, or null before the first load finished. */
    val snapshot: StateFlow<List<LocalApp>?> = apps.asStateFlow()

    /** The last read grid settings, or null before they were read. */
    val settings: StateFlow<AppGridConfiguration?> = grid.asStateFlow()

    /** Starts the background load once per process; cheap on the calling thread. */
    @JvmStatic
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            AppGridPreferences(app).observeSettings().collect { result -> result.getOrNull()?.let { grid.value = it } }
        }
        load(app, delayMillis = 0)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = load(app, delayMillis = PACKAGE_SETTLE_MILLIS)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        try {
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (failure: RuntimeException) {
            ControlsLog.warn("app_catalog_cache", failure, "stage", "receiver")
        }
    }

    /** Puts a catalog loaded elsewhere into the cache, so the next first frame shows it. */
    fun store(list: List<LocalApp>) {
        apps.value = list
    }

    // Several package broadcasts arrive for one install or update; they collapse into one load.
    private fun load(context: Context, delayMillis: Long) {
        synchronized(this) {
            reload?.cancel()
            reload = scope.launch {
                delay(delayMillis)
                try {
                    apps.value = AppsCatalog(context, InstalledAppPermissions(context)).load()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: RuntimeException) {
                    ControlsLog.warn("app_catalog_cache", failure, "stage", "load")
                }
            }
        }
    }

    private const val PACKAGE_SETTLE_MILLIS = 500L
}
