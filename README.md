# STArchive Player v4

Native Android music player for **starchive.streambox.space**, visually matching the
[STArchive web app](https://66ejzz.vercel.app/) and adding full Android Auto support.

- **Language/framework:** Kotlin + Jetpack Compose (Material 3)
- **Playback:** Media3 ExoPlayer behind a `MediaLibraryService` — background playback,
  lock-screen/notification controls, and a browsable media tree for Android Auto.
- **Data source:** Scrapes the Apache directory listing at
  `https://starchive.streambox.space/` directly. **Not** dependent on the Vercel web
  app or any backend API.
- **Files:** `.mp4` containers that are audio-only DJ sets.
- **Thumbnails:** derived from each track's filename timestamp
  (`{artistId}_picture_{timestamp}.jpg}`) — never scraped from `/thumbnails/`.
- **Avatars:** artist folders map 1:1 to Twitch usernames; avatars are resolved via
  `https://decapi.me/twitch/avatar/{username}` and cached to disk (`avatar_cache.json`
  in the app cache dir) so they aren't re-fetched every launch.

## What's new in v4

- **Android Auto / Automotive support** — `PlaybackService` is now a Media3
  `MediaLibraryService` exposing a browsable tree:
  - `Artists` → every artist → their tracks
  - `Favorites` → `Favorite Artists` and `Favorite Tracks`
  - `Search History` → past queries → matching tracks
  - Voice actions ("play dj_td", "play favorites", or any free-text search spoken to
    the Assistant) resolve through `onAddMediaItems` into real playable items.
  - `res/xml/automotive_app_desc.xml` + the `com.google.android.gms.car.application`
    manifest meta-data declare the app as a `media` category Android Auto app.
  - `uses-feature android:name="android.hardware.type.automotive" android:required="false"`
    so the app installs fine on phones and head units alike.
- **No persistent mini-player** — tapping a track opens the full-screen player.
  Leaving that screen does not show a mini bar anywhere; music keeps playing in the
  background (service + notification controls still work) exactly as a music app
  should, just without UI chrome following you around.
- **Favorite tracks are now browsable** on the Favorites screen (previously only
  favorite artists were listed) and resolve even across cold app starts, because the
  artist id is derived straight from the favorited track's filename.
- **Recent search history** persisted via DataStore; shown as quick-launch chips on
  the Search screen and exposed as a browsable Android Auto root.
- Fixed a real v3 bug: `PlayerScreen.kt` used `Icons.Filled.Share` without importing
  it (`Unresolved reference` at build time) — the import is now present.
- Version bump: `versionCode = 4`, `versionName = "1.4"`.

## Design

Colors and layout are matched from the Vercel web app screenshots:

- Background `#0A0A0F`, surface `#11111C`, card `#1A1A24`
- Primary violet `#7C3AED`, secondary purple `#A855F7`, green `#22C55E`, red `#EF4444`
- Browse screen: 2-column grid of square, rounded (16–20dp) artist cards with
  Twitch avatar / gradient placeholder, name, and a heart favorite toggle; Name/Date
  sort chips in the top bar.
- Player screen: large centered artwork, title + artist, seek slider, volume slider,
  prev / −10s / play-pause / +10s / next transport row, favorite + share actions.
- Bottom navigation: Browse, Search, Favorites (3 items — no mini-player row).

## Architecture

```
data/        Scraper (Jsoup/OkHttp), Repository (in-memory cache + favorites +
             search history), Models (Artist/Track), SettingsDataStore,
             FavoritesDataStore, SearchHistoryDataStore
playback/    PlaybackService (MediaLibraryService: ExoPlayer + MediaSession +
             Android Auto browse tree), PlaybackManager (MediaController wrapper
             used by the Compose UI)
ui/          Screens (ArtistList, ArtistDetail, Search, Favorites, Player),
             MainViewModel, AppNav (type-safe Navigation Compose routes), theme/
```

`Repository` caches artists and per-artist track lists in memory for the process
lifetime, persists favorites/search-history/avatar cache to disk, and lazily resolves
Twitch avatars at ~45 requests/min (well under decapi.me's ~100/min limit).

## Build

1. Open `starchive-player-v4/` in **Android Studio** (Ladybug/Meerkat or newer).
2. Let Gradle sync — requires JDK 17. If Android Studio's bundled JVM is newer
   (e.g. JDK 21+), set **Settings → Build Tools → Gradle → Gradle JDK** to a
   compatible version.
3. Build → Run on a device or emulator (minSdk 26 / Android 8.0+).

```bash
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # unsigned release APK
```

APK output: `app/build/outputs/apk/{debug,release}/app-*.apk`. For a signed release
build, create a keystore via **Build → Generate Signed App Bundle or APK**.

## Android Auto setup & testing

1. Build and install the app on a phone (`adb install app-debug.apk`).
2. Install the **Android Auto** app from Play Store (or use the **Desktop Head
   Unit (DHU)** from the Android SDK's `extras/google/auto` package for testing
   without a car).
3. Enable developer settings in Android Auto (tap the Android Auto version number
   ~10 times) and turn on **Unknown sources** so a non-Play-published build is
   allowed to show up.
4. Connect the phone (USB or the DHU over `adb forward`), open Android Auto; the
   STArchive icon should appear under media apps. Tapping it shows the `Artists`,
   `Favorites`, and `Search History` roots described above.
5. Voice test: "Hey Google, play [artist name] on STArchive" or "...play favorites
   on STArchive" — both are resolved in `PlaybackService.resolveForPlayback`.
6. For Android Automotive OS (head-unit OS, not phone projection) testing, use the
   **Automotive OS emulator** system image in Android Studio's AVD Manager.

### Known limitation

Browsing `Favorite Tracks` cold (before the app's in-memory track cache is warm)
requires one network round-trip per favorited track's artist folder — the archive
gives us no "last activity" index, so each favorite is resolved by deriving its
artist id from the filename and fetching that artist's track list (only that one
folder, not the whole archive).

## Notes

- `minSdk = 26` (Android 8.0) because the scraper uses `java.time` for thread-safe
  date parsing.
- `INTERNET`, `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
  permissions are declared; no other permissions are requested.
- Favorites, search history, theme choice, and the avatar cache are all stored
  locally (DataStore / cache dir) — nothing is synced to a server.
- No API keys are hardcoded; decapi.me's avatar endpoint is public and unauthenticated.
