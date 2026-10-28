# STArchive Player

**STArchive Player** is a native Android music player for DJ set archives from **ST Archive** — a community of Twitch DJs who record and publish their radio/live sets.

The app lets you **browse every DJ, stream their sets, follow your favourites, tune into who's live right now, and download sets for offline listening** — on your phone or in the car (Android Auto).

> [!NOTE]
> The source of truth for artwork and track lists lives in a hosted web-app API (`webapp-copy-silk.vercel.app/api/*`) which mirrors the archive. See [Data sources](#data-sources).

---

## What it does

- **Browse DJs** — a two-column grid of every artist with their profile picture, sorted newest-first (or by name, either direction).
- **Play sets** — stream any recorded set (audio-only MP4) through a full player with seek, next/previous, and 10s skip.
- **Live now** — see which DJs are broadcasting *right now* and tune in with one tap (HQ Icecast restream).
- **Favourites** — favourite artists and individual sets; a dedicated Favourites tab.
- **Resume playback** — the app remembers the last track and its exact position, and picks up where you left off on the next launch. It also remembers the last position *per DJ*, so re-opening a favourite DJ continues their last set.
- **Single Artist mode** — pin a DJ and the whole app behaves as if built for just that artist, until you exit.
- **Offline downloads** — download sets (multi-select supported) to internal storage and play them back with zero data; a dedicated **Offline** tab lists everything saved.
- **Search** — search across artists and track titles, with persistent recent searches.
- **Android Auto** — a full media-browser tree (Artists / Favourites / Search history) with garage-friendly transports, surfaced through Media3's `MediaLibraryService`.
- **System controls** — playing continues in the background with notification + lock-screen controls that support the full transport (including the notification's own × to stop).

## Screenshots

*(Add screenshots here when ready.)*

---

## Data sources

All data originates from the public **ST Archive** directory listing:

| Source | Purpose |
|---|---|
| `starchive.streambox.space` | The archive itself: one folder per DJ, each holding recorded set files (`*.mp4`, audio-only) and artwork under `/thumbnails/`. |
| `live.streambox.space` | Live broadcasting page (Icecast) — used to discover which DJs are live and to play the HQ restream (`<username>_hq`). |
| `https://decapi.me/twitch/avatar/<user>` | Twitch profile pictures — DJ identifiers are the same as their Twitch channel names, so we use the channel avatar when the archive has no artwork. |
| `webapp-copy-silk.vercel.app/api/*` | The app's data API (hosted, cached, refreshed daily). Endpoints: `/api/artists`, `/api/tracks?artist=…`, `/api/artist-thumbnails`. |

### How the API keeps itself fresh

A daily job on the hosting server:

1. Re-scans the archive for every DJ folder and their newest artwork.
2. Falls back to each DJ's **Twitch profile picture** for anyone without archive artwork.
3. **Removes DJs who have been unreachable without any artwork for 14+ days** — so the rotation stays clean automatically.
4. Publishes a static, cached snapshot the app reads instantly.

The Android app consumes this API, so it inherits these server-side rules (fresh artwork, removed DJs, Twitch fallbacks) without shipping its own scraping logic.

---

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3), single-activity with Navigation Compose
- **Playback:** Media3 / ExoPlayer + Media3 `MediaLibraryService` (background playback, notification, Android Auto)
- **Networking / parsing:** OkHttp, jsoup (archive scraping on the API side), Coil (images)
- **Persistence:** Jetpack DataStore (favourites, settings, search history, playback resume, download state)
- **Downloads:** app-internal storage — no storage permission required
- **Minimum SDK:** 26 · **Target SDK:** 35

---

## Project layout (highlights)

```
app/src/main/java/com/starchive/player/
├── data/          # Repository, Scraper (API client), DataStores, DownloadManager, models
├── playback/      # PlaybackService (MediaLibraryService) + PlaybackManager (MediaController wrapper)
└── ui/            # Compose screens: artist list, live, detail, player, favourites, offline, search + navigation
```

---

## Building

Requires JDK 21 and the Android SDK.

```bash
git clone <this repo>
./gradlew assembleDebug
```

Install on a connected device via `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

---

## License

*(Add a license before going public — see `LICENSE`.)*
