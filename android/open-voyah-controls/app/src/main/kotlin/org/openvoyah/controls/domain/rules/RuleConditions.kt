package org.openvoyah.controls.domain.rules

import org.openvoyah.controls.domain.routines.RoutineTimeWindow
import org.openvoyah.controls.domain.vehicle.automation.DrivingMode

internal enum class RuleComparison(val key: String) { AT_MOST("at_most"), AT_LEAST("at_least") }

internal data class RuleThreshold(val amount: Int, val comparison: RuleComparison) {
    fun matches(value: Double): Boolean = when (comparison) {
        RuleComparison.AT_MOST -> value <= amount
        RuleComparison.AT_LEAST -> value >= amount
    }
}

internal data class RuleConditions(
    val ambientLux: RuleThreshold? = null,
    val chargePercent: RuleThreshold? = null,
    val outsideCelsius: RuleThreshold? = null,
    val drivingMode: DrivingMode? = null,
    val timeWindow: RoutineTimeWindow? = null,
) {
    init {
        require(ambientLux == null || ambientLux.amount in 0..100_000)
        require(chargePercent == null || chargePercent.amount in 0..100)
        require(outsideCelsius == null || outsideCelsius.amount in -40..60)
    }

    val isEmpty get() = this == EMPTY

    companion object { val EMPTY = RuleConditions() }
}

internal data class RuleReading(val value: Double, val observedAtNanos: Long, val source: String) {
    fun usable(now: Long, maxAgeNanos: Long): Boolean =
        value.isFinite() && source.isNotBlank() && observedAtNanos in 1..now && now - observedAtNanos <= maxAgeNanos
}

internal data class RuleInputs(
    val ambientLux: RuleReading? = null,
    val chargePercent: RuleReading? = null,
    val localMinute: RuleReading? = null,
)
