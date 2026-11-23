package org.openvoyah.controls.presentation.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.domain.settings.AppAccent
import org.openvoyah.controls.domain.settings.AppAppearance
import org.openvoyah.controls.domain.settings.AppBackgroundTreatment
import org.openvoyah.controls.domain.settings.AppCorners
import org.openvoyah.controls.domain.settings.AppFontFamily
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.domain.settings.AppRoleTextSize
import org.openvoyah.controls.domain.settings.AppSpacing
import org.openvoyah.controls.domain.settings.AppTextSize
import org.openvoyah.controls.domain.settings.LauncherEntry
import org.openvoyah.controls.domain.settings.RouteTransitionSpeed
import org.openvoyah.controls.domain.settings.WallpaperDimming
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion
import org.openvoyah.controls.presentation.theme.cockpitColorsFor
import org.openvoyah.controls.presentation.theme.cockpitLayout
import org.openvoyah.controls.presentation.theme.cockpitShapes
import org.openvoyah.controls.presentation.theme.cockpitTypography
import org.openvoyah.controls.presentation.theme.cockpitWallpaperScrim

// Small live samples beside the options of the Appearance dialogs. Each one draws only what its option changes,
// with the same functions ControlsTheme uses, so the sample matches what the choice will look like.

private val SampleWidth = 72.dp
private val SampleHeight = 36.dp
private val SampleShape = RoundedCornerShape(8.dp)
private const val SAMPLE_TEXT = "Aa"

@Composable
private fun animated(color: Color): Color = animateColorAsState(color, CockpitMotion.change(), label = "sample").value

