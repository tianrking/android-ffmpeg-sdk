package io.github.tianrking.ffmpegsdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RecipePlannerTest {
    private val planner = DefaultCommandPlanner()

    @Test
    fun `typed media JSON round trips every recipe`() {
        val jobs: List<MediaJob> = listOf(
            MediaRecipes.h264Export(file("in.mp4"), file("out.mp4")),
            ThumbnailJob(input = file("in.mp4"), output = file("thumb.png")),
            WaveformJob(input = file("in.wav"), output = file("wave.png")),
            SubtitleBurnJob(
                input = file("in.mp4"),
                subtitles = file("captions.srt"),
                output = file("subbed.mp4"),
                style = SubtitleStyle(fontName = "Noto Sans", fontSize = 32),
            ),
            ConcatJob(
                segments = listOf(file("a.mp4"), file("b.mp4")),
                output = file("joined.mp4"),
                targetWidth = 1_280,
                targetHeight = 720,
            ),
        )

        jobs.forEach { job ->
            assertEquals(job, MediaJobJson.decode(MediaJobJson.encode(job)))
        }
    }

    @Test
    fun `thumbnail has an explicit single-image codec and muxer`() {
        val job = ThumbnailJob(
            input = MediaReference.ContentUri("content://media/input clip"),
            output = MediaReference.ContentUri("content://media/thumb"),
            positionMs = 2_500,
            format = ImageFormat.PNG,
            overwrite = true,
        )
        val attempt = planner.plan(job, descriptor(), capabilities()).attempts.single()
        val literals = attempt.literalValues()

        assertTrue(listOf("-frames:v", "1", "-c:v", "png") in literals.windowed(4))
        assertTrue("image2" in literals)
        assertEquals(2, attempt.arguments.filterIsInstance<CommandArgument.Resource>().size)
    }

    @Test
    fun `waveform uses a bounded typed showwavespic graph`() {
        val job = WaveformJob(
            input = file("audio.flac"),
            output = file("wave.png"),
            width = 1_024,
            height = 256,
            colors = listOf("#112233", "yellow@0.8"),
            splitChannels = true,
        )
        val literals = planner.plan(job, descriptor(), capabilities())
            .attempts.single().literalValues()

        assertTrue(literals.any {
            it.contains("showwavespic=s=1024x256") &&
                it.contains("split_channels=1") &&
                it.contains("colors=#112233|yellow@0.8")
        })
        assertFailsWith<IllegalArgumentException> {
            job.copy(colors = listOf("yellow@1.1"))
        }
    }

    @Test
    fun `subtitle path remains a composite resource and never becomes a literal`() {
        val subtitle = MediaReference.ContentUri("content://captions/sub title.srt")
        val job = SubtitleBurnJob(
            input = file("input.mp4"),
            subtitles = subtitle,
            output = file("output.mp4"),
            overwrite = true,
        )
        val arguments = planner.plan(job, descriptor(), capabilities()).attempts.first().arguments
        val composite = assertNotNull(arguments.filterIsInstance<CommandArgument.Composite>().singleOrNull())
        val resource = composite.parts.filterIsInstance<ArgumentPart.Resource>().single()

        assertEquals(subtitle, resource.reference)
        assertEquals(ResourceAccess.READ_SEEKABLE, resource.access)
        assertEquals(ResourceEscaping.FFMPEG_FILTER_VALUE, resource.escaping)
        assertTrue(arguments.filterIsInstance<CommandArgument.Literal>().none { subtitle.uri in it.value })
    }

    @Test
    fun `subtitle recipe fails preflight when libass filter is absent`() {
        val job = SubtitleBurnJob(
            input = file("input.mp4"),
            subtitles = file("captions.srt"),
            output = file("output.mp4"),
        )
        val missingFilter = capabilities().copy(filters = setOf("scale"), filtersKnown = true)

        assertFailsWith<PlanningException> { planner.plan(job, descriptor(), missingFilter) }
    }

    @Test
    fun `concat uses one structured input per segment and resets timestamps`() {
        val job = ConcatJob(
            segments = listOf(file("part 1.mp4"), file("part 2.mp4"), file("part 3.mp4")),
            output = file("joined.mp4"),
            targetWidth = 1_920,
            targetHeight = 1_080,
            overwrite = true,
        )
        val attempt = planner.plan(job, descriptor(), capabilities()).attempts.first()
        val resources = attempt.arguments.filterIsInstance<CommandArgument.Resource>()
        val graph = attempt.literalValues().single { "concat=n=3" in it }

        assertEquals(4, resources.size)
        assertEquals(3, resources.count { it.access == ResourceAccess.READ })
        assertEquals(3, "]setpts=PTS-STARTPTS".toRegex().findAll(graph).count())
        assertEquals(3, "]asetpts=PTS-STARTPTS".toRegex().findAll(graph).count())
        assertTrue("pad=1920:1080" in graph)
    }

    @Test
    fun `audio extraction selects the matching container and encoder`() {
        val job = MediaRecipes.extractAudio(
            input = file("input.mov"),
            output = file("output.flac"),
            codec = AudioCodec.FLAC,
            container = Container.FLAC,
        )
        val attempt = planner.plan(job, descriptor(), capabilities()).attempts.single()

        assertEquals("flac", attempt.audioEncoder)
        assertTrue("-vn" in attempt.literalValues())
        assertTrue("flac" in attempt.literalValues())
    }

    @Test
    fun `container matrix rejects mismatched audio output`() {
        val job = MediaRecipes.extractAudio(
            input = file("input.mp4"),
            output = file("bad.wav"),
            codec = AudioCodec.OPUS,
            container = Container.WAV,
        )

        assertFailsWith<PlanningException> { planner.plan(job, descriptor(), capabilities()) }
    }

    @Test
    fun `resource limits are emitted as output options`() {
        val job = MediaRecipes.h264Export(file("input.mp4"), file("output.mp4")).copy(
            limits = ResourceLimits(maxOutputBytes = 50_000_000, maxThreads = 4),
        )
        val literals = planner.plan(job, descriptor(), capabilities()).attempts.first().literalValues()

        assertTrue(literals.windowed(2).any { it == listOf("-fs", "50000000") })
        assertTrue(literals.windowed(2).any { it == listOf("-threads", "4") })
    }

    @Test
    fun `every MediaCodec video recipe requests NV12 input`() {
        val jobs = listOf<MediaJob>(
            MediaRecipes.h264Export(file("input.mp4"), file("output.mp4"), overwrite = true),
            SubtitleBurnJob(
                input = file("input.mp4"),
                subtitles = file("captions.srt"),
                output = file("subtitled.mp4"),
                overwrite = true,
            ),
            ConcatJob(
                segments = listOf(file("a.mp4"), file("b.mp4")),
                output = file("joined.mp4"),
                overwrite = true,
            ),
        )

        jobs.forEach { job ->
            val attempt = planner.plan(job, descriptor(), capabilities()).attempts.first()
            assertTrue(
                attempt.literalValues().windowed(2).any { it == listOf("-pix_fmt", "nv12") },
                "${job::class.simpleName} omitted the MediaCodec NV12 input format",
            )
        }
    }

    @Test
    fun `network URL credentials and fragments are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MediaReference.NetworkUrl("https://user:secret@example.test/video.mp4")
        }
        assertFailsWith<IllegalArgumentException> {
            MediaReference.NetworkUrl("https://example.test/video.mp4#fragment")
        }
        assertFailsWith<IllegalArgumentException> {
            MediaReference.ContentUri("content://?missing-authority")
        }
    }

    @Test
    fun `concat progress sums every probed segment`() = runBlocking {
        val engine = RecordingEngine(
            probes = ArrayDeque(
                listOf(
                    probe(durationMs = 1_000),
                    probe(durationMs = 2_000),
                ),
            ),
        )
        val sdk = FfmpegSdk(engine)
        val events = mutableListOf<ExecutionEvent>()
        val job = ConcatJob(
            segments = listOf(file("a.mp4"), file("b.mp4")),
            output = file("out.mp4"),
            overwrite = true,
        )

        assertIs<MediaResult.Success>(sdk.execute(job, onEvent = events::add))
        assertEquals(2, events.filterIsInstance<ExecutionEvent.Probed>().size)
        assertEquals(3_000, events.filterIsInstance<ExecutionEvent.Progress>().first().totalTimeMs)

        val missingAudio = RecordingEngine(
            probes = ArrayDeque(
                listOf(
                    probe(durationMs = 1_000).copy(
                        streams = listOf(MediaStream(0, "video", "h264", 1_280, 720)),
                    ),
                    probe(durationMs = 1_000),
                ),
            ),
        )
        assertFailsWith<PlanningException> { FfmpegSdk(missingAudio).execute(job) }
        assertEquals(0, missingAudio.executionCount)
    }

    @Test
    fun `duration and pixel limits reject before execution`() = runBlocking {
        val engine = RecordingEngine(
            probes = ArrayDeque(
                listOf(
                    probe(durationMs = 10_000, width = 3_840, height = 2_160),
                ),
            ),
        )
        val job = MediaRecipes.h264Export(file("in.mp4"), file("out.mp4")).copy(
            limits = ResourceLimits(maxInputDurationMs = 5_000, maxInputPixels = 2_000_000),
        )

        assertFailsWith<PlanningException> { FfmpegSdk(engine).execute(job) }
        assertEquals(0, engine.executionCount)

        val truncatedEngine = RecordingEngine(
            probes = ArrayDeque(listOf(probe(durationMs = 1_000).copy(streamsTruncated = true))),
        )
        val truncatedJob = MediaRecipes.h264Export(file("in.mp4"), file("out.mp4")).copy(
            limits = ResourceLimits(maxInputPixels = 10_000_000),
        )
        assertFailsWith<PlanningException> { FfmpegSdk(truncatedEngine).execute(truncatedJob) }
        assertEquals(0, truncatedEngine.executionCount)
    }

    @Test
    fun `engine exception becomes a structured failed attempt`() = runBlocking {
        val engine = RecordingEngine(
            probes = ArrayDeque(listOf(probe(durationMs = 1_000))),
            executionFailure = IllegalStateException("native process disappeared"),
        )
        val result = FfmpegSdk(engine).execute(
            MediaRecipes.h264Export(file("in.mp4"), file("out.mp4")),
        )

        val failure = assertIs<MediaResult.Failure>(result)
        assertEquals(EngineFailureCategory.RUNTIME, failure.attempts.single().failureCategory)
        assertTrue("native process disappeared" in failure.attempts.single().failureDetails.orEmpty())
    }

    @Test
    fun `typed engine exception preserves its failure category`() = runBlocking {
        val engine = RecordingEngine(
            probes = ArrayDeque(listOf(probe(durationMs = 1_000))),
            executionFailure = EngineException(
                EngineFailureCategory.RESOURCE,
                "content provider could not be opened",
            ),
        )

        val result = FfmpegSdk(engine).execute(
            MediaRecipes.h264Export(file("in.mp4"), file("out.mp4")),
        )

        val failure = assertIs<MediaResult.Failure>(result)
        assertEquals(EngineFailureCategory.RESOURCE, failure.attempts.single().failureCategory)
    }

    private fun file(path: String): MediaReference = MediaReference.FilePath(path)

    private fun descriptor() = EngineDescriptor(
        name = "test",
        wrapperVersion = "1",
        ffmpegVersion = "9.0.1",
        distribution = "test",
        runtimeLicense = RuntimeLicense.GPL,
    )

    private fun capabilities() = EngineCapabilities(
        isKnown = true,
        encoders = setOf(
            "h264_mediacodec",
            "hevc_mediacodec",
            "libx264",
            "libx265",
            "aac",
            "libopus",
            "libmp3lame",
            "libvorbis",
            "flac",
            "pcm_s16le",
            "png",
            "mjpeg",
            "libwebp",
        ),
        filters = setOf(
            "scale",
            "showwavespic",
            "subtitles",
            "concat",
            "setpts",
            "asetpts",
            "pad",
            "setsar",
        ),
        muxers = setOf("mp4", "matroska", "webm", "mpegts", "mov", "mp3", "ogg", "wav", "flac", "image2"),
        filtersKnown = true,
        muxersKnown = true,
    )

    private fun ExecutionAttempt.literalValues(): List<String> =
        arguments.filterIsInstance<CommandArgument.Literal>().map(CommandArgument.Literal::value)

    private fun probe(
        durationMs: Long,
        width: Int = 1_280,
        height: Int = 720,
    ) = MediaProbe(
        durationMs = durationMs,
        formatNames = setOf("mov", "mp4"),
        bitrate = 1_000_000,
        streams = listOf(
            MediaStream(0, "video", "h264", width, height),
            MediaStream(1, "audio", "aac", sampleRate = 48_000, channels = 2),
        ),
        rawJson = "{}",
    )

    private class RecordingEngine(
        private val probes: ArrayDeque<MediaProbe>,
        private val executionFailure: Throwable? = null,
    ) : FfmpegEngine {
        var executionCount: Int = 0

        override suspend fun descriptor(): EngineDescriptor = EngineDescriptor(
            "fake", "1", "9.0.1", "test", RuntimeLicense.GPL,
        )

        override suspend fun capabilities(refresh: Boolean): EngineCapabilities = EngineCapabilities(
            isKnown = true,
            encoders = setOf("h264_mediacodec", "libx264", "aac"),
            filters = setOf("concat", "setpts", "asetpts"),
            muxers = setOf("mp4"),
            filtersKnown = true,
            muxersKnown = true,
        )

        override suspend fun execute(
            request: EngineRequest,
            onEvent: (EngineEvent) -> Unit,
        ): EngineResult {
            executionCount += 1
            executionFailure?.let { throw it }
            onEvent(EngineEvent.Statistics(500, 15, 30f, 1_024, 500.0, 1.0))
            return EngineResult("session", 0, false, 10, "ok")
        }

        override suspend fun probe(reference: MediaReference): MediaProbe = probes.removeFirst()
    }
}
