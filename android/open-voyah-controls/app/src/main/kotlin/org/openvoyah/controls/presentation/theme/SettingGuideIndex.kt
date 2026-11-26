package org.openvoyah.controls.presentation.theme

internal val settingGuides: Map<Int, SettingGuide> = buildMap {
    listOf(homeGuides, cardsGuides, displaysGuides, dockGuides, buttonsGuides, voiceGuides,
        appearanceGuides, vehicleGuides, automationGuides, systemGuides).forEach { section ->
        for ((title, guide) in section) check(put(title, guide) == null) { "Duplicate setting guide: $title" }
    }
}
