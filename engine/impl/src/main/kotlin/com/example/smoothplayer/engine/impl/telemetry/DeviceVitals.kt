package com.example.smoothplayer.engine.impl.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

private const val MICROAMPS_PER_MILLIAMP = 1_000
private const val TENTHS_PER_DEGREE = 10f
private const val UNKNOWN = -1

/** Battery, heat and screen readings for one benchmark row. Null fields are ones the device does not report. */
internal data class Vitals(
    val batteryPercent: Int?,
    val batteryCurrentMa: Int?,
    val batteryTempC: Float?,
    val charging: Boolean?,
    val thermalStatus: Int?,
    val brightness: Int?,
)

/** Reads [Vitals] from the platform. Cheap enough for once a second; never call it per frame. */
internal class DeviceVitals(private val context: Context) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    fun read(): Vitals {
        // A null receiver only reads the sticky battery broadcast; nothing is registered.
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, UNKNOWN) ?: UNKNOWN
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, UNKNOWN) ?: UNKNOWN
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentUa = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return Vitals(
            batteryPercent = percent?.takeIf { it in 0..100 },
            // Integer.MIN_VALUE means the property is not supported.
            batteryCurrentMa = currentUa?.takeIf { it != Int.MIN_VALUE }?.let { it / MICROAMPS_PER_MILLIAMP },
            batteryTempC = tempTenths.takeIf { it != UNKNOWN }?.let { it / TENTHS_PER_DEGREE },
            charging = plugged.takeIf { it != UNKNOWN }?.let { it != 0 },
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus else null,
            brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, UNKNOWN)
                .takeIf { it != UNKNOWN },
        )
    }
}
