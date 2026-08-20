package io.github.tianrking.ffmpegsdk.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EncoderRequestTest {
    @Test
    fun `video dimensions must be complete and positive`() {
        assertFailsWith<IllegalArgumentException> {
            EncoderRequest(mimeType = "video/avc", width = 1_920)
        }
        assertFailsWith<IllegalArgumentException> {
            EncoderRequest(mimeType = "video/avc", width = -1, height = 1_080)
        }
    }

    @Test
    fun `audio request accepts sample rate and channel count`() {
        val request = EncoderRequest(
            mimeType = "audio/mp4a-latm",
            bitrate = 128_000,
            sampleRate = 48_000,
            channels = 2,
        )

        assertEquals(48_000, request.sampleRate)
        assertEquals(2, request.channels)
    }

    @Test
    fun `invalid rate and channel values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EncoderRequest(mimeType = "audio/opus", sampleRate = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            EncoderRequest(mimeType = "audio/opus", channels = 0)
        }
    }
}
