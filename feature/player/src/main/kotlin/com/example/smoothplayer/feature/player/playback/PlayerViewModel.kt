package com.example.smoothplayer.feature.player.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smoothplayer.engine.api.PlaybackEngine
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
 * No dispatcher is injected here: [PlaybackEngine.open] is a suspend function that already does
 * its own dispatcher switch internally (unlike [com.example.smoothplayer.engine.api.CapabilityProbe],
 * whose blocking `probe()` call forces the capability screen to own that switch itself).
 */
public class PlayerViewModel(
    private val engine: PlaybackEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    public val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private val _effects = Channel<PlayerEffect>(Channel.BUFFERED)
    public val effects: Flow<PlayerEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            engine.state.collect { onEvent(PlayerEvent.EngineStateChanged(it)) }
        }
    }

    public fun onEvent(event: PlayerEvent) {
        _uiState.update { PlayerReducer.reduce(it, event) }
        when (event) {
            PlayerEvent.ChooseFileClicked -> _effects.trySend(PlayerEffect.OpenFilePicker)
            is PlayerEvent.FileChosen -> openFile(event.uri)
            PlayerEvent.PlayPauseClicked -> togglePlayback()
            is PlayerEvent.EngineStateChanged -> Unit
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
            PlayerUiState.Phase.Ready, PlayerUiState.Phase.Paused -> engine.play()
            else -> Unit
        }
    }

    public class Factory(
        private val engine: PlaybackEngine,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(engine) as T
    }
}
