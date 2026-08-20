package io.github.tianrking.ffmpegsdk.engine.nativeffmpeg

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.tianrking.ffmpegsdk.core.ArgumentPart
import io.github.tianrking.ffmpegsdk.core.CommandArgument
import io.github.tianrking.ffmpegsdk.core.EngineFailureCategory
import io.github.tianrking.ffmpegsdk.core.EngineRequest
import io.github.tianrking.ffmpegsdk.core.EngineResult
import io.github.tianrking.ffmpegsdk.core.MediaReference
import io.github.tianrking.ffmpegsdk.core.ResourceAccess
import io.github.tianrking.ffmpegsdk.core.ResourceEscaping
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class NativeResourceResolver(
    context: Context,
    private val policy: OfficialFfmpegRuntimePolicy,
) {
    private val applicationContext: Context = context.applicationContext

    suspend fun prepare(request: EngineRequest): NativePreparedCommand = withContext(Dispatchers.IO) {
        val workspace = NativeTempWorkspace(
            applicationContext.cacheDir,
            request.jobId,
            request.attempt,
            policy.maxStagedInputBytes,
        )
        var output: NativePreparedOutput? = null
        try {
            val arguments = request.arguments.map { argument ->
                resolveArgument(argument, workspace, request) { prepared ->
                    check(output == null) { "A media job may contain only one output resource" }
                    output = prepared
                }
            }.toTypedArray()
            NativePreparedCommand(arguments, workspace, output, policy.maxCapturedOutputChars)
        } catch (failure: Throwable) {
            workspace.cleanup()
            throw failure
        }
    }

    suspend fun prepareProbe(reference: MediaReference): NativePreparedProbe = withContext(Dispatchers.IO) {
        val workspace = NativeTempWorkspace(
            applicationContext.cacheDir,
            "probe",
            0,
            policy.maxStagedInputBytes,
        )
        try {
            NativePreparedProbe(resolveRead(reference, ResourceAccess.READ, workspace), workspace)
        } catch (failure: Throwable) {
            workspace.cleanup()
            throw failure
        }
    }

    private suspend fun resolveArgument(
        argument: CommandArgument,
        workspace: NativeTempWorkspace,
        request: EngineRequest,
        setOutput: (NativePreparedOutput) -> Unit,
    ): String = when (argument) {
        is CommandArgument.Literal -> argument.value
        is CommandArgument.Resource -> when (argument.access) {
            ResourceAccess.READ, ResourceAccess.READ_SEEKABLE ->
                resolveRead(argument.reference, argument.access, workspace)
            ResourceAccess.WRITE -> resolveWrite(argument.reference, workspace, request, setOutput)
        }
        is CommandArgument.Composite -> buildString {
            argument.parts.forEach { part ->
                append(
                    when (part) {
                        is ArgumentPart.Literal -> part.value
                        is ArgumentPart.Resource -> {
                            val resolved = resolveRead(part.reference, part.access, workspace)
                            when (part.escaping) {
                                ResourceEscaping.NONE -> resolved
                                ResourceEscaping.FFMPEG_FILTER_VALUE -> escapeFilterValue(resolved)
                            }
                        }
                    },
                )
            }
        }
    }

    private suspend fun resolveRead(
        reference: MediaReference,
        access: ResourceAccess,
        workspace: NativeTempWorkspace,
    ): String = when (reference) {
        is MediaReference.FilePath -> reference.path
        is MediaReference.ContentUri -> {
            val uri = Uri.parse(reference.uri)
            if (access == ResourceAccess.READ_SEEKABLE || !isContentUriSeekable(uri)) {
                stageContentUri(uri, workspace)
            } else {
                uri.toString()
            }
        }
        is MediaReference.NetworkUrl -> {
            require(policy.allowNetworkInputs) { "This FFmpeg runtime policy denies network inputs" }
            stageNetworkUrl(reference.url, workspace)
        }
    }

    private fun resolveWrite(
        reference: MediaReference,
        workspace: NativeTempWorkspace,
        request: EngineRequest,
        setOutput: (NativePreparedOutput) -> Unit,
    ): String {
        if (!policy.transactionalOutputs) {
            return when (reference) {
                is MediaReference.FilePath -> reference.path
                is MediaReference.ContentUri -> reference.uri
                is MediaReference.NetworkUrl -> error("Network outputs are not supported")
            }
        }

        val prepared = when (reference) {
            is MediaReference.FilePath -> {
                val target = File(reference.path).absoluteFile
                val parent = target.parentFile ?: error("Output path has no parent directory")
                require(parent.isDirectory || parent.mkdirs()) {
                    "Unable to create output directory: ${parent.path}"
                }
                if (!request.overwrite && target.exists()) {
                    throw IOException("Output already exists and overwrite is disabled: ${target.path}")
                }
                NativePreparedOutput.FileTarget(
                    staged = workspace.reserveFile(parent, ".ffmpeg-sdk-", outputSuffix(target.name)),
                    target = target,
                    overwrite = request.overwrite,
                )
            }
            is MediaReference.ContentUri -> {
                require(request.overwrite) {
                    "Transactional content URI outputs require overwrite=true because Android " +
                        "providers do not offer a universal atomic no-replace operation"
                }
                NativePreparedOutput.ContentTarget(
                    staged = workspace.reserveFile("output", contentSuffix(Uri.parse(reference.uri))),
                    uri = Uri.parse(reference.uri),
                    context = applicationContext,
                )
            }
            is MediaReference.NetworkUrl -> error("Network outputs are not supported")
        }
        setOutput(prepared)
        return prepared.staged.path
    }

    private suspend fun stageContentUri(uri: Uri, workspace: NativeTempWorkspace): String {
        val target = workspace.createFile("input", contentSuffix(uri))
        val input = applicationContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Content provider returned no input stream for $uri")
        input.use { source ->
            target.outputStream().buffered().use { destination ->
                copyBounded(source, destination, workspace)
            }
        }
        return target.path
    }

    private fun isContentUriSeekable(uri: Uri): Boolean {
        return try {
            val descriptor = applicationContext.contentResolver.openFileDescriptor(uri, "r")
                ?: return false
            descriptor.use {
                try {
                    Os.lseek(it.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                    true
                } catch (_: ErrnoException) {
                    false
                }
            }
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun stageNetworkUrl(value: String, workspace: NativeTempWorkspace): String {
        var current = URI(value)
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            validateNetworkUri(current)
            val connection = URL(current.toASCIIString()).openConnection() as? HttpURLConnection
                ?: throw IOException("Only HTTP(S) URL connections are supported")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = policy.networkPolicy.connectTimeoutMs
                connection.readTimeout = policy.networkPolicy.readTimeoutMs
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", policy.networkPolicy.userAgent)
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirects >= policy.networkPolicy.maxRedirects) {
                        throw IOException("Network input exceeded ${policy.networkPolicy.maxRedirects} redirects")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect response omitted Location")
                    current = current.resolve(location)
                    redirects += 1
                    continue
                }
                if (status !in 200..299) throw IOException("Network input returned HTTP $status")
                val contentLength = connection.contentLengthLong
                if (contentLength > workspace.remainingStagedInputBytes) {
                    throw IOException(
                        "Network input declares $contentLength bytes; remaining staging budget is " +
                            workspace.remainingStagedInputBytes,
                    )
                }
                val target = workspace.createFile(
                    "network-input",
                    outputSuffix(current.path?.substringAfterLast('/')),
                )
                connection.inputStream.buffered().use { source ->
                    target.outputStream().buffered().use { destination ->
                        copyBounded(source, destination, workspace)
                    }
                }
                return target.path
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun validateNetworkUri(uri: URI) {
        val network = policy.networkPolicy
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme in network.allowedSchemes) { "Network scheme '$scheme' is not allowed" }
        require(uri.userInfo == null && uri.fragment == null) {
            "Network URLs must not contain credentials or fragments"
        }
        val host = uri.host?.let { IDN.toASCII(it).lowercase(Locale.ROOT) }
            ?: throw IllegalArgumentException("Network URL has no valid host")
        if (network.allowedHosts.isNotEmpty()) {
            require(host in network.allowedHosts) { "Network host '$host' is not allow-listed" }
        }
        if (network.blockNonPublicAddresses) {
            val addresses = InetAddress.getAllByName(host)
            require(addresses.isNotEmpty() && addresses.none(::isNonPublicAddress)) {
                "Network host '$host' resolves to a non-public address"
            }
        }
    }

    private fun contentSuffix(uri: Uri): String {
        val displayName = runCatching {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return outputSuffix(displayName ?: uri.lastPathSegment)
    }

    private suspend fun copyBounded(
        source: java.io.InputStream,
        destination: java.io.OutputStream,
        workspace: NativeTempWorkspace,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = source.read(buffer)
            if (count < 0) break
            workspace.claimStagedInputBytes(count.toLong())
            destination.write(buffer, 0, count)
        }
    }

    private fun isNonPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> {
                val bytes = address.address.map(Byte::toInt).map { it and 0xff }
                bytes[0] == 0 ||
                    bytes[0] == 100 && bytes[1] in 64..127 ||
                    bytes[0] == 192 && bytes[1] == 0 ||
                    bytes[0] == 198 && bytes[1] in 18..19 ||
                    bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100 ||
                    bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113 ||
                    bytes[0] >= 224
            }
            is Inet6Address -> (address.address[0].toInt() and 0xfe) == 0xfc
            else -> true
        }
    }

    private companion object {
        val REDIRECT_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)
    }
}

