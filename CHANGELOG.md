# Changelog

All notable changes will be documented here. The project follows Semantic Versioning after the
`1.0.0` API freeze.

## 0.2.0-SNAPSHOT — 2026-08-20

- Replaced the FFmpegKit/community runtime route with a signed official FFmpeg `n9.0.1` source
  lock, NDK r29 four-ABI cross-build, hashed runner patch, JNI bridge, native engine, and ELF
  provenance manifest.
- Generalized the core from one `TranscodeJob` to a sealed, versioned `MediaJob` family while
  retaining the v1 transcode JSON codec.
- Added typed recipes for remux, H.264, HEVC, audio extraction/transcode, trim, thumbnail,
  waveform, subtitle burn-in, and multi-segment filter concat.
- Added encoder/filter/muxer/demuxer discovery, a codec/container compatibility matrix, bounded
  public-libav probe JSON/stream capture, and duration/pixel/output/thread resource limits.
- Added structured engine failure categories and a bounded, replaying task-event buffer.
- Added a Java `CompletableFuture`/callback facade with cancellable task handles over the same
  coroutine and native cancellation path.
- Added seekability-aware SAF input staging, seekable auxiliary resources, commit-after-success
  file/content output staging, an aggregate per-session staging budget, and a concurrent-session
  limit.
- Added deny-by-default network staging with scheme/host controls, redirect and timeout limits,
  non-public-address checks, and bounded downloads; direct FFmpeg networking requires a separate
  audited native profile.
- Expanded Android codec requests and snapshots with video/audio ranges and per-format candidate
  filtering.
- Expanded automated coverage from 11 to 34 tests and added a host FFmpeg 8.1 smoke corpus for the
  ten high-value recipe paths.
- Added a debug-only physical-device self-test. Its first Qualcomm run exposed MediaCodec rejecting
  planar `yuv420p`; all MediaCodec plans now request NV12, and the rerun produced a fully decoded
  H.264/AAC MP4 through the official JNI runtime.
- Made the 16 KB ZIP/ELF/RELRO verifier host-portable, embedded the native manifest in every
  package, verified exact AAR/APK/AAB library closure, and wired debug/release checks into CI.

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
- Added platform-specific artifact and Maven metadata checksums required by clean Linux/CI
  dependency resolution.
- Added technical decision, architecture, compliance, security, and release-gate documentation.
