package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-settings-shell-helper] Pure-logic guards for the helper transport.
 * The Android/IO halves are exercised on device; these pin the wire format the
 * shell loop parses and the platform-restriction classification.
 */
class ShellSettingsHelperTest {

    @Test
    fun `request encodes every field on its own line`() {
        val body = ShellSettingsHelper.encodeRequest(
            token = "abc123",
            op = "put",
            seq = "m1-7",
            ns = "system",
            key = "SmartModeStatus",
            value = "5",
        )
        assertEquals(
            listOf("tok=abc123", "op=put", "seq=m1-7", "ns=system", "key=SmartModeStatus", "val=5"),
            body.trimEnd('\n').split('\n'),
        )
    }

    @Test
    fun `values are flattened so a newline cannot inject a field`() {
        val body = ShellSettingsHelper.encodeRequest("t", "put", "s", "system", "k", "a\nop=del\nb")
        assertEquals(6, body.trimEnd('\n').split('\n').size)
        assertTrue(body.contains("val=a op=del b"))
    }

    @Test
    fun `response parse reads res and ignores noise`() {
        assertEquals("ok", ShellSettingsHelper.parseResponse("seq=s\nres=ok\npid=42\n"))
        assertEquals("fail", ShellSettingsHelper.parseResponse("res=fail"))
        assertNull(ShellSettingsHelper.parseResponse("seq=s\npid=42\n"))
    }

    @Test
    fun `platform restriction is recognised by its exact provider messages`() {
        val t = IllegalArgumentException("You cannot keep your settings in the secure settings.")
        assertTrue(SystemSettingsBridge.isPlatformRestriction(t))
        assertTrue(
            SystemSettingsBridge.isPlatformRestriction(
                IllegalArgumentException("You cannot change private secure settings."),
            ),
        )
        assertFalse(SystemSettingsBridge.isPlatformRestriction(SecurityException("Permission denial")))
        assertFalse(SystemSettingsBridge.isPlatformRestriction(RuntimeException()))
    }

    @Test
    fun `script is substituted and carries the fixed vocabulary only`() {
        val script = ShellSettingsHelper.scriptForTest
        // `$` placeholder substitution must have run...
        assertTrue(script.contains("D=\$(dirname \"\$0\""))
        assertTrue(script.contains("T=\$(cat "))
        // ...and no placeholder may survive into shell code.
        assertFalse(script.contains("~"))
        // The only commands it can reach.
        assertTrue(script.contains("settings put "))
        assertTrue(script.contains("settings delete "))
        assertFalse(script.contains("eval"))
        assertFalse(script.contains("sh -c"))
        // It refuses to act unless it is shell(2000)/root.
        assertTrue(script.contains("2000|0"))
    }
}
