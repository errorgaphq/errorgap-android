package com.errorgap.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemoteSettingsTests {
    @Test fun aSwitchedOffKindIsNotSentAndEverythingStaysOnWhenUnreadable() {
        FakeIngestor().use { ing ->
            ing.settings = """{"errors_enabled":false,"apm_enabled":true,"poll_interval_seconds":600}"""
            val client = ErrorgapClient(
                ErrorgapConfiguration(endpoint = ing.endpoint, projectSlug = "demo", async = false, remoteSettings = true),
            )
            try {
                assertTrue(client.remote.refresh())
                assertFalse(client.remote.enabled(RemoteSettings.Kind.ERRORS))
                assertTrue(client.remote.enabled(RemoteSettings.Kind.APM))
                assertEquals("errors are switched off for this project", client.notify(RuntimeException("x")).body)
                assertEquals(0, ing.requests().size)

                // The switch is turned back on.
                ing.settings = """{"errors_enabled":true}"""
                assertTrue(client.remote.refresh())
                assertTrue(client.notify(RuntimeException("y")).success)

                // Unreadable settings keep what was known.
                ing.settings = null
                assertFalse(client.remote.refresh())
                assertTrue(client.remote.enabled(RemoteSettings.Kind.ERRORS))
            } finally {
                client.shutdown(500)
            }
        }
        val off = ErrorgapClient(ErrorgapConfiguration(endpoint = "http://127.0.0.1:1", projectSlug = "demo", remoteSettings = false))
        assertTrue(off.remote.enabled(RemoteSettings.Kind.ERRORS))
        off.shutdown(500)
    }
}
