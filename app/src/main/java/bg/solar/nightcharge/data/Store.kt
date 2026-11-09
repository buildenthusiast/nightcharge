package bg.solar.nightcharge.data

import android.content.Context
import android.content.SharedPreferences
import bg.solar.nightcharge.core.DayRecord
import bg.solar.nightcharge.core.Erp
import bg.solar.nightcharge.core.Proposal
import bg.solar.nightcharge.core.Settings
import org.json.JSONArray
import org.json.JSONObject

data class DeyeAuth(
    val appId: String = "",
    val appSecret: String = "",
    val email: String = "",
    val passwordSha256: String = "",
    val stationId: Long = 0,
    val deviceSn: String = "",
    val token: String = "",
    val tokenExpiresAt: Long = 0,
) {
    val configured: Boolean
        get() = appId.isNotBlank() && appSecret.isNotBlank() && email.isNotBlank() && passwordSha256.isNotBlank()
}

/** Всичко се пази в SharedPreferences като JSON. */
class Store(ctx: Context) {
    private val prefs: SharedPreferences = ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)

    fun settings(): Settings {
        val j = json("settings") ?: return Settings()
        val d = Settings()
        return Settings(
            pvKwp = j.optDouble("pvKwp", d.pvKwp),
            tiltDeg = j.optInt("tiltDeg", d.tiltDeg),
            azimuthDeg = j.optInt("azimuthDeg", d.azimuthDeg),
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
        )
    }

    fun save(s: Settings) {
        put(
            "settings",
            JSONObject()
                .put("pvKwp", s.pvKwp)
                .put("tiltDeg", s.tiltDeg)
                .put("azimuthDeg", s.azimuthDeg)
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
                .put("inverterRatedW", s.inverterRatedW),
        )
    }

    fun auth(): DeyeAuth {
        val j = json("auth") ?: return DeyeAuth()
        return DeyeAuth(
            appId = j.optString("appId"),
            appSecret = j.optString("appSecret"),
            email = j.optString("email"),
            passwordSha256 = j.optString("passwordSha256"),
            stationId = j.optLong("stationId"),
            deviceSn = j.optString("deviceSn"),
            token = j.optString("token"),
            tokenExpiresAt = j.optLong("tokenExpiresAt"),
        )
    }

    fun save(a: DeyeAuth) {
        put(
            "auth",
            JSONObject()
                .put("appId", a.appId)
                .put("appSecret", a.appSecret)
                .put("email", a.email)
                .put("passwordSha256", a.passwordSha256)
                .put("stationId", a.stationId)
                .put("deviceSn", a.deviceSn)
                .put("token", a.token)
                .put("tokenExpiresAt", a.tokenExpiresAt),
        )
    }

    /** Инверторите в акаунта от последната проверка на връзката. */
    fun inverters(): List<DeyeApi.Inverter> {
        val a = prefs.getString("inverters", null)?.let { JSONArray(it) } ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            DeyeApi.Inverter(o.getLong("stationId"), o.optString("stationName"), o.getString("deviceSn"), o.optString("raw"))
        }
    }

    fun saveInverters(list: List<DeyeApi.Inverter>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("stationId", it.stationId).put("stationName", it.stationName).put("deviceSn", it.deviceSn).put("raw", it.raw))
        }
        prefs.edit().putString("inverters", a.toString()).apply()
    }

    fun history(): Map<String, DayRecord> {
        val j = json("history") ?: return emptyMap()
        val out = HashMap<String, DayRecord>()
        for (k in j.keys()) {
            val r = j.getJSONArray(k)
            out[k] = DayRecord(k, r.getDouble(0), r.getDouble(1))
        }
        return out
    }

    fun saveHistory(h: Map<String, DayRecord>) {
        val j = JSONObject()
        h.values.forEach { j.put(it.date, JSONArray().put(it.pvKwh).put(it.consKwh)) }
        put("history", j)
    }

    fun model(): Map<String, Double> {
        val j = json("model") ?: return emptyMap()
        return j.keys().asSequence().associateWith { j.getDouble(it) }
    }

    fun saveModel(m: Map<String, Double>) = put("model", JSONObject(m))

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
        status = j.getString("status"),
    )
}
