package org.openvoyah.controls.data.voice

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The `piece<TAB>score` list that sherpa-onnx needs to split hotwords into BPE tokens, read from a SentencePiece model
 * (`ModelProto.pieces`, field 1: `piece` string 1, `score` float 2). sentencepiece itself is not available on Android.
 */
internal object BpeVocab {
    fun fromModel(model: ByteArray): String {
        val lines = StringBuilder()
        val reader = Reader(model, 0, model.size)
        while (reader.more()) {
            val key = reader.varint()
            if (key ushr 3 == 1L && key and 7 == 2L) {
                val piece = reader.message()
                var text: String? = null
                var score = 0f
                while (piece.more()) {
                    val field = piece.varint()
                    when {
                        field ushr 3 == 1L && field and 7 == 2L -> text = piece.string()
                        field ushr 3 == 2L && field and 7 == 5L -> score = piece.float()
                        else -> piece.skip(field)
                    }
                }
                lines.append(text ?: throw IOException("BPE piece without text")).append('\t').append(score).append('\n')
            } else reader.skip(key)
        }
        if (lines.isEmpty()) throw IOException("No BPE pieces in the model")
        return lines.toString()
    }

    private class Reader(private val bytes: ByteArray, private var position: Int, private val end: Int) {
        fun more() = position < end

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                if (position >= end || shift > 63) throw IOException("Truncated BPE model")
                val byte = bytes[position++].toInt()
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
            }
        }

        fun message(): Reader {
            val length = length()
            return Reader(bytes, position, position + length).also { position += length }
        }

        fun string(): String {
            val length = length()
            return String(bytes, position, length, Charsets.UTF_8).also { position += length }
        }

        fun float(): Float {
            if (position + 4 > end) throw IOException("Truncated BPE model")
            return ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.LITTLE_ENDIAN).float.also { position += 4 }
        }

        fun skip(key: Long) {
            when ((key and 7).toInt()) {
                0 -> varint()
                1 -> advance(8)
                2 -> advance(length())
                5 -> advance(4)
                else -> throw IOException("Unsupported BPE model field")
            }
        }

        private fun length(): Int {
            val length = varint()
            if (length < 0 || length > end - position) throw IOException("Truncated BPE model")
            return length.toInt()
        }

        private fun advance(count: Int) {
            if (count > end - position) throw IOException("Truncated BPE model")
            position += count
        }
    }
}
