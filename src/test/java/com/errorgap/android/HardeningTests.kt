package com.errorgap.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class HardeningTests {
    private fun config(endpoint: String, crashDirectory: String? = null, beforeNotify: ((Map<String, Any?>) -> Map<String, Any?>?)? = null) =
        ErrorgapConfiguration(
            endpoint = endpoint,
            projectSlug = "demo",
            async = false,
            timeoutMs = 1_000,
            crashDirectory = crashDirectory,
            beforeNotify = beforeNotify,
        )

    private fun stored(dir: File): List<File> =
        File(dir, "errorgap-crashes").listFiles()?.toList() ?: emptyList()

    @Test fun aCrashIsStoredAndSentOnTheNextLaunchWhenTheServerWasUnreachable() {
        val dir = Files.createTempDirectory("errorgap").toFile()
        // Nothing listens on port 1: the app "dies" with the crash undelivered.
        val dying = ErrorgapClient(config("http://127.0.0.1:1", dir.path))
        dying.notifyCrash(IllegalStateException("crashed offline"), waitMs = 1_500)
        dying.shutdown(500)
        assertEquals(1, stored(dir).size)

        FakeIngestor().use { ing ->
            val next = ErrorgapClient(config(ing.endpoint, dir.path))
            try {
                assertEquals(1, next.sendStoredCrashes())
                assertTrue(ing.requests()[0].body.contains("crashed offline"))
                assertEquals(0, stored(dir).size)
            } finally {
                next.shutdown(500)
            }
        }
    }

    @Test fun aDeliveredCrashIsNotKeptAndIsSentOffTheCrashingThread() {
        val dir = Files.createTempDirectory("errorgap").toFile()
        FakeIngestor().use { ing ->
            val client = ErrorgapClient(config(ing.endpoint, dir.path))
            try {
                val result = client.notifyCrash(RuntimeException("crashed online"))
                assertEquals(201, result.status)
                assertEquals(1, ing.requests().size)
                assertEquals(0, stored(dir).size)
            } finally {
                client.shutdown(500)
            }
        }
    }

    @Test fun beforeNotifyCanDropOrChangeANoticeAndAThrowingHookIsSkipped() {
        FakeIngestor().use { ing ->
            val drop = ErrorgapClient(config(ing.endpoint, beforeNotify = { null }))
            val change = ErrorgapClient(config(ing.endpoint, beforeNotify = { notice ->
                @Suppress("UNCHECKED_CAST")
                notice + ("context" to ((notice["context"] as Map<String, Any?>) + ("team" to "billing")))
            }))
            val broken = ErrorgapClient(config(ing.endpoint, beforeNotify = { throw IllegalStateException("bug") }))
            try {
                assertEquals("dropped by beforeNotify", drop.notify(RuntimeException("no")).body)
                assertTrue(change.notify(RuntimeException("yes")).success)
                assertTrue(broken.notify(RuntimeException("still sent")).success)
                val bodies = ing.requests().map { it.body }
                assertEquals(2, bodies.size)
                assertTrue(bodies[0].contains("\"team\":\"billing\""))
                assertTrue(bodies[1].contains("still sent"))
            } finally {
                listOf(drop, change, broken).forEach { it.shutdown(500) }
            }
        }
    }

    @Test fun a429PausesSendingAndOnlyNetworkAndServerErrorsAreRetried() {
        FakeIngestor(status = 429).use { ing ->
            val client = ErrorgapClient(config(ing.endpoint))
            try {
                assertEquals(429, client.notify(RuntimeException("one")).status)
                assertTrue(client.paused())
                assertTrue(client.notify(RuntimeException("two")).error is RateLimitedException)
                assertEquals(1, ing.requests().size)

                assertTrue(client.retryable(DeliveryResult(status = 500)))
                assertTrue(client.retryable(DeliveryResult(error = java.io.IOException("refused"))))
                assertFalse(client.retryable(DeliveryResult(status = 422)))
                assertFalse(client.retryable(DeliveryResult(status = 429)))
                assertFalse(client.retryable(DeliveryResult(error = RateLimitedException())))
            } finally {
                client.shutdown(500)
            }
        }
    }

    @Test fun nestedKeysAreMaskedInParamsAndSessionAndTheUserIsNamed() {
        val notice = Notice.build(
            RuntimeException("x"),
            ErrorgapConfiguration(projectSlug = "demo"),
            NoticeOptions(
                params = mapOf("users" to listOf(mapOf("password" to "p", "name" to "Ada")), "long" to "x".repeat(10_000)),
                session = mapOf("auth_token" to "t"),
                userId = "7",
                userEmail = "ada@example.com",
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val params = notice["params"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val user = (params["users"] as List<Map<String, Any?>>)[0]
        assertEquals("[FILTERED]", user["password"])
        assertEquals("Ada", user["name"])
        assertTrue((params["long"] as String).endsWith("[TRUNCATED]"))
        assertEquals(
            mapOf("user_id" to "7", "user_email" to "ada@example.com", "auth_token" to "[FILTERED]"),
            notice["session"],
        )
    }

    @Test fun nothingIsSentInAnIgnoredEnvironment() {
        val client = ErrorgapClient(
            ErrorgapConfiguration(projectSlug = "demo", environment = "test", ignoreEnvironments = listOf("test")),
        )
        try {
            assertEquals("ignored environment", client.notify(RuntimeException("x")).body)
        } finally {
            client.shutdown(500)
        }
    }
}
