# API contract

## Constructing the SDK

`FfmpegSdk` accepts an `FfmpegEngine` and optionally a `CommandPlanner`. The default planner is the
stable product surface. `OfficialFfmpegEngine` uses `OfficialFfmpegRuntimePolicy`, locks FFmpeg
major 9 by default, and accepts only the generated `core-lgpl` runtime.

Java callers use `FfmpegJavaSdk`, which exposes the same planner and engine through
`CompletableFuture`, `ExecutionEventListener`, and a cancellable `JavaMediaTask`:

```java
try (FfmpegJavaSdk sdk = new FfmpegJavaSdk(new OfficialFfmpegEngine(context))) {
    JavaMediaTask task = sdk.execute(job, event -> render(event));
    task.getFuture().thenAccept(result -> renderResult(result));
    // task.cancel(); propagates through the coroutine to the active native session.
}
```

Runtime policy is defense in depth:

- `allowedFfmpegMajorVersions` prevents an untested major from entering silently;
- the runtime license is read from `avcodec_license()` rather than trusted from the caller;
- the engine checks FFmpeg build configuration and rejects `--enable-gpl` in the LGPL profile;
- `--enable-nonfree` is always rejected;
- `allowNetworkInputs` defaults to false, independently of the per-job flag;
- the runner serializes commands; aggregate per-session `maxStagedInputBytes` and
  `maxProbeStreams` bound engine resources;
- ordinary seekable `content://` input uses FFmpeg 9's official content protocol; non-seekable
  input and filter resources that need a real filename are staged;
- `transactionalOutputs` defaults to commit-after-success staging;
- network input defaults to a bounded HTTPS cache download with redirect, timeout, host, and
  non-public-address policy;
- captured result/log strings and retained public-libav probe JSON are bounded.

## Resources

`MediaReference` is one of:

- `FilePath`: intended for app-private/cache files that remain accessible under scoped storage;
- `ContentUri`: an Android `content://` URI resolved by the engine's SAF protocol;
- `NetworkUrl`: HTTP(S) input only; output URLs are rejected.

Input and output may not be the same reference. A path or URI is never inserted into a shell
command string. It remains a `CommandArgument.Resource` until engine resolution. A resource needed
inside one FFmpeg filter argument is represented as `CommandArgument.Composite`; the engine
resolves it and applies filter-value escaping without shell parsing.

The application owns persisted URI grants. Under the default engine policy, an ordinary content
input is probed with a separate file descriptor: seekable providers go directly through FFmpeg's
Android content protocol, while non-seekable providers are copied to bounded app cache.
`READ_SEEKABLE` resources, such as external subtitle files, are always staged. Output is encoded to
a staging file first. Filesystem output then uses a
same-directory commit; content output is opened and copied only after FFmpeg succeeds. A content
provider can still revoke access, run out of space, or expose non-atomic replacement semantics, so
provider-matrix evidence remains a release gate. Because providers expose no universal atomic
no-replace primitive, transactional `ContentUri` output requires `overwrite = true`; this is an
explicit acknowledgement of provider replacement semantics, not a claim of atomicity.

## Job behavior

`MediaJob` is a sealed operation family encoded by `MediaJobJson` schema v2:

- `TranscodeJob`: video/audio copy, encode, or drop; container; trim; scaling; bitrate; frame rate;
  GOP; overwrite; network opt-in; and MP4 fast-start;
- `ThumbnailJob`: timestamp, seek accuracy, bounded scale, and PNG/JPEG/WebP output;
- `WaveformJob`: size, per-channel mode, validated colors, scale, and image output;
- `SubtitleBurnJob`: seekable subtitle resource, character encoding, validated ASS style fields,
  video encoder attempts, and optional trim;
- `ConcatJob`: 2–64 typed inputs, timestamp reset, optional scale/pad normalization, and joint
  audio/video concat.

