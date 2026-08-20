package io.github.tianrking.ffmpegsdk.engine.ffmpegkit

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.Packages
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Session
import io.github.tianrking.ffmpegsdk.core.CommandArgument
import io.github.tianrking.ffmpegsdk.core.EngineCapabilities
import io.github.tianrking.ffmpegsdk.core.EngineDescriptor
import io.github.tianrking.ffmpegsdk.core.EngineEvent
import io.github.tianrking.ffmpegsdk.core.EngineRequest
import io.github.tianrking.ffmpegsdk.core.EngineResult
import io.github.tianrking.ffmpegsdk.core.FfmpegEngine
import io.github.tianrking.ffmpegsdk.core.MediaProbe
import io.github.tianrking.ffmpegsdk.core.MediaReference
import io.github.tianrking.ffmpegsdk.core.MediaStream
import io.github.tianrking.ffmpegsdk.core.ResourceAccess
import io.github.tianrking.ffmpegsdk.core.RuntimeLicense
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

public data class FfmpegKitRuntimePolicy(
    val runtimeLicense: RuntimeLicense,
    val allowedFfmpegMajorVersions: Set<Int> = setOf(8),
    val distributionLabel: String = "application-supplied FFmpegKit runtime",
    val allowNetworkInputs: Boolean = false,
    val requireBuildConfiguration: Boolean = true,
    val maxCapturedOutputChars: Int = 64 * 1_024,
) {
    init {
        require(allowedFfmpegMajorVersions.isNotEmpty()) {
            "At least one FFmpeg major version must be explicitly allowed"
        }
        require(maxCapturedOutputChars >= 1_024) {
            "Captured output limit must be at least 1024 characters"
        }
    }
}

/**
 * Adapter for the com.arthenica.ffmpegkit API shared by FFmpegKitNext source builds and compatible
 * community packages. The native runtime is intentionally not a transitive dependency.
 */
