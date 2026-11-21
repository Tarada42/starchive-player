# STArchive — Feature Roadmap

> Living document, revised each session. English only.
> "App" = Android app · "Web" = webapp (own VPS instance) · "API" = hosted data service.
>
> **Current app version: 1.5.8** (versionCode 17). Play submission in review: 1.5.7 (versionCode 16).

Each item: **Impact** and **Effort** (Lo / Med / Hi).

Already shipped and therefore no longer tracked here: live-DJ notifications, recently played / continue listening, sleep timer, up-next queue, Chromecast, home-screen widget, Android Auto (host-rendered player with live row, Stop, car favourites), About-this-DJ screen, search history, and genre chips on the web.

---

## Phase 1 — Quick wins (app)

| # | Feature | Impact | Effort |
|---|---|---|---|
| 1 | **Playback speed** (0.5×–2×) | Med | Lo |
| 2 | **Auto-next / surf live DJs** — carry on to the next live DJ when a set ends | High | Med |
| 3 | **Download improvements** — download-all per DJ, storage used, delete by artist, sequential queue preferring Wi-Fi | Med | Med |

---

## Phase 2 — Shared systems (app + web)

| # | Feature | Impact | Effort |
|---|---|---|---|
| 4 | **Genre tagging in the app** — reuse the web's `genres.json` / `genre-overrides.json` for cards and filters (web side already shipped) | High | Med |
| 5 | **Synced resumes via API** — recently played and per-artist positions across devices | High | Med |
| 6 | **Per-track share / embed URL** — shareable link plus embeddable player | Med-Hi | Med |
| 7 | **Play-history API** — one source of truth feeding both clients | Med-Hi | Med |

---

## Phase 3 — Discovery & community

| # | Feature | Impact | Effort |
|---|---|---|---|
| 8 | **"On ST now" embed** for a DJ's Twitch page | Med-Hi | Med |
| 9 | **Top stats** — most-played DJ, newest set, streaks (needs play logging) | Med | Lo-Med |
| 10 | **Charts / trending** — most-played sets this week | Med | Med |
| 11 | **Collaborative playlists** — users curate sets | Med-Hi | Hi |

---

## Phase 4 — Polish & power users

| # | Feature | Impact | Effort |
|---|---|---|---|
| 12 | **Equalizer / audio settings** | Med | Med |
| 13 | **Android Auto: recently played root** — a fourth browse root for resume history | Med | Lo-Med |
| 14 | **Cast TV-remote controls** — make the default receiver's on-screen transport controls appear (debug task, no custom receiver) | Med | Med |
| 15 | **Tablet / landscape layout pass** — half-size thumbnails everywhere, continue-listening adapted | Med | Med |
| 16 | **Automatic / rule-based metadata** — DJ bio, images, descriptions | Med | Med |
| 17 | **PWA install prompt** ("Add to home screen") | Med | Lo |

---

## Shelved / not planned now

- **iOS port** — needs a Mac (Apple silicon) for Xcode 26+; no hardware and no cloud-Mac budget. Ogg Vorbis also needs a server-side transcode first.
- **F-Droid distribution** — violates store requirements for the app's data use.
- **Picture-in-Picture** — audio-only app, correctly rejected.
- **Suno / AI music generation, desktop app, local model integration** — out of scope for this product.

---

> **Selection rule:** only list features we actually intend to build soon. Everything shipped gets deleted from this file, not ticked off.