package com.rewatt.app.telemetry

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → Temperature adjustment: a fixed offset (°C) added to the module's temperature before the
 * Energy tab shows it, e.g. -2 when the sensor reads 2° warm from the board's own heat. Display only:
 * the stored and synced readings stay as the module sent them. Kept between app launches.
 */
object TemperatureAdjust {
    const val MIN_OFFSET_C = -10f
    const val MAX_OFFSET_C = 10f
    const val STEP_C = 1f

    private const val PREFS = "rewatt_display_prefs"
    private const val KEY_OFFSET = "temp_offset_c"

    private var prefs: SharedPreferences? = null
    private val offset = MutableStateFlow(0f)
    val offsetC: StateFlow<Float> = offset.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        offset.value = clamp(p.getFloat(KEY_OFFSET, 0f))
    }

    fun set(offsetC: Float) {
        val value = clamp(offsetC)
        offset.value = value
        prefs?.edit()?.putFloat(KEY_OFFSET, value)?.apply()
    }

    fun apply(rawC: Float?, offsetC: Float = offset.value): Float? = rawC?.plus(offsetC)

    private fun clamp(value: Float): Float = value.coerceIn(MIN_OFFSET_C, MAX_OFFSET_C)
}
