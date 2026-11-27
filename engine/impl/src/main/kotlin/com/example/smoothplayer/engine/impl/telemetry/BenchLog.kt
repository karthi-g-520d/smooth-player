package com.example.smoothplayer.engine.impl.telemetry

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

private const val FOLDER = "SmoothPlayer"
private const val MIME_CSV = "text/csv"

/**
 * One benchmark CSV: a version line, `# key=value` lines about the run, a header, then one row per second.
 * Changing the columns means bumping [SCHEMA_LINE], and tools/bench/summarize.py with it.
 *
 * Written to Download/SmoothPlayer on Android 10 and later, so the user can share it from the Files app without a PC.
 * Older versions use the app's external files folder.
 */
internal class BenchLog private constructor(
    private val writer: BufferedWriter,
    val displayPath: String,
) : Closeable {

    /** Appends one row. Callers serialise. */
    fun row(line: String) {
        writer.append(line).append('\n')
        // A run can end by the app being killed, so each row is flushed. One small write a second.
        writer.flush()
    }

    override fun close() {
        writer.close()
    }

    companion object {
        const val SCHEMA_LINE = "# fi-bench v1"
        val COLUMNS = listOf(
            "elapsed_s", "mode", "probe", "display_fps", "content_fps", "runs", "exact_runs", "dropped_total",
            "av_drift_ms", "draw_cpu_p95_ms", "copy_ms", "me_ms", "warp_ms", "thermal_headroom", "thermal_status",
            "battery_pct", "battery_ma", "battery_temp_c", "charging", "brightness", "fallback", "inb_warped",
            "inb_pending", "inb_not_searched", "inb_low_conf", "inb_held", "scene_cuts_total",
        )

        /** Opens a new file named after [startMillis] and writes the version line, [meta] and the header. */
        fun open(context: Context, startMillis: Long, meta: List<Pair<String, String>>): BenchLog {
            val name = "fi-bench-$startMillis.csv"
            val log = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openDownload(context, name) else openPrivate(context, name)
            log.writer.append(SCHEMA_LINE).append('\n')
            for ((key, value) in meta) log.writer.append("# ").append(key).append('=').append(value.replace('\n', ' ')).append('\n')
            log.writer.append(COLUMNS.joinToString(",")).append('\n')
            log.writer.flush()
            return log
        }

        @TargetApi(Build.VERSION_CODES.Q)
        private fun openDownload(context: Context, name: String): BenchLog {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_CSV)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val uri: Uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
                "could not create $name in Downloads"
            }
            val stream: OutputStream = checkNotNull(resolver.openOutputStream(uri)) { "could not open $uri" }
            return BenchLog(stream.bufferedWriter(), "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name")
        }

        private fun openPrivate(context: Context, name: String): BenchLog {
            val directory = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(directory, name)
            return BenchLog(BufferedWriter(OutputStreamWriter(FileOutputStream(file))), file.path)
        }
    }
}
