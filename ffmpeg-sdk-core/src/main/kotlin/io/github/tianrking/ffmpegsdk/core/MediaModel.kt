package io.github.tianrking.ffmpegsdk.core

import java.net.URI
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public const val CURRENT_JOB_SCHEMA_VERSION: Int = 1

@Serializable
public data class MediaJobEnvelope(
    val schemaVersion: Int = CURRENT_JOB_SCHEMA_VERSION,
    val job: TranscodeJob,
) {
    init {
        require(schemaVersion == CURRENT_JOB_SCHEMA_VERSION) {
            "Unsupported media job schema version: $schemaVersion"
        }
    }
}

@Serializable
public sealed interface MediaReference {
    @Serializable
    @SerialName("file_path")
    public data class FilePath(val path: String) : MediaReference {
        init {
            require(path.isNotBlank()) { "File path must not be blank" }
            require('\u0000' !in path) { "File path must not contain NUL" }
        }
    }

    @Serializable
    @SerialName("content_uri")
    public data class ContentUri(val uri: String) : MediaReference {
        init {
            require('\u0000' !in uri) { "URI must not contain NUL" }
            // Android Uri tolerates literal spaces; encode only those before structural validation.
            val parsed = parseUri(uri.replace(" ", "%20"), "content URI")
            require(parsed.scheme?.equals("content", ignoreCase = true) == true &&
                !parsed.rawAuthority.isNullOrBlank()) {
                "Expected an absolute content:// URI with an authority"
            }
            require(parsed.userInfo == null && parsed.fragment == null) {
                "Content URIs must not contain credentials or fragments"
            }
        }
    }

    @Serializable
    @SerialName("network_url")
    public data class NetworkUrl(val url: String) : MediaReference {
        init {
            require('\u0000' !in url) { "URL must not contain NUL" }
            val parsed = parseUri(url, "network URL")
            require(parsed.scheme?.lowercase() in setOf("https", "http") && !parsed.host.isNullOrBlank()) {
                "Only absolute HTTP(S) network inputs with a host are supported"
            }
            require(parsed.userInfo == null) { "Network URLs must not contain embedded credentials" }
            require(parsed.fragment == null) { "Network URLs must not contain fragments" }
        }
    }

    private companion object {
        fun parseUri(value: String, label: String): URI =
            runCatching { URI(value) }.getOrElse { cause ->
                throw IllegalArgumentException("Invalid $label", cause)
            }
    }
}

/** Common contract implemented by every typed media operation accepted by [FfmpegSdk]. */
@Serializable
public sealed interface MediaJob {
    public val id: String
    public val output: MediaReference
    public val overwrite: Boolean
    public val allowNetworkInput: Boolean
    public val limits: ResourceLimits

    /** All resources that can be opened as inputs by the execution engine. */
    public val inputReferences: List<MediaReference>

    /** Media inputs whose duration contributes to progress and resource-limit checks. */
    public val progressReferences: List<MediaReference>
        get() = inputReferences
}

@Serializable
@SerialName("transcode")
public data class TranscodeJob(
    override val id: String = UUID.randomUUID().toString(),
    val input: MediaReference,
    override val output: MediaReference,
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val container: Container = Container.MP4,
    val trim: TimeRange? = null,
    override val overwrite: Boolean = false,
    override val allowNetworkInput: Boolean = false,
    val optimizeForStreaming: Boolean = true,
    override val limits: ResourceLimits = ResourceLimits(),
) : MediaJob {
    override val inputReferences: List<MediaReference> get() = listOf(input)
    override val progressReferences: List<MediaReference> get() = listOf(input)

    init {
        require(id.isNotBlank()) { "Job id must not be blank" }
        require(input != output) { "Input and output must be different resources" }
        require(output !is MediaReference.NetworkUrl) { "Network outputs are not supported" }
        require(video.mode != StreamMode.DROP || audio.mode != StreamMode.DROP) {
            "At least one output stream is required"
        }
    }
}

