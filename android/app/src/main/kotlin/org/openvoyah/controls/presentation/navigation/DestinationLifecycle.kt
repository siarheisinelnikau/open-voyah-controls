package org.openvoyah.controls.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Outgoing animation frames cannot consume permissions, installer approvals or live preview work. */
@Composable
internal fun DestinationLifecycle(active: Boolean, content: @Composable () -> Unit) {
    val host = LocalLifecycleOwner.current
    val enabled = rememberUpdatedState(active)
    val owner = remember { RouteLifecycleOwner() }
    fun update() {
        val state = host.lifecycle.currentState
        owner.registry.currentState = if (enabled.value || state < Lifecycle.State.CREATED) state else Lifecycle.State.CREATED
    }
    DisposableEffect(host, owner) {
        val observer = LifecycleEventObserver { _, _ -> update() }
        host.lifecycle.addObserver(observer)
        update()
        onDispose {
            host.lifecycle.removeObserver(observer)
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }
    SideEffect { update() }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

private class RouteLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}