`MediaRecipes` supplies constructors for remux, H.264, HEVC, audio extraction/transcode, trim,
thumbnail, waveform, subtitle burn-in, and concat. Legacy `JobJson` remains the schema-v1
`TranscodeJob` codec; new persisted jobs should use `MediaJobJson`.

Defaults request H.264 through MediaCodec and AAC in MP4. They do not imply a software H.264
fallback on an LGPL runtime because FFmpeg's common `libx264` integration is GPL. Encoder candidates
are filtered against the runtime's actual `-encoders` output. Recipes also preflight required
filters and muxers. Container/codec combinations are checked before any media resource is opened.
MediaCodec video attempts request `-pix_fmt nv12`; this avoids Android encoders that advertise
flexible YUV but reject FFmpeg's default planar `yuv420p` input.
After probing, recipe-specific required streams are checked before execution; a concat job, for
example, rejects a segment missing an enabled audio or video stream.

`ResourceLimits` can require a trustworthy probe and reject an input duration, total duration, or
video pixel count. It can also add an FFmpeg `-fs` output ceiling and a `-threads` ceiling. `-fs`
is best effort because a small muxer trailer may exceed it.

Retries preserve the requested codec. They are disabled when `overwrite = false`, because an early
failed attempt may leave a partial output that the next attempt cannot safely replace.

## Execution

Kotlin `execute()` is a suspending, structured-concurrency API. Its callback may receive events from the
calling coroutine and native callback threads; callers must marshal UI work. `submit()` is the
convenience API: it publishes events through a thread-safe `SharedFlow` and exposes a `Deferred`
result.

For Java, `FfmpegJavaSdk.execute()` returns `JavaMediaTask`; completion and failure use its
`CompletableFuture`, and either `JavaMediaTask.cancel()` or `future.cancel()` cancels the underlying
coroutine. `FfmpegJavaSdk.close()` cancels all work owned by that facade, so its lifetime should
match an application service or another explicit owner.

Cancelling the deferred invokes the exact active runner's cancellation symbol. Awaiting a task
cancelled by its caller follows Kotlin coroutine semantics and throws `CancellationException`.
`MediaResult.Cancelled` remains available for engines that return a completed cancelled result.

Event order for a normal attempt is:

1. `Planned`
2. zero or more `Probed` events (with `inputIndex`)
3. `AttemptStarted`
4. zero or more `Log` / `Progress`
5. `AttemptFinished`

Additional attempts repeat steps 3–5. Progress fraction uses trim duration when provided, sums
known concat input durations, or uses the probed duration minus trim start. It is null when no
trustworthy total exists. `submit()` uses a bounded internal channel feeding a replaying
`SharedFlow`. The queue holds 256 events and drops the oldest buffered event under
backpressure; the latest terminal event remains replayable and task completion is independently
available from `result`.

## Probe

`FfmpegSdk.probe()` uses public `libavformat` APIs and returns a bounded set of format and stream
fields plus bounded JSON. `streamsTruncated` and `rawJsonTruncated` make truncation observable.
Network probing requires an explicit method opt-in and a runtime policy that also permits network
input.

## Errors

- Constructor `require` failures mean the task itself is invalid.
- `PlanningException` means policy, container, license, or component selection cannot produce a
  safe plan.
- Probe exceptions contain a bounded `libavformat` error.
- A completed native attempt is represented by `EngineResult`, including session id, exit code,
  cancel flag, duration, bounded output, failure details, and an optional `EngineFailureCategory`.
- A typed `EngineException` thrown during an attempt preserves its `RESOURCE` or `POLICY` category;
  other non-cancellation exceptions become structured `RUNTIME` failures. Output commit errors
  become `OUTPUT_COMMIT`; non-zero native commands become `COMMAND`.
- `MediaResult.Failure` means all safe compatible attempts failed; it retains every attempt result.

The current in-process adapter cannot survive a native process crash. The isolated worker defined in
the release gates will add a distinct process-death outcome without changing the job schema.
