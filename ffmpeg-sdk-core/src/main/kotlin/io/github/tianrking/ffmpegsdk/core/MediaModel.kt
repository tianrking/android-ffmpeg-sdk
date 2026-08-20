package io.github.tianrking.ffmpegsdk.core

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
            require(uri.startsWith("content://")) { "Expected a content:// URI" }
            require('\u0000' !in uri) { "URI must not contain NUL" }
        }
    }

    @Serializable
    @SerialName("network_url")
    public data class NetworkUrl(val url: String) : MediaReference {
        init {
            require(url.startsWith("https://") || url.startsWith("http://")) {
                "Only HTTP(S) network inputs are supported"
            }
            require('\u0000' !in url) { "URL must not contain NUL" }
        }
    }
}

@Serializable
public data class TranscodeJob(
    val id: String = UUID.randomUUID().toString(),
    val input: MediaReference,
    val output: MediaReference,
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val container: Container = Container.MP4,
    val trim: TimeRange? = null,
    val overwrite: Boolean = false,
    val allowNetworkInput: Boolean = false,
    val optimizeForStreaming: Boolean = true,
) {
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
public enum class AudioCodec { AAC, OPUS }

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
}

@Serializable
public enum class SeekAccuracy { FAST, ACCURATE }
