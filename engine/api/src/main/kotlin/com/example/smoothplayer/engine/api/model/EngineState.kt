package com.example.smoothplayer.engine.api.model

/** What [com.example.smoothplayer.engine.api.PlaybackEngine] is doing right now. */
public sealed interface EngineState {
    public data object Idle : EngineState
    public data object Preparing : EngineState
    public data class Ready(public val info: VideoInfo) : EngineState
    public data object Playing : EngineState
    public data object Paused : EngineState

    /** The last frame has been decoded. Calling play again restarts from the beginning. */
    public data object Ended : EngineState
    public data class Failed(public val error: EngineError) : EngineState
}
