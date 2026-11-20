package org.openvoyah.controls.domain.vehicle

internal enum class AutomotiveHeadlightSignal(val lamp: ExteriorLamp? = null) {
    SELECTOR,
    HEADLIGHTS(ExteriorLamp.HEADLIGHTS),
    HIGH_BEAM(ExteriorLamp.HIGH_BEAM);

    val unavailable: HeadlightFeedback = if (lamp == null) HeadlightFeedback.SelectorUnavailable
        else HeadlightFeedback.ActivityUnavailable(lamp)
}

/** Interprets values from one Automotive property subscription. */
internal class AutomotiveHeadlightSamples(
    private val subscribedAtNanos: Long,
    private val signal: AutomotiveHeadlightSignal = AutomotiveHeadlightSignal.SELECTOR,
) {
    private var newestNanos = 0L
    private var awaitingInitial = true

    fun accept(
        value: Any?,
        timestampNanos: Long,
        receivedAtNanos: Long,
        receivedAtMillis: Long,
        available: Boolean = true,
    ): HeadlightFeedback? {
        if (receivedAtNanos < subscribedAtNanos) return null
        val initial = awaitingInitial
        awaitingInitial = false
        val undated = timestampNanos == 0L
        if (timestampNanos < 0L || timestampNanos > receivedAtNanos || (!undated && timestampNanos < newestNanos)) {
            return null
        }
        val earlier = !undated && timestampNanos < subscribedAtNanos
        if (earlier && !initial) return null

        val numeric = if (available) value as? Int else null
        val lamp = signal.lamp
        val activity = if (lamp == null) null else when (numeric) {
            0 -> LampActivity.OFF
            1 -> LampActivity.ON
            2 -> LampActivity.DAYTIME_RUNNING.takeIf(lamp::supports)
            else -> null
        }
        val mode = if (lamp != null) null else when (numeric) {
            0 -> HeadlightMode.OFF
            1 -> HeadlightMode.ON
            2 -> HeadlightMode.DAYTIME_RUNNING
            256 -> HeadlightMode.AUTOMATIC
            else -> null
        }
        if (undated || timestampNanos == newestNanos) {
            if (activity != null || mode != null) return null
            // Without a sample time, only a reading after this loss was received can establish freshness.
            if (undated) newestNanos = maxOf(newestNanos, receivedAtNanos)
            return signal.unavailable
        }
        if (!earlier) newestNanos = timestampNanos
        val unsupported = if (earlier) null else signal.unavailable
        val ageMillis = (receivedAtNanos - timestampNanos) / 1_000_000L
        if (ageMillis >= receivedAtMillis) return unsupported
        val observedAtMillis = receivedAtMillis - ageMillis
        if (lamp != null && activity != null) {
            return if (earlier) HeadlightFeedback.EarlierLampReport(lamp, EarlierHeadlightReport(activity, observedAtMillis))
            else HeadlightFeedback.ActivityConfirmed(lamp, LampObservation(activity, observedAtMillis), timestampNanos)
        }
        if (mode == null) return unsupported
        return if (earlier) HeadlightFeedback.EarlierSelectorReport(EarlierHeadlightReport(mode, observedAtMillis))
        else HeadlightFeedback.Confirmed(HeadlightObservation(mode, observedAtMillis), timestampNanos)
    }
}
