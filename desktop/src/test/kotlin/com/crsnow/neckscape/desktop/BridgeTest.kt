package com.crsnow.neckscape.desktop

import com.crsnow.neckscape.bridge.BridgeServer
import com.crsnow.neckscape.bridge.SettingsStore
import com.crsnow.neckscape.song.Json
import com.crsnow.neckscape.song.Progression
import com.crsnow.neckscape.song.Tab
import com.crsnow.neckscape.ui.AiTools
import com.crsnow.neckscape.ui.NeckScapeViewModel
import com.crsnow.neckscape.ui.ViewMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/** The real bridge on a real socket, driving a real view model: what the MCP server talks to. */
class BridgeTest {
    private val store = object : SettingsStore {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }
    private val vm = NeckScapeViewModel()
    private val tools = AiTools(vm, "test")
    private val bridge = BridgeServer(store, lanCapable = false, basePort = 47700, handler = tools::call).also { it.start() }
    private val port get() = bridge.status.value.port
    private val token get() = bridge.status.value.token

    @After fun stop() = bridge.stop()

    private fun request(path: String, body: Any? = null, auth: String? = token, headers: Map<String, String> = emptyMap()): Pair<Int, Map<*, *>> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = if (body == null) "GET" else "POST"
        auth?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(Json.write(body).toByteArray()) }
        }
        val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
        return c.responseCode to Json.parse(stream.readBytes().decode()) as Map<*, *>
    }

    private fun ByteArray.decode() = String(this, Charsets.UTF_8)

    private fun tool(name: String, args: Map<String, Any?> = emptyMap()) = request("/v1/tools/$name", args).second

    private val fixtures = File("../schema/fixtures")

    @Test fun `health needs no token, tools do`() {
        assertEquals(true, request("/v1/health", auth = null).second["ok"])
        val (code, body) = request("/v1/tools/get_state", emptyMap<String, Any>(), auth = "wrong")
        assertEquals(401, code)
        assertEquals("AUTH_FAILED", (body["error"] as Map<*, *>)["code"])
    }

    /** Raw socket: Java's HTTP client won't send Origin, but a browser (or a rebinding attack) does. */
    private fun raw(headers: String): String = java.net.Socket("127.0.0.1", port).use { s ->
        s.getOutputStream().write("POST /v1/tools/get_state HTTP/1.1\r\n${headers}Authorization: Bearer $token\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
        s.getInputStream().bufferedReader().readLine()
    }

    @Test fun `browsers are turned away`() {
        assertEquals("HTTP/1.1 403 Forbidden", raw("Host: 127.0.0.1\r\nOrigin: https://evil.example\r\n"))
        // DNS rebinding: a page on evil.example resolving to 127.0.0.1 still sends its own Host.
        assertEquals("HTTP/1.1 403 Forbidden", raw("Host: evil.example:$port\r\n"))
        assertEquals("HTTP/1.1 200 OK", raw("Host: 127.0.0.1:$port\r\n"))
    }

    @Test fun `token regenerates and the old one stops working`() {
        val old = token
        bridge.regenerateToken()
        assertEquals(401, request("/v1/tools/get_state", emptyMap<String, Any>(), auth = old).first)
        assertEquals(200, request("/v1/tools/get_state", emptyMap<String, Any>()).first)
    }

    @Test fun `the desktop build passes the shared fixtures too`() {
        for (file in fixtures.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".expect.json") }!!) {
            val payload = Json.parse(file.readText()) as Map<*, *>
            val expect = Json.parse(File(file.path.removeSuffix(".json") + ".expect.json").readText()) as Map<*, *>
            val tool = when (payload["type"]) { "tab" -> "load_tab"; "scale" -> "show_scale"; else -> "load_progression" }
            val key = when (tool) { "load_tab" -> "tab"; "show_scale" -> "scale"; else -> "progression" }
            val result = tool(tool, mapOf(key to payload))
            assertEquals(file.name, expect["ok"], result["ok"])
            if (result["ok"] == true) {
                val got = (result["warnings"] as List<*>).map { w -> (w as Map<*, *>).let { "${it["code"]}@${it["path"]}" } }.sorted()
                assertEquals(file.name, (expect["warnings"] as List<*>).map { it.toString() }.sorted(), got)
            } else {
                val err = result["error"] as Map<*, *>
                assertEquals(file.name, expect["error"], err["code"])
                assertEquals(file.name, expect["path"], err["path"])
            }
        }
    }

    @Test fun `house of the rising sun lands, reports itself, and undoes`() {
        val payload = Json.parse(File(fixtures, "good-house-of-the-rising-sun.json").readText())
        val result = tool("load_progression", mapOf("progression" to payload))
        assertEquals("Verse: 16 bars, 5 chords (Am C D F E), open voicings, A minor, 6/8", result["rendered_summary"])
        val state = tool("get_state")
        assertEquals("progression", state["type"])
        assertEquals("Am", state["current_chord"])
        assertEquals(16.0, state["bar_count"])
        assertTrue(vm.state.value.song is Progression)
        tool("undo")
        assertEquals(ViewMode.Scale, vm.state.value.viewMode)
    }

    @Test fun `files import over the bridge, landing on the part asked for`() {
        val gp = File("../app/src/test/resources/gp/fixture.gp5").readBytes()
        val result = tool("import_file", mapOf("content_base64" to Base64.getEncoder().encodeToString(gp), "file_name" to "fixture.gp5", "part" to "solo"))
        assertEquals(true, result["ok"])
        assertEquals(2.0, result["start_bar"])
        assertTrue(vm.state.value.song is Tab)
        val green = File("../demo/library/Traditional - Greensleeves.musicxml").readText()
        val r2 = tool("import_file", mapOf("content" to green, "file_name" to "Greensleeves.musicxml", "part" to "refrain"))
        assertEquals(true, r2["ok"])
        assertEquals(17.0, r2["start_bar"])
        assertEquals("Greensleeves", (vm.state.value.song as Tab).title)
    }

    @Test fun `chords, scales, highlights and tuning`() {
        assertEquals(true, tool("show_chord", mapOf("chord" to "E7#9", "voicing_style" to "position_7"))["ok"])
        assertEquals("E7♯9", tool("get_state")["current_chord"])
        val scale = tool("show_scale", mapOf("scale" to "minor_pentatonic", "root" to "A", "fret_range" to listOf(5, 8)))
        assertEquals("A Minor Pentatonic, frets 5–8", scale["rendered_summary"])
        assertEquals(true, tool("highlight_notes", mapOf("notes" to listOf("C"), "label" to "every C"))["ok"])
        val t = tool("set_tuning", mapOf("tuning" to "DADGAD", "capo" to 2))
        assertEquals("DADGAD (D A D G A D), capo 2", t["rendered_summary"])
        val bad = tool("show_chord", mapOf("chord" to "Hm7"))
        assertEquals("INVALID_SCHEMA", (bad["error"] as Map<*, *>)["code"])
    }
}
