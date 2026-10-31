# Spec — "Favorite DJ is live" notifications

**Feature:** When a DJ the user has favourited goes live, post a phone notification; tapping it opens the app and starts listening to that DJ's live stream.
**Effort:** Med-High. **Priority:** the flagship of the approved trio (recently-played ✓, sleep-timer ✓, this is #1-the-last).

## Research basis (known-good Android sources — read before coding)
- **Background polling must use WorkManager** (developer.android.com/develop/background-work): it's Google's explicit recommendation for "periodically syncing application data," survives process death, device reboots, and Doze. NOT AlarmManager (wakes the device from Doze; only for exact-time events).
- **Honest latency caveat:** WorkManager's **minimum periodic interval is 15 minutes** (OS-enforced). So a notification can be up to ~15 min behind the DJ actually going live. Acceptable for this app (DJs stream for hours) — note it, don't fight it.
- **Notifications:** Android 13+ requires **`POST_NOTIFICATIONS` runtime permission** (it's already declared in the manifest — line 11 — but must be requested at runtime, and the app should create a NotificationChannel). Use `NotificationCompat.Builder` with a channel. Tap = PendingIntent → open the app to the live player.
- **Power/battery:** WorkManager constraints (network CONNECTED) + only polling **favourited** usernames keeps it cheap.

## What exists today (build on this — don't reinvent)
- `data/FavoritesDataStore.kt`: `favoriteArtists: Flow<Set<String>>` (artist IDs = Twitch usernames), `toggleArtist`. This is the "favourites" source.
- `data/Repository.kt`: `suspend fun loadLive(forceRefresh=false)` populates `liveUsernames: StateFlow<List<String>>` (the set of usernames currently live, scraped from the live server). THIS is the live-detection we need — the worker should call `loadLive(true)` (force refresh) and compare against favourites.
- `data/SettingsDataStore.kt`: exists (darkMode). Add a `liveNotifications: Flow<Boolean>` toggle here so users can enable/disable the feature. Default **on** (opt-in is the notification permission; the in-app toggle is a convenience).
- `Repository` presumably exposes the Artist resolver (id→name/thumbnail) — reuse the same hydration used by recently-played to build the notification title/thumbnail.
- `ui/SettingsScreen.kt`: currently shows only "No settings to change, yet" — this is a natural home for the **"Live notifications"** toggle (nice win — first real setting).

## Design

### 1. Dependency
Add `androidx.work:work-runtime-ktx` (a current stable, e.g. 2.9.x — verify the latest stable at build time; use the KTX artifact). This is the ONE new dependency.

### 2. Background worker — `LiveNotificationsWorker` (`data/` or `notifications/`)
- `Worker` that: (a) reads `favoriteArtists` from FavoritesDataStore, (b) if empty, returns success (no-op); (c) calls `Repository.loadLive(forceRefresh = true)` to get the current `liveUsernames`; (d) computes the intersection = **currently-live favourite DJs**; (e) for each such DJ, checks a small **"already notified"** ledger (persist a `Set<String>` of username+timestamp, e.g. in SettingsDataStore or a tiny DataStore / just remember last-notified usernames this session) so it does NOT re-notify every 15 min while the same DJ stays live; (f) posts a `NotificationCompat` for each newly-live favourite (title = DJ name, text = "is live now", tap opens the live player).
- **Idempotency/anti-duplicate:** the worker must not spam. Ledger keyed by live-DJ id; when a DJ stops being live it's cleared from the ledger so a future go-live notifies again. (Simple approach: store last-seen live set; notify for set-difference; remember. Trim/self-heal on size.)
- Never post an empty/invalid notification; guard against exceptions so a failed check doesn't crash.

### 3. Scheduling — `LiveNotificationsScheduler` (helper)
- `schedule()`: `WorkManager.enqueueUniquePeriodicWork("live_alert", ExistingPeriodicWorkPolicy.KEEP/CANCEL_AND_REENQUEUE, PeriodicWorkRequestBuilder<Worker>(15, MINUTES).setConstraints(Constraints(network=CONNECTED)).build())`. Use unique periodic work so we never double-schedule.
- `cancel()`: cancel unique work by name.
- **Schedule/unschedule on the toggle:** when the user turns "Live notifications" **on** → request permission (if needed) + schedule; **off** → cancel. Also schedule one-time on app start if enabled (idempotent via unique name).

### 4. Notification permission + channel
- Manifests declares `POST_NOTIFICATIONS` already; the app must **request it at runtime on API 33+** (Compose: `rememberLauncherForActivityResult(RequestPermission)`), best done when the user flips the toggle on (and/or first time on the Settings screen). If denied, still schedule but notifications won't show — show an inline hint.
- Create a `NotificationChannel` (importance DEFAULT) for "live alerts" before posting.

### 5. Tap-action → play live
- The notification PendingIntent should open the app and **start playing that DJ's live stream**. Cleanest: the notification carries the live artist's data; the app already knows how to play a live DJ (`liveArtists`, `liveStreamUrlHigh`. Reuse the exact path the Live screen uses to play a live DJ — check how `AppNav`/`MainViewModel` resolves a live username → `Artist` → live play. If a background context can't easily reach the ViewModel, have the PendingIntent launch an intent that the Activity handles to freshly load + play that DJ (mirror the existing "tap a live card" flow). Keep it minimal and reuse existing helpers.

### 6. Settings UI
- `SettingsScreen`: replace/augment the placeholder so it has one row: **"Live notifications"** switch (StateFlow from SettingsDataStore). Turning it on requests permission (if needed) + schedules + schedules; off cancels. Keep English.

## Acceptance criteria (for the judge)
1. `androidx.work:work-runtime-ktx` added (only new dep); a `PeriodicWorkRequest` (15-min min, network=CONNECTED constraint) is scheduled uniquely and cancels cleanly.
2. `LiveNotificationsWorker` reads favourites, fetch live (force refresh), notifies **only** for newly-live favourites, and does **not** spam (ledger / dedupe; same DJ stays-live → no repeat while live; stopped then re-lived → notifies again).
3. Notifications use a created `NotificationChannel` + `NotificationCompat`, and are only posted when the user has enable toggle AND (on 33+) POST_NOTIFICATIONS granted; no crash if denied.
4. Tap on the notification opens the app and **starts playing that DJ live** (reusing existing live-play path — no new player logic).
5. Settings screen has a **"Live notifications"** toggle wired to SettingsDataStore (persisted), which on/enables schedules + requests permission, off/cancels. First real Settings row.
6. English strings; theme colors; phone app only — **no `.aa` touch**; media3 unchanged; existing playback/live/recently-played/sleep-timer/history/cast untouched.
7. Clean-room: only intended files; worker/notifications/scheduler/logic safe (no crash path) against empty favourites, no network, permission denied.
8. Honest docs: WorkManager 15-min minimum latency noted (in comments + this spec).

## Out of scope (later)
- Push/FCM (no server — polling is the correct fit here).
- Per-DJ notification toggles, "available now" grouped, worn, channels per artist. This is the MVP single toggle.

## Build / verify plan
User builds on PC → enable "Live notifications" in Settings (grant permission) → wait for a favourited DJ to go live → within ~15 min a notification appears; tapping it plays the DJ's live stream; no spam while still live. Toggle off → worker cancelled (no more notifications).