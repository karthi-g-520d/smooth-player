package com.example.smoothplayer.engine.impl.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import com.example.smoothplayer.engine.api.PlaybackEngine
import com.example.smoothplayer.engine.api.RenderTargetController
import com.example.smoothplayer.engine.api.model.EngineError
import com.example.smoothplayer.engine.api.model.EngineState
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.api.model.VideoInfo
import com.example.smoothplayer.engine.impl.decoder.VideoDecoder
import com.example.smoothplayer.engine.impl.gl.VideoRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val MIME_VIDEO_PREFIX = "video/"
private const val MIME_AUDIO_PREFIX = "audio/"
private const val NO_ROTATION_DEGREES = 0

/**
 * Plays one local file into the surface it is given. Decoding is hardware, and frames are drawn as the
 * decoder produces them. Audio and A/V sync come in a later step.
 *
 * Threads are created on first use. An engine that is never shown or played starts none, which matters
 * because the view model that owns this engine can outlive several activity instances.
 */
public class DefaultPlaybackEngine(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : PlaybackEngine, RenderTargetController<Surface> {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var source: OpenSource? = null
    private var decoder: VideoDecoder? = null
    private var renderer: VideoRenderer? = null

    override suspend fun open(uri: String) {
        closeSource()
        _state.value = EngineState.Preparing
        _state.value = withContext(ioDispatcher) {
            try {
                source = openSource(uri)
                EngineState.Ready(checkNotNull(source).info)
            } catch (e: Exception) {
                EngineState.Failed(EngineError.SourceUnavailable(e.message ?: uri))
            }
        }
    }

    override fun play() {
        when (_state.value) {
            is EngineState.Ready, EngineState.Ended -> startFromBeginning()
            EngineState.Paused -> resume()
            else -> Unit
        }
    }

    override fun pause() {
        if (_state.value == EngineState.Playing) {
            decoder?.setPaused(true)
            _state.value = EngineState.Paused
        }
    }

    override fun release() {
        stopDecoder()
        renderer?.close()
        renderer = null
        closeSource()
        _state.value = EngineState.Idle
    }

    override fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
        ensureRenderer().attachWindow(surface, width, height)
    }

    override fun onSurfaceDestroyed() {
        renderer?.detachWindow()
    }

    private fun ensureRenderer(): VideoRenderer =
        renderer ?: VideoRenderer(context.assets).also { renderer = it }

    private fun startFromBeginning() {
        val opened = source ?: return
        val videoRenderer = ensureRenderer()
        videoRenderer.setContent(opened.info)
        stopDecoder()
        val newDecoder = VideoDecoder(
            extractor = opened.extractor,
            trackIndex = opened.videoTrack,
            trackFormat = opened.videoFormat,
            outputSurfaceProvider = { videoRenderer.awaitFrameSurface() },
            onEndOfStream = { _state.value = EngineState.Ended },
            onFailure = { reason ->
                _state.value = EngineState.Failed(EngineError.CodecFailed(reason, recoverable = false))
            },
        )
        decoder = newDecoder
        newDecoder.start()
        _state.value = EngineState.Playing
    }

    private fun resume() {
        decoder?.setPaused(false)
        _state.value = EngineState.Playing
    }

    private fun stopDecoder() {
        decoder?.release()
        decoder = null
    }

    private fun closeSource() {
        stopDecoder()
        source?.extractor?.release()
        source = null
    }

    private fun openSource(uri: String): OpenSource {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val videoTrack = extractor.indexOfTrackWithMimePrefix(MIME_VIDEO_PREFIX)
            check(videoTrack >= 0) { "no video track" }
            val format = extractor.getTrackFormat(videoTrack)
            val info = VideoInfo(
                durationUs = Micros(format.longOrDefault(MediaFormat.KEY_DURATION, 0L)),
                width = format.intOrDefault(MediaFormat.KEY_WIDTH, 0),
                height = format.intOrDefault(MediaFormat.KEY_HEIGHT, 0),
                rotationDegrees = format.intOrDefault(MediaFormat.KEY_ROTATION, NO_ROTATION_DEGREES),
                hasAudio = extractor.indexOfTrackWithMimePrefix(MIME_AUDIO_PREFIX) >= 0,
            )
            return OpenSource(extractor, videoTrack, format, info)
        } catch (e: Throwable) {
            extractor.release()
            throw e
        }
    }

    /** Everything needed to decode a file, kept from [open] until the next open or [release]. */
    private class OpenSource(
        val extractor: MediaExtractor,
        val videoTrack: Int,
        val videoFormat: MediaFormat,
        val info: VideoInfo,
    )
}

private fun MediaExtractor.indexOfTrackWithMimePrefix(prefix: String): Int =
    (0 until trackCount).firstOrNull { index ->
        getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(prefix)
    } ?: -1

// MediaFormat's 2-arg getInteger/getLong overloads need API 29; minSdk here is 26, so each key is
// checked with containsKey first.
private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

private fun MediaFormat.longOrDefault(key: String, default: Long): Long =
    if (containsKey(key)) getLong(key) else default
