package com.rewatt.app.telemetry

import org.json.JSONException
import org.json.JSONObject

object UsbDataParser {
    fun parseTelemetry(
        line: String,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): RadarTelemetry? {
        return try {
            val root = JSONObject(line.trim())
            val radar = root.getJSONObject("radar")

            val state = radar.getInt("state")
            val movD  = radar.optInt("mov_d", 0)
            val movE  = radar.optInt("mov_e", 0)
            val statD = radar.optInt("stat_d", 0)
            val statE = radar.optInt("stat_e", 0)

            val distanceCm = when (state) {
                1, 3 -> movD
                2    -> statD
                else -> 0
            }

            // The firmware sends 0.00 / 0.00 until the SHT3x gives its first reading (and keeps doing so
            // if the sensor never answers), so both exactly 0 means "no reading", not a freezing dry room.
            val rawTemp = root.optDouble("temp", Double.NaN).takeIf { !it.isNaN() }
            val rawHum  = root.optDouble("hum",  Double.NaN).takeIf { !it.isNaN() }
            val climateMissing = rawTemp == 0.0 && rawHum == 0.0
            val temp = rawTemp?.takeUnless { climateMissing }?.toFloat()
            val hum  = rawHum?.takeUnless { climateMissing }?.toFloat()

            RadarTelemetry(
                presence              = state > 0,
                movingEnergy          = movE,
                stationaryEnergy      = statE,
                distanceCm            = distanceCm,
                temperatureC          = temp,
                humidityPercent       = hum,
                capturedAtMillis      = capturedAtMillis,
                rawPacket             = line.trim(),
                radarState            = state,
                movingDistanceCm      = movD,
                stationaryDistanceCm  = statD,
            )
        } catch (_: JSONException) {
            null
        }
    }

    private val plainGasPattern = Regex("""VOC:(-?\d+),NOX:(-?\d+)""")

    /**
     * Optional SGP41 gas reading. Primary format: `"voc"` / `"nox"` fields in the module's JSON line;
     * fallback: plain-text `VOC:102,NOX:1` lines (last match wins, so glued chunks still parse).
     * Returns null when the line carries no gas data — i.e. modules without an SGP41 are unaffected.
     */
    fun parseGas(
        line: String,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): GasReading? {
        val trimmed = line.trim()
        if (trimmed.startsWith("{")) {
            val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            if (!root.has("voc") && !root.has("nox")) return null
            return GasReading(
                vocIndex = root.optInt("voc", -1).toValidIndex(),
                noxIndex = root.optInt("nox", -1).toValidIndex(),
                capturedAtMillis = capturedAtMillis,
            )
        }
        val match = plainGasPattern.findAll(trimmed).lastOrNull() ?: return null
        return GasReading(
            vocIndex = match.groupValues[1].toIntOrNull()?.toValidIndex(),
            noxIndex = match.groupValues[2].toIntOrNull()?.toValidIndex(),
            capturedAtMillis = capturedAtMillis,
        )
    }

    /** Gas indices are 0..500; anything negative (firmware sends -1 on a failed read) means "no value". */
    private fun Int.toValidIndex(): Int? = takeIf { it >= 0 }
}
