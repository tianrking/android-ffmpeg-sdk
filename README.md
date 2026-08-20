# FFmpeg Android

[![CI](https://github.com/tianrking/android-ffmpeg-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/tianrking/android-ffmpeg-sdk/actions/workflows/ci.yml)

A production-oriented FFmpeg SDK and media-processing runtime integration layer for Android.

> [!IMPORTANT]
> **FFmpeg Android is an independent project.** It is not affiliated with, sponsored by, approved
> by, or endorsed by the [FFmpeg project](https://ffmpeg.org/) or its contributors. It is not an
> official Android distribution of FFmpeg. FFmpeg is a trademark of Fabrice Bellard, originator of
> the FFmpeg project. See [Trademarks and non-affiliation](TRADEMARKS.md).

FFmpeg Android turns media work into versioned Kotlin jobs, resolves Android `content://`
resources without shell interpolation, reports progress and cancellation through coroutines,
checks device codec capabilities, and keeps the native engine replaceable so applications retain
control of binary provenance and licensing.

## Project status

`0.1.0-SNAPSHOT` is an engineering preview, not a production release.

- The Kotlin core, Android capability module, FFmpegKit-compatible adapter, and sample app build.
- Eleven core tests pass and Android Lint reports no issues in the Android modules.
- The evaluation APKs pass static 16 KB ZIP/ELF alignment and GNU RELRO checks.
- No Android device was connected for this snapshot. Native execution, output validity, device
  codecs, SAF providers, lifecycle, thermal behavior, and process death remain release gates.
- The sample uses an evaluation runtime. Production consumers must supply a pinned, audited,
  reproducibly built native runtime.

The exact boundary is recorded in [`docs/VERIFICATION_REPORT.md`](docs/VERIFICATION_REPORT.md) and
[`docs/RELEASE_GATES.md`](docs/RELEASE_GATES.md).

## Why this project exists

The FFmpeg project publishes source code rather than an official Android AAR, Maven package, or
Kotlin SDK. Existing Android wrappers make FFmpeg commands callable, but an application still owns
Android-specific concerns such as SAF, non-seekable providers, process lifecycle, hardware codec
variance, 16 KB page sizes, binary provenance, and LGPL/GPL compliance.

FFmpeg Android focuses on that product boundary. It is intentionally not a renamed FFmpeg binary,
an FFmpeg source fork, or another API that accepts only an interpolated command string.

Use it when an Android application needs several of the following:

- FFprobe metadata, broad demux/mux support, stream copy, subtitles, or FFmpeg filters;
- reliable `content://` input and output instead of storage-path workarounds;
- explicit hardware/software encoder selection with observable retry behavior;
- cancellable and structured jobs instead of shell command construction;
- an auditable LGPL/GPL runtime profile and reproducible native build evidence.

Prefer [Jetpack Media3 Transformer](https://developer.android.com/media/media3/transformer) for a
conventional Android-only trim, crop, effect, or export flow when its supported formats are enough.
FFmpeg Android should earn the additional native size and attack surface.

## Current capabilities

- Versioned, serializable Kotlin transcode jobs.
- Engine-neutral planning and execution contracts.
- Structured argument arrays with no shell interpolation.
- `FilePath`, `ContentUri`, and explicitly opted-in `NetworkUrl` resources.
- FFprobe format and stream inspection.
- Coroutine events for attempts, logs, progress, completion, failure, and cancellation.
- Explicit hardware/software encoder candidates without silent codec changes.
- Runtime FFmpeg major allow-list and encoder/decoder discovery.
- Runtime build-configuration checks for GPL and nonfree options.
- Android `MediaCodecList` capability survey.
- SAF resolution through the FFmpegKit-compatible protocol bridge.
- Bounded captured output and session-specific cancellation.

This list describes implemented APIs. It does not replace the device and release evidence required
by [`docs/RELEASE_GATES.md`](docs/RELEASE_GATES.md).

## Modules

| Module | Responsibility | Bundles native FFmpeg? |
| --- | --- | --- |
| `ffmpeg-sdk-core` | Job schema, planner, engine contract, events, retries | No |
| `ffmpeg-sdk-android` | Runtime Android `MediaCodec` capability survey | No |
| `ffmpeg-sdk-engine-ffmpegkit` | Adapter for the `com.arthenica.ffmpegkit` API | No (`compileOnly`) |
| `sample-app` | SAF input/output and cancellable H.264 export | Evaluation runtime only |

The adapter compiles against a compatible community artifact to verify the Java API, but the
published SDK POM does not pull that native artifact into consumer applications. A production app
should build and pin its own [FFmpegKitNext](https://github.com/arthenica/ffmpeg-kit-next) AAR.
FFmpegKitNext is also an independent project and is not part of the FFmpeg project.

## Quick start

Until the artifacts are published, depend on the included Gradle modules. The intended Maven
coordinates are:

```kotlin
dependencies {
    implementation("io.github.tianrking.ffmpegsdk:ffmpeg-sdk-core:0.1.0")
    implementation("io.github.tianrking.ffmpegsdk:ffmpeg-sdk-android:0.1.0")
    implementation("io.github.tianrking.ffmpegsdk:ffmpeg-sdk-engine-ffmpegkit:0.1.0")

    // The application chooses this runtime. Prefer a locally built, pinned FFmpegKitNext AAR.
    implementation(files("libs/ffmpeg-kit-next-android-8.1.1.aar"))
}
```

Create an explicitly licensed engine and a typed job:

```kotlin
val sdk = FfmpegSdk(
    FfmpegKitEngine(
        applicationContext,
        FfmpegKitRuntimePolicy(
            runtimeLicense = RuntimeLicense.LGPL,
            allowedFfmpegMajorVersions = setOf(8),
            distributionLabel = "our reproducible FFmpegKitNext build",
        ),
    ),
)

val job = TranscodeJob(
    input = MediaReference.ContentUri(inputUri.toString()),
    output = MediaReference.ContentUri(outputUri.toString()),
    overwrite = true,
)

val task = sdk.submit(viewModelScope, job)
task.events.collect { event -> /* render progress and attempts */ }
val result = task.result.await()
```

No command is assembled through a shell. Paths, URLs, and SAF URIs remain typed resource arguments
until the selected engine resolves them.

## Defaults and deliberate constraints

- `minSdk 24`, `compileSdk 37`, and `targetSdk 36` in the sample. Target 37 is a separate behavior
  validation gate, not an automatic production claim.
- FFmpeg `8.x` is the audited runtime line for v0.1. FFmpeg `9.x` remains canary-only until its
  Android wrapper and device matrix pass the same gates.
- `arm64-v8a` and `x86_64` are the sample defaults. Production apps should use ABI splits or App
  Bundles rather than a universal APK.
- Network input is denied per job and by the runtime unless explicitly enabled by both.
- H.264/H.265 software fallbacks (`libx264`/`libx265`) are blocked unless the runtime is explicitly
  declared GPL. The SDK does not change the requested codec merely to make a job pass.
- The engine runtime is not transitively bundled. Binary provenance remains an application
  decision until reproducible project-owned runtime profiles are released.

## Build

Requirements: JDK 17, Android SDK Platform 37.0, Build Tools 36.0.0, and NDK r29 for the full native
verification script.

```shell
git clone git@github.com:tianrking/android-ffmpeg-sdk.git
cd android-ffmpeg-sdk
```

```powershell
$env:ANDROID_SDK_ROOT = "C:\path\to\android-sdk"
.\gradlew.bat test lint assembleDebug assembleRelease
```

Verify a produced APK's ZIP and ELF page alignment:

```powershell
.\scripts\verify-16kb.ps1 `
  -Apk .\sample-app\build\outputs\apk\debug\sample-app-debug.apk `
  -AndroidSdkRoot $env:ANDROID_SDK_ROOT `
  -NdkVersion 29.0.14206865
```

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

Related but independent projects:

- [FFmpegKitNext](https://github.com/arthenica/ffmpeg-kit-next) — source-build wrapper used by the
  current engine adapter; not affiliated with the FFmpeg project.
- [Android Media3 Transformer](https://developer.android.com/media/media3/transformer) — official
  Android media transformation API and a planned complementary engine.

## Licensing and naming

The Kotlin/Android SDK source in this repository is licensed under
[Apache License 2.0](LICENSE). FFmpeg, FFmpegKitNext, and optional codec libraries retain their own
copyrights and licenses. A native FFmpeg build may be LGPL or GPL depending on its configuration;
the Apache license of this repository does not change those obligations and does not grant codec
patent rights.

The name **FFmpeg Android** describes this project's Android integration with FFmpeg. It does not
claim ownership of FFmpeg, official-project status, or endorsement. Always spell the upstream
project name as **FFmpeg**. See [NOTICE](NOTICE) and [TRADEMARKS.md](TRADEMARKS.md).
