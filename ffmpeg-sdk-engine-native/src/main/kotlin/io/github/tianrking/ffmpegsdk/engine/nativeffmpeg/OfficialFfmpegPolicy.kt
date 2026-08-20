package io.github.tianrking.ffmpegsdk.engine.nativeffmpeg

public data class OfficialFfmpegNetworkPolicy @JvmOverloads constructor(
    val allowedSchemes: Set<String> = setOf("https"),
    val allowedHosts: Set<String> = emptySet(),
    val blockNonPublicAddresses: Boolean = true,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 30_000,
    val maxRedirects: Int = 5,
    val userAgent: String = "ffmpeg-android-sdk-official/0.3",
) {
    init {
        require(allowedSchemes.isNotEmpty() && allowedSchemes.all { it in setOf("http", "https") }) {
            "Network schemes must be a non-empty subset of http and https"
        }
        require(allowedSchemes.all { it == it.lowercase() }) { "Network schemes must be lowercase" }
        require(allowedHosts.all { it.isNotBlank() && it == it.lowercase() }) {
            "Allowed network hosts must be non-blank lowercase names"
        }
        require(connectTimeoutMs in 1_000..300_000) { "Connect timeout must be between 1s and 5min" }
        require(readTimeoutMs in 1_000..300_000) { "Read timeout must be between 1s and 5min" }
        require(maxRedirects in 0..20) { "Redirect limit must be between 0 and 20" }
        require(userAgent.isNotBlank() && '\r' !in userAgent && '\n' !in userAgent) {
            "User-Agent must be a single non-blank line"
        }
    }
}

public data class OfficialFfmpegRuntimePolicy @JvmOverloads constructor(
    val allowedFfmpegMajorVersions: Set<Int> = setOf(9),
    val allowNetworkInputs: Boolean = false,
    val maxCapturedOutputChars: Int = 64 * 1_024,
    val transactionalOutputs: Boolean = true,
    val maxStagedInputBytes: Long = 2L * 1_024 * 1_024 * 1_024,
    val maxProbeStreams: Int = 256,
    val networkPolicy: OfficialFfmpegNetworkPolicy = OfficialFfmpegNetworkPolicy(),
) {
    init {
        require(allowedFfmpegMajorVersions.isNotEmpty()) {
            "At least one FFmpeg major version must be explicitly allowed"
        }
        require(maxCapturedOutputChars >= 1_024) {
            "Captured output limit must be at least 1024 characters"
        }
        require(maxStagedInputBytes >= 1L * 1_024 * 1_024) {
            "Staged input limit must be at least 1 MiB"
        }
        require(maxProbeStreams in 1..4_096) { "Probe stream limit must be between 1 and 4096" }
    }
}
