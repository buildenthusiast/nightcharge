package bg.solar.nightcharge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bg.solar.nightcharge.Engine
import bg.solar.nightcharge.core.AppPrefs
import bg.solar.nightcharge.core.DayRecord
import bg.solar.nightcharge.core.Erp
import bg.solar.nightcharge.core.Facing
import bg.solar.nightcharge.core.Planner
import bg.solar.nightcharge.core.Proposal
import bg.solar.nightcharge.core.PvArray
import bg.solar.nightcharge.core.Savings
import bg.solar.nightcharge.core.Settings
import bg.solar.nightcharge.core.Tariffs
import bg.solar.nightcharge.core.TouSlot
import bg.solar.nightcharge.core.pick
import bg.solar.nightcharge.inverter.Brand
import bg.solar.nightcharge.inverter.Capability
import bg.solar.nightcharge.inverter.Live
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Живите данни се обновяват на всяка минута. */
private const val LIVE_REFRESH_MS = 60_000L

@Composable
fun App(engine: Engine) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Header()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 3) {
                // Настройките имат собствено превъртане и фиксиран бутон „Запази“ отдолу.
                SettingsScreen(engine)
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (tab) {
                        0 -> Home(engine)
                        1 -> Live(engine)
                        4 -> HelpScreen(engine)
                        else -> Stats(engine)
                    }
                }
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf(
                "⌂" to pick("Начало", "Home"),
                "⚡" to pick("Живо", "Live"),
                "▦" to pick("Статистика", "Stats"),
                "⚙" to pick("Настройки", "Settings"),
                "?" to pick("Помощ", "Help"),
            ).forEachIndexed { i, (icon, title) ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = { Text(icon, fontSize = 18.sp) },
                    label = { Text(title) },
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            "Solar Enthusiast",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Text(
            pick("Направено от Build Enthusiast · YouTube", "Made by Build Enthusiast · YouTube"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { openUri("https://www.youtube.com/@buildenthusiast") },
        )
    }
}

/** Диалог за потвърждение преди действие, което записва в инвертора. */
private data class Confirm(val text: String, val action: () -> Unit)

@Composable
private fun ConfirmDialog(confirm: Confirm?, onDismiss: () -> Unit) {
    if (confirm == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pick("Да се запише ли в инвертора?", "Write to the inverter?")) },
        text = { Text(confirm.text) },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                confirm.action()
            }) { Text(pick("Запиши", "Write")) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(pick("Откажи", "Cancel")) } },
    )
}

