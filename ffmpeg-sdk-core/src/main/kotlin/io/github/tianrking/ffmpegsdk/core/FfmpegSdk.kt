package io.github.tianrking.ffmpegsdk.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

public data class ExecutionOptions(
    val probeInput: Boolean = true,
)

public sealed interface ExecutionEvent {
    public data class Probed(val probe: MediaProbe) : ExecutionEvent
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
        job: TranscodeJob,
        options: ExecutionOptions = ExecutionOptions(),
        onEvent: (ExecutionEvent) -> Unit = {},
    ): MediaResult {
        // Planning validates network and license policy before the engine touches any resource.
        val plan = planner.plan(job, engine.descriptor(), engine.capabilities())
        onEvent(ExecutionEvent.Planned(plan))

        val probedDurationMs = if (options.probeInput) {
            engine.probe(job.input).also { onEvent(ExecutionEvent.Probed(it)) }.durationMs
        } else {
            null
        }
        val totalTimeMs = job.trim?.durationMs ?: probedDurationMs?.let { duration ->
            (duration - (job.trim?.startMs ?: 0)).coerceAtLeast(0)
        }

        val results = mutableListOf<EngineResult>()
        for (attempt in plan.attempts) {
            onEvent(ExecutionEvent.AttemptStarted(attempt))
            val terminalSent = AtomicBoolean(false)
            val result = engine.execute(
                request = EngineRequest(job.id, attempt.index, attempt.arguments),
            ) { engineEvent ->
                when (engineEvent) {
                    is EngineEvent.Log -> onEvent(
                        ExecutionEvent.Log(attempt.index, engineEvent.message),
                    )
                    is EngineEvent.Statistics -> {
                        val fraction = totalTimeMs
                            ?.takeIf { it > 0 }
                            ?.let { (engineEvent.processedTimeMs.toDouble() / it).coerceIn(0.0, 1.0) }
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
            results += result
            if (terminalSent.compareAndSet(false, true)) {
                onEvent(ExecutionEvent.AttemptFinished(attempt.index, result))
            }
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
        job: TranscodeJob,
        options: ExecutionOptions = ExecutionOptions(),
    ): MediaTask {
        val events = MutableSharedFlow<ExecutionEvent>(replay = 1, extraBufferCapacity = 128)
        val result = scope.async {
            execute(job, options) { events.tryEmit(it) }
        }
        return MediaTask(job.id, events.asSharedFlow(), result)
    }
}
