# ALL-FUNCTIONAL Baseline (AA module)

Safe revert base BEFORE the design/polish pass.

- Git tag: `ALL-FUNCTIONAL`
- Commit: `1ae83a2` (Phase 4: favorites + sort persistence)
- Date: 30-10-2026
- Verified on DHU: browse (flat, newest-first, sort), player (-10s/+10s/play-pause/stop), live DJs + High/Low quality, favorites (flat), sort persists across AA reconnect. Phone app plays normally (shared MediaSession).
- All four phases, clean-room, in `com.starchive.player.aa`. Phone/UI code untouched.

## Revert (if the design pass goes wrong)

```
git checkout ALL-FUNCTIONAL -- .
# or reset the working tree fully:
git reset --hard ALL-FUNCTIONAL
```
