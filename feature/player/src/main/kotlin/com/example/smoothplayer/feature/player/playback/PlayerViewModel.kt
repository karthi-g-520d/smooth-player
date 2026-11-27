package com.example.smoothplayer.feature.player.playback

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smoothplayer.engine.api.PlaybackEngine
import com.example.smoothplayer.engine.api.RenderTargetController
import com.example.smoothplayer.engine.api.model.EngineState
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.feature.player.BuildConfig
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
            is PlayerEvent.FileChosen -> {
                engine.setBenchmark(null)
                openFile(event.uri)
            }
            PlayerEvent.PlayPauseClicked -> togglePlayback()
            is PlayerEvent.SeekRequested -> engine.seekTo(event.position)
            PlayerEvent.LoggingToggled -> engine.setTelemetryLogging(_uiState.value.loggingEnabled)
            PlayerEvent.BenchToggled -> toggleBench()
            PlayerEvent.InterpolationToggled -> engine.setInterpolationMode(_uiState.value.interpolation)
            PlayerEvent.DebugViewCycled -> engine.setDebugView(_uiState.value.debugView)
            PlayerEvent.FullscreenToggled -> Unit
            is PlayerEvent.EngineStateChanged -> if (event.state == EngineState.Ended) loopIfBenchmarking()
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

    /**
     * Starts or ends a benchmark run. A run starts playback if it is not already going, so one tap is enough on the
     * phone. Its label carries the build, so a log from a stale APK is easy to spot.
     */
    private fun toggleBench() {
        if (!_uiState.value.benchRunning) {
            engine.setBenchmark(null)
            return
        }
        engine.setBenchmark("build=${BuildConfig.BUILD_ID}")
        when (_uiState.value.phase) {
            PlayerUiState.Phase.Ready, PlayerUiState.Phase.Paused, PlayerUiState.Phase.Ended -> engine.play()
            else -> Unit
        }
    }

    /** Sustained runs need 10 to 60 minutes of playback, longer than most clips, so a run plays the clip again. */
    private fun loopIfBenchmarking() {
        if (!_uiState.value.benchRunning) return
        // From Ended, a seek leaves the engine paused at the start without resetting the run's counters.
        engine.seekTo(Micros(0L))
        engine.play()
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
