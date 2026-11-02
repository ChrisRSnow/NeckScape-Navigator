package com.crsnow.neckscape.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File
import javax.imageio.ImageIO
import com.crsnow.neckscape.bridge.BridgeServer
import com.crsnow.neckscape.song.Importers
import com.crsnow.neckscape.ui.AiTools
import com.crsnow.neckscape.ui.FileOpener
import com.crsnow.neckscape.ui.LocalBridge
import com.crsnow.neckscape.ui.LocalFileOpener
import com.crsnow.neckscape.ui.LocalScrollbars
import com.crsnow.neckscape.ui.NeckScapeScreen
import com.crsnow.neckscape.ui.FileSaver
import com.crsnow.neckscape.ui.NeckScapeViewModel
import com.crsnow.neckscape.ui.LocalFileSaver
import com.crsnow.neckscape.ui.LocalNotePlayer
import com.crsnow.neckscape.ui.NotePlayer

fun main() = application {
    val player = remember { NotePlayer(JvmAudioOutput()) }
    val viewModel = remember { NeckScapeViewModel().also { it.player = player } }
    // The local AI bridge (docs/ai-integration-spec.md): 127.0.0.1 only, token in Settings.
    val bridge = remember {
        val tools = AiTools(viewModel, platformName())
        BridgeServer(FileSettings(configDir().resolve("settings.properties")), lanCapable = false, handler = tools::call).also { it.start() }
    }
    // Only port/token/running changes matter to the discovery file, not every request.
    LaunchedEffect(bridge) {
        bridge.status.distinctUntilChangedBy { Triple(it.running, it.port, it.token) }.collect { Discovery.publish(it) }
    }
    Window(
        onCloseRequest = {
            bridge.stop()
            Discovery.withdraw()
            player.release()
            exitApplication()
        },
        title = "NeckScape-Navigator",
        icon = remember { BitmapPainter(ImageIO.read(object {}.javaClass.getResource("/icon.png")).toComposeImageBitmap()) },
        state = rememberWindowState(width = 1280.dp, height = 900.dp),
    ) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            val saver = remember(window) { FileSaver { name, _, bytes -> saveAndOpen(window, name, bytes) } }
            val opener = remember(window) { FileOpener { onFile -> pickFile(window)?.let { onFile(it.name, it.readBytes()) } } }
            CompositionLocalProvider(
                LocalNotePlayer provides player,
                LocalFileSaver provides saver,
                LocalFileOpener provides opener,
                LocalBridge provides bridge,
                LocalScrollbars provides DesktopScrollbars,
            ) {
                NeckScapeScreen(vm = viewModel)
            }
        }
    }
}

/** Asks where to save, writes the file, then opens it in the default viewer. */
private fun saveAndOpen(parent: java.awt.Frame, name: String, bytes: ByteArray) {
    val dialog = FileDialog(parent, "Save", FileDialog.SAVE).apply {
        file = name
        isVisible = true
    }
    val file = File(dialog.directory ?: return, dialog.file ?: return)
    file.writeBytes(bytes)
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Thread { runCatching { Desktop.getDesktop().open(file) } }.start()
    }
}

/** Asks for a song file to open; null if the user cancels or the file is too big. */
private fun pickFile(parent: java.awt.Frame): File? {
    val dialog = FileDialog(parent, "Open a song or tab", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> Importers.formatFor(name) != null }
        isVisible = true
    }
    val file = File(dialog.directory ?: return null, dialog.file ?: return null)
    return file.takeIf { it.length() <= AiTools.MAX_IMPORT_BYTES }
}

fun platformName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        "win" in os -> "windows"
        "mac" in os -> "macos"
        else -> "linux"
    }
}
