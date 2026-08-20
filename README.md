# FFmpeg Android

[![CI](https://github.com/tianrking/android-ffmpeg-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/tianrking/android-ffmpeg-sdk/actions/workflows/ci.yml)

A production-oriented FFmpeg SDK and media-processing runtime integration layer for Android.

> [!IMPORTANT]
> **FFmpeg Android is an independent project.** It is not affiliated with, sponsored by, approved
> by, or endorsed by the [FFmpeg project](https://ffmpeg.org/) or its contributors. It is not an
> official Android distribution of FFmpeg. FFmpeg is a trademark of Fabrice Bellard, originator of
> the FFmpeg project. See [Trademarks and non-affiliation](TRADEMARKS.md).

FFmpeg Android turns media work into versioned Kotlin jobs, cross-compiles the signed upstream
FFmpeg release for Android, resolves `content://` resources without shell interpolation, reports
progress and cancellation through coroutines, and records native provenance and licensing.

## Project status

`0.2.0-SNAPSHOT` is an engineering preview, not a production release.

- The Kotlin core, Java `CompletableFuture` facade, Android capability module, project-owned JNI
  engine, and sample app build.
- Thirty-four JVM/local Android tests cover planning, recipes, serialization, orchestration,
  Java cancellation, runtime policy, limits, and Android request validation.
- A host FFmpeg 8.1 smoke corpus successfully probes, remuxes, exports H.264/HEVC, extracts audio,
  creates thumbnails/waveforms, burns subtitles, trims, concatenates, and fully decodes outputs.
- The source-built release AAR, debug/release APKs, and release AAB contain the exact 36 locked
  native libraries and embedded provenance manifest. Both APKs pass 16 KB ZIP/ELF checks, and the
  AAB declares `PAGE_ALIGNMENT_16K`.
- An Android 13/API 33 arm64 Qualcomm device completed the debug device self-test: the official
  runtime generated MPEG-4/AAC media, the typed SDK transcoded it through `h264_mediacodec` to
  H.264/AAC MP4, native probe validated it, and an independent host FFmpeg fully decoded the pulled
  output. Broader device/SAF, lifecycle, thermal, 16 KiB-system, and process-death matrices remain
  release gates.
- The runtime lock pins official FFmpeg `n9.0.1`, its signed release archive, NDK r29, four ABIs,
  configure flags, the runner patch, bridge sources, and every produced library hash.

The exact boundary is recorded in [`docs/VERIFICATION_REPORT.md`](docs/VERIFICATION_REPORT.md) and
[`docs/RELEASE_GATES.md`](docs/RELEASE_GATES.md).

## Why this project exists

The FFmpeg project publishes source code rather than an official Android AAR, Maven package, or
Kotlin SDK. This repository therefore consumes the signed official source directly and owns only
the Android build recipe, a two-symbol fftools runner patch, and the JNI/Kotlin integration layer.
It does not consume FFmpegKit, FFmpegKitNext, or a community FFmpeg binary.

FFmpeg Android focuses on that product boundary. It is intentionally not a renamed FFmpeg binary,
an FFmpeg source fork, or another API that accepts only an interpolated command string.

Use it when an Android application needs several of the following:

- public-libav media metadata, broad demux/mux support, stream copy, subtitles, or FFmpeg filters;
- reliable `content://` input and output instead of storage-path workarounds;
- explicit hardware/software encoder selection with observable retry behavior;
- cancellable and structured jobs instead of shell command construction;
- an auditable LGPL/GPL runtime profile and reproducible native build evidence.

Prefer [Jetpack Media3 Transformer](https://developer.android.com/media/media3/transformer) for a
conventional Android-only trim, crop, effect, or export flow when its supported formats are enough.
FFmpeg Android should earn the additional native size and attack surface.

## Current capabilities

- Versioned, serializable Kotlin transcode jobs.
- A sealed typed job family for transcode, thumbnail, waveform, subtitle burn-in, and concat.
- Typed constructors for remux, H.264/HEVC export, audio extraction/transcode, and trim.
- Engine-neutral planning and execution contracts.
- Structured argument arrays with no shell interpolation.
- `FilePath`, `ContentUri`, and explicitly opted-in `NetworkUrl` resources.
- Public `libavformat` format and stream inspection with bounded JSON.
- Kotlin coroutine/Flow APIs plus a Java `CompletableFuture`, callback, and cancellable task facade.
- Coroutine events for attempts, logs, progress, completion, failure, and cancellation.
- Explicit hardware/software encoder candidates without silent codec changes.
- Runtime FFmpeg major allow-list plus encoder, decoder, filter, muxer, and demuxer discovery.
- Runtime build-configuration checks for GPL and nonfree options.
- Android `MediaCodecList` capability survey.
- SAF file-descriptor seek probing, bounded fallback staging, and seekable auxiliary resources for
  filters such as `subtitles`.
- Commit-after-success file/content output staging with cleanup after failure or cancellation.
- Per-input duration/pixel limits plus output-byte, thread, aggregate per-session staging-byte, and
  concurrent-session limits.
- Network downloads disabled by default; explicit use is bounded by scheme/host, address,
  redirect, timeout, and byte policies.
- Bounded captured output and session-specific cancellation.

This list describes implemented APIs. It does not replace the device and release evidence required
by [`docs/RELEASE_GATES.md`](docs/RELEASE_GATES.md).

## Modules

| Module | Responsibility | Bundles native FFmpeg? |
| --- | --- | --- |
| `ffmpeg-sdk-core` | Job schema, planner, engine contract, events, retries | No |
| `ffmpeg-sdk-android` | Runtime Android `MediaCodec` capability survey | No |
| `ffmpeg-sdk-engine-native` | JNI runner, probe, capabilities, SAF/network policy | Generated official runtime |
| `sample-app` | SAF input/output and cancellable H.264 MediaCodec export | Generated official runtime |

`native-runtime/ffmpeg.lock.json` is the trust root for the generated runtime. The build verifies
the FFmpeg release PGP signature and every input hash before compilation, then verifies each ELF's
SONAME, 16 KiB LOAD alignment, GNU_RELRO, and absence of TEXTREL.

## Quick start

Until the artifacts are published, depend on the included Gradle modules. The intended Maven
coordinates are:

```kotlin
dependencies {
    implementation("io.github.tianrking.ffmpegsdk:ffmpeg-sdk-core:0.2.0")
    implementation("io.github.tianrking.ffmpegsdk:ffmpeg-sdk-android:0.2.0")
    implementation("io.github.tianrking.ffmpegsdk:ffmpeg-sdk-engine-native:0.2.0")
}
```

Create an explicitly licensed engine and a typed job:

```kotlin
val sdk = FfmpegSdk(OfficialFfmpegEngine(applicationContext))

val job = TranscodeJob(
    input = MediaReference.ContentUri(inputUri.toString()),
    output = MediaReference.ContentUri(outputUri.toString()),
    overwrite = true,
)

val task = sdk.submit(viewModelScope, job)
task.events.collect { event -> /* render progress and attempts */ }
val result = task.result.await()
```

Java uses the same engine and typed jobs without calling suspend functions directly:

```java
try (FfmpegJavaSdk sdk = new FfmpegJavaSdk(new OfficialFfmpegEngine(context))) {
    JavaMediaTask task = sdk.execute(job, event -> render(event));
    task.getFuture().thenAccept(result -> renderResult(result));
}
```

The high-value operations remain typed rather than accepting arbitrary command text:

```kotlin
val remux = MediaRecipes.remux(input, output, Container.MP4, overwrite = true)
val audio = MediaRecipes.extractAudio(input, output, AudioCodec.FLAC, Container.FLAC)
val thumbnail = ThumbnailJob(input = input, output = output, positionMs = 5_000)
val waveform = WaveformJob(input = input, output = output, width = 1_280, height = 320)
val subtitled = SubtitleBurnJob(input = input, subtitles = subtitle, output = output)
val joined = ConcatJob(segments = clips, output = output, targetWidth = 1_920, targetHeight = 1_080)
```

No command is assembled through a shell. Paths, URLs, and SAF URIs remain typed resource arguments
until the selected engine resolves them.

## Defaults and deliberate constraints

- `minSdk 24`, `compileSdk 37`, and `targetSdk 36` in the sample. Target 37 is a separate behavior
  validation gate, not an automatic production claim.
- FFmpeg `n9.0.1` / commit `bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa` is the locked runtime.
- `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86` are built. Production apps should use ABI splits
  or App Bundles rather than a universal APK.
- Network input is denied per job and by the runtime unless explicitly enabled by both. The engine
  defaults to downloading a bounded HTTPS resource into cache; HTTP, host scope, and direct FFmpeg
  protocol access are separate decisions, and direct FFmpeg networking requires a separately
  audited runtime profile because `core-lgpl` is compiled with `--disable-network`.
- Content inputs that cannot seek are copied into a bounded cache file. Outputs are encoded to a
  staging file and committed only after FFmpeg succeeds; a `content://` provider commit is safer
  than direct encoding but cannot be universally atomic across providers. Transactional
  `content://` output therefore requires `overwrite = true` as an explicit acknowledgement;
  filesystem `overwrite = false` uses a no-replace commit.
- H.264/H.265 software fallbacks (`libx264`/`libx265`) are blocked unless the runtime is explicitly
  declared GPL. The SDK does not change the requested codec merely to make a job pass.
- MediaCodec video attempts explicitly request NV12 encoder input. A physical Qualcomm device
  rejected FFmpeg's default planar `yuv420p` input but completed the same H.264 job with NV12.
- The `core-lgpl` build does not enable GPL, nonfree, or external codec libraries. H.264/HEVC/AV1
  hardware encoding comes from FFmpeg's MediaCodec encoders; optional software-codec profiles must
  be separate coordinates with separate license evidence. The official-only profile therefore has
  no libass subtitle filter, libx264, or libx265; typed jobs fail capability preflight instead of
  silently changing behavior.

## Build

Requirements: JDK 17, Android SDK Platform 37, Build Tools 36.0.0, NDK r29, and Linux/WSL2 for
the official native cross-build.

```shell
git clone git@github.com:tianrking/android-ffmpeg-sdk.git
cd android-ffmpeg-sdk
```

```powershell
$env:ANDROID_SDK_ROOT = "C:\path\to\android-sdk"
.\scripts\build-official-ffmpeg.ps1
.\gradlew.bat test lint assembleDebug assembleRelease bundleRelease
```

The PowerShell wrapper invokes `native-runtime/scripts/build-android.sh` in WSL2. Linux users can
run that Bash script directly. Generated headers, all four ABI trees, and `manifest.json` appear in
`native-runtime/prebuilt/` and are packaged by `ffmpeg-sdk-engine-native`.

Run the optional host-FFmpeg recipe smoke corpus (this is not Android device evidence):

```powershell
.\scripts\verify-recipes.ps1
```

Verify a produced APK's ZIP and ELF page alignment:

```powershell
.\scripts\verify-16kb.ps1 `
  -Apk .\sample-app\build\outputs\apk\debug\sample-app-debug.apk `
  -AndroidSdkRoot $env:ANDROID_SDK_ROOT `
  -NdkVersion 29.0.14206865
```

Run the debug-only, intent-driven Android device self-test after installing the debug APK:

```powershell
adb install -r .\sample-app\build\outputs\apk\debug\sample-app-debug.apk
adb shell am start -W `
  -n io.github.tianrking.ffmpegsdk.sample/.DeviceSelfTestActivity
adb shell run-as io.github.tianrking.ffmpegsdk.sample `
  cat files/ffmpeg-sdk-device-self-test.json
```

The test generates real media, performs a typed H.264 MediaCodec transcode through JNI, probes the
output, and writes a machine-readable result. It is compiled only into the debug variant.

## Documentation

- [`docs/DECISION_REPORT.zh-CN.md`](docs/DECISION_REPORT.zh-CN.md) — technical and product decision.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — component, ownership, and trust boundaries.
- [`docs/API.md`](docs/API.md) — resource, policy, event, cancellation, and failure contracts.
- [`docs/DISTRIBUTION_AND_COMPLIANCE.md`](docs/DISTRIBUTION_AND_COMPLIANCE.md) — LGPL/GPL, source,
  patents, SBOM, and runtime profiles.
- [`docs/RELEASE_GATES.md`](docs/RELEASE_GATES.md) — evidence required before production labels.
- [`docs/VERIFICATION_REPORT.md`](docs/VERIFICATION_REPORT.md) — verified and unverified claims.
- [`SECURITY.md`](SECURITY.md) — hostile-input model and update policy.
- [`TRADEMARKS.md`](TRADEMARKS.md) — naming, attribution, and non-affiliation statement.

## Official upstream resources

The following links point to resources operated by, or explicitly identified by, the FFmpeg
project. They are provided for attribution and source verification; linking does not imply
affiliation or endorsement.

| Resource | Official link |
| --- | --- |
| FFmpeg project home | [ffmpeg.org](https://ffmpeg.org/) |
| FFmpeg source releases and verification | [Download FFmpeg](https://ffmpeg.org/download.html) |
| Main FFmpeg Git repository | [`git.ffmpeg.org/ffmpeg.git`](https://git.ffmpeg.org/ffmpeg.git) |
| Officially listed GitHub mirror | [`FFmpeg/FFmpeg`](https://github.com/FFmpeg/FFmpeg) |
| FFmpeg documentation | [ffmpeg.org/documentation.html](https://ffmpeg.org/documentation.html) |
| FFmpeg legal and license guidance | [ffmpeg.org/legal.html](https://ffmpeg.org/legal.html) |
| FFmpeg security advisories | [ffmpeg.org/security.html](https://ffmpeg.org/security.html) |

Related but independent project:

- [Android Media3 Transformer](https://developer.android.com/media/media3/transformer) — official
  Android media transformation API and a planned complementary engine.

## Licensing and naming

The Kotlin/Android SDK source in this repository is licensed under
[Apache License 2.0](LICENSE). FFmpeg and optional codec libraries retain their own
copyrights and licenses. A native FFmpeg build may be LGPL or GPL depending on its configuration;
the Apache license of this repository does not change those obligations and does not grant codec
patent rights.

The name **FFmpeg Android** describes this project's Android integration with FFmpeg. It does not
claim ownership of FFmpeg, official-project status, or endorsement. Always spell the upstream
project name as **FFmpeg**. See [NOTICE](NOTICE) and [TRADEMARKS.md](TRADEMARKS.md).
