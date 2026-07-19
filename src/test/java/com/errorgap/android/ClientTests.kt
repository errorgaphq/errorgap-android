package com.errorgap.android

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

    @Test fun postsApmTransactionsAndNormalizesSql() {
        FakeIngestor().use { ing ->
            val client = ErrorgapClient(
                ErrorgapConfiguration(
                    endpoint = ing.endpoint,
                    projectSlug = "demo",
                    apiKey = "flk_test",
                    async = false,
                    apmEnabled = true,
                )
            )
            try {
                val result = client.notifyTransaction(
                    ApmTransaction(
                        method = "GET",
                        path = "/orders/{id}",
                        statusCode = 200,
                        durationMs = 12.5,
                        spans = listOf(
                            ApmSpan.database(
                                "SELECT * FROM orders WHERE id = 42 AND state = 'paid'",
                                2.5,
                                "OrderRepository.kt",
                                41,
                                "OrderRepository.find",
                            )
                        ),
                    )
                )
                assertTrue(result.success)
                val request = ing.requests().single()
                assertEquals("/api/projects/demo/transactions", request.path)
                assertTrue(request.body.contains("\"duration_ms\":12.5"))
                assertTrue(request.body.contains("SELECT * FROM orders WHERE id = ? AND state = ?"))
            } finally {
                client.shutdown(2_000)
            }
        }
    }

    @Test fun skipsApmWhenDisabled() {
        FakeIngestor().use { ing ->
            val client = ErrorgapClient(
                ErrorgapConfiguration(
                    endpoint = ing.endpoint,
                    projectSlug = "demo",
                    async = false,
                    apmEnabled = false,
                )
            )
            try {
                assertEquals(204, client.notifyTransaction(ApmTransaction(durationMs = 1.0)).status)
                assertTrue(ing.requests().isEmpty())
            } finally {
                client.shutdown(2_000)
            }
        }
    }

    @Test fun forwardsLogsAtConfiguredMinimumLevel() {
        FakeIngestor().use { ing ->
            val client = ErrorgapClient(
                ErrorgapConfiguration(
                    endpoint = ing.endpoint,
                    projectSlug = "demo",
                    async = false,
                    logsEnabled = true,
                    minimumLogLevel = "warn",
                )
            )
            try {
                assertEquals(204, client.notifyLog("not sent", "info").status)
                assertTrue(client.notifyLog("gateway timeout", "warning", "checkout").success)
                val request = ing.requests().single()
                assertEquals("/api/projects/demo/logs", request.path)
                assertTrue(request.body.contains("\"level\":\"warn\""))
                assertTrue(request.body.contains("\"source\":\"checkout\""))
            } finally {
                client.shutdown(2_000)
            }
        }
    }

    @Test fun trackJobReportsFailureAndJobTransaction() {
        FakeIngestor().use { ing ->
            val client = ErrorgapClient(
                ErrorgapConfiguration(
                    endpoint = ing.endpoint,
                    projectSlug = "demo",
                    async = false,
                    apmEnabled = true,
                )
            )
            try {
                assertThrows(IllegalStateException::class.java) {
                    client.trackJob("ReceiptJob", "critical") { spans ->
                        spans.database("SELECT 7", 3.0, function = "ReceiptJob.run")
                        throw IllegalStateException("receipt failed")
                    }
                }
                assertEquals(
                    listOf(
                        "/api/projects/demo/notices",
                        "/api/projects/demo/transactions",
                    ),
                    ing.requests().map { it.path },
                )
                assertTrue(ing.requests()[1].body.contains("\"kind\":\"job\""))
                assertTrue(ing.requests()[1].body.contains("\"job_class\":\"ReceiptJob\""))
            } finally {
                client.shutdown(2_000)
            }
        }
    }
}
