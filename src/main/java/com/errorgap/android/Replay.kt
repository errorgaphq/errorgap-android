package com.errorgap.android

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * Options for session replay. Sessions are timeline only: no screenshots or
 * view snapshots are taken.
 */
data class ReplayOptions(
    /** Shown with the release as the session's "browser", e.g. `Acme 2.3.0`. */
    val appName: String? = null,
    /** E.g. `Android 14`. Defaults to `Android` plus `android.os.Build.VERSION.RELEASE` when available. */
    val os: String? = null,
    /** `Mobile` or `Tablet`. */
    val device: String = "Mobile",
    /** How often recorded events are sent, in ms. */
    val flushIntervalMs: Long = 10_000,
)

/** A session ends once the app has been in the background this long. */
const val REPLAY_BACKGROUND_TIMEOUT_MS: Long = 30 * 60 * 1000
internal const val REPLAY_MAX_BATCH_EVENTS = 200
private const val MAX_BUFFERED_EVENTS = 1000
/** Before the session's first error, only the most recent events are held. */
internal const val REPLAY_MAX_PENDING_EVENTS = 300
private const val MAX_LABEL = 200

/**
 * Records a session replay timeline: app start, screens, failed API calls,
 * reported errors and backgrounding. Whether a session is recorded is the
 * project's call: its replay settings are read when each session starts, and
 * a sampled share of sessions is recorded while replay is on.
 *
 * Only sessions that hit an error are kept: a session's events are held in
 * memory and nothing is sent until an error is reported; then everything held
 * goes up and the rest of the session follows. A session that ends without an
 * error is never sent.
 */
