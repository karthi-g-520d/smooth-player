package com.example.smoothplayer.engine.impl.lifecycle

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the native media objects the engine holds open: codecs and audio tracks. Each one is counted when
 * it is created and uncounted when it is released. Tests read the counts to prove that open and close cycles
 * leave nothing behind. The counters are a few atomic operations, so they stay in release builds.
 */
internal object LiveResources {
    private val codecs = AtomicInteger(0)
    private val audioTracks = AtomicInteger(0)

    val liveCodecs: Int get() = codecs.get()
    val liveAudioTracks: Int get() = audioTracks.get()

    fun codecOpened() {
        codecs.incrementAndGet()
    }

    fun codecClosed() {
        codecs.decrementAndGet()
    }

    fun audioTrackOpened() {
        audioTracks.incrementAndGet()
    }

    fun audioTrackClosed() {
        audioTracks.decrementAndGet()
    }
}
