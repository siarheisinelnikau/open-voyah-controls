package org.openvoyah.controls.platform.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import org.openvoyah.controls.domain.display.LoweredScreen
import kotlin.math.roundToInt

/** Overrides the observed lowered state, for previews and tests; null observes the head unit. */
internal val LocalScreenLowered = compositionLocalOf<Boolean?> { null }

/**
 * Whether the display is lowered, from the launcher agent's Settings.Global value and, while composed, the launcher's
 * own switch broadcast: whichever arrived last wins, and the layout switches back as soon as the display rises.
 * Without an agent and before any broadcast it is false, which keeps the normal layout.
 */
@Composable
internal fun rememberScreenLowered(): State<Boolean> {
    val forced = LocalScreenLowered.current
    val context = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(forced ?: readSetting(context)) }
    DisposableEffect(context, forced) {
        if (forced != null) {
            state.value = forced
            return@DisposableEffect onDispose { }
        }
        state.value = readSetting(context)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state.value = readSetting(context) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                state.value = LoweredScreen.fromLayoutType(intent.getIntExtra("type", 0))
            }
        }
        val observing = runCatching {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(LoweredScreen.SETTING), false, observer)
        }.isSuccess
        // The launcher sends it without a permission or package, so the receiver must be exported.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(LoweredScreen.LAYOUT_CHANGED),
            ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            if (observing) context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return state
}

private fun readSetting(context: Context): Boolean =
    LoweredScreen.fromSetting(runCatching { Settings.Global.getString(context.contentResolver, LoweredScreen.SETTING) }.getOrNull())

/**
 * While [lowered], pads the bottom so the content ends where the lowered display stops showing it. The padding comes
 * from the content's own position on the screen, so a window that does not reach the bottom loses less.
 */
@Composable
internal fun Modifier.visibleWhenLowered(lowered: Boolean): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    var hidden by remember { mutableIntStateOf(0) }
    if (!lowered) return this
    return onGloballyPositioned { coordinates ->
        val origin = IntArray(2).also(view::getLocationOnScreen)
        hidden = LoweredScreen.hiddenBelow(origin[1] + coordinates.boundsInRoot().bottom.roundToInt())
    }.padding(bottom = with(density) { hidden.toDp() })
}
