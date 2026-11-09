package bg.solar.nightcharge.core

/**
 * Спестяването за един ден, по оценка.
 * Всичко, купено от мрежата през нощта за батерията, е разход, дори ако не е разредено.
 * Спестена покупка е само част от разреждането през деня, която идва от мрежата, а не от панелите.
 * Затова стойността е най-много колкото е зареждано от мрежата и никога не е по-висока от старата формула.
 */
object Savings {
    /**
     * [nightGridCharge]: купен от мрежата през нощта за батерията, kWh.
     * [dayDischarge]: разреден от батерията през деня, kWh.
     */
    fun perDay(nightGridCharge: Double, dayDischarge: Double, dayPrice: Double, nightPrice: Double): Double {
        val charge = nightGridCharge.coerceAtLeast(0.0)
        val fromGrid = minOf(charge, dayDischarge.coerceAtLeast(0.0))
        return fromGrid * dayPrice - charge * nightPrice
    }
}
