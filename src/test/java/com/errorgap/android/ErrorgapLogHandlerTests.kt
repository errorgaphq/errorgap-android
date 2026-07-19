package com.errorgap.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.logging.Level
import java.util.logging.LogRecord

class ErrorgapLogHandlerTests {
    @Test fun forwardsJavaUtilLoggingRecords() {
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
                val record = LogRecord(Level.WARNING, "payment gateway timeout").apply {
                    sourceClassName = "CheckoutService"
                    sourceMethodName = "charge"
                }
                ErrorgapLogHandler(client).publish(record)
                val request = ing.requests().single()
                assertEquals("/api/projects/demo/logs", request.path)
                assertEquals(true, request.body.contains("CheckoutService.charge"))
            } finally {
                client.shutdown(2_000)
            }
        }
    }
}
