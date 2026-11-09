package bg.solar.nightcharge.inverter

import bg.solar.nightcharge.core.DayRecord
import bg.solar.nightcharge.core.TouSlot
import bg.solar.nightcharge.core.pick
import java.time.LocalDate

/** Марките инвертори, които приложението знае. Всяка има собствен облачен акаунт. */
enum class Brand(
    val label: String,
    /** Името на облака, както се показва в съобщенията. */
    val cloudName: String,
    /** Полетата за вход в Настройки, в реда, в който се показват. */
    val form: List<FormField>,
    /** Суфикс за ключовете в SharedPreferences. Deye е без суфикс, за да се запазят старите данни. */
    val storeSuffix: String,
) {
    DEYE(
        "Deye", "Deye Cloud",
        listOf(
            FormField("appId", "AppId", "AppId"),
            FormField("appSecret", "AppSecret", "AppSecret"),
            FormField("email", "Имейл или потребителско име в Deye Cloud", "Email or username in Deye Cloud"),
            FormField("passwordSha256", "Парола", "Password", masked = true, keepIfEmpty = true, hashed = true),
        ),
        "",
    ),
    GROWATT(
        "Growatt", "Growatt Cloud",
        listOf(FormField("apiToken", "API токен от Growatt Cloud", "Growatt Cloud API token", masked = true, keepIfEmpty = true)),
        "_growatt",
    ),
    VICTRON(
        "Victron", "VRM (Victron)",
        listOf(FormField("accessToken", "Access token от VRM", "VRM access token", masked = true, keepIfEmpty = true)),
        "_victron",
    ),
    ;

    companion object {
        fun fromName(name: String?): Brand = entries.firstOrNull { it.name == name } ?: DEYE
    }
}

/**
 * Едно поле от формата за вход.
 * [masked]: показва се скрито. [keepIfEmpty]: празно значи „без промяна“. [hashed]: пази се SHA-256 хеш.
 */
data class FormField(
    val key: String,
    val labelBg: String,
    val labelEn: String,
    val masked: Boolean = false,
    val keepIfEmpty: Boolean = false,
    val hashed: Boolean = false,
) {
    val label: String get() = pick(labelBg, labelEn)
}

/** Възможностите на една марка. UI скрива функциите, които липсват. */
enum class Capability {
    /** Почасова и дневна история: графики „Ден“ и „Месец“, спестяване по нощите. */
    HISTORY,
    /** Избор между няколко инвертора в акаунта. */
    DISCOVER,
    /** Чете и записва часовите слотове за зареждане от мрежата. */
    SCHEDULE,
}

/**
 * Данните за входа на една марка: полетата от [Brand.form] и избраният инвертор.
 * Токенът се пази отделно, защото се взема от облака след входа.
 */
data class Account(
    val fields: Map<String, String> = emptyMap(),
    val stationId: Long = 0,
    val deviceSn: String = "",
    val token: String = "",
    val tokenExpiresAt: Long = 0,
) {
    operator fun get(key: String): String = fields[key].orEmpty()

    fun configured(brand: Brand): Boolean = brand.form.all { this[it.key].isNotBlank() }
}

/** Текущи показания. gridW и batteryW са null, ако облакът не ги е върнал. */
data class Live(
    val soc: Int,
    val pvW: Double,
    val loadW: Double,
    val gridW: Double?,
    val batteryW: Double?,
    val raw: String,
)

/** Един инвертор в акаунта. [raw] е описанието му от облака. */
data class Inverter(val stationId: Long, val stationName: String, val deviceSn: String, val raw: String)

/** Какво успяхме да прочетем за инвертора. null = не е открито. */
data class Specs(val model: String?, val ratedPowerW: Int?, val batteryKwh: Double?)

/** Почасови стойности за един ден в kWh (W, осреднени за часа). */
data class HourPoint(
    val hour: Int,
    val production: Double,
    val consumption: Double,
    val purchased: Double,
    val discharge: Double,
    val charge: Double,
    /** Зарядът на батерията в проценти, ако марката го връща. */
    val soc: Double? = null,
)

/**
 * Часовите слотове, както ги е прочел инвертора. [raw] е точното копие от облака,
 * за да може да се върне байт по байт при „Върни запазените настройки“.
 */
data class Schedule(val slots: List<TouSlot>, val raw: String)

/**
 * Общият интерфейс за облака на една марка. Методите, които са извън [capabilities],
 * не се викат; ако все пак се извикат, хвърлят грешка.
 */
interface InverterProvider {
    val brand: Brand
    val capabilities: Set<Capability>

    /** Всички инвертори в акаунта. Празен списък, ако няма такива. */
    fun inverters(): List<Inverter>

    /** Текущите показания за избрания инвертор. */
    fun live(): Live

    /** Опитва да прочете модела, мощността и капацитета. Каквото липсва, остава null. */
    fun specs(): Specs

    /** Активните аварии на инсталацията, като текст. Празен списък, ако марката не ги показва. */
    fun activeAlarms(): List<String> = emptyList()

    /** [Capability.HISTORY] */
    fun hourly(day: LocalDate): List<HourPoint> = unsupported(Capability.HISTORY)

    /** [Capability.HISTORY] */
    fun history(from: LocalDate, to: LocalDate): Map<String, DayRecord> = unsupported(Capability.HISTORY)

    /** [Capability.SCHEDULE] */
    fun readSchedule(): Schedule = unsupported(Capability.SCHEDULE)

    /** [Capability.SCHEDULE] Записва точно суровото копие, както е прочетено. */
    fun writeSchedule(raw: String): String = unsupported(Capability.SCHEDULE)

    /** [Capability.SCHEDULE] Записва нови слотове. */
    fun applySchedule(slots: List<TouSlot>): String = unsupported(Capability.SCHEDULE)

    private fun unsupported(c: Capability): Nothing =
        throw UnsupportedOperationException("${brand.label}: ${c.name} is not supported")
}
