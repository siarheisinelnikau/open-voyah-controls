package org.openvoyah.controls.presentation.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.openvoyah.controls.data.updates.AppUpdates

/** Only an interactive launch checks for releases; background services stay independent. */
internal class HomeUpdatesViewModel(application: Application) : AndroidViewModel(application) {
    val checks = AppUpdates.repository(application).checks

    init { AppUpdates.startup(application) }
}
