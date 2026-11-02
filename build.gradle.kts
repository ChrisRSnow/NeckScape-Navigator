// AGP 9 has Kotlin built in — do NOT also apply org.jetbrains.kotlin.android,
// it double-registers the "kotlin" extension (see TailCamX/build.gradle).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
