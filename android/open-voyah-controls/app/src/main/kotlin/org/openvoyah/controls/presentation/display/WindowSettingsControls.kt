package org.openvoyah.controls.presentation.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.appWindowConfiguration

/**
 * Settings → Windows and screens → App windows: the default app scale of each display and the window swipe between
 * screens. Both belong to the window service, so a head unit without it shows neither row.
 */
@Composable
internal fun WindowSettingsControls() {
    val app = LocalContext.current.applicationContext
    val windows = remember(app) { appWindowConfiguration(app) }
    if (!windows.supported) return
    val store = remember { ViewModelStore() }
    val provider = remember(store, windows) {
        ViewModelProvider(object : ViewModelStoreOwner {
            override val viewModelStore = store
        }, viewModelFactory {
            initializer { AppDisplayDefaultsViewModel(windows) }
            initializer { WindowSwipeViewModel(windows) }
        })
    }
    val defaults = remember(provider) { provider[AppDisplayDefaultsViewModel::class.java] }
    val swipe = remember(provider) { provider[WindowSwipeViewModel::class.java] }
    DisposableEffect(store) { onDispose { store.clear() } }
    // A dialog left open in the background closes, as it did on the virtual display page.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { defaults.close() }
    AppDisplayDefaultsControl(defaults)
    WindowSwipeControl(swipe)
}
