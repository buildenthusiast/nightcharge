package bg.solar.nightcharge.ui

import org.jetbrains.skia.Canvas as SkiaCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bg.solar.nightcharge.Engine
import bg.solar.nightcharge.core.DayRecord
import bg.solar.nightcharge.core.Planner
import bg.solar.nightcharge.core.pick
import bg.solar.nightcharge.inverter.Capability
import bg.solar.nightcharge.inverter.HourPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos

enum class ChartRange(private val bgLabel: String, private val enLabel: String) {
    DAY("Ден", "Day"),
    WEEK("Седмица", "Week"),
    MONTH("Месец", "Month"),
    YEAR("Година", "Year"),
    ALL("Общо", "Total"),
    ;

    val label: String get() = pick(bgLabel, enLabel)
}

/** Един стълб в kWh по серии. */
data class BarPoint(
    val label: String,
    val production: Double,
    val discharge: Double,
    val purchased: Double,
    val consumption: Double,
    val charge: Double,
    val feedIn: Double,
)

/** Серия за графиката. Нагоре са източниците, надолу потреблението, както в Deye Cloud. */
private class Series(val name: String, val color: Color, val up: Boolean, val value: (BarPoint) -> Double)

private val seriesList: List<Series>
    get() = listOf(
        Series(pick("Производство", "Production"), SeriesProduction, true) { it.production },
        Series(pick("Разреждане", "Discharge"), SeriesDischarge, true) { it.discharge },
        Series(pick("Покупка от мрежата", "Bought from grid"), SeriesPurchased, true) { it.purchased },
        Series(pick("Консумация", "Consumption"), SeriesConsumption, false) { it.consumption },
        Series(pick("Зареждане", "Charge"), SeriesCharge, false) { it.charge },
        Series(pick("Подаване в мрежата", "Feed-in to grid"), SeriesFeedIn, false) { it.feedIn },
    )

private fun DayRecord.toBar(label: String) =
    BarPoint(label, pvKwh, dischargeKwh, purchasedKwh, consKwh, chargeKwh, feedInKwh)

/** Дневните точки за седмица, месец, година и общо. */
fun dailyPoints(history: Map<String, DayRecord>, range: ChartRange, end: LocalDate): List<BarPoint> = when (range) {
    ChartRange.DAY -> emptyList()
    ChartRange.WEEK -> (6L downTo 0L).map { i -> dayBar(history, end.minusDays(i)) }
    ChartRange.MONTH -> monthDays(end).map { dayBar(history, it) }
    ChartRange.YEAR -> monthly(history.values.filter { it.date >= end.minusMonths(12).toString() && it.date <= end.toString() })
    ChartRange.ALL -> yearly(history.values.toList())
}

/** Всички дни от календарния месец, в който е датата: от 1-во до последното число. */
private fun monthDays(end: LocalDate): List<LocalDate> = YearMonth.from(end).let { ym ->
    (1..ym.lengthOfMonth()).map { ym.atDay(it) }
}

/** Какъв е периодът, който се показва, например „06.10 – 12.10“. */
private fun periodLabel(range: ChartRange, end: LocalDate): String {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
    return when (range) {
        ChartRange.DAY -> end.format(fmt)
        ChartRange.WEEK -> "${end.minusDays(6).format(fmt)} – ${end.format(fmt)}"
        ChartRange.MONTH -> YearMonth.from(end).let { "${it.atDay(1).format(fmt)} – ${it.atEndOfMonth().format(fmt)}" }
        ChartRange.YEAR -> "${end.minusMonths(11).withDayOfMonth(1).format(fmt)} – ${end.format(fmt)}"
        ChartRange.ALL -> pick("Всички години", "All years")
    }
}

/** Премества края на периода напред или назад, без да минава над вчера. Месец се мести по календарни месеци. */
private fun shiftEnd(end: LocalDate, range: ChartRange, dir: Long, yesterday: LocalDate): LocalDate {
    val moved = when (range) {
        ChartRange.DAY -> end.plusDays(dir)
        ChartRange.WEEK -> end.plusDays(7 * dir)
        ChartRange.MONTH -> end.withDayOfMonth(1).plusMonths(dir)
        ChartRange.YEAR -> end.plusMonths(12 * dir)
        ChartRange.ALL -> end
    }
    return if (moved.isAfter(yesterday)) yesterday else moved
}

