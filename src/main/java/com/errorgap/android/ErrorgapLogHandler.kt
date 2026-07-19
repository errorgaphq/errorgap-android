package com.errorgap.android

import java.util.logging.Handler
import java.util.logging.LogRecord

/**
 * Dependency-free java.util.logging bridge. Android apps using another
 * logging facade can call [Errorgap.notifyLog] from their logging adapter.
 */
class ErrorgapLogHandler(
    private val client: ErrorgapClient? = null,
) : Handler() {
    override fun publish(record: LogRecord?) {
        if (record == null || !isLoggable(record)) return
        val message = try {
            formatter?.format(record)?.trim().takeUnless { it.isNullOrEmpty() } ?: record.message
        } catch (_: Exception) {
            record.message
        }
        val source = record.sourceClassName
            ?.let { className -> record.sourceMethodName?.let { "$className.$it" } ?: className }
            ?: record.loggerName
        if (client != null) {
            client.notifyLog(message ?: "", record.level.name, source)
        } else {
            Errorgap.notifyLog(message ?: "", record.level.name, source)
        }
    }

    override fun flush() = Unit

    override fun close() = Unit
}
