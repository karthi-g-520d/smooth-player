package com.example.smoothplayer.engine.impl.telemetry

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter

/**
 * Writes one telemetry CSV in the schema the plan fixes: a version line, a header, then one row per frame.
 * Changing the columns means bumping the version line.
 */
internal class TelemetryCsv private constructor(private val writer: BufferedWriter) : Closeable {

    /** Appends one row. Rows come from the decoder thread, so writes are serialised. */
    fun row(line: String) {
        synchronized(this) {
            writer.append(line).append('\n')
        }
    }

    override fun close() {
        synchronized(this) {
            writer.close()
        }
    }

    companion object {
        const val SCHEMA_LINE = "# fi-telemetry v1"
        const val HEADER =
            "frame_idx,src_pts_us,vsync_ns,present_ns,t_phase,mode,me_gpu_us,warp_gpu_us,confidence,scene_cut,thermal_headroom,dropped"

        fun open(file: File): TelemetryCsv {
            val writer = BufferedWriter(FileWriter(file))
            writer.append(SCHEMA_LINE).append('\n')
            writer.append(HEADER).append('\n')
            return TelemetryCsv(writer)
        }
    }
}
