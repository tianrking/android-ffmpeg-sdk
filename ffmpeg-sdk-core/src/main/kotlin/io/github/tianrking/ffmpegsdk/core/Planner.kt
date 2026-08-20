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
        job: MediaJob,
        engine: EngineDescriptor,
        capabilities: EngineCapabilities,
    ): ExecutionPlan
}

/** Plans the supported media recipes into argv entries without invoking a shell. */
public class DefaultCommandPlanner : CommandPlanner {
    override fun plan(
        job: MediaJob,
        engine: EngineDescriptor,
        capabilities: EngineCapabilities,
    ): ExecutionPlan {
        validateNetworkPolicy(job)
        val planned = when (job) {
            is TranscodeJob -> planTranscode(job, engine, capabilities)
            is ThumbnailJob -> planThumbnail(job, capabilities)
            is WaveformJob -> planWaveform(job, capabilities)
            is SubtitleBurnJob -> planSubtitleBurn(job, engine, capabilities)
            is ConcatJob -> planConcat(job, engine, capabilities)
        }
        require(planned.attempts.isNotEmpty()) { "Planner produced no execution attempts" }

        return ExecutionPlan(
            jobId = job.id,
            attempts = planned.attempts,
            warnings = commonWarnings(job, engine, planned.attempts) + planned.warnings,
        )
    }

    private fun planTranscode(
        job: TranscodeJob,
        engine: EngineDescriptor,
        capabilities: EngineCapabilities,
    ): PlannedAttempts {
        validateContainer(job.container, job.video, job.audio)
        if (job.video.mode == StreamMode.ENCODE &&
            (job.video.maxWidth != null || job.video.maxHeight != null)
        ) {
            requireFilter("scale", capabilities)
        }
        requireMuxer(job.container.ffmpegName, capabilities)
        val audioEncoder = requireAudioEncoder(job.audio, capabilities)
        val videoCandidates = requireVideoCandidates(job.video, engine.runtimeLicense, capabilities)
        val selected = retryCandidates(job.overwrite, videoCandidates)
        val attempts = when (job.video.mode) {
            StreamMode.ENCODE -> selected.mapIndexed { index, encoder ->
                buildTranscodeAttempt(job, index + 1, encoder, audioEncoder)
            }
            StreamMode.COPY, StreamMode.DROP -> listOf(
                buildTranscodeAttempt(job, 1, null, audioEncoder),
            )
        }
        return PlannedAttempts(
            attempts = attempts,
            warnings = buildList {
                addRetryWarnings(job.overwrite, videoCandidates, attempts)
                if (job.video.mode == StreamMode.COPY || job.audio.mode == StreamMode.COPY) {
                    add("Stream-copy compatibility depends on the probed codecs and selected container")
                }
            },
        )
    }

    private fun planThumbnail(
        job: ThumbnailJob,
        capabilities: EngineCapabilities,
    ): PlannedAttempts {
        requireEncoder(job.format.encoder, "image", capabilities)
        if (job.maxWidth != null || job.maxHeight != null) requireFilter("scale", capabilities)
        requireMuxer("image2", capabilities)
        val args = baseArguments(job.overwrite).apply {
            if (job.accuracy == SeekAccuracy.FAST && job.positionMs > 0) {
                literal("-ss", seconds(job.positionMs))
            }
            literal("-i")
            resource(job.input, ResourceAccess.READ)
            if (job.accuracy == SeekAccuracy.ACCURATE && job.positionMs > 0) {
                literal("-ss", seconds(job.positionMs))
            }
            literal("-map", "0:v:0", "-an", "-sn", "-dn")
            scaleFilter(job.maxWidth, job.maxHeight)?.let { literal("-vf", it) }
            literal("-frames:v", "1", "-c:v", job.format.encoder)
            if (job.format == ImageFormat.JPEG) literal("-q:v", "2")
            outputLimits(job.limits)
            literal("-f", "image2", "-update", "1")
            resource(job.output, ResourceAccess.WRITE)
        }
        return PlannedAttempts(
            attempts = listOf(ExecutionAttempt(1, job.format.encoder, null, false, args)),
        )
    }

