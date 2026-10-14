package com.errorgap.android

import java.net.HttpURLConnection
import java.net.URL

/**
 * The project's remote settings: what errorgap wants this app to send.
 * Someone can turn errors, performance data or logs off for a project in
 * errorgap (Project settings › What SDKs send) and installed apps follow
 * within a poll interval, with no new release.
 *
 * Read from `GET /api/projects/:slug/config` on a daemon thread. Until the
 * first answer, and whenever errorgap cannot be reached, everything stays on.
 */
class RemoteSettings internal constructor(private val configuration: () -> ErrorgapConfiguration) {
    enum class Kind(internal val field: String) {
        ERRORS("errors_enabled"), APM("apm_enabled"), LOGS("logs_enabled")
    }

    @Volatile private var off: Set<Kind> = emptySet()
    @Volatile private var intervalSeconds = DEFAULT_INTERVAL_SECONDS
    @Volatile private var poller: Thread? = null
    @Volatile private var stopped = false

    /** False only when errorgap has said to stop sending this kind of data. */
    fun enabled(kind: Kind): Boolean {
        if (!configuration().remoteSettings) return true
        ensurePolling()
        return kind !in off
    }

    /**
     * Fetch now, on this thread. Returns whether the settings could be read.
     * One read at a time, so an older answer can never overwrite a newer one.
     */
    fun refresh(): Boolean = synchronized(refreshLock) { read() }

    private val refreshLock = Any()

    private fun read(): Boolean {
        val config = configuration()
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("${config.endpoint.trimEnd('/')}/api/projects/${config.projectSlug}/config")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = config.timeoutMs
                readTimeout = config.timeoutMs
                setRequestProperty("user-agent", "errorgap-android/${Version.CURRENT}")
                config.apiKey?.takeIf { it.isNotBlank() }?.let { setRequestProperty("x-errorgap-project-key", it) }
            }
            if (connection.responseCode != 200) return false
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (!body.trimStart().startsWith("{")) return false
            // An absent field means on.
            off = Kind.values().filter { Regex("\"${it.field}\"\\s*:\\s*false").containsMatchIn(body) }.toSet()
            val seconds = Regex("\"poll_interval_seconds\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            intervalSeconds = if (seconds >= MIN_INTERVAL_SECONDS) seconds else DEFAULT_INTERVAL_SECONDS
            true
        } catch (_: Throwable) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    internal fun stop() {
        stopped = true
        poller?.interrupt()
    }

    @Synchronized
    private fun ensurePolling() {
        if (poller != null || stopped) return
        poller = Thread({
            while (!stopped) {
                refresh()
                try {
                    Thread.sleep(intervalSeconds * 1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "errorgap-settings").apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val DEFAULT_INTERVAL_SECONDS = 600L
        const val MIN_INTERVAL_SECONDS = 60L
    }
}
