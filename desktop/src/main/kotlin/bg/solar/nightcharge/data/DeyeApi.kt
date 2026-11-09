package bg.solar.nightcharge.data

import bg.solar.nightcharge.core.DayRecord
import bg.solar.nightcharge.core.Planner
import bg.solar.nightcharge.core.TouSlot
import bg.solar.nightcharge.core.pick
import bg.solar.nightcharge.inverter.Account
import bg.solar.nightcharge.inverter.Brand
import bg.solar.nightcharge.inverter.Capability
import bg.solar.nightcharge.inverter.HourPoint
import bg.solar.nightcharge.inverter.Inverter
import bg.solar.nightcharge.inverter.InverterProvider
import bg.solar.nightcharge.inverter.Live
import bg.solar.nightcharge.inverter.Schedule
import bg.solar.nightcharge.inverter.Specs
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/** Deye Cloud Developer API (европейският сървър). */
class DeyeApi(private val store: Store) : InverterProvider {

    override val brand = Brand.DEYE
    override val capabilities = setOf(Capability.HISTORY, Capability.DISCOVER, Capability.SCHEDULE)

    private fun account(): Account = store.account(brand)

    private fun token(): String {
        val a = account()
        if (!a.configured(brand)) throw IOException(pick("Въведи данните за Deye Cloud в Настройки.", "Enter your Deye Cloud details in Settings."))
        if (a.token.isNotBlank() && System.currentTimeMillis() < a.tokenExpiresAt) return a.token

        val email = a["email"]
        val loginField = if (email.contains("@")) "email" else "username"
        val body = JSONObject()
            .put("appSecret", a["appSecret"])
            .put(loginField, email)
            .put("password", a["passwordSha256"])
        try {
            val r = checked(Http.post("$BASE/account/token?appId=${a["appId"]}", body))
            val token = r.getString("accessToken")
            val expires = System.currentTimeMillis() + (r.optLong("expiresIn", 86400) - 86400).coerceAtLeast(3600) * 1000
            store.saveAccount(brand, a.copy(token = token, tokenExpiresAt = expires))
            return token
        } catch (e: IOException) {
            throw IOException(pick("Вход в Deye Cloud като $loginField „$email“ неуспешен. ", "Deye Cloud sign-in as $loginField “$email” failed. ") + (e.message ?: ""))
        }
    }

    private fun call(path: String, body: JSONObject): JSONObject =
        checked(Http.post(BASE + path, body, mapOf("Authorization" to "bearer ${token()}")))

    private fun checked(r: JSONObject): JSONObject {
        if (!r.optBoolean("success", false)) {
            throw IOException("Deye Cloud: ${r.optString("message", pick("грешка", "error"))} (${r.optString("code")})")
        }
        return r
    }

    /** Всички инвертори във всички станции на акаунта. */
    override fun inverters(): List<Inverter> {
        val out = mutableListOf<Inverter>()
        var page = 1
        while (page <= 20) {
            val r = call("/station/listWithDevice", JSONObject().put("page", page).put("size", PAGE_SIZE).put("deviceType", "INVERTER"))
            val stations = r.optJSONArray("stationList") ?: JSONArray()
            for (i in 0 until stations.length()) {
                val st = stations.getJSONObject(i)
                val devices = st.optJSONArray("deviceListItems") ?: JSONArray()
                for (k in 0 until devices.length()) {
                    val dev = devices.getJSONObject(k)
                    val type = dev.optString("deviceType")
                    if (type.isNotEmpty() && type != "INVERTER") continue
                    val sn = dev.optString("deviceSn")
                    if (sn.isBlank()) continue
                    out.add(Inverter(st.getLong("id"), st.optString("name"), sn, dev.toString()))
                }
            }
            if (stations.length() < PAGE_SIZE) break
            page++
        }
        return out
    }

    override fun live(): Live {
        val r = call("/station/latest", JSONObject().put("stationId", account().stationId))
        return Live(
            soc = Math.round(r.optDouble("batterySOC", 0.0)).toInt(),
            pvW = r.optDouble("generationPower", 0.0),
            loadW = r.optDouble("consumptionPower", 0.0),
            // gridPower е null при SUN-15K; мрежата се взима от purchasePower, а при липсата му от wirePower.
            gridW = r.number("gridPower") ?: r.number("purchasePower") ?: r.number("wirePower"),
            batteryW = r.number("batteryPower"),
            raw = r.toString(),
        )
    }