/** Текущите показания за таблото на „Начало“. Обновява се както „Живо“. */
@Composable
private fun Home(engine: Engine) {
    val scope = rememberCoroutineScope()
    var proposal by remember { mutableStateOf(engine.store.pending()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var manualSoc by remember { mutableFloatStateOf((proposal?.targetSoc ?: 50).toFloat()) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var live by remember { mutableStateOf<Live?>(null) }
    var liveError by remember { mutableStateOf("") }
    var undoAvailable by remember { mutableStateOf(engine.canUndo()) }
    val cloud = engine.brand().cloudName
    val canWrite = engine.supports(Capability.SCHEDULE)

    LaunchedEffect(Unit) {
        while (true) {
            try {
                live = withContext(Dispatchers.IO) { engine.live() }
                liveError = ""
            } catch (e: Exception) {
                // При грешка остават последно известните показания, а грешката се показва.
                liveError = pick("Няма връзка с $cloud: ", "No connection to $cloud: ") + (e.message ?: e.toString())
            }
            delay(LIVE_REFRESH_MS)
        }
    }

    fun run(done: String, block: () -> Proposal) {
        busy = true
        message = ""
        scope.launch {
            try {
                proposal = withContext(Dispatchers.IO) { block() }
                undoAvailable = engine.canUndo()
                message = done
            } catch (e: Exception) {
                message = pick("Грешка: ", "Error: ") + (e.message ?: e.toString())
            } finally {
                busy = false
            }
        }
    }

    ConfirmDialog(confirm) { confirm = null }

    // Таблото: три ключови числа.
    val p = proposal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(pick("Слънце сега", "Solar now"), watts(live?.pvW), Modifier.weight(1f))
        Tile("SOC", live?.let { "${it.soc}%" } ?: "–", Modifier.weight(1f))
        Tile(
            pick("Тази нощ", "Tonight"),
            p?.let { if (it.charge) "→ ${it.targetSoc}%" else pick("без", "none") } ?: "–",
            Modifier.weight(1f),
        )
    }

    if (liveError.isNotEmpty()) {
        Text(liveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    if (!engine.accountConfigured()) {
        Card(Modifier.fillMaxWidth()) {
            Text(
                pick("Първо въведи данните за $cloud в раздел „Настройки“.", "First enter your $cloud details in Settings."),
                Modifier.padding(16.dp),
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p == null) {
                Text(pick("Още няма предложение.", "No proposal yet."))
            } else {
                Text(
                    if (p.charge) pick("Зареждане до ${p.targetSoc}%", "Charge to ${p.targetSoc}%")
                    else pick("Без зареждане от мрежата", "No grid charging"),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    pick(
                        "За нощта срещу ${p.forDate} · батерия при изчислението ${p.currentSoc}% · ${statusLabel(p.status)}",
                        "For the night of ${p.forDate} · battery at calculation ${p.currentSoc}% · ${statusLabel(p.status)}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(p.reason)
                if (p.status == Proposal.STATUS_PENDING && canWrite) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                confirm = Confirm(pick("Ще се запишат часовите слотове за тази нощ.", "The time-of-use slots for tonight will be written.")) {
                                    run(pick("Записано в инвертора.", "Written to the inverter.")) { engine.apply(p) }
                                }
                            },
                            enabled = !busy,
                        ) { Text(pick("Одобри", "Approve")) }
                        OutlinedButton(
                            onClick = {
                                confirm = Confirm(pick("Зареждането от мрежата ще се изключи за тази нощ.", "Grid charging will be turned off for tonight.")) {
                                    run(pick("Зареждането от мрежата е изключено.", "Grid charging is off.")) { engine.reject(p) }
                                }
                            },
                            enabled = !busy,
                        ) { Text(pick("Откажи", "Reject")) }
                    }
                }
            }
            OutlinedButton(onClick = { run(pick("Предложението е обновено.", "Proposal refreshed.")) { engine.refresh() } }, enabled = !busy) {
                Text(pick("Изчисли сега", "Calculate now"))
            }
        }
    }

    if (undoAvailable && canWrite) {
        OutlinedButton(
            onClick = {
                confirm = Confirm(pick("Настройките на инвертора ще се върнат към тези от преди последния запис.", "The inverter settings will go back to those from before the last write.")) {
                    busy = true
                    message = pick("Връщам предишната настройка…", "Restoring the previous setting…")
                    scope.launch {
                        message = try {
                            withContext(Dispatchers.IO) { engine.undoLast() }
                            undoAvailable = false
                            pick("Предишната настройка е върната в инвертора.", "The previous setting was restored to the inverter.")
                        } catch (e: Exception) {
                            pick("Грешка: ", "Error: ") + (e.message ?: e.toString())
                        }
                        busy = false
                    }
                }
            },
            enabled = !busy,
        ) { Text(pick("Върни предишната настройка", "Undo last write")) }
    }

    if (p != null && canWrite) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val min = engine.store.settings().minSoc
                Text(pick("Ръчно задаване", "Manual setting"), style = MaterialTheme.typography.titleMedium)
                LabeledSlider(pick("Цел: ${manualSoc.roundToInt()}%", "Target: ${manualSoc.roundToInt()}%"), manualSoc, min.toFloat()..100f, 5f) { manualSoc = it }
                OutlinedButton(
                    onClick = {
                        val target = manualSoc.roundToInt()
                        confirm = Confirm(
                            if (target > min) pick("Ще се зареди до $target% тази нощ.", "Will charge to $target% tonight.")
                            else pick("Зареждането от мрежата ще се изключи.", "Grid charging will be turned off."),
                        ) {
                            run(pick("Записано в инвертора.", "Written to the inverter.")) { engine.apply(p, target) }
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(
                        if (manualSoc.roundToInt() > min) pick("Зареди до ${manualSoc.roundToInt()}% тази нощ", "Charge to ${manualSoc.roundToInt()}% tonight")
                        else pick("Изключи зареждането", "Turn charging off"),
                    )
                }
            }
        }
    }

    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (message.isNotEmpty()) Text(message)
    Text(
        pick(
            "Ако не отговориш на предложението, инверторът запазва последно записаната настройка.",
            "If you do not answer the proposal, the inverter keeps the last setting written.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun statusLabel(status: String) = when (status) {
    Proposal.STATUS_APPLIED -> pick("записано в инвертора", "written to the inverter")
    Proposal.STATUS_REJECTED -> pick("отказано", "rejected")
    Proposal.STATUS_ERROR -> pick("грешка", "error")
    else -> pick("чака отговор", "awaiting answer")
}

/** Текущите показания от Deye Cloud, които се презареждат на всяка минута. */
@Composable
private fun Live(engine: Engine) {
    var data by remember { mutableStateOf<Live?>(null) }
    var error by remember { mutableStateOf("") }
    var lastOk by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var busy by remember { mutableStateOf(false) }
    val cloud = engine.brand().cloudName

    // Данните се взимат веднага при отваряне на екрана и после на всяка минута.
    LaunchedEffect(Unit) {
        while (true) {
            busy = true
            try {
                data = withContext(Dispatchers.IO) { engine.live() }
                error = ""
                lastOk = System.currentTimeMillis()
            } catch (e: Exception) {
                error = pick("Грешка: ", "Error: ") + (e.message ?: e.toString())
            } finally {
                busy = false
                now = System.currentTimeMillis()
            }
            delay(LIVE_REFRESH_MS)
        }
    }

    val d = data
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pick("Батерия", "Battery"), style = MaterialTheme.typography.titleMedium)
            Text(
                d?.let { "${it.soc}%" } ?: "–",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(
                progress = { (d?.soc ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = DeyeGreen,
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        FlowDiagram(d)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row {
                Metric(pick("Слънце (PV)", "Solar (PV)"), watts(d?.pvW), Modifier.weight(1f))
                Metric(pick("Консумация", "Consumption"), watts(d?.loadW), Modifier.weight(1f))
            }
            Row {
                Metric(pick("Мрежа", "Grid"), watts(d?.gridW), Modifier.weight(1f))
                Metric(batteryLabel(d?.batteryW), watts(d?.batteryW), Modifier.weight(1f))
            }
        }
    }

    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    Text(freshness(lastOk, now, error, cloud), style = MaterialTheme.typography.bodySmall, color = freshnessColor(lastOk, now, error))
}

/** Колко е свежа информацията: „току-що“, „преди X мин“ или предупреждение, ако няма връзка. */
private fun freshness(lastOk: Long, now: Long, error: String, cloud: String): String {
    if (lastOk == 0L) return pick("Зареждане…", "Loading…")
    val minutes = (now - lastOk) / 60_000
    val base = if (minutes < 1) pick("обновено току-що", "updated just now") else pick("обновено преди $minutes мин", "updated $minutes min ago")
    return if (error.isNotEmpty() && minutes >= 3) pick("Няма връзка с $cloud, $base", "No connection to $cloud, $base") else "$base · " + pick("на всяка минута", "every minute")
}

@Composable
private fun freshnessColor(lastOk: Long, now: Long, error: String): Color {
    val minutes = if (lastOk == 0L) 0 else (now - lastOk) / 60_000
    return if (error.isNotEmpty() && minutes >= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun batteryLabel(v: Double?): String = when {
    v == null -> pick("Батерия", "Battery")
    v < 0 -> pick("Батерия · зарежда", "Battery · charging")
    v > 0 -> pick("Батерия · разрежда", "Battery · discharging")
    else -> pick("Батерия", "Battery")
}

private fun watts(v: Double?): String = v?.let { "${it.roundToInt()} W" } ?: "–"

@Composable
private fun Stats(engine: Engine) {
    val history = remember { engine.store.history() }
    val model = remember { engine.store.model() }
    val log = remember { engine.store.log() }
    val today = LocalDate.now(Planner.ZONE)
    var view by remember { mutableIntStateOf(0) }

    if (history.isEmpty()) {
        val cloud = engine.brand().cloudName
        Text(
            pick(
                "Още няма данни. Натисни „Изчисли сега“ в „Начало“, за да се изтегли историята от $cloud.",
                "No data yet. Tap “Calculate now” on Home to download the history from $cloud.",
            ),
        )
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(pick("Графики", "Charts"), pick("Таблици", "Tables")).forEachIndexed { i, title ->
            if (view == i) {
                Button(onClick = { view = i }, modifier = Modifier.weight(1f)) { Text(title) }
            } else {
                OutlinedButton(onClick = { view = i }, modifier = Modifier.weight(1f)) { Text(title) }
            }
        }
    }

    if (view == 0) {
        Card(Modifier.fillMaxWidth()) { EnergyChartCard(engine, history, today) }
        Card(Modifier.fillMaxWidth()) { ForecastVsActual(engine, history, today) }
        return
    }

    // Спестено (оценка): разреденото през деня по дневна цена минус купеното през нощта по нощна цена.
    val settings = engine.store.settings()
    // Историята пази всички дни, затова сумата и таблицата се ограничават до последните 30 дни.
    val from = today.minusDays(30).toString()
    val savings = engine.store.nightStats().filterKeys { it >= from }.toSortedMap(reverseOrder()).map { (day, v) ->
        day to Savings.perDay(v.first, v.second, settings.dayPrice, settings.nightPrice)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(pick("Спестено (оценка)", "Saved (estimate)"), style = MaterialTheme.typography.titleMedium)
            if (savings.isEmpty()) {
                Text(pick("Още няма данни за нощните цени. Ще се появят след следващото обновяване.", "No night price data yet. It will appear after the next refresh."))
            } else {
                Text(
                    pick("Последните 30 дни: ${f2(savings.sumOf { it.second })} €", "Last 30 days: ${f2(savings.sumOf { it.second })} €"),
                    fontWeight = FontWeight.Bold,
                )
                TableRow(pick("Дата", "Date"), pick("Спестено", "Saved"), "", "", bold = true)
                savings.forEach { (day, eur) -> TableRow(day.substring(5), f2(eur) + " €", "", "") }
            }
        }
    }

    val cal = Planner.calibration(history, model, today)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(pick("Корекция на прогнозата", "Forecast correction"), style = MaterialTheme.typography.titleMedium)
            Text(pick("Последни 3 дни: ${pct(cal.shortTerm)} от модела", "Last 3 days: ${pct(cal.shortTerm)} of model"))
            Text(pick("Последни 30 дни: ${pct(cal.longTerm)} от модела", "Last 30 days: ${pct(cal.longTerm)} of model"))
            Text(pick("Използван коефициент: ${pct(cal.factor)} (по ${cal.daysUsed} дни)", "Factor used: ${pct(cal.factor)} (${cal.daysUsed} days)"))
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(pick("Последни 14 дни", "Last 14 days"), style = MaterialTheme.typography.titleMedium)
            TableRow(pick("Дата", "Date"), pick("Произв.", "Prod."), pick("Модел", "Model"), pick("Консум.", "Cons."), bold = true)
            for (i in 1L until 15L) {
                val d = today.minusDays(i).toString()
                val r = history[d] ?: continue
                TableRow(d.substring(5), f1(r.pvKwh), model[d]?.let { f1(it) } ?: "–", f1(r.consKwh))
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(pick("По месеци (kWh)", "By month (kWh)"), style = MaterialTheme.typography.titleMedium)
            TableRow(pick("Месец", "Month"), pick("Произв.", "Prod."), pick("Консум.", "Cons."), pick("Дни", "Days"), bold = true)
            history.values.groupBy { it.date.substring(0, 7) }.toSortedMap(reverseOrder()).forEach { (month, days: List<DayRecord>) ->
                TableRow(month, f0(days.sumOf { it.pvKwh }), f0(days.sumOf { it.consKwh }), days.size.toString())
            }
        }
    }

    if (log.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(pick("Предложения", "Proposals"), style = MaterialTheme.typography.titleMedium)
                TableRow(pick("За дата", "For date"), pick("Прогноза", "Forecast"), pick("Реално", "Actual"), pick("Цел", "Target"), bold = true)
                log.forEach {
                    val real = history[it.forDate]?.let { r -> f1(r.pvKwh) } ?: "–"
                    TableRow(it.forDate.substring(5), f1(it.pvKwh), real, if (it.charge) "${it.targetSoc}%" else pick("без", "none"))
                }
            }
        }
    }
}

@Composable
private fun TableRow(a: String, b: String, c: String, d: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        val weight = if (bold) FontWeight.Bold else FontWeight.Normal
        listOf(a, b, c, d).forEach { Text(it, Modifier.weight(1f), fontWeight = weight) }
    }
}

/** Секция, която се свива и разтваря с докосване. */
@Composable
private fun CollapsibleSection(title: String, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (open) "▴" else "▾", style = MaterialTheme.typography.titleMedium)
            }
            if (open) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

