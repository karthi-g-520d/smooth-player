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
import com.example.smoothplayer.engine.impl.clock.MasterClock
import com.example.smoothplayer.engine.impl.clock.WallClock
import com.example.smoothplayer.engine.impl.decoder.AudioDecoder
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
private const val MAX_AUDIO_CHANNELS = 2

/**
 * Plays one local file into the surface it is given. Decoding is hardware. Audio is the master clock, and
 * video frames are shown when the audio position reaches them. A file with no audio uses the wall clock.
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

    private val _position = MutableStateFlow(Micros(0L))
    override val position: StateFlow<Micros> = _position.asStateFlow()

    @Volatile private var source: OpenSource? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioDecoder: AudioDecoder? = null
    private var wallClock: WallClock? = null
    private var renderer: VideoRenderer? = null

    override suspend fun open(uri: String) {
        closeSource()
        _position.value = Micros(0L)
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
            videoDecoder?.setPaused(true)
            audioDecoder?.setPaused(true)
            wallClock?.pause()
            _state.value = EngineState.Paused
        }
    }

    /**
     * Playing stays playing. Paused, or ended, leaves the engine paused on the frame at the target. Ended
     * is treated as paused, so the seek can land on a picture and the user can press play again.
     */
    override fun seekTo(position: Micros) {
        val opened = source ?: return
        val current = _state.value
        if (current != EngineState.Playing && current != EngineState.Paused && current != EngineState.Ended) return
        val target = clampToDuration(position, opened.info.durationUs)
        if (current != EngineState.Playing) {
            videoDecoder?.setPaused(true)
            audioDecoder?.setPaused(true)
            wallClock?.pause()
            _state.value = EngineState.Paused
        }
        wallClock?.reset(target)
        audioDecoder?.seekTo(target)
        videoDecoder?.seekTo(target)
        _position.value = target
    }

    override fun release() {
        stopPlayback()
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
        stopPlayback()
        _position.value = Micros(0L)

        val audio = try {
            opened.audio?.let { track ->
                AudioDecoder(track.extractor, track.trackIndex, track.format, onFailure = ::fail)
            }
        } catch (e: Exception) {
            fail(EngineError.CodecFailed(e.message ?: e.toString(), recoverable = false))
            return
        }
        val clock: MasterClock = audio?.clock ?: WallClock().also { wallClock = it }
        audioDecoder = audio

        val newDecoder = VideoDecoder(
            extractor = opened.video.extractor,
            trackIndex = opened.video.trackIndex,
            trackFormat = opened.video.format,
            clock = clock,
            outputSurfaceProvider = { videoRenderer.awaitFrameSurface() },
            onFrameShown = { _position.value = it },
            onEndOfStream = { _state.value = EngineState.Ended },
            onFailure = { reason -> fail(EngineError.CodecFailed(reason, recoverable = false)) },
        )
        videoDecoder = newDecoder
        audio?.start()
        wallClock?.start()
        newDecoder.start()
        _state.value = EngineState.Playing
    }

    private fun resume() {
        videoDecoder?.setPaused(false)
        audioDecoder?.setPaused(false)
        wallClock?.resume()
        _state.value = EngineState.Playing
    }

    private fun fail(error: EngineError) {
        _state.value = EngineState.Failed(error)
    }

    /** Stops video first, because it reads the clock that audio owns. */
    private fun stopPlayback() {
        videoDecoder?.release()
        videoDecoder = null
        audioDecoder?.release()
        audioDecoder = null
        wallClock = null
    }

    private fun closeSource() {
        stopPlayback()
        source?.let { opened ->
            opened.video.extractor.release()
            opened.audio?.extractor?.release()
        }
        source = null
    }

    private fun openSource(uri: String): OpenSource {
        val video = openTrack(uri, MIME_VIDEO_PREFIX) ?: error("no video track")
        val audio = try {
            openTrack(uri, MIME_AUDIO_PREFIX)
        } catch (e: Throwable) {
            video.extractor.release()
            throw e
        }
        if (audio != null && audio.format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 0) > MAX_AUDIO_CHANNELS) {
            audio.extractor.release()
            video.extractor.release()
            error("unsupported audio channel count")
        }
        val info = VideoInfo(
            durationUs = Micros(video.format.longOrDefault(MediaFormat.KEY_DURATION, 0L)),
            width = video.format.intOrDefault(MediaFormat.KEY_WIDTH, 0),
            height = video.format.intOrDefault(MediaFormat.KEY_HEIGHT, 0),
            rotationDegrees = video.format.intOrDefault(MediaFormat.KEY_ROTATION, NO_ROTATION_DEGREES),
            hasAudio = audio != null,
        )
        return OpenSource(video, audio, info)
    }

    /**
     * Opens its own extractor on the first track with [mimePrefix]. Each decoder gets a separate extractor,
     * so neither has to route the other's samples. Returns null when the file has no such track.
     */
    private fun openTrack(uri: String, mimePrefix: String): TrackSource? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val index = extractor.indexOfTrackWithMimePrefix(mimePrefix)
            if (index < 0) {
                extractor.release()
                return null
            }
            return TrackSource(extractor, index, extractor.getTrackFormat(index))
        } catch (e: Throwable) {
            extractor.release()
            throw e
        }
    }

    /** Everything needed to decode a file, kept from [open] until the next open or [release]. */
    private class OpenSource(
        val video: TrackSource,
        val audio: TrackSource?,
        val info: VideoInfo,
    )

    private class TrackSource(
        val extractor: MediaExtractor,
        val trackIndex: Int,
        val format: MediaFormat,
    )
}

/** Keeps a seek inside the file. A duration of zero means the length is unknown, so only the start is enforced. */
private fun clampToDuration(position: Micros, duration: Micros): Micros {
    val upper = if (duration.value > 0L) duration.value else Long.MAX_VALUE
    return Micros(position.value.coerceIn(0L, upper))
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
