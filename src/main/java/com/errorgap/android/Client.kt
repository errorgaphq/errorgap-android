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

/** Not sent: the server asked for a pause (`Retry-After`) that has not passed yet. */
class RateLimitedException : RuntimeException("rate limited by the server, not sent")

private const val MAX_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 30_000L
private const val BACKLOG_SIZE = 100
private const val DEFAULT_RETRY_AFTER_S = 60L
private const val MAX_RETRY_AFTER_S = 300L

class ErrorgapClient(
    @Volatile private var configuration: ErrorgapConfiguration,
) {
    private data class Delivery(
        val resource: String,
        val payload: Map<String, Any?>,
        val attempt: Int = 1,
        /** An already encoded body (a stored crash), sent as it is. */
        val body: ByteArray? = null,
        /** Told the result once the server has answered (session replay links the error to its group). */
        val onDelivered: ((DeliveryResult) -> Unit)? = null,
    )

    /** Sending is paused until this time after a 429 or 503 (`Retry-After`). */
    @Volatile private var pausedUntil = 0L
    private val retryTimer by lazy { java.util.Timer("errorgap-retry", true) }
    private val backlog = AtomicInteger(0)

    /**
     * Session replay records each reported error, linked to its group: told the
     * error's label, it returns what to call with the group id.
     */
    @Volatile internal var errorObserver: ((String) -> (String?) -> Unit)? = null

    private val queue: LinkedBlockingQueue<Delivery> =
        LinkedBlockingQueue(configuration.queueSize)
    private val inFlight = AtomicInteger(0)
    @Volatile private var running = true
    private val worker: Thread = Thread({ loop() }, "errorgap-delivery").apply {
        isDaemon = true
        start()
    }

    /** What errorgap wants this app to send (the project's remote settings). */
    val remote = RemoteSettings { configuration }

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
            if (configuration.ignoredEnvironment()) return DeliveryResult(status = 202, body = "ignored environment")
            if (!remote.enabled(RemoteSettings.Kind.ERRORS)) {
                return DeliveryResult(status = 202, body = "errors are switched off for this project")
            }
            val notice = prepare(throwable, options)
                ?: return DeliveryResult(status = 202, body = "dropped by beforeNotify")
            val observed = errorObserver?.let { observer ->
                @Suppress("UNCHECKED_CAST")
                val first = (notice["errors"] as? List<Map<String, Any?>>)?.firstOrNull()
                runCatching { observer("${first?.get("type") ?: "Error"}: ${first?.get("message") ?: throwable.message}") }
                    .getOrNull()
            }
            submit(Delivery("notices", notice) { result -> observed?.invoke(groupIdOf(result)) }, sync)
        } catch (caught: Throwable) {
            DeliveryResult(error = caught)
        }
    }

    /**
     * The notice for [throwable] after `beforeNotify`, or null when the hook
     * dropped it. A hook that throws is skipped: a bug in a filter must not
     * lose the error it was filtering.
     */
    internal fun prepare(throwable: Throwable, options: NoticeOptions): Map<String, Any?>? {
        val notice = Notice.build(throwable, configuration, withTransaction(options))
        val hook = configuration.beforeNotify ?: return notice
        return try {
            hook(notice)
        } catch (_: Throwable) {
            notice
        }
    }

    /**
     * Report a crash from the uncaught-exception handler. The notice is
     * written to `crashDirectory` first, then sent from another thread (the
     * crashing thread may be the main thread, which must not touch the
     * network) and the crashing thread waits a moment for it. If the app
     * dies first, the next launch sends it.
     */
    fun notifyCrash(throwable: Throwable, options: NoticeOptions = NoticeOptions(), waitMs: Long = 2_000): DeliveryResult {
        return try {
            configuration.validate()
            if (configuration.ignoredEnvironment()) return DeliveryResult(status = 202, body = "ignored environment")
            val notice = prepare(throwable, options)
                ?: return DeliveryResult(status = 202, body = "dropped by beforeNotify")
            val body = Json.encode(notice)
            val store = configuration.crashDirectory?.let { CrashStore(it) }
            val file = store?.write(body)

            var result = DeliveryResult(queued = true, status = 202)
            val sender = Thread({
                val sent = send(Delivery("notices", notice, body = body.toByteArray()))
                if (file != null && delivered(sent)) store.remove(file)
                result = sent
            }, "errorgap-crash")
            sender.isDaemon = true
            sender.start()
            sender.join(waitMs)
            result
        } catch (caught: Throwable) {
            DeliveryResult(error = caught)
        }
    }

    /** Send the crashes the previous launch could not deliver. Returns how many were sent. */
    fun sendStoredCrashes(): Int {
        val store = configuration.crashDirectory?.let { CrashStore(it) } ?: return 0
        var sent = 0
        for (file in store.pending()) {
            val body = runCatching { file.readBytes() }.getOrNull()
            if (body == null || body.isEmpty()) {
                store.remove(file)
                continue
            }
            val result = send(Delivery("notices", emptyMap(), body = body))
            if (!delivered(result)) break
            store.remove(file)
            sent++
        }
        return sent
    }

    /** The server has it, or refused it for good: either way do not keep it. */
    private fun delivered(result: DeliveryResult): Boolean {
        val status = result.status ?: return false
        return status < 500 && status != 429
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
                if (!remote.enabled(RemoteSettings.Kind.APM)) return DeliveryResult(status = 204)
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
                if (!remote.enabled(RemoteSettings.Kind.LOGS)) return DeliveryResult(status = 204)
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
        val transactionId = java.util.UUID.randomUUID().toString()
        val startedAt = isoTimestamp()
        val started = System.nanoTime()
        val collector = SpanCollector()
        var failed = false
        try {
            return ErrorgapTransactionContext.run(transactionId) { operation(collector) }
        } catch (throwable: Throwable) {
            failed = true
            notify(
                throwable,
                NoticeOptions(
                    context = mapOf(
                        "source" to "errorgap-android job",
                        "component" to "android.job",
                        "action" to jobClass,
                        "transaction_id" to transactionId,
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
                    id = transactionId,
                ),
            )
        }
    }

    private fun submit(delivery: Delivery, sync: Boolean): DeliveryResult {
        if (configuration.ignoredEnvironment()) return DeliveryResult(status = 204)
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
        remote.stop()
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
        val result = send(delivery)
        delivery.onDelivered?.let { runCatching { it(result) } }
        if (retryable(result)) retryLater(delivery)
        return result
    }

    /**
     * Network failures and 5xx responses are worth another try. A 4xx is
     * not: the server understood and refused, and after a 429 the payload is
     * dropped so a limited project is not sent the same burst again.
     */
    internal fun retryable(result: DeliveryResult): Boolean {
        if (result.error is RateLimitedException) return false
        if (result.error != null) return true
        return (result.status ?: 0) >= 500
    }

    private fun retryLater(delivery: Delivery) {
        if (delivery.attempt >= MAX_ATTEMPTS || backlog.get() >= BACKLOG_SIZE || !running) return
        backlog.incrementAndGet()
        val again = delivery.copy(attempt = delivery.attempt + 1, onDelivered = null)
        runCatching {
            retryTimer.schedule(object : java.util.TimerTask() {
                override fun run() {
                    backlog.decrementAndGet()
                    if (!running) return
                    inFlight.incrementAndGet()
                    if (!queue.offer(again)) inFlight.decrementAndGet()
                }
            }, RETRY_DELAY_MS * delivery.attempt)
        }.onFailure { backlog.decrementAndGet() }
    }

    fun paused(): Boolean = System.currentTimeMillis() < pausedUntil

    private fun pauseFor(header: String?) {
        val seconds = (header?.trim()?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_S).coerceIn(1, MAX_RETRY_AFTER_S)
        pausedUntil = System.currentTimeMillis() + seconds * 1000
    }

    private fun send(delivery: Delivery): DeliveryResult {
        if (paused()) return DeliveryResult(error = RateLimitedException())
        val body = delivery.body ?: Json.encode(delivery.payload).toByteArray()
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
            if (status == 429 || status == 503) pauseFor(connection.getHeaderField("Retry-After"))
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

/**
 * The transaction this error was raised in ([ErrorgapTransactionContext]),
 * unless the caller set one, so errorgap links the two.
 */
private fun withTransaction(options: NoticeOptions): NoticeOptions {
    val id = ErrorgapTransactionContext.current() ?: return options
    if (options.context?.containsKey("transaction_id") == true) return options
    return options.copy(context = (options.context ?: emptyMap()) + ("transaction_id" to id))
}