@Composable
private fun SettingsScreen(engine: Engine) {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(engine.store.settings()) }
    // Избраната в падащото меню марка. Записва се едва при „Запази“.
    var brand by remember { mutableStateOf(engine.brand()) }
    // Полетата за вход на марката. Скритите (парола, токен) се показват празни: празно значи „без промяна“.
    val fields = remember(brand) {
        mutableStateMapOf<String, String>().apply {
            val saved = engine.store.account(brand)
            brand.form.forEach { put(it.key, if (it.masked) "" else saved[it.key]) }
        }
    }
    var showPassword by remember { mutableStateOf(false) }
    val cloud = brand.cloudName
    var lat by remember { mutableStateOf(s.lat.toString()) }
    var lon by remember { mutableStateOf(s.lon.toString()) }
    var dayPrice by remember { mutableStateOf(s.dayPrice.toString()) }
    var nightPrice by remember { mutableStateOf(s.nightPrice.toString()) }
    var message by remember { mutableStateOf("") }
    var inverters by remember(brand) { mutableStateOf(engine.store.inverters(brand)) }
    var selectedSn by remember(brand) { mutableStateOf(engine.store.account(brand).deviceSn) }
    var busy by remember { mutableStateOf(false) }
    var tou by remember { mutableStateOf<List<TouSlot>>(emptyList()) }
    var confirmRestore by remember { mutableStateOf(false) }
    var backupText by remember { mutableStateOf(backupLabel(engine)) }

    fun save() {
        s = s.copy(
            lat = lat.toDoubleOrNull() ?: s.lat,
            lon = lon.toDoubleOrNull() ?: s.lon,
            dayPrice = dayPrice.toDoubleOrNull() ?: s.dayPrice,
            nightPrice = nightPrice.toDoubleOrNull() ?: s.nightPrice,
        )
        engine.store.save(s)
        engine.saveBrand(brand, fields.toMap())
        // Скритите полета се изчистват след запис, за да не останат в полето.
        brand.form.filter { it.masked }.forEach { fields[it.key] = "" }
    }

    /** Мрежова операция, след която настройките се презареждат, защото може да ги е обновила. */
    fun runCloud(start: String, block: () -> String) {
        busy = true
        message = start
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                pick("Грешка: ", "Error: ") + (e.message ?: e.toString())
            }
            s = engine.store.settings()
            inverters = engine.store.inverters(brand)
            selectedSn = engine.store.account(brand).deviceSn
            busy = false
        }
    }

    /** Променя един от масивите панели по индекс. */
    fun setArray(i: Int, change: (PvArray) -> PvArray) {
        s = s.copy(arrays = s.arrays.mapIndexed { j, a -> if (j == i) change(a) else a })
    }

    ConfirmRestoreDialog(confirmRestore, onDismiss = { confirmRestore = false }) {
        confirmRestore = false
        busy = true
        message = pick("Връщам запазените настройки…", "Restoring the saved settings…")
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) { engine.restoreInverterSettings() }
                pick("Запазените настройки са върнати в инвертора.", "The saved settings were restored to the inverter.")
            } catch (e: Exception) {
                pick("Грешка: ", "Error: ") + (e.message ?: e.toString())
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CollapsibleSection(pick("Изглед и език", "Appearance and language"), startOpen = false) {
                Text(pick("Език", "Language"), style = MaterialTheme.typography.titleSmall)
                listOf("auto" to pick("Както телефона", "Same as phone"), "bg" to "Български", "en" to "English").forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = AppPrefs.language == value, onClick = {
                            engine.store.setAppPref("lang", value)
                            AppPrefs.language = value
                        })
                        Text(label)
                    }
                }
                Text(pick("Тема", "Theme"), style = MaterialTheme.typography.titleSmall)
                listOf("auto" to pick("Както телефона", "Same as phone"), "light" to pick("Светла", "Light"), "dark" to pick("Тъмна", "Dark")).forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = AppPrefs.theme == value, onClick = {
                            engine.store.setAppPref("theme", value)
                            AppPrefs.theme = value
                        })
                        Text(label)
                    }
                }
                Text(
                    pick("Смяната на език или тема презарежда приложението. Незапазените промени в настройките ще се загубят.", "Changing the language or theme restarts the app. Unsaved settings changes will be lost."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            CollapsibleSection(pick("История и спестяване", "History and savings"), startOpen = false) {
                Text(
                    pick(
                        "Историята и спестяванията се записват автоматично в Изтегляния/SolarEnthusiast всяка вечер. Файлът се запазва и при обновяване или преинсталиране на приложението.",
                        "History and savings are saved automatically to Downloads/SolarEnthusiast every evening. The file is kept when you update or reinstall the app.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    busy = true
                    message = pick("Записвам историята…", "Saving the history…")
                    scope.launch {
                        message = try {
                            withContext(Dispatchers.IO) { engine.backupToDownloads() }
                        } catch (e: Exception) {
                            pick("Грешка: ", "Error: ") + (e.message ?: e.toString())
                        }
                        busy = false
                    }
                }, enabled = !busy) {
                    Text(pick("Запиши историята сега", "Save history now"))
                }
            }

            CollapsibleSection(cloud, startOpen = false) {
                BrandDropdown(brand) { brand = it }
                Text(brandFeatures(engine.capabilitiesOf(brand)), style = MaterialTheme.typography.bodySmall)
                Text(brandHint(brand), style = MaterialTheme.typography.bodySmall)
                brand.form.forEach { f ->
                    OutlinedTextField(
                        value = fields[f.key].orEmpty(),
                        onValueChange = { fields[f.key] = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                if (f.keepIfEmpty && engine.store.account(brand)[f.key].isNotEmpty()) f.label + pick(" (празно = без промяна)", " (empty = unchanged)")
                                else f.label,
                            )
                        },
                        singleLine = true,
                        visualTransformation = if (f.masked && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = if (f.masked) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                    )
                }
                if (brand.form.any { it.masked }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showPassword, onCheckedChange = { showPassword = it })
                        Text(pick("Покажи паролата", "Show password"))
                    }
                }
                OutlinedButton(onClick = {
                    save()
                    runCloud(pick("Проверка…", "Checking…")) { engine.testConnection() }
                }, enabled = !busy) {
                    Text(pick("Запази и тествай връзката", "Save and test connection"))
                }
                if (inverters.size > 1) {
                    Text(pick("Инвертор", "Inverter"), style = MaterialTheme.typography.titleSmall)
                    inverters.forEach { inv ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = inv.deviceSn == selectedSn,
                                onClick = {
                                    if (inv.deviceSn != selectedSn) runCloud(pick("Избирам ${inv.deviceSn}…", "Selecting ${inv.deviceSn}…")) { engine.select(inv) }
                                },
                                // Избор се прави само за запазената марка; при различна избрана в менюто първо трябва „Запази“.
                                enabled = !busy && brand == engine.brand(),
                            )
                            Text(listOf(inv.stationName, inv.deviceSn).filter { it.isNotBlank() }.joinToString(" · "))
                        }
                    }
                }
            }

            CollapsibleSection(pick("Панели", "Panels"), startOpen = false) {
                Text(
                    pick(
                        "Колко различни положения имат панелите? Прогнозата се смята отделно за всяко и се сумира.",
                        "How many different panel orientations do you have? The forecast is calculated for each and summed.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    (1..Settings.MAX_ARRAYS).forEach { n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.arrays.size == n, onClick = { s = s.copy(arrays = resizeArrays(s.arrays, n)) })
                            Text("$n")
                        }
                    }
                }
                s.arrays.forEachIndexed { i, arr ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(pick("Панел ${i + 1}", "Panel ${i + 1}"), style = MaterialTheme.typography.titleSmall)
                            KwpField(arr.kwp) { v -> setArray(i) { it.copy(kwp = v) } }
                            LabeledSlider(pick("Наклон: ${arr.tiltDeg}°", "Tilt: ${arr.tiltDeg}°"), arr.tiltDeg.toFloat(), 0f..90f, 1f) { v ->
                                setArray(i) { it.copy(tiltDeg = v.roundToInt()) }
                            }
                            Text(pick("Посока", "Direction"))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Facing.entries.forEach { f ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = arr.facing == f, onClick = { setArray(i) { it.copy(facing = f) } })
                                        Text(f.label)
                                    }
                                }
                            }
                        }
                    }
                }
                Text(pick("Общо: ${f1(s.pvKwp)} kWp", "Total: ${f1(s.pvKwp)} kWp"), style = MaterialTheme.typography.bodySmall)
            }

            CollapsibleSection(pick("Локация и тарифа", "Location and tariff")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(pick("Ширина", "Latitude"), lat, Modifier.weight(1f), number = true) { lat = it }
                    Field(pick("Дължина", "Longitude"), lon, Modifier.weight(1f), number = true) { lon = it }
                }
                Erp.entries.forEach { erp ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.erp == erp, onClick = { s = s.copy(erp = erp) })
                        Text(erp.label)
                    }
                }
                Text(
                    pick(
                        "Нощна тарифа: ${Planner.hh(s.nightStartWinter)}–${Planner.hh(s.nightEndWinter)} зимно време, ${Planner.hh(s.nightStartSummer)}–${Planner.hh(s.nightEndSummer)} лятно време.",
                        "Night tariff: ${Planner.hh(s.nightStartWinter)}–${Planner.hh(s.nightEndWinter)} in winter, ${Planner.hh(s.nightStartSummer)}–${Planner.hh(s.nightEndSummer)} in summer.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                LabeledSlider(
                    pick("Начало на нощната тарифа (зима): ${Planner.hh(s.nightStartWinter)}", "Night tariff start (winter): ${Planner.hh(s.nightStartWinter)}"),
                    s.nightStartWinter.toFloat(), 20f..23f, 1f,
                ) {
                    val h = it.roundToInt()
                    s = s.copy(nightStartWinter = h, nightEndWinter = h - 16, nightStartSummer = minOf(23, h + 1), nightEndSummer = h - 15)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(pick("Дневна цена €/kWh", "Day price €/kWh"), dayPrice, Modifier.weight(1f), number = true) { dayPrice = it }
                    Field(pick("Нощна цена €/kWh", "Night price €/kWh"), nightPrice, Modifier.weight(1f), number = true) { nightPrice = it }
                }
            }

            CollapsibleSection(pick("Инвертор и батерия", "Inverter and battery")) {
                Text(
                    pick(
                        "Мощността и капацитетът се попълват сами при „Запази и тествай връзката“, ако $cloud ги върне. Провери ги и ги поправи при нужда.",
                        "Power and capacity are filled in automatically by “Save and test connection” if $cloud returns them. Check and correct them if needed.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                val ratedLabel = if (s.inverterRatedW > 0) "${s.inverterRatedW} W"
                else pick("не е открита, ползва се ${Settings.DEFAULT_RATED_W} W", "not found, using ${Settings.DEFAULT_RATED_W} W")
                LabeledSlider(pick("Номинална мощност на инвертора: $ratedLabel", "Inverter rated power: $ratedLabel"), s.ratedPowerW.toFloat(), 3000f..50000f, 500f) {
                    val w = it.roundToInt()
                    s = s.copy(inverterRatedW = w, chargePowerW = minOf(s.chargePowerW, w))
                }
                LabeledSlider(pick("Капацитет: ${f1(s.batteryKwh)} kWh", "Capacity: ${f1(s.batteryKwh)} kWh"), s.batteryKwh.toFloat(), 5f..100f, 0.1f) {
                    s = s.copy(batteryKwh = (10 * it).roundToInt() / 10.0)
                }
                LabeledSlider(pick("Минимален заряд: ${s.minSoc}%", "Minimum charge: ${s.minSoc}%"), s.minSoc.toFloat(), 5f..50f, 1f) {
                    s = s.copy(minSoc = it.roundToInt())
                }
                LabeledSlider(pick("Максимален заряд от мрежата: ${s.maxSoc}%", "Maximum charge from grid: ${s.maxSoc}%"), s.maxSoc.toFloat(), 50f..100f, 5f) {
                    s = s.copy(maxSoc = it.roundToInt())
                }
                LabeledSlider(pick("Мощност на зареждане: ${s.chargePowerW} W", "Charge power: ${s.chargePowerW} W"), s.chargePowerW.toFloat(), 1000f..s.ratedPowerW.toFloat(), 500f) {
                    s = s.copy(chargePowerW = it.roundToInt())
                }

                if (engine.supports(Capability.SCHEDULE)) {
                    Text(pick("Текущи настройки на инвертора", "Current inverter settings"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        pick(
                            "Прочети какво е записано в инвертора и го запази. Така, ако натиснеш нещо по погрешка, можеш да го върнеш.",
                            "Read what is written in the inverter and save it. If you press something by mistake, you can restore it.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        busy = true
                        message = pick("Чета настройките на инвертора…", "Reading the inverter settings…")
                        scope.launch {
                            try {
                                tou = withContext(Dispatchers.IO) { engine.readInverterSettings() }
                                message = pick("Прочетени са ${tou.size} слота и е запазено резервно копие.", "Read ${tou.size} slots and saved a backup.")
                                backupText = backupLabel(engine)
                            } catch (e: Exception) {
                                message = pick("Грешка: ", "Error: ") + (e.message ?: e.toString())
                            } finally {
                                busy = false
                            }
                        }
                    }, enabled = !busy) {
                        Text(pick("Прочети текущите настройки на инвертора", "Read current inverter settings"))
                    }
                    tou.forEach { t ->
                        Text(
                            pick(
                                "${t.time} · SOC ${t.soc}% · мрежово зареждане ${if (t.gridCharge) "да" else "не"} · ${t.powerW} W",
                                "${t.time} · SOC ${t.soc}% · grid charge ${if (t.gridCharge) "yes" else "no"} · ${t.powerW} W",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(backupText, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { confirmRestore = true }, enabled = !busy) {
                        Text(pick("Върни запазените настройки в инвертора", "Restore saved settings to the inverter"))
                    }
                }
            }

            CollapsibleSection(pick("Решение", "Decision")) {
                LabeledSlider(
                    pick("Час на предложението: ${Planner.hh(s.decisionHour)}", "Proposal time: ${Planner.hh(s.decisionHour)}"),
                    s.decisionHour.toFloat(), 15f..21f, 1f,
                ) {
                    s = s.copy(decisionHour = it.roundToInt())
                }
                Text(pick("Когато прогнозата е готова:", "When the forecast is ready:"), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !s.autoApply, onClick = { s = s.copy(autoApply = false) })
                    Text(pick("Питай ме с известие (Одобри / Откажи)", "Ask me with a notification (Approve / Reject)"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.autoApply, onClick = { s = s.copy(autoApply = true) })
                    Text(pick("Записвай автоматично в инвертора", "Write to the inverter automatically"))
                }
                LabeledSlider(pick("Дял на консумацията през деня: ${pct(s.dayShare)}", "Share of consumption during the day: ${pct(s.dayShare)}"), s.dayShare.toFloat(), 0.4f..1f, 0.05f) {
                    s = s.copy(dayShare = (20 * it).roundToInt() / 20.0)
                }
                LabeledSlider(pick("Резерв над изчисления недостиг: ${s.reservePct}%", "Reserve above calculated shortfall: ${s.reservePct}%"), s.reservePct.toFloat(), 0f..50f, 5f) {
                    s = s.copy(reservePct = it.roundToInt())
                }
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message)
        }

        // Фиксиран бутон „Запази“, който е винаги видим долу.
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
            Button(onClick = {
                save()
                message = pick("Настройките са запазени.", "Settings saved.")
            }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(pick("Запази", "Save"))
            }
        }
    }
}

@Composable
private fun ConfirmRestoreDialog(open: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pick("Да върна ли настройките?", "Restore the settings?")) },
        text = { Text(pick("Часовите слотове в инвертора ще се заменят със запазените.", "The time-of-use slots in the inverter will be replaced with the saved ones.")) },
        confirmButton = { Button(onClick = onConfirm) { Text(pick("Върни", "Restore")) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(pick("Откажи", "Cancel")) } },
    )
}

