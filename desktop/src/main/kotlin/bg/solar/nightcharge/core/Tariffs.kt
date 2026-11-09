package bg.solar.nightcharge.core

object Tariffs {
    /** Грубо определяне на ЕРП-то по координати. */
    fun erpFor(lat: Double, lon: Double): Erp {
        val northOfBalkan = lat > 42.75
        return when {
            northOfBalkan && lon >= 25.0 -> Erp.ENERGO_PRO
            !northOfBalkan && lon >= 23.9 -> Erp.EVN
            else -> Erp.ELECTROHOLD
        }
    }
}
