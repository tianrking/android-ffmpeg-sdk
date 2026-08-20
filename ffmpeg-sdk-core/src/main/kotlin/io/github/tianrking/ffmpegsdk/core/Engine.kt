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
    val filters: Set<String> = emptySet(),
    val muxers: Set<String> = emptySet(),
    val demuxers: Set<String> = emptySet(),
    val encodersKnown: Boolean = isKnown,
    val decodersKnown: Boolean = isKnown,
    val filtersKnown: Boolean = false,
    val muxersKnown: Boolean = false,
    val demuxersKnown: Boolean = false,
) {
    public fun supportsEncoder(name: String): Boolean = !encodersKnown || name in encoders

    public fun supportsDecoder(name: String): Boolean = !decodersKnown || name in decoders

    public fun supportsFilter(name: String): Boolean = !filtersKnown || name in filters

    public fun supportsMuxer(name: String): Boolean = !muxersKnown || name in muxers

    public fun supportsDemuxer(name: String): Boolean = !demuxersKnown || name in demuxers

    public companion object {
        public val Unknown: EngineCapabilities = EngineCapabilities(isKnown = false)
    }
}

public enum class ResourceAccess {
    READ,
    /** The resource must resolve to a seekable local filename, for example for the subtitles filter. */
    READ_SEEKABLE,
    WRITE,
}

public enum class ResourceEscaping {
    /** Insert the resolved value unchanged into the composite argument. */
    NONE,
    /** Quote and escape the resolved value as a single FFmpeg filter-option value. */
    FFMPEG_FILTER_VALUE,
}

public sealed interface ArgumentPart {
    public data class Literal(val value: String) : ArgumentPart {
        init {
            require('\u0000' !in value) { "Arguments must not contain NUL" }
        }
    }

    public data class Resource(
        val reference: MediaReference,
        val access: ResourceAccess = ResourceAccess.READ,
        val escaping: ResourceEscaping = ResourceEscaping.NONE,
    ) : ArgumentPart {
        init {
            require(access != ResourceAccess.WRITE) { "Composite output resources are not supported" }
        }
    }
}

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

    /** A single argv entry composed without passing through a shell. */
    public data class Composite(val parts: List<ArgumentPart>) : CommandArgument {
        init {
            require(parts.isNotEmpty()) { "Composite arguments require at least one part" }
        }
    }
}

public data class EngineRequest(
    val jobId: String,
    val attempt: Int,
    val arguments: List<CommandArgument>,
    val overwrite: Boolean = false,
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

public enum class EngineFailureCategory {
    COMMAND,
    RESOURCE,
    POLICY,
    RUNTIME,
    OUTPUT_COMMIT,
    PROCESS_DIED,
}

/** A typed engine boundary failure that can be preserved in [EngineResult]. */
public class EngineException(
    public val category: EngineFailureCategory,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public data class EngineResult(
    val sessionId: String?,
    val exitCode: Int?,
    val cancelled: Boolean,
    val durationMs: Long,
    val output: String,
    val failureDetails: String? = null,
    val failureCategory: EngineFailureCategory? = null,
) {
    public val succeeded: Boolean get() = !cancelled && exitCode == 0
}

public data class MediaProbe(
    val durationMs: Long?,
    val formatNames: Set<String>,
    val bitrate: Long?,
    val streams: List<MediaStream>,
    val rawJson: String,
    val streamsTruncated: Boolean = false,
    val rawJsonTruncated: Boolean = false,
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
