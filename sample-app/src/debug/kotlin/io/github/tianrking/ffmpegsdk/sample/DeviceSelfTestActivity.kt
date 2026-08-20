package io.github.tianrking.ffmpegsdk.sample

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.tianrking.ffmpegsdk.core.AudioCodec
import io.github.tianrking.ffmpegsdk.core.AudioSettings
import io.github.tianrking.ffmpegsdk.core.Bitrate
import io.github.tianrking.ffmpegsdk.core.CommandArgument
import io.github.tianrking.ffmpegsdk.core.Container
import io.github.tianrking.ffmpegsdk.core.EncoderPreference
import io.github.tianrking.ffmpegsdk.core.EngineRequest
import io.github.tianrking.ffmpegsdk.core.FfmpegSdk
import io.github.tianrking.ffmpegsdk.core.MediaReference
import io.github.tianrking.ffmpegsdk.core.MediaResult
import io.github.tianrking.ffmpegsdk.core.ResourceAccess
import io.github.tianrking.ffmpegsdk.core.RuntimeLicense
import io.github.tianrking.ffmpegsdk.core.TranscodeJob
import io.github.tianrking.ffmpegsdk.core.VideoCodec
import io.github.tianrking.ffmpegsdk.core.VideoSettings
import io.github.tianrking.ffmpegsdk.engine.nativeffmpeg.OfficialFfmpegEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Debug-only, intent-driven end-to-end check for an attached Android device. */
public class DeviceSelfTestActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "Running official FFmpeg device self-test…"
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        setContentView(status)

        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { runSelfTest() }.fold(
                    onSuccess = { it },
                    onFailure = { failure ->
                        JSONObject()
                            .put("success", false)
                            .put("errorType", failure::class.java.name)
                            .put("error", failure.message ?: failure.toString())
                            .put("stackTrace", failure.stackTraceToString().take(MAX_REPORT_CHARS))
                    },
                )
            }
            File(filesDir, REPORT_FILE).writeText(report.toString(2), Charsets.UTF_8)
            Log.i(LOG_TAG, "RESULT=$report")
            status.text = if (report.getBoolean("success")) {
                "PASS: official FFmpeg generated, transcoded, and probed real media."
            } else {
                "FAIL: ${report.optString("error")}"
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runSelfTest(): JSONObject {
        val root = File(externalCacheDir ?: cacheDir, "ffmpeg-sdk-device-self-test")
        check(!root.exists() || root.deleteRecursively()) { "Unable to reset device self-test directory" }
        check(root.mkdirs()) { "Unable to create device self-test directory" }

        val generated = File(root, "generated-input.mkv")
        val transcoded = File(root, "transcoded-output.mp4")
        val engine = OfficialFfmpegEngine(applicationContext)
        val descriptor = engine.descriptor()
        check(descriptor.runtimeLicense == RuntimeLicense.LGPL) {
            "Unexpected runtime license: ${descriptor.runtimeLicense}"
        }

        val generation = engine.execute(
            request = EngineRequest(
                jobId = "device-self-test-generate",
                attempt = 1,
                overwrite = true,
                arguments = literals(
                    "-hide_banner",
                    "-loglevel", "info",
                    "-f", "lavfi",
                    "-i", "testsrc2=size=320x240:rate=15:duration=2",
                    "-f", "lavfi",
                    "-i", "sine=frequency=440:sample_rate=48000:duration=2",
                    "-shortest",
                    "-threads", "1",
                    "-c:v", "mpeg4",
                    "-q:v", "5",
                    "-c:a", "aac",
                    "-b:a", "64000",
                    "-f", "matroska",
                ) + CommandArgument.Resource(
                    MediaReference.FilePath(generated.absolutePath),
                    ResourceAccess.WRITE,
                ),
            ),
        )
        check(generation.succeeded && generated.length() > 0L) {
            "Synthetic input generation failed: exit=${generation.exitCode}, " +
                "details=${generation.failureDetails ?: generation.output.takeLast(2_048)}"
        }

        val generatedProbe = engine.probe(MediaReference.FilePath(generated.absolutePath))
        check(generatedProbe.streams.any { it.type == "video" && it.codec == "mpeg4" }) {
            "Generated media has no MPEG-4 video stream: ${generatedProbe.rawJson}"
        }
        check(generatedProbe.streams.any { it.type == "audio" && it.codec == "aac" }) {
            "Generated media has no AAC audio stream: ${generatedProbe.rawJson}"
        }

        val transcode = FfmpegSdk(engine).execute(
            TranscodeJob(
                id = "device-self-test-transcode",
                input = MediaReference.FilePath(generated.absolutePath),
                output = MediaReference.FilePath(transcoded.absolutePath),
                video = VideoSettings(
                    codec = VideoCodec.H264,
                    encoderPreference = EncoderPreference.HARDWARE_ONLY,
                    bitrate = Bitrate(600_000),
                    maxFrameRate = 15,
                    keyFrameIntervalFrames = 15,
                ),
                audio = AudioSettings(
                    codec = AudioCodec.AAC,
                    bitrate = Bitrate(64_000),
                    sampleRate = 48_000,
                    channels = 1,
                ),
                container = Container.MP4,
                overwrite = true,
            ),
        )
        val transcodeSuccess = transcode as? MediaResult.Success
            ?: error("Typed H.264 transcode failed: $transcode")
        check(transcoded.length() > 0L) { "Typed H.264 transcode created no output" }

        val outputProbe = engine.probe(MediaReference.FilePath(transcoded.absolutePath))
        check(outputProbe.streams.any { it.type == "video" && it.codec == "h264" }) {
            "Output has no H.264 video stream: ${outputProbe.rawJson}"
        }
        check(outputProbe.streams.any { it.type == "audio" && it.codec == "aac" }) {
            "Output has no AAC audio stream: ${outputProbe.rawJson}"
        }
        check((outputProbe.durationMs ?: 0L) in 1_500L..3_000L) {
            "Output duration is outside the expected range: ${outputProbe.durationMs}"
        }

        return JSONObject()
            .put("success", true)
            .put("ffmpegVersion", descriptor.ffmpegVersion)
            .put("runtimeLicense", descriptor.runtimeLicense.name)
            .put("transcodeAttempt", transcodeSuccess.attempt)
            .put("videoEncoder", "h264_mediacodec")
            .put("generatedBytes", generated.length())
            .put("outputBytes", transcoded.length())
            .put("outputDurationMs", outputProbe.durationMs)
            .put("outputFormats", outputProbe.formatNames.sorted().joinToString(","))
            .put("outputPath", transcoded.absolutePath)
            .put("outputProbe", JSONObject(outputProbe.rawJson))
    }

    private fun literals(vararg values: String): List<CommandArgument> =
        values.map(CommandArgument::Literal)

    private companion object {
        const val LOG_TAG: String = "FfmpegSdkSelfTest"
        const val REPORT_FILE: String = "ffmpeg-sdk-device-self-test.json"
        const val MAX_REPORT_CHARS: Int = 16 * 1_024
    }
}
