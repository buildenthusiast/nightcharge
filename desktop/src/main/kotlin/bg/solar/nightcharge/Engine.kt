package bg.solar.nightcharge

import bg.solar.nightcharge.core.DayRecord
import bg.solar.nightcharge.core.Planner
import bg.solar.nightcharge.core.Proposal
import bg.solar.nightcharge.core.Savings
import bg.solar.nightcharge.core.Settings
import bg.solar.nightcharge.core.TouSlot
import bg.solar.nightcharge.core.pick
import bg.solar.nightcharge.data.DeyeApi
import bg.solar.nightcharge.data.ForecastApi
import bg.solar.nightcharge.data.GrowattApi
import bg.solar.nightcharge.data.Store
import bg.solar.nightcharge.data.VictronApi
import bg.solar.nightcharge.inverter.Account
import bg.solar.nightcharge.inverter.Brand
import bg.solar.nightcharge.inverter.Capability
import bg.solar.nightcharge.inverter.HourPoint
import bg.solar.nightcharge.inverter.Inverter
import bg.solar.nightcharge.inverter.InverterProvider
import bg.solar.nightcharge.inverter.Live
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/**
 * Свързва прогнозата, историята и инвертора на избраната марка с планировчика.
 * Всичко тук върви по мрежата. Функциите, които марката не поддържа, се прескачат или отказват ясно.
 */
class Engine(file: java.io.File? = null) {
    val store = if (file != null) Store(file) else Store()

    private val api: InverterProvider get() = providerFor(store.brand())

    private fun providerFor(brand: Brand): InverterProvider = when (brand) {
        Brand.DEYE -> DeyeApi(store)
        Brand.GROWATT -> GrowattApi(store)
        Brand.VICTRON -> VictronApi(store)
    }

    /** Марката, избрана в Настройки. */
    fun brand(): Brand = store.brand()

    /** Какво умее избраната марка. */
    fun supports(c: Capability): Boolean = c in api.capabilities

    /** Възможностите на произволна марка, за да може Настройките да ги покажат преди запис. */
    fun capabilitiesOf(brand: Brand): Set<Capability> = providerFor(brand).capabilities

    /** Дали са попълнени данните за вход за избраната марка. */
    fun accountConfigured(): Boolean = store.account(brand()).configured(brand())

    /** Инверторите на избраната марка от последната проверка. */
    fun inverters(): List<Inverter> = store.inverters(brand())

    /** Серийният номер на избрания инвертор. */
    fun selectedSn(): String = store.account(brand()).deviceSn

    fun refresh(): Proposal {
        ready()
        val s = store.settings()
        val today = LocalDate.now(Planner.ZONE)
        val result = ForecastApi.dailyModelKwh(s)
        val perArray = result.daily
        val model = perArray.mapValues { it.value.sum() }
        store.saveModel(model)
        store.saveHourlyModel(result.hourly)

        val history: Map<String, DayRecord> = if (supports(Capability.HISTORY)) {
            // Старата история няма серии за батерията и мрежата: при първото обновяване с новата версия се взима наново.
            val full = !store.historyComplete()
            val h = (if (full) emptyMap() else store.history()).toMutableMap()
            val from = today.minusDays(if (h.isEmpty()) 365 else 7)
            h.putAll(api.history(from, today))
            store.saveHistory(h)
            store.markHistoryComplete()
            updateNightStats(today)
            h
        } else {
            // Без история прогнозата се смята с настройките по подразбиране.
            emptyMap()
        }

        val tomorrow = today.plusDays(1).toString()
        val p = Planner.propose(s, history, model, today, api.live().soc, arrayRawKwh = perArray[tomorrow].orEmpty())
        store.savePending(p)
        store.logProposal(p)
        return p
    }

    /** Активните аварии на инвертора, ако марката ги показва. */
    fun activeAlarms(): List<String> {
        ready()
        return api.activeAlarms()
    }

    /** Текущите показания на инвертора за екрана „Живо“. Запазват се и за уиджета на началния екран. */
    fun live(): Live {
        ready()
        val l = api.live()
        store.saveLastLive(l.soc, l.pvW)
        return l
    }

