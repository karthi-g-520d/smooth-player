package com.example.smoothplayer.engine.impl.playback

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.smoothplayer.engine.api.model.EngineState
import com.example.smoothplayer.engine.impl.lifecycle.LiveResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

private const val CLIP_ARG = "clipUri"
private const val CYCLES = 50
private const val PLAY_MS = 500L
private const val SURFACE_WIDTH = 640
private const val SURFACE_HEIGHT = 360
private const val IMAGE_BUFFERS = 2

/**
 * Device tests for the leak rules: every codec and audio track opened by an engine is closed by [DefaultPlaybackEngine.release].
 *
 * Pass a clip the test process can open, with
 * `-Pandroid.testInstrumentationRunnerArguments.clipUri=<uri>`. Without it the tests are skipped, so a plain
 * connected run stays green. The surface is an [ImageReader], so the decoders have a real output without a view.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackLifecycleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun openPlayReleaseRepeatedlyLeavesNoLiveCodecsOrTracks() {
        val clip = requireClip()
        repeat(CYCLES) { cycle ->
            playOnce(clip)
            assertEquals("live codecs after cycle ${cycle + 1}", 0, LiveResources.liveCodecs)
            assertEquals("live audio tracks after cycle ${cycle + 1}", 0, LiveResources.liveAudioTracks)
        }
    }

    @Test
    fun playingHoldsCodecsOpenUntilRelease() {
        val clip = requireClip()
        val liveWhilePlaying = playOnce(clip)
        assertTrue("expected codecs open while playing, found $liveWhilePlaying", liveWhilePlaying >= 1)
        assertEquals(0, LiveResources.liveCodecs)
        assertEquals(0, LiveResources.liveAudioTracks)
    }

    @Test
    fun releaseAfterOpenWithoutPlayLeavesNothingOpen() {
        val clip = requireClip()
        val engine = DefaultPlaybackEngine(context, Dispatchers.IO)
        runBlocking { engine.open(clip) }
        engine.release()
        assertEquals(0, LiveResources.liveCodecs)
        assertEquals(0, LiveResources.liveAudioTracks)
    }

    /**
     * Opens, plays for [PLAY_MS], then releases. Returns the live codec count sampled while still playing,
     * so the tests can show the counter sees the decoders at all.
     */
    private fun playOnce(clip: String): Int {
        val reader = ImageReader.newInstance(SURFACE_WIDTH, SURFACE_HEIGHT, PixelFormat.RGBA_8888, IMAGE_BUFFERS)
        reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val engine = DefaultPlaybackEngine(context, Dispatchers.IO)
        try {
            engine.onSurfaceChanged(reader.surface, SURFACE_WIDTH, SURFACE_HEIGHT)
            runBlocking { engine.open(clip) }
            val opened = engine.state.value
            assertTrue("clip did not open: $opened", opened is EngineState.Ready)
            engine.play()
            Thread.sleep(PLAY_MS)
            val playing = engine.state.value
            assertTrue("clip is not playing: $playing", playing == EngineState.Playing || playing == EngineState.Ended)
            val live = LiveResources.liveCodecs
            engine.onSurfaceDestroyed()
            return live
        } finally {
            engine.release()
            reader.close()
        }
    }

    private fun requireClip(): String {
        val clip = InstrumentationRegistry.getArguments().getString(CLIP_ARG)
        Assume.assumeTrue("pass -P android.testInstrumentationRunnerArguments.clipUri=<uri> to run", clip != null)
        return checkNotNull(clip)
    }
}
