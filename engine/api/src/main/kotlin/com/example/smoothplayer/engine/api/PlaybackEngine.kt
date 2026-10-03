package com.example.smoothplayer.engine.api

import com.example.smoothplayer.engine.api.model.EngineState
import kotlinx.coroutines.flow.StateFlow

/**
 * Opens and controls one local video source.
 *
 * [play], [pause] and [release] are commands: they return immediately and the effect, if any,
 * shows up in [state]. [open] suspends while the source is probed, mirroring how the capability
 * screen's one-shot probe is called, but the engine's state keeps changing after that call returns.
 */
public interface PlaybackEngine {
    public val state: StateFlow<EngineState>

    /** Opens [uri] (a content:// or file:// URI string) for playback, replacing any current source. */
    public suspend fun open(uri: String)

    public fun play()

    public fun pause()

    /** Releases all resources. The engine cannot be reused after this; create a new instance. */
    public fun release()
}
