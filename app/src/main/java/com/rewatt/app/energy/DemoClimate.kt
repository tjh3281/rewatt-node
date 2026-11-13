package com.rewatt.app.energy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug demo (demo video playing): a presenter-driven room climate shown in the ENERGY tab instead of
 * the module's readings. The temperature starts at [START_C]; a tap on the TEMP box raises it 1 °C, a
 * tap on the HUMIDITY box lowers it 1 °C, and the humidity follows ([humidityFor], 60–70 %, lower when
 * warmer). When it climbs to [AC_ON_AT_C], the aircond is switched ON and a notification says the AC
 * is lowering the room temperature.
 */
class DemoClimate(context: Context, private val lights: LightsController, private val roomName: () -> String) {
    private val notifier = LightsNotifier(context.applicationContext)
    private val mutableTemp = MutableStateFlow<Int?>(null)

    /** The demo temperature (°C), or null when the demo isn't running (real module readings are shown). */
    val tempC: StateFlow<Int?> = mutableTemp.asStateFlow()

    /** Start the demo: 24 °C, aircond OFF (so it visibly switches ON at [AC_ON_AT_C]). */
    fun start() {
        mutableTemp.value = START_C
        lights.setAircond(false)
    }

    /** TEMP box tapped. */
    fun up() = change(+1)

    /** HUMIDITY box tapped. */
    fun down() = change(-1)

    private fun change(delta: Int) {
        val before = mutableTemp.value ?: return
        val after = (before + delta).coerceIn(MIN_C, MAX_C)
        mutableTemp.value = after
        if (before < AC_ON_AT_C && after >= AC_ON_AT_C) {
            lights.setAircond(true)
            notifier.notifyAircondCooling(roomName(), after)
        }
    }

    companion object {
        const val START_C = 24
        const val AC_ON_AT_C = 27
        private const val MIN_C = 16
        private const val MAX_C = 35

        /** Relative humidity for [tempC]: 66 % at 24 °C, 2 % lower per °C warmer, kept within 60–70 %. */
        fun humidityFor(tempC: Int): Int = (66 - 2 * (tempC - START_C)).coerceIn(60, 70)
    }
}
