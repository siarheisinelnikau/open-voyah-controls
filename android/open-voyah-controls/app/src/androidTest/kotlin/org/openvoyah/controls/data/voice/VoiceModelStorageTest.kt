package org.openvoyah.controls.data.voice

import android.content.ContextWrapper
import android.test.AndroidTestCase
import java.io.File

@Suppress("DEPRECATION")
class VoiceModelStorageTest : AndroidTestCase() {
    fun testLowSpaceStopsBeforeCopyAndRetryStagesTheBundledModel() {
        val root = File.createTempFile("voice-space-", "", context.cacheDir)
        check(root.delete() && root.mkdir())
        val scoped = object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
        }
        var free = 0L
        val model = BundledVoiceModel { free }
        val existing = File(root, "existing-settings")
        existing.writeText("keep")
        try {
            try {
                model.prepare(scoped) { true }
                fail("Insufficient space must stop before model extraction")
            } catch (_: BundledVoiceModel.StorageUnavailable) { }
            assertEquals(listOf("existing-settings"), root.list()?.toList())
            assertEquals("keep", existing.readText())
            free = 256L * 1024 * 1024
            val ready = model.prepare(scoped) { true }
            assertEquals(70_876_638L, File(ready, "encoder.int8.onnx").length())
            assertTrue(File(ready, "ready").isFile)
            free = 0
            assertEquals(ready, model.prepare(scoped) { true })
            assertEquals("keep", existing.readText())
        } finally {
            check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            root.walkBottomUp().forEach { check(it.delete()) }
        }
    }
}
