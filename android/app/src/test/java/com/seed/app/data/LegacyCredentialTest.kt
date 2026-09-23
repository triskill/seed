package com.seed.app.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class LegacyCredentialTest {
    @Test fun `absent legacy file never opens encrypted preferences`() {
        var opened = false
        val credential = LegacyCredential({ false }, { opened = true; error("keystore") })
        assertEquals("", credential.read())
        credential.clear()
        assertFalse(opened)
    }

    @Test fun `broken legacy store does not prevent settings hydration`() {
        val credential = LegacyCredential({ true }, { error("keystore unavailable") })
        assertEquals("", credential.read())
    }

    @Test fun `failed removal leaves legacy credential for retry`() {
        var removed = false
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, _ ->
            when (method.name) {
                "remove" -> { removed = true; proxy }
                "commit" -> false
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "edit" -> editor
                "getString" -> "old-secret"
                else -> error(method.name)
            }
        } as SharedPreferences
        val credential = LegacyCredential({ true }, { prefs })
        assertEquals("old-secret", credential.read())
        try { credential.clear(); error("expected failure") } catch (_: IllegalStateException) { }
        assertTrue(removed)
        assertEquals("old-secret", credential.read())
    }
}
