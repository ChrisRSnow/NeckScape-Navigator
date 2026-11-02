package com.crsnow.neckscape.bridge

import com.crsnow.neckscape.song.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Where the platform keeps the bridge's settings (token, LAN mode). */
interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

data class BridgeStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val token: String = "",
    val lan: Boolean = false,
    /** Shown on screen in LAN mode; a client swaps it for the token once. */
    val pairingCode: String? = null,
    val error: String? = null,
    /** "load_progression — Verse: 16 bars…", for the status line. */
    val lastCall: String? = null,
)

/**
 * The app's local API (docs/ai-integration-spec.md): JSON over HTTP on
 * 127.0.0.1, bearer-token protected. One implementation for every platform,
 * on plain sockets so it runs on Android too.
 *
 *   GET  /v1/health           liveness, no token needed, reveals nothing
 *   POST /v1/tools/<name>     the tool's arguments as a JSON object
 *   POST /v1/pair             LAN mode only: {"code": "123456"} → the token
 *
 * Requests from browsers (an Origin header, or a Host that isn't this
 * machine) are refused, so web pages can't drive the app.
 */
class BridgeServer(
    private val store: SettingsStore,
    /** LAN mode is offered on Android only (phone over Wi-Fi). */
    val lanCapable: Boolean,
    private val basePort: Int = DEFAULT_PORT,
    private val handler: (tool: String, args: Map<String, Any?>) -> Map<String, Any?>,
) {
    private val random = SecureRandom()
    private val _status = MutableStateFlow(BridgeStatus(token = loadToken(), lan = lanCapable && store.get(KEY_LAN) == "true"))
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    @Volatile private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var failedPairings = 0

    private fun loadToken(): String = store.get(KEY_TOKEN)?.takeIf { it.length >= 32 } ?: newToken().also { store.put(KEY_TOKEN, it) }

    private fun newToken(): String = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun newPairingCode() = (random.nextInt(900_000) + 100_000).toString()

    fun regenerateToken() {
        val t = newToken()
        store.put(KEY_TOKEN, t)
        _status.update { it.copy(token = t) }
    }

    fun setLan(on: Boolean) {
        if (!lanCapable) return
        store.put(KEY_LAN, on.toString())
        _status.update { it.copy(lan = on, pairingCode = if (on) newPairingCode() else null) }
        if (server != null) { stop(); start() }
    }

    /** Binds the first free port from [basePort] (so two desktop instances can run). */
    @Synchronized
    fun start() {
        if (server != null) return
        val lan = _status.value.lan
        val address = if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress()
        var bound: ServerSocket? = null
        var lastError: Exception? = null
        for (port in basePort until basePort + PORT_SPAN) {
            try {
                // Reuse lets us rebind past TIME_WAIT leftovers on Linux/macOS; on Windows the same flag would let
                // another program share the port, so it stays off there.
                bound = ServerSocket().apply { reuseAddress = !WINDOWS; bind(InetSocketAddress(address, port), 16) }
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (bound == null) {
            _status.update { it.copy(running = false, error = "No free port in $basePort–${basePort + PORT_SPAN - 1}: ${lastError?.message}") }
            return
        }
        server = bound
        pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "neckscape-bridge").apply { isDaemon = true } }
        _status.update { it.copy(running = true, port = bound.localPort, error = null, pairingCode = if (lan) it.pairingCode ?: newPairingCode() else null) }
        Thread({ acceptLoop(bound) }, "neckscape-bridge-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
        _status.update { it.copy(running = false) }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            val p = pool ?: break
            runCatching { p.execute { handle(client) } }.onFailure { client.close() }
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun handle(client: Socket) = client.use { s ->
        s.soTimeout = 15_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val req = try {
            read(input)
        } catch (e: TooLarge) {
            return@use respond(out, 413, error("INVALID_SCHEMA", "Payload over ${MAX_BODY / 1024 / 1024} MB"))
        } catch (_: Exception) {
            return@use respond(out, 400, error("INVALID_SCHEMA", "Malformed HTTP request"))
        }
        val loopback = s.inetAddress.isLoopbackAddress
        if (req.headers.containsKey("origin")) return@use respond(out, 403, error("AUTH_FAILED", "Browser requests aren't accepted"))
        val host = req.headers["host"]?.substringBeforeLast(':')?.trim('[', ']')?.lowercase()
        if (loopback && host != null && host !in setOf("127.0.0.1", "localhost", "::1")) {
            return@use respond(out, 403, error("AUTH_FAILED", "Unexpected Host header"))
        }
        when {
            req.method == "GET" && req.path == "/v1/health" ->
                respond(out, 200, mapOf("ok" to true, "app" to com.crsnow.neckscape.ui.APP_NAME, "schema_version" to com.crsnow.neckscape.song.SCHEMA_VERSION))
            req.method == "POST" && req.path == "/v1/pair" -> pair(out, req)
            req.method == "POST" && req.path.startsWith("/v1/tools/") -> {
                if (!authorised(req)) return@use respond(out, 401, error("AUTH_FAILED", "Missing or wrong token (Settings → AI bridge shows it)"))
                val tool = req.path.removePrefix("/v1/tools/")
                val args = try {
                    if (req.body.isEmpty()) emptyMap() else Json.parse(String(req.body, Charsets.UTF_8)) as? Map<String, Any?>
                        ?: return@use respond(out, 400, error("INVALID_SCHEMA", "The body must be a JSON object"))
                } catch (e: Json.ParseError) {
                    return@use respond(out, 400, error("INVALID_SCHEMA", "Bad JSON: ${e.message}"))
                }
                val result = handler(tool, args)
                _status.update { it.copy(lastCall = "$tool — ${result["rendered_summary"] ?: (result["error"] as? Map<*, *>)?.get("detail") ?: ""}".take(160)) }
                respond(out, 200, result)
            }
            else -> respond(out, 404, error("INVALID_SCHEMA", "No such endpoint ${req.method} ${req.path}"))
        }
    }

    private fun authorised(req: Request): Boolean {
        val given = req.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), _status.value.token.toByteArray())
    }

    private fun pair(out: OutputStream, req: Request) {
        val st = _status.value
        val code = st.pairingCode
        if (!st.lan || code == null) return respond(out, 404, error("AUTH_FAILED", "Pairing is only available in LAN mode"))
        val given = runCatching { (Json.parse(String(req.body, Charsets.UTF_8)) as Map<*, *>)["code"]?.toString() }.getOrNull()
        if (given != null && MessageDigest.isEqual(given.toByteArray(), code.toByteArray())) {
            failedPairings = 0
            _status.update { it.copy(pairingCode = newPairingCode()) } // single use
            respond(out, 200, mapOf("ok" to true, "token" to st.token))
        } else {
            // A fresh code after a few wrong guesses keeps brute-forcing hopeless.
            if (++failedPairings >= 5) { failedPairings = 0; _status.update { it.copy(pairingCode = newPairingCode()) } }
            respond(out, 401, error("AUTH_FAILED", "Wrong pairing code"))
        }
    }

    private class TooLarge : Exception()

    private fun read(input: InputStream): Request {
        var lines = 0
        val headers = HashMap<String, String>()
        var requestLine: String? = null
        while (true) {
            val line = readLine(input) ?: throw IllegalStateException("EOF")
            if (line.isEmpty()) break
            if (++lines > 100) throw IllegalStateException("Too many headers")
            if (requestLine == null) requestLine = line
            else line.indexOf(':').takeIf { it > 0 }?.let { headers[line.substring(0, it).trim().lowercase()] = line.substring(it + 1).trim() }
        }
        val parts = requestLine!!.split(' ')
        if (parts.size < 2) throw IllegalStateException("Bad request line")
        val length = headers["content-length"]?.toLongOrNull() ?: 0
        if (length > MAX_BODY) throw TooLarge()
        if (headers["transfer-encoding"] != null) throw IllegalStateException("Chunked bodies aren't supported")
        val body = ByteArray(length.toInt())
        var got = 0
        while (got < body.size) {
            val n = input.read(body, got, body.size - got)
            if (n < 0) throw IllegalStateException("Body ended early")
            got += n
        }
        return Request(parts[0], parts[1].substringBefore('?'), headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IllegalStateException("Header line too long")
            sb.append(c.toChar())
        }
    }

    private fun error(code: String, detail: String) = mapOf("ok" to false, "error" to mapOf("code" to code, "detail" to detail), "warnings" to emptyList<Any>())

    private fun respond(out: OutputStream, status: Int, body: Map<String, Any?>) {
        val bytes = Json.write(body).toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val DEFAULT_PORT = 47621
        private val WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows")
        const val PORT_SPAN = 10
        /** Biggest request body: an 8 MB file, base64-encoded, with room to spare. */
        const val MAX_BODY = 12L * 1024 * 1024
        private const val KEY_TOKEN = "bridge.token"
        private const val KEY_LAN = "bridge.lan"
    }
}
