# Local verification report

Date: 2026-08-20
Host: Windows 11, x86_64
Scope: SDK JVM/Android build and static inspection of the evaluation runtime. No Android device was
connected, so this report does not claim native execution or MediaCodec behavior on a device.

## Toolchain

| Component | Version |
| --- | --- |
| JDK | Microsoft OpenJDK 17.0.20 |
| Gradle | 9.5.0 |
| Android Gradle Plugin | 9.3.1 |
| Kotlin | 2.3.20 |
| Android compile/target SDK | 37 / 36 |
| Android Build Tools | 36.0.0 |
| Android NDK | 29.0.14206865 |

The Gradle wrapper pins the official distribution SHA-256:
`553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746`.

## Commands and results

```powershell
.\gradlew.bat --no-daemon test lint assembleDebug assembleRelease
```

Result: **PASS** from a clean, offline dependency build. Core planner/orchestration/serialization
tests: 11 passed. Android lint: zero issues in
`ffmpeg-sdk-android`, `ffmpeg-sdk-engine-ffmpegkit`, and `sample-app`. Debug APK and R8-minified
release APK both assembled.

The resolved build dependency graph was written to `gradle/verification-metadata.xml` with SHA-256
checksums. The same complete command then passed with `--offline`, so the snapshot builds under
strict dependency verification from the captured cache without network resolution.

```powershell
.\scripts\verify-16kb.ps1 `
  -Apk .\sample-app\build\outputs\apk\debug\sample-app-debug.apk `
  -AndroidSdkRoot <workspace-sdk> `
  -NdkVersion 29.0.14206865

.\scripts\verify-16kb.ps1 `
  -Apk .\sample-app\build\outputs\apk\release\sample-app-release-unsigned.apk `
  -AndroidSdkRoot <workspace-sdk> `
  -NdkVersion 29.0.14206865
```

Result: **PASS** for both APKs. `zipalign -P 16` succeeded. All 20 native libraries across
`arm64-v8a` and `x86_64` had ELF LOAD alignment of at least `2**14` and a `GNU_RELRO` segment.

## SDK artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `ffmpeg-sdk-core-0.1.0-SNAPSHOT.jar` | 159,112 | `C5C934EA08A5D4F6CEF6240838ED313B79CF1453A4C6330B80096323F8AB04C6` |
| `ffmpeg-sdk-android-release.aar` | 17,245 | `16AF1989C009705E5FC88ADA44DBC1F89A66775FC5746FFD0261E4002C0BA688` |
| `ffmpeg-sdk-engine-ffmpegkit-release.aar` | 33,782 | `A57735083272771146468A53977A419DCDF2A89F2D2C475220396D5D337E861D` |
| `sample-app-debug.apk` | 71,984,679 | `6937BAD727C3DD3594409609692A4BDDDEC3CFB4F0DC75A48D5B51C155E085C6` |
| `sample-app-release-unsigned.apk` | 69,121,836 | `D9EFC6DCE298F40FC937B07B68F5B689BBE395A9345B38DB000F5534C1162D37` |

These hashes identify this local snapshot only; the APKs contain an evaluation runtime and are not
release deliverables.

## Evaluation runtime intake

Artifact: `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7`

| Property | Observed value |
| --- | --- |
| AAR bytes | 30,962,496 |
| AAR SHA-256 | `C3CBC81D498175FD2AA69EE2DFE7DAFBF519052A96283C2568FE5B3B16618456` |
| POM SHA-256 | `91CB439F013D7AC4F60C3189E008523694FE100AFDAA1348A30EE2A16285DF2A` |
| ABIs in AAR | `arm64-v8a`, `x86_64` |
| Native libraries | 10 per ABI |
| 16 KB ZIP/ELF + RELRO | Passed in debug and release sample APKs |

Intake findings:

- The artifact was useful for compiling against the real Java API and for static native checks.
- Its POM repeats license/developer metadata.
- Its POM omits required `com.arthenica:smart-exception-java:0.2.1`; R8 release compilation exposed
  the missing class. The sample adds it explicitly for evaluation.
- Its bundled `source.txt` still points to the retired original FFmpegKit wiki and an old
  `open-source@arthenica.com` physical-source offer rather than identifying this artifact's own
  source revision.
- Static alignment success does not establish source correspondence, reproducibility, codec legal
  status, runtime correctness, or device compatibility.

Accordingly, the SDK adapter keeps this dependency `compileOnly`; production must replace it with a
pinned FFmpegKitNext source build.

## Unverified in this environment

- Real FFmpeg/FFprobe execution, cancellation, SAF provider behavior, and output validity.
- Hardware H.264/H.265/VP9/AV1 encoding on any physical device.
- Android 16 KB emulator boot and runtime execution.
- Thermal behavior, background/foreground lifecycle, process death, and long-running jobs.
- Reproducibility and exact corresponding source of the evaluated community AAR.
