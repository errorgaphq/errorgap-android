package com.errorgap.android

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/** A fake Errorgap answering the replay settings, session batches and notices. */
private class ReplayServer(
    private val enabled: Boolean = true,
    private val sessionStatus: Int = 201,
) : AutoCloseable {
    data class Request(val path: String, val query: String?, val headers: Map<String, String>, val body: String)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = ConcurrentLinkedQueue<Request>()

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
            val path = exchange.requestURI.path
            requests.add(Request(path, exchange.requestURI.query, headers, body))
            val (status, payload) = when {
                path.endsWith("/sessions/config") -> 200 to """{"enabled":$enabled,"sample_rate":1,"capture_snaps":false}"""
                path.endsWith("/sessions") -> sessionStatus to ""
                path.endsWith("/notices") -> 201 to """{"group_id":"group-5"}"""
                else -> 503 to "{}"
            }
            val bytes = payload.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
    }

    val endpoint: String
        get() = "http://127.0.0.1:${server.address.port}"

    fun batches(): List<String> = requests.filter { it.path.endsWith("/sessions") }.map { it.body }

    override fun close() = server.stop(0)
}

class ReplayTests {
    private var server: ReplayServer? = null

    @AfterEach
    fun tearDown() {
        Errorgap.shutdown()
        server?.close()
    }

    private fun init(server: ReplayServer) {
        this.server = server
        Errorgap.init(
            ErrorgapConfiguration(
                endpoint = server.endpoint,
                projectSlug = "app",
                apiKey = "egp_key",
                release = "2.3.0",
                async = false,
            ),
            captureGlobals = false,
            replay = true,
            replayOptions = ReplayOptions(appName = "Acme", os = "Android 14", flushIntervalMs = 3_600_000),
        )
    }

    private fun waitUntil(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && !check()) Thread.sleep(10)
    }

    @Test
    fun recordsNothingWhileReplayIsOff() {
        val server = ReplayServer(enabled = false)
        init(server)
        waitUntil { server.requests.isNotEmpty() }
        Thread.sleep(100)
        Errorgap.trackScreen("Checkout")
        Errorgap.appPaused()
        Thread.sleep(100)
        assertEquals(listOf("/api/projects/app/sessions/config"), server.requests.map { it.path })
        assertEquals("api_key=egp_key", server.requests.first().query)
    }

    @Test
    fun recordsScreensFailedCallsAndErrorsLinkedToTheirGroup() {
        val server = ReplayServer()
        init(server)
        waitUntil { server.batches().isNotEmpty() }

        Errorgap.trackScreen("Checkout")
        Errorgap.trackScreen("Checkout")
        Errorgap.recordCall("post", "https://api.acme.example/orders?token=secret", 503)
        Errorgap.recordCall("get", "https://api.acme.example/ok", 200)
        Errorgap.notify(IllegalStateException("token is undefined"), NoticeOptions())
        waitUntil { server.batches().any { it.contains("\"type\":\"error\"") } }

        val all = server.batches().joinToString("\n")
        assertTrue(server.requests.first { it.path.endsWith("/sessions") }.headers["x-errorgap-project-key"] == "egp_key")
        assertTrue(all.contains("\"label\":\"App started\""))
        assertEquals(1, Regex("Opened Checkout").findAll(all).count())
        assertTrue(all.contains("POST https://api.acme.example/orders → 503"), all)
        assertFalse(all.contains("secret"))
        assertFalse(all.contains("/ok"))
        assertTrue(all.contains("\"group_id\":\"group-5\""), all)
        assertTrue(all.contains("\"browser\":\"Acme 2.3.0\""))
        assertTrue(server.batches().last().contains("\"outcome\":\"error\""))
    }

    @Test
    fun aShortPauseKeepsTheSession() {
        val server = ReplayServer()
        init(server)
        waitUntil { server.batches().isNotEmpty() }
        Errorgap.appPaused()
        Errorgap.appResumed()
        Errorgap.notify(RuntimeException("boom"), NoticeOptions())
        waitUntil { server.batches().joinToString().let { it.contains("Returned to the app") && it.contains("\"type\":\"error\"") } }

        val ids = server.batches().map { Regex(""""id":"([^"]+)"""").find(it)!!.groupValues[1] }.toSet()
        assertEquals(1, ids.size)
        val all = server.batches().joinToString()
        assertTrue(all.contains("App went to the background"))
        assertTrue(all.contains("Returned to the app"))
    }

    @Test
    fun replayIsOffByDefaultOutsideAndroid() {
        val server = ReplayServer()
        this.server = server
        Errorgap.init(
            ErrorgapConfiguration(endpoint = server.endpoint, projectSlug = "app", apiKey = "k", async = false),
            captureGlobals = false,
        )
        Errorgap.notify(RuntimeException("boom"), NoticeOptions())
        Thread.sleep(100)
        assertEquals(listOf("/api/projects/app/notices"), server.requests.map { it.path })
    }
}
