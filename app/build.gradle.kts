plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
}

android {
    namespace = "com.starchive.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.starchive.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.4.4"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // AppCompat (needed by MediaRouteButton / Cast chooser dialog)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Media3 (ExoPlayer + MediaLibrarySession for background playback & Android Auto)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Google Cast (PHONE ONLY — nothing here is exposed to Android Auto)
    implementation("androidx.media3:media3-cast:1.4.1")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")

    // Android Auto — Car App Library templates (MEDIA category + playback template need API 8)
    implementation("androidx.car.app:app:1.8.0-rc01")

    // androidx.media is pulled in transitively by the Car App Library, but MediaSessionCompat
    // is used directly for registering the shared MediaSession token with AA, so pin it.
    implementation("androidx.media:media:1.6.0")

    // Bridges suspend functions to ListenableFuture for the MediaLibrarySession callbacks
    // that Android Auto / the Media3 framework invoke.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    // Networking + HTML parsing (Apache autoindex scraping)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // Image loading (thumbnails)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // DataStore for favorites / preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
