package dev.vq.dashboard

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Tiny HTTP/1.1 server the PC talks to (bearer token auth). Endpoints:
 *  POST /state          {"config":{…}, "colors":{dark palette}}  — also the heartbeat
 *  POST /cmd            {"brightness": 30|"auto", "screen": "on"|"off"|"auto", "oauth_token": "…"}
 *  GET  /status
 *  GET  /photos         list of stored photo names
 *  PUT  /photos/<name>  JPEG body
 *  DELETE /photos/<name>
 */
class HttpServer(private val context: Context, private val port: Int = PORT) {
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        thread(name = "dash-http", isDaemon = true) {
            val ss = try {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
            } catch (e: IOException) {
                return@thread
            }
            server = ss
            while (!ss.isClosed) {
                val socket = try { ss.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { handle(socket) }
            }
        }
    }

    fun stop() {
        server?.close()
        server = null
    }

    private fun handle(socket: Socket) = socket.use { s ->
        try {
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "/" }.substringBefore("?")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length > MAX_BODY) return respond(s, 413, JSONObject().put("error", "too large"))
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            if (headers["authorization"] != "Bearer ${Remote.token}") {
                return respond(s, 401, JSONObject().put("error", "unauthorized"))
            }
            val (code, json) = try {
                route(method, path, body)
            } catch (e: Exception) {
                500 to JSONObject().put("error", e.message ?: e.javaClass.simpleName)
            }
            respond(s, code, json)
        } catch (_: IOException) {
        }
    }

    private fun route(method: String, path: String, body: ByteArray): Pair<Int, JSONObject> {
        val ok = JSONObject().put("ok", true)
        return when {
            method == "POST" && path == "/state" -> {
                Remote.heartbeat()
                val j = JSONObject(String(body))
                j.optJSONObject("config")?.let(Remote::applyConfig)
                j.optJSONObject("colors")?.let(Remote::applyColors)
                j.optJSONArray("notifications")?.let(Remote::applyNotifications)
                Remote.applyExtras(j)
                200 to ok
            }
            method == "POST" && path == "/cmd" -> {
                val j = JSONObject(String(body))
                if (j.optString("pc") == "offline") {
                    Remote.pcGoodbye()
                    return 200 to ok
                }
                Remote.heartbeat()
                if (j.has("brightness")) {
                    Remote.setBrightness(j.opt("brightness").let { if (it is Number) it.toInt() else null })
                }
                if (j.has("screen")) {
                    Remote.setScreen(when (j.optString("screen")) { "on" -> true; "off" -> false; else -> null })
                }
                j.optString("oauth_token").takeIf { it.isNotBlank() }?.let { GoogleHome.signIn(it) }
                200 to Remote.status()
            }
            method == "GET" && path == "/status" -> 200 to Remote.status()
            method == "GET" && path == "/photos" ->
                200 to JSONObject().put("photos", Remote.jsonArrayOf(PhotoStore.names(context)))
            path.startsWith("/photos/") -> {
                val name = path.removePrefix("/photos/")
                require(PhotoStore.validName(name)) { "bad name" }
                when (method) {
                    "PUT" -> PhotoStore.save(context, name, body)
                    "DELETE" -> PhotoStore.delete(context, name)
                    else -> return 405 to JSONObject().put("error", "method")
                }
                200 to ok
            }
            else -> 404 to JSONObject().put("error", "not found")
        }
    }

    private fun respond(s: Socket, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (buf.size() > 8192) throw IOException("line too long")
            buf.write(b)
        }
        return buf.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
    }

    companion object {
        const val PORT = 8765
        private const val MAX_BODY = 20 * 1024 * 1024
    }
}
