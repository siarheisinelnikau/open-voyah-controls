package org.openvoyah.controls.data.voice

import org.openvoyah.controls.domain.voice.SpeechEngine

/** A file the app keeps from a download, pinned by size and SHA-256. */
internal data class PinnedFile(val name: String, val size: Long, val sha256: String)

/**
 * One download. A [FILE] source is a single pinned file. A [ZIP] source is pinned as a whole by [archiveSha256] and
 * unpacked without its top folder.
 */
internal data class ModelSource(
    val url: String,
    val kind: Kind,
    val downloadSize: Long,
    val file: PinnedFile? = null,
    val archiveSha256: String? = null,
) {
    enum class Kind { FILE, ZIP }

    init {
        require(url.startsWith("https://"))
        require(downloadSize > 0)
        when (kind) {
            Kind.FILE -> require(file != null && file.size == downloadSize && archiveSha256 == null)
            Kind.ZIP -> require(file == null && archiveSha256 != null)
        }
    }
}

/** A speech model the app can download for one engine and recognition language. */
internal data class SpeechModel(
    val id: String,
    val engine: SpeechEngine,
    val language: String,
    val licence: String,
    val sources: List<ModelSource>,
    /** Approximate space on the device after unpacking. */
    val installedSize: Long,
) {
    val downloadSize: Long get() = sources.sumOf { it.downloadSize }
}

/**
 * Every model the app downloads, from its publisher, at a fixed revision and with pinned hashes. A changed file on
 * the server fails verification and is never installed. The bundled Zipformer is pinned by the build preparation script.
 */
internal object SpeechModelCatalog {
    val VOSK_RU = vosk("vosk-model-small-ru-0.22", "ru", 46_236_750, 91_289_240,
        "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4")
    val VOSK_EN = vosk("vosk-model-small-en-us-0.15", "en", 41_205_931, 70_898_967,
        "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498")
    val VOSK_KK = vosk("vosk-model-small-kz-0.42", "kk", 59_697_294, 108_000_000,
        "b6d6bfe6195c866805bd18b1f56723e4d2ba4866e81df1481006d0c5704fda4f")

    val ALL = listOf(VOSK_RU, VOSK_EN, VOSK_KK)

    /** The download required for an engine and recognition language, if any. */
    fun forEngine(engine: SpeechEngine, language: String): SpeechModel? =
        ALL.firstOrNull { it.engine == engine && it.language == language }

    private fun vosk(id: String, language: String, downloadSize: Long, installedSize: Long, sha256: String) = SpeechModel(
        id = id, engine = SpeechEngine.VOSK, language = language, licence = "Apache-2.0",
        sources = listOf(ModelSource("https://alphacephei.com/vosk/models/$id.zip",
            ModelSource.Kind.ZIP, downloadSize, archiveSha256 = sha256)),
        installedSize = installedSize,
    )
}
