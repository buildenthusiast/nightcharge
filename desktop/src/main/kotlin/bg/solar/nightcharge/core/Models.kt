package bg.solar.nightcharge.core

enum class Erp(val label: String) {
    EVN("EVN / ЕР Юг"),
    ELECTROHOLD("Електрохолд / ЕРМ Запад"),
    ENERGO_PRO("Енерго-Про / ЕРП Север"),
}

/** Посока на панелите. Азимутът е спрямо юг: 0 = юг, -90 = изток, 90 = запад. */
enum class Facing(private val bgLabel: String, private val enLabel: String, val azimuthDeg: Int) {
    EAST("Изток", "East", -90),
    SOUTH("Юг", "South", 0),
    WEST("Запад", "West", 90);

    val label: String get() = pick(bgLabel, enLabel)
}

/** Един масив панели със собствена мощност, наклон и посока. */
data class PvArray(
    val kwp: Double = 8.7,
    val tiltDeg: Int = 17,
    val facing: Facing = Facing.SOUTH,
)

data class Settings(
    /** От 1 до 3 масива; прогнозата се смята за всеки поотделно и се сумира. */
    val arrays: List<PvArray> = listOf(PvArray()),
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
    /** false = питай с известие (Одобри / Откажи); true = записва сам в инвертора. */
    val autoApply: Boolean = false,
) {
    /** Общата мощност на всички панели. */
    val pvKwp: Double get() = arrays.sumOf { it.kwp }

    /** Мощността, която се записва в дневните слотове и е таван за зареждането. */
    val ratedPowerW: Int get() = if (inverterRatedW > 0) inverterRatedW else DEFAULT_RATED_W

    companion object {
        /** Предишното твърдо зададено поведение (SUN-15K), докато мощността не е открита. */
        const val DEFAULT_RATED_W = 15000
        const val MAX_ARRAYS = 3
    }
}

/** Дневните kWh както в Deye Cloud: производство, консумация, покупка от мрежата, зареждане, разреждане и подаване. */
data class DayRecord(
    val date: String,
    val pvKwh: Double,
    val consKwh: Double,
    val purchasedKwh: Double = 0.0,
    val chargeKwh: Double = 0.0,
    val dischargeKwh: Double = 0.0,
    val feedInKwh: Double = 0.0,
)

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
    /** Прогнозата за деня по масиви (kWh, след корекцията), в реда на настройките. */
    val arrayKwh: List<Double> = emptyList(),
    val status: String = STATUS_PENDING,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPLIED = "APPLIED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_ERROR = "ERROR"
    }
}
