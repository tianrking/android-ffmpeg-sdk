package io.github.tianrking.ffmpegsdk.sample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.tianrking.ffmpegsdk.android.EncoderRequest
import io.github.tianrking.ffmpegsdk.android.MediaCodecSurvey
import io.github.tianrking.ffmpegsdk.core.ExecutionEvent
import io.github.tianrking.ffmpegsdk.core.FfmpegSdk
import io.github.tianrking.ffmpegsdk.core.MediaReference
import io.github.tianrking.ffmpegsdk.core.MediaResult
import io.github.tianrking.ffmpegsdk.core.MediaTask
import io.github.tianrking.ffmpegsdk.core.RuntimeLicense
import io.github.tianrking.ffmpegsdk.core.TranscodeJob
import io.github.tianrking.ffmpegsdk.engine.ffmpegkit.FfmpegKitEngine
import io.github.tianrking.ffmpegsdk.engine.ffmpegkit.FfmpegKitRuntimePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inputUri: Uri? = null
    private var activeTask: MediaTask? = null

    private lateinit var chooseButton: Button
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        inputUri = uri
        status.text = getString(R.string.input_selected, uri)
        startButton.isEnabled = true
    }

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { outputUri ->
        outputUri ?: return@registerForActivityResult
        persist(outputUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startTranscode(outputUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        showDeviceCapability()
    }

    override fun onDestroy() {
        activeTask?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.app_subtitle)
            textSize = 14f
            setPadding(0, padding / 3, 0, padding)
        })

        chooseButton = Button(this).apply {
            text = getString(R.string.select_input)
            setOnClickListener { openDocument.launch(arrayOf("video/*")) }
        }
        startButton = Button(this).apply {
            text = getString(R.string.export_h264)
            isEnabled = false
            setOnClickListener { createDocument.launch(getString(R.string.output_filename)) }
        }
        cancelButton = Button(this).apply {
            text = getString(R.string.cancel_job)
            isEnabled = false
            setOnClickListener { activeTask?.cancel() }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1_000
            isIndeterminate = true
        }
        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, padding, 0, 0)
            setTextIsSelectable(true)
        }

        listOf(chooseButton, startButton, cancelButton, progress, status).forEach(content::addView)
        setContentView(ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        })
    }

    private fun showDeviceCapability() {
        val encoder = MediaCodecSurvey().findEncoder(
            EncoderRequest(
                mimeType = "video/avc",
                width = 1_920,
                height = 1_080,
                bitrate = 4_000_000,
                frameRate = 30,
            ),
        )
        status.text = getString(
            R.string.device_encoder,
            encoder ?: getString(R.string.encoder_unavailable),
        )
    }

    private fun startTranscode(outputUri: Uri) {
        val source = checkNotNull(inputUri)
        val sdk = FfmpegSdk(
            FfmpegKitEngine(
                applicationContext,
                FfmpegKitRuntimePolicy(
                    runtimeLicense = RuntimeLicense.LGPL,
                    allowedFfmpegMajorVersions = setOf(8),
                    distributionLabel = "evaluation Maven runtime",
                ),
            ),
        )
        val job = TranscodeJob(
            input = MediaReference.ContentUri(source.toString()),
            output = MediaReference.ContentUri(outputUri.toString()),
            overwrite = true,
        )

        val task = sdk.submit(scope, job)
        activeTask = task
        cancelButton.isEnabled = true
        chooseButton.isEnabled = false
        startButton.isEnabled = false
        progress.isIndeterminate = true

        scope.launch {
            task.events.collect(::renderEvent)
        }
        scope.launch {
            val result = runCatching { task.result.await() }
            renderResult(result.getOrNull(), result.exceptionOrNull())
        }
    }

    private fun renderEvent(event: ExecutionEvent) {
        when (event) {
            is ExecutionEvent.Probed -> status.text = getString(
                R.string.probe_status,
                event.probe.durationMs?.toString() ?: getString(R.string.unknown),
                event.probe.formatNames,
            )
            is ExecutionEvent.Planned -> status.text = resources.getQuantityString(
                R.plurals.planned_status,
                event.plan.attempts.size,
                event.plan.attempts.size,
            )
            is ExecutionEvent.AttemptStarted -> status.text = getString(
                R.string.attempt_status,
                event.attempt.index,
                event.attempt.videoEncoder ?: getString(R.string.stream_copy),
            )
            is ExecutionEvent.Progress -> {
                event.fraction?.let {
                    progress.isIndeterminate = false
                    progress.progress = (it * progress.max).toInt()
                }
                status.text = resources.getQuantityString(
                    R.plurals.encoding_status,
                    event.outputBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    (event.fraction?.times(100))?.toInt()?.toString() ?: "?",
                    event.speed,
                    event.outputBytes,
                )
            }
            is ExecutionEvent.Log -> Unit
            is ExecutionEvent.AttemptFinished -> Unit
        }
    }

    private fun renderResult(result: MediaResult?, error: Throwable?) {
        activeTask = null
        cancelButton.isEnabled = false
        chooseButton.isEnabled = true
        startButton.isEnabled = inputUri != null
        progress.isIndeterminate = false
        status.text = when {
            error is CancellationException -> getString(R.string.export_cancelled)
            error != null -> getString(R.string.execution_interrupted, error.message)
            result is MediaResult.Success -> getString(R.string.export_complete, result.attempt)
            result is MediaResult.Cancelled -> getString(R.string.export_cancelled)
            result is MediaResult.Failure -> getString(R.string.export_failed, result.message)
            else -> getString(R.string.no_result)
        }
    }

    private fun persist(uri: Uri, flags: Int) {
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
    }
}