class ReplayRecorder internal constructor(
    private val configuration: ErrorgapConfiguration,
    private val options: ReplayOptions,
) {
    private enum class State { PENDING, RECORDING, SKIPPED, STOPPED }

    private val lock = Any()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "errorgap-replay").apply { isDaemon = true }
    }
    private var state = State.PENDING
    private var sessionId = ""
    private var startedAt = 0L
    private var hadError = false
    private var events = mutableListOf<MutableMap<String, Any?>>()
    private var lastScreen: String? = null
    private var pausedAt: Long? = null
    private var timer: ScheduledFuture<*>? = null

    val recording: Boolean
        get() = synchronized(lock) { state == State.RECORDING }

    private val base: String
        get() = "${configuration.endpoint.trimEnd('/')}/api/projects/${configuration.projectSlug}"

    internal fun start() = newSession("App started")

    internal fun stop() {
        synchronized(lock) {
            timer?.cancel(false)
            timer = null
            state = State.STOPPED
            events = mutableListOf()
        }
        executor.shutdown()
    }

    /** A screen is now showing. */
    fun screen(name: String) {
        val changed = synchronized(lock) {
            if (name.isEmpty() || name == lastScreen) false else {
                lastScreen = name
                true
            }
        }
        if (changed) push("navigation", "Opened $name")
    }

    /** The app went to the background. */
    fun paused() {
        val pause = synchronized(lock) {
            if (state == State.STOPPED || pausedAt != null) false else {
                pausedAt = System.currentTimeMillis()
                true
            }
        }
        if (!pause) return
        push("exit", "App went to the background")
        executor.execute { flush(final = true) }
    }

    /** The app is in the foreground again. */
    fun resumed() {
        val away = synchronized(lock) {
            val at = pausedAt
            if (state == State.STOPPED || at == null) null else {
                pausedAt = null
                System.currentTimeMillis() - at
            }
        } ?: return
        if (away >= REPLAY_BACKGROUND_TIMEOUT_MS) newSession("App opened") else push("navigation", "Returned to the app")
    }

    /** An error is being reported; call the result with its group id once answered. */
    internal fun errorStarted(label: String): (String?) -> Unit {
        val started = synchronized(lock) {
            if (state != State.PENDING && state != State.RECORDING) null else {
                hadError = true
                offset() to sessionId
            }
        } ?: return {}
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        return { groupId ->
            if (done.compareAndSet(false, true) && synchronized(lock) { sessionId == started.second }) {
                push("error", label, t = started.first, groupId = groupId)
                executor.execute { flush(final = false) }
            }
        }
    }

    /** A call failed or answered 5xx. */
    fun apiError(method: String, url: String, status: Int) {
        // No query string: it carries tokens and ids.
        val shown = try {
            val u = URI(url)
            if (u.scheme != null && u.host != null) URI(u.scheme, null, u.host, u.port, u.path, null, null).toString() else u.path
        } catch (_: Exception) {
            url.substringBefore('?').substringBefore('#')
        }
        push("api_error", "${method.uppercase()} $shown → ${if (status == 0) "failed" else status.toString()}")
    }

    internal fun flush(final: Boolean) {
        var last = final
        while (true) {
            val batch = synchronized(lock) {
                // No error yet: keep holding.
                if (state != State.RECORDING || !hadError) return
                val take = events.take(REPLAY_MAX_BATCH_EVENTS)
                events = events.drop(take.size).toMutableList()
                if (take.isEmpty() && !last) return
                payload(take, last)
            }
            send(batch)
            last = false
            if (synchronized(lock) { events.isEmpty() }) return
        }
    }

    // ── Sessions ─────────────────────────────────────────────────────────────

    private fun newSession(label: String) {
        val id = synchronized(lock) {
            timer?.cancel(false)
            timer = null
            state = State.PENDING
            sessionId = UUID.randomUUID().toString()
            startedAt = System.currentTimeMillis()
            hadError = false
            events = mutableListOf()
            lastScreen = null
            sessionId
        }
        push("page_load", label)
        executor.execute { decide(id) }
    }

    private fun decide(id: String) {
        var record = false
        try {
            val key = configuration.apiKey?.takeIf { it.isNotBlank() }
            val query = if (key == null) "" else "?api_key=${URLEncoder.encode(key, "UTF-8")}"
            val connection = URL("$base/sessions/config$query").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = configuration.timeoutMs
                connection.readTimeout = configuration.timeoutMs
                if (connection.responseCode == 200) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val enabled = Regex(""""enabled"\s*:\s*true""").containsMatchIn(body)
                    val rate = Regex(""""sample_rate"\s*:\s*([0-9.eE+-]+)""").find(body)
                        ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                    record = enabled && ThreadLocalRandom.current().nextDouble() < rate
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            // Settings unreadable (offline, wrong key): not recorded.
        }
        val proceed = synchronized(lock) {
            if (sessionId != id || state != State.PENDING) return
            if (record) {
                state = State.RECORDING
                timer = executor.scheduleAtFixedRate(
                    { flush(final = false) },
                    options.flushIntervalMs,
                    options.flushIntervalMs,
                    TimeUnit.MILLISECONDS,
                )
            } else {
                state = State.SKIPPED
                events = mutableListOf()
            }
            record
        }
        if (proceed) flush(final = false)
    }

    private fun skip() {
        synchronized(lock) {
            timer?.cancel(false)
            timer = null
            state = State.SKIPPED
            events = mutableListOf()
        }
    }

    // ── Events ───────────────────────────────────────────────────────────────

    private fun push(type: String, label: String, t: Int? = null, groupId: String? = null) {
        synchronized(lock) {
            if (state != State.PENDING && state != State.RECORDING) return
            // Before an error, the recent past matters most: drop the oldest.
            if (!hadError && events.size >= REPLAY_MAX_PENDING_EVENTS) events.removeAt(0)
            if (events.size >= MAX_BUFFERED_EVENTS) return
            events.add(
                mutableMapOf<String, Any?>(
                    "t" to (t ?: offset()),
                    "type" to type,
                    "label" to if (label.length > MAX_LABEL) label.take(MAX_LABEL - 1) + "…" else label,
                ).apply { if (groupId != null) put("group_id", groupId) },
            )
        }
    }

    // Called with `lock` held.
    private fun payload(batch: List<Map<String, Any?>>, final: Boolean): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val app = options.appName ?: "App"
        val release = configuration.release.orEmpty()
        return mapOf(
            "session" to mapOf(
                "id" to sessionId,
                "browser" to if (release.isEmpty()) app else "$app $release",
                "os" to (options.os ?: runningOs()),
                "device" to options.device,
                "started_at" to Instant.ofEpochMilli(startedAt).toString(),
                "ended_at" to Instant.ofEpochMilli(now).toString(),
                "duration_sec" to offset(now),
                "outcome" to if (hadError) "error" else if (final) "completed" else "abandoned",
            ),
            "events" to batch,
            "snapshots" to emptyList<Any>(),
        )
    }

    private fun send(batch: Map<String, Any?>) {
        val id = synchronized(lock) { sessionId }
        try {
            val connection = URL("$base/sessions").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = configuration.timeoutMs
                connection.readTimeout = configuration.timeoutMs
                connection.setRequestProperty("content-type", "application/json")
                configuration.apiKey?.takeIf { it.isNotBlank() }?.let {
                    connection.setRequestProperty("x-errorgap-project-key", it)
                }
                connection.outputStream.use { it.write(Json.encode(batch).toByteArray()) }
                val status = connection.responseCode
                // Replay switched off, or the session belongs elsewhere: stop this session.
                if (status in setOf(204, 401, 404, 409) && synchronized(lock) { sessionId == id && state == State.RECORDING }) {
                    skip()
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            // Replay is best effort.
        }
    }

    // Called with `lock` held.
    private fun offset(now: Long = System.currentTimeMillis()): Int =
        maxOf(0L, Math.round((now - startedAt) / 1000.0)).toInt()

    private fun runningOs(): String = try {
        val release = Class.forName("android.os.Build\$VERSION").getField("RELEASE").get(null) as? String
        if (release.isNullOrBlank()) "Android" else "Android $release"
    } catch (_: Throwable) {
        System.getProperty("os.name") ?: ""
    }
}

/** The error group named in a notice response, for session replay. */
internal fun groupIdOf(result: DeliveryResult): String? =
    result.body?.let { Regex(""""group_id"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
