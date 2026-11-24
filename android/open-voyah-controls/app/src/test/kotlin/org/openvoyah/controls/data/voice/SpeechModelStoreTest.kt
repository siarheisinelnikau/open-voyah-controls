package org.openvoyah.controls.data.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openvoyah.controls.domain.voice.SpeechEngine

class SpeechModelStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fileSource(name: String, bytes: ByteArray, pinned: ByteArray = bytes) =
        ModelSource("https://example.test/$name", ModelSource.Kind.FILE, bytes.size.toLong(),
            PinnedFile(name, pinned.size.toLong(), sha256(pinned)))

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun model(vararg sources: ModelSource) =
        SpeechModel("test-model", SpeechEngine.ZIPFORMER, "ru", "test", sources.toList(), installedSize = 1_000)

    private fun store(served: Map<String, ByteArray>, space: Long = Long.MAX_VALUE) =
        SpeechModelStore(temporary.root, { url -> ByteArrayInputStream(served.getValue(url)) }, { space })

    @Test fun pinnedFilesInstallTogetherWithTheReadyMarker() {
        val encoder = "encoder".toByteArray()
        val tokens = "tokens".toByteArray()
        val model = model(fileSource("encoder.onnx", encoder), fileSource("tokens.txt", tokens))
        val store = store(model.sources.associate { it.url to if (it.url.endsWith("onnx")) encoder else tokens })
        val reported = mutableListOf<Long>()
        store.install(model, { done, total -> reported += done; assertEquals(13L, total) }) { true }
        assertTrue(store.installed(model))
        assertArrayEquals(encoder, File(store.directory(model), "encoder.onnx").readBytes())
        assertEquals(13L, reported.last())
    }

    @Test fun aChangedFileIsRejectedAndNothingIsInstalled() {
        val model = model(fileSource("encoder.onnx", "tampered".toByteArray(), pinned = "original".toByteArray()))
        try {
            store(mapOf(model.sources[0].url to "tampered".toByteArray())).install(model, { _, _ -> }) { true }
            fail("A file that does not match its pin must not install")
        } catch (_: SpeechModelStore.Mismatch) { }
        assertFalse(File(temporary.root, "test-model").exists())
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun aPinnedZipUnpacksWithoutItsTopFolder() {
        val archive = zip("vosk-model/am/final.mdl" to "am".toByteArray(), "vosk-model/conf/model.conf" to "conf".toByteArray())
        val source = ModelSource("https://example.test/model.zip", ModelSource.Kind.ZIP, archive.size.toLong(),
            archiveSha256 = sha256(archive))
        val model = model(source)
        val store = store(mapOf(source.url to archive))
        store.install(model, { _, _ -> }) { true }
        assertEquals("am", File(store.directory(model), "am/final.mdl").readText())
        assertEquals("conf", File(store.directory(model), "conf/model.conf").readText())
    }

    @Test fun aZipWithAnotherHashOrAnEscapingEntryIsRejected() {
        val archive = zip("model/a.txt" to "a".toByteArray())
        val wrong = ModelSource("https://example.test/a.zip", ModelSource.Kind.ZIP, archive.size.toLong(),
            archiveSha256 = sha256("other".toByteArray()))
        try {
            store(mapOf(wrong.url to archive)).install(model(wrong), { _, _ -> }) { true }
            fail("An archive that does not match its pin must not install")
        } catch (_: SpeechModelStore.Mismatch) { }
        val escaping = zip("model/../../outside.txt" to "x".toByteArray())
        val source = ModelSource("https://example.test/b.zip", ModelSource.Kind.ZIP, escaping.size.toLong(),
            archiveSha256 = sha256(escaping))
        try {
            store(mapOf(source.url to escaping)).install(model(source), { _, _ -> }) { true }
            fail("An entry outside the model folder must be refused")
        } catch (failure: IOException) {
            assertFalse(failure is SpeechModelStore.Mismatch)
        }
        assertFalse(File(temporary.root.parentFile, "outside.txt").exists())
        assertFalse(File(temporary.root, "test-model").exists())
    }

    @Test fun cancellingStopsTheDownloadAndKeepsTheInstalledModel() {
        val first = "first".toByteArray()
        val model = model(fileSource("encoder.onnx", first))
        val store = store(mapOf(model.sources[0].url to first))
        store.install(model, { _, _ -> }) { true }
        try {
            store.install(model, { _, _ -> }) { false }
            fail("A cancelled download must stop")
        } catch (_: SpeechModelStore.Cancelled) { }
        assertTrue("The earlier installation stays usable", store.installed(model))
        assertFalse(File(temporary.root, ".test-model.part").exists())
    }

    @Test fun notEnoughSpaceStopsBeforeDownloading() {
        val model = model(fileSource("encoder.onnx", "x".toByteArray()))
        var fetched = false
        val store = SpeechModelStore(temporary.root, { fetched = true; ByteArrayInputStream(ByteArray(1)) }, { 10L })
        try {
            store.install(model, { _, _ -> }) { true }
            fail("Low storage must stop the download")
        } catch (_: SpeechModelStore.StorageFull) { }
        assertFalse(fetched)
    }

    @Test fun deleteRemovesTheModel() {
        val bytes = "x".toByteArray()
        val model = model(fileSource("encoder.onnx", bytes))
        val store = store(mapOf(model.sources[0].url to bytes))
        store.install(model, { _, _ -> }) { true }
        store.delete(model)
        assertFalse(store.installed(model))
        assertFalse(store.directory(model).exists())
    }

    @Test fun theCatalogPinsEveryDownloadOverHttps() {
        for (model in SpeechModelCatalog.ALL) {
            assertTrue(model.id, model.sources.isNotEmpty())
            assertTrue(model.id, model.engine.supports(model.language))
            for (source in model.sources) {
                assertTrue(source.url, source.url.startsWith("https://"))
                val hash = source.file?.sha256 ?: source.archiveSha256
                assertTrue(source.url, hash != null && Regex("[0-9a-f]{64}").matches(hash))
            }
        }
        assertEquals(null, SpeechModelCatalog.forEngine(SpeechEngine.ZIPFORMER, "ru"))
        assertEquals(SpeechModelCatalog.VOSK_RU, SpeechModelCatalog.forEngine(SpeechEngine.VOSK, "ru"))
        assertEquals(SpeechModelCatalog.VOSK_EN, SpeechModelCatalog.forEngine(SpeechEngine.VOSK, "en"))
        assertEquals(SpeechModelCatalog.VOSK_KK, SpeechModelCatalog.forEngine(SpeechEngine.VOSK, "kk"))
    }

    @Test fun anAlreadyExtractedVoskModelIsReusedAfterUpgrade() {
        val legacy = temporary.newFolder("speech-model-ru-0.22")
        val model = SpeechModelCatalog.VOSK_RU
        val store = SpeechModelStore(File(temporary.root, "speech-models"), legacyRoot = temporary.root)
        File(legacy, "am").mkdir()
        File(legacy, "am/final.mdl").writeText("legacy model")
        assertFalse(store.installed(model))
        File(legacy, "ready").writeText("1")
        assertEquals(legacy, store.installedDirectory(model))
        store.delete(model)
        assertFalse(store.installed(model))
    }
}
