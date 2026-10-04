package com.errorgap.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TracedCallTests {
    @Test
    fun traceCallRecordsTheTraceIdItSends() {
        val spans = SpanCollector()
        var sent: Map<String, String> = emptyMap()
        val result = spans.traceCall("GET /api/orders/7") { headers ->
            sent = headers
            "ok"
        }
        assertEquals("ok", result)
        val span = spans.snapshot().single()
        assertEquals("http", span.kind)
        assertEquals("GET /api/orders/7", span.function)
        val traceId = span.traceId!!
        assertEquals(traceId, java.util.UUID.fromString(traceId).toString())
        assertEquals(mapOf("x-errorgap-trace" to traceId), sent)
        assertEquals(traceId, span.toMap()["trace_id"])
        assertEquals("GET /api/orders/7", span.toMap()["fn_name"])
    }

    @Test
    fun traceCallRecordsTheSpanWhenItThrowsAndFinishIsIdempotent() {
        val spans = SpanCollector()
        assertThrows(IllegalStateException::class.java) {
            spans.traceCall<Unit>("POST /api/pay") { throw IllegalStateException("offline") }
        }
        val call = spans.startCall("GET /api/menu")
        call.finish()
        call.finish()
        assertEquals(listOf("POST /api/pay", "GET /api/menu"), spans.snapshot().map { it.function })
    }
}