    private fun planWaveform(
        job: WaveformJob,
        capabilities: EngineCapabilities,
    ): PlannedAttempts {
        requireFilter("showwavespic", capabilities)
        requireEncoder(job.format.encoder, "image", capabilities)
        requireMuxer("image2", capabilities)
        val scale = when (job.scale) {
            WaveformScale.LINEAR -> "lin"
            WaveformScale.LOGARITHMIC -> "log"
            WaveformScale.SQUARE_ROOT -> "sqrt"
            WaveformScale.CUBE_ROOT -> "cbrt"
        }
        val filter = "[0:a:0]showwavespic=" +
            "s=${job.width}x${job.height}:" +
            "split_channels=${if (job.splitChannels) 1 else 0}:" +
            "colors=${job.colors.joinToString("|")}:scale=$scale[wave]"
        val args = baseArguments(job.overwrite).apply {
            literal("-i")
            resource(job.input, ResourceAccess.READ)
            literal("-filter_complex", filter, "-map", "[wave]")
            literal("-frames:v", "1", "-c:v", job.format.encoder, "-an", "-sn", "-dn")
            if (job.format == ImageFormat.JPEG) literal("-q:v", "2")
            outputLimits(job.limits)
            literal("-f", "image2", "-update", "1")
            resource(job.output, ResourceAccess.WRITE)
        }
        return PlannedAttempts(
            attempts = listOf(ExecutionAttempt(1, job.format.encoder, null, false, args)),
        )
    }

    private fun planSubtitleBurn(
        job: SubtitleBurnJob,
        engine: EngineDescriptor,
        capabilities: EngineCapabilities,
    ): PlannedAttempts {
        requireFilter("subtitles", capabilities)
        if (job.video.maxWidth != null || job.video.maxHeight != null) {
            requireFilter("scale", capabilities)
        }
        validateContainer(job.container, job.video, job.audio)
        requireMuxer(job.container.ffmpegName, capabilities)
        val audioEncoder = requireAudioEncoder(job.audio, capabilities)
        val videoCandidates = requireVideoCandidates(job.video, engine.runtimeLicense, capabilities)
        val selected = retryCandidates(job.overwrite, videoCandidates)
        val attempts = selected.mapIndexed { index, encoder ->
            buildSubtitleAttempt(job, index + 1, encoder, audioEncoder)
        }
        return PlannedAttempts(
            attempts = attempts,
            warnings = buildList { addRetryWarnings(job.overwrite, videoCandidates, attempts) },
        )
    }

    private fun planConcat(
        job: ConcatJob,
        engine: EngineDescriptor,
        capabilities: EngineCapabilities,
    ): PlannedAttempts {
        requireFilter("concat", capabilities)
        if (job.video.mode == StreamMode.ENCODE) {
            requireFilter("setpts", capabilities)
        }
        if (job.audio.mode == StreamMode.ENCODE) {
            requireFilter("asetpts", capabilities)
        }
        if (job.targetWidth != null) {
            listOf("scale", "pad", "setsar").forEach { requireFilter(it, capabilities) }
        }
        validateContainer(job.container, job.video, job.audio)
        requireMuxer(job.container.ffmpegName, capabilities)
        val audioEncoder = requireAudioEncoder(job.audio, capabilities)
        val videoCandidates = requireVideoCandidates(job.video, engine.runtimeLicense, capabilities)
        val selected: List<String?> = if (job.video.mode == StreamMode.ENCODE) {
            retryCandidates(job.overwrite, videoCandidates)
        } else {
            listOf(null)
        }
        val attempts = selected.mapIndexed { index, encoder ->
            buildConcatAttempt(job, index + 1, encoder, audioEncoder)
        }
        return PlannedAttempts(
            attempts = attempts,
            warnings = buildList {
                addRetryWarnings(job.overwrite, videoCandidates, attempts)
                if (job.video.mode == StreamMode.ENCODE && job.targetWidth == null) {
                    add("Concat video segments must already share a resolution when no target size is set")
                }
                add("Concat expects each segment to contain every enabled stream")
            },
        )
    }

