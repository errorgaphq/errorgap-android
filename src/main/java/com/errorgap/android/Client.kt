package com.errorgap.android

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class DeliveryResult(
    val status: Int? = null,
    val body: String? = null,
    val error: Throwable? = null,
    val queued: Boolean = false,
) {
    val success: Boolean
        get() = error == null && status != null && status in 200..299
}

class ErrorgapClient(
    @Volatile private var configuration: ErrorgapConfiguration,
) {
    private data class Delivery(val resource: String, val payload: Map<String, Any?>)

    private val queue: LinkedBlockingQueue<Delivery> =
        LinkedBlockingQueue(configuration.queueSize)
    private val inFlight = AtomicInteger(0)
    @Volatile private var running = true
    private val worker: Thread = Thread({ loop() }, "errorgap-delivery").apply {
        isDaemon = true
        start()
    }

    fun configuration(): ErrorgapConfiguration = configuration

    fun configure(configuration: ErrorgapConfiguration) {
        this.configuration = configuration
    }

    @JvmOverloads
    fun notify(
        throwable: Throwable,
        options: NoticeOptions = NoticeOptions(),
        sync: Boolean = false,
    ): DeliveryResult {
        return try {
            configuration.validate()
            val notice = Notice.build(throwable, configuration, options)
            submit(Delivery("notices", notice), sync)
        } catch (caught: Throwable) {
            DeliveryResult(error = caught)
        }
    }

    @JvmOverloads
    fun notifyTransaction(
        transaction: ApmTransaction,
        sync: Boolean = false,
    ): DeliveryResult {
        return try {
            val config = configuration
            config.validate()
            if (!config.apmEnabled ||
                config.apmSampleRate <= 0.0 ||
                (config.apmSampleRate < 1.0 &&
                    ThreadLocalRandom.current().nextDouble() >= config.apmSampleRate)
            ) {
                DeliveryResult(status = 204)
            } else {
                submit(Delivery("transactions", transaction.toMap(config)), sync)
            }
        } catch (caught: Throwable) {
            DeliveryResult(error = caught)
        }
    }

    @JvmOverloads
    fun notifyLog(
        message: String,
        level: String = "info",
        source: String? = null,
        sync: Boolean = false,
    ): DeliveryResult {
        return try {
            val config = configuration
            config.validate()
            val normalizedLevel = normalizeLogLevel(level)
            if (!config.logsEnabled ||
                logLevelRank(normalizedLevel) < logLevelRank(normalizeLogLevel(config.minimumLogLevel))
            ) {
                DeliveryResult(status = 204)
            } else {
                val payload = linkedMapOf<String, Any?>(
                    "message" to message,
                    "level" to normalizedLevel,
                    "environment" to config.environment,
                    "occurred_at" to isoTimestamp(),
                )
                source?.takeIf { it.isNotBlank() }?.let { payload["source"] = it }
                submit(Delivery("logs", payload), sync)
            }
        } catch (caught: Throwable) {
            DeliveryResult(error = caught)
        }
    }

    fun <T> trackJob(
        jobClass: String,
        queue: String = "default",
        operation: (SpanCollector) -> T,
    ): T {
        val startedAt = isoTimestamp()
        val started = System.nanoTime()
        val collector = SpanCollector()
        var failed = false
        try {
            return operation(collector)
        } catch (throwable: Throwable) {
            failed = true
            notify(
                throwable,
                NoticeOptions(
                    context = mapOf(
                        "source" to "errorgap-android job",
                        "component" to "android.job",
                        "action" to jobClass,
                    ),
                    environment = mapOf("queue" to queue),
                ),
            )
            throw throwable
        } finally {
            notifyTransaction(
                ApmTransaction(
                    kind = "job",
                    statusCode = if (failed) 500 else 200,
                    durationMs = (System.nanoTime() - started) / 1_000_000.0,
                    occurredAt = startedAt,
                    spans = collector.snapshot(),
                    jobClass = jobClass,
                    queue = queue,
                ),
            )
        }
    }

    private fun submit(delivery: Delivery, sync: Boolean): DeliveryResult {
        if (sync || !configuration.async) return deliver(delivery)
        // Count at enqueue time so flush cannot observe an empty queue in the
        // handoff between worker poll and delivery.
        inFlight.incrementAndGet()
        if (!queue.offer(delivery)) {
            inFlight.decrementAndGet()
            return DeliveryResult(error = IllegalStateException("queue full"))
        }
        return DeliveryResult(status = 202, queued = true)
    }

    @Throws(InterruptedException::class)
    fun flush(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
    }

    @Throws(InterruptedException::class)
    fun shutdown(timeoutMs: Long) {
        flush(timeoutMs)
        running = false
        worker.interrupt()
        worker.join(timeoutMs)
    }

    private fun loop() {
        while (running) {
            val notice = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (notice != null) {
                try {
                    deliver(notice)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
    }

    private fun deliver(delivery: Delivery): DeliveryResult {
        val body = Json.encode(delivery.payload).toByteArray()
        val url = URL(resourceUrl(delivery.resource))
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = configuration.timeoutMs
            connection.readTimeout = configuration.timeoutMs
            connection.setRequestProperty("content-type", "application/json")
            connection.setRequestProperty("user-agent", "errorgap-android/${Version.CURRENT}")
            configuration.apiKey?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("x-errorgap-project-key", it)
            }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val responseBody = (if (status >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""
            DeliveryResult(status = status, body = responseBody)
        } catch (caught: Throwable) {
            DeliveryResult(error = caught)
        } finally {
            connection.disconnect()
        }
    }

    private fun resourceUrl(resource: String): String {
        val base = configuration.endpoint.trimEnd('/')
        return "$base/api/projects/${configuration.projectSlug}/$resource"
    }

    private fun normalizeLogLevel(level: String): String = when (level.trim().lowercase()) {
        "warning", "warn" -> "warn"
        "err", "severe" -> "error"
        "fine", "finer", "finest" -> "debug"
        "debug", "info", "error", "fatal", "trace" -> level.trim().lowercase()
        else -> "info"
    }

    private fun logLevelRank(level: String): Int = when (level) {
        "trace" -> 0
        "debug" -> 10
        "info" -> 20
        "warn" -> 30
        "error" -> 40
        "fatal" -> 50
        else -> 20
    }
}
