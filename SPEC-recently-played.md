# Spec — "Recently played / Continue listening" section

**Feature:** Continue listening on the main Browse (Artists) screen.
**Effort:** Lo-Med. **Priority:** #2 of the approved trio (after live-notifications, but cheapest high-value win — build first).

## Goal
A horizontal "Continue listening" section near the top of the Browse screen showing the **most recently played artists/sets** with a one-tap resume to where the user left off. Turning "find the DJ, open their page, find the set, tap play, seek" into **one tap on a card**.

## What exists today (do NOT rebuild — build on this)
- `PlaybackManager.persistIfChanged()` already calls `repository.saveLastPlayback(track, positionMs)` (~every ≤2s while playing, throttled) for **non-live** tracks.
- `Repository.saveLastPlayback(track, posMs)` → `PlaybackStateDataStore.saveLast(trackId, artistId, posMs)`.
- `PlaybackStateDataStore` already persists a **single** global last-track + **per-artist** `{"artistId": "trackId|positionMs"}` map (`artistPositionsKey`), plus `singleArtistKey` for Single-Artist mode.
- `MainViewModel` already has `ResumePoint(artist, tracks, index, positionMs)` + `lastResumePoint()` + `resumePointForArtist()`.
- Browse grid is driven by the repository's artists flow; `ArtistListScreen.kt` renders it.

## The gap
There is **no ordered history**. The per-artist map is unordered (JSON object) and holds only `trackId|position`, not the artist **display data** (name, thumbnail) or a timestamp. "Recently played" needs: an **ordered list** of recent plays (last N, newest first), each with enough data to render a card (artist name + thumbnail) and resume (track/trackId + positionMs). Live streams are excluded (indefinite, no meaningful resume — same rule as saveLastPlayback).

## Design

### 1. Persistence — add a `recentHistoryKey` (ordered list) to `PlaybackStateDataStore`
- New key: `stringPreferencesKey("recent_history")`.
- Shape: JSON array, newest first, capped at **N = 20** entries:
  ```json
  [{"t":"<trackId>","a":"<artistId>","p":<positionMs>,"ts":<epochMs>}, ...]
  ```
  Ordering by `ts` gives a reliable MRU list (the per-artist map can't because it's unordered).
- New API on `PlaybackStateDataStore`:
  - `val recentHistory: Flow<List<RecentEntry>>` (decoded, newest first) — expose a small `data class RecentEntry(trackId, artistId, positionMs, timestamp)`.
  - `suspend fun recordPlay(trackId, artistId, positionMs)`: upsert the entry (if the same `trackId` already exists, move it to front + bump ts; else prepend), trim to 20.
- `saveLast()` also calls `recordPlay(...)` so the existing persistence path feeds the history **with no call-site change** in `PlaybackManager` (verify precisely — either call `recordPlay` inside `saveLast` or add the call in the one existing `saveLastPlayback` path; prefer inside `saveLast` to keep it centralized).

### 2. Repository — surface the history + hydrate artists
- `Repository.recentHistory: Flow<List<RecentEntry>>` (pass-through from the DataStore).
- Provide a way to turn `RecentEntry` → renderable card: resolve `artistId` against the loaded artists (name + thumbnailUrl). The ViewModel already has `Artist`s; add `suspend fun artistsMap(): Map<String, Artist>` (or reuse an existing map if present — check MainViewModel for a cached `Map<id, Artist>`).

### 3. UI — "Continue listening" section
- In `ArtistListScreen.kt` (Browse), render a **horizontal, non-2-column** section **above** the main grid (below any existing header/sort row), LazyRow of cards.
- Each card: artist thumbnail (Coil, same component used by the grid) + artist name + a small "Resume" affordance; tap = resume playback at `positionMs`.
- Title: "Continue listening" (English). Empty state: hide the whole section (no wasted space).
- Should it appear in Single-Artist mode? **No** — that mode focuses one artist; keep it for the normal Browse view (clarify with agent; default = show only when not in single-artist mode).

### 4. ViewModel — expose a state
- `MainViewModel.recentResumePoints: StateFlow<List<ResumePoint>>` — combine `repo.recentHistory` × loaded artists, resolve each entry to `ResumePoint(artist, tracks=artist tracks, index=idxOf(trackId), positionMs)`. Reuse the track-resolution helper already used by `lastResumePoint()` so resume logic is identical. If a track can't be resolved (deleted), skip that entry.
- Tap handler reuses the existing "play + position" path (same as `lastResumePoint` resumption).

## Acceptance criteria (for the judge)
1. `PlaybackStateDataStore` has an ordered, capped (20), timestamped `recentHistory` — newest first.
2. Recording a play pushes/moves the entry and trims to 20; duplicates of the same trackId move to front (not duplicated).
3. `saveLast` (existing persistence path) feeds the history without new call-sites in `PlaybackManager`.
4. `Repository.recentHistory` exposes it; artists hydrate to cards (name + thumbnail).
5. Browse shows a horizontal "Continue listening" LazyRow above the grid, with cards; hidden when empty; hidden in single-artist mode.
6. Tapping a card resumes the right track at the right position (live excluded; unresolved entries skipped).
7. English UI strings. theme colors (not hardcoded). Phone app only — **no `.aa` touch**. media3 unchanged. No new heavy deps.
8. Clean-room: only the intended files changed.

## Out of scope (later)
- Sync across devices / web (roadmap #9/#10 — separate phase).
- "X played this recently" community event (n/a).
- Equalizer, playback speed, queue (separate items).

## Build / verify plan
User builds on PC → check: play a couple of sets for different DJs → reopen Browse → "Continue listening" shows those, newest first → tap a card → resumes at correct position → plays a live DJ → live doesn't appear in the list.