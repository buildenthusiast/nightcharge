package bg.solar.nightcharge.data

import bg.solar.nightcharge.core.DayRecord
import bg.solar.nightcharge.core.Erp
import bg.solar.nightcharge.core.Facing
import bg.solar.nightcharge.core.Proposal
import bg.solar.nightcharge.core.PvArray
import bg.solar.nightcharge.core.Settings
import bg.solar.nightcharge.inverter.Account
import bg.solar.nightcharge.inverter.Brand
import bg.solar.nightcharge.inverter.Inverter
import org.json.JSONArray
import org.json.JSONObject

/** Всичко се пази в SharedPreferences като JSON. */
class Store(file: java.io.File = java.io.File(System.getProperty("user.home"), ".nightcharge/store.json")) {
    private val prefs = FilePrefs(file)

    fun settings(): Settings {
        val j = json("settings") ?: return Settings()
        val d = Settings()
        return Settings(
            arrays = arraysFrom(j, d),
            lat = j.optDouble("lat", d.lat),
            lon = j.optDouble("lon", d.lon),
            batteryKwh = j.optDouble("batteryKwh", d.batteryKwh),
            minSoc = j.optInt("minSoc", d.minSoc),
            maxSoc = j.optInt("maxSoc", d.maxSoc),
            chargePowerW = j.optInt("chargePowerW", d.chargePowerW),
            erp = runCatching { Erp.valueOf(j.optString("erp")) }.getOrDefault(d.erp),
            nightStartWinter = j.optInt("nightStartWinter", d.nightStartWinter),
            nightEndWinter = j.optInt("nightEndWinter", d.nightEndWinter),
            nightStartSummer = j.optInt("nightStartSummer", d.nightStartSummer),
            nightEndSummer = j.optInt("nightEndSummer", d.nightEndSummer),
            dayPrice = j.optDouble("dayPrice", d.dayPrice),
            nightPrice = j.optDouble("nightPrice", d.nightPrice),
            dayShare = j.optDouble("dayShare", d.dayShare),
            reservePct = j.optInt("reservePct", d.reservePct),
            defaultDailyConsKwh = j.optDouble("defaultDailyConsKwh", d.defaultDailyConsKwh),
            decisionHour = j.optInt("decisionHour", d.decisionHour),
            decisionMinute = j.optInt("decisionMinute", d.decisionMinute),
            inverterRatedW = j.optInt("inverterRatedW", d.inverterRatedW),
            autoApply = j.optBoolean("autoApply", d.autoApply),
        )
    }

    /** Масивите панели. Стари настройки с един масив (pvKwp, tiltDeg, azimuthDeg) се превръщат в един масив. */
    private fun arraysFrom(j: JSONObject, d: Settings): List<PvArray> {
        j.optJSONArray("arrays")?.let { a ->
            return (0 until a.length()).take(Settings.MAX_ARRAYS).map {
                val o = a.getJSONObject(it)
                PvArray(
                    kwp = o.optDouble("kwp", 1.0),
                    tiltDeg = o.optInt("tiltDeg", 17),
                    facing = runCatching { Facing.valueOf(o.optString("facing")) }.getOrDefault(Facing.SOUTH),
                )
            }
        }
        if (!j.has("pvKwp")) return d.arrays
        val az = j.optInt("azimuthDeg", 0)
        val facing = when {
            az <= -45 -> Facing.EAST
            az >= 45 -> Facing.WEST
            else -> Facing.SOUTH
        }
        return listOf(PvArray(j.optDouble("pvKwp", 8.7), j.optInt("tiltDeg", 17), facing))
    }

    fun save(s: Settings) {
        val arrays = JSONArray()
        s.arrays.forEach {
            arrays.put(JSONObject().put("kwp", it.kwp).put("tiltDeg", it.tiltDeg).put("facing", it.facing.name))
        }
        put(
            "settings",
            JSONObject()
                .put("arrays", arrays)
                .put("lat", s.lat)
                .put("lon", s.lon)
                .put("batteryKwh", s.batteryKwh)
                .put("minSoc", s.minSoc)
                .put("maxSoc", s.maxSoc)
                .put("chargePowerW", s.chargePowerW)
                .put("erp", s.erp.name)
                .put("nightStartWinter", s.nightStartWinter)
                .put("nightEndWinter", s.nightEndWinter)
                .put("nightStartSummer", s.nightStartSummer)
                .put("nightEndSummer", s.nightEndSummer)
                .put("dayPrice", s.dayPrice)
                .put("nightPrice", s.nightPrice)
                .put("dayShare", s.dayShare)
                .put("reservePct", s.reservePct)
                .put("defaultDailyConsKwh", s.defaultDailyConsKwh)
                .put("decisionHour", s.decisionHour)
                .put("decisionMinute", s.decisionMinute)
                .put("inverterRatedW", s.inverterRatedW)
                .put("autoApply", s.autoApply),
        )
    }

