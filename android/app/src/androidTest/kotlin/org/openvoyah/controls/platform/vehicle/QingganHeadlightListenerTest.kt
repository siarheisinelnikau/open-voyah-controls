package org.openvoyah.controls.platform.vehicle

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import junit.framework.TestCase
import org.openvoyah.controls.domain.vehicle.HeadlightMode

class QingganHeadlightListenerTest : TestCase() {
    fun testMatchesOnlyTheControllerSelectorChangeFromTheBoundUid() {
        val received = mutableListOf<HeadlightMode>()
        val listener = QingganHeadlightListener(Process.myUid(), received::add)
        for (selector in 0..3) send(listener, selector = selector)
        send(listener, code = 40)
        send(listener, code = 41)
        send(listener, ordinal = 898)
        send(listener, logicalValue = 1115)
        send(listener, selector = 4)
        send(listener, flags = 0)
        send(listener, extraField = true)
        send(listener, descriptor = "invalid.listener")
        send(QingganHeadlightListener(Process.myUid() + 1, received::add))

        assertEquals(listOf(HeadlightMode.AUTOMATIC, HeadlightMode.LOW_BEAM, HeadlightMode.POSITION, HeadlightMode.OFF), received)
    }

    fun testOnlyRegistersAndRemovesTheSameObserverHandle() {
        val listener = QingganHeadlightListener(Process.myUid()) { fail("Unexpected report during registration") }
        val observed = mutableListOf<Int>()
        val service = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.qinggan.canbus.ICanBusService")
                assertSame(listener, data.readStrongBinder())
                assertEquals(0, data.dataAvail())
                assertEquals(0, flags)
                observed += code
                checkNotNull(reply).writeNoException()
                reply.writeInt(1)
                return true
            }
        }

        assertTrue(listener.register(service))
        assertTrue(listener.unregister(service))
        assertEquals(listOf(28, 29), observed)
    }

    private fun send(
        listener: QingganHeadlightListener,
        selector: Int = 1,
        ordinal: Int = 897,
        logicalValue: Int = 1114,
        code: Int = 36,
        flags: Int = IBinder.FLAG_ONEWAY,
        extraField: Boolean = false,
        descriptor: String = "com.qinggan.canbus.ICanBusServiceCallback",
    ) {
        val packet = Parcel.obtain()
        try {
            packet.writeInterfaceToken(descriptor)
            packet.writeInt(1)
            packet.writeInt(ordinal)
            packet.writeInt(logicalValue)
            packet.writeInt(selector)
            if (extraField) packet.writeInt(0)
            listener.transact(code, packet, null, flags)
        } finally {
            packet.recycle()
        }
    }
}
