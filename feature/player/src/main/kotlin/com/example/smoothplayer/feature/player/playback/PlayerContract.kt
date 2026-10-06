package com.example.smoothplayer.feature.player.playback

import com.example.smoothplayer.engine.api.model.EngineError
import com.example.smoothplayer.engine.api.model.EngineState
import com.example.smoothplayer.engine.api.model.InterpolationMode
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.PlaybackStats
import com.example.smoothplayer.engine.api.model.VideoInfo

/** Everything the player screen renders. */
public data class PlayerUiState(
    val phase: Phase = Phase.Idle,
    val videoInfo: VideoInfo? = null,
    val error: EngineError? = null,
    val position: Micros = Micros(0L),
    val stats: PlaybackStats? = null,
    val statsVisible: Boolean = false,
    val loggingEnabled: Boolean = false,
    val interpolation: InterpolationMode = InterpolationMode.Duplicate,
    val fullscreen: Boolean = false,
) {
    public enum class Phase { Idle, Preparing, Ready, Playing, Paused, Ended, Failed }
}

public sealed interface PlayerEvent {
    public data object ChooseFileClicked : PlayerEvent
    public data class FileChosen(val uri: String) : PlayerEvent
    public data object PlayPauseClicked : PlayerEvent
    public data class EngineStateChanged(val state: EngineState) : PlayerEvent
    public data class PositionChanged(val position: Micros) : PlayerEvent

    /** The user let go of the seek bar at [position]. The view model forwards it to the engine. */
    public data class SeekRequested(val position: Micros) : PlayerEvent

    public data class StatsReceived(val stats: PlaybackStats) : PlayerEvent
    public data object StatsToggled : PlayerEvent
    public data object LoggingToggled : PlayerEvent

    /** The user switched between duplicating and blending frames. The view model forwards the new mode to the engine. */
    public data object InterpolationToggled : PlayerEvent

    /** Enters or leaves full screen. The picture fills the window, and the host hides everything else. */
    public data object FullscreenToggled : PlayerEvent
}

public sealed interface PlayerEffect {
    public data object OpenFilePicker : PlayerEffect
}