    /** Марката, избрана в Настройки. Без избор е Deye, както при досегашните инсталации. */
    fun brand(): Brand = Brand.fromName(prefs.getString("brand", null))

    fun setBrand(b: Brand) = prefs.edit().putString("brand", b.name).apply()

    /**
     * Данните за входа на марката. Полетата са на най-горното ниво на JSON, както при старата Deye версия,
     * така че съществуващият акаунт се чете без промяна.
     */
    fun account(brand: Brand): Account {
        val j = json("auth" + brand.storeSuffix) ?: return Account()
        return Account(
            fields = brand.form.associate { it.key to j.optString(it.key) },
            stationId = j.optLong("stationId"),
            deviceSn = j.optString("deviceSn"),
            token = j.optString("token"),
            tokenExpiresAt = j.optLong("tokenExpiresAt"),
        )
    }

    fun saveAccount(brand: Brand, a: Account) {
        val j = JSONObject()
        brand.form.forEach { j.put(it.key, a[it.key]) }
        j.put("stationId", a.stationId)
            .put("deviceSn", a.deviceSn)
            .put("token", a.token)
            .put("tokenExpiresAt", a.tokenExpiresAt)
        put("auth" + brand.storeSuffix, j)
    }

    /** Инверторите в акаунта на марката от последната проверка на връзката. */
    fun inverters(brand: Brand): List<Inverter> {
        val a = prefs.getString("inverters" + brand.storeSuffix, null)?.let { JSONArray(it) } ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Inverter(o.getLong("stationId"), o.optString("stationName"), o.getString("deviceSn"), o.optString("raw"))
        }
    }

    fun saveInverters(brand: Brand, list: List<Inverter>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("stationId", it.stationId).put("stationName", it.stationName).put("deviceSn", it.deviceSn).put("raw", it.raw))
        }
        prefs.edit().putString("inverters" + brand.storeSuffix, a.toString()).apply()
    }

    fun history(): Map<String, DayRecord> {
        val j = json("history") ?: return emptyMap()
        val out = HashMap<String, DayRecord>()
        for (k in j.keys()) {
            val r = j.getJSONArray(k)
            // Формат: [производство, консумация, покупка, зареждане, разреждане, подаване]. Старите записи имат само първите две.
            fun at(i: Int) = if (r.length() > i) r.getDouble(i) else 0.0
            out[k] = DayRecord(k, at(0), at(1), at(2), at(3), at(4), at(5))
        }
        return out
    }

    fun saveHistory(h: Map<String, DayRecord>) {
        val j = JSONObject()
        h.values.forEach {
            j.put(
                it.date,
                JSONArray().put(it.pvKwh).put(it.consKwh).put(it.purchasedKwh).put(it.chargeKwh).put(it.dischargeKwh).put(it.feedInKwh),
            )
        }
        put("history", j)
    }

    /** Историята е взета с всички серии (покупка, зареждане, разреждане, подаване). */
    fun historyComplete(): Boolean = prefs.getBoolean("history_v2", false)

    fun markHistoryComplete() = prefs.edit().putBoolean("history_v2", true).apply()

    fun model(): Map<String, Double> {
        val j = json("model") ?: return emptyMap()
        return j.keys().asSequence().associateWith { j.getDouble(it) }
    }

    fun saveModel(m: Map<String, Double>) = put("model", JSONObject(m))

    /** Почасовото очаквано производство по дни: 24 стойности, kWh. */
    fun hourlyModel(): Map<String, List<Double>> {
        val j = json("model_hourly") ?: return emptyMap()
        return j.keys().asSequence().associateWith { k ->
            val a = j.getJSONArray(k)
            (0 until a.length()).map { a.getDouble(it) }
        }
    }

    fun saveHourlyModel(m: Map<String, List<Double>>) {
        val j = JSONObject()
        m.forEach { (k, v) -> j.put(k, JSONArray(v)) }
        put("model_hourly", j)
    }

    fun pending(): Proposal? = json("pending")?.let { proposalFrom(it) }

    fun savePending(p: Proposal) = put("pending", toJson(p))

    fun log(): List<Proposal> {
        val a = prefs.getString("log", null)?.let { JSONArray(it) } ?: return emptyList()
        return (0 until a.length()).map { proposalFrom(a.getJSONObject(it)) }
    }

    fun logProposal(p: Proposal) {
        val list = listOf(p) + log().filter { it.forDate != p.forDate }
        val a = JSONArray()
        list.take(60).forEach { a.put(toJson(it)) }
        prefs.edit().putString("log", a.toString()).apply()
    }

    /** Резервно копие на настройките на инвертора за зареждане: суровият текст, както е прочетен от облака. */
    fun saveTouBackup(raw: String) {
        put("tou_backup", JSONObject().put("items", raw).put("savedAt", System.currentTimeMillis()))
    }

    fun touBackup(): Pair<String, Long>? {
        val j = json("tou_backup") ?: return null
        return j.getString("items") to j.getLong("savedAt")
    }

    /** По дни: [покупка през нощта kWh, разреждане през деня kWh]. Използва се за спестяването. */
    fun nightStats(): Map<String, Pair<Double, Double>> {
        val j = json("night_stats_v2") ?: return emptyMap()
        return j.keys().asSequence().associateWith { k ->
            val a = j.getJSONArray(k)
            a.getDouble(0) to a.getDouble(1)
        }
    }

    fun saveNightStats(m: Map<String, Pair<Double, Double>>) {
        val j = JSONObject()
        m.forEach { (k, v) -> j.put(k, JSONArray().put(v.first).put(v.second)) }
        put("night_stats_v2", j)
    }

    /** Последните показания за уиджета: SOC, PV мощност и време. */
    fun saveLastLive(soc: Int, pvW: Double) {
        put("last_live", JSONObject().put("soc", soc).put("pvW", pvW).put("at", System.currentTimeMillis()))
    }

    fun lastLive(): Triple<Int, Double, Long>? {
        val j = json("last_live") ?: return null
        return Triple(j.getInt("soc"), j.getDouble("pvW"), j.getLong("at"))
    }

    /** Настройките на инвертора преди последния запис. Позволяват да се върнат назад. */
    fun saveUndo(raw: String) {
        put("undo_tou", JSONObject().put("items", raw).put("savedAt", System.currentTimeMillis()))
    }

    fun undo(): String? {
        val j = json("undo_tou") ?: return null
        return j.getString("items")
    }

    fun clearUndo() = prefs.edit().remove("undo_tou").apply()

    /** Избор на потребителя: език и тема. */
    fun appPref(key: String, default: String): String = prefs.getString("app_$key", default) ?: default

    fun setAppPref(key: String, value: String) = prefs.edit().putString("app_$key", value).apply()

    private fun json(key: String): JSONObject? = prefs.getString(key, null)?.let { JSONObject(it) }

    private fun put(key: String, j: JSONObject) {
        prefs.edit().putString(key, j.toString()).apply()
    }

    private fun toJson(p: Proposal): JSONObject = JSONObject()
        .put("forDate", p.forDate)
        .put("createdAt", p.createdAt)
        .put("rawPvKwh", p.rawPvKwh)
        .put("calibration", p.calibration)
        .put("pvKwh", p.pvKwh)
        .put("dayAfterPvKwh", p.dayAfterPvKwh ?: JSONObject.NULL)
        .put("consKwh", p.consKwh)
        .put("deficitKwh", p.deficitKwh)
        .put("currentSoc", p.currentSoc)
        .put("targetSoc", p.targetSoc)
        .put("charge", p.charge)
        .put("gridKwh", p.gridKwh)
        .put("savingEur", p.savingEur)
        .put("nightStart", p.nightStart)
        .put("nightEnd", p.nightEnd)
        .put("reason", p.reason)
        .put("arrayKwh", JSONArray(p.arrayKwh))
        .put("status", p.status)

    private fun proposalFrom(j: JSONObject) = Proposal(
        forDate = j.getString("forDate"),
        createdAt = j.getLong("createdAt"),
        rawPvKwh = j.getDouble("rawPvKwh"),
        calibration = j.getDouble("calibration"),
        pvKwh = j.getDouble("pvKwh"),
        dayAfterPvKwh = if (j.isNull("dayAfterPvKwh")) null else j.getDouble("dayAfterPvKwh"),
        consKwh = j.getDouble("consKwh"),
        deficitKwh = j.getDouble("deficitKwh"),
        currentSoc = j.getInt("currentSoc"),
        targetSoc = j.getInt("targetSoc"),
        charge = j.getBoolean("charge"),
        gridKwh = j.getDouble("gridKwh"),
        savingEur = j.getDouble("savingEur"),
        nightStart = j.getInt("nightStart"),
        nightEnd = j.getInt("nightEnd"),
        reason = j.getString("reason"),
        arrayKwh = j.optJSONArray("arrayKwh")?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList(),
        status = j.getString("status"),
    )
}

/** Замества SharedPreferences: ключ -> стойност, записани във файл. */
class FilePrefs(private val file: java.io.File) {
    private val map: MutableMap<String, Any?> = HashMap()

    init {
        if (file.exists()) {
            val j = JSONObject(file.readText())
            for (k in j.keys()) map[k] = j.get(k)
        }
    }

    fun getString(key: String, default: String?): String? = (map[key] as? String) ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = (map[key] as? Boolean) ?: default

    fun edit(): Editor = Editor()

    inner class Editor {
        private val changes = LinkedHashMap<String, Any?>()
        fun putString(key: String, value: String?): Editor { changes[key] = value; return this }
        fun putBoolean(key: String, value: Boolean): Editor { changes[key] = value; return this }
        fun remove(key: String): Editor { changes[key] = null; return this }
        fun apply() {
            changes.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            file.parentFile?.mkdirs()
            val j = JSONObject()
            map.forEach { (k, v) -> if (v != null) j.put(k, v) }
            file.writeText(j.toString())
        }
    }
}
