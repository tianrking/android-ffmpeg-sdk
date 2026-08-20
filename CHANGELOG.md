# Changelog

All notable changes will be documented here. The project follows Semantic Versioning after the
`1.0.0` API freeze.

## 0.1.0-SNAPSHOT — 2026-08-20

- Established the public project identity as FFmpeg Android in the `android-ffmpeg-sdk` repository,
  with official upstream links and an explicit non-affiliation and trademark statement.
- Added versioned typed transcode jobs and JSON serialization.
- Added explicit MediaCodec/software encoder planning with LGPL/GPL gates.
- Added engine-neutral coroutine execution, progress, cancellation, and same-codec retry events.
- Added Android `MediaCodecList` device capability survey.
- Added replaceable FFmpegKit-compatible engine with SAF resources, FFprobe, runtime major guard,
  build-configuration attestation, bounded output, and network deny-by-default.
- Added API 37 / target 36 sample application using Android document providers.
- Added dependency checksum verification, build/lint/R8 CI, and 16 KB ELF/ZIP/RELRO scripts.
- Added technical decision, architecture, compliance, security, and release-gate documentation.
