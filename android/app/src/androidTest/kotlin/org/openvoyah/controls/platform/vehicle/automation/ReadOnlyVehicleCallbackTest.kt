package org.openvoyah.controls.platform.vehicle.automation

import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.test.AndroidTestCase
import org.openvoyah.controls.domain.vehicle.HeadlightMode
import org.openvoyah.controls.platform.vehicle.QingganHeadlightListener
import org.openvoyah.controls.platform.vehicle.QingganReadOnlyCallbacks

class ReadOnlyVehicleCallbackTest : AndroidTestCase() {
    fun testAdditionalReadOnlyDecoderPreservesTheEstablishedLightingDecoder() {
        val modes = mutableListOf<HeadlightMode>()
        val raw = mutableListOf<List<Int>>()
        val observer = object : QingganReadOnlyCallbacks {
            override fun onConnected(service: IBinder) = Unit
            override fun close() = Unit
            override fun onCallback(code: Int, data: Parcel): Boolean {
                data.enforceInterface("com.qinggan.canbus.ICanBusServiceCallback")
                raw += List(data.dataAvail() / 4) { data.readInt() }
                return true
            }
        }
        val listener = QingganHeadlightListener(Process.myUid(), modes::add, observer)
        val request = Parcel.obtain()
        try {
            request.writeInterfaceToken("com.qinggan.canbus.ICanBusServiceCallback")
            listOf(1, 897, 1114, 1).forEach(request::writeInt)
            listener.transact(36, request, null, IBinder.FLAG_ONEWAY)
            assertEquals(listOf(HeadlightMode.LOW_BEAM), modes)
            assertEquals(listOf(listOf(1, 897, 1114, 1)), raw)
        } finally {
            request.recycle()
        }
    }

    fun testForeignOrSynchronousCallbacksCannotReachTheAdditionalDecoder() {
        var received = 0
        val observer = object : QingganReadOnlyCallbacks {
            override fun onConnected(service: IBinder) = Unit
            override fun close() = Unit
            override fun onCallback(code: Int, data: Parcel): Boolean { received++; return true }
        }
        val rejectedUid = QingganHeadlightListener(Process.myUid() + 1, { fail("Unexpected lighting update") }, observer)
        val acceptedUid = QingganHeadlightListener(Process.myUid(), { fail("Unexpected lighting update") }, observer)
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken("com.qinggan.canbus.ICanBusServiceCallback")
            listOf(1, 0, 0).forEach(request::writeInt)
            rejectedUid.transact(12, request, null, IBinder.FLAG_ONEWAY)
            acceptedUid.transact(12, request, reply, 0)
            assertEquals(0, received)
        } finally {
            request.recycle()
            reply.recycle()
        }
    }
}
