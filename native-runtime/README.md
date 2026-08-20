# Official FFmpeg Android runtime

This directory builds Android shared libraries only from the signed source release published by
the FFmpeg project. It does not clone, depend on, or redistribute FFmpegKit/FFmpegKitNext.

The immutable inputs live in `ffmpeg.lock.json`: FFmpeg tag/commit, source and signature hashes,
release-key fingerprint, Google NDK revision/archive hash, ABI list, and configure policy. The
default `core-lgpl` profile intentionally omits GPL/nonfree flags and external codec libraries.
The single runner patch is hash-checked and applied with `--fuzz=0`; an inexact hunk aborts the
build.

From Linux or WSL2:

```bash
bash ./native-runtime/scripts/build-android.sh
```

From Windows PowerShell:

```powershell
./scripts/build-official-ffmpeg.ps1
```

The generated `native-runtime/prebuilt/` directory contains headers, four `jniLibs/<abi>` trees,
pkg-config metadata, and `manifest.json` with exact artifact hashes. It is ignored by Git because a
release must publish binaries together with its exact manifest, signed source bundle, notices, and
SBOM rather than silently mixing generated files into source history.
The Gradle native-engine module embeds the exact manifest at
`assets/ffmpeg-sdk/manifest.json` in its AAR and downstream APK/AAB packages.

The build enables official FFmpeg JNI and MediaCodec support and requests 16 KiB ELF LOAD alignment,
RELRO, immediate binding, stack protection, and FORTIFY. The verification step rejects a missing
RELRO/NOW flag, executable stack, TEXTREL, RPATH/RUNPATH, wrong SONAME, undeclared dependency,
artifact-hash mismatch, or LOAD alignment below 16 KiB.

`--disable-network` is deliberate: the core runtime does not open arbitrary URLs. Android code must
stage an explicitly permitted HTTPS resource through platform TLS into app-owned storage before it
enters native parsing. This keeps the official-only profile from silently acquiring another TLS
library and preserves the SDK's network policy boundary.
