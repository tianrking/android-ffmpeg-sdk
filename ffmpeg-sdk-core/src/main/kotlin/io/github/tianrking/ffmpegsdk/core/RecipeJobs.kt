package io.github.tianrking.ffmpegsdk.core

import java.nio.charset.Charset
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class ImageFormat(
    public val encoder: String,
    public val mimeType: String,
) {
    PNG("png", "image/png"),
    JPEG("mjpeg", "image/jpeg"),
    WEBP("libwebp", "image/webp"),
}

@Serializable
public enum class WaveformScale { LINEAR, LOGARITHMIC, SQUARE_ROOT, CUBE_ROOT }

@Serializable
@SerialName("thumbnail")
public data class ThumbnailJob(
    override val id: String = UUID.randomUUID().toString(),
    val input: MediaReference,
    override val output: MediaReference,
    val positionMs: Long = 0,
    val accuracy: SeekAccuracy = SeekAccuracy.ACCURATE,
    val maxWidth: Int? = 1_280,
    val maxHeight: Int? = 720,
    val format: ImageFormat = ImageFormat.PNG,
    override val overwrite: Boolean = false,
    override val allowNetworkInput: Boolean = false,
    override val limits: ResourceLimits = ResourceLimits(),
) : MediaJob {
    override val inputReferences: List<MediaReference> get() = listOf(input)

    init {
        validateCommonJob(id, inputReferences, output)
        require(positionMs >= 0) { "Thumbnail position must be non-negative" }
        require(maxWidth == null || maxWidth > 0) { "Maximum width must be positive" }
        require(maxHeight == null || maxHeight > 0) { "Maximum height must be positive" }
    }
}

@Serializable
@SerialName("waveform")
public data class WaveformJob(
    override val id: String = UUID.randomUUID().toString(),
    val input: MediaReference,
    override val output: MediaReference,
    val width: Int = 1_280,
    val height: Int = 320,
    val colors: List<String> = listOf("#33B5E5"),
    val splitChannels: Boolean = false,
    val scale: WaveformScale = WaveformScale.LINEAR,
    val format: ImageFormat = ImageFormat.PNG,
    override val overwrite: Boolean = false,
    override val allowNetworkInput: Boolean = false,
    override val limits: ResourceLimits = ResourceLimits(),
) : MediaJob {
    override val inputReferences: List<MediaReference> get() = listOf(input)

    init {
        validateCommonJob(id, inputReferences, output)
        require(width in 16..16_384 && height in 16..16_384) {
            "Waveform dimensions must be between 16 and 16384"
        }
        require(colors.isNotEmpty() && colors.size <= 8) { "Waveform requires between 1 and 8 colors" }
        colors.forEach { color ->
            require(WAVEFORM_COLOR.matches(color)) { "Unsupported waveform color: $color" }
        }
    }
}

@Serializable
public data class SubtitleStyle(
    val fontName: String? = null,
    val fontSize: Int? = null,
    val primaryColor: String? = null,
    val outlineColor: String? = null,
    val outline: Double? = null,
    val shadow: Double? = null,
    val alignment: Int? = null,
    val marginVertical: Int? = null,
) {
    init {
        fontName?.let {
            require(it.isNotBlank() && SAFE_STYLE_TEXT.matches(it)) { "Unsupported subtitle font name" }
        }
        require(fontSize == null || fontSize in 1..512) { "Subtitle font size must be between 1 and 512" }
        listOfNotNull(primaryColor, outlineColor).forEach { color ->
            require(ASS_COLOR.matches(color)) { "Subtitle colors must use ASS &HAABBGGRR syntax" }
        }
        require(outline == null || outline in 0.0..32.0) { "Subtitle outline must be between 0 and 32" }
        require(shadow == null || shadow in 0.0..32.0) { "Subtitle shadow must be between 0 and 32" }
        require(alignment == null || alignment in 1..9) { "Subtitle alignment must be between 1 and 9" }
        require(marginVertical == null || marginVertical in 0..10_000) {
            "Subtitle vertical margin must be between 0 and 10000"
        }
    }
}

@Serializable
@SerialName("subtitle_burn")
public data class SubtitleBurnJob(
    override val id: String = UUID.randomUUID().toString(),
    val input: MediaReference,
    val subtitles: MediaReference,
    override val output: MediaReference,
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(mode = StreamMode.COPY),
    val container: Container = Container.MP4,
    val trim: TimeRange? = null,
    val characterEncoding: String = "UTF-8",
    val style: SubtitleStyle? = null,
    override val overwrite: Boolean = false,
    override val allowNetworkInput: Boolean = false,
    override val limits: ResourceLimits = ResourceLimits(),
) : MediaJob {
    override val inputReferences: List<MediaReference> get() = listOf(input, subtitles)
    override val progressReferences: List<MediaReference> get() = listOf(input)

    init {
        validateCommonJob(id, inputReferences, output)
        require(video.mode == StreamMode.ENCODE) { "Burning subtitles requires video encoding" }
        require(runCatching { Charset.isSupported(characterEncoding) }.getOrDefault(false)) {
            "Unsupported subtitle character encoding: $characterEncoding"
        }
    }
}

