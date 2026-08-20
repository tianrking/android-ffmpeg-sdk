package io.github.tianrking.ffmpegsdk.engine.nativeffmpeg

import android.content.Context
import io.github.tianrking.ffmpegsdk.core.EngineCapabilities
import io.github.tianrking.ffmpegsdk.core.EngineDescriptor
import io.github.tianrking.ffmpegsdk.core.EngineEvent
import io.github.tianrking.ffmpegsdk.core.EngineException
import io.github.tianrking.ffmpegsdk.core.EngineFailureCategory
import io.github.tianrking.ffmpegsdk.core.EngineRequest
import io.github.tianrking.ffmpegsdk.core.EngineResult
import io.github.tianrking.ffmpegsdk.core.FfmpegEngine
import io.github.tianrking.ffmpegsdk.core.MediaProbe
import io.github.tianrking.ffmpegsdk.core.MediaReference
import io.github.tianrking.ffmpegsdk.core.MediaStream
import io.github.tianrking.ffmpegsdk.core.RuntimeLicense
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Engine backed by a signed FFmpeg release and this repository's small JNI/fftools runner bridge.
 * Native invocations are serialized; the runner shared object is unloaded after every command so
 * private fftools global state never leaks into the next session.
 */
public class OfficialFfmpegEngine @JvmOverloads constructor(
    context: Context,
    private val policy: OfficialFfmpegRuntimePolicy = OfficialFfmpegRuntimePolicy(),
) : FfmpegEngine {
    private val applicationContext = context.applicationContext
    private val resolver = NativeResourceResolver(applicationContext, policy)
    private val executionSemaphore = Semaphore(1)
    private val descriptorMutex = Mutex()
    private val capabilitiesMutex = Mutex()

    @Volatile
    private var cachedDescriptor: EngineDescriptor? = null

    @Volatile
    private var cachedCapabilities: EngineCapabilities? = null

    init {
        try {
            check(NativeBindings.nativeInitialize(applicationContext)) {
                "Unable to initialize official FFmpeg JNI application context"
            }
        } catch (failure: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "The official FFmpeg native runtime is missing. Run " +
                    "scripts/build-official-ffmpeg.ps1 before packaging the application.",
                failure,
            )
        }
    }

    override suspend fun descriptor(): EngineDescriptor = descriptorMutex.withLock {
        cachedDescriptor?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val version = NativeBindings.nativeVersion()
            val major = VERSION_MAJOR.find(version)?.groupValues?.get(1)?.toIntOrNull()
                ?: error("Unable to parse FFmpeg version: $version")
            require(major in policy.allowedFfmpegMajorVersions) {
                "FFmpeg major $major is outside the explicitly allowed set " +
                    policy.allowedFfmpegMajorVersions.sorted()
            }
            val configuration = NativeBindings.nativeConfiguration()
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .toSet()
            require("--enable-gpl" !in configuration && "--enable-nonfree" !in configuration) {
                "The core-lgpl engine refuses GPL or nonfree FFmpeg builds"
            }
            val licenseText = NativeBindings.nativeLicense()
            val runtimeLicense = when {
                licenseText.contains("Lesser", ignoreCase = true) ||
                    licenseText.contains("LGPL", ignoreCase = true) -> RuntimeLicense.LGPL
                licenseText.contains("GPL", ignoreCase = true) -> RuntimeLicense.GPL
                else -> RuntimeLicense.UNKNOWN
            }
            EngineDescriptor(
                name = "Official FFmpeg native",
                wrapperVersion = BRIDGE_VERSION,
                ffmpegVersion = version,
                distribution = "signed FFmpeg/FFmpeg n9.0.1 source + repository JNI bridge",
                runtimeLicense = runtimeLicense,
                externalLibraries = configuration
                    .filter { it.startsWith("--enable-lib") }
                    .map { it.removePrefix("--enable-") }
                    .toSet(),
                buildConfiguration = configuration,
            ).also { cachedDescriptor = it }
        }
    }

    override suspend fun capabilities(refresh: Boolean): EngineCapabilities =
        capabilitiesMutex.withLock {
            if (!refresh) cachedCapabilities?.let { return@withLock it }
            descriptor()
            withContext(Dispatchers.IO) {
                EngineCapabilities(
                    isKnown = true,
                    encoders = nativeComponents(0),
                    decoders = nativeComponents(1),
                    filters = nativeComponents(2),
                    muxers = nativeComponents(3),
                    demuxers = nativeComponents(4),
                    encodersKnown = true,
                    decodersKnown = true,
                    filtersKnown = true,
                    muxersKnown = true,
                    demuxersKnown = true,
                ).also { cachedCapabilities = it }
            }
        }

    override suspend fun execute(
        request: EngineRequest,
        onEvent: (EngineEvent) -> Unit,
    ): EngineResult {
        descriptor()
        return executionSemaphore.withPermit {
            val prepared = try {
                resolver.prepare(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                throw EngineException(
                    category = when (failure) {
                        is IllegalArgumentException, is SecurityException -> EngineFailureCategory.POLICY
                        else -> EngineFailureCategory.RESOURCE
                    },
                    message = "Unable to prepare FFmpeg resources: " +
                        (failure.message ?: failure::class.java.simpleName),
                    cause = failure,
                )
            }
            try {
                if (prepared.arguments.any { it == "-stdin" }) {
                    throw EngineException(
                        EngineFailureCategory.POLICY,
                        "Interactive FFmpeg stdin is not permitted inside an Android application",
                    )
                }
                prepared.finish(
                    executeNative(arrayOf("-nostdin", *prepared.arguments), onEvent),
                )
            } catch (failure: Throwable) {
                prepared.abort()
                throw failure
            }
        }
    }

    override suspend fun probe(reference: MediaReference): MediaProbe {
        descriptor()
        return executionSemaphore.withPermit {
            val prepared = resolver.prepareProbe(reference)
            try {
                parseProbe(executeNativeProbe(prepared.input))
            } finally {
                prepared.cleanup()
            }
        }
    }

    private suspend fun executeNative(
        arguments: Array<String>,
        onEvent: (EngineEvent) -> Unit,
    ): EngineResult = suspendCancellableCoroutine { continuation ->
        val sessionId = NEXT_SESSION.incrementAndGet()
        val output = BoundedLog(policy.maxCapturedOutputChars)
        val startedAt = System.nanoTime()
        val callback = NativeLogCallback { _, message ->
            output.append(message)
            runCatching { onEvent(EngineEvent.Log(message.take(policy.maxCapturedOutputChars))) }
            parseStatistics(message)?.let { statistics ->
                runCatching { onEvent(statistics) }
            }
        }

        continuation.invokeOnCancellation { NativeBindings.nativeCancel(sessionId) }
        EXECUTOR.execute {
            if (!continuation.isActive) return@execute
            runCatching {
                val exitCode = NativeBindings.nativeExecute(sessionId, arguments, callback)
                val captured = output.value()
                EngineResult(
                    sessionId = sessionId.toString(),
                    exitCode = exitCode,
                    cancelled = false,
                    durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                    output = captured,
                    failureDetails = when {
                        exitCode == 0 -> null
                        exitCode == -127 -> "libffmpeg_sdk_cli.so could not be loaded"
                        exitCode == -126 -> "FFmpeg runner symbols are missing"
                        exitCode == -125 -> "Native log callback initialization failed"
                        exitCode == -124 -> "A command argument could not be encoded as UTF-8"
                        else -> captured.takeIf(String::isNotBlank)
                    },
                    failureCategory = if (exitCode == 0) null else when (exitCode) {
                        -127, -126, -125, -124 -> EngineFailureCategory.RUNTIME
                        else -> EngineFailureCategory.COMMAND
                    },
                )
            }.fold(
                onSuccess = { result -> if (continuation.isActive) continuation.resume(result) },
                onFailure = { failure ->
                    if (continuation.isActive) continuation.resumeWithException(failure)
                },
            )
        }
    }

    private suspend fun executeNativeProbe(input: String): String =
        suspendCancellableCoroutine { continuation ->
            val sessionId = NEXT_SESSION.incrementAndGet()
            continuation.invokeOnCancellation { NativeBindings.nativeCancelProbe(sessionId) }
            EXECUTOR.execute {
                if (!continuation.isActive) return@execute
                runCatching { NativeBindings.nativeProbe(sessionId, input) }.fold(
                    onSuccess = { value -> if (continuation.isActive) continuation.resume(value) },
                    onFailure = { failure ->
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    },
                )
            }
        }

    private fun nativeComponents(kind: Int): Set<String> = NativeBindings.nativeComponents(kind)
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

    private fun parseProbe(raw: String): MediaProbe {
        val root = Json.parseToJsonElement(raw).jsonObject
        root["error"]?.jsonPrimitive?.content?.let { error ->
            throw IllegalStateException("FFmpeg probe failed: $error")
        }
        val allStreams = root["streams"]?.jsonArray.orEmpty()
        val selectedStreams = allStreams.take(policy.maxProbeStreams).map { element ->
            val stream = element.jsonObject
            MediaStream(
                index = stream.requiredInt("index"),
                type = stream.requiredString("type"),
                codec = stream.optionalString("codec"),
                width = stream.optionalInt("width"),
                height = stream.optionalInt("height"),
                sampleRate = stream.optionalInt("sampleRate"),
                channels = stream.optionalInt("channels"),
            )
        }
        val rawTruncated = raw.length > policy.maxCapturedOutputChars
        return MediaProbe(
            durationMs = root.optionalLong("durationMs"),
            formatNames = root["formatNames"]?.jsonArray.orEmpty()
                .map { it.jsonPrimitive.content }
                .toSet(),
            bitrate = root.optionalLong("bitrate"),
            streams = selectedStreams,
            rawJson = if (rawTruncated) raw.take(policy.maxCapturedOutputChars) else raw,
            streamsTruncated = allStreams.size > selectedStreams.size,
            rawJsonTruncated = rawTruncated,
        )
    }

    private companion object {
        const val BRIDGE_VERSION: String = "0.3.0-dev"
        val VERSION_MAJOR: Regex = Regex("^(\\d+)")
        val NEXT_SESSION = AtomicLong(0)
        val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ffmpeg-official-native").apply { isDaemon = true }
        }
        val STATS = Regex(
            "frame=\\s*(\\d+).*?fps=\\s*([0-9.]+).*?size=\\s*([0-9]+)KiB.*?" +
                "time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?).*?bitrate=\\s*([0-9.]+)kbits/s.*?" +
                "speed=\\s*([0-9.]+)x",
        )

        fun parseStatistics(message: String): EngineEvent.Statistics? {
            val match = STATS.find(message) ?: return null
            val hours = match.groupValues[4].toLongOrNull() ?: return null
            val minutes = match.groupValues[5].toLongOrNull() ?: return null
            val seconds = match.groupValues[6].toDoubleOrNull() ?: return null
            return EngineEvent.Statistics(
                processedTimeMs = ((hours * 3_600 + minutes * 60 + seconds) * 1_000).toLong(),
                frame = match.groupValues[1].toIntOrNull() ?: 0,
                framesPerSecond = match.groupValues[2].toFloatOrNull() ?: 0f,
                outputBytes = (match.groupValues[3].toLongOrNull() ?: 0L) * 1_024,
                bitrateKbps = match.groupValues[7].toDoubleOrNull() ?: 0.0,
                speed = match.groupValues[8].toDoubleOrNull() ?: 0.0,
            )
        }
    }
}

private class BoundedLog(private val limit: Int) {
    private val content = StringBuilder()

    @Synchronized
    fun append(value: String) {
        if (content.length >= limit) return
        content.append(value, 0, minOf(value.length, limit - content.length))
    }

    @Synchronized
    fun value(): String = content.toString()
}

private fun JsonObject.requiredInt(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: error("Probe field '$name' is missing or invalid")

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: error("Probe field '$name' is missing")

private fun JsonObject.optionalInt(name: String): Int? =
    this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull

private fun JsonObject.optionalLong(name: String): Long? =
    this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull

private fun JsonObject.optionalString(name: String): String? =
    this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