    /**
     * За последните 31 дни, които още ги няма, смята покупката през нощта и разреждането през деня.
     * Всеки ден е една заявка и се пази, за да не се тегли повторно.
     */
    private fun updateNightStats(today: LocalDate) {
        val s = store.settings()
        val known = store.nightStats().toMutableMap()
        for (i in 1L..31L) {
            val day = today.minusDays(i)
            if (known.containsKey(day.toString())) continue
            runCatching {
                // Нощта е зимно 22–06 или лятно 23–07, според това дали денят е в лятно часово време.
                val (ns, ne) = Planner.nightWindow(day, s)
                val isNight = { h: Int -> h >= ns || h < ne }
                val hours = api.hourly(day)
                val night = hours.filter { isNight(it.hour) }
                // Нощното зареждане на батерията е покупката от мрежата без това, което къщата е ползвала през нощта.
                val nightCharge = (night.sumOf { it.purchased } - night.sumOf { it.consumption }).coerceAtLeast(0.0)
                val dayDischarge = hours.filter { !isNight(it.hour) }.sumOf { it.discharge }
                known[day.toString()] = nightCharge to dayDischarge
            }
        }
        store.saveNightStats(known)
    }

    /** Почасовите стойности за деня (графиката „Ден“). Без история марката връща празно. */
    fun hourly(day: LocalDate): List<HourPoint> {
        if (!supports(Capability.HISTORY)) return emptyList()
        ready()
        return api.hourly(day)
    }

    /** Чете часовите слотове за зареждане и ги запазва като резервно копие. Нищо не записва. */
    fun readInverterSettings(): List<TouSlot> {
        val p = scheduleApi()
        val schedule = p.readSchedule()
        store.saveTouBackup(schedule.raw)
        return schedule.slots
    }

    /** Връща в инвертора последното запазено копие. */
    fun restoreInverterSettings(): String {
        val raw = store.touBackup()?.first
            ?: throw IllegalStateException(pick("Няма запазено копие. Първо прочети настройките на инвертора.", "No saved backup. Read the inverter settings first."))
        return scheduleApi().writeSchedule(raw)
    }

    /** Връща настройките, които са били в инвертора преди последния запис от приложението. */
    fun undoLast(): String {
        val raw = store.undo()
            ?: throw IllegalStateException(pick("Няма предишна настройка за връщане.", "There is no previous setting to go back to."))
        val result = scheduleApi().writeSchedule(raw)
        store.clearUndo()
        return result
    }

    fun canUndo(): Boolean = store.undo() != null

    /** Запазва текущите часови слотове, преди приложението да ги промени. Ако не могат да се прочетат, нищо не се пази. */
    private fun keepUndoPoint() {
        runCatching { store.saveUndo(api.readSchedule().raw) }
    }

    fun apply(p: Proposal, targetSoc: Int = p.targetSoc): Proposal {
        val s = store.settings()
        val charge = targetSoc > s.minSoc
        val inv = scheduleApi()
        keepUndoPoint()
        inv.applySchedule(Planner.touSlots(s, targetSoc, charge, p.nightStart, p.nightEnd))
        return mark(p.copy(targetSoc = targetSoc, charge = charge), Proposal.STATUS_APPLIED)
    }

    /**
     * Отказът изключва зареждането от мрежата в инвертора. Иначе там остава
     * последно записаната настройка, например зареждане от предната нощ.
     */
    fun reject(p: Proposal): Proposal {
        val s = store.settings()
        val inv = scheduleApi()
        keepUndoPoint()
        inv.applySchedule(Planner.touSlots(s, s.minSoc, false, p.nightStart, p.nightEnd))
        return mark(p, Proposal.STATUS_REJECTED)
    }

    private fun mark(p: Proposal, status: String): Proposal {
        val updated = p.copy(status = status)
        store.savePending(updated)
        store.logProposal(updated)
        return updated
    }

