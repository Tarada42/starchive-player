# Release notes

Play Console needs a "What's new" text per release, and it is easy to forget at
upload time. Keep the next one here before building: the same text lives in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, which the Play
Console accepts directly when uploading.

Rules: English only, 500 characters maximum, describe what a user notices — not
commits, not internal file names, no version numbers of libraries unless they
change behaviour.

---

## 1.6.1 (versionCode 20)

Android Auto, rebuilt on Media3. The car now draws the browse list and player
itself: play/pause, +/-30 s on archive tracks, Stop on live streams, favourite
DJs, newest-first sorting, and a Live row inside each DJ folder. Live streams no
longer offer seek or skip controls. Swiping the app away stops playback and
casting, and your last archive set resumes when you reconnect to the car.
Under the hood: Media3 1.11.1, Kotlin 2.2.

## 1.6.0 (versionCode 19) — never uploaded

Media3 1.4.1 to 1.11.1, Kotlin 2.2.10, cast-framework 22.3.1. Superseded by
1.6.1 before submission (the car still advertised skip/seek on live streams).

## 1.5.9 (versionCode 18)

Resume in the car: the app remembers the last archive track and position,
restores it when the car connects (opt-in, asked once), and the car heart starts
from the DJ's favourite state.
