package org.openvoyah.controls.presentation.theme

import androidx.annotation.FontRes
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.openvoyah.controls.R
import org.openvoyah.controls.domain.settings.AppFontFamily

private val GolosTextFamily by lazy { localCockpitFont(R.font.golos_text) }

internal fun cockpitFontFamily(family: AppFontFamily): FontFamily? = when (family) {
    AppFontFamily.SYSTEM -> null
    AppFontFamily.GOLOS_TEXT -> GolosTextFamily
}

@OptIn(ExperimentalTextApi::class)
internal fun localCockpitFont(@FontRes resource: Int): FontFamily {
    val weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
    return FontFamily(weights.map { weight ->
        Font(
            resource,
            weight = weight,
            loadingStrategy = FontLoadingStrategy.OptionalLocal,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    })
}