private fun dayBar(history: Map<String, DayRecord>, day: LocalDate): BarPoint {
    val key = day.toString()
    return history[key]?.toBar(key.substring(5)) ?: BarPoint(key.substring(5), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
}

private fun monthly(days: List<DayRecord>): List<BarPoint> = sumBy(days) { it.date.substring(0, 7) }.map { (k, b) -> b.copy(label = k.substring(2)) }

/** По един стълб за всяка година: събира всички години, които има в историята. */
private fun yearly(days: List<DayRecord>): List<BarPoint> = sumBy(days) { it.date.substring(0, 4) }.map { (k, b) -> b.copy(label = k) }

private fun sumBy(days: List<DayRecord>, key: (DayRecord) -> String): List<Pair<String, BarPoint>> =
    days.groupBy(key).toSortedMap().map { (k, rs) ->
        k to BarPoint(
            k,
            rs.sumOf { it.pvKwh },
            rs.sumOf { it.dischargeKwh },
            rs.sumOf { it.purchasedKwh },
            rs.sumOf { it.consKwh },
            rs.sumOf { it.chargeKwh },
            rs.sumOf { it.feedInKwh },
        )
    }

/** Почасовите стойности за деня: 24 стълба. */
private fun hourlyPoints(hours: List<HourPoint>): List<BarPoint> = (0 until 24).map { h ->
    val x = hours.firstOrNull { it.hour == h }
    BarPoint(
        String.format(Locale.US, "%02d", h),
        x?.production ?: 0.0,
        x?.discharge ?: 0.0,
        x?.purchased ?: 0.0,
        x?.consumption ?: 0.0,
        x?.charge ?: 0.0,
        0.0,
    )
}

/** Избор на период: всички бутони в един ред с еднаква ширина. Избраният е запълнен. */
@Composable
fun RangePicker(selected: ChartRange, onSelect: (ChartRange) -> Unit) {
    val pad = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ChartRange.entries.forEach { r ->
            if (r == selected) {
                Button(onClick = { onSelect(r) }, modifier = Modifier.weight(1f), contentPadding = pad) {
                    Text(r.label, fontSize = 12.sp, maxLines = 1)
                }
            } else {
                OutlinedButton(onClick = { onSelect(r) }, modifier = Modifier.weight(1f), contentPadding = pad) {
                    Text(r.label, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Стълбове в стила на Deye: нагоре източниците, надолу потреблението.
 * Натисни стълб, за да видиш числата му. Натисни серия в легендата, за да я скриеш или покажеш.
 */
@Composable
fun EnergyBars(points: List<BarPoint>, hidden: Set<String>, onToggle: (String) -> Unit) {
    var selected by remember(points) { mutableIntStateOf(-1) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val series = seriesList
    val visible = series.filter { it.name !in hidden }

    val upMax = points.maxOfOrNull { p -> visible.filter { it.up }.sumOf { it.value(p) } } ?: 0.0
    val downMax = points.maxOfOrNull { p -> visible.filter { !it.up }.sumOf { it.value(p) } } ?: 0.0
    val total = (upMax + downMax).coerceAtLeast(0.1)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(points) {
                detectTapGestures { o ->
                    if (points.isNotEmpty()) selected = (o.x / widthPx * points.size).toInt().coerceIn(0, points.size - 1)
                }
            },
    ) {
        val n = points.size
        if (n == 0) return@Canvas
        val slot = size.width / n
        val barW = (slot * 0.6f).coerceAtMost(18.dp.toPx())
        val labelH = 18.dp.toPx()
        val plotH = size.height - labelH
        val scale = plotH / total.toFloat()
        val zeroY = scale * upMax.toFloat()

        if (selected in 0 until n) {
            drawRect(Color.Gray.copy(alpha = 0.15f), topLeft = Offset(selected * slot, 0f), size = Size(slot, plotH))
        }

        points.forEachIndexed { i, p ->
            val x = i * slot + (slot - barW) / 2
            var upY = zeroY
            visible.filter { it.up }.forEach { s ->
                val h = (s.value(p) * scale).toFloat()
                if (h > 0f) {
                    drawRect(s.color, topLeft = Offset(x, upY - h), size = Size(barW, h))
                    upY -= h
                }
            }
            var downY = zeroY
            visible.filter { !it.up }.forEach { s ->
                val h = (s.value(p) * scale).toFloat()
                if (h > 0f) {
                    drawRect(s.color, topLeft = Offset(x, downY), size = Size(barW, h))
                    downY += h
                }
            }
        }

        drawLine(Color.Gray.copy(alpha = 0.6f), Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.dp.toPx())
        val step = ceil(n / 8.0).toInt().coerceAtLeast(1)
        val canvas = drawContext.canvas.nativeCanvas
        points.forEachIndexed { i, p ->
            if (i % step == 0) canvas.centeredText(p.label, i * slot + slot / 2, size.height - 4.dp.toPx(), 10.sp.toPx(), labelColor)
        }
    }

    val chosen = points.getOrNull(selected)

    // Всеки ред е серия: името вляво, стойността при избран стълб вдясно, в една колона.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        series.forEach { s ->
            val off = s.name in hidden
            val value = if (chosen != null && !off) String.format(Locale.US, "%.1f kWh", s.value(chosen)) else null
            LegendRow(s.name, s.color, value, off) { onToggle(s.name) }
        }
    }
}

/** Прогнозата и реалното за един стълб: за ден, месец или година. */
data class ForecastBar(val label: String, val forecast: Double, val actual: Double)

/** Прогнозата и реалното по дни, само за дните, за които има и едното, и другото. */
private fun forecastPairs(engine: Engine, history: Map<String, DayRecord>): Map<String, Pair<Double, Double>> =
    engine.store.log()
        .filter { history.containsKey(it.forDate) }
        .associate { it.forDate to (it.pvKwh to history.getValue(it.forDate).pvKwh) }

/** Стълбовете за периода: по ден за „Ден“, „Седмица“ и „Месец“, по месец за „Година“, по година за „Общо“. */
fun forecastPoints(pairs: Map<String, Pair<Double, Double>>, range: ChartRange, end: LocalDate): List<ForecastBar> {
    fun day(d: LocalDate): ForecastBar {
        val key = d.toString()
        val (f, a) = pairs[key] ?: (0.0 to 0.0)
        return ForecastBar(key.substring(5), f, a)
    }
    fun sumOf(label: String, rs: List<Pair<String, Pair<Double, Double>>>) =
        ForecastBar(label, rs.sumOf { it.second.first }, rs.sumOf { it.second.second })

    return when (range) {
        ChartRange.DAY -> listOf(day(end))
        ChartRange.WEEK -> (6L downTo 0L).map { day(end.minusDays(it)) }
        ChartRange.MONTH -> monthDays(end).map { day(it) }
        ChartRange.YEAR -> {
            val from = end.minusMonths(12).toString()
            val to = end.toString()
            pairs.toList()
                .filter { it.first >= from && it.first <= to }
                .groupBy { it.first.substring(0, 7) }
                .toSortedMap()
                .map { (m, rs) -> sumOf(m.substring(2), rs) }
        }
        ChartRange.ALL -> pairs.toList()
            .groupBy { it.first.substring(0, 4) }
            .toSortedMap()
            .map { (y, rs) -> sumOf(y, rs) }
    }
}

/** Предишен и следващ период с ◀ ▶. Показва името на периода по средата. */
@Composable
private fun PeriodNav(range: ChartRange, anchor: LocalDate, yesterday: LocalDate, onShift: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onShift(-1) }) { Text("◀") }
        Text(
            periodLabel(range, anchor),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { onShift(1) }, enabled = anchor.isBefore(yesterday)) { Text("▶") }
    }
}

/**
 * Два стълба за всеки период: синьо за прогнозата, зелено за реалното. Натисни стълб, за да видиш числата му.
 * Плъзни наляво или надясно, за да минеш към следващия или предишния период.
 */
@Composable
private fun ForecastBars(points: List<ForecastBar>, onShift: (Long) -> Unit) {
    var selected by remember(points) { mutableIntStateOf(-1) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val max = points.maxOf { maxOf(it.forecast, it.actual) }.coerceAtLeast(0.1)
    val latestShift by rememberUpdatedState(onShift)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(points) {
                detectTapGestures { o ->
                    if (points.isNotEmpty()) selected = (o.x / widthPx * points.size).toInt().coerceIn(0, points.size - 1)
                }
            }
            .pointerInput(Unit) {
                val threshold = 60.dp.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total <= -threshold) latestShift(1) else if (total >= threshold) latestShift(-1)
                    },
                    onDragCancel = { total = 0f },
                ) { _, dragAmount -> total += dragAmount }
            },
    ) {
        val n = points.size
        if (n == 0) return@Canvas
        val slot = size.width / n
        val barW = (slot * 0.35f).coerceAtMost(12.dp.toPx())
        val plotH = size.height - 18.dp.toPx()
        // Оставя място над най-високия стълб за числото му.
        val scale = (plotH - 14.dp.toPx()) / max.toFloat()
        // Числата стоят върху стълбовете, докато има места; при месец се виждат само при натискане.
        val showValues = n <= 12
        val digits = if (n <= 7) "%.1f" else "%.0f"

        if (selected in 0 until n) {
            drawRect(Color.Gray.copy(alpha = 0.15f), topLeft = Offset(selected * slot, 0f), size = Size(slot, plotH))
        }
        val canvas = drawContext.canvas.nativeCanvas

        points.forEachIndexed { i, p ->
            val x = i * slot + (slot - 2 * barW) / 2
            val fH = (p.forecast * scale).toFloat()
            val aH = (p.actual * scale).toFloat()
            drawRect(DeyeBlue, topLeft = Offset(x, plotH - fH), size = Size(barW, fH))
            drawRect(SeriesProduction, topLeft = Offset(x + barW, plotH - aH), size = Size(barW, aH))
            if (showValues) {
                canvas.centeredText(String.format(Locale.US, digits, p.forecast), x + barW / 2, plotH - fH - 3.dp.toPx(), 9.sp.toPx(), labelColor)
                canvas.centeredText(String.format(Locale.US, digits, p.actual), x + barW + barW / 2, plotH - aH - 3.dp.toPx(), 9.sp.toPx(), labelColor)
            }
        }

        drawLine(Color.Gray.copy(alpha = 0.6f), Offset(0f, plotH), Offset(size.width, plotH), strokeWidth = 1.dp.toPx())
        val step = ceil(n / 8.0).toInt().coerceAtLeast(1)
        points.forEachIndexed { i, p ->
            if (i % step == 0) canvas.centeredText(p.label, i * slot + slot / 2, size.height - 4.dp.toPx(), 10.sp.toPx(), labelColor)
        }
    }

    val chosen = points.getOrNull(selected)
    if (chosen == null) {
        val totalF = points.sumOf { it.forecast }
        val totalA = points.sumOf { it.actual }
        Text(
            pick("Общо за периода: прогноза ", "Period total: forecast ") + String.format(Locale.US, "%.1f", totalF) + " kWh · " +
                pick("реално ", "actual ") + String.format(Locale.US, "%.1f", totalA) + " kWh",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // При избран стълб стойностите стоят в същата колона като легендата.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LegendRow(pick("Прогноза", "Forecast"), DeyeBlue, chosen?.let { String.format(Locale.US, "%.1f kWh", it.forecast) }, false)
        LegendRow(pick("Реално", "Actual"), SeriesProduction, chosen?.let { String.format(Locale.US, "%.1f kWh", it.actual) }, false)
    }
}

