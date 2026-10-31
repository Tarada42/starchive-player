# Spec — Sleep timer

**Feature:** Let the user set a countdown after which playback stops automatically (e.g. falling asleep).
**Effort:** Lo-Med. **Priority:** #3 of the approved trio (after live-notifications, recently-played ✓).

## Goal
A small control on the Player screen: pick **Off / 15 / 30 / 45 / 60 min**. When time runs out, **stop playback** (pause + reset to 0, same as the existing Stop action → which clears the persisted resume). The timer must keep working in the **background** (screen off, app navigated away), because that's the whole point of a sleep timer.

## What exists today (build on this — do NOT reinvent)
- `PlaybackService` (`playback/PlaybackService.kt`) is a **foreground `MediaSessionService`** — the right home for timer logic since it survives backgrounding and screen-off. It has a `mediaSession`, `player`, `localPlayer`/`castPlayer`, and a `serviceScope = CoroutineScope(Main + SupervisorJob())`.
- `PlaybackManager.stop()` (in `playback/PlaybackManager.kt`) already implements "stop": `c.pause(); c.seekTo(0); _isPlaying=false; _positionMs=0; scope.launch { repository.clearLastPlayback() }`. The sleep timer should trigger the equivalent of this stop when it fires.
- `SettingsDataStore` (`data/SettingsDataStore.kt`) exists for persisted preferences — but the sleep timer is **per-session** (do NOT persist the deadline; it should reset when the service restarts). So persistence is optional (see Design).
- `PlayerScreen.kt` (`ui/PlayerScreen.kt`) is Material3 with transport `IconButton`s and at least one `Slider` (seek). The timer control can be a compact row of choice chips / a button that opens the options.

## Design

### 1. Timer logic — lives in `PlaybackService` (survives background)
- Add a `SleepTimer : Closeable` helper OR a plain suspend/coroutine managed by `serviceScope`. Recommended: a small dedicated `SleepTimer(scope, onExpired)` class in `playback/` that:
  - `fun start(remainingMs: Long)` — cancels any prior, launches a `delay(remainingMs)` then calls `onExpired` (on the main thread, via the scope).
  - `fun cancel()` — cancels.
  - `fun remainingMs(): Long?` — for the UI to show the remaining countdown (optional but nice).
- On expiry: call the **service-side "stop playback"** path. The service currently doesn't expose a stop that fully mirrors `PlaybackManager.stop()`; have the timer's `onExpired` invoke the same effect: pause, seekTo(0), clear persisted resume (via the existing `repository.clearLastPlayback()`), and if casting, end/keep the cast session consistent with the existing stop semantics. Reuse whatever the service already does for "user hit stop" if present; otherwise implement a private `stopPlayback()` in the service that mirrors `PlaybackManager.stop()`'s semantics. Keep it simple and consistent.
- The service must cancel any active timer in `onDestroy()` and when a new playback session starts (per user intent — a sleep timer set on one set shouldn't survive into the next unless desired; default: cancel the timer in `onDestroy()` and on service restart; keep it running if the user is just navigating the UI since the service stays alive).

### 2. Control — Player screen
- In `PlayerScreen.kt`, add a compact sleep-timer control. Recommended UX: a small **"Sleep" button** (icon e.g. `Icons.Filled.Bedtime`, or a text "Sleep timer") next to the transport controls that toggles a set of choices (Off / 15 / 30 / 45 / 60 min) via a `DropdownMenu` or a small `ModalBottomSheet` — pick whichever fits the existing screen style (it already uses `DropdownMenu`-capable Material3; match the existing pattern). Show the **current selection / remaining time** as the button label when active (e.g. "Sleep 15m" or "Sleep ⏳ 12m").
- Choosing **Off** (or canceling) stops the timer. The control is **phone-app only** — never in the AA module.

### 3. State wiring — UI ↔ service
- The service owns the timer state; the Player screen / ViewModel needs to **set** and **read** it. Cleanest options (pick one that fits the existing architecture and verify):
  - (a) A **custom MediaSession command** (the service already registers a custom command `COMMAND_GET_PLATFORM_TOKEN` — reuse that mechanism): add `SET_SLEEP_TIMER` command bytes → service handles it (start/cancel), and a way for the UI to query remaining (e.g. a state in a StateFlow exposed via the command + polling, or a `getSleepTimerRemaining` command). This survives backgrounding cleanly and is the "correct" service boundary.
  - (b) Expose a `StateFlow<SleepTimerState>` from the service via... but UI can't easily observe the service directly. The app already uses `PlaybackManager` (a `MediaController` wrapper) — route the set through a controller command and report state back through the media session's `Player.Listener`/timeline or a callback.
  - Decide based on what the existing `AaPlaybackClient`/custom-command plumbing supports. **Verify against the current code** (read `PlaybackService.mediaSessionCallback`, `AaPlaybackClient` commands, and how the ViewModel talks to `PlaybackManager`) before choosing. Keep it minimal — the timer doesn't need to be sub-second precise in the UI; a coarse remaining display is fine.

### 4. Persistence
- **Do NOT persist the countdown deadline** (a sleep timer is a per-launch convenience; it should not survive a process kill). If the remaining time needs to survive rotation, keep it in the ViewModel (rotation-safe) or queryable from the service. When the app/process is killed, the timer is simply gone.

## Acceptance criteria (for the judge)
1. A sleep-timer control exists on the Player screen with **Off / 15 / 30 / 45 / 60 min** options.
2. Selecting a duration arms the timer; the countdown runs in `PlaybackService` (survives background/screen-off).
3. On expiry, playback **stops** (pauses + resets to 0) and clears the persisted resume — consistent with the existing stop action.
4. Changing **Off** or restarting cancels an armed timer; service `onDestroy()` cancels it.
5. Works for local and live (and casting is consistent with existing stop semantics — no crash, session handled as the existing stop path does).
6. English strings; theme colors; phone app only — **no `.aa` touch**; media3 unchanged; no new heavy deps (a small helper class is fine).
7. Clean-room: only intended files changed; no regression to existing playback/stop/resume/cast/continue-listening.
8. The UI reflects the active selection/remaining time (coarse remaining is acceptable).

## Out of scope (later)
- Per-preference default timer, notifications when it expires, "sleep after end of set" (only plain countdown). These can be follow-ups.

## Build / verify plan
User builds on PC → play a set → open Player → set Sleep 1 min (or a short test if off-the-list possible during dev, else 15) → navigate away / let the screen sleep → after expiry, playback has stopped and resume is cleared; no crash with local, live, and (if cast device present) casting. Cancel path: set a timer, choose Off → it stays playing past the old deadline.