# R8 / ProGuard rules for the release build.
# Release builds run R8 because Google Play's "DEX code optimization" vitals check
# requires real obfuscation (it reported 0% while minify was disabled).

# --- Kotlin serialization ---------------------------------------------------
# Navigation type-safe routes are @Serializable; their generated $$serializer
# classes and Companion objects are resolved reflectively by the library.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.starchive.player.**$$serializer { *; }
-keepclasseswithmembers class com.starchive.player.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.starchive.player.** {
    *** Companion;
}

# --- Media3 / ExoPlayer -----------------------------------------------------
# Media3 ships its own consumer rules in the AARs; the blanket keep is retained
# deliberately: it costs obfuscation percentage but removes any risk of a
# reflective lookup failing at runtime in the player or the Cast session.
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# --- OkHttp -----------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Jsoup ------------------------------------------------------------------
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# --- Crash readability ------------------------------------------------------
# Keep source file names and line numbers so stack traces from the obfuscated
# release build stay usable (mapping.txt is still needed for real deobfuscation).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
