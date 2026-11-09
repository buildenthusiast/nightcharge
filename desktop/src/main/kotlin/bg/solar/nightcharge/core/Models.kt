package bg.solar.nightcharge.core

enum class Erp(val label: String) {
    EVN("EVN / ЕР Юг"),
    ELECTROHOLD("Електрохолд / ЕРМ Запад"),
    ENERGO_PRO("Енерго-Про / ЕРП Север"),
}

data class Settings(
    val pvKwp: Double = 8.7,
    val tiltDeg: Int = 17,
    val azimuthDeg: Int = 0,
    val lat: Double = 42.2167,
    val lon: Double = 24.6333,
    val batteryKwh: Double = 25.6,
    val minSoc: Int = 10,
    val maxSoc: Int = 100,
    val chargePowerW: Int = 5000,
    val erp: Erp = Erp.EVN,
    val nightStartWinter: Int = 22,
    val nightEndWinter: Int = 6,
    val nightStartSummer: Int = 23,
    val nightEndSummer: Int = 7,
    val dayPrice: Double = 0.145,
    val nightPrice: Double = 0.085,
    val dayShare: Double = 0.7,
    val reservePct: Int = 10,
    val defaultDailyConsKwh: Double = 20.0,
    val decisionHour: Int = 19,
    val decisionMinute: Int = 0,
    /** Номинална мощност на инвертора; 0 = още не е открита. */
    val inverterRatedW: Int = 0,
) {
    /** Мощността, която се записва в дневните слотове и е таван за зареждането. */
    val ratedPowerW: Int get() = if (inverterRatedW > 0) inverterRatedW else DEFAULT_RATED_W

    companion object {
        /** Предишното твърдо зададено поведение (SUN-15K), докато мощността не е открита. */
        const val DEFAULT_RATED_W = 15000
    }
}

data class DayRecord(val date: String, val pvKwh: Double, val consKwh: Double)

data class Calibration(val longTerm: Double, val shortTerm: Double, val factor: Double, val daysUsed: Int)

data class TouSlot(val time: String, val soc: Int, val gridCharge: Boolean, val powerW: Int)

data class Proposal(
    val forDate: String,
    val createdAt: Long,
    val rawPvKwh: Double,
    val calibration: Double,
    val pvKwh: Double,
    val dayAfterPvKwh: Double?,
    val consKwh: Double,
    val deficitKwh: Double,
    val currentSoc: Int,
    val targetSoc: Int,
    val charge: Boolean,
    val gridKwh: Double,
    val savingEur: Double,
    val nightStart: Int,
    val nightEnd: Int,
    val reason: String,
    val status: String = STATUS_PENDING,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPLIED = "APPLIED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_ERROR = "ERROR"
    }
}
