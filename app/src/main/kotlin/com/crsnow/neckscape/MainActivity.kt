package com.crsnow.neckscape

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModelProvider
import com.crsnow.neckscape.bridge.BridgeServer
import com.crsnow.neckscape.bridge.SettingsStore
import com.crsnow.neckscape.song.Json
import com.crsnow.neckscape.ui.AiTools
import com.crsnow.neckscape.ui.FileOpener
import com.crsnow.neckscape.ui.FileSaver
import com.crsnow.neckscape.ui.LocalBridge
import com.crsnow.neckscape.ui.LocalFileOpener
import com.crsnow.neckscape.ui.LocalFileSaver
import com.crsnow.neckscape.ui.LocalNotePlayer
import com.crsnow.neckscape.ui.NeckScapeScreen
import com.crsnow.neckscape.ui.NeckScapeTheme
import com.crsnow.neckscape.ui.NeckScapeViewModel
import com.crsnow.neckscape.ui.NotePlayer
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    private val player = NotePlayer(AndroidAudioOutput())
    private lateinit var vm: NeckScapeViewModel

    // Saved files go wherever the user picks in the system document picker (Downloads, Drive …).
    private var pending: ByteArray? = null
    private fun saveTo(uri: Uri?) {
        val bytes = pending
        pending = null
        if (uri == null || bytes == null) return
        val ok = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
        Toast.makeText(this, if (ok) "Saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
    }
    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), ::saveTo)
    private val createMidi = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/midi"), ::saveTo)
    private val saver = FileSaver { name, mime, bytes ->
        pending = bytes
        if (mime == "audio/midi") createMidi.launch(name) else createPdf.launch(name)
    }

    private var onOpened: ((String, ByteArray) -> Unit)? = null
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = onOpened
        onOpened = null
        if (uri == null || callback == null) return@registerForActivityResult
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { readAtMost(it, AiTools.MAX_IMPORT_BYTES + 1) }
        }.getOrNull()
        when {
            bytes == null -> Toast.makeText(this, "Couldn't read $name", Toast.LENGTH_SHORT).show()
            bytes.size > AiTools.MAX_IMPORT_BYTES -> Toast.makeText(this, "$name is too big", Toast.LENGTH_SHORT).show()
            else -> callback(name, bytes)
        }
    }
    private val opener = FileOpener { onFile ->
        onOpened = onFile
        openDocument.launch(arrayOf("*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = ViewModelProvider(this)[NeckScapeViewModel::class.java]
        vm.player = player
        tools = AiTools(vm, "android")
        // One bridge per process: it survives rotation, and always talks to the current screen's model.
        val b = bridge ?: BridgeServer(PrefsSettings(applicationContext), lanCapable = true) { tool, args ->
            tools?.call(tool, args) ?: mapOf("ok" to false, "error" to mapOf("code" to "NO_TARGET", "detail" to "App is closing"))
        }.also { bridge = it }
        b.start()
        setContent {
            NeckScapeTheme(fontFamily = Inter, logo = painterResource(R.mipmap.ic_launcher_foreground)) {
                CompositionLocalProvider(
                    LocalNotePlayer provides player,
                    LocalFileSaver provides saver,
                    LocalFileOpener provides opener,
                    LocalBridge provides b,
                ) {
                    NeckScapeScreen(vm)
                }
            }
        }
        // Only a fresh launch: after a rotation the same intent comes back and would load twice.
        if (savedInstanceState == null) handleLoad(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLoad(intent)
    }

    /**
     * The fallback for small pushes without a server (spec: "Android transport"):
     *   adb shell am start -n <package>/com.crsnow.neckscape.MainActivity -a <package>.LOAD --es payload '<json>' --es token '<token>'
     * Other apps can send intents too, so nothing happens without the token.
     */
    private fun handleLoad(intent: Intent?) {
        if (intent?.action != "$packageName.LOAD") return
        val given = intent.getStringExtra("token").orEmpty()
        val token = bridge?.status?.value?.token.orEmpty()
        if (token.isEmpty() || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            Toast.makeText(this, "Ignored a load request with a missing or wrong token", Toast.LENGTH_LONG).show()
            return
        }
        val payload = runCatching { Json.parse(intent.getStringExtra("payload").orEmpty()) as Map<*, *> }.getOrNull()
        val tool = when (payload?.get("type")) {
            "progression" -> "load_progression"
            "tab" -> "load_tab"
            "scale" -> "show_scale"
            else -> null
        }
        @Suppress("UNCHECKED_CAST")
        val result = if (payload == null || tool == null) null
        else tools?.call(tool, if (tool == "show_scale") mapOf("scale" to payload) else payload as Map<String, Any?>)
        val text = when {
            result == null -> "The payload isn't a progression, tab or scale"
            result["ok"] == true -> result["rendered_summary"].toString()
            else -> ((result["error"] as? Map<*, *>)?.get("detail") ?: "Couldn't load it").toString()
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        intent.action = null
    }

    override fun onDestroy() {
        player.release()
        if (isFinishing) {
            tools = null
            bridge?.stop()
            bridge = null
        }
        super.onDestroy()
    }

    private companion object {
        var bridge: BridgeServer? = null
        var tools: AiTools? = null
    }
}

/** Up to [limit] bytes (InputStream.readNBytes needs Android 13). */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Bridge settings (token, LAN mode) in private shared preferences. */
private class PrefsSettings(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
}