@Serializable
@SerialName("concat")
public data class ConcatJob(
    override val id: String = UUID.randomUUID().toString(),
    val segments: List<MediaReference>,
    override val output: MediaReference,
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val targetWidth: Int? = null,
    val targetHeight: Int? = null,
    val container: Container = Container.MP4,
    override val overwrite: Boolean = false,
    override val allowNetworkInput: Boolean = false,
    override val limits: ResourceLimits = ResourceLimits(),
) : MediaJob {
    override val inputReferences: List<MediaReference> get() = segments

    init {
        validateCommonJob(id, inputReferences, output)
        require(segments.size in 2..64) { "Concat requires between 2 and 64 segments" }
        require(video.mode != StreamMode.COPY && audio.mode != StreamMode.COPY) {
            "Filter-based concat supports ENCODE or DROP; use remux only for already compatible streams"
        }
        require(video.mode != StreamMode.DROP || audio.mode != StreamMode.DROP) {
            "Concat requires at least one output stream"
        }
        require((targetWidth == null) == (targetHeight == null)) {
            "Concat target width and height must be supplied together"
        }
        require(targetWidth == null || (targetWidth > 0 && targetWidth % 2 == 0)) {
            "Concat target width must be a positive even number"
        }
        require(targetHeight == null || (targetHeight > 0 && targetHeight % 2 == 0)) {
            "Concat target height must be a positive even number"
        }
    }
}

/** Typed constructors for the common recipes that can be represented by [TranscodeJob]. */
public object MediaRecipes {
    public fun remux(
        input: MediaReference,
        output: MediaReference,
        container: Container,
        overwrite: Boolean = false,
        trim: TimeRange? = null,
    ): TranscodeJob = TranscodeJob(
        input = input,
        output = output,
        video = VideoSettings(mode = StreamMode.COPY),
        audio = AudioSettings(mode = StreamMode.COPY),
        container = container,
        trim = trim,
        overwrite = overwrite,
    )

    public fun h264Export(
        input: MediaReference,
        output: MediaReference,
        overwrite: Boolean = false,
        video: VideoSettings = VideoSettings(codec = VideoCodec.H264),
        audio: AudioSettings = AudioSettings(),
        trim: TimeRange? = null,
    ): TranscodeJob = TranscodeJob(
        input = input,
        output = output,
        video = video.copy(codec = VideoCodec.H264, mode = StreamMode.ENCODE),
        audio = audio,
        container = Container.MP4,
        trim = trim,
        overwrite = overwrite,
    )

    public fun hevcExport(
        input: MediaReference,
        output: MediaReference,
        overwrite: Boolean = false,
        video: VideoSettings = VideoSettings(codec = VideoCodec.HEVC),
        audio: AudioSettings = AudioSettings(),
        trim: TimeRange? = null,
    ): TranscodeJob = TranscodeJob(
        input = input,
        output = output,
        video = video.copy(codec = VideoCodec.HEVC, mode = StreamMode.ENCODE),
        audio = audio,
        container = Container.MP4,
        trim = trim,
        overwrite = overwrite,
    )

    public fun extractAudio(
        input: MediaReference,
        output: MediaReference,
        codec: AudioCodec = AudioCodec.AAC,
        container: Container = Container.MP4,
        overwrite: Boolean = false,
        bitrate: Bitrate = Bitrate(128_000),
        trim: TimeRange? = null,
    ): TranscodeJob = TranscodeJob(
        input = input,
        output = output,
        video = VideoSettings(mode = StreamMode.DROP),
        audio = AudioSettings(mode = StreamMode.ENCODE, codec = codec, bitrate = bitrate),
        container = container,
        trim = trim,
        overwrite = overwrite,
        optimizeForStreaming = false,
    )

    public fun trim(
        input: MediaReference,
        output: MediaReference,
        range: TimeRange,
        container: Container = Container.MP4,
        video: VideoSettings = VideoSettings(),
        audio: AudioSettings = AudioSettings(),
        overwrite: Boolean = false,
    ): TranscodeJob = TranscodeJob(
        input = input,
        output = output,
        video = video,
        audio = audio,
        container = container,
        trim = range,
        overwrite = overwrite,
    )

    public fun thumbnail(
        input: MediaReference,
        output: MediaReference,
        positionMs: Long = 0,
        overwrite: Boolean = false,
    ): ThumbnailJob = ThumbnailJob(
        input = input,
        output = output,
        positionMs = positionMs,
        overwrite = overwrite,
    )

    public fun waveform(
        input: MediaReference,
        output: MediaReference,
        overwrite: Boolean = false,
    ): WaveformJob = WaveformJob(input = input, output = output, overwrite = overwrite)

    public fun burnSubtitles(
        input: MediaReference,
        subtitles: MediaReference,
        output: MediaReference,
        overwrite: Boolean = false,
    ): SubtitleBurnJob = SubtitleBurnJob(
        input = input,
        subtitles = subtitles,
        output = output,
        overwrite = overwrite,
    )

    public fun concat(
        segments: List<MediaReference>,
        output: MediaReference,
        overwrite: Boolean = false,
    ): ConcatJob = ConcatJob(segments = segments, output = output, overwrite = overwrite)
}

private fun validateCommonJob(
    id: String,
    inputs: List<MediaReference>,
    output: MediaReference,
) {
    require(id.isNotBlank()) { "Job id must not be blank" }
    require(inputs.isNotEmpty()) { "At least one input is required" }
    require(output !is MediaReference.NetworkUrl) { "Network outputs are not supported" }
    require(output !in inputs) { "Output must be different from every input" }
}

private val WAVEFORM_COLOR: Regex = Regex(
    "(?:#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?|0x[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?|" +
        "[A-Za-z]{1,32})(?:@(?:0(?:\\.\\d{1,3})?|1(?:\\.0{1,3})?))?",
)
private val SAFE_STYLE_TEXT: Regex = Regex("[\\p{L}\\p{N} ._+-]{1,128}")
private val ASS_COLOR: Regex = Regex("&H[0-9A-Fa-f]{8}&?")