/** Падащото меню с марките инвертори. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrandDropdown(selected: Brand, onSelect: (Brand) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
            label = { Text(pick("Марка инвертор", "Inverter brand")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Brand.entries.forEach { b ->
                DropdownMenuItem(
                    text = { Text(b.label) },
                    onClick = {
                        onSelect(b)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Какво може да се прави с избраната марка, за да се знае още преди запис. */
private fun brandFeatures(caps: Set<Capability>): String {
    val items = buildList {
        add(pick("показания", "readings"))
        if (Capability.DISCOVER in caps) add(pick("избор на инвертор", "inverter selection"))
        if (Capability.HISTORY in caps) add(pick("история", "history"))
        if (Capability.SCHEDULE in caps) add(pick("запис на зареждане от мрежата", "grid-charge schedule"))
    }
    return pick("Какво поддържа: ", "Supports: ") + items.joinToString(", ") + "."
}

private fun brandHint(brand: Brand): String = when (brand) {
    Brand.DEYE -> pick(
        "AppId и AppSecret се създават на developer.deyecloud.com. Паролата не се пази, а само неин SHA-256 хеш.",
        "Create AppId and AppSecret at developer.deyecloud.com. The password is not stored, only its SHA-256 hash.",
    )
    Brand.GROWATT -> pick(
        "Growatt още е в проверка: адресите на облака не са тествани с реален акаунт. Уредът е само за четене.",
        "Growatt is still being verified: the cloud addresses have not been tested with a real account. The device is read-only.",
    )
    Brand.VICTRON -> pick(
        "Access token се създава във VRM портала (Preferences → Integrations → Access tokens). Уредът е само за четене.",
        "Create the access token in the VRM portal (Preferences → Integrations → Access tokens). The device is read-only.",
    )
}