@Serializable
public data class ResourceLimits(
    /** Reject an individual probed input longer than this value. */
    val maxInputDurationMs: Long? = null,
    /** Reject a multi-input job when the sum of known input durations exceeds this value. */
    val maxTotalInputDurationMs: Long? = null,
    /** Reject a video stream whose coded width multiplied by height exceeds this value. */
    val maxInputPixels: Long? = null,
    /** Pass an FFmpeg output-size ceiling through `-fs`. */
    val maxOutputBytes: Long? = null,
    /** Bound codec/filter worker threads when the selected FFmpeg component honors `-threads`. */
    val maxThreads: Int? = null,
) {
    init {
        require(maxInputDurationMs == null || maxInputDurationMs > 0) {
            "Maximum input duration must be positive"
        }
        require(maxTotalInputDurationMs == null || maxTotalInputDurationMs > 0) {
            "Maximum total input duration must be positive"
        }
        require(maxInputPixels == null || maxInputPixels > 0) {
            "Maximum input pixel count must be positive"
        }
        require(maxOutputBytes == null || maxOutputBytes > 0) {
            "Maximum output size must be positive"
        }
        require(maxThreads == null || maxThreads in 1..256) {
            "Maximum thread count must be between 1 and 256"
        }
    }

    public val requiresProbe: Boolean
        get() = maxInputDurationMs != null || maxTotalInputDurationMs != null || maxInputPixels != null
}

@Serializable
public data class TimeRange(
    val startMs: Long = 0,
    val durationMs: Long? = null,
    val accuracy: SeekAccuracy = SeekAccuracy.ACCURATE,
) {
    init {
        require(startMs >= 0) { "Start time must be non-negative" }
        require(durationMs == null || durationMs > 0) { "Duration must be positive" }
    }
}

@Serializable
public data class VideoSettings(
    val mode: StreamMode = StreamMode.ENCODE,
    val codec: VideoCodec = VideoCodec.H264,
    val encoderPreference: EncoderPreference = EncoderPreference.PREFER_HARDWARE,
    val bitrate: Bitrate = Bitrate(4_000_000),
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val maxFrameRate: Int? = null,
    val keyFrameIntervalFrames: Int? = null,
) {
    init {
        require(maxWidth == null || maxWidth > 0) { "Maximum width must be positive" }
        require(maxHeight == null || maxHeight > 0) { "Maximum height must be positive" }
        require(maxFrameRate == null || maxFrameRate > 0) { "Frame rate must be positive" }
        require(keyFrameIntervalFrames == null || keyFrameIntervalFrames > 0) {
            "Key-frame interval must be positive"
        }
    }
}

@Serializable
public data class AudioSettings(
    val mode: StreamMode = StreamMode.ENCODE,
    val codec: AudioCodec = AudioCodec.AAC,
    val bitrate: Bitrate = Bitrate(128_000),
    val sampleRate: Int? = 48_000,
    val channels: Int? = 2,
) {
    init {
        require(sampleRate == null || sampleRate > 0) { "Sample rate must be positive" }
        require(channels == null || channels in 1..8) { "Channel count must be between 1 and 8" }
    }
}

@Serializable
@JvmInline
public value class Bitrate(public val bitsPerSecond: Long) {
    init {
        require(bitsPerSecond > 0) { "Bitrate must be positive" }
    }
}

@Serializable
public enum class StreamMode { COPY, ENCODE, DROP }

@Serializable
public enum class VideoCodec { H264, HEVC, VP9, AV1, MPEG4 }

@Serializable
public enum class AudioCodec { AAC, OPUS, MP3, VORBIS, FLAC, PCM_S16LE }

@Serializable
public enum class EncoderPreference {
    HARDWARE_ONLY,
    PREFER_HARDWARE,
    PREFER_SOFTWARE,
    SOFTWARE_ONLY,
}

@Serializable
public enum class Container(public val ffmpegName: String) {
    MP4("mp4"),
    MATROSKA("matroska"),
    WEBM("webm"),
    MPEG_TS("mpegts"),
    MOV("mov"),
    MP3("mp3"),
    OGG("ogg"),
    WAV("wav"),
    FLAC("flac"),
}

@Serializable
public enum class SeekAccuracy { FAST, ACCURATE }
