package bg.solar.nightcharge

import bg.solar.nightcharge.core.Planner
import bg.solar.nightcharge.core.Proposal
import bg.solar.nightcharge.data.DeyeApi
import bg.solar.nightcharge.data.ForecastApi
import bg.solar.nightcharge.data.Store
import java.time.LocalDate
import java.util.Locale

/** Свързва прогнозата, историята от Deye Cloud и планировчика. Всичко тук върви по мрежата. */
class Engine(file: java.io.File? = null) {
    val store = if (file != null) Store(file) else Store()
    private val api = DeyeApi(store)

    fun refresh(): Proposal {
        val s = store.settings()
        val today = LocalDate.now(Planner.ZONE)
        val model = ForecastApi.dailyModelKwh(s)
        store.saveModel(model)

        val history = store.history().toMutableMap()
        val from = today.minusDays(if (history.isEmpty()) 365 else 7)
        history.putAll(api.history(from, today))
        store.saveHistory(history)

        val p = Planner.propose(s, history, model, today, api.live().soc)
        store.savePending(p)
        store.logProposal(p)
        return p
    }

    fun apply(p: Proposal, targetSoc: Int = p.targetSoc): Proposal {
        val s = store.settings()
        val charge = targetSoc > s.minSoc
        api.setTou(Planner.touSlots(s, targetSoc, charge, p.nightStart, p.nightEnd))
        return mark(p.copy(targetSoc = targetSoc, charge = charge), Proposal.STATUS_APPLIED)
    }

    /**
     * Отказът изключва зареждането от мрежата в инвертора. Иначе там остава
     * последно записаната настройка, например зареждане от предната нощ.
     */
    fun reject(p: Proposal): Proposal {
        val s = store.settings()
        api.setTou(Planner.touSlots(s, s.minSoc, false, p.nightStart, p.nightEnd))
        return mark(p, Proposal.STATUS_REJECTED)
    }

    private fun mark(p: Proposal, status: String): Proposal {
        val updated = p.copy(status = status)
        store.savePending(updated)
        store.logProposal(updated)
        return updated
    }

    /** Суровите данни на инвертора за показване в приложението. Нищо не се записва. */
    fun rawKeys(): String = api.rawKeys()

    /** Проверка на връзката: намира инверторите и чете каквото може за избрания. */
    fun testConnection(): String {
        val a = api.discover()
        val live = api.live()
        val tou = runCatching { api.readTou() }.getOrElse { "не може да се прочете (${it.message})" }
        val count = store.inverters().size
        return buildString {
            append("Станция ${a.stationId}, инвертор ${a.deviceSn}, SOC ${live.soc}%.")
            if (count > 1) append("\nВ акаунта има $count инвертора, избери кой да управлявам по-долу.")
            append("\n").append(detectSpecs())
            append("\nТекущ Time of Use: ").append(tou)
        }
    }

    /** Избира друг инвертор от списъка и чете неговите данни. */
    fun select(inverter: DeyeApi.Inverter): String {
        store.save(store.auth().copy(stationId = inverter.stationId, deviceSn = inverter.deviceSn))
        // Историята и прогнозата са за предишната станция.
        store.saveHistory(emptyMap())
        return "Избран е инвертор ${inverter.deviceSn}.\n" + detectSpecs()
    }

    /** Записва в настройките номиналната мощност и батерията, ако Deye Cloud ги върне. */
    private fun detectSpecs(): String {
        val specs = runCatching { api.specs() }.getOrElse { return "Не успях да прочета данните на инвертора (${it.message}). Въведи ги ръчно в Настройки." }
        var s = store.settings()
        val found = mutableListOf<String>()
        specs.model?.let { found.add("модел $it") }
        specs.ratedPowerW?.let {
            s = s.copy(inverterRatedW = it, chargePowerW = minOf(s.chargePowerW, it))
            found.add("мощност $it W")
        }
        specs.batteryKwh?.let {
            val kwh = Math.round(it * 10) / 10.0
            s = s.copy(batteryKwh = kwh)
            found.add("батерия ${String.format(Locale.US, "%.1f", kwh)} kWh")
        }
        store.save(s)
        val missing = buildList {
            if (specs.ratedPowerW == null) add("мощността")
            if (specs.batteryKwh == null) add("капацитета на батерията")
        }
        return buildString {
            append(if (found.isEmpty()) "От инвертора не се прочетоха данни." else "Прочетено от инвертора: ${found.joinToString(", ")}.")
            if (missing.isNotEmpty()) append(" Провери ръчно ${missing.joinToString(" и ")} в Настройки.")
        }
    }
}