    private fun buildTranscodeAttempt(
        job: TranscodeJob,
        attempt: Int,
        videoEncoder: String?,
        audioEncoder: String?,
    ): ExecutionAttempt {
        val args = baseArguments(job.overwrite).apply {
            addInput(job.input, job.trim)
            addVideoOutput(job.video, videoEncoder)
            addAudioOutput(job.audio, audioEncoder)
            if (job.container == Container.MP4 && job.optimizeForStreaming) {
                literal("-movflags", "+faststart")
            }
            outputLimits(job.limits)
            literal("-f", job.container.ffmpegName)
            resource(job.output, ResourceAccess.WRITE)
        }
        return attempt(attempt, videoEncoder, audioEncoder, args)
    }

    private fun buildSubtitleAttempt(
        job: SubtitleBurnJob,
        attempt: Int,
        videoEncoder: String,
        audioEncoder: String?,
    ): ExecutionAttempt {
        val filterParts = buildList {
            add(ArgumentPart.Literal("subtitles=filename="))
            add(
                ArgumentPart.Resource(
                    reference = job.subtitles,
                    access = ResourceAccess.READ_SEEKABLE,
                    escaping = ResourceEscaping.FFMPEG_FILTER_VALUE,
                ),
            )
            add(ArgumentPart.Literal(":charenc=${job.characterEncoding}"))
            styleArgument(job.style)?.let { add(ArgumentPart.Literal(":force_style='$it'")) }
            scaleFilter(job.video.maxWidth, job.video.maxHeight)?.let {
                add(ArgumentPart.Literal(",$it"))
            }
        }
        val args = baseArguments(job.overwrite).apply {
            addInput(job.input, job.trim)
            literal("-map", "0:v:0", "-c:v", videoEncoder, "-b:v", job.video.bitrate.bitsPerSecond.toString())
            mediaCodecPixelFormat(videoEncoder)
            job.video.keyFrameIntervalFrames?.let { literal("-g", it.toString()) }
            job.video.maxFrameRate?.let { literal("-r", it.toString()) }
            literal("-vf")
            add(CommandArgument.Composite(filterParts))
            addAudioOutput(job.audio, audioEncoder)
            if (job.container == Container.MP4) literal("-movflags", "+faststart")
            outputLimits(job.limits)
            literal("-f", job.container.ffmpegName)
            resource(job.output, ResourceAccess.WRITE)
        }
        return attempt(attempt, videoEncoder, audioEncoder, args)
    }

    private fun buildConcatAttempt(
        job: ConcatJob,
        attempt: Int,
        videoEncoder: String?,
        audioEncoder: String?,
    ): ExecutionAttempt {
        val videoEnabled = job.video.mode == StreamMode.ENCODE
        val audioEnabled = job.audio.mode == StreamMode.ENCODE
        val graph = buildString {
            job.segments.indices.forEach { index ->
                if (videoEnabled) {
                    append("[$index:v:0]setpts=PTS-STARTPTS")
                    if (job.targetWidth != null && job.targetHeight != null) {
                        append(",scale=${job.targetWidth}:${job.targetHeight}:force_original_aspect_ratio=decrease")
                        append(",pad=${job.targetWidth}:${job.targetHeight}:(ow-iw)/2:(oh-ih)/2,setsar=1")
                    }
                    append("[v$index];")
                }
                if (audioEnabled) append("[$index:a:0]asetpts=PTS-STARTPTS[a$index];")
            }
            job.segments.indices.forEach { index ->
                if (videoEnabled) append("[v$index]")
                if (audioEnabled) append("[a$index]")
            }
            append("concat=n=${job.segments.size}:v=${if (videoEnabled) 1 else 0}:a=${if (audioEnabled) 1 else 0}")
            if (videoEnabled) append("[outv]")
            if (audioEnabled) append("[outa]")
        }
        val args = baseArguments(job.overwrite).apply {
            job.segments.forEach { segment ->
                literal("-i")
                resource(segment, ResourceAccess.READ)
            }
            literal("-filter_complex", graph)
            if (videoEnabled) {
                val selectedVideoEncoder = checkNotNull(videoEncoder)
                literal("-map", "[outv]", "-c:v", selectedVideoEncoder)
                mediaCodecPixelFormat(selectedVideoEncoder)
                literal("-b:v", job.video.bitrate.bitsPerSecond.toString())
                job.video.keyFrameIntervalFrames?.let { literal("-g", it.toString()) }
                job.video.maxFrameRate?.let { literal("-r", it.toString()) }
            } else {
                literal("-vn")
            }
            if (audioEnabled) {
                literal("-map", "[outa]", "-c:a", checkNotNull(audioEncoder))
                literal("-b:a", job.audio.bitrate.bitsPerSecond.toString())
                job.audio.sampleRate?.let { literal("-ar", it.toString()) }
                job.audio.channels?.let { literal("-ac", it.toString()) }
            } else {
                literal("-an")
            }
            if (job.container == Container.MP4) literal("-movflags", "+faststart")
            outputLimits(job.limits)
            literal("-f", job.container.ffmpegName)
            resource(job.output, ResourceAccess.WRITE)
        }
        return attempt(attempt, videoEncoder, audioEncoder, args)
    }

