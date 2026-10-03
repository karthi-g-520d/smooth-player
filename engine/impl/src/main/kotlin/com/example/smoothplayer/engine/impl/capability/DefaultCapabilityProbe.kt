package com.example.smoothplayer.engine.impl.capability

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.example.smoothplayer.engine.api.CapabilityProbe
import com.example.smoothplayer.engine.api.model.CapabilityReport
import com.example.smoothplayer.engine.api.model.GlProbeResult
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

private const val GL_THREAD_NAME = "FI-GL-probe"

/**
 * Probes the device once. GL runs on its own thread; display, decoder and thermal queries run on the caller's thread.
 * Blocks until the probe finishes, so call it off the main thread.
 */
public class DefaultCapabilityProbe(private val context: Context) : CapabilityProbe {

    override fun probe(): CapabilityReport {
        val display = DeviceQuery.displayModes(context)
        return CapabilityReport(
            generatedAtMillis = System.currentTimeMillis(),
            device = DeviceQuery.deviceInfo(),
            gl = runGlProbe(),
            displayModes = display.modes,
            activeDisplayModeId = display.activeModeId,
            decoders = DeviceQuery.decoders(),
            thermalHeadroom = DeviceQuery.thermalHeadroom(context),
        )
    }

    private fun runGlProbe(): GlProbeResult {
        val thread = HandlerThread(GL_THREAD_NAME, Process.THREAD_PRIORITY_DISPLAY)
        thread.start()
        return try {
            val task = FutureTask(Callable { GlProbe.run() })
            Handler(thread.looper).post(task)
            task.get()
        } catch (e: ExecutionException) {
            GlProbeResult.Unavailable(e.cause?.message ?: e.toString())
        } finally {
            thread.quitSafely()
        }
    }
}
