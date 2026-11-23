package org.openvoyah.controls.domain.vehicle

import kotlinx.coroutines.flow.Flow

internal interface HeadlightMonitoringStore {
    fun observeWhileControlsOpen(): Flow<Boolean>
    suspend fun setWhileControlsOpen(enabled: Boolean)
}
