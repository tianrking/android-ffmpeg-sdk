package io.github.tianrking.ffmpegsdk.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public object JobJson {
    private val codec: Json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    public fun encode(job: TranscodeJob): String =
        codec.encodeToString(MediaJobEnvelope(job = job))

    public fun decode(json: String): TranscodeJob =
        codec.decodeFromString<MediaJobEnvelope>(json).job
}
