# Architecture and trust boundaries

## Data path

```text
MediaJob (versioned JSON; transcode / thumbnail / waveform / subtitle / concat)
        |
        v
DefaultCommandPlanner ---- EngineDescriptor + compiled FFmpeg capabilities
        |
        v
ExecutionPlan [explicit encoder attempts]
        |
        v
FfmpegEngine ---- resource resolver ---- file / staged content:// / bounded HTTP(S)
        |                    |
        |                    +---- seekability + staging + commit-after-success
        |
        +---- logs + statistics ---- ExecutionEvent (Flow)
        |
        v
EngineResult ---- success / retry same codec / failure / cancellation
```

The public core has no dependency on a wrapper vendor or native artifact coordinate. The only
active engine binds this repository's JNI API to libraries built from the signed FFmpeg release.

## Boundaries

### Core SDK

- Owns the versioned task model and validation.
- Produces argument arrays; never invokes a shell and never concatenates resource strings.
- Represents a filter filename as a composite argument with a typed resource segment; only the
  engine applies FFmpeg filter-value escaping after resolving that resource.
- Makes fallback attempts observable and preserves the requested codec.
- Applies runtime license policy before an encoder is selected.
- Validates container/codec combinations and optional duration, pixel, byte, and thread limits.
- Has no Android or native dependency and is unit-testable on the JVM.

### Android platform module

- Describes what `MediaCodecList` reports on the current device.
- Filters candidates with `CodecCapabilities.isFormatSupported()` and exposes advertised video and
  audio ranges.
- Does not claim that an advertised codec will complete a particular file.
- Does not infer FFmpeg compile-time capabilities.

### Official native engine

- Resolves typed resources into engine-specific handles.
- Owns native session cancellation and converts native callbacks into stable events.
- Verifies the allowed FFmpeg major and audits `avcodec_configuration()` at runtime; the
  `core-lgpl` engine rejects `--enable-gpl` and `--enable-nonfree`.
- Discovers actual FFmpeg encoders, decoders, filters, muxers, and demuxers with independent
  known/unknown state, so one failed listing does not disable preflight for the others.
- Applies a second, runtime-wide network input gate in addition to the per-job opt-in.
- Stages non-seekable SAF inputs and resources that filters must open as local seekable files.
- Stages output and commits it only after the native command succeeds; filesystem commits use a
  same-directory operation, while content-provider commits depend on provider semantics.
- Defaults network input to a bounded cache download with redirect, timeout, scheme/host, and
  non-public-address checks. Direct FFmpeg networking requires a separately audited native profile;
  it cannot be enabled at runtime in the `--disable-network` core binary.
- Serializes native sessions. Each command dynamically loads a fresh `libffmpeg_sdk_cli.so`, calls
  the two-symbol runner, and unloads it after completion so private fftools globals cannot leak
  across jobs.
- Uses direct public libav APIs for version, license, component discovery, and probe.
- Limits native sessions and bounds captured probe streams/JSON as well as logs.
- Bounds asynchronous task event buffering; under backpressure stale log/progress entries may be
  discarded while the latest terminal event and independent `Deferred` result remain available.
- Bounds captured result/log strings so a single result object cannot grow without limit.
- Re-hashes the exact four-ABI library closure against the locked runtime manifest before Gradle
  packaging and embeds that same manifest in AAR/APK/AAB assets.

### Native supply chain

- `native-runtime/ffmpeg.lock.json` pins the official tag/commit, signed archive, release key,
  Google NDK, ABIs, flags, patch, bridge sources, and hashes.
- `build-android.sh` verifies PGP and SHA-256 before compiling all four Android ABIs.
- The FFmpeg patch only renames the command entry point, suppresses process-global terminal/signal
  side effects, and exports cancellation. It is hashed and applied with `--fuzz=0` to a fresh signed
  source tree on every build.
- The ELF verifier requires an unversioned Android SONAME, 16 KiB LOAD alignment, GNU_RELRO/NOW,
  non-executable stack, a closed declared dependency graph, and no TEXTREL or RPATH/RUNPATH for
  every FFmpeg and SDK shared object.

### Application

- Selects and audits a native runtime profile.
- Owns persisted URI permissions and user-visible output lifetime.
- Owns foreground execution, notification UX, retry policy, and network allow-list.
- Must retain exact corresponding source and notices for every shipped native binary.

## Failure semantics

An attempt may fail because the component is absent, the device MediaCodec rejects a format, a
provider is not seekable, the input is malformed, storage fills, or native code crashes. These are
not interchangeable. Every attempt records its encoder, exit code, logs, duration, and failure
details. A retry may select another implementation of the same codec; it may not silently select a
different codec or container.

An interrupted encode no longer writes directly to the selected destination under the default
policy. A filesystem output is committed from a same-directory staging file. A `content://` output
is copied only after FFmpeg succeeds, but Android providers do not expose one universal atomic
replace primitive; provider-specific failure and atomicity testing remains a release gate.

## Planned isolated worker

FFmpeg processes attacker-controlled bytes in native code. The production engine should run in an
Android service declared with `android:isolatedProcess="true"`, communicate through a narrow Binder
protocol, and use app-owned file descriptors instead of broad storage/network permissions. The
service must support session-specific cancellation and report process death as a distinct result.
This is a release gate, not a property of the current in-process adapter.
