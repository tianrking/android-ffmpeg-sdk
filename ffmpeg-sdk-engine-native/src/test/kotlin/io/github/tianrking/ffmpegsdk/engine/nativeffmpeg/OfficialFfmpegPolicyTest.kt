package io.github.tianrking.ffmpegsdk.engine.nativeffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OfficialFfmpegPolicyTest {
    @Test
    fun `filter resource values are escaped once without shell quoting`() {
        assertEquals(
            "a\\:b\\,c\\[d\\]\\;e\\'f\\\\g",
            escapeFilterValue("a:b,c[d];e'f\\g"),
        )
    }

    @Test
    fun `network policy defaults to https only`() {
        assertEquals(setOf("https"), OfficialFfmpegNetworkPolicy().allowedSchemes)
    }

    @Test
    fun `runtime requires an explicit supported major`() {
        assertFailsWith<IllegalArgumentException> {
            OfficialFfmpegRuntimePolicy(allowedFfmpegMajorVersions = emptySet())
        }
    }
}
