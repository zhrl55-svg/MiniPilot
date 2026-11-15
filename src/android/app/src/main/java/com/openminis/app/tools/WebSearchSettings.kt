package com.openminis.app.tools

import android.content.Context
import com.openminis.app.util.EncryptedPrefsFactory

/**
 * [T-android-brave-web-search] Storage for the Brave Search API key.
 *
 * A plain site credential, not a provider account: it lives in its own
 * encrypted SharedPreferences file (the same [EncryptedPrefsFactory] backing
 * the hub credential), never in ProviderConfig and never in logs.
 */
object WebSearchSettings {

    private const val PREFS = "minis_web_search_secrets"
    private const val KEY_BRAVE = "brave_api_key"

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_BRAVE, null).orEmpty().trim()

    fun setApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        val editor = prefs(context).edit()
        if (trimmed.isEmpty()) editor.remove(KEY_BRAVE) else editor.putString(KEY_BRAVE, trimmed)
        editor.apply()
    }

    fun hasApiKey(context: Context): Boolean = apiKey(context).isNotBlank()

    private fun prefs(context: Context) =
        EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS)
}
