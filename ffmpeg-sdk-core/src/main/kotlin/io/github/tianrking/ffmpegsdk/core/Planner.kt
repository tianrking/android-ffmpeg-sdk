package io.github.tianrking.ffmpegsdk.core

public data class ExecutionPlan(
    val jobId: String,
    val attempts: List<ExecutionAttempt>,
    val warnings: List<String>,
)

public data class ExecutionAttempt(
    val index: Int,
    val videoEncoder: String?,
    val audioEncoder: String?,
    val hardwareAccelerated: Boolean,
    val arguments: List<CommandArgument>,
)

public class PlanningException(message: String) : IllegalArgumentException(message)

public fun interface CommandPlanner {
    public fun plan(
        job: TranscodeJob,
        engine: EngineDescriptor,
        capabilities: EngineCapabilities,
    ): ExecutionPlan
}

public class DefaultCommandPlanner : CommandPlanner {
    override fun plan(
        job: TranscodeJob,
        engine: EngineDescriptor,
        capabilities: EngineCapabilities,
    ): ExecutionPlan {
        validateNetworkPolicy(job)
        validateContainer(job)

        val videoCandidates = videoEncoders(job.video, engine.runtimeLicense)
            .filter(capabilities::supportsEncoder)
        val audioEncoder = audioEncoder(job.audio).also { encoder ->
            if (encoder != null && !capabilities.supportsEncoder(encoder)) {
                throw PlanningException("Required audio encoder '$encoder' is unavailable")
            }
        }

        if (job.video.mode == StreamMode.ENCODE && videoCandidates.isEmpty()) {
            throw PlanningException(
                "No compatible ${job.video.codec} encoder is available for " +
                    "${job.video.encoderPreference} under ${engine.runtimeLicense}",
            )
        }

        val safeVideoCandidates = if (job.overwrite) videoCandidates else videoCandidates.take(1)
        val attempts = when (job.video.mode) {
            StreamMode.ENCODE -> safeVideoCandidates.mapIndexed { index, encoder ->
                buildAttempt(job, index + 1, encoder, audioEncoder)
            }
            StreamMode.COPY, StreamMode.DROP -> listOf(buildAttempt(job, 1, null, audioEncoder))
        }

        val warnings = buildList {
            if (engine.runtimeLicense == RuntimeLicense.UNKNOWN) {
                add("Runtime license is unknown; distribution must be reviewed before release")
            }
            if (attempts.size > 1) {
                add("Encoder retries preserve the requested codec but may change hardware acceleration")
            }
            if (!job.overwrite && videoCandidates.size > 1) {
                add("Encoder retries were disabled because a partial non-overwritable output is unsafe")
            }
            if (job.input is MediaReference.FilePath) {
                add("Android file paths must remain app-accessible under scoped storage")
            }
        }

        return ExecutionPlan(job.id, attempts, warnings)
    }

    private fun buildAttempt(
        job: TranscodeJob,
        attempt: Int,
        videoEncoder: String?,
        audioEncoder: String?,
    ): ExecutionAttempt {
        val args = buildList {
            literal("-hide_banner", "-nostdin", if (job.overwrite) "-y" else "-n")

            if (job.trim?.accuracy == SeekAccuracy.FAST && job.trim.startMs > 0) {
                literal("-ss", seconds(job.trim.startMs))
            }

            add(CommandArgument.Literal("-i"))
            add(CommandArgument.Resource(job.input, ResourceAccess.READ))

            if (job.trim?.accuracy == SeekAccuracy.ACCURATE && job.trim.startMs > 0) {
                literal("-ss", seconds(job.trim.startMs))
            }
            job.trim?.durationMs?.let { literal("-t", seconds(it)) }

            when (job.video.mode) {
                StreamMode.DROP -> literal("-vn")
                StreamMode.COPY -> literal("-map", "0:v:0?", "-c:v", "copy")
                StreamMode.ENCODE -> {
                    literal("-map", "0:v:0?", "-c:v", checkNotNull(videoEncoder))
                    literal("-b:v", job.video.bitrate.bitsPerSecond.toString())
                    job.video.keyFrameIntervalFrames?.let { literal("-g", it.toString()) }
                    job.video.maxFrameRate?.let { literal("-r", it.toString()) }
                    scaleFilter(job.video)?.let { literal("-vf", it) }
                }
            }

            when (job.audio.mode) {
                StreamMode.DROP -> literal("-an")
                StreamMode.COPY -> literal("-map", "0:a:0?", "-c:a", "copy")
                StreamMode.ENCODE -> {
                    literal("-map", "0:a:0?", "-c:a", checkNotNull(audioEncoder))
                    literal("-b:a", job.audio.bitrate.bitsPerSecond.toString())
                    job.audio.sampleRate?.let { literal("-ar", it.toString()) }
                    job.audio.channels?.let { literal("-ac", it.toString()) }
                }
            }

            if (job.container == Container.MP4 && job.optimizeForStreaming) {
                literal("-movflags", "+faststart")
            }
            literal("-f", job.container.ffmpegName)
            add(CommandArgument.Resource(job.output, ResourceAccess.WRITE))
        }

        return ExecutionAttempt(
            index = attempt,
            videoEncoder = videoEncoder,
            audioEncoder = audioEncoder,
            hardwareAccelerated = videoEncoder?.endsWith("_mediacodec") == true,
            arguments = args,
        )
    }

