package org.openvoyah.controls.presentation.theme

import org.openvoyah.controls.R
import org.openvoyah.controls.presentation.theme.CockpitIcon.*
import org.openvoyah.controls.presentation.theme.Ink.*

internal val voiceGuides = mapOf(
    R.string.voice_recognition to SettingGuide(R.array.guide_voice_recognition) {
        story({ microphone(on = false) }, { microphone(on = true) })
    },
    R.string.voice_language to SettingGuide(R.array.guide_voice_language) {
        story({ recognition("EN", "Hello") }, { recognition("RU", "Привет") })
    },
    R.string.voice_recognition_engine to SettingGuide(R.array.guide_voice_recognition_engine) {
        story(
            { recognition("Vosk", "Привет") },
            {
                recognition("Zipformer", "Привет")
                icon("offline", FIRMWARE, 96, 14, 18)
            },
        )
    },
    R.string.voice_online_title to SettingGuide(R.array.guide_voice_online_title) {
        GuideStory(frame { fallback(false, false) }, frame { fallback(true, true) }, frame { fallback(true, false) })
    },
    R.string.voice_online_switch to SettingGuide(R.array.guide_voice_online_switch) {
        story({ fallback(false, false) }, { fallback(true, false) })
    },
    R.string.voice_online_key to SettingGuide(R.array.guide_voice_online_key) {
        story(
            {
                icon("key", LOCK, 12, 22, 33, MUTED)
                word("token", "— — —", 84, 34, 15, MUTED)
                check("ready", 94, 55, false)
            },
            {
                icon("key", LOCK, 12, 22, 33)
                word("token", "••••••", 84, 34, 16)
                check("ready", 94, 55, true)
            },
        )
    },
    R.string.default_levels to SettingGuide(R.array.guide_default_levels) { seat ->
        GuideStory(frame { seatRequest(seat, 1) }, frame { seatRequest(seat, 3) },
            frame { seatRequest(seat, 3); gesture("speech-request", 57, 35) })
    },
    R.string.window_options to SettingGuide(R.array.guide_window_options) {
        story({ windows(8, 5) }, { windows(15, 13) })
    },
    R.string.double_smoke to SettingGuide(R.array.guide_double_smoke) {
        story({ windows(5, 0, driverOnly = true) }, { windows(14, 0, driverOnly = true) })
    },
    R.string.double_ventilation to SettingGuide(R.array.guide_double_ventilation) {
        story({ windows(5, 5) }, { windows(13, 13) })
    },
    R.string.voice_vehicle_commands to SettingGuide(R.array.guide_voice_vehicle_commands) {
        story(
            { connector("safety", VOICE, CAR, false) },
            {
                connector("safety", VOICE, CAR)
                icon("check", LOCK, 55, 49, 22)
            },
        )
    },
    R.string.voice_audio_settings to SettingGuide(R.array.guide_voice_audio_settings) {
        story(
            {
                tile("mic", VOICE, 16, 24, 29)
                tile("reply", PLAY, 87, 24, 29, MUTED, BORDER)
            },
            {
                tile("mic", VOICE, 16, 24, 29)
                signal("cue", 48, 38, 7, 16)
                tile("reply", PLAY, 87, 24, 29)
            },
        )
    },
    R.string.voice_spoken_feedback to SettingGuide(R.array.guide_voice_spoken_feedback) {
        story({ voicePopup(result = true) }, { voicePopup(result = true); signal("reply", 78, 60, 5, 12) })
    },
    R.string.voice_speech_rate to SettingGuide(R.array.guide_voice_speech_rate) {
        story({ speechRate(false) }, { speechRate(true) })
    },
    R.string.voice_speech_volume to SettingGuide(R.array.guide_voice_speech_volume) {
        story({ replyLevel(25) }, { replyLevel(70) })
    },
    R.string.voice_feedback_style to SettingGuide(R.array.guide_voice_feedback_style) {
        story(
            {
                voiceBubble("«Radio»", 63, 35)
                tile("speaker", VOICE, 44, 47, 21)
            },
            {
                voiceBubble("FM 97.5 ✓", 63, 35)
                tile("speaker", VOICE, 76, 47, 21)
            },
        )
    },
    R.string.voice_reply_channel to SettingGuide(R.array.guide_voice_reply_channel) {
        story({ channels(false) }, { channels(true) })
    },
    R.string.voice_offline_voice to SettingGuide(R.array.guide_voice_offline_voice) {
        story(
            {
                icon("offline", FIRMWARE, 16, 25, 25)
                voiceBubble("A", 77, 22)
                signal("voice-a", 59, 53, 6, 16)
            },
            {
                icon("offline", FIRMWARE, 16, 25, 25)
                voiceBubble("B", 77, 22)
                signal("voice-b", 55, 53, 9, 26, 4)
            },
        )
    },
    R.string.voice_ready_cue to SettingGuide(R.array.guide_voice_ready_cue) {
        story({ cue(beforeListening = true, enabled = false) }, { cue(beforeListening = true, enabled = true) })
    },
    R.string.voice_ready_style to SettingGuide(R.array.guide_voice_ready_style) {
        story({ cue(beforeListening = true, enabled = true) }, { cue(beforeListening = true, enabled = true, spoken = true) })
    },
    R.string.voice_ready_phrase to SettingGuide(R.array.guide_voice_ready_phrase) {
        story(
            {
                voiceBubble("«Ready»", 66, 24)
                icon("mic", VOICE, 55, 45, 22)
            },
            {
                voiceBubble("«Let’s go»", 66, 24)
                icon("mic", VOICE, 55, 45, 22)
            },
        )
    },
    R.string.voice_ready_cue_volume to SettingGuide(R.array.guide_voice_ready_cue_volume) {
        story(
            { cue(beforeListening = true, enabled = true, tall = 8) },
            { cue(beforeListening = true, enabled = true, tall = 29) },
        )
    },
    R.string.voice_end_listening_cue to SettingGuide(R.array.guide_voice_end_listening_cue) {
        story({ cue(beforeListening = false, enabled = false) }, { cue(beforeListening = false, enabled = true) })
    },
    R.string.voice_end_cue_volume to SettingGuide(R.array.guide_voice_end_cue_volume) {
        story(
            { cue(beforeListening = false, enabled = true, tall = 9) },
            { cue(beforeListening = false, enabled = true, tall = 27) },
        )
    },
    R.string.voice_capture_popup to SettingGuide(R.array.guide_voice_capture_popup) {
        GuideStory(
            frame { voicePopup(result = false) },
            frame { voicePopup(result = true) },
            frame {
                voicePopup(result = false)
                word("captured", "«FM»", 82, 41, 14)
            },
        )
    },
    R.string.voice_listening_limit to SettingGuide(R.array.guide_voice_listening_limit) {
        story({ duration(VOICE, 3, 50) }, { duration(VOICE, 6, 95) })
    },
    R.string.voice_result_duration to SettingGuide(R.array.guide_voice_result_duration) {
        story({ duration(ROUTINE, 1, 20, result = true) }, { duration(ROUTINE, 4, 76, result = true) })
    },
    R.string.voice_show_recognized_phrase to SettingGuide(R.array.guide_voice_show_recognized_phrase) {
        story({ voicePopup(result = true) }, { voicePopup(result = true, phrase = true) })
    },
    R.string.voice_startup_volume to SettingGuide(R.array.guide_voice_startup_volume) {
        GuideStory(
            frame { startupAudio(55, applied = false) },
            frame { startupAudio(20, applied = true) },
            frame { startupAudio(55, applied = true) },
        )
    },
    R.string.voice_startup_volume_level to SettingGuide(R.array.guide_voice_startup_volume_level) {
        story({ startupAudio(10, applied = true) }, { startupAudio(20, applied = true) })
    },
    R.string.voice_duck_media to SettingGuide(R.array.guide_voice_duck_media) {
        GuideStory(
            frame { ducking(VOICE, 75, false) },
            frame { ducking(VOICE, 27, true) },
            frame { ducking(VOICE, 75, true) },
        )
    },
    R.string.voice_duck_calls to SettingGuide(R.array.guide_voice_duck_calls) {
        GuideStory(frame { ducking(null, 75, false) }, frame { ducking(null, 27, true) }, frame { ducking(null, 75, true) })
    },
    R.string.voice_duck_level to SettingGuide(R.array.guide_voice_duck_level) {
        story({ ducking(VOICE, 35, true, percent = true) }, { ducking(VOICE, 10, true, percent = true) })
    },
    R.string.ui_177 to SettingGuide(R.array.guide_ui_177) {
        story(
            { tile("setup", SETTINGS, 48, 22, 36) },
            {
                tile("setup", SETTINGS, 12, 22, 36)
                word("transfer", "⇄", 66, 39, 20, ACCENT)
                tile("archive", DOWNLOAD, 84, 22, 36)
            },
        )
    },
    R.string.ui_181 to SettingGuide(R.array.guide_ui_181) {
        story({ contact("Anna", "••• 42") }, { dialer("••• 42") })
    },
    R.string.ui_193 to SettingGuide(R.array.guide_ui_193) {
        story({ contact("Anna", "••• 42") }, { voiceBubble("«Anna»", 66, 24); icon("request", VOICE, 53, 44, 23) })
    },
    R.string.ui_194 to SettingGuide(R.array.guide_ui_194) {
        story({ voiceBubble("«Anna»", 66, 27); icon("request", VOICE, 54, 45, 22) }, { dialer("••• 42") })
    },
    R.string.ui_188 to SettingGuide(R.array.guide_ui_188) {
        story({ connector("assignment", VOICE, HOME) }, { connector("assignment", VOICE, PLAY) })
    },
)

