# Android Auto player — Option A1 (host-rendered media app)

Status: approved by Nikolaj (20-11-2026). Replaces the hand-built Car App Library
`ListTemplate` player with Android Auto's native, host-rendered media player.

## Problem

The current AA experience is a `ListTemplate` with text rows ("Seek back", "Seek forward",
"Play/Pause", "Stop"). Car App Library has no free-form UI, so there is no artwork, no
progress bar and no transport layout. Every other media app in Android Auto looks and
behaves better because it lets the host draw the player from its `MediaSession`.

## Goal

AA shows the native media player: DJ artwork, title/subtitle, seekable progress bar for
archived tracks, combined play/pause, ±30 s seek, previous/next track, and Stop. Live
streams show artwork + LIVE state with play/pause and Stop only.

The phone UI is NOT part of this work and must not change.

## Architecture

- Replace the Car App Library entry point with a **`MediaLibraryService`** exposing a
  browse tree that AA renders itself.
- Playback stays on the existing single ExoPlayer inside `PlaybackService`; the service
  becomes `MediaLibraryService` (media3 1.4.1, pinned — do not upgrade).
- AA draws the player from the `MediaSession`. We only control capabilities and metadata.

### Browse tree

```
Root
├── Live now          (one playable MediaItem per live DJ)
├── Artists           (folder per artist)
│   └── <Artist>      (all archive sets, playable, queueable)
└── Favorites         (favorite tracks; folder + flat list of favorites)
```

Folders must be browsable (`MEDIA_TYPE_FOLDER_MIXED`) and playable items must carry
`MEDIA_TYPE_AUDIO` plus full `MediaMetadata`.

## Metadata contract

| Field | Archived track | Live stream |
|---|---|---|
| `title` | formatted track title (see below) | `<DJ> · Live` |
| `subtitle`/`artist` | DJ display name | DJ display name |
| `artworkUri` | artist artwork (existing avatar resolution) | artist artwork |
| `isBrowsable` | false | false |
| `isPlayable` | true | true |

Track title formatting: `yyyy-MM-dd HH:mm` parsed from the filename when the filename
matches the archive pattern, otherwise the raw filename without extension.
Do not invent titles.

## Capabilities per content type

**Archived track**
- `COMMAND_PLAY_PAUSE`, `COMMAND_SEEK_TO`, `COMMAND_SEEK_BACK`, `COMMAND_SEEK_FORWARD`,
  `COMMAND_SKIP_TO_NEXT`, `COMMAND_SKIP_TO_PREVIOUS`, `COMMAND_STOP`
- `isSeekable = true`; real duration from the media source
- Seek increments **30 000 ms** both directions (replaces the current 10 s behaviour)
- Queue = the artist's track list, so prev/next move within that artist

**Live stream**
- `COMMAND_PLAY_PAUSE`, `COMMAND_STOP` only
- `isSeekable = false`; duration `C.TIME_UNSET`
- Single-item queue: no next/previous, no seek

## Manifest changes

- `PlaybackService`: add intent-filters for `androidx.media3.session.MediaLibraryService`
  and keep `androidx.media3.session.MediaSessionService`.
- Remove `com.starchive.player.aa.StArchiveCarAppService` service entry, the
  `androidx.car.app.category.MEDIA` category, the `androidx.car.app.MEDIA_TEMPLATES`
  permission and the `androidx.car.app.minCarApiLevel` meta-data.
- Keep `com.google.android.gms.car.application` (`@xml/automotive_app_desc`); ensure the
  descriptor declares media support.
- Delete the now-dead `com/starchive/player/aa/*` sources only after the service compiles
  without them. `AaPlaybackClient` / `AaPlayerWrapper` must not remain in the service path.

## Constraints

- media3 stays at **1.4.1**.
- Do not touch `PlaybackManager`, the phone UI, Cast behaviour, widget or notification code
  beyond removing AA-specific wrappers.
- English-US only in code, comments, docs and commit messages.
- No new dependencies.
- Single APK, no product flavours.

## Verification (Nikolaj, on device)

1. DHU: AA shows the app; Live now / Artists / Favorites browse correctly.
2. Archive: play a set — artwork, title, DJ name, scrub bar, play/pause, ±30 s, prev/next.
3. Live: play a live DJ — artwork, LIVE state, play/pause, no scrub bar, no skip.
4. Stop ends playback and dismisses the car player.
5. Phone app behaves exactly as before (regression check).
