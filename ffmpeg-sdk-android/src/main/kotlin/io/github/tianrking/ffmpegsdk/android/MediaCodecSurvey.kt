package io.github.tianrking.ffmpegsdk.android

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

public data class AndroidCodec(
    val name: String,
    val canonicalName: String,
    val isEncoder: Boolean,
    val isHardwareAccelerated: Boolean,
    val isSoftwareOnly: Boolean,
    val isVendor: Boolean,
    val supportedTypes: Set<String>,
    val typeCapabilities: List<AndroidCodecType>,
)

public data class AndroidCodecType(
    val mimeType: String,
    val maxSupportedInstances: Int,
    val colorFormats: Set<Int>,
    val profileLevels: List<CodecProfileLevel>,
    val videoCapabilities: AndroidVideoCapabilities? = null,
    val audioCapabilities: AndroidAudioCapabilities? = null,
)

public data class IntRangeValue(val minimum: Int, val maximum: Int)

public data class DoubleRangeValue(val minimum: Double, val maximum: Double)

public data class AndroidVideoCapabilities(
    val widthAlignment: Int,
    val heightAlignment: Int,
    val supportedWidths: IntRangeValue,
    val supportedHeights: IntRangeValue,
    val bitrateRange: IntRangeValue,
    val frameRateRange: DoubleRangeValue,
)

public data class AndroidAudioCapabilities(
    val bitrateRange: IntRangeValue,
    val supportedSampleRates: Set<Int>,
    val sampleRateRanges: List<IntRangeValue>,
    val maxInputChannels: Int,
)

public data class CodecProfileLevel(
    val profile: Int,
    val level: Int,
)

public data class EncoderRequest(
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val frameRate: Int? = null,
    val profile: Int? = null,
    val level: Int? = null,
    val colorFormat: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
) {
    init {
        require(mimeType.isNotBlank() && '/' in mimeType) { "A valid codec MIME type is required" }
        require((width == null) == (height == null)) { "Video width and height must be supplied together" }
        require(width == null || width > 0) { "Video width must be positive" }
        require(height == null || height > 0) { "Video height must be positive" }
        require(bitrate == null || bitrate > 0) { "Bitrate must be positive" }
        require(frameRate == null || frameRate > 0) { "Frame rate must be positive" }
        require(profile == null || profile >= 0) { "Codec profile must be non-negative" }
        require(level == null || level >= 0) { "Codec level must be non-negative" }
        require(colorFormat == null || colorFormat >= 0) { "Color format must be non-negative" }
        require(sampleRate == null || sampleRate > 0) { "Sample rate must be positive" }
        require(channels == null || channels > 0) { "Channel count must be positive" }
    }
}

/**
 * Takes a runtime snapshot of the codecs exposed by this exact Android device.
 *
 * Android codec support is device-specific. This survey is intentionally kept separate from
 * FFmpeg's compiled encoder list: both must be checked before promising hardware acceleration.
 */
