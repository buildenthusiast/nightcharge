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
 * Growatt през Growatt Cloud OpenAPI с API токен от портала.
 *
 * ВНИМАНИЕ: адресите и полетата на отговорите са от памет и не са проверени срещу реален акаунт.
 * Ако списъкът с инверторите или показанията са празни, първо провери [PLANT_LIST], [DEVICE_LIST] и [LIVE_PATH].
 * Уредът е само за четене: няма история и няма запис на зареждане.
 */
class GrowattApi(private val store: Store) : InverterProvider {

    override val brand = Brand.GROWATT
    override val capabilities = setOf(Capability.DISCOVER)

    private fun get(path: String): JSONObject {
        val token = store.account(brand)["apiToken"]
        if (token.isBlank()) throw IOException(pick("Въведи API токена от Growatt Cloud в Настройки.", "Enter the Growatt Cloud API token in Settings."))
        return Http.get(BASE + path, mapOf("token" to token))
    }

    /** Отговорите идват в различни обвивки; взима първия масив, намерен под някое от имената. */
    private fun arrayOf(r: JSONObject, vararg keys: String): JSONArray? {
        val data = r.optJSONObject("data")
        for (k in keys) {
            r.optJSONArray(k)?.let { return it }
            data?.optJSONArray(k)?.let { return it }
        }
        return r.optJSONArray("data")
    }

    override fun inverters(): List<Inverter> {
        val plants = arrayOf(get(PLANT_LIST), "plants", "plantList") ?: JSONArray()
        val out = mutableListOf<Inverter>()
        for (i in 0 until plants.length()) {
            val p = plants.getJSONObject(i)
            val plantId = p.optLong("id", p.optLong("plant_id", 0L))
            val devices = arrayOf(get("$DEVICE_LIST?plant_id=$plantId"), "devices", "deviceList") ?: JSONArray()
            for (k in 0 until devices.length()) {
                val d = devices.getJSONObject(k)
                val sn = d.optString("device_sn", d.optString("deviceSn"))
                if (sn.isBlank()) continue
                out.add(Inverter(plantId, p.optString("name", p.optString("plant_name")), sn, d.toString()))
            }
        }
        return out
    }

    override fun live(): Live {
        val sn = store.account(brand).deviceSn
        val r = get("$LIVE_PATH?device_sn=$sn")
        val d = r.optJSONObject("data") ?: r
        return Live(
            soc = Math.round(d.optDouble("soc", d.optDouble("batterySOC", 0.0))).toInt(),
            pvW = d.optDouble("ppv", d.optDouble("pvPower", 0.0)),
            loadW = d.optDouble("load", d.optDouble("loadPower", 0.0)),
            gridW = if (d.has("pacToGrid")) d.optDouble("pacToGrid") else null,
            batteryW = if (d.has("pdisCharge")) d.optDouble("pdisCharge") else null,
            raw = r.toString(),
        )
    }

    override fun specs(): Specs = Specs(model = null, ratedPowerW = null, batteryKwh = null)

    companion object {
        const val BASE = "https://openapi.growatt.com/v1"
        const val PLANT_LIST = "/plant/list"
        const val DEVICE_LIST = "/device/list"
        const val LIVE_PATH = "/device/storage/data"
    }
}
