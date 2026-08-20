package io.github.tianrking.ffmpegsdk.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public const val CURRENT_MEDIA_JOB_SCHEMA_VERSION: Int = 2

@Serializable
public data class TypedMediaJobEnvelope(
    val schemaVersion: Int = CURRENT_MEDIA_JOB_SCHEMA_VERSION,
    val job: MediaJob,
) {
    init {
        require(schemaVersion == CURRENT_MEDIA_JOB_SCHEMA_VERSION) {
            "Unsupported typed media job schema version: $schemaVersion"
        }
    }
}

/** Versioned JSON codec for the complete typed media-job family. */
public object MediaJobJson {
    private val codec: Json = Json {
        classDiscriminator = "operation"
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    public fun encode(job: MediaJob): String =
        codec.encodeToString(TypedMediaJobEnvelope(job = job))

    public fun decode(json: String): MediaJob =
        codec.decodeFromString<TypedMediaJobEnvelope>(json).job
}
