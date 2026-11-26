// Top-level build file
plugins {
    id("com.android.application") version "8.6.1" apply false
    // Kotlin 2.2.x is required by media3 1.11.1 and play-services-cast-framework 22.3.1:
    // both ship Kotlin metadata 2.2.0, which a 2.0.x compiler refuses to read.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
