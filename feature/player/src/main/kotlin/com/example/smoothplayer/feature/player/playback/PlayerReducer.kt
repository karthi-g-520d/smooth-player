package com.example.smoothplayer.feature.player.playback

import com.example.smoothplayer.engine.api.model.EngineState

/** Pure state transitions for the player screen. No Android imports, so it runs in JVM tests. */
internal object PlayerReducer {
    fun reduce(state: PlayerUiState, event: PlayerEvent): PlayerUiState = when (event) {
        PlayerEvent.ChooseFileClicked -> state
        is PlayerEvent.FileChosen -> state.copy(phase = PlayerUiState.Phase.Preparing, error = null)
        PlayerEvent.PlayPauseClicked -> state
        is PlayerEvent.EngineStateChanged -> state.fromEngine(event.state)
        is PlayerEvent.PositionChanged -> state.copy(position = event.position)
        is PlayerEvent.SeekRequested -> state
    }

    private fun PlayerUiState.fromEngine(engineState: EngineState): PlayerUiState = when (engineState) {
        EngineState.Idle -> copy(phase = PlayerUiState.Phase.Idle, videoInfo = null, error = null)
        EngineState.Preparing -> copy(phase = PlayerUiState.Phase.Preparing)
        is EngineState.Ready -> copy(phase = PlayerUiState.Phase.Ready, videoInfo = engineState.info, error = null)
        EngineState.Playing -> copy(phase = PlayerUiState.Phase.Playing)
        EngineState.Paused -> copy(phase = PlayerUiState.Phase.Paused)
        EngineState.Ended -> copy(phase = PlayerUiState.Phase.Ended)
        is EngineState.Failed -> copy(phase = PlayerUiState.Phase.Failed, error = engineState.error)
    }
}
