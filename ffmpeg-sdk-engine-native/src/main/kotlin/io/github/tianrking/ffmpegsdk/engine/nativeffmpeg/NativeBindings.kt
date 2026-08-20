package io.github.tianrking.ffmpegsdk.engine.nativeffmpeg

import android.content.Context

internal fun interface NativeLogCallback {
    fun onLog(level: Int, message: String)
}

internal object NativeBindings {
    init {
        System.loadLibrary("ffmpeg_sdk_bridge")
    }

    external fun nativeInitialize(applicationContext: Context): Boolean
    external fun nativeVersion(): String
    external fun nativeConfiguration(): String
    external fun nativeLicense(): String
    external fun nativeComponents(kind: Int): String
    external fun nativeProbe(sessionId: Long, input: String): String
    external fun nativeCancelProbe(sessionId: Long): Boolean
    external fun nativeExecute(
        sessionId: Long,
        arguments: Array<String>,
        callback: NativeLogCallback,
    ): Int
    external fun nativeCancel(sessionId: Long): Boolean
}
