package com.example.smoothplayer

import android.content.Context
import com.example.smoothplayer.feature.player.capability.CapabilityExporter
import java.io.File

private const val EXPORT_FILE_NAME = "fi-capabilities.json"

/** Writes the report into the app's external files folder, which needs no storage permission. */
class FileCapabilityExporter(private val context: Context) : CapabilityExporter {
    override fun export(json: String): String {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, EXPORT_FILE_NAME).apply { writeText(json) }.absolutePath
    }
}
