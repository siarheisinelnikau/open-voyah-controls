package org.openvoyah.controls.data.audio

import android.content.Context
import org.openvoyah.controls.domain.audio.RadioBand
import org.openvoyah.controls.domain.audio.RadioStationIdentity
import org.openvoyah.controls.domain.audio.validRadioMediaId

/** A bookmark belongs to the OEM session implementation that published its media ID. */
internal class FactoryRadioPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("factory_radio", Context.MODE_PRIVATE)

    fun read(source: String, band: RadioBand): RadioStationIdentity? {
        val id = try {
            preferences.getString(key(source, band), null)
        } catch (_: ClassCastException) {
            null
        }
        return id?.takeIf(::validRadioMediaId)?.let { RadioStationIdentity(band, it) }
    }

    fun remember(source: String, station: RadioStationIdentity): Boolean {
        require(validRadioMediaId(station.mediaId))
        return preferences.edit().putString(key(source, station.band), station.mediaId).commit()
    }

    private fun key(source: String, band: RadioBand): String = "station.v1.$source.${band.name}"
}
