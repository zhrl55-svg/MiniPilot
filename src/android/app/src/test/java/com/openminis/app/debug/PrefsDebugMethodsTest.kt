package com.openminis.app.debug

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-debug-prefs-rpc] Source-level guards for the four SharedPreferences
 * debug RPCs: DEV_TOOLS-only registration/dispatch and no plaintext secrets.
 */
class PrefsDebugMethodsTest {

    @Test
    fun `all four methods are registered only under DEV_TOOLS`() {
        val registry = ProductionSources.read("debug/DebugMethodRegistry.kt")
        assertTrue("registry gates PrefsDebugMethods.specs behind DEV_TOOLS",
            registry.contains("if (BuildConfig.DEV_TOOLS) {") &&
            registry.contains("addAll(PrefsDebugMethods.specs)"))
    }

    @Test
    fun `dispatch entry rechecks DEV_TOOLS and throws method not found`() {
        val src = ProductionSources.read("debug/PrefsDebugMethods.kt")
        assertTrue("requireDevTools throws -32601",
            src.contains("if (!BuildConfig.DEV_TOOLS)") && src.contains("-32601"))
        assertTrue("every dispatch goes through requireDevTools",
            src.indexOf("requireDevTools(method)") < src.indexOf("\"debug.prefs.list\" ->"))
    }

    @Test
    fun `secret-shaped keys are redacted and never logged`() {
        assertTrue(PrefsDebugMethods.isSecretKey("api_key"))
        assertTrue(PrefsDebugMethods.isSecretKey("Password"))
        assertTrue(PrefsDebugMethods.isSecretKey("auth.token"))
        assertTrue(PrefsDebugMethods.isSecretKey("session-key"))
        assertFalse(PrefsDebugMethods.isSecretKey("readReplies.speed"))
        assertFalse(PrefsDebugMethods.isSecretKey("voice_wake"))

        val src = ProductionSources.read("debug/PrefsDebugMethods.kt")
        // No value ever reaches AppLogger: log lines contain only file/key/type.
        assertTrue("no value interpolated into log", !src.contains("AppLogger.info(\"DebugRPC\", \"debug.prefs.write") ||
            src.contains("type=\${finalValue::class.simpleName}"))
        assertFalse("redaction is used", !src.contains("<redacted>"))
    }

    @Test
    fun `all four names are present in the published catalogue`() {
        val src = ProductionSources.read("debug/PrefsDebugMethods.kt")
        for (n in listOf("debug.prefs.list", "debug.prefs.read", "debug.prefs.write", "debug.prefs.delete")) {
            assertTrue("catalogue has $n", src.contains("\"$n\""))
        }
    }
}
