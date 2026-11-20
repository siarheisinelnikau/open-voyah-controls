package org.openvoyah.controls.platform.vehicle

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.openvoyah.controls.domain.vehicle.HeadlightMode

/** Only the observer portion of the pinned factory service's AIDL wire contract. */
internal class QingganHeadlightListener(
    private val serviceUid: Int,
    private val onMode: (HeadlightMode) -> Unit,
) : Binder() {
    init {
        attachInterface(null, LISTENER)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == IBinder.INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
        if (code !in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION || Binder.getCallingUid() != serviceUid ||
            flags and IBinder.FLAG_ONEWAY == 0
        ) return false
        if (code != VEHICLE_STATE_CHANGED) return true

        // This callback is distinct from write acknowledgments. The pinned enum parcel carries both IDs.
        val mode = try {
            data.enforceInterface(LISTENER)
            if (data.dataAvail() != 4 * Int.SIZE_BYTES || data.readInt() != 1 ||
                data.readInt() != LAMP_SWITCH_POSITION_FB_ORDINAL || data.readInt() != LAMP_SWITCH_POSITION_FB_VALUE
            ) return true
            when (data.readInt()) {
                0 -> HeadlightMode.AUTOMATIC
                1 -> HeadlightMode.LOW_BEAM
                2 -> HeadlightMode.POSITION
                3 -> HeadlightMode.OFF
                else -> return true
            }
        } catch (_: RuntimeException) {
            return false
        }
        onMode(mode)
        return true
    }

    fun register(service: IBinder): Boolean = observerRequest(service, ADD_CALLBACK)

    fun unregister(service: IBinder): Boolean = observerRequest(service, REMOVE_CALLBACK)

    private fun observerRequest(service: IBinder, code: Int): Boolean {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(SERVICE)
            request.writeStrongBinder(this)
            if (!service.transact(code, request, reply, 0)) return false
            reply.readException()
            reply.readInt() == 1
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val SERVICE = "com.qinggan.canbus.ICanBusService"
        const val LISTENER = "com.qinggan.canbus.ICanBusServiceCallback"
        const val ADD_CALLBACK = 28
        const val REMOVE_CALLBACK = 29
        const val VEHICLE_STATE_CHANGED = 36
        const val LAMP_SWITCH_POSITION_FB_ORDINAL = 897
        const val LAMP_SWITCH_POSITION_FB_VALUE = 1114
    }
}
