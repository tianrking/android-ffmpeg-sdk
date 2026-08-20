package io.github.tianrking.ffmpegsdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FfmpegSdkTest {
    @Test
    fun `retries same codec and reports measured progress`() = runBlocking {
        val engine = FakeEngine(
            mutableListOf(
                result(exitCode = 1),
                result(exitCode = 0),
            ),
        )
        val sdk = FfmpegSdk(engine)
        val events = mutableListOf<ExecutionEvent>()
        val result = sdk.execute(job(), onEvent = events::add)

        assertIs<MediaResult.Success>(result)
        assertEquals(2, result.attempt)
        assertEquals(2, engine.requests.size)
        assertEquals(
            listOf("h264_mediacodec", "libx264"),
            engine.requests.map { request ->
                request.arguments
                    .filterIsInstance<CommandArgument.Literal>()
                    .zipWithNext()
                    .first { it.first.value == "-c:v" }
                    .second.value
            },
        )
        assertIs<ExecutionEvent.Planned>(events.first())
        val progress = events.filterIsInstance<ExecutionEvent.Progress>().first()
        assertEquals(0.5, progress.fraction)
    }

    @Test
    fun `denied network job is rejected before probe touches resource`() = runBlocking {
        val engine = FakeEngine(mutableListOf(result(exitCode = 0)))
        val sdk = FfmpegSdk(engine)
        val networkJob = job().copy(
            input = MediaReference.NetworkUrl("https://example.test/input.mp4"),
            allowNetworkInput = false,
        )

        assertFailsWith<PlanningException> { sdk.execute(networkJob) }
        assertEquals(0, engine.probeCount)
        assertEquals(0, engine.requests.size)
    }

    @Test
    fun `standalone network probe requires explicit opt in`() = runBlocking {
        val engine = FakeEngine(mutableListOf())
        val sdk = FfmpegSdk(engine)
        val reference = MediaReference.NetworkUrl("https://example.test/input.mp4")

        assertFailsWith<IllegalArgumentException> { sdk.probe(reference) }
        sdk.probe(reference, allowNetworkInput = true)
        assertEquals(1, engine.probeCount)
    }

    private fun job() = TranscodeJob(
        id = "orchestration-test",
        input = MediaReference.FilePath("input.mp4"),
        output = MediaReference.FilePath("output.mp4"),
        overwrite = true,
        trim = TimeRange(durationMs = 2_000),
    )

    private fun result(exitCode: Int) = EngineResult(
        sessionId = "test-$exitCode",
        exitCode = exitCode,
        cancelled = false,
        durationMs = 25,
        output = "test",
    )

    private class FakeEngine(
        private val results: MutableList<EngineResult>,
    ) : FfmpegEngine {
        val requests = mutableListOf<EngineRequest>()
        var probeCount: Int = 0

        override suspend fun descriptor(): EngineDescriptor = EngineDescriptor(
            name = "fake",
            wrapperVersion = "1",
            ffmpegVersion = "9.0.1",
            distribution = "test",
            runtimeLicense = RuntimeLicense.GPL,
        )

        override suspend fun capabilities(refresh: Boolean): EngineCapabilities =
            EngineCapabilities(
                isKnown = true,
                encoders = setOf("h264_mediacodec", "libx264", "aac"),
            )

        override suspend fun execute(
            request: EngineRequest,
            onEvent: (EngineEvent) -> Unit,
        ): EngineResult {
            requests += request
            onEvent(
                EngineEvent.Statistics(
                    processedTimeMs = 1_000,
                    frame = 30,
                    framesPerSecond = 30f,
                    outputBytes = 1_024,
                    bitrateKbps = 1_000.0,
                    speed = 1.0,
                ),
            )
            return results.removeAt(0)
        }

        override suspend fun probe(reference: MediaReference): MediaProbe {
            probeCount += 1
            return MediaProbe(
                durationMs = 10_000,
                formatNames = setOf("mov", "mp4"),
                bitrate = 1_000_000,
                streams = listOf(MediaStream(0, "video", "h264", width = 1_280, height = 720)),
                rawJson = "{}",
            )
        }
    }
}
