package org.openvoyah.controls.data.frida

import android.content.Context
import org.openvoyah.controls.presentation.home.SystemRestarts

/** Runs the Settings restart actions through the reviewed root channel; call it off the main thread. */
internal class FridaSystemRestarts(context: Context) : SystemRestarts {
    private val app = context.applicationContext

    override fun restartMultimedia(): SystemRestartScripts.MediaRestart = FridaBridge(app).restartMultimedia()

    override fun rebootSystem() = FridaBridge(app).rebootSystem()
}
