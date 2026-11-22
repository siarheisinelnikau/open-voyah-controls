package org.openvoyah.controls.domain.keyboard

/** A keyboard Android lists as installed that the owner can choose for the central display. */
data class KeyboardOption(val id: String, val label: String, val packageName: String)

/** Why an earlier choice was dropped and the factory keyboard returned. */
enum class KeyboardFallback { CRASHED, UNAVAILABLE }

/**
 * The keyboard state read back from Android. [choice] is the stored owner's choice, null when the firmware chooses;
 * [active] is the keyboard Android bound for the central display, null when it bound none; [passenger] is the
 * keyboard of the passenger display's own service on firmware that has one.
 */
data class KeyboardStatus(
    val choice: String? = null,
    val active: String? = null,
    val passenger: String? = null,
    val fallback: KeyboardFallback? = null,
    val fallbackId: String? = null,
) {
    /** True when the stored choice is what Android bound. */
    val confirmed: Boolean get() = choice != null && choice == active
}

/** Whether a choice can be made and read back now. */
enum class KeyboardAvailability {
    /** The window agent in system_server announced the keyboard policy. */
    READY,
    /** Controls is connected, but the window service, which carries the keyboard policy, is off or not running. */
    WINDOW_SERVICE_OFF,
    /** The root channel is not set up or not authorized yet. */
    SETUP_REQUIRED,
    /** This Android version has no window agent. */
    UNSUPPORTED,
}

enum class KeyboardOutcome { APPLIED, NOT_CONFIRMED, NOT_ENABLED, FACTORY, READ }

data class KeyboardResult(val outcome: KeyboardOutcome, val status: KeyboardStatus)

/** Chooses the central display's keyboard and reads back what Android bound. Calls block; run them off the main thread. */
interface KeyboardPort {
    fun availability(): KeyboardAvailability
    fun read(): KeyboardResult
    fun select(id: String): KeyboardResult
    fun restoreFactory(): KeyboardResult
}