/** Прогнозата срещу реално производството, със същите периоди и навигация като „Графики“. */
@Composable
fun ForecastVsActual(engine: Engine, history: Map<String, DayRecord>, today: LocalDate) {
    var range by remember { mutableStateOf(ChartRange.WEEK) }
    val yesterday = today.minusDays(1)
    var anchor by remember { mutableStateOf(yesterday) }
    val pairs = remember(history) { forecastPairs(engine, history) }
    val points = remember(pairs, range, anchor) { forecastPoints(pairs, range, anchor) }
    val hasData = points.any { it.forecast > 0.0 || it.actual > 0.0 }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(pick("Прогноза срещу реално", "Forecast vs actual"), style = MaterialTheme.typography.titleMedium)
        RangePicker(range) { range = it }
        if (range != ChartRange.ALL) PeriodNav(range, anchor, yesterday) { anchor = shiftEnd(anchor, range, it, yesterday) }
        if (hasData) {
            ForecastBars(points) { dir -> if (range != ChartRange.ALL) anchor = shiftEnd(anchor, range, dir, yesterday) }
        } else {
            Text(
                pick("Още няма сравнение за този период. Ще се появи, след като минат дни с прогноза.", "No comparison for this period yet. It appears after days with a forecast have passed."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Сборът на днешните часове до момента, като стълб. Енергията е средната мощност за часа, в kWh. */
private fun todayBarFrom(hours: List<HourPoint>, day: LocalDate): BarPoint = BarPoint(
    day.toString().substring(5),
    hours.sumOf { it.production },
    hours.sumOf { it.discharge },
    hours.sumOf { it.purchased },
    hours.sumOf { it.consumption },
    hours.sumOf { it.charge },
    0.0,
)

/**
 * Плавна линия през часовите стойности: между два съседни часа се чертае косинусова интерполация на всеки 5 минути.
 * Липсващата стойност прекъсва линията. [xAt] и [yAt] превръщат час (с дроби) и стойност в координати.
 */
private fun smoothPath(values: List<Double?>, xAt: (Double) -> Float, yAt: (Double) -> Float): Path {
    val path = Path()
    var previous: Double? = null
    values.forEachIndexed { h, v ->
        if (v == null) {
            previous = null
            return@forEachIndexed
        }
        val prev = previous
        if (prev == null) {
            path.moveTo(xAt(h.toDouble()), yAt(v))
        } else {
            // 12 стъпки по 5 минути между предишния и текущия час.
            for (k in 1..12) {
                val t = k / 12.0
                val w = (1 - cos(PI * t)) / 2
                path.lineTo(xAt((h - 1) + t), yAt(prev + (v - prev) * w))
            }
        }
        previous = v
    }
    return path
}

/** Една линия от профила: име, цвят, стойност за час и дали е в проценти (дясна ос). */
private class ProfileLine(val name: String, val color: Color, val percent: Boolean, val value: (HourPoint) -> Double?)

private val profileLines: List<ProfileLine>
    get() = listOf(
        ProfileLine(pick("Производство", "Production"), SeriesProduction, false) { it.production },
        ProfileLine(pick("Консумация", "Consumption"), SeriesConsumption, false) { it.consumption },
        ProfileLine(pick("Покупка от мрежата", "Bought from grid"), SeriesPurchased, false) { it.purchased },
        ProfileLine(pick("Батерия (+ разряд, − заряд)", "Battery (+ discharge, − charge)"), DeyeBlue, false) { it.discharge - it.charge },
        ProfileLine(pick("Заряд SoC %", "SoC %"), Color(0xFF8E24AA), true) { it.soc },
    )

/**
 * Профил за деня, в стила на Deye Cloud: линии за всеки час. Вляво са киловати, вдясно проценти от SoC.
 * Натисни час, за да видиш стойностите му. Натисни име в легендата, за да скриеш или покажеш линията.
 */
@Composable
private fun ProfileChart(hours: List<HourPoint>, hidden: Set<String>, expected: List<Double>, onToggle: (String) -> Unit) {
    var selected by remember(hours) { mutableIntStateOf(-1) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val lines = profileLines
    val visible = lines.filter { it.name !in hidden }
    val byHour = (0 until 24).map { h -> hours.firstOrNull { it.hour == h } }
    val expName = pick("Прогноза по модела, kW", "Model forecast, kW")
    val expShown = expected.size == 24 && expName !in hidden
    val kwValues = visible.filter { !it.percent }.flatMap { l -> byHour.mapNotNull { p -> p?.let { l.value(it) } } } +
        (if (expShown) expected else emptyList())
    val yMax = maxOf(0.5, kwValues.maxOrNull() ?: 0.0)
    val yMin = minOf(0.0, kwValues.minOrNull() ?: 0.0)

    if (hours.none { it.soc != null }) {
        Text(
            pick("Deye не връща заряда (SoC) за тези часове. Линията за SoC ще се появи, когато го върне.", "Deye does not return the charge (SoC) for these hours. The SoC line will appear once it does."),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(hours) {
                detectTapGestures { o -> selected = (o.x / widthPx * 24).toInt().coerceIn(0, 23) }
            },
    ) {
        val plotH = size.height - 18.dp.toPx()
        val slot = size.width / 24f
        fun yKw(v: Double): Float = plotH - ((v - yMin) / (yMax - yMin) * plotH).toFloat()
        fun yPct(v: Double): Float = plotH - (v.coerceIn(0.0, 100.0) / 100.0 * plotH).toFloat()

        if (selected in 0..23) drawRect(Color.Gray.copy(alpha = 0.15f), topLeft = Offset(selected * slot, 0f), size = Size(slot, plotH))
        drawLine(Color.Gray.copy(alpha = 0.6f), Offset(0f, yKw(0.0)), Offset(size.width, yKw(0.0)), strokeWidth = 1.dp.toPx())

        visible.forEach { line ->
            val values = byHour.map { p -> p?.let { line.value(it) } }
            val path = smoothPath(values, { pos -> pos.toFloat() * slot + slot / 2 }, { v -> if (line.percent) yPct(v) else yKw(v) })
            drawPath(path, line.color, style = Stroke(width = 2.dp.toPx()))
        }

        if (expShown) {
            // Прогнозата по модела е пунктирана, за да се сравнява с реалното производство.
            val path = smoothPath(expected, { pos -> pos.toFloat() * slot + slot / 2 }, { v -> yKw(v) })
            drawPath(
                path,
                Color.Gray,
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        }
        val canvas = drawContext.canvas.nativeCanvas
        for (h in 0 until 24 step 3) {
            canvas.centeredText(String.format(Locale.US, "%02d", h), h * slot + slot / 2, size.height - 4.dp.toPx(), 10.sp.toPx(), labelColor)
        }
    }

    // Стойностите за избрания час стоят в същата колона като легендата.
    val pickedHour = byHour.getOrNull(selected)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { l ->
            val off = l.name in hidden
            val v = pickedHour?.let { l.value(it) }
            val value = when {
                off || v == null -> null
                l.percent -> String.format(Locale.US, "%.0f %%", v)
                else -> String.format(Locale.US, "%.2f kW", v)
            }
            LegendRow(l.name, l.color, value, off) { onToggle(l.name) }
        }
        if (expected.size == 24) {
            val ev = if (selected in 0..23 && expName !in hidden) String.format(Locale.US, "%.2f kW", expected[selected]) else null
            LegendRow(expName, MaterialTheme.colorScheme.onSurfaceVariant, ev, expName in hidden) { onToggle(expName) }
        }
    }
}

/** Ред от легендата: цветното име вляво, стойността вдясно в една колона за всички редове. */
@Composable
private fun LegendRow(name: String, color: Color, value: String?, off: Boolean, onClick: (() -> Unit)? = null) {
    val rowModifier = Modifier.fillMaxWidth().let { m -> if (onClick != null) m.clickable { onClick() } else m }
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "■ $name",
            color = if (off) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else color,
            style = MaterialTheme.typography.bodySmall,
            textDecoration = if (off) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        Text(
            value.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(96.dp),
        )
    }
}

/** Графика за избрания период. „Ден“ взима почасовите данни за вчера от Deye Cloud. */
@Composable
fun EnergyChartCard(engine: Engine, history: Map<String, DayRecord>, today: LocalDate) {
    var range by remember { mutableStateOf(ChartRange.WEEK) }
    var hourly by remember { mutableStateOf<List<BarPoint>>(emptyList()) }
    var hourlyError by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    // Краят на показвания период. По подразбиране е вчера, защото днешните данни още не са пълни.
    val yesterday = today.minusDays(1)
    var anchor by remember { mutableStateOf(yesterday) }
    // Дневната и седмичната графика могат да покажат и днес (частично). Месец и година спират до вчера.
    val limit = if (range == ChartRange.DAY || range == ChartRange.WEEK) today else yesterday
    var hourlyRaw by remember { mutableStateOf<List<HourPoint>>(emptyList()) }
    var liveToday by remember { mutableStateOf<BarPoint?>(null) }
    var dayView by remember { mutableStateOf(0) }
    var profileHidden by remember { mutableStateOf(setOf<String>()) }
    // Прогнозата по модела за деня по часове, с текущата корекция. Липсва за дни, които не са били смятани.
    val expected = remember(anchor, history) {
        val raw = engine.store.hourlyModel()[anchor.toString()].orEmpty()
        val factor = Planner.calibration(history, engine.store.model(), today).factor
        raw.map { it * factor }
    }

    LaunchedEffect(range, anchor) {
        if (range == ChartRange.DAY) {
            try {
                val hours = withContext(Dispatchers.IO) { engine.hourly(anchor) }
                hourlyRaw = hours
                hourly = hourlyPoints(hours)
                hourlyError = ""
            } catch (e: Exception) {
                hourlyError = pick("Почасовите данни не се зареждат: ", "Hourly data did not load: ") + (e.message ?: e.toString())
            }
        }
        // Седмицата с днес: днешните стойности са сборът от часовете до момента.
        liveToday = if (range == ChartRange.WEEK && anchor == today) {
            try {
                withContext(Dispatchers.IO) { engine.hourly(today) }.takeIf { it.isNotEmpty() }?.let { todayBarFrom(it, today) }
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    fun toggleProfile(name: String) {
        profileHidden = if (name in profileHidden) profileHidden - name else profileHidden + name
    }

    fun toggle(name: String) {
        hidden = if (name in hidden) hidden - name else hidden + name
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(pick("Графики", "Charts"), style = MaterialTheme.typography.titleMedium)
        RangePicker(range) { range = it }
        if (range != ChartRange.ALL) PeriodNav(range, anchor, limit) { anchor = shiftEnd(anchor, range, it, limit) }
        if (range == ChartRange.DAY) {
            Text(pick("Почасово за $anchor", "Hourly for $anchor"), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(pick("Профил", "Profile"), pick("Стълбове", "Bars")).forEachIndexed { i, title ->
                    if (dayView == i) {
                        Button(onClick = { dayView = i }, modifier = Modifier.weight(1f)) { Text(title) }
                    } else {
                        OutlinedButton(onClick = { dayView = i }, modifier = Modifier.weight(1f)) { Text(title) }
                    }
                }
            }
            when {
                !engine.supports(Capability.HISTORY) -> Text(pick("Почасова история няма за тази марка.", "There is no hourly history for this brand."), style = MaterialTheme.typography.bodySmall)
                hourlyError.isNotEmpty() -> Text(hourlyError, style = MaterialTheme.typography.bodySmall)
                hourly.isEmpty() -> Text(pick("Зареждане…", "Loading…"), style = MaterialTheme.typography.bodySmall)
                dayView == 0 -> ProfileChart(hourlyRaw, profileHidden, expected, ::toggleProfile)
                else -> EnergyBars(hourly, hidden, ::toggle)
            }
        } else {
            val live = liveToday
            val points = dailyPoints(history, range, anchor).let { p ->
                if (range == ChartRange.WEEK && anchor == today && live != null) p.dropLast(1) + live else p
            }
            if (points.isEmpty()) {
                Text(pick("Още няма данни за този период.", "No data for this period yet."), style = MaterialTheme.typography.bodySmall)
            } else {
                EnergyBars(points, hidden, ::toggle)
            }
        }
    }
}
