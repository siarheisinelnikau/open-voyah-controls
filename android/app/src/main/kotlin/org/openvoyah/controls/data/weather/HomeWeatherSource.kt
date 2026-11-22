package org.openvoyah.controls.data.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.weather.HomeWeather
import org.openvoyah.controls.domain.weather.HomeWeatherReading
import org.openvoyah.controls.domain.weather.WeatherCondition
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** The city the home screen weather falls back to when the car's location is unknown. */
class HomeWeatherPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("home_weather", Context.MODE_PRIVATE)

    var city: String
        get() = preferences.getString("city", "") ?: ""
        set(value) { preferences.edit().putString("city", HomeWeather.cleanCity(value)).apply() }
}

/** Why no reading could be made; the card then shows "no data" once the last reading is too old. */
class HomeWeatherUnavailable(val reason: String) : IOException(reason)

/**
 * Current weather for the home screen card from Open-Meteo, which needs no account or key. The position is the
 * last one Android knows (rounded to about 1 km before it leaves the car), else the city from the settings.
 */
class HomeWeatherSource(private val context: Context) {
    fun read(now: Long = System.currentTimeMillis()): HomeWeatherReading {
        val (latitude, longitude) = position() ?: throw HomeWeatherUnavailable("no_location")
        val current = get(HomeWeather.forecastUrl(latitude, longitude)).getJSONObject("current")
        val condition = WeatherCondition.fromWmo(current.getInt("weather_code"))
            ?: throw HomeWeatherUnavailable("unknown_condition")
        val temperature = current.getDouble("temperature_2m")
        if (temperature.isNaN()) throw HomeWeatherUnavailable("no_temperature")
        // Air quality is optional; the card hides its level without it.
        val aqi = try {
            get(HomeWeather.airQualityUrl(latitude, longitude)).optJSONObject("current")
                ?.takeIf { it.has("us_aqi") && !it.isNull("us_aqi") }?.getInt("us_aqi")
        } catch (_: Exception) { null }
        return HomeWeatherReading(condition, HomeWeather.temperature(temperature), HomeWeather.airQualityLevel(aqi), now)
    }

    /** The JSON the launcher agent reads (`org.openvoyah.controls.homeWeather`). */
    fun payload(reading: HomeWeatherReading): String = JSONObject()
        .put("schema", 1)
        .put("code", reading.condition.launcherCode)
        .put("text", conditionText(reading.condition))
        .put("temperature", reading.temperature)
        .put("aqi", reading.airQuality ?: JSONObject.NULL)
        .put("fetchedAt", reading.fetchedAt)
        .toString()

    private fun position(): Pair<Double, Double>? {
        lastKnown()?.let { return it.latitude to it.longitude }
        val city = HomeWeatherPreferences(context).city
        if (city.isEmpty()) return null
        val results = get(HomeWeather.geocodeUrl(city, UiText.language())).optJSONArray("results")
        val first = results?.optJSONObject(0) ?: throw HomeWeatherUnavailable("city_not_found")
        return first.getDouble("latitude") to first.getDouble("longitude")
    }

    private fun lastKnown(): Location? {
        val granted = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (_: SecurityException) { null }
    }

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "OpenVoyahControls")
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode != 200) throw IOException("HTTP " + connection.responseCode)
            val body = connection.inputStream.use { stream ->
                val bytes = stream.readNBytesCompat(256 * 1024)
                String(bytes, Charsets.UTF_8)
            }
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun conditionText(condition: WeatherCondition): String = UiText.get(when (condition) {
        WeatherCondition.CLEAR -> R.string.home_weather_clear
        WeatherCondition.CLOUDY -> R.string.home_weather_cloudy
        WeatherCondition.OVERCAST -> R.string.home_weather_overcast
        WeatherCondition.FOG -> R.string.home_weather_fog
        WeatherCondition.RAIN -> R.string.home_weather_rain
        WeatherCondition.SNOW -> R.string.home_weather_snow
        WeatherCondition.THUNDERSTORM -> R.string.home_weather_thunderstorm
        WeatherCondition.HAIL -> R.string.home_weather_hail
    })
}

/** Reads at most [limit] bytes; a larger answer is not weather. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        if (out.size() > limit) throw IOException("Response too large")
    }
    return out.toByteArray()
}
