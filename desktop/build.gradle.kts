import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// The Windows and macOS builds are assembled on Linux, so the UI's native
// renderer (Skia) has to be picked explicitly: -Pneckscape.os=windows|macos-arm64|macos-x64.
val targetOs = providers.gradleProperty("neckscape.os").orNull

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    // Compiles the Android app's theory and UI sources directly — one codebase,
    // so both apps always behave the same. Only the entry point and the audio
    // output are desktop-specific (src/main/kotlin here).
    sourceSets.main {
        kotlin.srcDir("../app/src/main/kotlin/com/crsnow/neckscape/theory")
        kotlin.srcDir("../app/src/main/kotlin/com/crsnow/neckscape/ui")
        kotlin.srcDir("../app/src/main/kotlin/com/crsnow/neckscape/notation")
        kotlin.srcDir("../app/src/main/kotlin/com/crsnow/neckscape/song")
        kotlin.srcDir("../app/src/main/kotlin/com/crsnow/neckscape/bridge")
        // Inter and the logo layer come from the Android resources, so there's one copy.
        resources.srcDir("../app/src/main/res/font")
        resources.srcDir("../app/src/main/res/mipmap-xxxhdpi")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(
        when (targetOs) {
            "windows" -> compose.desktop.windows_x64
            "linux" -> compose.desktop.linux_x64
            "macos-arm64" -> compose.desktop.macos_arm64
            "macos-x64" -> compose.desktop.macos_x64
            else -> compose.desktop.currentOs
        },
    )
    implementation(libs.cmp.material3)
    implementation(libs.jb.lifecycle.viewmodel.compose)
    implementation(libs.jb.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.swing) // Dispatchers.Main for viewModelScope
}

compose.desktop {
    application {
        mainClass = "com.crsnow.neckscape.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            // From :desktop:suggestRuntimeModules; java.desktop (incl. Java Sound) is always in.
            modules("java.instrument", "jdk.unsupported", "java.xml")
            packageName = "neckscape"
            packageVersion = "1.1.0"
            description = "Scales and chords on a guitar fretboard"
            vendor = "crsnow"
            // Drawn by tools/make_icons.py.
            linux {
                shortcut = true
                menuGroup = "Audio"
                iconFile.set(rootProject.file("icons/neckscape.png"))
            }
            windows {
                menu = true
                shortcut = true
                iconFile.set(rootProject.file("icons/neckscape.ico"))
            }
            macOS {
                iconFile.set(rootProject.file("icons/neckscape.icns"))
            }
        }
    }
}

// Renders the real screen to PNGs in build/snapshots — a quick visual check without a device.
dependencies {
    testImplementation(libs.junit)
}

// Feature-tour video frames + soundtrack (see FeatureVideo, tools/make_video.sh).
tasks.test {
    systemProperty("neckscape.video", providers.gradleProperty("neckscape.video").getOrElse(""))
    systemProperty("neckscape.videoDir", layout.buildDirectory.dir("video").get().asFile.path)
    systemProperty("neckscape.narrationDir", layout.buildDirectory.dir("narration").get().asFile.path)
    testLogging.showStandardStreams = providers.gradleProperty("neckscape.video").isPresent
}
