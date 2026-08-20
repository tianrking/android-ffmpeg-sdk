package io.github.tianrking.ffmpegsdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultCommandPlannerTest {
    private val planner = DefaultCommandPlanner()

    @Test
    fun `plans SAF resources as structured arguments and prefers MediaCodec`() {
        val job = TranscodeJob(
            id = "test-job",
            input = MediaReference.ContentUri("content://media/input clip"),
            output = MediaReference.ContentUri("content://media/output clip"),
            overwrite = true,
        )
        val plan = planner.plan(job, descriptor(RuntimeLicense.LGPL), capabilities())

        assertEquals("h264_mediacodec", plan.attempts.single().videoEncoder)
        assertIs<CommandArgument.Resource>(plan.attempts.single().arguments[4])
        assertTrue(plan.attempts.single().arguments.none {
            it is CommandArgument.Literal && it.value.contains("content://")
        })
    }

    @Test
    fun `does not silently fall back to GPL x264 on LGPL runtime`() {
        val job = basicJob(
            video = VideoSettings(encoderPreference = EncoderPreference.PREFER_HARDWARE),
        )
        val plan = planner.plan(job, descriptor(RuntimeLicense.LGPL), capabilities())

        assertEquals(listOf("h264_mediacodec"), plan.attempts.map { it.videoEncoder })
    }

    @Test
    fun `allows same-codec software retry on explicit GPL runtime`() {
        val job = basicJob(
            video = VideoSettings(encoderPreference = EncoderPreference.PREFER_HARDWARE),
        )
        val plan = planner.plan(job, descriptor(RuntimeLicense.GPL), capabilities())

        assertEquals(listOf("h264_mediacodec", "libx264"), plan.attempts.map { it.videoEncoder })
    }

    @Test
    fun `disables retry when a partial output cannot be overwritten`() {
        val job = basicJob().copy(overwrite = false)
        val plan = planner.plan(job, descriptor(RuntimeLicense.GPL), capabilities())

        assertEquals(listOf("h264_mediacodec"), plan.attempts.map { it.videoEncoder })
        assertTrue(plan.warnings.any { "partial non-overwritable output" in it })
    }

    @Test
    fun `rejects network access unless job opts in`() {
        val job = basicJob(input = MediaReference.NetworkUrl("https://example.test/video.mp4"))

        assertFailsWith<PlanningException> {
            planner.plan(job, descriptor(RuntimeLicense.LGPL), capabilities())
        }
    }

    @Test
    fun `job JSON round trips with schema version`() {
        val original = basicJob()
        val restored = JobJson.decode(JobJson.encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun `scale filter preserves aspect ratio without shell quotes`() {
        val job = basicJob(
            video = VideoSettings(maxWidth = 1_280),
        )
        val literals = planner.plan(job, descriptor(RuntimeLicense.LGPL), capabilities())
            .attempts.single().arguments.filterIsInstance<CommandArgument.Literal>()
            .map { it.value }

        assertTrue(
            "scale=min(1280\\,iw):-2:force_original_aspect_ratio=decrease:" +
                "force_divisible_by=2" in literals,
        )
    }

    @Test
    fun `rejects identical input and output resources`() {
        val resource = MediaReference.FilePath("same.mp4")

        assertFailsWith<IllegalArgumentException> {
            TranscodeJob(input = resource, output = resource)
        }
    }

    private fun basicJob(
        input: MediaReference = MediaReference.FilePath("input.mp4"),
        video: VideoSettings = VideoSettings(),
    ) = TranscodeJob(
        id = "stable-id",
        input = input,
        output = MediaReference.FilePath("output.mp4"),
        video = video,
        overwrite = true,
    )

    private fun descriptor(license: RuntimeLicense) = EngineDescriptor(
        name = "test",
        wrapperVersion = "1",
        ffmpegVersion = "8.1.2",
        distribution = "test",
        runtimeLicense = license,
    )

    private fun capabilities() = EngineCapabilities(
        isKnown = true,
        encoders = setOf("h264_mediacodec", "libx264", "aac"),
    )
}
