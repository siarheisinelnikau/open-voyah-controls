package org.openvoyah.controls

import org.openvoyah.controls.platform.input.ButtonInputAdapter

/** Keeps the service's package-private entry points behind one cancellable foreground owner. */
internal class ButtonCapture {
    private class Session(val service: ButtonAccessibilityService)
    private var session: Session? = null

    fun begin(callback: ButtonInputAdapter.Capture): Boolean {
        cancel()
        val service = ButtonAccessibilityService.connected() ?: return false
        val request = Session(service)
        session = request
        active = request
        service.beginCapture(object : ButtonInputAdapter.Capture {
            override fun captured(keyCode: Int, descriptor: String, deviceName: String) {
                if (complete(request)) callback.captured(keyCode, descriptor, deviceName)
            }
            override fun expired() { if (complete(request)) callback.expired() }
        })
        return true
    }

    fun cancel() {
        val request = session ?: return
        session = null
        if (active === request) {
            active = null
            request.service.cancelCapture()
        }
    }

    private fun complete(request: Session): Boolean {
        if (session !== request || active !== request) return false
        session = null
        active = null
        return true
    }

    private companion object { var active: Session? = null }
}
