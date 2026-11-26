package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.UiText

// Product names, station names and samples of the selected language stay literal.
private val sampleLabels = mapOf(
    "Maps" to R.string.setting_guide_sample_maps,
    "Music" to R.string.setting_guide_sample_music,
    "Clock" to R.string.setting_guide_sample_clock,
    "Voice" to R.string.setting_guide_sample_voice,
    "Video" to R.string.setting_guide_sample_video,
    "Daily drive" to R.string.setting_guide_sample_daily,
    "Trip layout" to R.string.setting_guide_sample_trip_layout,
    "Morning drive" to R.string.setting_guide_sample_morning_drive,
    "Road trip" to R.string.setting_guide_sample_road_trip,
    "«Road trip»" to R.string.setting_guide_sample_spoken_trip,
    "Lights in Park" to R.string.setting_guide_sample_lights_in_park,
    "Maps + Music" to R.string.setting_guide_sample_maps_and_music,
    "Mode" to R.string.setting_guide_sample_mode,
    "Trip" to R.string.setting_guide_sample_trip,
    "Evening" to R.string.setting_guide_sample_evening,
    "Morning" to R.string.setting_guide_sample_morning,
    "«Ready»" to R.string.setting_guide_sample_ready,
    "«Let’s go»" to R.string.setting_guide_sample_lets_go,
)

internal fun GuideStory.localizeSampleLabels(): GuideStory {
    fun labels(frame: GuideFrame): GuideFrame = frame.map { mark ->
        sampleLabels[mark.text]?.let { mark.copy(text = UiText.get(it)) } ?: mark
    }
    return GuideStory(labels(before), labels(after), event?.let(::labels))
}
