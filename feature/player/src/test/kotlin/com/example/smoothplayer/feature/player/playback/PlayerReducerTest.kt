package com.example.smoothplayer.feature.player.playback

import com.example.smoothplayer.engine.api.model.EngineError
import com.example.smoothplayer.engine.api.model.EngineState
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerReducerTest {
    private val info = VideoInfo(
        durationUs = Micros(5_000_000),
        width = 1920,
        height = 1080,
        rotationDegrees = 0,
        hasAudio = true,
    )

    @Test
    fun `choosing a file moves to preparing and clears the error`() {
        val state = PlayerReducer.reduce(
            PlayerUiState(phase = PlayerUiState.Phase.Failed, error = EngineError.ThermalThrottled),
            PlayerEvent.FileChosen("content://clip.mp4"),
        )
        assertEquals(PlayerUiState.Phase.Preparing, state.phase)
        assertEquals(null, state.error)
    }

    @Test
    fun `engine ready carries the video info into ready`() {
        val state = PlayerReducer.reduce(
            PlayerUiState(phase = PlayerUiState.Phase.Preparing),
            PlayerEvent.EngineStateChanged(EngineState.Ready(info)),
        )
        assertEquals(PlayerUiState.Phase.Ready, state.phase)
        assertEquals(info, state.videoInfo)
    }

    @Test
    fun `engine failure keeps the error and moves to failed`() {
        val state = PlayerReducer.reduce(
            PlayerUiState(),
            PlayerEvent.EngineStateChanged(EngineState.Failed(EngineError.ThermalThrottled)),
        )
        assertEquals(PlayerUiState.Phase.Failed, state.phase)
        assertEquals(EngineError.ThermalThrottled, state.error)
    }

    @Test
    fun `engine playing and paused map straight through`() {
        val playing = PlayerReducer.reduce(PlayerUiState(), PlayerEvent.EngineStateChanged(EngineState.Playing))
        assertEquals(PlayerUiState.Phase.Playing, playing.phase)

        val paused = PlayerReducer.reduce(playing, PlayerEvent.EngineStateChanged(EngineState.Paused))
        assertEquals(PlayerUiState.Phase.Paused, paused.phase)
    }
}
