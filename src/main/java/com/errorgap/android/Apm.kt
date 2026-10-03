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
) {
    internal fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "kind" to kind,
        "duration_ms" to durationMs,
    ).apply {
        sql?.let { put("sql", it) }
        file?.let { put("file", it) }
        line?.let { put("line", it) }
        function?.let { put("fn_name", it) }
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

    @Synchronized
    fun snapshot(): List<ApmSpan> = spans.toList()
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
