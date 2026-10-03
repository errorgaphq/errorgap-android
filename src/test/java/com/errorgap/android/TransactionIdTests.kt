package com.errorgap.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Errors reported during a transaction carry its id, so errorgap links the two. */
class TransactionIdTests {
    @Test fun aNoticeInsideATransactionCarriesItsId() {
        FakeIngestor().use { ing ->
            val client = ErrorgapClient(
                ErrorgapConfiguration(endpoint = ing.endpoint, projectSlug = "demo", async = false, apmEnabled = true),
            )
            try {
                val transaction = ApmTransaction(path = "/checkout", durationMs = 12.0)
                ErrorgapTransactionContext.run(transaction.id) {
                    client.notify(IllegalStateException("declined"))
                    client.notify(
                        IllegalStateException("explicit"),
                        NoticeOptions(context = mapOf("transaction_id" to "mine")),
                    )
                }
                client.notify(IllegalStateException("after"))
                client.notifyTransaction(transaction)

                val bodies = ing.requests().map { it.body }
                assertTrue(bodies[0].contextOnly().contains("\"transaction_id\":\"${transaction.id}\""), bodies[0])
                assertTrue(bodies[1].contextOnly().contains("\"transaction_id\":\"mine\""), bodies[1])
                assertFalse(bodies[2].contextOnly().contains("transaction_id"), bodies[2])
                assertTrue(bodies[3].contains("\"id\":\"${transaction.id}\""), bodies[3])
                assertNull(ErrorgapTransactionContext.current())
            } finally {
                client.shutdown(2_000)
            }
        }
    }

    @Test fun aFailedJobsErrorCarriesTheJobsId() {
        FakeIngestor().use { ing ->
            val client = ErrorgapClient(
                ErrorgapConfiguration(endpoint = ing.endpoint, projectSlug = "demo", async = false, apmEnabled = true),
            )
            try {
                var seen: String? = null
                runCatching {
                    client.trackJob("ReceiptJob") {
                        seen = ErrorgapTransactionContext.current()
                        throw IllegalStateException("smtp down")
                    }
                }
                val requests = ing.requests()
                val notice = requests.first { it.path.endsWith("/notices") }.body
                val transaction = requests.first { it.path.endsWith("/transactions") }.body
                assertNotNull(seen)
                assertTrue(notice.contextOnly().contains("\"transaction_id\":\"$seen\""), notice)
                assertTrue(transaction.contains("\"id\":\"$seen\""), transaction)
            } finally {
                client.shutdown(2_000)
            }
        }
    }

    /** The context object only: backtrace source excerpts quote this file's code. */
    private fun String.contextOnly(): String = substring(lastIndexOf("\"context\":"))
}
