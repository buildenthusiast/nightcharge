package bg.solar.nightcharge.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Посоката на енергията по баланса: PV + разреждане + внос от мрежата = консумация + зареждане + загуби.
 * Батерията: положително = разреждане, отрицателно = зареждане. Мрежата: положително = внос (износът е забранен).
 */
object EnergyFlow {
    /** Под този праг (W) стойността се счита за шум и стрелка не се рисува. */
    const val NOISE_W = 10.0

    /** Мрежовият баланс може да се отклонява с 10–25 W заради токовите трансформатори; под това няма стрелка. */
    const val GRID_DEADBAND_W = 25.0

    /** 1 = към инвертора, -1 = от инвертора, 0 = няма поток. */
    data class Directions(val pv: Int, val grid: Int, val battery: Int, val house: Int)

    /** Мрежата от баланса, ако Deye Cloud не я връща: внос = консумация + зареждане − PV − разреждане. */
    fun gridFromBalance(pvW: Double, loadW: Double, batteryW: Double): Double {
        val charge = max(0.0, -batteryW)
        val discharge = max(0.0, batteryW)
        return loadW + charge - pvW - discharge
    }

    fun directions(pvW: Double?, loadW: Double?, batteryW: Double?, gridW: Double?): Directions {
        val pv = pvW ?: 0.0
        val load = loadW ?: 0.0
        val battery = batteryW ?: 0.0
        val grid = gridW ?: gridFromBalance(pv, load, battery)
        return Directions(
            pv = if (pv > NOISE_W) 1 else 0,
            grid = when {
                abs(grid) <= GRID_DEADBAND_W -> 0
                grid > 0 -> 1
                else -> -1
            },
            battery = when {
                battery < -NOISE_W -> -1
                battery > NOISE_W -> 1
                else -> 0
            },
            house = if (load > NOISE_W) -1 else 0,
        )
    }
}
