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
)

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
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, request.mimeType)
            request.width?.let { setInteger(MediaFormat.KEY_WIDTH, it) }
            request.height?.let { setInteger(MediaFormat.KEY_HEIGHT, it) }
            request.bitrate?.let { setInteger(MediaFormat.KEY_BIT_RATE, it) }
            request.frameRate?.let { setInteger(MediaFormat.KEY_FRAME_RATE, it) }
            request.profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
        }
        return codecList.findEncoderForFormat(format)
    }

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
            )
        }

    private fun looksSoftwareOnly(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized.startsWith("omx.google.") ||
            normalized.startsWith("c2.android.") ||
            normalized.contains("sw.") ||
            normalized.contains("software")
    }

    private fun looksVendor(name: String): Boolean {
        val normalized = name.lowercase()
        return !normalized.startsWith("omx.google.") && !normalized.startsWith("c2.android.")
    }
}
