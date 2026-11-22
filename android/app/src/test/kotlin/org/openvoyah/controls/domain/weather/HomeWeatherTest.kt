package org.openvoyah.controls.domain.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWeatherTest {
    @Test fun everyWmoGroupMapsToALauncherIcon() {
        val expected = mapOf(
            0 to WeatherCondition.CLEAR, 1 to WeatherCondition.CLEAR, 2 to WeatherCondition.CLOUDY,
            3 to WeatherCondition.OVERCAST, 45 to WeatherCondition.FOG, 48 to WeatherCondition.FOG,
            51 to WeatherCondition.RAIN, 57 to WeatherCondition.RAIN, 61 to WeatherCondition.RAIN, 67 to WeatherCondition.RAIN,
            71 to WeatherCondition.SNOW, 77 to WeatherCondition.SNOW, 80 to WeatherCondition.RAIN, 82 to WeatherCondition.RAIN,
            85 to WeatherCondition.SNOW, 86 to WeatherCondition.SNOW, 95 to WeatherCondition.THUNDERSTORM,
            96 to WeatherCondition.HAIL, 99 to WeatherCondition.HAIL,
        )
        for ((code, condition) in expected) assertEquals("WMO $code", condition, WeatherCondition.fromWmo(code))
        for (code in listOf(-1, 4, 50, 60, 70, 90, 100)) assertNull("WMO $code", WeatherCondition.fromWmo(code))
    }

    @Test fun launcherCodesAreTheOnesTheAgentAccepts() {
        // hooks/home-weather-card.js ICON_CODES
        val agent = setOf("0", "2", "3", "5", "6", "7", "14", "19", "21")
        assertTrue(agent.containsAll(WeatherCondition.entries.map { it.launcherCode }))
    }

    @Test fun temperatureIsWholeDegreesWithoutMinusZero() {
        assertEquals("-3", HomeWeather.temperature(-2.6))
        assertEquals("0", HomeWeather.temperature(-0.4))
        assertEquals("0", HomeWeather.temperature(0.4))
        assertEquals("21", HomeWeather.temperature(20.5))
    }

    @Test fun airQualityUsesTheCardLevels() {
        assertNull(HomeWeather.airQualityLevel(null))
        assertNull(HomeWeather.airQualityLevel(-1))
        assertEquals("优", HomeWeather.airQualityLevel(50))
        assertEquals("良", HomeWeather.airQualityLevel(51))
        assertEquals("轻度污染", HomeWeather.airQualityLevel(150))
        assertEquals("中度污染", HomeWeather.airQualityLevel(200))
        assertEquals("重度污染", HomeWeather.airQualityLevel(300))
        assertEquals("严重污染", HomeWeather.airQualityLevel(301))
    }

    @Test fun requestsCarryOnlyARoundedPosition() {
        val url = HomeWeather.forecastUrl(55.751244, 37.618423)
        assertTrue(url, url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=55.75&longitude=37.62&"))
        assertTrue(HomeWeather.airQualityUrl(-33.8688, 151.2093).contains("latitude=-33.87&longitude=151.21"))
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=%D0%A2%D0%B2%D0%B5%D1%80%D1%8C&count=1&format=json&language=ru",
            HomeWeather.geocodeUrl(" Тверь ", "ru"))
        assertTrue(HomeWeather.geocodeUrl("Paris", "de").endsWith("language=en"))
    }

    @Test fun cityIsTrimmedAndBounded() {
        assertEquals("Moscow", HomeWeather.cleanCity("  Mos\u0000cow \n"))
        assertEquals(60, HomeWeather.cleanCity("x".repeat(100)).length)
    }

    @Test fun aRefreshIsDueWithoutAReadingAfterHalfAnHourOrAfterTheClockWentBack() {
        val now = 1_795_368_460_000L
        assertTrue(HomeWeather.needsRefresh(0, now))
        assertTrue(HomeWeather.needsRefresh(now - HomeWeather.REFRESH_MS, now))
        assertTrue(HomeWeather.needsRefresh(now + 60_000, now))
        assertEquals(false, HomeWeather.needsRefresh(now - HomeWeather.REFRESH_MS + 1, now))
    }

    @Test fun freshnessMatchesTheAgent() {
        // hooks/home-weather-card.js MAX_AGE_MS
        assertEquals(3L * 60 * 60 * 1000, HomeWeather.MAX_AGE_MS)
        assertTrue(HomeWeather.REFRESH_MS < HomeWeather.MAX_AGE_MS)
    }
}
