<div align="center">

# 🎧 STArchive Player

**Stream DJ sets from ST Archive — on your phone and in the car.**

_Android app · Kotlin · Jetpack Compose · Media3/ExoPlayer · Android Auto_

</div>

STArchive Player is a native Android music player for the [ST Archive](https://starchive.streambox.space) — a community of Twitch DJs. Browse every DJ, stream their sets, tune into who's live right now, download sets for offline listening, and control it all from the car.

> 📄 Full overview, features, and data sources: **[ABOUT.md](ABOUT.md)**

---

## ✨ Features

- 🎛️ **Browse & stream** every DJ's recorded sets (audio-only MP4)
- 🔴 **Live now** — see who's broadcasting right now and tune in instantly
- ⭐ **Favourites** for artists and individual sets
- ▶️ **Resume playback** — picks up your last track + position, per DJ
- 📌 **Single Artist mode** — pin a DJ and focus the whole app on them
- ⬇️ **Offline downloads** — save sets (multi-select) and play with zero data
- 🔍 **Search** with persistent recent searches
- 🚗 **Android Auto** — full media-browser tree + garage-friendly controls
- 🔔 **Background playback** with notification + lock-screen controls

---

## Tech Stack

Kotlin · Jetpack Compose (Material 3) · Media3/ExoPlayer + `MediaLibraryService` · OkHttp · jsoup · Coil · Jetpack DataStore

- **Min SDK:** 26 · **Target SDK:** 35
- JDK 21 required to build

---

## Getting Started

```bash
git clone <this-repo-url>
./gradlew assembleDebug
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Android Auto: setup & testing

1. Build and install the app on a phone (`adb install app-debug.apk`).
2. Install the **Android Auto** app from the Play Store, or use the **Desktop Head Unit (DHU)** from the Android SDK's `extras/google/auto` package to test without a car.
3. Enable developer settings in Android Auto (tap the version number ~10×) and turn on **Unknown sources** so a non-Published build is allowed to appear.
4. Connect the phone (USB, or the DHU via `adb forward`) and open Android Auto — the STArchive icon appears under media apps, with `Artists`, `Favourites`, and `Search History` roots.
5. Voice test: *"Hey Google, play [artist name] on STArchive"* or *"…play favourites on STArchive"* — both resolve in `PlaybackService.resolveForPlayback`.

---

## Design

The look is matched to the STArchive web app palette:

- Background `#0A0A0F`, surface `#11111C`, card `#1A1A24`
- Primary violet `#7C3AED`, secondary `#A855F7`, green `#22C55E`, red `#EF4444`
- 2-column grid of square artist cards with Twitch avatar / gradient placeholder, name, favourite toggle, and Name/Date sort chips.
- Bottom navigation: Browse, Live now, Search, Offline, Favourites.

---

## License

*(Add a license before going public — see `LICENSE`.)*

<div align="center">

**Built with ❤️ for the ST Archive community**

</div>