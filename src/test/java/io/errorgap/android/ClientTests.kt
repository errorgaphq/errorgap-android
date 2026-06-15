package io.errorgap.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClientTests {
    @Test fun postsToNoticesWithCanonicalHeaders() {
        FakeIngestor().use { ing ->
            val cfg = ErrorgapConfiguration(
                endpoint = ing.endpoint,
                projectSlug = "demo",
                apiKey = "flk_test",
                async = false,
            )
            val client = ErrorgapClient(cfg)
            try {
                val result = client.notify(IllegalStateException("test"))
                assertTrue(result.success)

                val reqs = ing.requests()
                assertEquals(1, reqs.size)
                val req = reqs[0]
                assertEquals("POST", req.method)
                assertEquals("/api/projects/demo/notices", req.path)
                assertEquals("flk_test", req.headers["x-errorgap-project-key"])
                assertTrue(req.headers["user-agent"]!!.startsWith("errorgap-android/"))
            } finally {
                client.shutdown(2_000)
            }
        }
    }

    @Test fun asyncQueuesAndFlushes() {
        FakeIngestor().use { ing ->
            val cfg = ErrorgapConfiguration(
                endpoint = ing.endpoint,
                projectSlug = "demo",
                apiKey = "flk_test",
                async = true,
            )
            val client = ErrorgapClient(cfg)
            try {
                val result = client.notify(RuntimeException("x"))
                assertTrue(result.queued)
                assertEquals(202, result.status)
                client.flush(5_000)
                assertEquals(1, ing.requests().size)
            } finally {
                client.shutdown(2_000)
            }
        }
    }

    @Test fun rejectsMissingProjectSlug() {
        FakeIngestor().use { ing ->
            val cfg = ErrorgapConfiguration(endpoint = ing.endpoint, projectSlug = null)
            val client = ErrorgapClient(cfg)
            try {
                val result = client.notify(RuntimeException("x"))
                assertNotNull(result.error)
                assertTrue(ing.requests().isEmpty())
            } finally {
                client.shutdown(2_000)
            }
        }
    }
}
