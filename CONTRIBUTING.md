# Contributing

Keep the project boundary narrow: Android task semantics belong here; general FFmpeg changes belong
upstream. Do not add a public API that exposes FFmpegKit classes or accepts an interpolated command
string.

FFmpeg changes should be proposed to the [main FFmpeg repository](https://git.ffmpeg.org/ffmpeg.git).
Do not describe this project, its maintainers, packages, or releases as official FFmpeg work. Keep
the non-affiliation statement in `README.md`, `NOTICE`, and `TRADEMARKS.md` intact.

Every behavior change should include a planner/serialization test. Native changes additionally need
the exact runtime build manifest, license delta, 16 KB report, and relevant device-corpus evidence.

Before opening a change:

```powershell
.\gradlew.bat test lint assembleDebug assembleRelease
```

Commit generated binary runtime artifacts only in a dedicated, reviewed release process. Normal
pull requests must not add AARs, APKs, `.so` files, private media, credentials, signing keys, or
unredistributable codec samples.
