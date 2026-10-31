# STArchive — Feature Roadmap

> Living document. Revised as the app / web-app evolve.
> "App" = Android app · "Web" = web app (own Vercel instance) · "API" = hosted data service.

Each item: **Impact** (how much value) and **Effort** (Lo = low, Med = medium, Hi = high). Suggested order by phase.

---

## Approved next steps (building now — app)

The community trio, agreed as the immediate focus for the Android app:

| # | Feature | Platform | Impact | Effort |
|---|---|---|---|---|
| 1 | **"Favorite DJ is live" notifications** — alert when a favourited artist goes live; tap to listen | App | High (flagship) | Med |
| 2 | **Recently played / Continue listening** section at the top of Browse | App + Web | High | Lo |
| 3 | **Sleep timer** ("stop playing in 30 min", etc.) | App | High | Lo |

These are the current priority: implement one at a time through the established agent + judge process, verify each before the next.

---

## Phase 1 — Quick wins (low effort, high everyday value)

| # | Feature | Platform | Impact | Effort |
|---|---|---|---|---|
| 4 | **Playback speed** (0.5×–2×) | App | Med | Lo |
| 5 | **Auto-next / surf through live DJs** | App | High, unique | Med |
| 6 | **Download queue** (multi-download sequential, prefer WiFi) | App | Med | Med |
| 7 | **PWA install prompt** ("Add to home screen") | Web | Med | Lo |

---

## Phase 2 — Shared systems (build once, benefit both)

| # | Feature | Platform | Impact | Effort |
|---|---|---|---|---|
| 8 | **Genre tagging from Twitch tags** (tag→genre cards, filter + "by genre") | App + Web | High | Med-Hi |
| 9 | **Synced resumes via API** (recently-played + per-artist) | App + Web | High | Med |
| 10 | **Last-played / history server** (single source-of-truth API driving both) | API | Med-Hi | Med |
| 11 | **Per-track share / embed URL** (share + embeddable widget) | Web + App | Med-Hi | Med |

---

## Phase 3 — Community & discovery

| # | Feature | Platform | Impact | Effort |
|---|---|---|---|---|
| 12 | **"On ST now" widget / embed** for a DJ's Twitch page | Web | Med-Hi | Med |
| 13 | **Top stats** (most-played DJ, newest set, streaks) | Web + App | Med | Lo-Med |
| 14 | **Collaborative playlist** (users curate sets) | Web + App | Med-Hi | Hi |

---

## Phase 4 — Polish & power users

| # | Feature | Platform | Impact | Effort |
|---|---|---|---|---|
| 15 | **Equalizer / audio settings** | App | Med | Med |
| 16 | **Queue management in player** (view + reorder upcoming tracks) | App | Med-Hi | Med |
| 17 | **Cast to device** ***(done — Chromecast live MP3+Ogg & archive, track switching, stop-cast local resume)*** | App | Med-Hi | Done |
| 18 | **More AA roots / AA recents** | App | Med | Lo-Med |
| 19 | **Automatic / rule-based metadata** (DJ bio, images, descriptions) | API | Med | Med |

---

## Planned order (next steps)

1. **Favorite DJ is live** notifications
2. **Recently played / Continue listening** section
3. **Sleep timer**
4. Playback speed
5. Genre tagging (Twitch)

---

> **Selection rule:** only include features we actually intend to build soon. Remove or mark as backlog the rest. Revised each session.