    private fun videoEncoders(
        settings: VideoSettings,
        runtimeLicense: RuntimeLicense,
    ): List<String> {
        if (settings.mode != StreamMode.ENCODE) return emptyList()

        val hardware = when (settings.codec) {
            VideoCodec.H264 -> "h264_mediacodec"
            VideoCodec.HEVC -> "hevc_mediacodec"
            VideoCodec.VP9 -> "vp9_mediacodec"
            VideoCodec.AV1 -> "av1_mediacodec"
            VideoCodec.MPEG4 -> null
        }
        val software = when (settings.codec) {
            VideoCodec.H264 -> "libx264"
            VideoCodec.HEVC -> "libx265"
            VideoCodec.VP9 -> "libvpx-vp9"
            VideoCodec.AV1 -> "libaom-av1"
            VideoCodec.MPEG4 -> "mpeg4"
        }.takeUnless { it in GPL_ONLY_ENCODERS && runtimeLicense != RuntimeLicense.GPL }

        return when (settings.encoderPreference) {
            EncoderPreference.HARDWARE_ONLY -> listOfNotNull(hardware)
            EncoderPreference.PREFER_HARDWARE -> listOfNotNull(hardware, software)
            EncoderPreference.PREFER_SOFTWARE -> listOfNotNull(software, hardware)
            EncoderPreference.SOFTWARE_ONLY -> listOfNotNull(software)
        }.distinct()
    }

    private fun audioEncoder(settings: AudioSettings): String? = when (settings.mode) {
        StreamMode.COPY, StreamMode.DROP -> null
        StreamMode.ENCODE -> when (settings.codec) {
            AudioCodec.AAC -> "aac"
            AudioCodec.OPUS -> "libopus"
        }
    }

    private fun validateNetworkPolicy(job: TranscodeJob) {
        if (job.input is MediaReference.NetworkUrl && !job.allowNetworkInput) {
            throw PlanningException("Network inputs are disabled for this job")
        }
    }

    private fun validateContainer(job: TranscodeJob) {
        if (job.container == Container.WEBM && job.video.mode == StreamMode.ENCODE &&
            job.video.codec !in setOf(VideoCodec.VP9, VideoCodec.AV1)
        ) {
            throw PlanningException("WebM output requires VP9 or AV1 video")
        }
        if (job.container == Container.WEBM && job.audio.mode == StreamMode.ENCODE &&
            job.audio.codec != AudioCodec.OPUS
        ) {
            throw PlanningException("WebM output requires Opus audio")
        }
    }

    private fun scaleFilter(settings: VideoSettings): String? {
        val width = settings.maxWidth
        val height = settings.maxHeight
        if (width == null && height == null) return null
        val w = width?.let { "min($it\\,iw)" } ?: "-2"
        val h = height?.let { "min($it\\,ih)" } ?: "-2"
        return "scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2"
    }

    private fun seconds(milliseconds: Long): String =
        "%.3f".format(java.util.Locale.ROOT, milliseconds / 1_000.0)

    private fun MutableList<CommandArgument>.literal(vararg values: String) {
        values.forEach { add(CommandArgument.Literal(it)) }
    }

    private companion object {
        val GPL_ONLY_ENCODERS: Set<String> = setOf("libx264", "libx265")
    }
}
