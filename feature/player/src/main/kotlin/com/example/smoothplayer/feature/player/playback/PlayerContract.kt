package com.example.smoothplayer.feature.player.playback

import com.example.smoothplayer.engine.api.model.EngineError
import com.example.smoothplayer.engine.api.model.EngineState
import com.example.smoothplayer.engine.api.model.VideoInfo

/** Everything the player screen renders. */
public data class PlayerUiState(
    val phase: Phase = Phase.Idle,
    val videoInfo: VideoInfo? = null,
    val error: EngineError? = null,
) {
    public enum class Phase { Idle, Preparing, Ready, Playing, Paused, Ended, Failed }
}

public sealed interface PlayerEvent {
    public data object ChooseFileClicked : PlayerEvent
    public data class FileChosen(val uri: String) : PlayerEvent
    public data object PlayPauseClicked : PlayerEvent
    public data class EngineStateChanged(val state: EngineState) : PlayerEvent
}

public sealed interface PlayerEffect {
    public data object OpenFilePicker : PlayerEffect
}
