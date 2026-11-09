package bg.solar.nightcharge.data

import bg.solar.nightcharge.core.Planner
import bg.solar.nightcharge.core.Settings
import java.util.Locale

/** Очакваното производство по модела: по дни и по часове (24 стойности за деня, kWh). */
data class ModelResult(val daily: Map<String, List<Double>>, val hourly: Map<String, List<Double>>)

object ForecastApi {
    /**
     * Дневно и почасово очаквано производство (kWh) по модела за последните 31 дни и следващите 3.
     * Всеки масив панели се смята със собствения си наклон и посока; резултатът е дата → по един елемент за масив.
     */
    fun dailyModelKwh(s: Settings): ModelResult {
        val perArray = mutableListOf<Map<String, Double>>()
        val hourlyTotal = HashMap<String, DoubleArray>()
        s.arrays.forEach { arr ->
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&hourly=global_tilted_irradiance,temperature_2m,snowfall&tilt=%d&azimuth=%d&past_days=31&forecast_days=3&timezone=Europe%%2FSofia",
                s.lat, s.lon, arr.tiltDeg, arr.facing.azimuthDeg,
            )
            val hourly = Http.get(url).getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val gti = hourly.getJSONArray("global_tilted_irradiance")
            val temp = hourly.optJSONArray("temperature_2m")
            val snow = hourly.optJSONArray("snowfall")
            val sums = HashMap<String, Double>()
            for (i in 0 until times.length()) {
                if (gti.isNull(i)) continue
                val stamp = times.getString(i)
                val date = stamp.substring(0, 10)
                val hour = stamp.substring(11, 13).toInt()
                val g = gti.getDouble(i)
                // Снеговалеж през часа: панелите са покрити, без производство. Температурата се взима, ако я има.
                val covered = snow != null && !snow.isNull(i) && snow.getDouble(i) > 0.0
                val ambient = temp?.takeIf { !it.isNull(i) }?.getDouble(i) ?: 20.0
                val factor = if (covered) 0.0 else Planner.tempFactor(ambient, g)
                val irradiance = g / 1000.0 * factor
                sums[date] = (sums[date] ?: 0.0) + irradiance
                hourlyTotal.getOrPut(date) { DoubleArray(24) }[hour] += arr.kwp * irradiance * Planner.BASE_PR
            }
            perArray.add(sums.mapValues { Planner.modelKwh(it.value, arr.kwp) })
        }
        val dates = perArray.flatMap { it.keys }.toSet()
        val daily = dates.associateWith { d -> perArray.map { it[d] ?: 0.0 } }
        return ModelResult(daily, hourlyTotal.mapValues { it.value.toList() })
    }
}