internal fun defaultSeatGuide(command: String): Int = when (command) {
    "EnableHeatDriverLevelDefault" -> 0
    "EnableHeatPassengerLevelDefault" -> 1
    "EnableHeatRearLeftLevelDefault" -> 2
    "EnableHeatRearRightLevelDefault" -> 3
    "EnableConditionDriverLevelDefault" -> 4
    "EnableConditionPassengerLevelDefault" -> 5
    "EnableMassageDriverLevelDefault" -> 6
    "EnableMassagePassengerLevelDefault" -> 7
    else -> 0
}

private fun GuideFrameBuilder.seatRequest(seat: Int, level: Int) {
    icon("command", VOICE, 10, 25, 21)
    arrow("request", 45)
    screen("cabin", 66, 10, 54, 58, radius = 10)
    val position = if (seat < 4) seat else seat % 2
    val tone = if (seat < 4) AMBER else if (seat < 6) TEAL else ACCENT
    val symbol = if (seat < 4) "≈" else if (seat < 6) "✳" else "∿"
    for (i in 0..3) {
        val x = 74 + i % 2 * 22
        val y = 21 + i / 2 * 22
        box("seat-$i", x, y, 16, 14, if (i == position) ACTION else BORDER)
        if (i == position) {
            word("mode", symbol, x + 8, y + 7, 13, tone)
            repeat(level) { bar -> box("seat-level-$bar", x + 2 + bar * 5, y + 14, 3, 3, tone, 1) }
        }
    }
}