    /** Кратко за вчера: произведено и спестено. Показва се във вечерното известие. */
    fun yesterdaySummary(): String {
        val s = store.settings()
        val day = LocalDate.now(Planner.ZONE).minusDays(1).toString()
        val pv = store.history()[day]?.pvKwh
        val night = store.nightStats()[day]
        val saving = night?.let { Savings.perDay(it.first, it.second, s.dayPrice, s.nightPrice) }
        val pvText = pv?.let { String.format(Locale.US, "%.1f kWh", it) } ?: "–"
        val savingText = saving?.let { String.format(Locale.US, "%.2f €", it) } ?: "–"
        return pick("Вчера: произведени $pvText, спестено ~$savingText.", "Yesterday: produced $pvText, saved ~$savingText.")
    }

    /** Цялата история като CSV: дата, производство, консумация, покупка, зареждане, разреждане, подаване и спестяване. */
    fun exportCsv(): String {
        val s = store.settings()
        val history = store.history()
        val nights = store.nightStats()
        val sb = StringBuilder()
        sb.append("date,production_kwh,consumption_kwh,bought_kwh,charge_kwh,discharge_kwh,feed_in_kwh,night_charge_kwh,day_discharge_kwh,saving_eur\n")
        for (day in history.keys.sorted()) {
            val r = history.getValue(day)
            val n = nights[day]
            val saving = n?.let { Savings.perDay(it.first, it.second, s.dayPrice, s.nightPrice) }
            sb.append(
                listOf(
                    day,
                    f3(r.pvKwh), f3(r.consKwh), f3(r.purchasedKwh), f3(r.chargeKwh), f3(r.dischargeKwh), f3(r.feedInKwh),
                    n?.let { f3(it.first) } ?: "", n?.let { f3(it.second) } ?: "", saving?.let { f2(it) } ?: "",
                ).joinToString(","),
            ).append('\n')
        }
        return sb.toString()
    }

    /**
     * Пази цялата история и спестяванията като CSV в „Изтегляния/SolarEnthusiast“.
     * Файлът не се трие при обновяване или преинсталиране на приложението.
     */
    fun backupToDownloads(): String {
        val csv = exportCsv().toByteArray(Charsets.UTF_8)
        val done = pick("Историята е записана в Документи/SolarEnthusiast.", "History saved to Documents/SolarEnthusiast.")
        val dir = File(System.getProperty("user.home"), "Documents/SolarEnthusiast")
        dir.mkdirs()
        File(dir, "solar-enthusiast-history.csv").writeBytes(csv)
        return done
    }

    /** Проверка на връзката: намира инверторите и чете каквото може за избрания. */
    fun testConnection(): String {
        val b = brand()
        val chosen = discover()
        val live = api.live()
        val count = store.inverters(b).size
        return buildString {
            append(pick("Станция ${chosen.stationId}, инвертор ${chosen.deviceSn}, SOC ${live.soc}%.", "Station ${chosen.stationId}, inverter ${chosen.deviceSn}, SOC ${live.soc}%."))
            if (count > 1) {
                append("\n" + pick("В акаунта има $count инвертора, избери кой да управлявам по-долу.", "The account has $count inverters, choose which one to manage below."))
            }
            append("\n").append(detectSpecs())
            if (supports(Capability.SCHEDULE)) {
                val slots = runCatching { api.readSchedule().slots.size.toString() }
                    .getOrElse { pick("не може да се прочете", "cannot be read") + " (${it.message})" }
                append("\n" + pick("Часови слотове за зареждане: ", "Time-of-use slots: ")).append(slots)
            }
        }
    }

    /** Избира друг инвертор от списъка и чете неговите данни. */
    fun select(inverter: Inverter): String {
        val b = brand()
        store.saveAccount(b, store.account(b).copy(stationId = inverter.stationId, deviceSn = inverter.deviceSn))
        // Историята и прогнозата са за предишния инвертор.
        store.saveHistory(emptyMap())
        return pick("Избран е инвертор ${inverter.deviceSn}.\n", "Inverter ${inverter.deviceSn} selected.\n") + detectSpecs()
    }

