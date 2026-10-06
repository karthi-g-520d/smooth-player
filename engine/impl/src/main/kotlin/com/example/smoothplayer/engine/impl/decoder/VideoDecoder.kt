package com.example.smoothplayer.engine.impl.decoder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.example.smoothplayer.engine.api.model.Micros
import com.example.smoothplayer.engine.impl.clock.MasterClock
import com.example.smoothplayer.engine.impl.pacer.FrameDecision
import com.example.smoothplayer.engine.impl.pacer.decideFrame
import com.example.smoothplayer.engine.impl.pacer.shouldPrefetchNext
import com.example.smoothplayer.engine.impl.telemetry.FrameTelemetry
import com.example.smoothplayer.engine.impl.telemetry.TraceNames
import com.example.smoothplayer.engine.impl.telemetry.trace
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val DECODER_THREAD_NAME = "FI-Decoder"
private const val RELEASE_TIMEOUT_MS = 500L
private const val MICROS_PER_MILLI = 1_000L

/**
 * Decodes one video track of [extractor] into a surface with an asynchronous [MediaCodec].
 *
 * Everything that touches the codec runs on the FI-Decoder thread. Decoded frames wait in [heldOutputs]
 * until [clock] says they are due. A frame is then shown, dropped when it is more than 40 ms late, or
 * held and checked again later (see [decideFrame]). While paused, input and output are both held.
 *
 * A seek sets [seekTargetUs]. Frames before the target are discarded, and the first frame at or after it
 * is shown at once, even while paused, so a seek always lands on a picture.
 *
 * Each frame sent to the surface is copied by the GL thread before the next one is sent, because a surface keeps
 * only its newest frame. [onFrameCopied] signals that the copy is done. While [blendsAhead] holds, the frame after
 * the last one released is sent as soon as that last frame is due, so the GL thread has both frames of a blend.
 *
 * A seek replaces the codec rather than flushing it. A flushed codec can still deliver callbacks that were
 * queued before the flush, and those name buffers the codec no longer owns. Callbacks are ignored unless
 * they come from the current codec.
 *
 * [outputSurfaceProvider] runs on the decoder thread, so waiting for the surface does not block the UI.
 * The extractor is used only from the decoder thread until [release] returns.
 */
