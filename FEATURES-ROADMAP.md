# STArchive — Feature Roadmap

> Living document. Revised as the app/web-app evolve.
> "App" = Android app · "Web" = web-app (own Vercel instance).

Each item: **Impact** (how much value) and **Effort** (Lo=low, Med, Hi). Suggested order by phase.

---

## Phase 1 — Quick wins (low effort, high everyday value)

| # | Feature | Platform | Impact | Effort |
|---|---|---|---|---|
| 1 | **Sleep timer** ("stop om 30 min") | App | High | Lo |
| 2 | **"Sidst lyttet / Fortsæt" sektion** øverst på Browse | App + Web | High | Lo |
| 3 | **Afspilningshastighed** (0.5×–2×) | App | Med | Lo |
| 4 | **Auto-next / surf gennem live DJs** | App | High, unikt | Med |
| 5 | **Download-kø** (multi-download sekventielt, helst på WiFi) | App | Med | Med |
| 6 | **PWA-install prompt** ("Tilføj til hjemmeskærm") | Web | Med | Lo |

**Klar til at bygge nu:** sleep timer, playback speed, recently-played section.

---

## Phase 2 — Shared systems (build once, benefit both)

| # | Feature | Platform | Impact | Effort |
|---|---|---|---|---|
| 7 | **Genre-tagging fra Twitch-tags** (tag→genre-kort, filter + "by genre") | App + Web | High | Med-Hi |
| 8 | **Synkroniserede resumes** via API (recently-played + per-artist) | App + Web | High | Med |
| 9 | **Sidst-lyttet / history-server** (sandheds-API der driver begge) | API | Med-Hi | Med |
| 10 | **Del-/embed-URL pr. track** (share + embeddable widget) | Web + App | Med-Hi | Med |

---

## Phase 3 — Community & discovery

| # | Feature | Platform | Impact | Effort |
|---|---|---|---|---|
| 11 | **"Lige nu på ST" widget / embed** for DJ's Twitch-side | Web | Med-Hi | Med |
| 12 | **Top-stats** (mest spillede DJ, nyeste-set, streak) | Web + App | Med | Lo-Med |
| 13 | **Kollaborativ afspilningsliste** (users kuraterer sets) | Web + App | Med-Hi | Hi |
| 14 | **Notifikationer**: "din favorit-DJ er live" | App | High | Med |

---

## Phase 4 — Polish & power users

| # | Feature | Platform | Impact | Effort |
|---|---|---|---|---|
| 15 | **Egalizer / lyd-indstillinger** | App | Med | Med |
| 16 | **Kø-styring i player** (se + reorder kommende tracks) | App | Med-Hi | Med |
| 17 | **Chromecast / CAST til enhed** | App | Med-Hi | Hi |
| 18 | **Flere AA-roots / AA-nylige** | App | Med | Lo-Med |
| 19 | **Automatisk / regelbaseret metadata** (DJ-bio, billeder, beskrivelser) | API | Med | Med |

---

## Planlagt rækkefølge (næste trin)

1. Sleep timer
2. Recently-played / Fortsæt sektion
3. Playback speed
4. Genre-tagging (Twitch)
5. Synkroniserede resumes (API)

---

> **Valg-regel:** indsæt kun features vi reelt vil bygge snart. Fjern/sæt "backlog"-markering på resten. Revideres ved hver session.