internal class NativePreparedCommand(
    val arguments: Array<String>,
    private val workspace: NativeTempWorkspace,
    private val output: NativePreparedOutput?,
    private val maxCapturedOutputChars: Int,
) {
    suspend fun finish(result: EngineResult): EngineResult = withContext(Dispatchers.IO) {
        try {
            if (result.succeeded && output != null) {
                runCatching { output.commit() }.fold(
                    onSuccess = { result },
                    onFailure = { failure ->
                        result.copy(
                            exitCode = null,
                            failureDetails = failure.describe(maxCapturedOutputChars),
                            failureCategory = EngineFailureCategory.OUTPUT_COMMIT,
                        )
                    },
                )
            } else {
                result
            }
        } finally {
            workspace.cleanup()
        }
    }

    suspend fun abort() {
        withContext(Dispatchers.IO) { workspace.cleanup() }
    }
}

internal class NativePreparedProbe(
    val input: String,
    private val workspace: NativeTempWorkspace,
) {
    suspend fun cleanup() {
        withContext(Dispatchers.IO) { workspace.cleanup() }
    }
}

internal sealed class NativePreparedOutput(public val staged: File) {
    abstract fun commit()

    class FileTarget(
        staged: File,
        private val target: File,
        private val overwrite: Boolean,
    ) : NativePreparedOutput(staged) {
        override fun commit() {
            if (overwrite) {
                Os.rename(staged.path, target.path)
            } else {
                Os.link(staged.path, target.path)
                check(staged.delete()) { "Unable to remove committed staging file" }
            }
        }
    }

