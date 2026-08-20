# Architecture and trust boundaries

## Data path

```text
TranscodeJob (versioned JSON)
        |
        v
DefaultCommandPlanner ---- EngineDescriptor + compiled FFmpeg capabilities
        |
        v
ExecutionPlan [explicit encoder attempts]
        |
        v
FfmpegEngine ---- resource resolver ---- file / content:// / HTTP(S)
        |
        +---- logs + statistics ---- ExecutionEvent (Flow)
        |
        v
EngineResult ---- success / retry same codec / failure / cancellation
```

The public core never imports `com.arthenica.ffmpegkit`. This prevents a wrapper implementation,
native artifact coordinate, or FFmpeg major from becoming part of the stable SDK ABI.

## Boundaries

### Core SDK

- Owns the versioned task model and validation.
- Produces argument arrays; never invokes a shell and never concatenates resource strings.
- Makes fallback attempts observable and preserves the requested codec.
- Applies runtime license policy before an encoder is selected.
- Has no Android or native dependency and is unit-testable on the JVM.

### Android platform module

- Describes what `MediaCodecList` reports on the current device.
- Does not claim that an advertised codec will complete a particular file.
- Does not infer FFmpeg compile-time capabilities.

### Engine adapter

- Resolves typed resources into engine-specific handles.
- Owns native session cancellation and converts native callbacks into stable events.
- Verifies the allowed FFmpeg major and audits `-buildconf` at runtime; an LGPL declaration rejects
  `--enable-gpl`, and every profile rejects `--enable-nonfree`.
- Discovers actual FFmpeg encoders and decoders.
- Applies a second, runtime-wide network input gate in addition to the per-job opt-in.
- Bounds captured result/log strings so a single result object cannot grow without limit.
- Does not distribute the native runtime transitively.

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

An interrupted job can leave a partial output with the current direct-SAF engine. Production GA
requires the staged-output transaction in `RELEASE_GATES.md`.

## Planned isolated worker

FFmpeg processes attacker-controlled bytes in native code. The production engine should run in an
Android service declared with `android:isolatedProcess="true"`, communicate through a narrow Binder
protocol, and use app-owned file descriptors instead of broad storage/network permissions. The
service must support session-specific cancellation and report process death as a distinct result.
This is a release gate, not a property of the current in-process adapter.
