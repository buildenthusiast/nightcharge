package bg.solar.nightcharge.data

import bg.solar.nightcharge.core.DayRecord
import bg.solar.nightcharge.core.TouSlot
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/** Deye Cloud Developer API (европейският сървър). */
class DeyeApi(private val store: Store) {

    data class Live(val soc: Int, val pvW: Double, val loadW: Double, val raw: String)

    /** Един инвертор в акаунта. [raw] е описанието му от Deye Cloud, ползва се за модела. */
    data class Inverter(val stationId: Long, val stationName: String, val deviceSn: String, val raw: String)

    /** Какво успяхме да прочетем за инвертора. null = не е открито. */
    data class Specs(val model: String?, val ratedPowerW: Int?, val batteryKwh: Double?)

    private fun token(): String {
        val a = store.auth()
        if (!a.configured) throw IOException("Въведи данните за Deye Cloud в Настройки.")
        if (a.token.isNotBlank() && System.currentTimeMillis() < a.tokenExpiresAt) return a.token

        val loginField = if (a.email.contains("@")) "email" else "username"
        val body = JSONObject()
            .put("appSecret", a.appSecret)
            .put(loginField, a.email)
            .put("password", a.passwordSha256)
        try {
            val r = checked(Http.post("$BASE/account/token?appId=${a.appId}", body))
            val token = r.getString("accessToken")
            val expires = System.currentTimeMillis() + (r.optLong("expiresIn", 86400) - 86400).coerceAtLeast(3600) * 1000
            store.save(a.copy(token = token, tokenExpiresAt = expires))
            return token
        } catch (e: IOException) {
            throw IOException("Вход в Deye Cloud като $loginField „${a.email}“ неуспешен. ${e.message}")
        }
    }

    private fun call(path: String, body: JSONObject): JSONObject =
        checked(Http.post(BASE + path, body, mapOf("Authorization" to "bearer ${token()}")))

    private fun checked(r: JSONObject): JSONObject {
        if (!r.optBoolean("success", false)) {
            throw IOException("Deye Cloud: ${r.optString("message", "грешка")} (${r.optString("code")})")
        }
        return r
    }

    /** Всички инвертори във всички станции на акаунта. */
    fun inverters(): List<Inverter> {
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

    /**
     * Обновява списъка с инвертори. Запазва избрания, ако още е в акаунта,
     * иначе избира първия.
     */
    fun discover(): DeyeAuth {
        val list = inverters()
        if (list.isEmpty()) throw IOException("В този Deye Cloud акаунт няма станция с инвертор.")
        store.saveInverters(list)
        val auth = store.auth()
        val chosen = list.firstOrNull { it.deviceSn == auth.deviceSn } ?: list.first()
        val a = auth.copy(stationId = chosen.stationId, deviceSn = chosen.deviceSn)
        store.save(a)
        return a
    }

    private fun station(): DeyeAuth {
        val it = store.auth()
        return if (it.stationId == 0L || it.deviceSn.isBlank()) discover() else it
    }

    fun live(): Live {
        val r = call("/station/latest", JSONObject().put("stationId", station().stationId))
        return Live(
            soc = Math.round(r.optDouble("batterySOC", 0.0)).toInt(),
            pvW = r.optDouble("generationPower", 0.0),
            loadW = r.optDouble("consumptionPower", 0.0),
            raw = r.toString(),
        )
    }

    /**
     * Суровите отговори на /station/latest и /device/latest за избрания инвертор,
     * за да се види кой ключ какво означава (например UPS и къща). Нищо не се записва.
     */
    fun rawKeys(): String {
        val a = store.auth()
        if (a.stationId == 0L || a.deviceSn.isBlank()) throw IOException("Първо натисни „Запази и тествай връзката“ в Настройки.")
        val station = call("/station/latest", JSONObject().put("stationId", a.stationId))
        val device = call("/device/latest", JSONObject().put("deviceList", JSONArray().put(a.deviceSn)))
        return "/station/latest\n" + station.toString(2) + "\n\n/device/latest\n" + device.toString(2)
    }

    /** Дневно производство и консумация, на парчета по 30 дни. */
    fun history(from: LocalDate, to: LocalDate): Map<String, DayRecord> {
        val id = station().stationId
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
                out[date] = DayRecord(date, row.optDouble("generationValue", 0.0), row.optDouble("consumptionValue", 0.0))
            }
            start = end.plusDays(1)
        }
        return out
    }

    /**
     * Опитва да разбере модела, номиналната мощност и капацитета на батерията.
     * Всяка стъпка е по избор: каквото Deye Cloud не върне, остава null и се въвежда ръчно.
     */
    fun specs(): Specs {
        val sn = station().deviceSn
        val listed = store.inverters().firstOrNull { it.deviceSn == sn }?.raw.orEmpty()

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

    fun setTou(slots: List<TouSlot>): String {
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
        val body = JSONObject()
            .put("deviceSn", station().deviceSn)
            .put("timeUseSettingItems", items)
            .put("timeoutSeconds", 30)
        try {
            return call("/order/sys/tou/update", body).toString()
        } catch (e: IOException) {
            throw IOException(
                "${e.message}\nАко инверторът няма батерия или не е хибриден, той не поддържа Time of Use и приложението не може да го управлява.",
            )
        }
    }

    fun readTou(): String = call("/config/tou", JSONObject().put("deviceSn", station().deviceSn)).toString()

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
