package bg.solar.nightcharge.data

import bg.solar.nightcharge.core.pick
import bg.solar.nightcharge.inverter.Brand
import bg.solar.nightcharge.inverter.Capability
import bg.solar.nightcharge.inverter.Inverter
import bg.solar.nightcharge.inverter.InverterProvider
import bg.solar.nightcharge.inverter.Live
import bg.solar.nightcharge.inverter.Specs
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Victron през VRM портала. Входът е VRM access token (от VRM: Preferences → Integrations → Access tokens).
 * Инверторът тук е инсталацията (сайта), защото VRM не дава отделен инвертор по серийния номер.
 *
 * ВНИМАНИЕ: адресите и кодовете на показанията са по документацията на VRM от памет и не са проверени
 * срещу реален акаунт. Затова уредът е само за четене: няма история и няма запис на зареждане.
 */
class VictronApi(private val store: Store) : InverterProvider {

    override val brand = Brand.VICTRON
    override val capabilities = setOf(Capability.DISCOVER)

    private fun headers(): Map<String, String> {
        val token = store.account(brand)["accessToken"]
        if (token.isBlank()) throw IOException(pick("Въведи VRM access token в Настройки.", "Enter the VRM access token in Settings."))
        return mapOf("X-Authorization" to "Token $token")
    }

    private fun get(path: String): JSONObject = Http.get(BASE + path, headers())

    private fun userId(): Long {
        val r = get("/users/me")
        return r.optJSONObject("user")?.optLong("id", 0L)?.takeIf { it > 0 }
            ?: throw IOException(pick("VRM не върна потребител за този токен.", "VRM returned no user for this token."))
    }

    override fun inverters(): List<Inverter> {
        val recs = get("/users/${userId()}/installations?extended=1").optJSONArray("records") ?: JSONArray()
        return (0 until recs.length()).map {
            val o = recs.getJSONObject(it)
            val id = o.getLong("idSite")
            Inverter(id, o.optString("name"), id.toString(), o.toString())
        }
    }

    override fun live(): Live {
        val id = store.account(brand).stationId
        val recs = get("/installations/$id/diagnostics?count=200").optJSONArray("records") ?: JSONArray()
        val byCode = HashMap<String, Double>()
        for (i in 0 until recs.length()) {
            val o = recs.getJSONObject(i)
            val code = o.optString("code")
            if (code.isNotEmpty() && !o.isNull("rawValue")) byCode[code] = o.optDouble("rawValue")
        }
        return Live(
            soc = Math.round(byCode[CODE_SOC] ?: 0.0).toInt(),
            pvW = byCode[CODE_PV] ?: 0.0,
            loadW = byCode[CODE_LOAD] ?: 0.0,
            gridW = byCode[CODE_GRID],
            batteryW = byCode[CODE_BATTERY],
            raw = recs.toString(),
        )
    }

    override fun specs(): Specs = Specs(model = null, ratedPowerW = null, batteryKwh = null)

    /** Активните записи от GET /installations/{idSite}/alarm-log (isActive = 1). */
    override fun activeAlarms(): List<String> {
        val id = store.account(brand).stationId
        val recs = get("/installations/$id/alarm-log").optJSONArray("records") ?: JSONArray()
        return (0 until recs.length()).mapNotNull {
            val o = recs.getJSONObject(it)
            if (o.optInt("isActive") != 1) return@mapNotNull null
            listOf(o.optString("device"), o.optString("description")).filter { s -> s.isNotBlank() }.joinToString(": ")
        }
    }

    companion object {
        const val BASE = "https://vrmapi.victronenergy.com/v2"
        // Кодове на показанията в VRM diagnostics (непроверени).
        private const val CODE_SOC = "bs"
        private const val CODE_BATTERY = "bp"
        private const val CODE_PV = "Pvs"
        private const val CODE_LOAD = "Pac"
        private const val CODE_GRID = "Gp"
    }
}
