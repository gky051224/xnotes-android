package com.xnotes.ai

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

/** A loopback HTTP fixture. It accepts no external connections and uses no real credentials. */
internal class LocalAiServer : Closeable {
    data class Request(val line: String, val headers: Map<String, String>, val body: JSONObject)
    data class Reply(val status: Int, val body: String, val location: String? = null, val delayMs: Long = 0)
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${socket.localPort}/v1"
    val replies = LinkedBlockingQueue<Reply>()
    val requests = LinkedBlockingQueue<Request>()
    private val worker = thread(name = "local-ai-http-fixture", isDaemon = true) {
        while (!socket.isClosed) {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 10_000
                    val input = client.getInputStream().buffered()
                    val line = readLine(input)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = readLine(input)
                        if (header.isEmpty()) break
                        val split = header.indexOf(':')
                        require(split > 0)
                        headers[header.substring(0, split).lowercase()] = header.substring(split + 1).trim()
                    }
                    val length = headers.getValue("content-length").toInt()
                    require(length in 1..2_000_000)
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(bytes, offset, length - offset)
                        require(count > 0)
                        offset += count
                    }
                    val body = JSONObject(bytes.toString(Charsets.UTF_8))
                    requests.put(Request(line, headers, body))
                    val reply = replies.poll() ?: Reply(200, answer(if (body.optInt("max_tokens") == 64) "OK" else ANSWER))
                    if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
                    val response = reply.body.toByteArray(Charsets.UTF_8)
                    val redirect = reply.location?.let { "Location: $it\r\n" }.orEmpty()
                    client.getOutputStream().use {
                        it.write(("HTTP/1.1 ${reply.status} Fixture\r\nContent-Type: application/json; charset=utf-8\r\n" +
                            "Content-Length: ${response.size}\r\nConnection: close\r\n$redirect\r\n").toByteArray(Charsets.US_ASCII))
                        it.write(response)
                        it.flush()
                    }
                }
            } catch (e: SocketException) {
                // Cancellation may close the client before the delayed fixture response.
            }
        }
    }

    fun nextRequest(): Request = checkNotNull(requests.poll(10, TimeUnit.SECONDS)) { "No HTTP request received" }

    override fun close() {
        socket.close()
        worker.join(2_000)
    }

    private fun readLine(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val byte = input.read()
            require(byte >= 0 && bytes.size < 16_384)
            if (byte == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            bytes.add(byte.toByte())
        }
    }

    companion object {
        const val ANSWER = "HTTP 回答：这是本地测试服务返回的内容。"
        fun answer(text: String) = JSONObject().put("choices", org.json.JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", text)))).toString()
    }
}
