package com.example.smoothplayer.feature.player.capability

/** Pure state transitions for the capability screen. No Android imports, so it runs in JVM tests. */
internal object CapabilityReducer {
    fun reduce(state: CapabilityUiState, event: CapabilityEvent): CapabilityUiState = when (event) {
        is CapabilityEvent.ProbeSucceeded -> state.copy(
            phase = CapabilityUiState.Phase.Ready,
            checks = event.checks,
            reportJson = event.reportJson,
            error = null,
        )
        is CapabilityEvent.ProbeFailed -> state.copy(
            phase = CapabilityUiState.Phase.Failed,
            error = event.reason,
        )
        CapabilityEvent.ExportClicked ->
            if (state.phase == CapabilityUiState.Phase.Ready) state.copy(export = ExportStatus.Exporting) else state
        is CapabilityEvent.ExportSucceeded -> state.copy(export = ExportStatus.Saved(event.path))
        is CapabilityEvent.ExportFailed -> state.copy(export = ExportStatus.Failed(event.reason))
    }
}
