package org.openvoyah.controls.presentation.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.domain.settings.AppCorners
import org.openvoyah.controls.domain.settings.AppFontFamily
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.domain.settings.AppSpacing
import org.openvoyah.controls.domain.settings.AppTextSize

class CockpitAppearanceTest {
    @Test fun roleSizesFollowTheBaseByDefaultAndKeepIndependentFontsAndSpace() {
        val defaults = cockpitLayout(AppSpacing.STANDARD, AppTextSize.LARGE)
        assertEquals(defaults.textScale, defaults.navigationLabelScale, 0f)
        assertEquals(defaults.textScale, defaults.pageTitleScale, 0f)

        val settings = cockpitLayout(AppSpacing.STANDARD, AppTextSize.LARGE,
            AppRoleTextSize.EXTRA_LARGE, AppRoleTextSize.STANDARD)
        val base = cockpitTypography(AppTextSize.LARGE, AppFontFamily.GOLOS_TEXT, bold = true)
        val navigation = base.titleMedium.scaledText(settings.navigationLabelScale / settings.textScale)
        val page = base.headlineSmall.scaledText(settings.pageTitleScale / settings.textScale)
        assertEquals(17f * 1.3f, navigation.fontSize.value, 0.001f)
        assertEquals(23f, page.fontSize.value, 0.001f)
        assertEquals(base.titleMedium.fontFamily, navigation.fontFamily)
        assertEquals(base.headlineSmall.fontWeight, page.fontWeight)
        assertTrue(settings.columnsMinimum(740.dp, 1f, settings.navigationLabelScale) >
            defaults.columnsMinimum(740.dp, 1f))
    }

    @Test fun fontFamilyAndBoldTextEachApplyWithoutChangingTheOtherTextChoices() {
        val standard = cockpitTypography(AppTextSize.STANDARD)
        val golos = cockpitTypography(AppTextSize.STANDARD, AppFontFamily.GOLOS_TEXT)
        val boldSystem = cockpitTypography(AppTextSize.STANDARD, bold = true)
        val boldGolos = cockpitTypography(AppTextSize.EXTRA_LARGE, AppFontFamily.GOLOS_TEXT, bold = true)
        assertNotEquals(standard.bodyLarge.fontFamily, golos.bodyLarge.fontFamily)
        assertEquals(standard.bodyLarge.fontWeight, golos.bodyLarge.fontWeight)
        assertEquals(standard.labelLarge.fontFamily, boldSystem.labelLarge.fontFamily)
        assertEquals(FontWeight.Bold, boldSystem.labelLarge.fontWeight)
        assertEquals(FontWeight.Bold, boldGolos.titleMedium.fontWeight)
        assertEquals(golos.bodyLarge.fontFamily, boldGolos.bodyLarge.fontFamily)
        assertEquals(golos.labelLarge.fontSize * 1.3f, boldGolos.labelLarge.fontSize)
    }

    @Test fun textPresetsChangeMaterialRolesAndKeepSpUnits() {
        val standard = cockpitTypography(AppTextSize.STANDARD)
        val large = cockpitTypography(AppTextSize.LARGE)
        val extraLarge = cockpitTypography(AppTextSize.EXTRA_LARGE)
        assertEquals(17.sp, standard.bodyLarge.fontSize)
        assertEquals(24.sp, standard.bodyLarge.lineHeight)
        assertEquals(23.sp, standard.headlineSmall.fontSize)
        assertEquals(standard.bodyLarge.fontSize * 1.15f, large.bodyLarge.fontSize)
        assertEquals(standard.titleMedium.lineHeight * 1.3f, extraLarge.titleMedium.lineHeight)
        assertEquals(standard.labelSmall.fontSize * 1.3f, extraLarge.labelSmall.fontSize)
        assertTrue(extraLarge.bodyLarge.fontSize.isSp)
    }

    @Test fun spacingAndLargerFontsRetainSpaceForReadableColumns() {
        val standard = cockpitLayout(AppSpacing.STANDARD, AppTextSize.STANDARD)
        val compact = cockpitLayout(AppSpacing.COMPACT, AppTextSize.EXTRA_LARGE)
        val spacious = cockpitLayout(AppSpacing.SPACIOUS, AppTextSize.STANDARD)
        assertEquals(14.dp, standard.spacing(14.dp))
        assertEquals(16.dp * 0.8f, compact.spacing(16.dp))
        assertEquals(16.dp * 1.2f, spacious.spacing(16.dp))
        assertEquals(730.dp, standard.columnsMinimum(730.dp, 1f))
        assertTrue(1280.dp >= compact.columnsMinimum(730.dp, 1f))
        assertTrue(1280.dp < compact.columnsMinimum(730.dp, 1.5f))
        assertTrue(480.dp < compact.columnsMinimum(730.dp, 1.5f))
    }

    @Test fun cornerStylesChangeTheActualCardAndControlOutlines() {
        val subtle = cockpitShapes(AppCorners.SUBTLE)
        val standard = cockpitShapes(AppCorners.STANDARD)
        val rounded = cockpitShapes(AppCorners.ROUNDED)
        assertEquals(listOf(6f, 14f, 20f), listOf(subtle, standard, rounded).map { radius(it.medium) })
        assertEquals(listOf(8f, 20f, 28f), listOf(subtle, standard, rounded).map { radius(it.large) })
        assertEquals(listOf(12f, 24f, 32f), listOf(subtle, standard, rounded).map { radius(it.extraLarge) })
    }

    private fun radius(shape: Shape): Float {
        val outline = shape.createOutline(Size(240f, 100f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
        return outline.roundRect.topLeftCornerRadius.x
    }
}