public class MediaCodecSurvey(
    private val codecList: MediaCodecList = MediaCodecList(MediaCodecList.ALL_CODECS),
) {
    public fun codecs(encodersOnly: Boolean = false): List<AndroidCodec> =
        codecList.codecInfos
            .asSequence()
            .filterNot { encodersOnly && !it.isEncoder }
            .map(::describe)
            .sortedWith(compareByDescending<AndroidCodec> { it.isHardwareAccelerated }.thenBy { it.name })
            .toList()

    /** Returns the platform-selected codec name, or null when this device rejects the request. */
    public fun findEncoder(request: EncoderRequest): String? {
        val format = request.toMediaFormat()
        return runCatching { codecList.findEncoderForFormat(format) }.getOrNull()
    }

    /** Returns every encoder whose advertised per-type capabilities accept the complete request. */
    public fun findEncoders(
        request: EncoderRequest,
        hardwareOnly: Boolean = false,
    ): List<AndroidCodec> {
        val format = request.toMediaFormat()
        return codecList.codecInfos.asSequence()
            .filter(MediaCodecInfo::isEncoder)
            .filter { info -> info.supportedTypes.any { it.equals(request.mimeType, ignoreCase = true) } }
            .filter { info ->
                runCatching {
                    val type = info.supportedTypes.first {
                        it.equals(request.mimeType, ignoreCase = true)
                    }
                    info.getCapabilitiesForType(type).isFormatSupported(format)
                }.getOrDefault(false)
            }
            .map(::describe)
            .filterNot { hardwareOnly && !it.isHardwareAccelerated }
            .sortedWith(
                compareByDescending<AndroidCodec> { it.isHardwareAccelerated }
                    .thenBy { it.isSoftwareOnly }
                    .thenBy { it.name },
            )
            .toList()
    }

    /** Selects a hardware candidate first while retaining the platform order as a tiebreaker. */
    public fun findPreferredEncoder(request: EncoderRequest): String? =
        findEncoders(request).firstOrNull()?.name

    private fun describe(info: MediaCodecInfo): AndroidCodec = AndroidCodec(
        name = info.name,
        canonicalName = if (Build.VERSION.SDK_INT >= 29) info.canonicalName else info.name,
        isEncoder = info.isEncoder,
        isHardwareAccelerated = if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated
        } else {
            !looksSoftwareOnly(info.name)
        },
        isSoftwareOnly = if (Build.VERSION.SDK_INT >= 29) {
            info.isSoftwareOnly
        } else {
            looksSoftwareOnly(info.name)
        },
        isVendor = if (Build.VERSION.SDK_INT >= 29) info.isVendor else looksVendor(info.name),
        supportedTypes = info.supportedTypes.map(String::lowercase).toSet(),
        typeCapabilities = info.supportedTypes.mapNotNull { type -> describeType(info, type) },
    )

    private fun describeType(info: MediaCodecInfo, type: String): AndroidCodecType? =
        runCatching { info.getCapabilitiesForType(type) }.getOrNull()?.let { capabilities ->
            AndroidCodecType(
                mimeType = type.lowercase(),
                maxSupportedInstances = capabilities.maxSupportedInstances,
                colorFormats = capabilities.colorFormats.toSet(),
                profileLevels = capabilities.profileLevels.map {
                    CodecProfileLevel(profile = it.profile, level = it.level)
                },
                videoCapabilities = runCatching { capabilities.videoCapabilities }.getOrNull()?.let { video ->
                    runCatching {
                        AndroidVideoCapabilities(
                            widthAlignment = video.widthAlignment,
                            heightAlignment = video.heightAlignment,
                            supportedWidths = IntRangeValue(
                                video.supportedWidths.lower,
                                video.supportedWidths.upper,
                            ),
                            supportedHeights = IntRangeValue(
                                video.supportedHeights.lower,
                                video.supportedHeights.upper,
                            ),
                            bitrateRange = IntRangeValue(
                                video.bitrateRange.lower,
                                video.bitrateRange.upper,
                            ),
                            frameRateRange = DoubleRangeValue(
                                video.supportedFrameRates.lower.toDouble(),
                                video.supportedFrameRates.upper.toDouble(),
                            ),
                        )
                    }.getOrNull()
                },
                audioCapabilities = runCatching { capabilities.audioCapabilities }.getOrNull()?.let { audio ->
                    runCatching {
                        AndroidAudioCapabilities(
                            bitrateRange = IntRangeValue(
                                audio.bitrateRange.lower,
                                audio.bitrateRange.upper,
                            ),
                            supportedSampleRates = audio.supportedSampleRates?.toSet().orEmpty(),
                            sampleRateRanges = audio.supportedSampleRateRanges.map { range ->
                                IntRangeValue(range.lower, range.upper)
                            },
                            maxInputChannels = audio.maxInputChannelCount,
                        )
                    }.getOrNull()
                },
            )
        }

    private fun EncoderRequest.toMediaFormat(): MediaFormat = MediaFormat().apply {
        setString(MediaFormat.KEY_MIME, mimeType.lowercase())
        width?.let { setInteger(MediaFormat.KEY_WIDTH, it) }
        height?.let { setInteger(MediaFormat.KEY_HEIGHT, it) }
        bitrate?.let { setInteger(MediaFormat.KEY_BIT_RATE, it) }
        frameRate?.let { setInteger(MediaFormat.KEY_FRAME_RATE, it) }
        profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
        level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
        colorFormat?.let { setInteger(MediaFormat.KEY_COLOR_FORMAT, it) }
        sampleRate?.let { setInteger(MediaFormat.KEY_SAMPLE_RATE, it) }
        channels?.let { setInteger(MediaFormat.KEY_CHANNEL_COUNT, it) }
    }

    private fun looksSoftwareOnly(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized.startsWith("omx.google.") ||
            normalized.startsWith("c2.android.") ||
            normalized.startsWith("c2.google.") ||
            normalized.contains("sw.") ||
            normalized.contains("software")
    }

    private fun looksVendor(name: String): Boolean {
        val normalized = name.lowercase()
        return !normalized.startsWith("omx.google.") &&
            !normalized.startsWith("c2.android.") &&
            !normalized.startsWith("c2.google.")
    }
}
