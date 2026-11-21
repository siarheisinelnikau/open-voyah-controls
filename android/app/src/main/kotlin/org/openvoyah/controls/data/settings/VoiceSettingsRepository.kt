package org.openvoyah.controls.data.settings

import android.content.Context
import android.net.Uri
import org.openvoyah.controls.data.frida.ReviewedVoiceCommands
import org.openvoyah.controls.data.voice.CloudVoiceSettings
import org.openvoyah.controls.platform.voice.OfflineVoiceInput
import org.openvoyah.controls.platform.voice.SpeechModels
import org.openvoyah.controls.presentation.voice.VoiceSettingsChange
import org.openvoyah.controls.presentation.voice.VoiceSettingsConfiguration
import org.openvoyah.controls.presentation.voice.VoiceSettingsSource

/** Application-only persistence; all entry points are called by the settings worker. */
internal class VoiceSettingsRepository(context: Context) : VoiceSettingsSource {
    private val app = context.applicationContext
    private val settings by lazy { VoiceSettings(app) }
    private val online by lazy { CloudVoiceSettings(app) }
    private val documents by lazy { VoiceSettingsDocuments(app.contentResolver, settings) }

    override fun load(): VoiceSettingsConfiguration {
        val language = settings.language()
        val catalog = settings.withContacts()
        val commands = catalog.all()
        return VoiceSettingsConfiguration(catalog, language, ReviewedVoiceCommands(app).hasApprovedCommands(),
            commands.mapNotNull { it.id().takeIf { id -> settings.app(id).isNotEmpty() } }.toSet(),
            commands.filter { it.id().endsWith("LevelDefault") }.associate { it.id() to settings.level(it.id()) },
            settings.option("doubleSmoke"), settings.option("doubleVentilation"),
            settings.option("duckMedia"), settings.option("spokenFeedback"), online.configured(), settings.audioOptions(),
            settings.offlineVoice(language), settings.readyPrompt(language))
    }

    override fun change(change: VoiceSettingsChange) {
        when (change) {
            is VoiceSettingsChange.Language -> settings.language(change.language)
            is VoiceSettingsChange.App -> settings.app(change.command, change.component)
            is VoiceSettingsChange.Level -> settings.level(change.command, change.level)
            is VoiceSettingsChange.Windows -> settings.windowOptions(change.smoke, change.ventilation)
            is VoiceSettingsChange.Audio -> settings.audio(change.duck, change.feedback, change.options, change.language,
                change.offlineVoiceName, change.readyPrompt)
            is VoiceSettingsChange.Contact -> settings.contact(change.name, change.phone)
            VoiceSettingsChange.RestorePrevious -> settings.restore()
        }
        // The prepared model and command grammar follow the language and the contact list.
        if (change is VoiceSettingsChange.Language || change is VoiceSettingsChange.Contact ||
            change == VoiceSettingsChange.RestorePrevious) SpeechModels.warmUp(app, "settings")
    }

    override fun saveOnline(enabled: Boolean, key: String) { online.save(enabled, key) }
    override suspend fun repairModel() {
        OfflineVoiceInput(app, duckMedia = false).repair(settings.language())
        SpeechModels.warmUp(app, "repair")
    }
    override suspend fun saveDocument(document: String) { documents.save(Uri.parse(document)) }
    override suspend fun restoreDocument(document: String) {
        documents.restore(Uri.parse(document))
        SpeechModels.warmUp(app, "settings")
    }
}
