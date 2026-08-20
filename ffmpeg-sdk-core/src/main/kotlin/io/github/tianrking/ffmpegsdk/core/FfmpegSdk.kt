package io.github.tianrking.ffmpegsdk.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn

public data class ExecutionOptions(
    val probeInput: Boolean = true,
)

public sealed interface ExecutionEvent {
    public data class Probed(
        val probe: MediaProbe,
        val inputIndex: Int = 0,
    ) : ExecutionEvent
    public data class Planned(val plan: ExecutionPlan) : ExecutionEvent
    public data class AttemptStarted(val attempt: ExecutionAttempt) : ExecutionEvent
    public data class Log(val attempt: Int, val message: String) : ExecutionEvent
    public data class Progress(
        val attempt: Int,
        val processedTimeMs: Long,
        val totalTimeMs: Long?,
        val fraction: Double?,
        val speed: Double,
        val outputBytes: Long,
    ) : ExecutionEvent
    public data class AttemptFinished(val attempt: Int, val result: EngineResult) : ExecutionEvent
}

public sealed interface MediaResult {
    public val jobId: String

    public data class Success(
        override val jobId: String,
        val attempt: Int,
        val engineResult: EngineResult,
        val warnings: List<String>,
    ) : MediaResult

    public data class Failure(
        override val jobId: String,
        val attempts: List<EngineResult>,
        val message: String,
    ) : MediaResult

    public data class Cancelled(
        override val jobId: String,
        val attempt: Int,
        val engineResult: EngineResult,
    ) : MediaResult
}

public class MediaTask internal constructor(
    public val jobId: String,
    public val events: SharedFlow<ExecutionEvent>,
    public val result: Deferred<MediaResult>,
) {
    public fun cancel() {
        result.cancel()
    }
}

