package io.github.tianrking.ffmpegsdk.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation

class FfmpegJavaSdkTest {
    @Test
    fun `java facade completes future and forwards events`() {
        FfmpegJavaSdk(SuccessEngine()).use { sdk ->
            val events = CopyOnWriteArrayList<ExecutionEvent>()
            val task = sdk.execute(job(), events::add)
            val result = task.future.get(5, TimeUnit.SECONDS)

            assertIs<MediaResult.Success>(result)
            assertEquals("java-facade", task.jobId)
            assertTrue(events.any { it is ExecutionEvent.Planned })
            assertTrue(events.any { it is ExecutionEvent.AttemptFinished })
        }
    }

    @Test
    fun `cancelling future propagates into engine execution`() {
        val engine = CancellableEngine()
        FfmpegJavaSdk(engine).use { sdk ->
            val task = sdk.execute(job())
            assertTrue(engine.started.await(5, TimeUnit.SECONDS))

            assertTrue(task.future.cancel(true))
            assertTrue(engine.cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(task.future.isCancelled)
        }
    }

    private fun job(): TranscodeJob = TranscodeJob(
        id = "java-facade",
        input = MediaReference.FilePath("input.mp4"),
        output = MediaReference.FilePath("output.mp4"),
        overwrite = true,
    )

    private open class SuccessEngine : FfmpegEngine {
        override suspend fun descriptor(): EngineDescriptor = EngineDescriptor(
            name = "java-test",
            wrapperVersion = "1",
            ffmpegVersion = "9.0.1",
            distribution = "test",
            runtimeLicense = RuntimeLicense.LGPL,
        )

        override suspend fun capabilities(refresh: Boolean): EngineCapabilities =
            EngineCapabilities(isKnown = true, encoders = setOf("h264_mediacodec", "aac"))

        override suspend fun execute(
            request: EngineRequest,
            onEvent: (EngineEvent) -> Unit,
        ): EngineResult = EngineResult(
            sessionId = "java-test",
            exitCode = 0,
            cancelled = false,
            durationMs = 1,
            output = "ok",
        )

        override suspend fun probe(reference: MediaReference): MediaProbe = MediaProbe(
            durationMs = 1_000,
            formatNames = setOf("mp4"),
            bitrate = 1_000_000,
            streams = listOf(MediaStream(0, "video", "h264", width = 640, height = 360)),
            rawJson = "{}",
        )
    }

    private class CancellableEngine : SuccessEngine() {
        val started = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)

        override suspend fun execute(
            request: EngineRequest,
            onEvent: (EngineEvent) -> Unit,
        ): EngineResult {
            started.countDown()
            try {
                awaitCancellation()
            } finally {
                cancelled.countDown()
            }
        }
    }
}
