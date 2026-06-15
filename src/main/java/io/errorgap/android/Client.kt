package io.errorgap.android

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
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
    private val queue: LinkedBlockingQueue<Map<String, Any?>> =
        LinkedBlockingQueue(configuration.queueSize)
    private val inFlight = AtomicInteger(0)
    private val worker: Thread = Thread({ loop() }, "errorgap-delivery").apply {
        isDaemon = true
        start()
    }
    @Volatile private var running = true

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
            if (sync || !configuration.async) {
                return deliver(notice)
            }
            if (!queue.offer(notice)) {
                return DeliveryResult(error = IllegalStateException("queue full"))
            }
            DeliveryResult(status = 202, queued = true)
        } catch (caught: Throwable) {
            DeliveryResult(error = caught)
        }
    }

    @Throws(InterruptedException::class)
    fun flush(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while ((!queue.isEmpty() || inFlight.get() > 0) && System.nanoTime() < deadline) {
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
                inFlight.incrementAndGet()
                try {
                    deliver(notice)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
    }

    internal fun deliver(notice: Map<String, Any?>): DeliveryResult {
        val body = Json.encode(notice).toByteArray()
        val url = URL(noticesUrl())
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

    private fun noticesUrl(): String {
        val base = configuration.endpoint.trimEnd('/')
        return "$base/api/projects/${configuration.projectSlug}/notices"
    }
}
