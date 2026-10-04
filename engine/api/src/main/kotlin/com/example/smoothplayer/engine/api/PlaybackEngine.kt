package com.example.smoothplayer.engine.api

import com.example.smoothplayer.engine.api.model.EngineState
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.PlaybackStats
import kotlinx.coroutines.flow.StateFlow

/**
 * Opens and controls one local video source.
 *
 * [play], [pause], [seekTo] and [release] are commands: they return immediately and the effect, if any,
 * shows up in [state] and [position]. [open] suspends while the source is probed, mirroring how the capability
 * screen's one-shot probe is called, but the engine's state keeps changing after that call returns.
 */
public interface PlaybackEngine {
    public val state: StateFlow<EngineState>

    /** Time of the frame on screen, in the video's PTS timeline. Updated as frames are shown. */
    public val position: StateFlow<Micros>

    /** Per-second measurements for the debug overlay. Updated about once a second while frames are shown. */
    public val stats: StateFlow<PlaybackStats>

    /** Opens [uri] (a content:// or file:// URI string) for playback, replacing any current source. */
    public suspend fun open(uri: String)

    public fun play()

    public fun pause()

    /**
     * Moves playback to [position], clamped to the video's length. Playback keeps going if it was playing.
     * Otherwise it stays paused on the frame at the target.
     */
    public fun seekTo(position: Micros)

    /**
     * Starts or stops writing one CSV row per frame to a file in the app's external files folder. The file
     * path is reported in [stats]. Turning logging on mid-playback starts writing from the next frame.
     */
    public fun setTelemetryLogging(enabled: Boolean)

    /** Releases all resources. The engine cannot be reused after this; create a new instance. */
    public fun release()
}
