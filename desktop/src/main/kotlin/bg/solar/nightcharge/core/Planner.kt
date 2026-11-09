package bg.solar.nightcharge.core

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object Planner {
    val ZONE: ZoneId = ZoneId.of("Europe/Sofia")

    /** Общи загуби без температурата: кабели, инвертор, замърсяване, разминаване на панелите. Температурата е отделно. */
    const val BASE_PR = 0.92
    const val CHARGE_EFF = 0.95
    const val DISCHARGE_EFF = 0.95
    private const val LONG_DAYS = 30L
    private const val SHORT_DAYS = 3
    private const val MIN_MODEL_KWH = 1.0

    /** Очаквано производство от дневната радиация върху един масив панели. */
    fun modelKwh(irradianceKwhM2: Double, kwp: Double): Double = kwp * irradianceKwhM2 * BASE_PR

    /**
     * Загуба от температурата на клетките за един час: −0.35% на градус над 25 °C.
     * Клетката е по-гореща от въздуха при силно слънце (около 3 °C на всеки 100 W/m²).
     */
    fun tempFactor(ambientC: Double, gtiWm2: Double): Double {
        val cell = ambientC + 0.03 * gtiWm2
        return (1.0 - 0.0035 * max(0.0, cell - 25.0)).coerceIn(0.8, 1.0)
    }

    /**
     * Колко от модела реално дават панелите. Пропускат се дни, в които производството е
     * близо до консумацията, защото тогава инверторът е ограничавал (zero export).
     */
    fun calibration(history: Map<String, DayRecord>, model: Map<String, Double>, today: LocalDate): Calibration {
        val ratios = mutableListOf<Double>()
        val oldest = today.minusDays(LONG_DAYS)
        var d = today.minusDays(1)
        while (!d.isBefore(oldest)) {
            val rec = history[d.toString()]
            val m = model[d.toString()]
            if (rec != null && m != null && m >= MIN_MODEL_KWH && rec.pvKwh < rec.consKwh * 0.9) {
                ratios.add((rec.pvKwh / m).coerceIn(0.0, 1.5))
            }
            d = d.minusDays(1)
        }
        if (ratios.isEmpty()) return Calibration(1.0, 1.0, 1.0, 0)
        val longTerm = median(ratios)
        val shortTerm = median(ratios.take(SHORT_DAYS))
        val factor = (0.4 * longTerm + 0.6 * shortTerm).coerceIn(0.2, 1.3)
        return Calibration(longTerm, shortTerm, factor, ratios.size)
    }

    /** Средна консумация за последните 14 дни; същият ден от седмицата тежи двойно. */
    fun expectedConsumption(history: Map<String, DayRecord>, forDate: LocalDate, fallback: Double): Double {
        var sum = 0.0
        var weight = 0.0
        for (i in 2L until 16L) {
            val d = forDate.minusDays(i)
            val rec = history[d.toString()] ?: continue
            if (rec.consKwh <= 0.0) continue
            val w = if (d.dayOfWeek == forDate.dayOfWeek) 2.0 else 1.0
            sum += rec.consKwh * w
            weight += w
        }
        return if (weight > 0.0) sum / weight else fallback
    }

    fun nightWindow(date: LocalDate, s: Settings): Pair<Int, Int> {
        val summer = ZONE.rules.isDaylightSavings(date.atTime(12, 0).atZone(ZONE).toInstant())
        return if (summer) s.nightStartSummer to s.nightEndSummer else s.nightStartWinter to s.nightEndWinter
    }

    fun propose(
        s: Settings,
        history: Map<String, DayRecord>,
        model: Map<String, Double>,
        today: LocalDate,
        currentSoc: Int,
        now: Long = System.currentTimeMillis(),
        arrayRawKwh: List<Double> = emptyList(),
    ): Proposal {
        val tomorrow = today.plusDays(1)
        val raw = model[tomorrow.toString()] ?: throw IllegalStateException(pick("Няма прогноза за $tomorrow", "No forecast for $tomorrow"))
        val cal = calibration(history, model, today)
        val pv = raw * cal.factor
        val arrayKwh = arrayRawKwh.map { it * cal.factor }
        val dayAfter = model[tomorrow.plusDays(1).toString()]?.let { it * cal.factor }
        val cons = expectedConsumption(history, tomorrow, s.defaultDailyConsKwh)

        val deficit = max(0.0, (s.dayShare * cons - pv * DISCHARGE_EFF) * (1 + s.reservePct / 100.0))
        val economical = s.nightPrice / (CHARGE_EFF * DISCHARGE_EFF) < s.dayPrice
        var target = s.minSoc
        if (deficit > 0.5 && economical) {
            val pct = deficit / (s.batteryKwh * DISCHARGE_EFF) * 100
            target = min(s.maxSoc, (ceil((s.minSoc + pct) / 5.0) * 5).toInt())
        }
        val charge = target > s.minSoc
        val gridKwh = if (charge) max(0, target - min(currentSoc, target)) / 100.0 * s.batteryKwh / CHARGE_EFF else 0.0
        val saving = gridKwh * CHARGE_EFF * DISCHARGE_EFF * s.dayPrice - s.nightPrice * gridKwh
        val (ns, ne) = nightWindow(today, s)

        val sb = StringBuilder()
        sb.append(pick("Прогноза за $tomorrow: ${f1(pv)} kWh", "Forecast for $tomorrow: ${f1(pv)} kWh"))
        if (cal.daysUsed > 0) {
            sb.append(
                pick(
                    " (модел ${f1(raw)} kWh × ${pct(cal.factor)}; последните дни панелите дават ${pct(cal.shortTerm)} от модела, за месеца ${pct(cal.longTerm)})",
                    " (model ${f1(raw)} kWh × ${pct(cal.factor)}; recent days ${pct(cal.shortTerm)} of model, month ${pct(cal.longTerm)})",
                ),
            )
        } else {
            sb.append(pick(" (още няма история за корекция)", " (no history for correction yet)"))
        }
        sb.append(
            pick(
                ". Очаквана консумация ${f1(cons)} kWh, от тях ${f1(s.dayShare * cons)} kWh на дневна тарифа. ",
                ". Expected consumption ${f1(cons)} kWh, of which ${f1(s.dayShare * cons)} kWh on the day tariff. ",
            ),
        )
        when {
            !economical -> sb.append(
                pick(
                    "Нощната цена не е достатъчно по-ниска от дневната, за да си струва зареждането.",
                    "The night price is not low enough compared with the day price to make charging worth it.",
                ),
            )
            charge -> sb.append(
                pick(
                    "Недостиг ~${f1(deficit)} kWh → зареждане до $target% между ${hh(ns)} и ${hh(ne)} (до ~${f1(gridKwh)} kWh от мрежата, спестява ~${f2(saving)} €).",
                    "Shortfall ~${f1(deficit)} kWh → charge to $target% between ${hh(ns)} and ${hh(ne)} (up to ~${f1(gridKwh)} kWh from the grid, saves ~${f2(saving)} €).",
                ),
            )
            else -> sb.append(pick("Слънцето покрива деня, зареждане от мрежата не е нужно.", "Solar covers the day, no grid charging needed."))
        }
        if (dayAfter != null) sb.append(pick(" Вдругиден: ~${f1(dayAfter)} kWh.", " Day after: ~${f1(dayAfter)} kWh."))
        if (arrayKwh.size > 1 && arrayKwh.size == s.arrays.size) {
            val parts = s.arrays.zip(arrayKwh).joinToString("; ") { (arr, kwh) ->
                "${arr.facing.label} ${f1(arr.kwp)} kWp, ${arr.tiltDeg}°: ${f1(kwh)} kWh"
            }
            sb.append(pick(" По панели: $parts.", " Per array: $parts."))
        }

        return Proposal(
            forDate = tomorrow.toString(),
            createdAt = now,
            rawPvKwh = raw,
            calibration = cal.factor,
            pvKwh = pv,
            dayAfterPvKwh = dayAfter,
            consKwh = cons,
            deficitKwh = deficit,
            currentSoc = currentSoc,
            targetSoc = target,
            charge = charge,
            gridKwh = gridKwh,
            savingEur = saving,
            nightStart = ns,
            nightEnd = ne,
            reason = sb.toString(),
            arrayKwh = arrayKwh,
        )
    }

    /**
     * Шестте Time of Use слота на Deye: нощният прозорец зарежда (или не) до целта,
     * а четирите дневни слота пускат батерията до минималния заряд с пълната мощност на инвертора.
     */
    fun touSlots(s: Settings, targetSoc: Int, charge: Boolean, nightStart: Int, nightEnd: Int): List<TouSlot> {
        val step = max(1, (nightStart - nightEnd) / 4)
        val night = { h: Int -> TouSlot(hh(h), targetSoc, charge, min(s.chargePowerW, s.ratedPowerW)) }
        val slots = mutableListOf(night(0))
        for (i in 0 until 4) {
            slots.add(TouSlot(hh(nightEnd + i * step), s.minSoc, false, s.ratedPowerW))
        }
        slots.add(night(nightStart))
        return slots
    }

    fun hh(h: Int): String = String.format(Locale.US, "%02d:00", h)

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun pct(v: Double) = "${Math.round(100 * v)}%"

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