    private fun MutableList<CommandArgument>.addInput(reference: MediaReference, trim: TimeRange?) {
        if (trim?.accuracy == SeekAccuracy.FAST && trim.startMs > 0) {
            literal("-ss", seconds(trim.startMs))
        }
        literal("-i")
        resource(reference, ResourceAccess.READ)
        if (trim?.accuracy == SeekAccuracy.ACCURATE && trim.startMs > 0) {
            literal("-ss", seconds(trim.startMs))
        }
        trim?.durationMs?.let { literal("-t", seconds(it)) }
    }

    private fun MutableList<CommandArgument>.addVideoOutput(settings: VideoSettings, encoder: String?) {
        when (settings.mode) {
            StreamMode.DROP -> literal("-vn")
            StreamMode.COPY -> literal("-map", "0:v:0?", "-c:v", "copy")
            StreamMode.ENCODE -> {
                val selectedEncoder = checkNotNull(encoder)
                literal("-map", "0:v:0?", "-c:v", selectedEncoder)
                mediaCodecPixelFormat(selectedEncoder)
                literal("-b:v", settings.bitrate.bitsPerSecond.toString())
                settings.keyFrameIntervalFrames?.let { literal("-g", it.toString()) }
                settings.maxFrameRate?.let { literal("-r", it.toString()) }
                scaleFilter(settings.maxWidth, settings.maxHeight)?.let { literal("-vf", it) }
            }
        }
    }

    private fun MutableList<CommandArgument>.mediaCodecPixelFormat(encoder: String) {
        // Android encoders commonly reject planar yuv420p even when they advertise flexible YUV.
        // FFmpeg's MediaCodec encoder accepts NV12 and converts decoded frames when needed.
        if (encoder.endsWith("_mediacodec")) literal("-pix_fmt", "nv12")
    }

    private fun MutableList<CommandArgument>.addAudioOutput(settings: AudioSettings, encoder: String?) {
        when (settings.mode) {
            StreamMode.DROP -> literal("-an")
            StreamMode.COPY -> literal("-map", "0:a:0?", "-c:a", "copy")
            StreamMode.ENCODE -> {
                literal("-map", "0:a:0?", "-c:a", checkNotNull(encoder))
                if (settings.codec !in setOf(AudioCodec.FLAC, AudioCodec.PCM_S16LE)) {
                    literal("-b:a", settings.bitrate.bitsPerSecond.toString())
                }
                settings.sampleRate?.let { literal("-ar", it.toString()) }
                settings.channels?.let { literal("-ac", it.toString()) }
            }
        }
    }

    private fun MutableList<CommandArgument>.outputLimits(limits: ResourceLimits) {
        limits.maxThreads?.let { literal("-threads", it.toString()) }
        limits.maxOutputBytes?.let { literal("-fs", it.toString()) }
    }

