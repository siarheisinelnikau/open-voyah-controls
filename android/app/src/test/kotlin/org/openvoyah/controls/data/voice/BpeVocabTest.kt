package org.openvoyah.controls.data.voice

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BpeVocabTest {
    private fun ByteArrayOutputStream.varint(value: Long) {
        var rest = value
        while (rest >= 0x80) {
            write(((rest and 0x7f) or 0x80).toInt())
            rest = rest ushr 7
        }
        write(rest.toInt())
    }

    private fun piece(text: String, score: Float, type: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        val bytes = text.toByteArray()
        out.varint((1L shl 3) or 2)
        out.varint(bytes.size.toLong())
        out.write(bytes)
        out.varint((2L shl 3) or 5)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(score).array())
        out.varint(3L shl 3)
        out.varint(type.toLong())
        return out.toByteArray()
    }

    private fun model(vararg pieces: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (piece in pieces) {
            out.varint((1L shl 3) or 2)
            out.varint(piece.size.toLong())
            out.write(piece)
        }
        // Trainer and normalizer specs follow the pieces in a real model.
        out.varint((2L shl 3) or 2)
        out.varint(3)
        out.write(byteArrayOf(8, 1, 16))
        return out.toByteArray()
    }

    @Test fun piecesAndScoresComeOutInModelOrder() {
        val vocab = BpeVocab.fromModel(model(piece("<unk>", 0f, 2), piece("▁под", -3.5f), piece("грев", -12.25f)))
        assertEquals("<unk>\t0.0\n▁под\t-3.5\nгрев\t-12.25\n", vocab)
    }

    @Test fun aTruncatedModelIsRejected() {
        val bytes = model(piece("▁люк", -1f))
        try {
            BpeVocab.fromModel(bytes.copyOf(bytes.size - 6))
            fail("A truncated model must not produce a partial vocabulary")
        } catch (_: IOException) { }
    }
}