/** A colour swatch with a filled and an outlined mini button, in the accent as the current palette shows it. */
@Composable
internal fun AccentSample(accent: AppAccent, current: AppAppearance) {
    val colors = cockpitColorsFor(accent, current.palette, current.highContrast)
    val primary = animated(colors.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).background(primary, CircleShape))
        Box(Modifier.size(32.dp, 24.dp).background(primary, SampleShape), contentAlignment = Alignment.Center) {
            Text(SAMPLE_TEXT, color = animated(colors.onPrimary), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Box(Modifier.size(32.dp, 24.dp).border(1.5.dp, primary, SampleShape), contentAlignment = Alignment.Center) {
            Text(SAMPLE_TEXT, color = primary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** A miniature page: background, a card with a line of text and an accent button. */
@Composable
internal fun PaletteSample(palette: AppPalette, current: AppAppearance) {
    val colors = cockpitColorsFor(current.accent, palette, current.highContrast)
    SampleFrame(colors.background, colors) {
        Column(Modifier.padding(5.dp).fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(5.dp)).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(34.dp, 4.dp).background(colors.onSurface, CircleShape))
            Box(Modifier.size(18.dp, 7.dp).background(colors.primary, CircleShape))
        }
    }
}

@Composable
internal fun BackgroundSample(treatment: AppBackgroundTreatment, current: AppAppearance) {
    val colors = cockpitColorsFor(current.accent, current.palette, current.highContrast)
    val fill = when (treatment) {
        AppBackgroundTreatment.AMBIENT -> Brush.linearGradient(listOf(colors.primaryContainer, colors.background, colors.background))
        AppBackgroundTreatment.SOLID -> Brush.linearGradient(listOf(colors.background, colors.background))
    }
    Box(Modifier.size(SampleWidth, SampleHeight).background(fill, SampleShape).border(1.dp, colors.outlineVariant, SampleShape))
}

/** A stand-in wallpaper under the scrim the option puts over a real one. */
@Composable
internal fun WallpaperSample(dimming: WallpaperDimming, current: AppAppearance) {
    val colors = cockpitColorsFor(current.accent, current.palette, current.highContrast)
    val wallpaper = Brush.linearGradient(listOf(Color(0xFF3D7BD9), Color(0xFFE8A04C), Color(0xFF3A9A6B)))
    Box(Modifier.size(SampleWidth, SampleHeight).background(wallpaper, SampleShape)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight().background(animated(cockpitWallpaperScrim(colors.background, dimming)), SampleShape))
        Text(SAMPLE_TEXT, Modifier.align(Alignment.Center), color = colors.onBackground, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun TextSizeSample(size: AppTextSize, current: AppAppearance) {
    TypeSample(cockpitTypography(size, current.fontFamily, current.boldText).titleMedium)
}

@Composable
internal fun RoleTextSizeSample(size: AppRoleTextSize, current: AppAppearance) {
    TypeSample(cockpitTypography(size.preset ?: current.textSize, current.fontFamily, current.boldText).titleMedium)
}

@Composable
internal fun FontSample(family: AppFontFamily, current: AppAppearance) {
    TypeSample(cockpitTypography(current.textSize, family, current.boldText).titleMedium)
}

@Composable
private fun TypeSample(style: androidx.compose.ui.text.TextStyle) {
    Box(Modifier.size(SampleWidth, SampleHeight), contentAlignment = Alignment.Center) {
        Text(SAMPLE_TEXT, style = style, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** Three tiles with the gaps the option puts between blocks. */
@Composable
internal fun SpacingSample(spacing: AppSpacing) {
    val gap = cockpitLayout(spacing, AppTextSize.STANDARD).spacing(5.dp)
    val tile = MaterialTheme.colorScheme.primary.copy(alpha = .72f)
    Row(Modifier.size(SampleWidth, SampleHeight), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { Box(Modifier.size(12.dp, 22.dp).background(tile, RoundedCornerShape(3.dp))) }
    }
}

@Composable
internal fun CornersSample(corners: AppCorners) {
    val shape = cockpitShapes(corners).medium
    Box(Modifier.size(SampleWidth, SampleHeight), contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp, 28.dp).border(1.5.dp, MaterialTheme.colorScheme.primary, shape))
    }
}

/** A dot crossing a track in the option's route duration and easing; still while route animation is off. */
@Composable
internal fun TransitionSample(speed: RouteTransitionSpeed) {
    val colors = MaterialTheme.colorScheme
    val travel = SampleWidth - 16.dp
    val position = if (LocalCockpitMotion.current.enabled) {
        val duration = CockpitMotion.routeDurations(speed).enterMillis
        val loop = rememberInfiniteTransition(label = "transition sample")
        val value by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(duration, PAUSE_MILLIS, CockpitMotion.Settle),
            RepeatMode.Reverse), label = "transition sample")
        value
    } else 1f
    Box(Modifier.size(SampleWidth, SampleHeight), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.size(SampleWidth, 4.dp).background(colors.outlineVariant, CircleShape))
        Box(Modifier.offset(x = travel * position).size(16.dp).background(colors.primary, CircleShape))
    }
}

@Composable
internal fun StartScreenSample(entry: LauncherEntry) {
    Box(Modifier.size(SampleWidth, SampleHeight), contentAlignment = Alignment.Center) {
        CockpitGlyph(when (entry) {
            LauncherEntry.APP_GRID -> CockpitIcon.APPS
            LauncherEntry.VEHICLE -> CockpitIcon.CAR
            LauncherEntry.SETTINGS -> CockpitIcon.SETTINGS
        })
    }
}

/** A volume bar filled to the limit. */
@Composable
internal fun VolumeSample(percent: Int) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(SampleWidth, SampleHeight), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.size(SampleWidth, 6.dp).background(colors.outlineVariant, CircleShape))
        Box(Modifier.size(SampleWidth * (percent / 100f), 6.dp).background(colors.primary, CircleShape))
    }
}

@Composable
private fun SampleFrame(background: Color, colors: ColorScheme, content: @Composable () -> Unit) {
    Box(Modifier.size(SampleWidth, SampleHeight).background(animated(background), SampleShape)
        .border(1.dp, colors.outlineVariant, SampleShape)) { content() }
}

private const val PAUSE_MILLIS = 700
