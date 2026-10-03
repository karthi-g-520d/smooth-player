package com.example.smoothplayer.engine.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityJsonTest {
    private fun report(model: String = "Phone") = CapabilityReport(
        generatedAtMillis = 42L,
        device = DeviceInfo("Acme", model, 36),
        gl = GlProbeResult.Unavailable("no context"),
        displayModes = emptyList(),
        activeDisplayModeId = null,
        decoders = emptyList(),
        thermalHeadroom = null,
    )

    @Test
    fun `minimal report renders as stable pretty JSON`() {
        val expected = """
            {
              "schemaVersion": 1,
              "generatedAtMillis": 42,
              "device": {
                "manufacturer": "Acme",
                "model": "Phone",
                "sdkInt": 36
              },
              "gl": {
                "available": false,
                "reason": "no context"
              },
              "displayModes": [],
              "activeDisplayModeId": null,
              "decoders": [],
              "thermalHeadroom": null
            }
        """.trimIndent()
        assertEquals(expected, report().toJson())
    }

    @Test
    fun `quotes backslashes and newlines are escaped`() {
        val json = report(model = "a\"b\\c\nd").toJson()
        assertTrue(json.contains("\"model\": \"a\\\"b\\\\c\\nd\""))
    }
}
