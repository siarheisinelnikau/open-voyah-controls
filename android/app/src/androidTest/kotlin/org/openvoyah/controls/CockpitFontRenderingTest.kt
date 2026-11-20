package org.openvoyah.controls

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.test.AndroidTestCase
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import org.openvoyah.controls.domain.settings.AppFontFamily
import org.openvoyah.controls.presentation.theme.cockpitFontFamily
import org.openvoyah.controls.presentation.theme.localCockpitFont

class CockpitFontRenderingTest : AndroidTestCase() {
    fun testBundledFontRendersLatinAndRussianWithSeparateRegularAndBoldWeights() {
        val resolver = createFontFamilyResolver(context)
        val family = cockpitFontFamily(AppFontFamily.GOLOS_TEXT)
        val regular = resolver.resolve(family, FontWeight.Normal).value as Typeface
        val bold = resolver.resolve(family, FontWeight.Bold).value as Typeface
        val system = resolver.resolve(null, FontWeight.Normal).value as Typeface
        assertFalse("The packaged face must work on the running Android version", regular == system)
        assertFalse("Golos Text must resolve a separate bold instance", regular == bold)
        val letters = "Controls Ёё Йй Щщ Ъъ Ьь 0123456789"
        for (face in listOf(regular, bold)) {
            val paint = Paint().apply { typeface = face; textSize = 24f }
            for (letter in letters.filterNot(Char::isWhitespace)) assertTrue("Missing glyph $letter", paint.hasGlyph(letter.toString()))
        }

        // Either CJK or emoji is available in the system fallback on supported Android builds.
        val systemPaint = Paint().apply { typeface = system; textSize = 24f }
        val fallbackGlyph = listOf("界", "🚗").firstOrNull(systemPaint::hasGlyph)
        assertNotNull("The Android fixture must include at least one fallback glyph", fallbackGlyph)
        val alternatePaint = Paint().apply { typeface = regular; textSize = 24f }
        assertTrue(alternatePaint.hasGlyph(checkNotNull(fallbackGlyph)))
    }

    @SuppressLint("ResourceType")
    fun testUnavailableLocalFontFallsBackToTheSystemFamily() {
        val resolver = createFontFamilyResolver(context)
        val badResource = localCockpitFont(0x6F010000)
        val normal = resolver.resolve(null, FontWeight.Normal).value as Typeface
        val fallback = resolver.resolve(badResource, FontWeight.Normal).value as Typeface
        assertEquals(normal, fallback)
    }
}