    /**
     * Записва марката и данните за вход. Празно поле с „без промяна“ пази старата стойност.
     * Ако се е сменила марката или някое поле, избраният инвертор и токенът се изчистват,
     * а историята и спестяванията от предишната марка се премахват.
     */
    fun saveBrand(brand: Brand, input: Map<String, String>) {
        val previous = store.brand()
        val old = store.account(brand)
        val fields = brand.form.associate { f ->
            val raw = input[f.key].orEmpty()
            f.key to when {
                raw.isEmpty() && f.keepIfEmpty -> old[f.key]
                f.hashed -> DeyeApi.sha256(raw)
                else -> raw.trim()
            }
        }
        if (brand != previous) {
            // Историята и спестяванията са от предишната марка.
            store.saveHistory(emptyMap())
            store.saveNightStats(emptyMap())
        }
        store.setBrand(brand)
        val changed = fields != old.fields
        store.saveAccount(brand, if (changed) Account(fields = fields) else old)
    }

    /** Проверява дали има избран инвертор; ако няма, взима първия от акаунта. */
    private fun ready() {
        if (store.account(brand()).deviceSn.isBlank()) discover()
    }

    /** Обновява списъка с инвертори. Запазва избрания, ако още е в акаунта, иначе избира първия. */
    private fun discover(): Inverter {
        val b = brand()
        val list = api.inverters()
        if (list.isEmpty()) {
            throw IOException(pick("В този ${b.cloudName} акаунт няма инвертор.", "This ${b.cloudName} account has no inverter."))
        }
        store.saveInverters(b, list)
        val a = store.account(b)
        val chosen = list.firstOrNull { it.deviceSn == a.deviceSn } ?: list.first()
        store.saveAccount(b, a.copy(stationId = chosen.stationId, deviceSn = chosen.deviceSn))
        return chosen
    }

    /** Записите за зареждане са достъпни само ако марката ги поддържа. */
    private fun scheduleApi(): InverterProvider {
        if (!supports(Capability.SCHEDULE)) {
            throw IllegalStateException(pick("${brand().label} не поддържа запис на зареждане от мрежата.", "${brand().label} does not support writing the grid charge schedule."))
        }
        ready()
        return api
    }

    /** Записва в настройките номиналната мощност и батерията, ако облакът ги върне. */
    private fun detectSpecs(): String {
        val specs = runCatching { api.specs() }.getOrElse {
            return pick(
                "Не успях да прочета данните на инвертора (${it.message}). Въведи ги ръчно в Настройки.",
                "Could not read the inverter data (${it.message}). Enter them manually in Settings.",
            )
        }
        var s = store.settings()
        val found = mutableListOf<String>()
        specs.model?.let { found.add(pick("модел $it", "model $it")) }
        specs.ratedPowerW?.let {
            s = s.copy(inverterRatedW = it, chargePowerW = minOf(s.chargePowerW, it))
            found.add(pick("мощност $it W", "power $it W"))
        }
        specs.batteryKwh?.let {
            val kwh = Math.round(it * 10) / 10.0
            s = s.copy(batteryKwh = kwh)
            found.add(pick("батерия", "battery") + " ${String.format(Locale.US, "%.1f", kwh)} kWh")
        }
        store.save(s)
        val missing = buildList {
            if (specs.ratedPowerW == null) add(pick("мощността", "power"))
            if (specs.batteryKwh == null) add(pick("капацитета на батерията", "battery capacity"))
        }
        return buildString {
            append(
                if (found.isEmpty()) pick("От инвертора не се прочетоха данни.", "No data was read from the inverter.")
                else pick("Прочетено от инвертора: ", "Read from the inverter: ") + "${found.joinToString(", ")}.",
            )
            if (missing.isNotEmpty()) {
                append(pick(" Провери ръчно ", " Check manually: ") + missing.joinToString(pick(" и ", " and ")) + pick(" в Настройки.", " in Settings."))
            }
        }
    }

    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun f3(v: Double) = String.format(Locale.US, "%.3f", v)
}
