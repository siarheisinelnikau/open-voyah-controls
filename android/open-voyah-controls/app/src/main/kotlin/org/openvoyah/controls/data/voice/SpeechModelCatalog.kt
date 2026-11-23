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
 * the server fails verification and is never installed. The Russian and English Vosk models ship inside the APK.
 */
internal object SpeechModelCatalog {
    private const val ZIPFORMER_RU_REPO =
        "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20/resolve/641de8d322c05b9087ad2927ccda4bda3cccc159/"

    private fun zipformerFile(name: String, size: Long, sha256: String) =
        ModelSource(ZIPFORMER_RU_REPO + name, ModelSource.Kind.FILE, size, PinnedFile(name, size, sha256))

    /** Vosk small Kazakh model from Alpha Cephei (Apache-2.0). */
    val VOSK_KK = SpeechModel(
        id = "vosk-model-small-kz-0.42", engine = SpeechEngine.VOSK, language = "kk", licence = "Apache-2.0",
        sources = listOf(ModelSource("https://alphacephei.com/vosk/models/vosk-model-small-kz-0.42.zip",
            ModelSource.Kind.ZIP, 59_697_294,
            archiveSha256 = "b6d6bfe6195c866805bd18b1f56723e4d2ba4866e81df1481006d0c5704fda4f")),
        installedSize = 108_000_000,
    )

    /**
     * The offline Russian Zipformer of the Vosk team in sherpa-onnx format (int8, Apache-2.0), with the Silero voice
     * activity detector (MIT) that ends the phrase.
     */
    val ZIPFORMER_RU = SpeechModel(
        id = "sherpa-onnx-zipformer-ru-int8-2025-04-20", engine = SpeechEngine.ZIPFORMER, language = "ru",
        licence = "Apache-2.0; Silero VAD MIT",
        sources = listOf(
            zipformerFile("encoder.int8.onnx", 70_876_638, "eb6c12fbad810d5bc3e427802e604604c69b5943a91feebc43424dd09d9ec407"),
            zipformerFile("decoder.onnx", 2_093_080, "dcbe1ffa0211e77ca6d3a80164df13fbda3ec00e47d12b9f449f89572df12136"),
            zipformerFile("joiner.int8.onnx", 259_417, "93f2e1d12b78d53e7802f1606488c14bb3d764b15fadf5ef6c022f6ba1fa40f7"),
            zipformerFile("tokens.txt", 6_388, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"),
            zipformerFile("bpe.model", 246_184, "c7a756aeb3550417d6b2ed3efde9a7aa3eea54787d4eac011e9cce6090c9c64a"),
            ModelSource("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                ModelSource.Kind.FILE, 643_854,
                PinnedFile("silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")),
        ),
        installedSize = 74_200_000,
    )

    val ALL = listOf(ZIPFORMER_RU, VOSK_KK)

    /** The model a language and engine need, or null when the APK carries it or the engine lacks that language. */
    fun forEngine(engine: SpeechEngine, language: String): SpeechModel? =
        ALL.firstOrNull { it.engine == engine && it.language == language }
}