    private fun JSONObject.number(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    override fun hourly(day: LocalDate): List<HourPoint> {
        val body = JSONObject()
            .put("stationId", account().stationId)
            .put("granularity", 1)
            .put("startAt", day.toString())
            .put("endAt", day.plusDays(1).toString())
        val items = call("/station/history", body).optJSONArray("stationDataItems") ?: JSONArray()
        // Записите може да идват през няколко минути: за всеки час се взима средната мощност, така че сборът да е kWh.
        val rows = (0 until items.length()).map { items.getJSONObject(it) }
        return rows.groupBy { Instant.ofEpochSecond(it.optLong("timeStamp")).atZone(Planner.ZONE).hour }
            .map { (hour, list) ->
                HourPoint(
                    hour = hour,
                    production = list.map { kw(it, "generationPower") }.average(),
                    consumption = list.map { kw(it, "consumptionPower") }.average(),
                    purchased = list.map { ((it.number("purchasePower") ?: it.number("wirePower") ?: 0.0) / 1000).coerceAtLeast(0.0) }.average(),
                    discharge = list.map { kw(it, "dischargePower") }.average(),
                    charge = list.map { kw(it, "chargePower") }.average(),
                    soc = list.mapNotNull { it.number("batterySOC") }.takeIf { it.isNotEmpty() }?.average(),
                )
            }
            .sortedBy { it.hour }
    }

    private fun kw(row: JSONObject, key: String): Double = (row.number(key) ?: 0.0) / 1000

    /** Дневно производство и консумация, на парчета по 30 дни. */
    override fun history(from: LocalDate, to: LocalDate): Map<String, DayRecord> {
        val id = account().stationId
        val out = HashMap<String, DayRecord>()
        var start = from
        while (!start.isAfter(to)) {
            val end = minOf(start.plusDays(29), to)
            val body = JSONObject()
                .put("stationId", id)
                .put("granularity", 2)
                .put("startAt", start.toString())
                .put("endAt", end.plusDays(1).toString())
            val items = call("/station/history", body).optJSONArray("stationDataItems") ?: JSONArray()
            for (i in 0 until items.length()) {
                val row = items.getJSONObject(i)
                if (row.isNull("generationValue") && row.isNull("consumptionValue")) continue
                val date = String.format(Locale.US, "%04d-%02d-%02d", row.optInt("year"), row.optInt("month"), row.optInt("day"))
                out[date] = DayRecord(
                    date,
                    row.number("generationValue") ?: 0.0,
                    row.number("consumptionValue") ?: 0.0,
                    purchasedKwh = row.number("purchaseValue") ?: 0.0,
                    chargeKwh = row.number("chargeValue") ?: 0.0,
                    dischargeKwh = row.number("dischargeValue") ?: 0.0,
                    feedInKwh = row.number("gridValue") ?: 0.0,
                )
            }
            start = end.plusDays(1)
        }
        return out
    }

    /**
     * Опитва да разбере модела, номиналната мощност и капацитета на батерията.
     * Всяка стъпка е по избор: каквото Deye Cloud не върне, остава null и се въвежда ръчно.
     */
    override fun specs(): Specs {
        val sn = account().deviceSn
        val listed = store.inverters(brand).firstOrNull { it.deviceSn == sn }?.raw.orEmpty()

        val data = runCatching { latestData(sn) }.getOrDefault(emptyList())
        val texts = listOf(listed) + data.map { it.value }
        val model = texts.firstNotNullOfOrNull { MODEL.find(it)?.value }

        val ratedFromData = data.firstOrNull { RATED_KEY.matches(it.key) }?.let { toWatts(it.value, it.unit) }
        val ratedFromModel = model?.let { MODEL.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }?.let { (it * 1000).toInt() }
        val rated = (ratedFromData ?: ratedFromModel)?.takeIf { it in 1000..100000 }

        val batteryKwh = runCatching {
            val cfg = call("/config/battery", JSONObject().put("deviceSn", sn))
            val ah = listOf("battCapacity", "batteryCapacity").map { cfg.optDouble(it, Double.NaN) }.firstOrNull { !it.isNaN() && it > 0 }
                ?: return@runCatching null
            val volts = data.firstOrNull { BATT_VOLT_KEY.matches(it.key) }?.value?.toDoubleOrNull()?.takeIf { it > 10 }
                ?: LV_VOLTAGE
            ah * volts / 1000.0
        }.getOrNull()?.takeIf { it in 1.0..500.0 }

        return Specs(model, rated, batteryKwh)
    }

    private data class DataPoint(val key: String, val value: String, val unit: String)

    private fun latestData(sn: String): List<DataPoint> {
        val r = call("/device/latest", JSONObject().put("deviceList", JSONArray().put(sn)))
        val devices = r.optJSONArray("deviceDataList") ?: return emptyList()
        if (devices.length() == 0) return emptyList()
        val list = devices.getJSONObject(0).optJSONArray("dataList") ?: return emptyList()
        return (0 until list.length()).map {
            val o = list.getJSONObject(it)
            DataPoint(o.optString("key"), o.optString("value"), o.optString("unit"))
        }
    }

    private fun toWatts(value: String, unit: String): Int? {
        val v = value.toDoubleOrNull() ?: return null
        return if (unit.equals("kW", ignoreCase = true)) (v * 1000).toInt() else v.toInt()
    }

    /** Записва слотовете за зареждане (Time of Use). */
    override fun applySchedule(slots: List<TouSlot>): String {
        val items = JSONArray()
        slots.forEach {
            items.put(
                JSONObject()
                    .put("enableGeneration", false)
                    .put("enableGridCharge", it.gridCharge)
                    .put("power", it.powerW)
                    .put("soc", it.soc)
                    .put("time", it.time),
            )
        }
        try {
            return writeTouItems(items)
        } catch (e: IOException) {
            throw IOException(
                "${e.message}\n" + pick(
                    "Ако инверторът няма батерия или не е хибриден, той не поддържа Time of Use и приложението не може да го управлява.",
                    "If the inverter has no battery or is not hybrid, it does not support Time of Use and the app cannot control it.",
                ),
            )
        }
    }

    /** Часовите слотове от инвертора, както ги връща Deye Cloud (timeUseSettingItems). */
    override fun readSchedule(): Schedule {
        val r = call("/config/tou", JSONObject().put("deviceSn", account().deviceSn))
        val items = findArray(r, "timeUseSettingItems")
            ?: throw IOException(pick("Не намерих часовите слотове в отговора на Deye Cloud.", "The time-of-use slots were not found in the Deye Cloud response."))
        val slots = (0 until items.length()).map {
            val o = items.getJSONObject(it)
            TouSlot(o.optString("time"), o.optInt("soc"), o.optBoolean("enableGridCharge"), o.optInt("power"))
        }
        return Schedule(slots, items.toString())
    }

    /** Записва точно същите слотове, които са били прочетени преди това (за връщане назад). */
    override fun writeSchedule(raw: String): String = writeTouItems(JSONArray(raw))

    private fun writeTouItems(items: JSONArray): String {
        val body = JSONObject()
            .put("deviceSn", account().deviceSn)
            .put("timeUseSettingItems", items)
            .put("timeoutSeconds", 30)
        return call("/order/sys/tou/update", body).toString()
    }

    private fun findArray(node: Any?, key: String): JSONArray? = when (node) {
        is JSONObject -> node.optJSONArray(key) ?: node.keys().asSequence().firstNotNullOfOrNull { findArray(node.get(it), key) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findArray(node.get(it), key) }
        else -> null
    }

    companion object {
        const val BASE = "https://eu1-developer.deyecloud.com/v1.0"
        private const val PAGE_SIZE = 20
        /** Ниско волтова батерия (48 V клас), ако напрежението не се прочете. */
        private const val LV_VOLTAGE = 51.2
        private val MODEL = Regex("""SUN-(\d+(?:\.\d+)?)K-[A-Z0-9-]+""", RegexOption.IGNORE_CASE)
        private val RATED_KEY = Regex("""(?i).*rated.?power.*""")
        private val BATT_VOLT_KEY = Regex("""(?i)batt(ery)?.?voltage""")

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
