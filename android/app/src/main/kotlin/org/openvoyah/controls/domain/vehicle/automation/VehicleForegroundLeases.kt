package org.openvoyah.controls.domain.vehicle.automation

internal class VehicleForegroundLease<D>(val owner: Any, val confirmationOwner: Boolean, val dispatch: D)

/** Immutable resumed-window leases. A closed display cannot retain another display's dispatcher. */
internal class VehicleForegroundLeases<D> private constructor(private val entries: List<VehicleForegroundLease<D>>) {
    constructor() : this(emptyList())

    val hasOwners: Boolean get() = entries.isNotEmpty()
    val hasConfirmationOwner: Boolean get() = entries.any { it.confirmationOwner }
    val latest: VehicleForegroundLease<D>? get() = entries.lastOrNull()

    fun started(owner: Any, confirmationOwner: Boolean, dispatch: D): VehicleForegroundLeases<D> =
        VehicleForegroundLeases(entries.filterNot { it.owner === owner } + VehicleForegroundLease(owner, confirmationOwner, dispatch))

    fun stopped(owner: Any): VehicleForegroundLeases<D> =
        if (entries.none { it.owner === owner }) this else VehicleForegroundLeases(entries.filterNot { it.owner === owner })

    /** A selection is invalid after its owner stops or replaces that exact registration. */
    fun retains(lease: VehicleForegroundLease<D>): Boolean = entries.any { it === lease }
}
