# Verification report

Date: 2026-08-20

Scope: signed upstream source, Android cross-build, JVM/Android build, static package evidence, and
one physical Android device smoke run.

## Result

The repository now builds its native runtime from the signed FFmpeg `n9.0.1` release without an
FFmpegKit, FFmpegKitNext, or community-binary dependency. Four Android ABIs produced 36 verified
shared libraries. Clean Gradle tests, Lint, debug/release APKs, a release AAB, and release AARs all
completed successfully.

This is engineering-preview evidence. One Qualcomm device has positive MediaCodec execution
evidence; the broader device matrix, real document providers, long-running thermal behavior,
native-crash isolation, the final corresponding-source bundle, and an SBOM remain release gates.

## Official source and toolchain chain

| Input | Locked evidence |
| --- | --- |
| Canonical Git | `https://git.ffmpeg.org/ffmpeg.git` |
| Official GitHub mirror | `https://github.com/FFmpeg/FFmpeg.git` |
| FFmpeg tag / commit | `n9.0.1` / `bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa` |
| Signed release archive | `ffmpeg-9.0.1.tar.xz` |
| Archive SHA-256 | `cf38e0e28c7e5605942c4a77755349b0145804a397af37eb1fb4c77cb237f635` |
| Release-key fingerprint | `FCF986EA15E6E293A5644F10B4322F04D67658D8` |
| Signature time / `SOURCE_DATE_EPOCH` | `2026-08-12 03:42:43 UTC` / `1786506163` |
| Android NDK | `29.0.14206865` (`r29`, Linux x86_64) |
| NDK archive SHA-256 | `4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf` |
| Android API baseline | 24 |
| Runner patch SHA-256 | `2d6907152d3bfcc581f36a992f73896a28cc6748cf2fa314e36d4c270790d712` |

The build downloaded the archive, detached signature, release key, and NDK only through their
locked HTTPS URLs; every download was checked before use. An empty temporary GnuPG home imported
only the hash- and fingerprint-pinned FFmpeg release key. GnuPG reported a good signature from the
exact fingerprint. Its expected local trust-database warning does not change that cryptographic
check; no Web-of-Trust assertion is made.

The runner patch applied as six exact hunks with `--fuzz=0`. It changes no codec, demuxer, muxer,
filter, or protocol implementation. It exposes one-shot execute/cancel entry points and disables
terminal/signal side effects that are inappropriate inside an Android process.

## Native runtime

Profile: `core-lgpl`, API 24, no GPL, nonfree, autodetected, or external codec libraries. JNI,
MediaCodec, pthreads, and zlib are enabled; FFmpeg network protocols are disabled. Android HTTPS
input is staged by the Kotlin layer through platform TLS under an explicit policy.

Each ABI contains the seven dynamically linked FFmpeg libraries plus
`libffmpeg_sdk_cli.so` and `libffmpeg_sdk_bridge.so`:

| ABI | Libraries | Bytes |
| --- | ---: | ---: |
| `arm64-v8a` | 9 | 22,839,912 |
| `armeabi-v7a` | 9 | 20,450,664 |
| `x86_64` | 9 | 25,674,456 |
| `x86` | 9 | 24,878,124 |
| **Total** | **36** | **93,843,156** |

The NDK r29 verifier re-read the final manifest and every file and passed all of these checks:

- exact four-ABI by nine-library closure, size, and SHA-256;
- correct architecture and SONAME;
- every ELF LOAD segment aligned to at least `0x4000` (16 KiB);
- GNU_RELRO, immediate binding, and non-executable stack;
- no TEXTREL and no RPATH/RUNPATH;
- every `DT_NEEDED` entry resolved by a packaged library or a declared Android system library;
- no dependency on `libc++_shared.so`;
- CLI exports only the two intended runner functions; the bridge exports only `JNI_OnLoad` and the
  ten JNI entry points;
- no transient `/home/user` or randomized build path embedded in the 36 binaries.

After regenerating the patch to enforce exact context, all 36 binary hashes remained identical to
the prior semantically equivalent build. A separate x86 build in a different work/output directory
then reproduced all nine x86 SHA-256 values exactly. This is positive reproducibility evidence for
x86 on this host, not yet a multi-host reproducible-build claim.

## JVM, Android, and Java API checks

The clean command was:

```powershell
./gradlew.bat --no-daemon clean test lint assembleDebug assembleRelease bundleRelease `
  :ffmpeg-sdk-core:generatePomFileForMavenPublication `
  :ffmpeg-sdk-android:generatePomFileForReleasePublication `
  :ffmpeg-sdk-engine-native:generatePomFileForReleasePublication
