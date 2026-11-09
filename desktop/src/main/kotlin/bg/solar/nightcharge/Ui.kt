package bg.solar.nightcharge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import bg.solar.nightcharge.core.DayRecord
import bg.solar.nightcharge.core.Erp
import bg.solar.nightcharge.core.Planner
import bg.solar.nightcharge.core.Proposal
import bg.solar.nightcharge.core.Settings
import bg.solar.nightcharge.core.Tariffs
import bg.solar.nightcharge.data.DeyeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun App(engine: Engine) {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(selectedTabIndex = tab) {
            listOf("Начало", "Статистика", "Настройки", "Данни").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                0 -> Home(engine)
                1 -> Stats(engine)
                2 -> SettingsScreen(engine)
                else -> RawScreen(engine)
            }
        }
    }
}

@Composable
private fun Home(engine: Engine) {
    val scope = rememberCoroutineScope()
    var proposal by remember { mutableStateOf(engine.store.pending()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var manualSoc by remember { mutableFloatStateOf((proposal?.targetSoc ?: 50).toFloat()) }

    fun run(done: String, block: () -> Proposal) {
        busy = true
        message = ""
        scope.launch {
            try {
                proposal = withContext(Dispatchers.IO) { block() }
                message = done
            } catch (e: Exception) {
                message = "Грешка: ${e.message ?: e}"
            } finally {
                busy = false
            }
        }
    }

    if (!engine.store.auth().configured) {
        Card(Modifier.fillMaxWidth()) {
            Text("Първо въведи данните за Deye Cloud в раздел „Настройки“.", Modifier.padding(16.dp))
        }
    }

    val p = proposal
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p == null) {
                Text("Още няма предложение.")
            } else {
                Text(
                    if (p.charge) "Зареждане до ${p.targetSoc}%" else "Без зареждане от мрежата",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "За нощта срещу ${p.forDate} · батерия при изчислението ${p.currentSoc}% · ${statusLabel(p.status)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(p.reason)
                if (p.status == Proposal.STATUS_PENDING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run("Записано в инвертора.") { engine.apply(p) } }, enabled = !busy) {
                            Text("Одобри")
                        }
                        OutlinedButton(
                            onClick = { run("Зареждането от мрежата е изключено.") { engine.reject(p) } },
                            enabled = !busy,
                        ) {
                            Text("Откажи")
                        }
                    }
                }
            }
            OutlinedButton(onClick = { run("Предложението е обновено.") { engine.refresh() } }, enabled = !busy) {
                Text("Изчисли сега")
            }
        }
    }

    if (p != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val min = engine.store.settings().minSoc
                Text("Ръчно задаване", style = MaterialTheme.typography.titleMedium)
                LabeledSlider("Цел: ${manualSoc.roundToInt()}%", manualSoc, min.toFloat()..100f, 5f) { manualSoc = it }
                OutlinedButton(
                    onClick = { run("Записано в инвертора.") { engine.apply(p, manualSoc.roundToInt()) } },
                    enabled = !busy,
                ) {
                    Text(if (manualSoc.roundToInt() > min) "Зареди до ${manualSoc.roundToInt()}% тази нощ" else "Изключи зареждането")
                }
            }
        }
    }

    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (message.isNotEmpty()) Text(message)
    Text(
        "Ако не отговориш на предложението, инверторът запазва последно записаната настройка.",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun statusLabel(status: String) = when (status) {
    Proposal.STATUS_APPLIED -> "записано в инвертора"
    Proposal.STATUS_REJECTED -> "отказано"
    Proposal.STATUS_ERROR -> "грешка"
    else -> "чака отговор"
}

@Composable
private fun Stats(engine: Engine) {
    val history = remember { engine.store.history() }
    val model = remember { engine.store.model() }
    val log = remember { engine.store.log() }
    val today = LocalDate.now(Planner.ZONE)

    if (history.isEmpty()) {
        Text("Още няма данни. Натисни „Изчисли сега“ в „Начало“, за да се изтегли историята от Deye Cloud.")
        return
    }

    val cal = Planner.calibration(history, model, today)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Корекция на прогнозата", style = MaterialTheme.typography.titleMedium)
            Text("Последни 3 дни: ${pct(cal.shortTerm)} от модела")
            Text("Последни 30 дни: ${pct(cal.longTerm)} от модела")
            Text("Използван коефициент: ${pct(cal.factor)} (по ${cal.daysUsed} дни)")
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Последни 14 дни", style = MaterialTheme.typography.titleMedium)
            TableRow("Дата", "Произв.", "Модел", "Консум.", bold = true)
            for (i in 1L until 15L) {
                val d = today.minusDays(i).toString()
                val r = history[d] ?: continue
                TableRow(d.substring(5), f1(r.pvKwh), model[d]?.let { f1(it) } ?: "–", f1(r.consKwh))
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("По месеци (kWh)", style = MaterialTheme.typography.titleMedium)
            TableRow("Месец", "Произв.", "Консум.", "Дни", bold = true)
            history.values.groupBy { it.date.substring(0, 7) }.toSortedMap(reverseOrder()).forEach { (month, days: List<DayRecord>) ->
                TableRow(month, f0(days.sumOf { it.pvKwh }), f0(days.sumOf { it.consKwh }), days.size.toString())
            }
        }
    }

    if (log.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Предложения", style = MaterialTheme.typography.titleMedium)
                TableRow("За дата", "Прогноза", "Реално", "Цел", bold = true)
                log.forEach {
                    val real = history[it.forDate]?.let { r -> f1(r.pvKwh) } ?: "–"
                    TableRow(it.forDate.substring(5), f1(it.pvKwh), real, if (it.charge) "${it.targetSoc}%" else "без")
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

@Composable
private fun SettingsScreen(engine: Engine) {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(engine.store.settings()) }
    val auth = remember { engine.store.auth() }
    var appId by remember { mutableStateOf(auth.appId) }
    var appSecret by remember { mutableStateOf(auth.appSecret) }
    var email by remember { mutableStateOf(auth.email) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var lat by remember { mutableStateOf(s.lat.toString()) }
    var lon by remember { mutableStateOf(s.lon.toString()) }
    var dayPrice by remember { mutableStateOf(s.dayPrice.toString()) }
    var nightPrice by remember { mutableStateOf(s.nightPrice.toString()) }
    var message by remember { mutableStateOf("") }
    var inverters by remember { mutableStateOf(engine.store.inverters()) }
    var selectedSn by remember { mutableStateOf(auth.deviceSn) }
    var busy by remember { mutableStateOf(false) }

    fun save() {
        s = s.copy(
            lat = lat.toDoubleOrNull() ?: s.lat,
            lon = lon.toDoubleOrNull() ?: s.lon,
            dayPrice = dayPrice.toDoubleOrNull() ?: s.dayPrice,
            nightPrice = nightPrice.toDoubleOrNull() ?: s.nightPrice,
        )
        engine.store.save(s)
        val old = engine.store.auth()
        val changed = appId != old.appId || appSecret != old.appSecret || email != old.email || password.isNotEmpty()
        if (changed) {
            engine.store.save(
                old.copy(
                    appId = appId.trim(),
                    appSecret = appSecret.trim(),
                    email = email.trim(),
                    passwordSha256 = if (password.isNotEmpty()) DeyeApi.sha256(password) else old.passwordSha256,
                    stationId = 0,
                    deviceSn = "",
                    token = "",
                    tokenExpiresAt = 0,
                ),
            )
            password = ""
        }
        }

    /** Мрежова операция, след която настройките се презареждат, защото може да ги е обновила. */
    fun runDeye(start: String, block: () -> String) {
        busy = true
        message = start
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "Грешка: ${e.message ?: e}"
            }
            s = engine.store.settings()
            inverters = engine.store.inverters()
            selectedSn = engine.store.auth().deviceSn
            busy = false
        }
    }

    Text("Deye Cloud", style = MaterialTheme.typography.titleMedium)
    Text(
        "AppId и AppSecret се създават на developer.deyecloud.com. Паролата не се пази, а само неин SHA-256 хеш.",
        style = MaterialTheme.typography.bodySmall,
    )
    Field("AppId", appId) { appId = it }
    Field("AppSecret", appSecret) { appSecret = it }
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Имейл или потребителско име в Deye Cloud") },
        singleLine = true,
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (auth.passwordSha256.isEmpty()) "Парола" else "Парола (празно = без промяна)") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = showPassword, onCheckedChange = { showPassword = it })
        Text("Покажи паролата")
    }
    OutlinedButton(onClick = {
        save()
        runDeye("Проверка…") { engine.testConnection() }
    }, enabled = !busy) {
        Text("Запази и тествай връзката")
    }

    if (inverters.size > 1) {
        Text("Инвертор", style = MaterialTheme.typography.titleMedium)
        inverters.forEach { inv ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = inv.deviceSn == selectedSn,
                    onClick = {
                        if (inv.deviceSn != selectedSn) runDeye("Избирам ${inv.deviceSn}…") { engine.select(inv) }
                    },
                    enabled = !busy,
                )
                Text(listOf(inv.stationName, inv.deviceSn).filter { it.isNotBlank() }.joinToString(" · "))
            }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (message.isNotEmpty()) Text(message)

    Text("Панели", style = MaterialTheme.typography.titleMedium)
    LabeledSlider("Мощност: ${f1(s.pvKwp)} kWp", s.pvKwp.toFloat(), 1f..30f, 0.1f) {
        s = s.copy(pvKwp = (10 * it).roundToInt() / 10.0)
    }
    LabeledSlider("Наклон: ${s.tiltDeg}°", s.tiltDeg.toFloat(), 0f..90f, 1f) { s = s.copy(tiltDeg = it.roundToInt()) }
    LabeledSlider("Посока: ${azimuthLabel(s.azimuthDeg)}", s.azimuthDeg.toFloat(), -180f..180f, 5f) {
        s = s.copy(azimuthDeg = it.roundToInt())
    }

    Text("Локация и тарифа", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Ширина", lat, Modifier.weight(1f), number = true) { lat = it }
        Field("Дължина", lon, Modifier.weight(1f), number = true) { lon = it }
    }
    Erp.entries.forEach { erp ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.erp == erp, onClick = { s = s.copy(erp = erp) })
            Text(erp.label)
        }
    }
    Text(
        "Нощна тарифа: ${Planner.hh(s.nightStartWinter)}–${Planner.hh(s.nightEndWinter)} зимно време, " +
            "${Planner.hh(s.nightStartSummer)}–${Planner.hh(s.nightEndSummer)} лятно време.",
        style = MaterialTheme.typography.bodySmall,
    )
    LabeledSlider("Начало на нощната тарифа (зима): ${Planner.hh(s.nightStartWinter)}", s.nightStartWinter.toFloat(), 20f..23f, 1f) {
        val h = it.roundToInt()
        s = s.copy(nightStartWinter = h, nightEndWinter = h - 16, nightStartSummer = minOf(23, h + 1), nightEndSummer = h - 15)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Дневна цена €/kWh", dayPrice, Modifier.weight(1f), number = true) { dayPrice = it }
        Field("Нощна цена €/kWh", nightPrice, Modifier.weight(1f), number = true) { nightPrice = it }
    }

    Text("Инвертор и батерия", style = MaterialTheme.typography.titleMedium)
    Text(
        "Мощността и капацитетът се попълват сами при „Запази и тествай връзката“, ако Deye Cloud ги върне. Провери ги и ги поправи при нужда.",
        style = MaterialTheme.typography.bodySmall,
    )
    val ratedLabel = if (s.inverterRatedW > 0) "${s.inverterRatedW} W" else "не е открита, ползва се ${Settings.DEFAULT_RATED_W} W"
    LabeledSlider("Номинална мощност на инвертора: $ratedLabel", s.ratedPowerW.toFloat(), 3000f..50000f, 500f) {
        val w = it.roundToInt()
        s = s.copy(inverterRatedW = w, chargePowerW = minOf(s.chargePowerW, w))
    }
    LabeledSlider("Капацитет: ${f1(s.batteryKwh)} kWh", s.batteryKwh.toFloat(), 5f..100f, 0.1f) {
        s = s.copy(batteryKwh = (10 * it).roundToInt() / 10.0)
    }
    LabeledSlider("Минимален заряд: ${s.minSoc}%", s.minSoc.toFloat(), 5f..50f, 1f) { s = s.copy(minSoc = it.roundToInt()) }
    LabeledSlider("Максимален заряд от мрежата: ${s.maxSoc}%", s.maxSoc.toFloat(), 50f..100f, 5f) {
        s = s.copy(maxSoc = it.roundToInt())
    }
    LabeledSlider("Мощност на зареждане: ${s.chargePowerW} W", s.chargePowerW.toFloat(), 1000f..s.ratedPowerW.toFloat(), 500f) {
        s = s.copy(chargePowerW = it.roundToInt())
    }

    Text("Решение", style = MaterialTheme.typography.titleMedium)
    LabeledSlider("Час на предложението: ${Planner.hh(s.decisionHour)}", s.decisionHour.toFloat(), 15f..21f, 1f) {
        s = s.copy(decisionHour = it.roundToInt())
    }
    LabeledSlider("Дял на консумацията през деня: ${pct(s.dayShare)}", s.dayShare.toFloat(), 0.4f..1f, 0.05f) {
        s = s.copy(dayShare = (20 * it).roundToInt() / 20.0)
    }
    LabeledSlider("Резерв над изчисления недостиг: ${s.reservePct}%", s.reservePct.toFloat(), 0f..50f, 5f) {
        s = s.copy(reservePct = it.roundToInt())
    }
    Button(onClick = {
        save()
        message = "Настройките са запазени."
    }, modifier = Modifier.fillMaxWidth()) {
        Text("Запази")
    }
}

/** Суровите ключове и стойности от Deye Cloud, с бутон за копиране. Само четене. */
@Composable
private fun RawScreen(engine: Engine) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("Натисни „Прочети“, за да се покажат суровите данни от Deye Cloud.") }
    var busy by remember { mutableStateOf(false) }

    Text("Сурови данни от Deye Cloud", style = MaterialTheme.typography.titleMedium)
    Text(
        "Показват се /station/latest и /device/latest за избрания инвертор. Нищо не се записва.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            busy = true
            scope.launch {
                text = try {
                    withContext(Dispatchers.IO) { engine.rawKeys() }
                } catch (e: Exception) {
                    "Грешка: ${e.message ?: e}"
                }
                busy = false
            }
        }, enabled = !busy) {
            Text("Прочети")
        }
        OutlinedButton(onClick = {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        }) {
            Text("Копирай")
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(text, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun azimuthLabel(az: Int) = when {
    az == 0 -> "юг"
    az == -90 -> "изток"
    az == 90 -> "запад"
    az >= 0 -> "$az° на запад от юг"
    else -> "${-az}° на изток от юг"
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
private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun pct(v: Double) = "${(100 * v).roundToInt()}%"
