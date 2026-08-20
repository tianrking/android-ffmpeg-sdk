# API contract

## Constructing the SDK

`FfmpegSdk` accepts an `FfmpegEngine` and optionally a `CommandPlanner`. The default planner is the
stable product surface. The FFmpegKit adapter requires an explicit `FfmpegKitRuntimePolicy`; there
is no license default.

Runtime policy is defense in depth:

- `allowedFfmpegMajorVersions` prevents an untested major from entering silently;
- `runtimeLicense` controls whether GPL-only encoder candidates may be planned;
- the engine checks FFmpeg `-buildconf` and rejects a declared LGPL runtime with `--enable-gpl`;
- `--enable-nonfree` is always rejected;
- `allowNetworkInputs` defaults to false, independently of the per-job flag;
- captured result/log strings are bounded.

## Resources

`MediaReference` is one of:

- `FilePath`: intended for app-private/cache files that remain accessible under scoped storage;
- `ContentUri`: an Android `content://` URI resolved by the engine's SAF protocol;
- `NetworkUrl`: HTTP(S) input only; output URLs are rejected.

Input and output may not be the same reference. A path or URI is never inserted into a command
string. It remains a `CommandArgument.Resource` until engine resolution.

The application owns persisted URI grants. A content provider can still reject seeking, truncate
differently, run out of space, or revoke access. Transactional staging is a beta release gate.

## Job behavior

`TranscodeJob` controls video/audio copy, encode, or drop; container; trim; scaling bounds; bitrate;
frame rate; optional GOP frames; overwrite; network opt-in; and MP4 fast-start.

Defaults request H.264 through MediaCodec and AAC in MP4. They do not imply a software H.264
fallback on an LGPL runtime because FFmpeg's common `libx264` integration is GPL. Encoder candidates
are filtered against the runtime's actual `-encoders` output.

Retries preserve the requested codec. They are disabled when `overwrite = false`, because an early
failed attempt may leave a partial output that the next attempt cannot safely replace.

## Execution

`execute()` is a suspending, structured-concurrency API. Its callback may receive events from the
calling coroutine and native callback threads; callers must marshal UI work. `submit()` is the
convenience API: it publishes events through a thread-safe `SharedFlow` and exposes a `Deferred`
result.

Cancelling the deferred cancels the exact native session. Awaiting a task cancelled by its caller
follows Kotlin coroutine semantics and throws `CancellationException`. `MediaResult.Cancelled`
represents a native session that completed with FFmpegKit's cancel return code.

Event order for a normal attempt is:

1. `Planned`
2. optional `Probed`
3. `AttemptStarted`
4. zero or more `Log` / `Progress`
5. `AttemptFinished`

Additional attempts repeat steps 3–5. Progress fraction uses trim duration when provided; otherwise
it uses probed duration minus trim start. It is null when no trustworthy total exists.

## Probe

`FfmpegSdk.probe()` returns a bounded set of format and stream fields plus the selected FFprobe JSON.
Network probing requires an explicit method opt-in and a runtime policy that also permits network
input.

## Errors

- Constructor `require` failures mean the task itself is invalid.
- `PlanningException` means policy, container, license, or component selection cannot produce a
  safe plan.
- Probe exceptions contain the bounded FFprobe failure tail.
- A completed native attempt is represented by `EngineResult`, including session id, exit code,
  cancel flag, duration, bounded output, and failure details.
- `MediaResult.Failure` means all safe compatible attempts failed; it retains every attempt result.

The current in-process adapter cannot survive a native process crash. The isolated worker defined in
the release gates will add a distinct process-death outcome without changing the job schema.
