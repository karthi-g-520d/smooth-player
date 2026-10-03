package com.example.smoothplayer.feature.player.capability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smoothplayer.engine.api.CapabilityProbe
import com.example.smoothplayer.engine.api.model.checks
import com.example.smoothplayer.engine.api.model.toJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs the probe once per screen, then turns button presses into exports. Dispatchers are injected. */
public class CapabilityViewModel(
    private val probe: CapabilityProbe,
    private val exporter: CapabilityExporter,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CapabilityUiState())
    public val uiState: StateFlow<CapabilityUiState> = _uiState.asStateFlow()

    init {
        runProbe()
    }

    public fun onEvent(event: CapabilityEvent) {
        _uiState.update { CapabilityReducer.reduce(it, event) }
        when (event) {
            CapabilityEvent.ExportClicked ->
                if (_uiState.value.export == ExportStatus.Exporting) exportReport(_uiState.value.reportJson)
            else -> Unit
        }
    }

    private fun runProbe() {
        viewModelScope.launch {
            val event: CapabilityEvent = withContext(ioDispatcher) {
                runCatching { probe.probe() }.fold(
                    onSuccess = { report -> CapabilityEvent.ProbeSucceeded(report.checks(), report.toJson()) },
                    onFailure = { error -> CapabilityEvent.ProbeFailed(error.message ?: error.toString()) },
                )
            }
            onEvent(event)
        }
    }

    private fun exportReport(json: String) {
        viewModelScope.launch {
            val event: CapabilityEvent = withContext(ioDispatcher) {
                runCatching { exporter.export(json) }.fold(
                    onSuccess = { path -> CapabilityEvent.ExportSucceeded(path) },
                    onFailure = { error -> CapabilityEvent.ExportFailed(error.message ?: error.toString()) },
                )
            }
            onEvent(event)
        }
    }

    public class Factory(
        private val probe: CapabilityProbe,
        private val exporter: CapabilityExporter,
        private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CapabilityViewModel(probe, exporter, ioDispatcher) as T
    }
}
