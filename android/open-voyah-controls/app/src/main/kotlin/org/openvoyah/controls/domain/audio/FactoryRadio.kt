package org.openvoyah.controls.domain.audio

internal enum class RadioBand { FM, AM }

internal data class FactoryStation(
    val mediaId: String?,
    val band: RadioBand?,
    val title: String,
) {
    fun identity(): RadioStationIdentity? =
        mediaId?.takeIf(::validRadioMediaId)?.let { id -> band?.let { RadioStationIdentity(it, id) } }
}

internal data class RadioStationIdentity(val band: RadioBand, val mediaId: String)
internal data class RadioQueueStation(val queueId: Long, val station: FactoryStation)

internal fun validRadioMediaId(id: String): Boolean =
    id.length in 1..160 && id == id.trim() && id.none(Char::isISOControl)

/** Pick a band only from entries the current OEM session actually exposes. Prefer that band's saved station. */
internal fun radioBandTarget(
    queue: List<RadioQueueStation>,
    band: RadioBand,
    saved: RadioStationIdentity?,
): RadioQueueStation? {
    val entries = queue.filter { it.station.band == band && uniqueRadioEntry(queue, it) }
    return entries.firstOrNull { it.station.identity() == saved } ?: entries.firstOrNull()
}

internal fun radioSavedTarget(
    queue: List<RadioQueueStation>,
    saved: RadioStationIdentity?,
): RadioQueueStation? = saved?.let { wanted ->
    queue.singleOrNull { it.station.identity() == wanted }?.takeIf { uniqueRadioEntry(queue, it) }
}

private fun uniqueRadioEntry(queue: List<RadioQueueStation>, entry: RadioQueueStation): Boolean {
    val identity = entry.station.identity() ?: return false
    return queue.count { it.queueId == entry.queueId || it.station.identity() == identity } == 1
}
