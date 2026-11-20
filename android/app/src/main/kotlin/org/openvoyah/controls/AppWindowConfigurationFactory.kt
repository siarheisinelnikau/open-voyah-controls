package org.openvoyah.controls

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import org.openvoyah.controls.data.display.AppWindowPreferences
import org.openvoyah.controls.data.frida.FridaWindowService
import org.openvoyah.controls.domain.display.AppWindowConfiguration

private val windowWrites = Mutex()

internal fun appWindowConfiguration(context: Context): AppWindowConfiguration =
    AppWindowConfiguration(AppWindowPreferences(context), FridaWindowService(context), windowWrites)
