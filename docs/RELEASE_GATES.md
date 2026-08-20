# Release gates

`0.2.0-SNAPSHOT` is a buildable foundation, not a production guarantee. Do not label a release GA
until every required gate has an attached artifact or report.

## Required for 0.2.0 alpha

- [x] Stable, engine-neutral Kotlin task model with schema version.
- [x] Argument-array execution; no shell interpolation.
- [x] SAF input/output adapter and session-specific cancellation.
- [x] Runtime FFmpeg major allow-list and component discovery.
- [x] Explicit LGPL/GPL encoder gate.
- [x] Android MediaCodec capability survey.
- [x] Typed probe/remux/H.264/HEVC/audio/thumbnail/waveform/subtitle/trim/concat surface.
- [x] Unit-tested planner, orchestration, network preflight, progress, engine parsing, Android
  requests, limits, staging paths, MediaCodec NV12 arguments, and both JSON schemas (34 tests).
- [x] Release AARs plus sample debug/release APK and release AAB build with API 36.
- [x] Pin signed official FFmpeg 9.0.1 source, commit, release key, hashes, NDK r29, flags, and four
  Android ABIs in a reproducible LGPL recipe.
- [ ] Generate and publish CycloneDX/SPDX SBOM and exact source bundle.
- [x] Build and verify the source-built runtime's native `.so` files for SONAME, 16 KB ELF
  alignment, GNU_RELRO, and absence of TEXTREL.
- [x] Verify source-built debug/release APK 16 KB ZIP/ELF alignment and release AAB
  `PAGE_ALIGNMENT_16K` after all four ABI artifacts are packaged.

## Required for beta

- [ ] Isolated-process execution service and process-death result.
- [x] File-descriptor seekability probe plus bounded cache staging for non-seekable SAF providers.
- [x] Commit-after-success output staging and failure/cancel cleanup in the in-process adapter.
- [ ] Validate seekability, replacement atomicity, truncation, revocation, and commit failure across
  the supported document-provider matrix.
- [ ] Foreground service / WorkManager integration sample with recovery token.
- [x] Staged HTTP(S) network policy with scheme/host controls, redirect limits, timeouts,
  non-public-address checks, and bounded downloads.
- [ ] Validate DNS rebinding behavior and define separately audited direct-FFmpeg/HLS/DASH policy.
- [x] Source-level resource limits: duration, total duration, pixels, output bytes, staging bytes,
  threads, probe streams/JSON, and concurrent sessions.
- [x] Host FFmpeg 8.1 smoke corpus for probe, remux, H.264/HEVC, audio, thumbnail, waveform,
  subtitles, trim, concat, and full output decode.
- [x] Android 13/API 33 arm64 Qualcomm PEGM10 smoke: official JNI runtime generation, typed
  `h264_mediacodec` NV12 transcode, native probe, artifact pull, and independent full decode.
- [ ] Android golden corpus for malformed media, cancellation, full storage, provider failure, and
  all supported recipes.
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
