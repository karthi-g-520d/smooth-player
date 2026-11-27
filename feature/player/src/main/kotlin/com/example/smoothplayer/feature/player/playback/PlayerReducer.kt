package com.example.smoothplayer.feature.player.playback

import com.example.smoothplayer.engine.api.model.DebugView
import com.example.smoothplayer.engine.api.model.EngineState
import com.example.smoothplayer.engine.api.model.InterpolationMode

/** Pure state transitions for the player screen. No Android imports, so it runs in JVM tests. */
internal object PlayerReducer {
    fun reduce(state: PlayerUiState, event: PlayerEvent): PlayerUiState = when (event) {
        PlayerEvent.ChooseFileClicked -> state
        // A new file ends a benchmark run: its log is about the clip it started on.
        is PlayerEvent.FileChosen -> state.copy(phase = PlayerUiState.Phase.Preparing, error = null, benchRunning = false)
        PlayerEvent.PlayPauseClicked -> state
        is PlayerEvent.EngineStateChanged -> state.fromEngine(event.state)
        is PlayerEvent.PositionChanged -> state.copy(position = event.position)
        is PlayerEvent.SeekRequested -> state
        is PlayerEvent.StatsReceived -> state.copy(stats = event.stats)
        PlayerEvent.StatsToggled -> state.copy(statsVisible = !state.statsVisible)
        PlayerEvent.LoggingToggled -> state.copy(loggingEnabled = !state.loggingEnabled)
        PlayerEvent.BenchToggled -> state.copy(benchRunning = !state.benchRunning)
        PlayerEvent.InterpolationToggled -> state.copy(interpolation = state.interpolation.other())
        PlayerEvent.DebugViewCycled -> state.copy(debugView = state.debugView.next())
        PlayerEvent.FullscreenToggled -> state.copy(fullscreen = !state.fullscreen)
    }

    /** Duplicate, then blend, then motion-compensated, then back to duplicate. */
    private fun InterpolationMode.other(): InterpolationMode = when (this) {
        InterpolationMode.Duplicate -> InterpolationMode.Blend
        InterpolationMode.Blend -> InterpolationMode.MotionCompensated
        InterpolationMode.MotionCompensated -> InterpolationMode.Duplicate
    }

    /** Normal picture, then motion vectors, match error and warp confidence, then back to normal. */
    private fun DebugView.next(): DebugView = when (this) {
        DebugView.Off -> DebugView.MotionVectors
        DebugView.MotionVectors -> DebugView.MatchError
        DebugView.MatchError -> DebugView.WarpConfidence
        DebugView.WarpConfidence -> DebugView.Off
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
