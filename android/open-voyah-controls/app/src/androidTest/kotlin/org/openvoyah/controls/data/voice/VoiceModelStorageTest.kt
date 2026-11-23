package org.openvoyah.controls.data.voice

import android.content.ContextWrapper
import android.test.AndroidTestCase
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@Suppress("DEPRECATION")
class VoiceModelStorageTest : AndroidTestCase() {
    fun testLowSpaceStopsBeforeCopyAndRetryLoadsTheNativeModel() {
        val root = File.createTempFile("voice-space-", "", context.cacheDir)
        check(root.delete() && root.mkdir())
        val scoped = object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
        }
        val free = AtomicLong(0)
        val models = VoskModels { free.get() }
        val existing = File(root, "existing-settings")
        existing.writeText("keep")
        try {
            try {
                models.load(scoped, "en") { true }
                fail("Insufficient space must stop before model extraction")
            } catch (_: VoskModels.StorageUnavailable) { }
            assertFalse(File(root, "speech-model-en-0.15").exists())
            assertEquals("keep", existing.readText())
            free.set(256L * 1024 * 1024)
            assertNotNull(models.load(scoped, "en") { true })
            assertTrue(File(root, "speech-model-en-0.15/ready").isFile)
            models.release()
            free.set(0)
            assertNotNull(models.load(scoped, "en") { true })
            assertEquals("keep", existing.readText())
        } finally {
            models.release()
            check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            root.walkBottomUp().forEach { check(it.delete()) }
        }
    }
}
