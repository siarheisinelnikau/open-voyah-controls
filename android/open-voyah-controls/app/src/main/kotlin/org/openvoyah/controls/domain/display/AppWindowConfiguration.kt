package org.openvoyah.controls.domain.display

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class WindowConnection { DISCONNECTED, DISABLED, ENABLED }

internal enum class WindowProfileChange { APPLY, RESET }

internal sealed interface WindowProfileDelivery {
    data class Applied(val widthPx: Int, val dpi: Int, val leftPx: Int) : WindowProfileDelivery
    data class DensityOnly(val dpi: Int) : WindowProfileDelivery
    enum class Outcome : WindowProfileDelivery {
        SAVED, NEXT_LAUNCH, UNSUPPORTED_WINDOW, UNSUPPORTED_TASK, NO_SPACE, UNCONFIRMED, FAILED,
    }
}

internal enum class DisplayDefaultsDelivery { SAVED, CENTRAL_ONLY, NEXT_LAUNCH, MOVE_UNAVAILABLE }

internal interface WindowProfileStorage {
    fun load(component: String): AppWindowProfile
    fun save(profile: AppWindowProfile)
    fun reset(component: String)
    fun loadDefaults(): AppDisplayDefaults
    fun saveDefaults(defaults: AppDisplayDefaults)
}

internal interface WindowProfileService {
    val supported: Boolean
    fun connection(): WindowConnection
    fun enable()
    fun apply(component: String, change: WindowProfileChange): WindowProfileDelivery
    fun publishDefaults(passengerRequested: Boolean, moveRequested: Boolean): DisplayDefaultsDelivery
    /** Brings every window the swipe moved back to the central display; false when nobody confirmed it. */
    fun recallMovedWindows(): Boolean
}

internal class AppWindowConfiguration(
    private val storage: WindowProfileStorage,
    private val service: WindowProfileService,
    private val writes: Mutex = Mutex(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Selection(val profile: AppWindowProfile, val connection: WindowConnection)
    data class SavedProfile(val selection: Selection, val delivery: WindowProfileDelivery)

    val supported: Boolean get() = service.supported

    fun supportsProfile(component: String): Boolean = supported && AppWindowProfile.eligible(component)

    suspend fun connection(): WindowConnection = withContext(io) { service.connection() }

    suspend fun readProfile(component: String): Selection = withContext(io) {
        Selection(storage.load(component), service.connection())
    }

    suspend fun changeProfile(profile: AppWindowProfile, change: WindowProfileChange): SavedProfile = withContext(io) {
        writes.withLock {
            val stored = when (change) {
                WindowProfileChange.APPLY -> profile.also(storage::save)
                WindowProfileChange.RESET -> {
                    storage.reset(profile.component)
                    AppWindowProfile(profile.component)
                }
            }
            val connection = service.connection()
            val delivery = if (connection == WindowConnection.ENABLED) service.apply(stored.component, change)
                else WindowProfileDelivery.Outcome.SAVED
            SavedProfile(Selection(stored, connection), delivery)
        }
    }

    suspend fun enableService(): WindowConnection = withContext(io) {
        writes.withLock {
            service.enable()
            service.connection()
        }
    }

    suspend fun readDisplayDefaults(): AppDisplayDefaults = withContext(io) { storage.loadDefaults() }

    suspend fun changeDisplayDefaults(defaults: AppDisplayDefaults): DisplayDefaultsDelivery = withContext(io) {
        writes.withLock {
            storage.saveDefaults(defaults)
            service.publishDefaults(passengerRequested = defaults.secondaryDpi != null,
                moveRequested = defaults.moveFingers != 0)
        }
    }

    suspend fun recallMovedWindows(): Boolean = withContext(io) { writes.withLock { service.recallMovedWindows() } }
}