internal class VideoDecoder(
    private val extractor: MediaExtractor,
    private val trackIndex: Int,
    private val trackFormat: MediaFormat,
    private val clock: MasterClock,
    private val releaseTargetUs: () -> Micros,
    private val blendsAhead: () -> Boolean,
    private val telemetry: FrameTelemetry,
    private val outputSurfaceProvider: () -> Surface,
    private val onFrameShown: (Micros) -> Unit,
    private val onEndOfStream: () -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val thread = HandlerThread(DECODER_THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)
    private val feeder = SampleFeeder(extractor)
    private val heldOutputs = ArrayDeque<HeldOutput>()
    private val pollHeldOutputs = Runnable { releaseHeldOutputs() }
    private var codec: MediaCodec? = null
    private var paused = false
    private var seekTargetUs: Micros? = null

    // Frame sent to the surface most recently, cleared on seek. A prefetch is judged against it.
    private var lastReleasedUs: Micros? = null

    // True from a release to the surface until the GL thread has copied that frame. Only the decoder thread reads it.
    private var copyPending = false

    private class HeldOutput(val index: Int, val ptsUs: Micros, val size: Int, val flags: Int) {
        val endOfStream: Boolean get() = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

        /** The codec's empty end marker carries no picture, so it is never paced or shown. */
        val isEmptyEnd: Boolean get() = endOfStream && size == 0
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mediaCodec: MediaCodec, index: Int) {
            if (!isCurrent(mediaCodec)) return
            feeder.onInputAvailable(index)
            feeder.feed(mediaCodec, !canDecode())
        }

        override fun onOutputBufferAvailable(mediaCodec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!isCurrent(mediaCodec)) return
            heldOutputs.addLast(HeldOutput(index, Micros(info.presentationTimeUs), info.size, info.flags))
            releaseHeldOutputs()
        }

        override fun onOutputFormatChanged(mediaCodec: MediaCodec, format: MediaFormat) = Unit

        override fun onError(mediaCodec: MediaCodec, e: MediaCodec.CodecException) {
            if (!isCurrent(mediaCodec)) return
            onFailure(e.diagnosticInfo)
        }
    }

    /** Selects the track, configures the codec and starts it. Failures are reported through [onFailure]. */
    fun start() {
        handler.post {
            try {
                extractor.selectTrack(trackIndex)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                startCodec()
            } catch (e: Exception) {
                onFailure(e.message ?: e.toString())
            }
        }
    }

    /**
     * Called on every vsync, once the pacer has published its target. Releases any frame the target now covers.
     * Releases happen right after the vsync, so every frame switch lands on the same side of a vsync.
     */
    fun onVsyncTick() {
        handler.post { releaseHeldOutputs() }
    }

    /** Called on the GL thread once a released frame is in the ring. Releases the next frame if one is waiting. */
    fun onFrameCopied() {
        handler.post {
            copyPending = false
            releaseHeldOutputs()
        }
    }

    /** Holds decoding when [value] is true, and resumes it when false. */
    fun setPaused(value: Boolean) {
        handler.post {
            paused = value
            if (!value) {
                codec?.let { feeder.feed(it, !canDecode()) }
                releaseHeldOutputs()
            }
        }
    }

    /**
     * Jumps to the sync frame at or before [target] and discards forward to it. The codec is replaced, so
     * frames decoded before the seek cannot reach the screen. Runs on the decoder thread.
     */
    fun seekTo(target: Micros) {
        handler.post {
            seekTargetUs = target
            lastReleasedUs = null
            copyPending = false
            handler.removeCallbacks(pollHeldOutputs)
            heldOutputs.clear()
            extractor.seekTo(target.value, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            feeder.reset()
            try {
                releaseCodec()
                startCodec()
            } catch (e: Exception) {
                onFailure(e.message ?: e.toString())
            }
        }
    }

    /** Stops and releases the codec, then the thread. Blocks briefly so the output surface is unused on return. */
    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            try {
                handler.removeCallbacks(pollHeldOutputs)
                releaseCodec()
                heldOutputs.clear()
            } finally {
                done.countDown()
            }
        }
        done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    /** Creates, configures and starts a codec for [trackFormat], then makes it the current one. */
    private fun startCodec() {
        val mime = checkNotNull(trackFormat.getString(MediaFormat.KEY_MIME))
        codec = startDecoder(mime, callback, handler, trackFormat, outputSurfaceProvider())
    }

    /** Detaches the current codec first, so any callbacks it still posts are recognised as stale. */
    private fun releaseCodec() {
        val old = codec ?: return
        codec = null
        closeDecoder(old)
    }

    private fun isCurrent(mediaCodec: MediaCodec): Boolean = mediaCodec === codec

    /** Input and output run while playing, and also while a seek is looking for its landing frame. */
    private fun canDecode(): Boolean = !paused || seekTargetUs != null

    /**
     * Releases held frames in order while [canDecode] holds. A frame before a pending seek target is dropped.
     * The first frame at or after the target is shown at once. Other frames wait for the clock, except that
     * a blend may prefetch the one after the last released frame (see [shouldPrefetchNext]).
     */
    private fun releaseHeldOutputs() {
        handler.removeCallbacks(pollHeldOutputs)
        val mediaCodec = codec ?: return
        while (canDecode() && heldOutputs.isNotEmpty()) {
            val output = heldOutputs.first()
            val target = seekTargetUs
            val discardForSeek = target != null && output.ptsUs < target
            // Pacing decides against the content time the next vsync will show. Without pacing, the clock decides.
            val releaseClockUs = releaseTargetUs()
            val decision = when {
                output.isEmptyEnd -> FrameDecision.Render
                discardForSeek -> FrameDecision.Drop
                target != null -> FrameDecision.Render
                else -> decideFrame(output.ptsUs, releaseClockUs, clock.isStalled())
            }
            val prefetch = decision is FrameDecision.Wait &&
                shouldPrefetchNext(blendsAhead(), lastReleasedUs, releaseClockUs)
            if (decision is FrameDecision.Wait && !prefetch) {
                handler.postDelayed(pollHeldOutputs, maxOf(1L, decision.delayUs / MICROS_PER_MILLI))
                return
            }
            val render = !output.isEmptyEnd && (decision == FrameDecision.Render || prefetch)
            // Wait for the GL thread to copy the previous frame. The surface keeps only its newest frame, so an
            // earlier release here would drop it. onFrameCopied runs this loop again.
            if (render && copyPending) return
            heldOutputs.removeFirst()
            if (target != null && (output.ptsUs >= target || output.endOfStream)) seekTargetUs = null
            trace(TraceNames.VIDEO_RELEASE) { mediaCodec.releaseOutputBuffer(output.index, render) }
            if (render) {
                copyPending = true
                lastReleasedUs = output.ptsUs
            }
            if (!output.isEmptyEnd && !discardForSeek) {
                telemetry.onFrameReleased(
                    ptsUs = output.ptsUs,
                    rendered = render,
                    releaseNs = System.nanoTime(),
                )
            }
            if (render && !prefetch) onFrameShown(output.ptsUs)
            if (output.endOfStream) onEndOfStream()
        }
    }
}