private fun GuideFrameBuilder.microphone(on: Boolean) {
    tile("mic", VOICE, 12, 22, 37, if (on) ACCENT else MUTED, if (on) ACTION else BORDER)
    if (on) signal("audio", 65, 39, 9)
    else box("silence", 64, 38, 45, 2, BORDER)
}

private fun GuideFrameBuilder.recognition(engine: String, example: String) {
    icon("mic", VOICE, 14, 13, 24)
    arrow("model", 55, 25)
    word("engine", engine, 85, 25, 13, width = 51)
    voiceBubble(example, 66, 53)
}

private fun GuideFrameBuilder.fallback(online: Boolean, matched: Boolean) {
    tile("local", FIRMWARE, 9, 18, 27)
    check("local-miss", 23, 53, false)
    arrow("fallback", 53, 31)
    icon("service", LANGUAGE, 66, 18, 26, if (online) ACCENT else MUTED)
    if (online) lines("text", 69, 52, 20, 2, 5, ACCENT, 2)
    if (matched) check("suggestion", 112, 32, true)
}

internal fun GuideFrameBuilder.windows(driver: Int, passenger: Int, driverOnly: Boolean = false) {
    screen("body", 13, 15, 106, 47, BORDER, radius = 15)
    box("divider", 65, 19, 2, 36, MUTED)
    box("driver-glass", 24, 22 + driver, 34, 30 - driver, TEAL)
    box("driver-gap", 24, 21, 34, driver + 1, SURFACE)
    box("passenger-glass", 73, 22 + passenger, 34, 30 - passenger, TEAL)
    if (!driverOnly) box("passenger-gap", 73, 21, 34, passenger + 1, SURFACE)
}

internal fun GuideFrameBuilder.seatLevel(level: Int, side: Int) {
    listOf(20, 77).forEachIndexed { index, x ->
        val active = side == index || side == 2
        val tone = if (active) ACCENT else MUTED
        box("back-$index", x, 18, 11, 29, tone, 5)
        box("seat-$index", x + 3, 42, 32, 11, tone, 5)
        for (bar in 0..2) box("heat-$index-$bar", x + 5 + bar * 9, 60, 6, 4, if (active && bar < level) AMBER else BORDER, 2)
    }
}