public class FfmpegKitEngine(
    context: Context,
    private val policy: FfmpegKitRuntimePolicy,
) : FfmpegEngine {
    private val applicationContext: Context = context.applicationContext
    private val capabilityMutex = Mutex()
    private val descriptorMutex = Mutex()

    @Volatile
    private var cachedCapabilities: EngineCapabilities? = null

    @Volatile
    private var cachedDescriptor: EngineDescriptor? = null

    override suspend fun descriptor(): EngineDescriptor = descriptorMutex.withLock {
        cachedDescriptor?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val ffmpegVersion = FFmpegKitConfig.getFFmpegVersion()
            val externalLibraries = Packages.getExternalLibraries().toSet()
            val buildConfiguration = queryBuildConfiguration()
            verifyVersion(ffmpegVersion)
            verifyLicenseConfiguration(buildConfiguration)
            EngineDescriptor(
                name = "FFmpegKit-compatible",
                wrapperVersion = FFmpegKitConfig.getVersion(),
                ffmpegVersion = ffmpegVersion,
                distribution = "$policyLabel (${Packages.getPackageName()})",
                runtimeLicense = policy.runtimeLicense,
                externalLibraries = externalLibraries,
                buildConfiguration = buildConfiguration,
            ).also { cachedDescriptor = it }
        }
    }

    override suspend fun capabilities(refresh: Boolean): EngineCapabilities =
        capabilityMutex.withLock {
            if (!refresh) cachedCapabilities?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val encoders = queryComponents("-encoders")
                val decoders = queryComponents("-decoders")
                EngineCapabilities(
                    isKnown = encoders != null && decoders != null,
                    encoders = encoders.orEmpty(),
                    decoders = decoders.orEmpty(),
                ).also { cachedCapabilities = it }
            }
        }

    override suspend fun execute(
        request: EngineRequest,
        onEvent: (EngineEvent) -> Unit,
    ): EngineResult {
        descriptor()
        return suspendCancellableCoroutine { continuation ->
            val sessionReference = AtomicReference<Session?>()
            val arguments = request.arguments.map(::resolve).toTypedArray()

            val session = FFmpegKit.executeWithArgumentsAsync(
                arguments,
                { completed ->
                    val returnCode = completed.returnCode
                    val result = EngineResult(
                        sessionId = completed.sessionId.toString(),
                        exitCode = returnCode?.value,
                        cancelled = ReturnCode.isCancel(returnCode),
                        durationMs = completed.duration,
                        output = truncate(completed.output.orEmpty()),
                        failureDetails = completed.failStackTrace?.let(::truncate),
                    )
                    if (continuation.isActive) continuation.resume(result)
                },
                { log ->
                    runCatching {
                        onEvent(EngineEvent.Log(truncate(log.message.orEmpty())))
                    }
                },
                { statistics ->
                    runCatching {
                        onEvent(
                            EngineEvent.Statistics(
                                processedTimeMs = statistics.time.toLong().coerceAtLeast(0),
                                frame = statistics.videoFrameNumber,
                                framesPerSecond = statistics.videoFps,
                                outputBytes = statistics.size,
                                bitrateKbps = statistics.bitrate,
                                speed = statistics.speed,
                            ),
                        )
                    }
                },
            )
            sessionReference.set(session)
            if (!continuation.isActive) session.cancel()
            continuation.invokeOnCancellation { sessionReference.get()?.cancel() }
        }
    }

    override suspend fun probe(reference: MediaReference): MediaProbe {
        descriptor()
        return suspendCancellableCoroutine { continuation ->
            val sessionReference = AtomicReference<Session?>()
            val arguments = arrayOf(
                "-v", "error",
                "-show_format",
                "-show_streams",
                "-show_entries",
                "format=duration,format_name,bit_rate:" +
                    "stream=index,codec_type,codec_name,width,height,sample_rate,channels",
                "-of", "json",
                resolve(CommandArgument.Resource(reference, ResourceAccess.READ)),
            )
            val session = FFprobeKit.executeWithArgumentsAsync(
                arguments,
                { completed: FFprobeSession ->
                    val returnCode = completed.returnCode
                    if (!ReturnCode.isSuccess(returnCode)) {
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.failure(
                                    IllegalStateException(
                                        "FFprobe failed (${returnCode?.value}): " +
                                            truncate(completed.output.orEmpty()),
                                    ),
                                ),
                            )
                        }
                    } else if (continuation.isActive) {
                        continuation.resume(parseProbe(completed.output.orEmpty()))
                    }
                },
                LogCallback { _ -> },
            )
            sessionReference.set(session)
            if (!continuation.isActive) session.cancel()
            continuation.invokeOnCancellation { sessionReference.get()?.cancel() }
        }
    }

    private fun queryComponents(option: String): Set<String>? {
        val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", option))
        if (!ReturnCode.isSuccess(session.returnCode)) return null
        return COMPONENT_LINE.findAll(session.output.orEmpty())
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun queryBuildConfiguration(): Set<String> {
        val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-buildconf"))
        if (!ReturnCode.isSuccess(session.returnCode)) {
            if (policy.requireBuildConfiguration) {
                error("Unable to attest FFmpeg build configuration: ${truncate(session.output.orEmpty())}")
            }
            return emptySet()
        }
        val options = CONFIGURATION_OPTION.findAll(session.output.orEmpty())
            .map { it.value }
            .toSet()
        if (options.isEmpty() && policy.requireBuildConfiguration) {
            error("FFmpeg runtime returned no auditable build configuration")
        }
        return options
    }

    private fun resolve(argument: CommandArgument): String = when (argument) {
        is CommandArgument.Literal -> argument.value
        is CommandArgument.Resource -> when (val reference = argument.reference) {
            is MediaReference.FilePath -> reference.path
            is MediaReference.NetworkUrl -> {
                require(policy.allowNetworkInputs) {
                    "This FFmpeg runtime policy denies network inputs"
                }
                reference.url
            }
            is MediaReference.ContentUri -> {
                val uri = Uri.parse(reference.uri)
                when (argument.access) {
                    ResourceAccess.READ -> FFmpegKitConfig.getSafParameterForRead(applicationContext, uri)
                    ResourceAccess.WRITE -> FFmpegKitConfig.getSafParameterForWrite(applicationContext, uri)
                }
            }
        }
    }

    private fun parseProbe(rawOutput: String): MediaProbe {
        val firstBrace = rawOutput.indexOf('{')
        val lastBrace = rawOutput.lastIndexOf('}')
        require(firstBrace >= 0 && lastBrace >= firstBrace) { "FFprobe did not return JSON" }
        val rawJson = rawOutput.substring(firstBrace, lastBrace + 1)
        val root = JSONObject(rawJson)
        val format = root.optJSONObject("format") ?: JSONObject()
        val streamArray = root.optJSONArray("streams")
        val streams = buildList {
            if (streamArray != null) {
                for (index in 0 until streamArray.length()) {
                    val stream = streamArray.getJSONObject(index)
                    add(
                        MediaStream(
                            index = stream.optInt("index", index),
                            type = stream.optString("codec_type", "unknown"),
                            codec = stream.optString("codec_name").ifBlank { null },
                            width = stream.optionalPositiveInt("width"),
                            height = stream.optionalPositiveInt("height"),
                            sampleRate = stream.optString("sample_rate").toIntOrNull(),
                            channels = stream.optionalPositiveInt("channels"),
                        ),
                    )
                }
            }
        }
        return MediaProbe(
            durationMs = format.optString("duration").toDoubleOrNull()?.times(1_000)?.toLong(),
            formatNames = format.optString("format_name")
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet(),
            bitrate = format.optString("bit_rate").toLongOrNull(),
            streams = streams,
            rawJson = rawJson,
        )
    }

    private fun JSONObject.optionalPositiveInt(name: String): Int? =
        optInt(name, 0).takeIf { it > 0 }

    private fun verifyVersion(version: String) {
        val major = version.substringBefore('.').toIntOrNull()
            ?: error("Unrecognized FFmpeg version: $version")
        require(major in policy.allowedFfmpegMajorVersions) {
            "FFmpeg $version is outside the audited runtime line " +
                policy.allowedFfmpegMajorVersions.sorted().joinToString()
        }
    }

    private fun verifyLicenseConfiguration(buildConfiguration: Set<String>) {
        require("--enable-nonfree" !in buildConfiguration) {
            "Nonfree FFmpeg builds are rejected because they are not redistributable"
        }
        if (policy.runtimeLicense == RuntimeLicense.LGPL) {
            require("--enable-gpl" !in buildConfiguration) {
                "Runtime was declared LGPL but its FFmpeg build enables GPL components"
            }
        }
    }

    private fun truncate(value: String): String {
        if (value.length <= policy.maxCapturedOutputChars) return value
        val omitted = value.length - policy.maxCapturedOutputChars
        return "[truncated $omitted earlier characters]\n" +
            value.takeLast(policy.maxCapturedOutputChars)
    }

    private val policyLabel: String get() = policy.distributionLabel

    private companion object {
        val COMPONENT_LINE: Regex = Regex("(?m)^\\s*[A-Z.]{6}\\s+(\\S+)")
        val CONFIGURATION_OPTION: Regex = Regex("--[A-Za-z0-9][^\\s]*")
    }
}
