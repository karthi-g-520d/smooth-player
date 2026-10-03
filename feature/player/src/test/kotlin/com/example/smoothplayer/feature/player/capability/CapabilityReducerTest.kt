package com.example.smoothplayer.feature.player.capability

import com.example.smoothplayer.engine.api.model.CapabilityCheck
import com.example.smoothplayer.engine.api.model.CapabilityCheckId
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityReducerTest {
    private val check = CapabilityCheck(CapabilityCheckId.GLES_31, passed = true, detail = "3.2")

    private fun ready(): CapabilityUiState = CapabilityReducer.reduce(
        CapabilityUiState(),
        CapabilityEvent.ProbeSucceeded(listOf(check), "{}"),
    )

    @Test
    fun `successful probe moves to ready with checks and report json`() {
        val state = ready()
        assertEquals(CapabilityUiState.Phase.Ready, state.phase)
        assertEquals(listOf(check), state.checks)
        assertEquals("{}", state.reportJson)
    }

    @Test
    fun `failed probe keeps the reason and moves to failed`() {
        val state = CapabilityReducer.reduce(CapabilityUiState(), CapabilityEvent.ProbeFailed("boom"))
        assertEquals(CapabilityUiState.Phase.Failed, state.phase)
        assertEquals("boom", state.error)
    }

    @Test
    fun `export is ignored until the probe has finished`() {
        val state = CapabilityReducer.reduce(CapabilityUiState(), CapabilityEvent.ExportClicked)
        assertEquals(ExportStatus.Idle, state.export)
    }

    @Test
    fun `export starts when ready and ends as saved or failed`() {
        val running = CapabilityReducer.reduce(ready(), CapabilityEvent.ExportClicked)
        assertEquals(ExportStatus.Exporting, running.export)

        val saved = CapabilityReducer.reduce(running, CapabilityEvent.ExportSucceeded("/x.json"))
        assertEquals(ExportStatus.Saved("/x.json"), saved.export)

        val failed = CapabilityReducer.reduce(running, CapabilityEvent.ExportFailed("disk full"))
        assertEquals(ExportStatus.Failed("disk full"), failed.export)
    }
}
