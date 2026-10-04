package com.errorgap.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ApmSpan(
    val kind: String,
    val sql: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val function: String? = null,
    val durationMs: Double,
    /**
     * For a traced `http` call: the id sent in its `x-errorgap-trace` header.
     * The server request that recorded the header links to it.
     */
    val traceId: String? = null,
) {
    internal fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "kind" to kind,
        "duration_ms" to durationMs,
    ).apply {
        sql?.let { put("sql", it) }
        file?.let { put("file", it) }
        line?.let { put("line", it) }
        function?.let { put("fn_name", it) }
        traceId?.let { put("trace_id", it) }
    }

    companion object {
        @JvmStatic
        fun database(
            sql: String,
            durationMs: Double,
            file: String? = null,
            line: Int? = null,
            function: String? = null,
        ) = ApmSpan("db", normalizeSql(sql), file, line, function, durationMs)

        @JvmStatic
        fun external(
            durationMs: Double,
            file: String? = null,
            line: Int? = null,
            function: String? = null,
        ) = ApmSpan("http", file = file, line = line, function = function, durationMs = durationMs)
    }
}

data class ApmTransaction(
    val kind: String = "web",
    val method: String? = null,
    val path: String? = null,
    val pathRaw: String? = null,
    val statusCode: Int? = null,
    val durationMs: Double,
    val environment: String? = null,
    val occurredAt: String = isoTimestamp(),
    val spans: List<ApmSpan> = emptyList(),
    val jobClass: String? = null,
    val queue: String? = null,
    /** Links errors raised during this transaction to it; see [ErrorgapTransactionContext]. */
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    internal fun toMap(configuration: ErrorgapConfiguration): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "id" to id,
            "kind" to kind,
            "duration_ms" to durationMs,
            "environment" to (environment ?: configuration.environment),
            "occurred_at" to occurredAt,
            "spans" to spans.map(ApmSpan::toMap),
        ).apply {
            method?.let { put("method", it) }
            path?.let { put("path", it) }
            pathRaw?.let { put("path_raw", it) }
            statusCode?.let { put("status_code", it) }
            jobClass?.let { put("job_class", it) }
            queue?.let { put("queue", it) }
        }
}

class SpanCollector {
    private val spans = mutableListOf<ApmSpan>()

    @Synchronized
    fun add(span: ApmSpan) {
        spans += span
    }

    @Synchronized
    fun database(
        sql: String,
        durationMs: Double,
        file: String? = null,
        line: Int? = null,
        function: String? = null,
    ) = add(ApmSpan.database(sql, durationMs, file, line, function))

    @Synchronized
    fun external(
        durationMs: Double,
        file: String? = null,
        line: Int? = null,
        function: String? = null,
    ) = add(ApmSpan.external(durationMs, file, line, function))

    /**
     * Start a traced API call. Send [TracedCall.headers] with the request and
     * call [TracedCall.finish] when the response arrives: the `http` span
     * records the trace id, and a server SDK that records the header links the
     * server request to it.
     */
    fun startCall(label: String): TracedCall = TracedCall(label, this)

    /**
     * Time a traced API call: [block] gets the headers to send and its result
     * is returned. The span is recorded even if it throws.
     *
     * ```kotlin
     * val response = spans.traceCall("GET /api/orders/7") { headers ->
     *     client.newCall(Request.Builder().url(url).headers(headers.toHeaders()).build()).execute()
     * }
     * ```
     */
    inline fun <T> traceCall(label: String, block: (Map<String, String>) -> T): T {
        val call = startCall(label)
        try {
            return block(call.headers)
        } finally {
            call.finish()
        }
    }

    @Synchronized
    fun snapshot(): List<ApmSpan> = spans.toList()
}

/** The header that links an API call to the server request answering it. */
const val ERRORGAP_TRACE_HEADER = "x-errorgap-trace"

/** A traced outbound call in flight; see [SpanCollector.startCall]. */
class TracedCall internal constructor(
    private val label: String,
    private val collector: SpanCollector,
) {
    /** The id sent with the call. */
    val traceId: String = java.util.UUID.randomUUID().toString()

    /** Headers to add to the request: `x-errorgap-trace: traceId`. */
    val headers: Map<String, String> = mapOf(ERRORGAP_TRACE_HEADER to traceId)

    private val startedNanos = System.nanoTime()
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Record the call's span, timed from [SpanCollector.startCall]. Idempotent. */
    fun finish() {
        if (!finished.compareAndSet(false, true)) return
        collector.add(
            ApmSpan(
                kind = "http",
                function = label,
                durationMs = (System.nanoTime() - startedNanos) / 1_000_000.0,
                traceId = traceId,
            ),
        )
    }
}

private val quotedSql = Regex("'(?:''|[^'])*'")
private val numberSql = Regex("\\b\\d+(?:\\.\\d+)?\\b")
private val whitespace = Regex("\\s+")

fun normalizeSql(sql: String): String = sql
    .replace(quotedSql, "?")
    .replace(numberSql, "?")
    .replace(whitespace, " ")
    .trim()

internal fun isoTimestamp(): String = SimpleDateFormat(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    Locale.US,
).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())
