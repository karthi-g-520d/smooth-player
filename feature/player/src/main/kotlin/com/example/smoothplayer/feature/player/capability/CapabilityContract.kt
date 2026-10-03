package com.example.smoothplayer.feature.player.capability

import com.example.smoothplayer.engine.api.model.CapabilityCheck

/** Everything the capability screen renders. Persistent results live here so they survive rotation. */
public data class CapabilityUiState(
    val phase: Phase = Phase.Probing,
    val checks: List<CapabilityCheck> = emptyList(),
    val reportJson: String = "",
    val error: String? = null,
    val export: ExportStatus = ExportStatus.Idle,
) {
    public enum class Phase { Probing, Ready, Failed }
}

public sealed interface ExportStatus {
    public data object Idle : ExportStatus
    public data object Exporting : ExportStatus
    public data class Saved(val path: String) : ExportStatus
    public data class Failed(val reason: String) : ExportStatus
}

public sealed interface CapabilityEvent {
    public data class ProbeSucceeded(val checks: List<CapabilityCheck>, val reportJson: String) : CapabilityEvent
    public data class ProbeFailed(val reason: String) : CapabilityEvent
    public data object ExportClicked : CapabilityEvent
    public data class ExportSucceeded(val path: String) : CapabilityEvent
    public data class ExportFailed(val reason: String) : CapabilityEvent
}

/** Writes the report JSON somewhere the user can reach. The app provides the implementation. */
public fun interface CapabilityExporter {
    /** Returns the saved path. Throws when the file cannot be written. */
    public fun export(json: String): String
}
