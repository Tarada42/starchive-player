<div align="center">

# STArchive Player

**Stream DJ sets from ST Archive — on your phone and in the car.**

_Android app · Kotlin · Jetpack Compose · Media3/ExoPlayer · Android Auto_

</div>

STArchive Player is a native Android music player for the [ST Archive](https://starchive.streambox.space) — a community of Twitch DJs (team Stellar Transmissions). Browse every DJ, stream their sets, tune into who's live right now, download sets for offline listening, and control it all from the car.

> Full overview: **[ABOUT.md](ABOUT.md)** · Upcoming work: **[FEATURES-ROADMAP.md](FEATURES-ROADMAP.md)**

---

## Features

- **Browse & stream** every DJ's recorded sets (audio-only MP4) — grid or list view with date/name sorting
- **Live now** — see who's broadcasting right now and tune in instantly, with High (MP3) / Low (Ogg) quality choice where available
- **Favourites** for artists and individual sets
- **Resume playback** — picks up your last track + position, per DJ
- **Single Artist mode** — pin a DJ and focus the whole app on them
- **Offline downloads** — save sets (multi-select) and play with zero data
- **Cast** — stream live (MP3 & Ogg) and archive sets to Chromecast/Google Cast devices from the phone app; track switches follow the device, and stopping a cast resumes the last track locally
- **Search** with persistent recent searches
- **Hamburger drawer & overflow menu** — Settings and About (app version, Twitch/Discord/web links) from the phone app
- **Android Auto** — full media module with Artists / Live / Favourites tabs, artist grid/list toggle, sort cycling, quality picker, and a plain-list player with -10 s / +10 s / play-pause / stop
- **Background playback** with notification + lock-screen controls

---

## Tech Stack

Kotlin · Jetpack Compose (Material 3) · Media3/ExoPlayer + `MediaLibraryService` · Google Cast · OkHttp · jsoup · Coil · Jetpack DataStore

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
adb install -r app/build/outputs/apk/debug/STArchivePlayer-1.4.4-debug.apk
```

The APK is auto-named `STArchivePlayer-<version>-<buildType>.apk` (e.g. `STArchivePlayer-1.4.4-debug.apk` / `-release.apk`). To share a ready-to-install build, run `assembleRelease` and publish it as a [GitHub Release](https://github.com/Tarada42/starchive-player/releases).

---

## Android Auto: setup & testing

1. Build and install the app on a phone (see above).
2. Install the **Android Auto** app from the Play Store, or use the **Desktop Head Unit (DHU)** from the Android SDK's `extras/google/auto` package to test without a car. Note: DHU has no real audio sink — silence there is normal.
3. Enable developer settings in Android Auto (tap the version number ~10×) and turn on **Unknown sources** so a non-Published build is allowed to appear.
4. Connect the phone (USB, or the DHU via `adb forward`) and open Android Auto — the STArchive icon appears under media apps with **Artists / Live / Favourites** tabs.

The AA module is clean-room and phone-parity-driven: it shares only the `Repository` data layer with the phone app and never uses the phone UI or casting.

---

## Design

The look is matched to the STArchive web app palette:

- Background `#0A0A0F`, surface `#11111C`, card `#1A1A24`
- Primary violet `#7C3AED`, secondary `#A855F7`, green `#22C55E`, red `#EF4444`
- 2-column grid of square artist cards with Twitch avatar / gradient placeholder, name, favourite toggle, and date/name sort
- Bottom navigation: Browse, Live now, Search, Offline, Favourites
- Hamburger drawer (top-left) and 3-dot overflow menu (top-right) both expose Settings and About

---

## Community

Android player for the music archive for members of Twitch team **Stellar Transmissions**.

- **Discord:** https://discord.gg/xg9yEAv
- **Web app:** https://testplayer.streambox.space/

---

## License

Released under the [MIT License](LICENSE).

<div align="center">

**Built for the ST Archive community**

_Vibecoded using Birgitte The Bot, under direction of NickTheD_

</div>