```

Result: `BUILD SUCCESSFUL` in 2m 56s; 285 tasks were executed or restored from the clean build's
dependency cache. Current-module reports contain 34 tests, zero failures, zero errors, and zero
skips:

| Area | Tests |
| --- | ---: |
| Core planner, typed recipes, JSON, orchestration, limits | 26 |
| Java `CompletableFuture`/cancellation facade | 2 |
| Android codec request validation | 3 |
| Official native-runtime policy | 3 |
| **Total** | **34** |

Debug Lint reports for the Android platform module, official native engine, and sample app contain
zero issues. R8 kept `NativeBindings` and `NativeLogCallback` under their exact JNI names. `javap`
confirmed Java overloads for `OfficialFfmpegEngine(Context)`, `FfmpegJavaSdk`, probe, execute, and
task cancellation. The native-engine POM contains both Apache-2.0 and LGPL-2.1-or-later entries.

## Package closure and 16 KiB evidence

The release AAR, both APKs, and release AAB were opened independently. Every package contained
exactly the expected 36 libraries, and every embedded library's length and SHA-256 matched the
native manifest. Every package also contained an identical
`assets/ffmpeg-sdk/manifest.json` (under `base/` in the AAB).

`zipalign -c -P 16 4` passed for both APKs. Their extracted libraries also passed 16 KiB LOAD and
GNU_RELRO checks. The primary native inspection used NDK r29; the Windows APK extraction check used
the locally installed NDK 28.2 LLVM reader, then exact package hashes bound those files back to the
r29-verified originals. The release AAB's `BundleConfig.pb`, decoded with bundletool 1.18.3,
contains `uncompress_native_libraries.enabled: true` and
`alignment: PAGE_ALIGNMENT_16K`.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Runtime manifest | 13,298 | `1436e066af3bb3c5afc25cc623d633593095a4b1d0d07d0ac8aaf83f96414385` |
| Core JAR | 292,199 | `dbbbf7a0abe6b1e399829a569853c2260e657bcc2e0edc79e434315396f06ec9` |
| Android release AAR | 34,002 | `ea1d3de46edc47c4d2aa5b38e2ca2f3bb2c0cba0f58298c331751eece9c22973` |
| Official native release AAR | 43,256,493 | `753d6694e8f522a01e95252b33c3da912e7a2ed1fe9b3a91ff1b547bd9928aa8` |
| Sample debug APK | 97,244,176 | `6557e3c913f35bfd3373e2bc7797689d54a71a85f7fad5daa80b2d135e529b54` |
| Sample release APK (unsigned) | 94,382,708 | `95be98ff774acc76b219855b7c41295e9c4bf3add822ffdadca248f1ecc5a6f0` |
| Sample release AAB | 43,743,860 | `2e1d625919bb3cf37d97caa3b3c075ef9199999cd8270754a0bd7fb10f3c9f30` |

These hashes identify this local snapshot. Signing an APK/AAB or rebuilding Kotlin artifacts can
legitimately change their container hashes; the embedded native manifest remains the native
identity record.

## Host recipe smoke corpus

`scripts/verify-recipes.ps1` passed probe, remux, H.264, HEVC, FLAC extraction, thumbnail,
waveform, subtitle burn, trim, concat, and full-decode checks with host FFmpeg 8.1. This validates
the planner's command/filter semantics. The host executable is a GPL build with external libraries,
so this does **not** prove that an Android device or the `core-lgpl` component set can execute every
recipe. In particular, the default official-only profile intentionally lacks libass, libx264, and
libx265; capability preflight rejects those unavailable paths before resource access.

## Physical Android device smoke

Device: OPPO Reno5 K 5G (`PEGM10`), Android 13/API 33, `arm64-v8a`, Qualcomm MediaCodec, 4 KiB
system page size. The debug-only `DeviceSelfTestActivity` ran this sequence without a document
picker:

1. Load the packaged official FFmpeg 9.0.1 LGPL runtime through JNI.
2. Generate a two-second 320x240 MPEG-4/AAC Matroska input with the native FFmpeg runner.
3. Probe it through the public-libav JNI path.
4. Submit a typed Kotlin H.264/AAC MP4 job using `h264_mediacodec`.
5. Probe the output and write a machine-readable report.
6. Pull the MP4 to Windows, inspect it with host `ffprobe`, and fully decode it with host FFmpeg.

The first run was useful negative evidence: the device rejected the default `yuv420p` encoder
input and FFmpeg requested NV12. The planner was changed to emit `-pix_fmt nv12` for every
MediaCodec video attempt and covered by a unit test across transcode, subtitle, and concat plans.
The second run passed on attempt 1.

| Device output evidence | Value |
| --- | --- |
| FFmpeg / license | `9.0.1` / `LGPL` |
| Video | H.264, 320x240, host-decoded `yuv420p` |
| Audio | AAC, 48 kHz, mono |
| Duration / size | 2.005333 s / 171,499 bytes |
| Bitrate | 684,171 bit/s |
| SHA-256 | `41d15989f05f5d0e0c562122fbd6f3f4c2e7ff56bcd38a28234febf57a2866e1` |
| Independent full decode | passed with zero FFmpeg errors |

## Not verified in this snapshot

- Loading and executing the JNI runtime on Android versions/devices beyond the single API 33
  Qualcomm smoke device, including a 16 KiB system image.
- MediaCodec availability and accepted profile/level/size/bitrate combinations beyond the tested
  320x240 H.264 NV12 request.
- Seekability, revocation, full-storage, truncation, and commit behavior across supported document
  providers. Source implements FD seek probing, bounded fallback staging, and commit-after-success,
  but the provider matrix has not run.
- Cancellation timing under active hardware/software encode, lifecycle transitions, and thermal or
  4K stress.
- Survival of a native crash; the current adapter is in-process and an isolated worker is a beta
  gate.
- DNS-rebinding validation for explicitly enabled staged HTTPS input.
- Malformed-media golden corpus, fuzzing, and independent security review.
- Final SPDX/CycloneDX SBOM, exact corresponding-source distribution, LGPL texts/notices inside the
  release bundle, signed artifacts, provenance attestation, and rollback drill.

Until those gates have evidence, the correct label remains **engineering preview**, not production
GA.
