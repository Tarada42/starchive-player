// Top-level build file
plugins {
    // AGP 8.10 is the floor for Kotlin 2.2: it bundles R8 8.10.21, the first R8 that
    // parses Kotlin 2.2 metadata (the "error occurred when parsing kotlin metadata"
    // warnings came from AGP 8.6.1's older R8). It also supports compileSdk 36.
    id("com.android.application") version "8.10.1" apply false
    // Kotlin 2.2.x is required by media3 1.11.1 and play-services-cast-framework 22.3.1:
    // both ship Kotlin metadata 2.2.0, which a 2.0.x compiler refuses to read.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
