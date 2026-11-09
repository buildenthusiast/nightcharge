package bg.solar.nightcharge.data

import bg.solar.nightcharge.core.Planner
import bg.solar.nightcharge.core.Settings
import java.util.Locale

object ForecastApi {
    /** Дневно очаквано производство (kWh) по модела за последните 31 дни и следващите 3. */
    fun dailyModelKwh(s: Settings): Map<String, Double> {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&hourly=global_tilted_irradiance&tilt=%d&azimuth=%d&past_days=31&forecast_days=3&timezone=Europe%%2FSofia",
            s.lat, s.lon, s.tiltDeg, s.azimuthDeg,
        )
        val hourly = Http.get(url).getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val gti = hourly.getJSONArray("global_tilted_irradiance")
        val sums = HashMap<String, Double>()
        for (i in 0 until times.length()) {
            if (gti.isNull(i)) continue
            val date = times.getString(i).substring(0, 10)
            sums[date] = (sums[date] ?: 0.0) + gti.getDouble(i) / 1000.0
        }
        return sums.mapValues { Planner.modelKwh(it.value, s) }
    }
}
