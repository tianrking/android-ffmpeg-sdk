# Release gates

`0.1.0-SNAPSHOT` is a buildable foundation, not a production guarantee. Do not label a release GA
until every required gate has an attached artifact or report.

## Required for 0.1.0 alpha

- [x] Stable, engine-neutral Kotlin task model with schema version.
- [x] Argument-array execution; no shell interpolation.
- [x] SAF input/output adapter and session-specific cancellation.
- [x] Runtime FFmpeg major allow-list and component discovery.
- [x] Explicit LGPL/GPL encoder gate.
- [x] Android MediaCodec capability survey.
- [x] Unit-tested planner, orchestration, network preflight, progress, and JSON round trip (11 tests).
- [x] Release AARs and sample debug APK build with API 36.
- [ ] Pin a reproducibly built FFmpegKitNext 8.1.1 / FFmpeg 8.1.2 LGPL runtime.
- [ ] Generate and publish CycloneDX/SPDX SBOM and exact source bundle.
- [x] Verify the evaluation runtime's 20 native `.so` files for 16 KB ELF alignment and GNU RELRO.
- [x] Verify evaluation debug/release APK 16 KB ZIP alignment.
- [ ] Repeat native and APK/AAB checks for the pinned reproducible source-built runtime.

## Required for beta

- [ ] Isolated-process execution service and process-death result.
- [ ] Seekability probe plus cache staging for non-seekable SAF providers.
- [ ] Transactional output: commit on success, best-effort delete on failure/cancel.
- [ ] Foreground service / WorkManager integration sample with recovery token.
- [ ] Network protocol allow-list, redirect limits, timeouts, and local-address blocking.
- [ ] Resource limits: duration, pixels, output bytes, threads, and concurrent sessions.
- [ ] Golden corpus for probe, remux, trim, scale, audio, subtitles, malformed media, cancellation,
  full storage, and provider failure.
- [ ] Android 24/29/33/35/36/37 emulator coverage including a 16 KB image.
- [ ] At least 12 physical devices across Qualcomm, MediaTek, Tensor, Exynos, and low-memory tiers.
- [ ] 30-minute and 4K thermal/foreground-background stress runs.

## Required for 1.0 GA

- [ ] Public compatibility policy and two-minor deprecation window.
- [ ] Reproducible `minimal-lgpl` and `full-lgpl` artifact profiles.
- [ ] GPL artifact isolated by coordinate, documentation, sample, and dependency graph.
- [ ] External security review of parser boundary, Binder protocol, URI handling, and network input.
- [ ] Fuzzing evidence for the supported container/codec surface.
- [ ] Crash-free/job-success/thermal/latency baselines and regression thresholds.
- [ ] License/source-offer review and market-specific codec patent review.
- [ ] Signed artifacts, provenance attestation, checksums, SBOM, and rollback procedure.
