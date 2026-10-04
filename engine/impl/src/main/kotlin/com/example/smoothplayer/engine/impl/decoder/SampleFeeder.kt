package com.example.smoothplayer.engine.impl.decoder

import android.media.MediaCodec
import android.media.MediaExtractor
import java.util.ArrayDeque

/**
 * Moves compressed samples from [extractor] into a codec's input buffers. Shared by the video and audio
 * decoders. Only the codec's own thread may call it.
 *
 * Buffers are filled in the order the codec frees them. The end of the track is sent exactly once.
 */
internal class SampleFeeder(private val extractor: MediaExtractor) {
    private val freeInputs = ArrayDeque<Int>()
    private var ended = false

    fun onInputAvailable(index: Int) {
        freeInputs.addLast(index)
    }

    /** Forgets the free buffers and the end-of-track marker. Call after the codec is flushed, because its indices are invalid. */
    fun reset() {
        freeInputs.clear()
        ended = false
    }

    /** Queues samples into every free input buffer, unless [paused]. */
    fun feed(codec: MediaCodec, paused: Boolean) {
        while (!paused && !ended && freeInputs.isNotEmpty()) {
            val index = freeInputs.removeFirst()
            val buffer = checkNotNull(codec.getInputBuffer(index))
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                ended = true
            } else {
                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, extractor.sampleFlags)
                extractor.advance()
            }
        }
    }
}
