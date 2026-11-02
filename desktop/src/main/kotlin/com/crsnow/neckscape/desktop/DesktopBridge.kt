package com.crsnow.neckscape.desktop

import com.crsnow.neckscape.bridge.BridgeStatus
import com.crsnow.neckscape.bridge.SettingsStore
import com.crsnow.neckscape.song.Json
import com.crsnow.neckscape.ui.APP_NAME
import com.crsnow.neckscape.ui.APP_VERSION
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/** Per-user settings folder: ~/.config/neckscape, ~/Library/Application Support/NeckScape or %APPDATA%\NeckScape. */
fun configDir(): File {
    val home = File(System.getProperty("user.home"))
    val dir = when (platformName()) {
        "windows" -> File(System.getenv("APPDATA") ?: home.path, "NeckScape")
        "macos" -> File(home, "Library/Application Support/NeckScape")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: File(home, ".config").path, "neckscape")
    }
    dir.mkdirs()
    return dir
}

/**
 * Writes [text] readable by this user only (where the file system supports it):
 * the file is created private before anything is written, then renamed into place.
 */
private fun writePrivate(file: File, text: String) {
    file.parentFile.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp").toPath()
    Files.deleteIfExists(tmp)
    runCatching { Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
        .onFailure { Files.createFile(tmp) } // Windows: per-user folders are private already
    Files.writeString(tmp, text)
    Files.move(tmp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
}

/** Bridge settings (token) in a properties file. */
class FileSettings(private val file: File) : SettingsStore {
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    @Synchronized override fun get(key: String): String? = props.getProperty(key)

    @Synchronized override fun put(key: String, value: String) {
        props.setProperty(key, value)
        writePrivate(file, java.io.StringWriter().also { props.store(it, "NeckScape-Navigator settings") }.toString())
    }
}

/**
 * Tells the MCP server where this instance listens: one small JSON file per
 * running app in instances/, removed on exit. It holds the token, so only
 * this user can read it (the MCP server runs as the same user).
 */
object Discovery {
    private var current: File? = null

    @Synchronized
    fun publish(status: BridgeStatus) {
        val file = File(configDir(), "instances/${status.port}.json")
        // Replaced in place (never deleted first), so the MCP server can't catch it missing.
        if (!status.running || current != file) withdraw()
        if (!status.running) return
        writePrivate(
            file,
            Json.write(
                mapOf(
                    "app" to APP_NAME, "version" to APP_VERSION, "platform" to platformName(),
                    "host" to "127.0.0.1", "port" to status.port, "pid" to ProcessHandle.current().pid(), "token" to status.token,
                ),
                "  ",
            ),
        )
        file.deleteOnExit()
        current = file
    }

    @Synchronized
    fun withdraw() {
        current?.delete()
        current = null
    }
}
