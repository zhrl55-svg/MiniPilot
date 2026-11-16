package com.openminis.app.assistant.context

import android.content.Context

/**
 * [T-android-context-injection] Per-source switches for the device context
 * appended to the model system prompt.
 *
 * The block is request-only: it is never written into chat history. A source
 * only earns its place when the line saves the model a tool call: time,
 * battery, network, weather and the foreground app are cheap state that the
 * model would otherwise have to query. Notifications, notification titles,
 * location and screen text stay OFF until the user asks for them — their
 * content is third-party text or identifying data, and a tool can fetch it
 * with better fidelity.
 */
object ContextInjectionSettings {

    private const val PREFS = "minis_context_injection"
    private const val KEY_TIME = "context.time"
    private const val KEY_BATTERY = "context.battery"
    private const val KEY_NETWORK = "context.network"
    private const val KEY_WEATHER = "context.weather"
    private const val KEY_LOCATION = "context.location"
    private const val KEY_NOTIFICATIONS = "context.notifications"
    private const val KEY_NOTIFICATION_TITLES = "context.notification_titles"
    private const val KEY_CURRENT_APP = "context.current_app"
    private const val KEY_SCREEN_TEXT = "context.screen_text"

    data class Config(
        val time: Boolean = true,
        val battery: Boolean = true,
        val network: Boolean = true,
        val weather: Boolean = true,
        val location: Boolean = false,
        val notifications: Boolean = false,
        val notificationTitles: Boolean = false,
        val currentApp: Boolean = true,
        val screenText: Boolean = false,
    ) {
        val anyEnabled: Boolean
            get() = time || battery || network || weather || location ||
                notifications || currentApp || screenText
    }

    fun load(context: Context): Config {
        val prefs = prefs(context)
        return Config(
            time = prefs.getBoolean(KEY_TIME, true),
            battery = prefs.getBoolean(KEY_BATTERY, true),
            network = prefs.getBoolean(KEY_NETWORK, true),
            weather = prefs.getBoolean(KEY_WEATHER, true),
            location = prefs.getBoolean(KEY_LOCATION, false),
            notifications = prefs.getBoolean(KEY_NOTIFICATIONS, false),
            notificationTitles = prefs.getBoolean(KEY_NOTIFICATION_TITLES, false),
            currentApp = prefs.getBoolean(KEY_CURRENT_APP, true),
            screenText = prefs.getBoolean(KEY_SCREEN_TEXT, false),
        )
    }

    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putBoolean(KEY_TIME, config.time)
            .putBoolean(KEY_BATTERY, config.battery)
            .putBoolean(KEY_NETWORK, config.network)
            .putBoolean(KEY_WEATHER, config.weather)
            .putBoolean(KEY_LOCATION, config.location)
            .putBoolean(KEY_NOTIFICATIONS, config.notifications)
            .putBoolean(KEY_NOTIFICATION_TITLES, config.notificationTitles)
            .putBoolean(KEY_CURRENT_APP, config.currentApp)
            .putBoolean(KEY_SCREEN_TEXT, config.screenText)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