    private fun requireVideoCandidates(
        settings: VideoSettings,
        runtimeLicense: RuntimeLicense,
        capabilities: EngineCapabilities,
    ): List<String> {
        val candidates = videoEncoders(settings, runtimeLicense).filter(capabilities::supportsEncoder)
        if (settings.mode == StreamMode.ENCODE && candidates.isEmpty()) {
            throw PlanningException(
                "No compatible ${settings.codec} encoder is available for " +
                    "${settings.encoderPreference} under $runtimeLicense",
            )
        }
        return candidates
    }

    private fun videoEncoders(settings: VideoSettings, runtimeLicense: RuntimeLicense): List<String> {
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

    private fun requireAudioEncoder(
        settings: AudioSettings,
        capabilities: EngineCapabilities,
    ): String? {
        val encoder = audioEncoder(settings)
        if (encoder != null) requireEncoder(encoder, "audio", capabilities)
        return encoder
    }

    private fun audioEncoder(settings: AudioSettings): String? = when (settings.mode) {
        StreamMode.COPY, StreamMode.DROP -> null
        StreamMode.ENCODE -> when (settings.codec) {
            AudioCodec.AAC -> "aac"
            AudioCodec.OPUS -> "libopus"
            AudioCodec.MP3 -> "libmp3lame"
            AudioCodec.VORBIS -> "libvorbis"
            AudioCodec.FLAC -> "flac"
            AudioCodec.PCM_S16LE -> "pcm_s16le"
        }
    }

    private fun validateNetworkPolicy(job: MediaJob) {
        if (job.inputReferences.any { it is MediaReference.NetworkUrl } && !job.allowNetworkInput) {
            throw PlanningException("Network inputs are disabled for this job")
        }
    }

    private fun validateContainer(container: Container, video: VideoSettings, audio: AudioSettings) {
        if (container in AUDIO_ONLY_CONTAINERS && video.mode != StreamMode.DROP) {
            throw PlanningException("$container is an audio-only output container")
        }
        if (video.mode == StreamMode.ENCODE) {
            VIDEO_CODECS_BY_CONTAINER[container]?.let { allowed ->
                if (video.codec !in allowed) {
                    throw PlanningException("$container output does not support ${video.codec} video in this SDK")
                }
            }
        }
        if (audio.mode == StreamMode.ENCODE) {
            AUDIO_CODECS_BY_CONTAINER[container]?.let { allowed ->
                if (audio.codec !in allowed) {
                    throw PlanningException("$container output does not support ${audio.codec} audio in this SDK")
                }
            }
        }
    }

    private fun requireEncoder(name: String, kind: String, capabilities: EngineCapabilities) {
        if (!capabilities.supportsEncoder(name)) {
            throw PlanningException("Required $kind encoder '$name' is unavailable")
        }
    }

    private fun requireFilter(name: String, capabilities: EngineCapabilities) {
        if (!capabilities.supportsFilter(name)) {
            throw PlanningException("Required FFmpeg filter '$name' is unavailable")
        }
    }

    private fun requireMuxer(name: String, capabilities: EngineCapabilities) {
        if (!capabilities.supportsMuxer(name)) {
            throw PlanningException("Required FFmpeg muxer '$name' is unavailable")
        }
    }

    private fun retryCandidates(overwrite: Boolean, candidates: List<String>): List<String> =
        if (overwrite) candidates else candidates.take(1)

    private fun MutableList<String>.addRetryWarnings(
        overwrite: Boolean,
        allCandidates: List<String>,
        attempts: List<ExecutionAttempt>,
    ) {
        if (attempts.size > 1) {
            add("Encoder retries preserve the requested codec but may change hardware acceleration")
        }
        if (!overwrite && allCandidates.size > 1) {
            add("Encoder retries were disabled because a partial non-overwritable output is unsafe")
        }
    }

    private fun commonWarnings(
        job: MediaJob,
        engine: EngineDescriptor,
        attempts: List<ExecutionAttempt>,
    ): List<String> = buildList {
        if (engine.runtimeLicense == RuntimeLicense.UNKNOWN) {
            add("Runtime license is unknown; distribution must be reviewed before release")
        }
        if (job.inputReferences.any { it is MediaReference.FilePath }) {
            add("Android file paths must remain app-accessible under scoped storage")
        }
        if (job.inputReferences.any { it is MediaReference.NetworkUrl }) {
            add("Network input remains subject to the engine host, redirect, timeout, and address policy")
        }
        if (job.limits.maxOutputBytes != null) {
            add("The FFmpeg -fs limit is a best-effort ceiling and may be exceeded by a small muxer trailer")
        }
        if (attempts.any { it.hardwareAccelerated }) {
            add("A compiled MediaCodec encoder still requires device-specific runtime validation")
        }
    }

    private fun scaleFilter(width: Int?, height: Int?): String? {
        if (width == null && height == null) return null
        val w = width?.let { "min($it\\,iw)" } ?: "-2"
        val h = height?.let { "min($it\\,ih)" } ?: "-2"
        return "scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2"
    }

    private fun styleArgument(style: SubtitleStyle?): String? {
        style ?: return null
        return buildList {
            style.fontName?.let { add("FontName=$it") }
            style.fontSize?.let { add("FontSize=$it") }
            style.primaryColor?.let { add("PrimaryColour=$it") }
            style.outlineColor?.let { add("OutlineColour=$it") }
            style.outline?.let { add("Outline=$it") }
            style.shadow?.let { add("Shadow=$it") }
            style.alignment?.let { add("Alignment=$it") }
            style.marginVertical?.let { add("MarginV=$it") }
        }.joinToString(",").ifEmpty { null }
    }

    private fun attempt(
        index: Int,
        videoEncoder: String?,
        audioEncoder: String?,
        arguments: List<CommandArgument>,
    ): ExecutionAttempt = ExecutionAttempt(
        index = index,
        videoEncoder = videoEncoder,
        audioEncoder = audioEncoder,
        hardwareAccelerated = videoEncoder?.endsWith("_mediacodec") == true,
        arguments = arguments,
    )

    private fun baseArguments(overwrite: Boolean): MutableList<CommandArgument> =
        mutableListOf<CommandArgument>().apply {
            literal("-hide_banner", "-nostdin", if (overwrite) "-y" else "-n")
        }

    private fun seconds(milliseconds: Long): String =
        "%.3f".format(java.util.Locale.ROOT, milliseconds / 1_000.0)

    private fun MutableList<CommandArgument>.literal(vararg values: String) {
        values.forEach { add(CommandArgument.Literal(it)) }
    }

    private fun MutableList<CommandArgument>.resource(reference: MediaReference, access: ResourceAccess) {
        add(CommandArgument.Resource(reference, access))
    }

    private data class PlannedAttempts(
        val attempts: List<ExecutionAttempt>,
        val warnings: List<String> = emptyList(),
    )

    private companion object {
        val GPL_ONLY_ENCODERS: Set<String> = setOf("libx264", "libx265")
        val AUDIO_ONLY_CONTAINERS: Set<Container> = setOf(
            Container.MP3,
            Container.OGG,
            Container.WAV,
            Container.FLAC,
        )
        val VIDEO_CODECS_BY_CONTAINER: Map<Container, Set<VideoCodec>> = mapOf(
            Container.MP4 to setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.MPEG4),
            Container.MOV to setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.MPEG4),
            Container.WEBM to setOf(VideoCodec.VP9, VideoCodec.AV1),
            Container.MPEG_TS to setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.MPEG4),
        )
        val AUDIO_CODECS_BY_CONTAINER: Map<Container, Set<AudioCodec>> = mapOf(
            Container.MP4 to setOf(AudioCodec.AAC, AudioCodec.MP3),
            Container.MOV to setOf(AudioCodec.AAC, AudioCodec.PCM_S16LE),
            Container.WEBM to setOf(AudioCodec.OPUS, AudioCodec.VORBIS),
            Container.MPEG_TS to setOf(AudioCodec.AAC, AudioCodec.MP3),
            Container.MP3 to setOf(AudioCodec.MP3),
            Container.OGG to setOf(AudioCodec.OPUS, AudioCodec.VORBIS, AudioCodec.FLAC),
            Container.WAV to setOf(AudioCodec.PCM_S16LE),
            Container.FLAC to setOf(AudioCodec.FLAC),
        )
    }
}