private fun GuideFrameBuilder.voicePopup(result: Boolean, phrase: Boolean = false) {
    screen("popup", 11, 12, 110, 54)
    if (result) {
        check("result", 32, 39, true)
        word("station", "FM 97.5", 83, if (phrase) 43 else 38, 14, width = 58)
        if (phrase) word("phrase", "«FM»", 83, 26, 10, MUTED)
    } else {
        icon("voice", VOICE, 19, 25, 27)
        if (!phrase) signal("capture", 62, 40, 7)
    }
}

private fun GuideFrameBuilder.speechRate(fast: Boolean) {
    icon("reply", VOICE, 11, 17, 23)
    signal("words", 41, 29, 9, gap = if (fast) 5 else 9)
    word("rate", if (fast) "1.4×" else "1.0×", 77, 55, 15)
}

private fun GuideFrameBuilder.replyLevel(value: Int) {
    icon("speech", VOICE, 13, 14, 22)
    signal("speech", 44, 26, 8, value / 3)
    gauge("voice-volume", 22, 54, 89, value)
    word("value", "$value%", 103, 26, 12, width = 42)
}

private fun GuideFrameBuilder.voiceBubble(text: String, x: Int, y: Int) {
    box("bubble", x - 41, y - 11, 82, 23, ACTION, 8)
    word("spoken", text, x, y, 14, width = 75)
}

private fun GuideFrameBuilder.channels(media: Boolean) {
    tile("reply", VOICE, 11, 25, 25)
    icon("assistant", VOICE, 92, 12, 20, if (media) MUTED else ACCENT)
    icon("media", PLAY, 92, 45, 20, if (media) ACCENT else MUTED)
    signal("signal", 51, if (media) 53 else 21, 6, gap = 5)
}

private fun GuideFrameBuilder.cue(beforeListening: Boolean, enabled: Boolean, tall: Int = 16, spoken: Boolean = false) {
    val micX = if (beforeListening) 86 else 15
    val cueX = if (beforeListening) 32 else 96
    tile("mic", VOICE, micX, 24, 31, if (beforeListening) ACCENT else MUTED)
    arrow("when", 64)
    if (enabled && spoken) {
        box("voice-bubble", 10, 25, 44, 24, ACTION, 5)
        word("speech", "«…»", 32, 37, 17)
    } else if (enabled) signal("tone", cueX - 13, 38, 5, tall)
    else word("silent", "—", cueX, 38, 14, MUTED)
}

private fun GuideFrameBuilder.duration(icon: CockpitIcon, seconds: Int, level: Int, result: Boolean = false) {
    tile("stage", icon, 12, 15, 27)
    if (result) check("result", 52, 29, true)
    word("seconds", "$seconds s", 93, 30, 17)
    gauge("duration", 20, 55, 92, level, knob = false)
}

private fun GuideFrameBuilder.startupAudio(level: Int, applied: Boolean) {
    icon("boot", ENERGY, 13, 16, 26, if (applied) ACCENT else MUTED)
    word("media", "♪", 55, 29, 24, ACCENT)
    word("volume", "$level%", 91, 29, 16)
    gauge("volume", 21, 55, 89, level)
}

private fun GuideFrameBuilder.ducking(event: CockpitIcon?, volume: Int, active: Boolean, percent: Boolean = false) {
    if (event == null) word("call", "☎", 24, 26, 23, if (active) ACCENT else MUTED)
    else icon("event", event, 12, 13, 25, if (active) ACCENT else MUTED)
    word("music", "♪", 49, 50, 22, ACCENT)
    gauge("music-volume", 64, 50, 51, volume)
    if (percent) word("retained", "$volume%", 91, 25, 15)
}

private fun GuideFrameBuilder.contact(name: String, number: String) {
    screen("contact")
    word("person", "●", 32, 36, 22, ACCENT)
    word("name", name, 83, 27, 16)
    word("number", number, 83, 50, 13, MUTED)
}

private fun GuideFrameBuilder.dialer(number: String) {
    word("phone", "☎", 23, 29, 23, ACCENT)
    word("number", number, 77, 24, 17)
    repeat(9) { i -> box("key-$i", 56 + (i % 3) * 16, 40 + (i / 3) * 8, 10, 5, ACTION, 2) }
}
