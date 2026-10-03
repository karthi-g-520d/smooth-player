package com.example.smoothplayer.engine.impl.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.smoothplayer.engine.api.PlaybackEngine
import com.example.smoothplayer.engine.api.model.EngineError
import com.example.smoothplayer.engine.api.model.EngineState
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.VideoInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val MIME_VIDEO_PREFIX = "video/"
private const val MIME_AUDIO_PREFIX = "audio/"
private const val NO_ROTATION_DEGREES = 0

/**
 * Step 2 (branch 1): opens a local file and reports its track info. There is no decoder yet, so
 * [play] and [pause] only move the reported state; nothing actually renders or plays sound until
 * the decode branches land.
 */
public class DefaultPlaybackEngine(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : PlaybackEngine {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override suspend fun open(uri: String) {
        _state.value = EngineState.Preparing
        _state.value = withContext(ioDispatcher) { probe(uri) }
    }

    override fun play() {
        when (_state.value) {
            is EngineState.Ready, EngineState.Paused -> _state.value = EngineState.Playing
            else -> Unit
        }
    }

    override fun pause() {
        if (_state.value == EngineState.Playing) {
            _state.value = EngineState.Paused
        }
    }

    override fun release() {
        _state.value = EngineState.Idle
    }

    private fun probe(uri: String): EngineState {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            EngineState.Ready(extractor.readVideoInfo())
        } catch (e: Exception) {
            EngineState.Failed(EngineError.SourceUnavailable(e.message ?: uri))
        } finally {
            extractor.release()
        }
    }
}

private fun MediaExtractor.readVideoInfo(): VideoInfo {
    var width = 0
    var height = 0
    var durationUs = 0L
    var rotationDegrees = NO_ROTATION_DEGREES
    var hasAudio = false
    for (trackIndex in 0 until trackCount) {
        val format = getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith(MIME_VIDEO_PREFIX)) {
            // MediaFormat's 2-arg getInteger/getLong overloads need API 29; minSdk here is 26,
            // so every key is guarded with containsKey instead.
            width = format.intOrDefault(MediaFormat.KEY_WIDTH, width)
            height = format.intOrDefault(MediaFormat.KEY_HEIGHT, height)
            durationUs = format.longOrDefault(MediaFormat.KEY_DURATION, durationUs)
            rotationDegrees = format.intOrDefault(MediaFormat.KEY_ROTATION, NO_ROTATION_DEGREES)
        } else if (mime.startsWith(MIME_AUDIO_PREFIX)) {
            hasAudio = true
        }
    }
    return VideoInfo(
        durationUs = Micros(durationUs),
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        hasAudio = hasAudio,
    )
}

private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

private fun MediaFormat.longOrDefault(key: String, default: Long): Long =
    if (containsKey(key)) getLong(key) else default
