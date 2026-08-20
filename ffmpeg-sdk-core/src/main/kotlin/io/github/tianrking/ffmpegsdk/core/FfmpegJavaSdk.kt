package io.github.tianrking.ffmpegsdk.core

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Java-friendly event callback for [FfmpegJavaSdk]. */
public fun interface ExecutionEventListener {
    public fun onEvent(event: ExecutionEvent)
}

/**
 * A Java-friendly task handle. Cancelling either this handle or [future] cancels the coroutine and
 * therefore reaches the active native FFmpeg session through the engine cancellation hook.
 */
public class JavaMediaTask internal constructor(
    public val jobId: String,
    public val future: CompletableFuture<MediaResult>,
    private val coroutine: Job,
) {
    @JvmOverloads
    public fun cancel(mayInterruptIfRunning: Boolean = true): Boolean {
        val accepted = future.cancel(mayInterruptIfRunning)
        coroutine.cancel()
        return accepted
    }
}

/**
 * Callback/future facade for Java applications. Call [close] when its application or service scope
 * ends; individual work can be cancelled through the returned [JavaMediaTask].
 */
public class FfmpegJavaSdk @JvmOverloads constructor(
    engine: FfmpegEngine,
    planner: CommandPlanner = DefaultCommandPlanner(),
) : AutoCloseable {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)
    private val sdk = FfmpegSdk(engine, planner)

    @JvmOverloads
    public fun probe(
        reference: MediaReference,
        allowNetworkInput: Boolean = false,
    ): CompletableFuture<MediaProbe> = startFuture {
        sdk.probe(reference, allowNetworkInput)
    }.first

    @JvmOverloads
    public fun execute(
        job: MediaJob,
        listener: ExecutionEventListener = ExecutionEventListener {},
        options: ExecutionOptions = ExecutionOptions(),
    ): JavaMediaTask {
        val (future, coroutine) = startFuture {
            sdk.execute(job, options, listener::onEvent)
        }
        return JavaMediaTask(job.id, future, coroutine)
    }

    override fun close() {
        scope.cancel("FfmpegJavaSdk was closed")
    }

    private fun <T> startFuture(block: suspend () -> T): Pair<CompletableFuture<T>, Job> {
        val future = CompletableFuture<T>()
        val coroutine = scope.launch {
            try {
                future.complete(block())
            } catch (_: CancellationException) {
                future.cancel(false)
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
            }
        }
        future.whenComplete { _, _ ->
            if (future.isCancelled) coroutine.cancel()
        }
        return future to coroutine
    }
}