/** Мощността на панел се въвежда с цифри, не със скрол. Текстът се пази локално, за да може да се пише „1.“ без да се сбие. */
@Composable
private fun KwpField(value: Double, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(f1(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.replace(',', '.')
            text.toDoubleOrNull()?.takeIf { it > 0 }?.let(onValue)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(pick("Мощност на панелите", "Panel power")) },
        suffix = { Text("kWp") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun backupLabel(engine: Engine): String {
    val (_, savedAt) = engine.store.touBackup() ?: return pick("Още няма запазено копие.", "No backup saved yet.")
    val when_ = java.time.Instant.ofEpochMilli(savedAt).atZone(Planner.ZONE).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    return pick("Запазено копие от $when_.", "Backup saved on $when_.")
}

/** Задава броя масиви. Новите получават нарастваща по посока стойност по подразбиране. */
private fun resizeArrays(list: List<PvArray>, n: Int): List<PvArray> {
    if (n <= list.size) return list.take(n)
    val extra = (list.size until n).map { i -> PvArray(kwp = 4.0, tiltDeg = 17, facing = Facing.entries[i % Facing.entries.size]) }
    return list + extra
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
    )
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, onChange: (Float) -> Unit) {
    Column {
        Text(label)
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = maxOf(0, ((range.endInclusive - range.start) / step).roundToInt() - 1),
        )
    }
}

private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun pct(v: Double) = "${(100 * v).roundToInt()}%"
