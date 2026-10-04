package com.example.smoothplayer.engine.impl.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import com.example.smoothplayer.engine.api.model.EngineError
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.impl.audio.AudioClock
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val AUDIO_THREAD_NAME = "FI-Audio"
private const val RELEASE_TIMEOUT_MS = 500L
private const val BUFFER_MULTIPLIER = 4
private const val WRITE_RETRY_MS = 5L
private const val BYTES_PER_SAMPLE = 2
private const val MONO_CHANNELS = 1

/**
 * Decodes the audio track of [extractor] to 16-bit PCM and plays it through an [AudioTrack].
 *
 * The FI-Audio thread runs the codec callbacks and every write to the track. Writes are non-blocking, so
 * pause and release never wait behind a full device buffer. [clock] reports how far the device has played,
 * and the video decoder paces frames against it.
 *
 * The extractor is used only from FI-Audio until [release] returns.
 */
internal class AudioDecoder(
    private val extractor: MediaExtractor,
    private val trackIndex: Int,
    private val trackFormat: MediaFormat,
    private val onFailure: (EngineError) -> Unit,
) {
    private val sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    private val channelCount = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    private val channelMask = if (channelCount == MONO_CHANNELS) {
        AudioFormat.CHANNEL_OUT_MONO
    } else {
        AudioFormat.CHANNEL_OUT_STEREO
    }
    private val bytesPerFrame = channelCount * BYTES_PER_SAMPLE
    private val track: AudioTrack = createTrack()

    /** Master clock for the video side. It reads the position of [track]. */
    val clock: AudioClock = AudioClock(track, sampleRate)

    private val thread = HandlerThread(AUDIO_THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)
    private val feeder = SampleFeeder(extractor)
    private val pendingOutputs = ArrayDeque<PendingOutput>()
    private val retryWrite = Runnable { writePending() }
    private var codec: MediaCodec? = null
    private var paused = false
    private var started = false
    private var writtenFrames = 0L

    private class PendingOutput(
        val index: Int,
        val ptsUs: Micros,
        val offset: Int,
        val size: Int,
        val endOfStream: Boolean,
    ) {
        var written = 0
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mediaCodec: MediaCodec, index: Int) {
            feeder.onInputAvailable(index)
            feeder.feed(mediaCodec, paused)
        }

        override fun onOutputBufferAvailable(mediaCodec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val endOfStream = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            pendingOutputs.addLast(
                PendingOutput(index, Micros(info.presentationTimeUs), info.offset, info.size, endOfStream),
            )
            writePending()
        }

        override fun onOutputFormatChanged(mediaCodec: MediaCodec, format: MediaFormat) = Unit

        override fun onError(mediaCodec: MediaCodec, e: MediaCodec.CodecException) {
            onFailure(EngineError.CodecFailed(e.diagnosticInfo ?: e.message.orEmpty(), e.isRecoverable))
        }
    }

    /** Selects the track, configures the codec and starts it. Failures are reported through [onFailure]. */
    fun start() {
        handler.post {
            try {
                extractor.selectTrack(trackIndex)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val mime = checkNotNull(trackFormat.getString(MediaFormat.KEY_MIME))
                val mediaCodec = MediaCodec.createDecoderByType(mime)
                mediaCodec.setCallback(callback, handler)
                mediaCodec.configure(trackFormat, null, null, 0)
                mediaCodec.start()
                codec = mediaCodec
            } catch (e: Exception) {
                onFailure(EngineError.CodecFailed(e.message ?: e.toString(), recoverable = false))
            }
        }
    }

    /** Pauses or resumes the track. The clock stops with it, because the device stops counting frames. */
    fun setPaused(value: Boolean) {
        handler.post {
            paused = value
            if (value) {
                if (started) track.pause()
            } else {
                if (started) track.play()
                writePending()
            }
        }
    }

    /** Stops and releases the codec and the track, then the thread. Blocks briefly, like [VideoDecoder.release]. */
    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            try {
                handler.removeCallbacks(retryWrite)
                codec?.let { mediaCodec ->
                    runCatching { mediaCodec.stop() }
                    mediaCodec.release()
                }
                codec = null
                pendingOutputs.clear()
                runCatching { track.stop() }
                track.release()
            } finally {
                done.countDown()
            }
        }
        done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    /** Writes queued PCM to the track until it is full, then retries shortly. Releases each codec buffer once written. */
    private fun writePending() {
        handler.removeCallbacks(retryWrite)
        val mediaCodec = codec ?: return
        while (!paused && pendingOutputs.isNotEmpty()) {
            val output = pendingOutputs.first()
            if (output.written < output.size) {
                val buffer = checkNotNull(mediaCodec.getOutputBuffer(output.index))
                buffer.limit(output.offset + output.size)
                buffer.position(output.offset + output.written)
                val bytes = track.write(buffer, output.size - output.written, AudioTrack.WRITE_NON_BLOCKING)
                if (bytes < 0) {
                    onFailure(EngineError.CodecFailed("audio write failed: $bytes", recoverable = false))
                    return
                }
                if (bytes == 0) {
                    handler.postDelayed(retryWrite, WRITE_RETRY_MS)
                    return
                }
                if (!started) startPlayback(output.ptsUs)
                output.written += bytes
                writtenFrames += bytes / bytesPerFrame
            }
            if (output.written >= output.size) {
                pendingOutputs.removeFirst()
                mediaCodec.releaseOutputBuffer(output.index, false)
                if (output.endOfStream) clock.finishAt(writtenFrames)
            }
        }
    }

    /** Anchors the clock to the first chunk's PTS, then starts the device. The head counter is still zero here. */
    private fun startPlayback(firstPtsUs: Micros) {
        clock.anchorAt(firstPtsUs)
        started = true
        track.play()
    }

    private fun createTrack(): AudioTrack {
        val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        check(minBufferBytes > 0) { "no audio output for $sampleRate Hz, $channelCount channels" }
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferBytes * BUFFER_MULTIPLIER)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(created.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack did not initialize" }
        return created
    }
}
