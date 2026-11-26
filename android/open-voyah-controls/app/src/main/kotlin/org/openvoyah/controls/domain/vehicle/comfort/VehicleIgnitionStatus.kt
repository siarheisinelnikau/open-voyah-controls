package org.openvoyah.controls.domain.vehicle.comfort

/** Android Automotive ignition states, standardized by VehiclePropertyIds.IGNITION_STATE. */
internal enum class IgnitionMode(val androidValue: Int) {
    LOCKED(1), OFF(2), ACCESSORY(3), ON(4), STARTING(5),
}

internal sealed interface IgnitionStatus {
    data object Unavailable : IgnitionStatus
    data object PermissionDenied : IgnitionStatus
    data object Waiting : IgnitionStatus
    data class Reported(val mode: IgnitionMode, val currentConnection: Boolean) : IgnitionStatus
}

internal class IgnitionSamples(private val subscribedAtNanos: Long) {
    private var latest = 0L

    /** An Android initial report can carry the earlier time when this mode changed. */
    fun accept(value: Int?, atNanos: Long, receivedAtNanos: Long): IgnitionStatus? {
        if (atNanos !in 1..receivedAtNanos) {
            if (value != null) return null
            latest = maxOf(latest, receivedAtNanos)
            return IgnitionStatus.Waiting
        }
        if (atNanos <= latest) return null
        latest = atNanos
        val mode = IgnitionMode.entries.firstOrNull { it.androidValue == value } ?: return IgnitionStatus.Waiting
        return IgnitionStatus.Reported(mode, atNanos >= subscribedAtNanos)
    }
}
