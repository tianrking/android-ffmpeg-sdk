package io.github.tianrking.ffmpegsdk.core

public enum class RuntimeLicense {
    LGPL,
    GPL,
    UNKNOWN,
}

public data class EngineDescriptor(
    val name: String,
    val wrapperVersion: String,
    val ffmpegVersion: String,
    val distribution: String,
    val runtimeLicense: RuntimeLicense,
    val externalLibraries: Set<String> = emptySet(),
    val buildConfiguration: Set<String> = emptySet(),
)

public data class EngineCapabilities(
    val isKnown: Boolean,
    val encoders: Set<String> = emptySet(),
    val decoders: Set<String> = emptySet(),
) {
    public fun supportsEncoder(name: String): Boolean = !isKnown || name in encoders

    public companion object {
        public val Unknown: EngineCapabilities = EngineCapabilities(isKnown = false)
    }
}

public enum class ResourceAccess { READ, WRITE }

public sealed interface CommandArgument {
    public data class Literal(val value: String) : CommandArgument {
        init {
            require('\u0000' !in value) { "Arguments must not contain NUL" }
        }
    }

    public data class Resource(
        val reference: MediaReference,
        val access: ResourceAccess,
    ) : CommandArgument
}

public data class EngineRequest(
    val jobId: String,
    val attempt: Int,
    val arguments: List<CommandArgument>,
)

public sealed interface EngineEvent {
    public data class Log(val message: String) : EngineEvent

    public data class Statistics(
        val processedTimeMs: Long,
        val frame: Int,
        val framesPerSecond: Float,
        val outputBytes: Long,
        val bitrateKbps: Double,
        val speed: Double,
    ) : EngineEvent
}

public data class EngineResult(
    val sessionId: String?,
    val exitCode: Int?,
    val cancelled: Boolean,
    val durationMs: Long,
    val output: String,
    val failureDetails: String? = null,
) {
    public val succeeded: Boolean get() = !cancelled && exitCode == 0
}

public data class MediaProbe(
    val durationMs: Long?,
    val formatNames: Set<String>,
    val bitrate: Long?,
    val streams: List<MediaStream>,
    val rawJson: String,
)

public data class MediaStream(
    val index: Int,
    val type: String,
    val codec: String?,
    val width: Int? = null,
    val height: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
)

public interface FfmpegEngine {
    public suspend fun descriptor(): EngineDescriptor

    public suspend fun capabilities(refresh: Boolean = false): EngineCapabilities

    public suspend fun execute(
        request: EngineRequest,
        onEvent: (EngineEvent) -> Unit = {},
    ): EngineResult

    public suspend fun probe(reference: MediaReference): MediaProbe
}
