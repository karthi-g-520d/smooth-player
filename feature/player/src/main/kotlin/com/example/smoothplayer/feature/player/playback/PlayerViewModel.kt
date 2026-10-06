package com.example.smoothplayer.feature.player.playback

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smoothplayer.engine.api.PlaybackEngine
import com.example.smoothplayer.engine.api.RenderTargetController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bridges [PlaybackEngine]'s ongoing state into [PlayerUiState] and forwards commands to it.
 *
 * [renderTarget] must be the same object that owns the playback state. The fragment takes its surface
 * events from here, and this view model outlives activity recreation, so the surface always reaches the
 * engine that is decoding.
 *
 * No dispatcher is injected: [PlaybackEngine.open] is a suspend function that switches dispatchers itself.
 */
public class PlayerViewModel(
    private val engine: PlaybackEngine,
    public val renderTarget: RenderTargetController<Surface>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    public val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private val _effects = Channel<PlayerEffect>(Channel.BUFFERED)
    public val effects: Flow<PlayerEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            engine.state.collect { onEvent(PlayerEvent.EngineStateChanged(it)) }
        }
        viewModelScope.launch {
            engine.position.collect { onEvent(PlayerEvent.PositionChanged(it)) }
        }
        viewModelScope.launch {
            engine.stats.collect { onEvent(PlayerEvent.StatsReceived(it)) }
        }
    }

    public fun onEvent(event: PlayerEvent) {
        _uiState.update { PlayerReducer.reduce(it, event) }
        when (event) {
            PlayerEvent.ChooseFileClicked -> _effects.trySend(PlayerEffect.OpenFilePicker)
            is PlayerEvent.FileChosen -> openFile(event.uri)
            PlayerEvent.PlayPauseClicked -> togglePlayback()
            is PlayerEvent.SeekRequested -> engine.seekTo(event.position)
            PlayerEvent.LoggingToggled -> engine.setTelemetryLogging(_uiState.value.loggingEnabled)
            PlayerEvent.InterpolationToggled -> engine.setInterpolationMode(_uiState.value.interpolation)
            PlayerEvent.FullscreenToggled -> Unit
            is PlayerEvent.EngineStateChanged -> Unit
            is PlayerEvent.PositionChanged -> Unit
            is PlayerEvent.StatsReceived -> Unit
            PlayerEvent.StatsToggled -> Unit
        }
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }

    private fun openFile(uri: String) {
        viewModelScope.launch { engine.open(uri) }
    }

    private fun togglePlayback() {
        when (_uiState.value.phase) {
            PlayerUiState.Phase.Playing -> engine.pause()
            PlayerUiState.Phase.Ready, PlayerUiState.Phase.Paused, PlayerUiState.Phase.Ended -> engine.play()
            else -> Unit
        }
    }

    public class Factory(
        private val engine: PlaybackEngine,
        private val renderTarget: RenderTargetController<Surface>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(engine, renderTarget) as T
    }
}
