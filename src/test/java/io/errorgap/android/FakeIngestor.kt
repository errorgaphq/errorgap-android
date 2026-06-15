package io.errorgap.android

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

data class CapturedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

class FakeIngestor(private val status: Int = 201) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val captured = ConcurrentLinkedQueue<CapturedRequest>()

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = exchange.requestHeaders.entries
                .associate { (k, v) -> k.lowercase() to v.first() }
            captured.add(
                CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    headers = headers,
                    body = body,
                )
            )
            val resp = """{"group_id":"g_1"}""".toByteArray()
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.sendResponseHeaders(status, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.start()
    }

    val endpoint: String
        get() = "http://127.0.0.1:${server.address.port}"

    fun requests(): List<CapturedRequest> = captured.toList()

    override fun close() {
        server.stop(0)
    }

    private fun java.io.InputStream.readBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        copyTo(out)
        return out.toByteArray()
    }
}