    class ContentTarget(
        staged: File,
        private val uri: Uri,
        private val context: Context,
    ) : NativePreparedOutput(staged) {
        override fun commit() {
            val destination = runCatching { context.contentResolver.openOutputStream(uri, "rwt") }.getOrNull()
                ?: context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Content provider returned no output stream for $uri")
            staged.inputStream().buffered().use { source ->
                destination.buffered().use { output ->
                    source.copyTo(output)
                    output.flush()
                }
            }
        }
    }
}

internal class NativeTempWorkspace(
    cacheDirectory: File,
    jobId: String,
    attempt: Int,
    private val maximumStagedInputBytes: Long,
) {
    private val files = linkedSetOf<File>()
    private var stagedInputBytes: Long = 0
    private val root = File(
        File(cacheDirectory, "ffmpeg-sdk-native"),
        "${jobId.safeToken()}-$attempt-${UUID.randomUUID()}",
    )

    val remainingStagedInputBytes: Long get() = maximumStagedInputBytes - stagedInputBytes

    fun createFile(prefix: String, suffix: String): File {
        require(root.isDirectory || root.mkdirs()) { "Unable to create SDK staging directory" }
        return File.createTempFile(prefix, suffix, root).also(::track)
    }

    fun reserveFile(prefix: String, suffix: String): File {
        require(root.isDirectory || root.mkdirs()) { "Unable to create SDK staging directory" }
        return reserveFile(root, prefix, suffix)
    }

    fun reserveFile(directory: File, prefix: String, suffix: String): File {
        val reserved = File.createTempFile(prefix, suffix, directory).also(::track)
        check(reserved.delete()) { "Unable to reserve output staging path" }
        return reserved
    }

    fun claimStagedInputBytes(bytes: Long) {
        require(bytes >= 0) { "Staged byte increment must be non-negative" }
        if (bytes > remainingStagedInputBytes) {
            throw IOException("Staged inputs exceed the $maximumStagedInputBytes byte session limit")
        }
        stagedInputBytes += bytes
    }

    fun track(file: File) {
        files += file
    }

    fun cleanup() {
        files.toList().asReversed().forEach { file -> runCatching { file.delete() } }
        runCatching { root.delete() }
        runCatching { root.parentFile?.delete() }
    }
}

internal fun escapeFilterValue(value: String): String = buildString(value.length + 8) {
    value.forEach { character ->
        when (character) {
            '\\', '\'', ':', ',', '[', ']', ';' -> append('\\').append(character)
            else -> append(character)
        }
    }
}

private fun outputSuffix(name: String?): String {
    val extension = name?.substringAfterLast('.', missingDelimiterValue = "")
    return if (extension != null && extension.matches(Regex("[A-Za-z0-9]{1,16}"))) {
        ".$extension"
    } else {
        ".media"
    }
}

private fun String.safeToken(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
}

private fun Throwable.describe(maximum: Int): String {
    val type = this::class.qualifiedName ?: this::class.simpleName ?: "Throwable"
    val value = message?.let { "$type: $it" } ?: type
    return if (value.length <= maximum) value else value.take(maximum)
}
