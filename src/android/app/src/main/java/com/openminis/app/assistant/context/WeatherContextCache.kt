package com.openminis.app.assistant.context

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.WeatherManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * [T-android-context-injection] Cached weather/place lines for the
 * per-message context block.
 *
 * A model request must never wait for a network call, a fresh GPS fix or a
 * reverse geocode. [refreshAsync] warms the cache from the last known location
 * on a background coroutine; [cachedWeatherLine] and [cachedLocationLine] only
 * read the cache. The cache is in memory and disappears with the process —
 * the block is request-only anyway.
 *
 * The location line is a readable place name (`甘肃 张掖 甘州区`), resolved
 * through the system [Geocoder] and cached for the same 30 minutes as the
 * weather. Raw coordinates are kept as the fallback when the Geocoder is
 * missing or returns nothing, so the line never silently becomes empty.
 *
 * Weather data comes from the same Open-Meteo path as `android-weather`, so a
 * manual weather query and the injected line cannot disagree about the source.
 */
object WeatherContextCache {

    private const val TAG = "ContextWeather"
    private const val TTL_MS = 30 * 60 * 1000L
    private const val RETRY_MS = 5 * 60 * 1000L

    @Volatile private var weatherLine: String? = null
    @Volatile private var weatherAtMs: Long = 0L

    /** Coordinate fallback used when reverse geocoding has no result. */
    @Volatile private var locationLine: String? = null
    @Volatile private var locationAtMs: Long = 0L

    /** Readable place name; preferred over [locationLine] when fresh. */
    @Volatile private var placeLine: String? = null
    @Volatile private var placeAtMs: Long = 0L

    @Volatile private var lastAttemptMs: Long = 0L

    private val refreshLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cachedWeatherLine(now: Long = System.currentTimeMillis()): String? {
        val line = weatherLine ?: return null
        return if (now - weatherAtMs <= TTL_MS) line else null
    }

    /** Place name when available, otherwise the coordinate fallback. */
    fun cachedLocationLine(now: Long = System.currentTimeMillis()): String? {
        cachedPlaceLine(now)?.let { return it }
        val line = locationLine ?: return null
        return if (now - locationAtMs <= TTL_MS) line else null
    }

    fun cachedPlaceLine(now: Long = System.currentTimeMillis()): String? {
        val line = placeLine ?: return null
        return if (now - placeAtMs <= TTL_MS) line else null
    }

    /** Warms the cache at app start when either weather or location is on. */
    fun warm(context: Context) {
        val config = ContextInjectionSettings.load(context)
        if (config.weather || config.location) refreshAsync(context)
    }

    /**
     * Refreshes only the parts that are enabled and stale (or [force]d), from
     * the best last-known fix — never a fresh GPS request. Rate-limited so a
     * burst of messages cannot start a burst of requests.
     */
    fun refreshAsync(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        synchronized(refreshLock) {
            if (!force && now - lastAttemptMs < RETRY_MS) return
            lastAttemptMs = now
        }
        val appContext = context.applicationContext
        scope.launch {
            try {
                val config = ContextInjectionSettings.load(appContext)
                val attemptMs = System.currentTimeMillis()
                val needLocation = config.location &&
                    (force || cachedLocationLine(attemptMs) == null)
                val needPlace = config.location &&
                    (force || cachedPlaceLine(attemptMs) == null)
                val needWeather = config.weather &&
                    (force || cachedWeatherLine(attemptMs) == null)
                if (!needLocation && !needPlace && !needWeather) return@launch

                // The location lookup and the geocoder are binder calls; keep
                // them off the UI thread along with the network request.
                val location = lastKnownLocation(appContext) ?: return@launch
                if (needLocation) {
                    locationLine = formatCoordinates(location)
                    locationAtMs = System.currentTimeMillis()
                }
                if (needPlace) {
                    val place = reverseGeocode(appContext, location)
                    if (!place.isNullOrBlank()) {
                        placeLine = place
                        placeAtMs = System.currentTimeMillis()
                    }
                }
                if (needWeather) {
                    val json = WeatherManager.fetchWeatherJson(
                        location.latitude,
                        location.longitude,
                    ) ?: return@launch
                    val current = json.optJSONObject("current") ?: return@launch
                    val units = json.optJSONObject("current_units")
                    val line = formatWeather(current, units)
                    if (line.isNotBlank()) {
                        weatherLine = line
                        weatherAtMs = System.currentTimeMillis()
                    }
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "context refresh failed: ${t.message}")
            }
        }
    }

    private fun lastKnownLocation(context: Context): Location? {
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!coarse && !fine) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE)
            as? LocationManager ?: return null
        var best: Location? = null
        val providers = try {
            manager.getProviders(true)
        } catch (_: Throwable) {
            emptyList()
        }
        for (provider in providers) {
            val location = try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: Throwable) {
                null
            } ?: continue
            if (best == null || location.time > best.time) best = location
        }
        return best
    }

    private fun reverseGeocode(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = try {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(location.latitude, location.longitude, 1)
        } catch (_: Throwable) {
            null
        } ?: return null
        val address = addresses.firstOrNull() ?: return null
        return formatPlace(
            adminArea = address.adminArea,
            locality = address.locality,
            district = address.subLocality ?: address.subAdminArea,
        ) ?: address.featureName?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * `甘肃 张掖 甘州区` — province → city → district, without the 省/市
     * suffixes. Duplicates are dropped (municipalities repeat the city name).
     * Null when every component is empty.
     */
    fun formatPlace(adminArea: String?, locality: String?, district: String?): String? {
        val parts = listOfNotNull(
            // Municipalities repeat the city in adminArea ("北京市"), so the
            // province slot must drop 市 as well as 省.
            adminArea?.trim()?.removeSuffix("省")?.removeSuffix("市"),
            locality?.trim()?.removeSuffix("市"),
            district?.trim()?.removeSuffix("市"),
        ).filter { it.isNotBlank() }.distinct()
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    private fun formatCoordinates(location: Location): String =
        String.format(
            Locale.US,
            "%.5f, %.5f",
            location.latitude,
            location.longitude,
        )

    private fun formatWeather(current: JSONObject, units: JSONObject?): String {
        val temp = current.optDouble("temperature_2m", Double.NaN)
        val feels = current.optDouble("apparent_temperature", Double.NaN)
        val humidity = current.optInt("relative_humidity_2m", -1)
        val wind = current.optDouble("wind_speed_10m", Double.NaN)
        val code = current.optInt("weather_code", -1)
        val tempUnit = units?.optString("temperature_2m", "°C") ?: "°C"
        val windUnit = units?.optString("wind_speed_10m", "km/h") ?: "km/h"

        val parts = mutableListOf<String>()
        if (code >= 0) parts += WeatherManager.weatherCodeToDescription(code)
        if (temp.isFinite()) {
            val value = String.format(Locale.US, "%.1f", temp) + tempUnit
            parts += if (feels.isFinite()) {
                "$value (feels ${String.format(Locale.US, "%.1f", feels)}$tempUnit)"
            } else {
                value
            }
        }
        if (humidity >= 0) parts += "humidity $humidity%"
        if (wind.isFinite()) {
            parts += "wind ${String.format(Locale.US, "%.1f", wind)} $windUnit"
        }
        return parts.joinToString(", ")
    }
}