public class FfmpegSdk(
    private val engine: FfmpegEngine,
    private val planner: CommandPlanner = DefaultCommandPlanner(),
) {
    public suspend fun probe(
        reference: MediaReference,
        allowNetworkInput: Boolean = false,
    ): MediaProbe {
        require(allowNetworkInput || reference !is MediaReference.NetworkUrl) {
            "Network probing is disabled unless explicitly enabled"
        }
        return engine.probe(reference)
    }

    public suspend fun execute(
        job: MediaJob,
        options: ExecutionOptions = ExecutionOptions(),
        onEvent: (ExecutionEvent) -> Unit = {},
    ): MediaResult {
        // Planning validates network and license policy before the engine touches any resource.
        val plan = planner.plan(job, engine.descriptor(), engine.capabilities())
        onEvent(ExecutionEvent.Planned(plan))

        if (!options.probeInput && job.limits.requiresProbe) {
            throw PlanningException("Input probing is required to enforce this job's resource limits")
        }
        val probes = if (options.probeInput) {
            job.progressReferences.mapIndexed { index, reference ->
                engine.probe(reference).also {
                    validateProbeLimits(job.limits, it, index)
                    onEvent(ExecutionEvent.Probed(it, index))
                }
            }
        } else {
            emptyList()
        }
        validateRequiredStreams(job, probes)
        validateTotalDurationLimit(job.limits, probes)
        val totalTimeMs = progressDuration(job, probes)

        val results = mutableListOf<EngineResult>()
        for (attempt in plan.attempts) {
            onEvent(ExecutionEvent.AttemptStarted(attempt))
            val startedAt = System.nanoTime()
            val result = try {
                engine.execute(
                    request = EngineRequest(
                        jobId = job.id,
                        attempt = attempt.index,
                        arguments = attempt.arguments,
                        overwrite = job.overwrite,
                    ),
                ) { engineEvent ->
                    when (engineEvent) {
                        is EngineEvent.Log -> onEvent(
                            ExecutionEvent.Log(attempt.index, engineEvent.message),
                        )
                        is EngineEvent.Statistics -> {
                            val fraction = totalTimeMs
                                ?.takeIf { it > 0 }
                                ?.let {
                                    (engineEvent.processedTimeMs.toDouble() / it).coerceIn(0.0, 1.0)
                                }
                            onEvent(
                                ExecutionEvent.Progress(
                                    attempt = attempt.index,
                                    processedTimeMs = engineEvent.processedTimeMs,
                                    totalTimeMs = totalTimeMs,
                                    fraction = fraction,
                                    speed = engineEvent.speed,
                                    outputBytes = engineEvent.outputBytes,
                                ),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                EngineResult(
                    sessionId = null,
                    exitCode = null,
                    cancelled = false,
                    durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                    output = "",
                    failureDetails = failure.describe(),
                    failureCategory = (failure as? EngineException)?.category
                        ?: EngineFailureCategory.RUNTIME,
                )
            }
            results += result
            onEvent(ExecutionEvent.AttemptFinished(attempt.index, result))
            if (result.cancelled) return MediaResult.Cancelled(job.id, attempt.index, result)
            if (result.succeeded) {
                return MediaResult.Success(job.id, attempt.index, result, plan.warnings)
            }
        }

        return MediaResult.Failure(
            jobId = job.id,
            attempts = results,
            message = "All ${results.size} compatible encoder attempt(s) failed",
        )
    }

    public fun submit(
        scope: CoroutineScope,
        job: MediaJob,
        options: ExecutionOptions = ExecutionOptions(),
    ): MediaTask {
        val eventChannel = Channel<ExecutionEvent>(
            capacity = EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val events = eventChannel.receiveAsFlow().shareIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )
        val result = scope.async {
            try {
                execute(job, options) { eventChannel.trySend(it) }
            } finally {
                eventChannel.close()
            }
        }
        return MediaTask(job.id, events, result)
    }

    private fun validateProbeLimits(limits: ResourceLimits, probe: MediaProbe, inputIndex: Int) {
        limits.maxInputDurationMs?.let { maximum ->
            val duration = probe.durationMs ?: throw PlanningException(
                "Input $inputIndex has no trustworthy duration; the duration limit cannot be enforced",
            )
            if (duration > maximum) {
                throw PlanningException("Input $inputIndex duration $duration ms exceeds limit $maximum ms")
            }
        }
        limits.maxInputPixels?.let { maximum ->
            if (probe.streamsTruncated) {
                throw PlanningException(
                    "Input $inputIndex stream metadata was truncated; the pixel limit cannot be enforced",
                )
            }
            probe.streams.filter { it.type == "video" }.forEach { stream ->
                val width = stream.width ?: throw PlanningException(
                    "Input $inputIndex video stream ${stream.index} has no trustworthy width",
                )
                val height = stream.height ?: throw PlanningException(
                    "Input $inputIndex video stream ${stream.index} has no trustworthy height",
                )
                val pixels = width.toLong() * height.toLong()
                if (pixels > maximum) {
                    throw PlanningException(
                        "Input $inputIndex video stream ${stream.index} has $pixels pixels; limit is $maximum",
                    )
                }
            }
        }
    }

    private fun validateTotalDurationLimit(limits: ResourceLimits, probes: List<MediaProbe>) {
        limits.maxTotalInputDurationMs?.let { maximum ->
            val durations = probes.mapIndexed { index, probe ->
                probe.durationMs ?: throw PlanningException(
                    "Input $index has no trustworthy duration; the total-duration limit cannot be enforced",
                )
            }
            val total = durations.fold(0L) { accumulator, value ->
                if (Long.MAX_VALUE - accumulator < value) Long.MAX_VALUE else accumulator + value
            }
            if (total > maximum) {
                throw PlanningException("Total input duration $total ms exceeds limit $maximum ms")
            }
        }
    }

    private fun validateRequiredStreams(job: MediaJob, probes: List<MediaProbe>) {
        fun requireStream(inputIndex: Int, type: String, operation: String) {
            val probe = probes.getOrNull(inputIndex) ?: return
            if (probe.streams.any { it.type == type }) return
            val reason = if (probe.streamsTruncated) {
                "stream metadata was truncated"
            } else {
                "no $type stream was found"
            }
            throw PlanningException("$operation input $inputIndex is invalid: $reason")
        }

        when (job) {
            is ThumbnailJob -> requireStream(0, "video", "Thumbnail")
            is WaveformJob -> requireStream(0, "audio", "Waveform")
            is SubtitleBurnJob -> requireStream(0, "video", "Subtitle burn")
            is ConcatJob -> probes.indices.forEach { index ->
                if (job.video.mode == StreamMode.ENCODE) requireStream(index, "video", "Concat")
                if (job.audio.mode == StreamMode.ENCODE) requireStream(index, "audio", "Concat")
            }
            is TranscodeJob -> {
                val probe = probes.firstOrNull() ?: return
                val wantsVideo = job.video.mode != StreamMode.DROP
                val wantsAudio = job.audio.mode != StreamMode.DROP
                val hasVideo = probe.streams.any { it.type == "video" }
                val hasAudio = probe.streams.any { it.type == "audio" }
                when {
                    wantsVideo && !wantsAudio -> requireStream(0, "video", "Transcode")
                    wantsAudio && !wantsVideo -> requireStream(0, "audio", "Transcode")
                    wantsVideo && wantsAudio && !hasVideo && !hasAudio -> {
                        val reason = if (probe.streamsTruncated) {
                            "stream metadata was truncated"
                        } else {
                            "no requested audio or video stream was found"
                        }
                        throw PlanningException("Transcode input 0 is invalid: $reason")
                    }
                }
            }
        }
    }

    private fun progressDuration(job: MediaJob, probes: List<MediaProbe>): Long? = when (job) {
        is TranscodeJob -> trimmedDuration(job.trim, probes.firstOrNull()?.durationMs)
        is SubtitleBurnJob -> trimmedDuration(job.trim, probes.firstOrNull()?.durationMs)
        is ThumbnailJob -> null
        is WaveformJob -> probes.firstOrNull()?.durationMs
        is ConcatJob -> probes.mapNotNull(MediaProbe::durationMs)
            .takeIf { it.size == probes.size }
            ?.fold(0L) { accumulator, value ->
                if (Long.MAX_VALUE - accumulator < value) Long.MAX_VALUE else accumulator + value
            }
    }

    private fun trimmedDuration(trim: TimeRange?, probedDurationMs: Long?): Long? =
        trim?.durationMs ?: probedDurationMs?.let { duration ->
            (duration - (trim?.startMs ?: 0)).coerceAtLeast(0)
        }

    private fun Throwable.describe(): String {
        val type = this::class.qualifiedName ?: this::class.simpleName ?: "Throwable"
        val value = message?.let { "$type: $it" } ?: type
        return if (value.length <= MAX_FAILURE_CHARS) value else value.take(MAX_FAILURE_CHARS)
    }

    private companion object {
        const val MAX_FAILURE_CHARS: Int = 8 * 1_024
        const val EVENT_BUFFER_CAPACITY: Int = 256
    }
}
