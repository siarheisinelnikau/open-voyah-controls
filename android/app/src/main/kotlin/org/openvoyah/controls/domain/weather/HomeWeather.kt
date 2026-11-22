package org.openvoyah.controls.domain.weather

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Conditions the factory launcher's weather card can draw, with the code of its icon
 * (`WeatherIconUtils.getMediumIcon` in the launcher; the agent accepts exactly these codes).
 */
enum class WeatherCondition(val launcherCode: String) {
    CLEAR("0"), CLOUDY("2"), OVERCAST("3"), FOG("19"), RAIN("7"), SNOW("14"), THUNDERSTORM("5"), HAIL("6");

    companion object {
        /** WMO weather interpretation code, as Open-Meteo reports it. */
        fun fromWmo(code: Int): WeatherCondition? = when (code) {
            0, 1 -> CLEAR
            2 -> CLOUDY
            3 -> OVERCAST
            45, 48 -> FOG
            in 51..57, in 61..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            95 -> THUNDERSTORM
            96, 99 -> HAIL
            else -> null
        }
    }
}

/** One reading for the home screen card. */
data class HomeWeatherReading(
    val condition: WeatherCondition,
    /** Whole degrees Celsius, as the card shows them. */
    val temperature: String,
    /** The card's air quality level, or null when unknown. */
    val airQuality: String?,
    val fetchedAt: Long,
)

/** Keyless Open-Meteo requests and the mapping to what the launcher card understands. */
object HomeWeather {
    /** The agent shows "no data" for readings older than this; it must match `MAX_AGE_MS` in home-weather-card.js. */
    const val MAX_AGE_MS = 3L * 60 * 60 * 1000
    const val REFRESH_MS = 30L * 60 * 1000

    fun temperature(celsius: Double): String {
        val rounded = celsius.roundToInt()
        return (if (rounded == 0) 0 else rounded).toString()
    }

    /**
     * US AQI to the card's levels. The bands are the ones the Chinese AQI uses too (0–50 excellent … over 300
     * hazardous), which is what the card's level names describe.
     */
    fun airQualityLevel(usAqi: Int?): String? = when {
        usAqi == null || usAqi < 0 -> null
        usAqi <= 50 -> "优"
        usAqi <= 100 -> "良"
        usAqi <= 150 -> "轻度污染"
        usAqi <= 200 -> "中度污染"
        usAqi <= 300 -> "重度污染"
        else -> "严重污染"
    }

    /** Two decimals (about 1 km): enough for weather, and no more of the car's position than that. */
    fun coordinate(value: Double): String = String.format(Locale.US, "%.2f", value)

    fun forecastUrl(latitude: Double, longitude: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${coordinate(latitude)}&longitude=${coordinate(longitude)}" +
            "&current=temperature_2m,weather_code&timezone=auto"

    fun airQualityUrl(latitude: Double, longitude: Double): String =
        "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${coordinate(latitude)}" +
            "&longitude=${coordinate(longitude)}&current=us_aqi"

    fun geocodeUrl(city: String, language: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city.trim(), "UTF-8")}" +
            "&count=1&format=json&language=${if (language == "ru") "ru" else "en"}"

    /** A city name the settings accept: trimmed, 1 to 60 characters, no control characters. */
    fun cleanCity(value: String): String = value.trim().filterNot { it.isISOControl() }.take(60)
}
