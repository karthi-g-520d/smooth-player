package com.example.smoothplayer.engine.impl.capability

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.PowerManager
import android.view.Display
import com.example.smoothplayer.engine.api.model.DecoderInfo
import com.example.smoothplayer.engine.api.model.DeviceInfo
import com.example.smoothplayer.engine.api.model.DisplayModeInfo

private const val MIME_AVC = "video/avc"
private const val MIME_HEVC = "video/hevc"
private const val TEST_WIDTH = 1920
private const val TEST_HEIGHT = 1080
private const val TEST_FPS = 60.0
private const val THERMAL_FORECAST_NOW_SECONDS = 0

internal data class DisplaySnapshot(
    val modes: List<DisplayModeInfo>,
    val activeModeId: Int?,
)

/** Platform queries that need no GL context. Safe to call from any thread. */
internal object DeviceQuery {

    fun deviceInfo(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
    )

    fun displayModes(context: Context): DisplaySnapshot {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?: return DisplaySnapshot(emptyList(), null)
        val modes = display.supportedModes.map {
            DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
        }
        return DisplaySnapshot(modes, display.mode.modeId)
    }

    fun decoders(): List<DecoderInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { !it.isEncoder }
        .flatMap { info ->
            info.supportedTypes
                .filter { it == MIME_AVC || it == MIME_HEVC }
                .map { type -> decoderInfo(info, type) }
        }

    fun thermalHeadroom(context: Context): Double? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val power = context.getSystemService(PowerManager::class.java)
        return power.getThermalHeadroom(THERMAL_FORECAST_NOW_SECONDS).toDouble().takeUnless { it.isNaN() }
    }

    private fun decoderInfo(info: MediaCodecInfo, type: String): DecoderInfo {
        val videoCaps = runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull()
        val supports1080p60 = videoCaps?.let {
            runCatching { it.areSizeAndRateSupported(TEST_WIDTH, TEST_HEIGHT, TEST_FPS) }.getOrDefault(false)
        } ?: false
        return DecoderInfo(
            codecName = info.name,
            mimeType = type,
            hardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else null,
            supports1080p60 = supports1080p60,
        )
    }
}
