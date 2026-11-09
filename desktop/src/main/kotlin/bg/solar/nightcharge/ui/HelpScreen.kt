package bg.solar.nightcharge.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bg.solar.nightcharge.Engine
import bg.solar.nightcharge.core.pick
import bg.solar.nightcharge.inverter.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Един код за грешка от официалното ръководство. [source] е моделът, за който важи кодът. */
data class FaultInfo(val code: String, val meaning: String, val steps: List<String>, val source: String = "")

/**
 * Кодовете за грешка на Deye. Обобщени от „Chart 7-1 Fault information“ (стр. 42–44) в ръководството
 * за SUN-5/25K-SG01HP3. Други модели може да имат различни кодове. W-кодове няма, защото не са намерени в официален източник.
 */
private val DEYE_FAULTS: List<FaultInfo> = listOf(
    FaultInfo("F01", "DC_Inversed_Failure: обърнат полюс на PV входа.", listOf("Провери полярността на PV входа.", "Ако не се оправи, свържи се с поддръжката.")),
    FaultInfo("F07", "DC_START_Failure: шината не може да се захрани от PV или батерия.", listOf("Рестартирай инвертора.", "Ако продължава, свържи се с поддръжката.")),
    FaultInfo("F13", "Working_Mode_change: промяна на работния режим, типа мрежа или батерията.", listOf("Обикновено се оправя сам.", "Ако не, изключи DC и AC за една минута и ги включи пак.")),
    FaultInfo("F15", "AC_OverCurr_SW_Failure: претоварване по AC страната.", listOf("Провери мощността на резервния товар.", "Рестартирай инвертора.")),
    FaultInfo("F16", "GFCI_Failure: теч на ток.", listOf("Провери заземяването на PV кабелите.", "Рестартирай 2–3 пъти.")),
    FaultInfo("F18", "Tz_Ac_OverCurr_Fault: претоварване по AC.", listOf("Провери мощността на товара.", "Рестартирай инвертора.")),
    FaultInfo("F20", "Tz_Dc_OverCurr_Fault: претоварване по DC.", listOf("Провери PV и батерията.", "При режим без мрежа намали големите пускови товари.")),
    FaultInfo("F21", "Tz_HV_Overcurr_fault: претоварване на шината.", listOf("Провери тока на PV входа и настройките на батерията.")),
    FaultInfo("F22", "Tz_EmergStop_Fault: дистанционно спиране.", listOf("Инверторът се управлява отдалечено. Провери дали спирането е нарочно.")),
    FaultInfo("F23", "Tz_GFCI_OC_Fault: теч на ток.", listOf("Провери заземяването на PV кабелите.")),
    FaultInfo("F24", "DC_Insulation_Fault: ниско изолационно съпротивление на PV.", listOf("Провери PV връзките и PE заземяването.")),
    FaultInfo("F26", "BusUnbalance_Fault: дисбаланс на шината.", listOf("Изчакай, провери пак и рестартирай.")),
    FaultInfo("F29", "Parallel_Comm_Fault: авария в паралелната комуникация.", listOf("Провери кабела за паралелна комуникация и адреса на всеки блок.")),
    FaultInfo("F34", "AC_Overload_Fault: претоварване на резервния товар.", listOf("Провери дали резервният товар е в допустимите граници.")),
    FaultInfo("F41", "Parallel_system_Stop: спрял е паралелен блок.", listOf("Ако един блок е спрял, всички показват F41. Провери статуса на блоковете.")),
    FaultInfo("F42", "Parallel_Version_Fault: различен фърмуер в блоковете.", listOf("Провери дали фърмуерът на всички блокове е един и същ.", "За обновяване се свържи с поддръжката.")),
    FaultInfo("F47", "AC_OverFreq_Fault: честотата на мрежата е извън диапазона.", listOf("Провери AC кабелите.")),
    FaultInfo("F48", "AC_UnderFreq_Fault: честотата на мрежата е извън диапазона.", listOf("Същите действия като при F47.")),
    FaultInfo("F52", "DC_VoltHigh_Fault: напрежението на шината е твърде високо.", listOf("Провери батерията и напрежението на PV.")),
    FaultInfo("F53", "DC_VoltLow_Fault: напрежението на шината е твърде ниско.", listOf("Провери батерията.", "Ако е празна, зареди я от PV или мрежата.")),
    FaultInfo("F54", "BAT2_VoltHigh_Fault: напрежението на батерия 2 е твърде високо.", listOf("Провери напрежението на батерия 2.", "Рестартирай два пъти, после възстанови фабричните настройки.")),
    FaultInfo("F55", "BAT1_VoltHigh_Fault: напрежението на батерия 1 е твърде високо.", listOf("Провери напрежението на батерия 1.", "Рестартирай два пъти, после възстанови фабричните настройки.")),
    FaultInfo("F56", "BAT1_VoltLow_Fault: напрежението на батерия 1 е твърде ниско.", listOf("Провери напрежението на батерия 1.", "Рестартирай два пъти, после възстанови фабричните настройки.")),
    FaultInfo("F57", "BAT2_VoltLow_Fault: напрежението на батерия 2 е твърде ниско.", listOf("Провери напрежението на батерия 2.", "Рестартирай два пъти, после възстанови фабричните настройки.")),
    FaultInfo("F58", "Battery_comm_Lose: загубена връзка с BMS, докато BMS_Err-Stop е активен.", listOf("Може да се изключи от LCD дисплея.")),
    FaultInfo("F62", "DRMs0_stop: DRM функция, приложима само за Австралия.", listOf("Провери дали е активна.")),
    FaultInfo("F63", "ARC_Fault: детекция на дъгов разряд, приложима само за САЩ.", listOf("Провери PV кабелите.")),
    FaultInfo("F64", "Heatsink_HighTemp_Fault: радиаторът е твърде горещ.", listOf("Провери околната температура.", "Изключи инвертора за 10 минути.")),
)

private const val GROWATT_TL3 = "Growatt TL3 (GR-UM-140-A)"
private const val GROWATT_10_20 = "Growatt 10–20kUE (ver. 1.0)"

/** Growatt: кодовете се различават по модел. Всеки код носи модела, от който е. Таблиците са парафраза. */
private val GROWATT_FAULTS: List<FaultInfo> = listOf(
    FaultInfo("124", "Няма AC връзка.", listOf("Провери AC кабелите и AC прекъсвача."), GROWATT_TL3),
    FaultInfo("PV Isolation Low", "Лоша изолация на PV страната.", listOf("Провери заземяването на панелите и инвертора.", "Провери за влага в DC прекъсвача.", "Измери PV+ и PV− спрямо земята: над 50 kΩ (или 1000 kΩ по VDE 0126)."), GROWATT_TL3),
    FaultInfo("126", "Residual I High: теч на ток.", listOf("Рестартирай.", "Ако продължава, свържи се с Growatt."), GROWATT_TL3),
    FaultInfo("128", "PV напрежението е над максималното.", listOf("Изключи DC веднага.", "Измери напрежението на всеки низ.", "Ако е под 1000 V, свържи се с Growatt."), GROWATT_TL3),
    FaultInfo("125", "Auto Test Failed.", listOf("Рестартирай и повтори теста."), GROWATT_TL3),
    FaultInfo("129", "AC V Outrange: напрежението на AC е извън диапазона.", listOf("Изключи DC.", "Провери AC кабелите, особено нулата и заземяването.", "Провери напрежението спрямо местните стандарти."), GROWATT_TL3),
    FaultInfo("130", "AC F Outrange: честотата на AC е извън диапазона.", listOf("Същите стъпки като при 129, но за честотата на мрежата."), GROWATT_TL3),
    FaultInfo("103", "Предупреждение: не е уточнено.", listOf("Рестартирай.", "Ако продължава, смени COM платката."), GROWATT_TL3),
    FaultInfo("104", "Предупреждение: несъвместима версия на фърмуера.", listOf("Обнови фърмуера."), GROWATT_TL3),
    FaultInfo("105", "Предупреждение: грешка при запис в EEPROM.", listOf("Рестартирай.", "Ако продължава, смени COM платката."), GROWATT_TL3),
    FaultInfo("108", "Предупреждение: късо съединение на PV1 или PV2.", listOf("Провери полярността на панелите.", "Рестартирай."), GROWATT_TL3),
    FaultInfo("109", "Предупреждение: грешка на boost на PV1 или PV2.", listOf("Рестартирай.", "Ако продължава, смени силовата платка."), GROWATT_TL3),
    FaultInfo("101", "Вътрешна грешка: загуба на данни от управляващата платка за 10 s.", listOf("Рестартирай, после обнови фърмуера или се свържи с Growatt."), GROWATT_TL3),
    FaultInfo("107", "Вътрешна грешка: несъвпадение на AC напрежението между MCU.", listOf("Рестартирай, после обнови фърмуера или се свържи с Growatt."), GROWATT_TL3),
    FaultInfo("116", "Вътрешна грешка: bus sample.", listOf("Рестартирай, после обнови фърмуера или се свържи с Growatt."), GROWATT_TL3),
    FaultInfo("117", "Вътрешна грешка: relay.", listOf("Рестартирай, после обнови фърмуера или се свържи с Growatt."), GROWATT_TL3),
    FaultInfo("119", "Вътрешна грешка: GFCI.", listOf("Рестартирай, после обнови фърмуера или се свържи с Growatt."), GROWATT_TL3),
    FaultInfo("120", "Вътрешна грешка: дисбаланс на тока.", listOf("Рестартирай, после обнови фърмуера или се свържи с Growatt."), GROWATT_TL3),
    FaultInfo("121", "Вътрешна грешка: загуба на данни от COM платката за 5 s.", listOf("Рестартирай, после обнови фърмуера или се свържи с Growatt."), GROWATT_TL3),
    FaultInfo("122", "Вътрешна грешка: пренапрежение на bus.", listOf("Рестартирай, после обнови фърмуера или се свържи с Growatt."), GROWATT_TL3),
    FaultInfo("127", "Вътрешна грешка: Output high DCI.", listOf("Рестартирай, после обнови фърмуера или се свържи с Growatt."), GROWATT_TL3),
    FaultInfo("131", "Прегряване, когато околната температура е под 60 °C.", listOf("Рестартирай."), GROWATT_TL3),
    FaultInfo("100", "Предупреждение: вентилатор.", emptyList(), GROWATT_10_20),
    FaultInfo("103", "Предупреждение: EEPROM read.", emptyList(), GROWATT_10_20),
    FaultInfo("104", "Предупреждение: несъвместим фърмуер. Грешка на модела.", emptyList(), GROWATT_10_20),
    FaultInfo("105", "Предупреждение: EEPROM write.", emptyList(), GROWATT_10_20),
    FaultInfo("101", "Грешка: COM не получава данни от control 10 s.", emptyList(), GROWATT_10_20),
    FaultInfo("107", "Грешка: голямо разминаване в семплирането.", emptyList(), GROWATT_10_20),
    FaultInfo("117", "Грешка: relay, 6 неуспешни проверки.", emptyList(), GROWATT_10_20),
    FaultInfo("121", "Грешка: control не получава данни от COM 5 s.", emptyList(), GROWATT_10_20),
    FaultInfo("122", "Грешка: bus напрежението е твърде високо.", emptyList(), GROWATT_10_20),
    FaultInfo("119", "Грешка: GFCI.", emptyList(), GROWATT_10_20),
    FaultInfo("126", "Residual I High.", emptyList(), GROWATT_10_20),
    FaultInfo("125", "Auto Test Failed. PV Isolation Low: над 1 MΩ.", emptyList(), GROWATT_10_20),
    FaultInfo("128", "PV Voltage High: над 1000 V.", emptyList(), GROWATT_10_20),
    FaultInfo("127", "Output High DCI.", emptyList(), GROWATT_10_20),
    FaultInfo("129", "AC V Outrange.", emptyList(), GROWATT_10_20),
    FaultInfo("130", "AC F Outrange.", emptyList(), GROWATT_10_20),
)

private const val VICTRON_VEBUS = "Victron MultiPlus-II GX (VE.Bus)"

/** Victron: VE.Bus кодове (MultiPlus, Quattro). Не са кодовете на инвертор Deye. Парафраза. */
private val VICTRON_FAULTS: List<FaultInfo> = listOf(
    FaultInfo("1", "Устройството се изключи, защото друга фаза в системата се е изключила.", listOf("Намери и провери фазата, която е отказала."), VICTRON_VEBUS),
    FaultInfo("3", "Открити са повече или по-малко устройства от очакваните.", listOf("Преконфигурирай системата.", "Ако продължава, провери комуникационните кабели, после изключи и включи всичко."), VICTRON_VEBUS),
    FaultInfo("4", "Не е открито друго устройство.", listOf("Провери комуникационните кабели."), VICTRON_VEBUS),
    FaultInfo("5", "Напрежението на AC изхода е твърде високо.", listOf("Провери AC кабелите."), VICTRON_VEBUS),
    FaultInfo("10", "Грешка при синхронизация на времето в системата. Не трябва да се случва при правилен монтаж.", listOf("Провери комуникационните кабели."), VICTRON_VEBUS),
    FaultInfo("14", "Устройството не може да предава данни.", listOf("Провери комуникационните кабели за късо съединение."), VICTRON_VEBUS),
    FaultInfo("17", "Устройство стана master, след като оригиналният master отказа.", listOf("Провери отказалото устройство и комуникационните кабели."), VICTRON_VEBUS),
    FaultInfo("18", "Настъпило е пренапрежение.", listOf("Провери AC кабелите."), VICTRON_VEBUS),
    FaultInfo("22", "Устройството не може да работи като slave. Остарял, неподходящ модел.", listOf("Смени устройството."), VICTRON_VEBUS),
    FaultInfo("24", "Задейства се защита при превключване. Не трябва да се случва при правилен монтаж.", listOf("Изключи и включи всичко.", "Ако се повтаря, прегледай монтажа.", "Може да вдигнеш долния AC вход до 210 VAC (фабрично 180 VAC)."), VICTRON_VEBUS),
    FaultInfo("25", "Несъвместим фърмуер: фърмуерът на едно устройство е твърде стар за останалите.", listOf("Изключи всичко, включи устройството с грешката, после добавяй останалите едно по едно.", "Обнови фърмуера на последното включено."), VICTRON_VEBUS),
    FaultInfo("26", "Вътрешна грешка. Не трябва да се случва.", listOf("Изключи и включи всичко.", "Ако продължава, свържи се с Victron."), VICTRON_VEBUS),
)

/** Марката на батерията. Може да е различна от марката на инвертора. */
enum class BatteryBrand(val label: String) {
    DEYE("Deye"),
    GROWATT("Growatt"),
    VICTRON("Victron"),
    BYD("BYD"),
    PYLONTECH("Pylontech"),
    DYNESS("Dyness"),
    SEPLOS("Seplos"),
}

private const val DEYE_BATTERY = "Deye SE-G5.1 Pro-B, Issue 03 (обобщение)"
private const val GROWATT_ARK = "Growatt ARK 5.1–25.6XH-A1, GR-UM-242-A-01 (обобщение)"
private const val VICTRON_LYNX = "Victron Lynx Smart BMS (обобщение)"
private const val VICTRON_VEBUS_BMS = "Victron VE.Bus BMS NG (обобщение)"
private const val BYD_BATTERY = "BYD Battery-Box Premium HVS/HVM, BCU V2.0, Service Guideline V1.1 (обобщение)"
private const val PYLON_US3000C = "Pylontech US3000C, Information Version 1.1 (обобщение)"
private const val PYLON_FORCE_H2 = "Pylontech Force-H2 V2, Operation Manual 22P2FHB1018 (обобщение)"
private const val DYNESS_POWERBOX = "Dyness Powerbox Pro, 51.2 V / 200 Ah, manual 2024-11-14 (обобщение)"
private const val SEPLOS_BMS = "Seplos EMU10XX, MODBUS-ASCII V2.0, битовете на предупрежденията (обобщение)"

/** Битове на Seplos: Warning 1–6, без резервните. Текстът е обобщение, стъпки няма. */
private fun seplos(warning: Int, bit: Int, text: String) = FaultInfo("Warning $warning, бит $bit", text, emptyList(), SEPLOS_BMS)

/**
 * Батериите. Deye SE-G, Growatt ARK, Pylontech Force-H2 и Dyness нямат числови кодове, затова се търсят по символ или защита.
 * Всички са обобщения от ръководствата. Кодовете важат за модела, посочен при всеки ред.
 */
private val BATTERY_ITEMS: Map<BatteryBrand, List<FaultInfo>> = mapOf(
    BatteryBrand.DEYE to listOf(
        FaultInfo("RUN мига бързо", "Обновяване на фърмуера (LED).", listOf("Изчакай."), DEYE_BATTERY),
        FaultInfo("RUN мига бавно", "Критична грешка (LED).", listOf("Свържи се със сервиз."), DEYE_BATTERY),
        FaultInfo("ALM мига", "Аларма при разреждане или покой (LED).", listOf("Провери алармата в мониторинг софтуера."), DEYE_BATTERY),
        FaultInfo("ERROR свети", "Грешка или защита (LED).", listOf("Виж режима на защита в мониторинг софтуера и раздел 8 в ръководството."), DEYE_BATTERY),
        FaultInfo("BMS неизправност", "Отказ на веригата за напрежение или температура на клетките: разхлабена връзка, изгорял предпазител или неработещ сензор.", listOf("Смени батерията."), DEYE_BATTERY),
        FaultInfo("Неизправност на клетка", "Ниско или небалансирано напрежение на клетка: разреждане под 2.0 V след дълго съхранение или механична повреда.", listOf("Смени батерията."), DEYE_BATTERY),
        FaultInfo("Защита от свръхнапрежение", "Клетка над 3.65 V при зареждане или пакет над 58.4 V.", listOf("Ако не се възстановява, свържи се с техник."), DEYE_BATTERY),
        FaultInfo("Защита от ниско напрежение", "Пакет под 40 V или най-ниска клетка под 2.5 V.", listOf("Ако не се възстановява, свържи се с техник."), DEYE_BATTERY),
        FaultInfo("Защита от висока температура", "Най-гореща клетка над 60 °C.", listOf("Ако не се възстановява, свържи се с техник."), DEYE_BATTERY),
        FaultInfo("Защита при ниска температура при зареждане", "Най-студена клетка под 0 °C.", listOf("Ако не се възстановява, свържи се с техник."), DEYE_BATTERY),
        FaultInfo("Защита при ниска температура при разреждане", "Най-студена клетка под −20 °C.", listOf("Ако не се възстановява, свържи се с техник."), DEYE_BATTERY),
    ),
    BatteryBrand.GROWATT to listOf(
        FaultInfo("ALM мига: ниско напрежение при разреждане", "Клетка под прага.", listOf("Спри разреждането и зареди."), GROWATT_ARK),
        FaultInfo("ALM мига: високо напрежение при зареждане", "Клетка над прага.", listOf("Спри зареждането. Системата се оправя сама."), GROWATT_ARK),
        FaultInfo("ALM мига: RS485 комуникация", "Загубена връзка инвертор–батерия.", listOf("Спри батерията, провери клемите, после се обади на инсталатор."), GROWATT_ARK),
        FaultInfo("ALM мига: CAN комуникация", "Загубена връзка между пакетите.", listOf("Провери кабелите между пакетите и контролера."), GROWATT_ARK),
        FaultInfo("ALM мига: висока температура", "Над лимита на BMS.", listOf("Спри веднага и остави да изстине. Оправя се сама."), GROWATT_ARK),
        FaultInfo("ALM мига: ниска температура", "Под лимита на BMS.", listOf("Няма риск. Остави да се затопли."), GROWATT_ARK),
        FaultInfo("ALM свети: късо съединение при разреждане", "Външно късо съединение.", listOf("Спри и повикай инсталатор за ремонт."), GROWATT_ARK),
        FaultInfo("ALM свети: предзареждане", "Къс на предзарядното устройство или таймаут.", listOf("Опасност. Спри и повикай инсталатор."), GROWATT_ARK),
        FaultInfo("ALM свети: семплиране на напрежението", "Отказ на BMS.", listOf("Опасност. Спри и повикай инсталатор."), GROWATT_ARK),
        FaultInfo("ALM свети: семплиране на тока", "Отказ на BMS.", listOf("Опасност. Спри и повикай инсталатор."), GROWATT_ARK),
        FaultInfo("ALM свети: основна верига", "Неизправност в основната верига, отказ на BMS.", listOf("Опасност. Спри и повикай инсталатор."), GROWATT_ARK),
        FaultInfo("ALM свети: EEPROM", "BMS не чете данните.", listOf("Опасност. Спри и повикай инсталатор."), GROWATT_ARK),
        FaultInfo("ALM свети: Chip", "Загуба на връзка между два MCU.", listOf("Опасност. Спри и повикай инсталатор."), GROWATT_ARK),
    ),
    BatteryBrand.VICTRON to listOf(
        FaultInfo("W-B01", "Ниско напрежение на клетка.", listOf("Зареди или намали товара, за да се избегне спиране."), VICTRON_LYNX),
        FaultInfo("W-B02", "Висок ток.", listOf("Намали тока."), VICTRON_LYNX),
        FaultInfo("W-B03", "Висока температура на BMS.", listOf("Провери околната температура и вентилаторите.", "Ако вентилаторите не работят, виж дилъра на Victron."), VICTRON_LYNX),
        FaultInfo("W-B04", "Лош контактор.", listOf("Намали тока.", "Виж дилъра на Victron."), VICTRON_LYNX),
        FaultInfo("W-B06", "Товарът ще се изключи.", listOf("Товарите се изключват след 30 s, ако причината не се оправи."), VICTRON_LYNX),
        FaultInfo("W-B07", "Ниско състояние на заряда.", listOf("Зареди или намали товара."), VICTRON_LYNX),
        FaultInfo("W-B10", "VE.CAN е прекъснат.", listOf("Провери комуникационния кабел."), VICTRON_LYNX),
        FaultInfo("W-D01 до W-D08", "Загубена връзка с дистрибутор от A до H.", listOf("Провери кабела между BMS и дистрибутора."), VICTRON_LYNX),
        FaultInfo("A-B01", "Ниско напрежение на клетка.", listOf("Зареди. Товарите се включват, когато има достатъчно заряд."), VICTRON_LYNX),
        FaultInfo("A-B02", "Висок ток.", listOf("Намали тока или изключи част от товарите. Системата опитва отново след 5 минути."), VICTRON_LYNX),
        FaultInfo("A-B06", "Товарът е изключен.", listOf("Зареди батерията. Ако не се оправи, контакторът се отваря."), VICTRON_LYNX),
        FaultInfo("A-B07", "Ниско състояние на заряда.", listOf("Зареди."), VICTRON_LYNX),
        FaultInfo("A-F01 до A-F32", "Предпазител от 1 до 32 е изгорял.", listOf("Провери системата и смени предпазителя. Имената се виждат във VictronConnect."), VICTRON_LYNX),
        FaultInfo("E-B01", "Грешка при инициализация на батерията.", listOf("Провери свързването на всички батерии."), VICTRON_LYNX),
        FaultInfo("E-B02", "Няма открита батерия.", listOf("Провери дали кабелът на BMS е затворен в контур."), VICTRON_LYNX),
        FaultInfo("E-B05", "Невалиден брой батерии.", listOf("Провери свързването."), VICTRON_LYNX),
        FaultInfo("E-B09", "Напрежението на батерията не е позволено: извън 9–60 V.", listOf("Провери напрежението и настройките."), VICTRON_LYNX),
        FaultInfo("E-B11", "Хардуерна грешка.", listOf("Виж дилъра на Victron."), VICTRON_LYNX),
        FaultInfo("E-B25", "Грешка при предварително зареждане.", listOf("Съпротивлението на товара е твърде ниско. Изключи част от товарите."), VICTRON_LYNX),
        FaultInfo("E-B26", "Грешка на контактора.", listOf("Виж дилъра на Victron."), VICTRON_LYNX),
        FaultInfo("E-B32", "Грешка на BMS кабела.", listOf("Провери M8 връзките на батерията."), VICTRON_LYNX),
        FaultInfo("E-B34", "Грешно системно напрежение.", listOf("Провери настройките."), VICTRON_LYNX),
        FaultInfo("E-B35", "Изтекло време за предварително зареждане.", listOf("Товарът е твърде голям. Изключи част от DC товарите."), VICTRON_LYNX),
        FaultInfo("E-B36", "Грешка на ATC/ATD.", listOf("Провери кабелите ATC/ATD."), VICTRON_LYNX),
        FaultInfo("E-B116", "Калибровъчните данни са загубени.", listOf("Виж дилъра на Victron."), VICTRON_LYNX),
        FaultInfo("E-B119", "Настройките са загубени.", listOf("Върни фабричните настройки."), VICTRON_LYNX),
        FaultInfo("W-B05", "Загубена комуникация с батерията.", listOf("Провери кабелите."), VICTRON_VEBUS_BMS),
        FaultInfo("W-B11", "Cellguard фърмуерът не може да се обнови.", listOf("Нужен е цикъл на захранване."), VICTRON_VEBUS_BMS),
        FaultInfo("A-B05", "Загубена комуникация.", emptyList(), VICTRON_VEBUS_BMS),
        FaultInfo("A-B09", "Висока температура на батерията: зареждането е блокирано.", emptyList(), VICTRON_VEBUS_BMS),
        FaultInfo("A-B13", "Ниска температура на батерията.", emptyList(), VICTRON_VEBUS_BMS),
        FaultInfo("E-B116", "Калибровката е върната към стойностите по подразбиране.", listOf("Направи sector check."), VICTRON_VEBUS_BMS),
    ),
    BatteryBrand.BYD to listOf(
        FaultInfo("EC 101", "Отказ на сензора за напрежение или комуникация с BIC.", listOf("Провери DC кабелите към батерията, инвертора и комбинаторната кутия.", "Рестартирай. Ако продължава, тествай друг BCU."), BYD_BATTERY),
        FaultInfo("EC 102", "Ненормално изключване, отказ на предзареждането или на модул.", listOf("Провери терминалния резистор.", "Махни горния модул и провери дали кодът изчезва."), BYD_BATTERY),
        FaultInfo("EC 103", "Отказ на сензора за напрежение на пакета.", listOf("Провери напреженията на клетките с BCP.", "Махни модула с неправилни стойности."), BYD_BATTERY),
        FaultInfo("EC 104", "Обърната DC връзка или DC късо съединение.", listOf("Изключи DC кабела инвертор–батерия и измери P+ до P− за късо.", "Ако има късо, смени BCU."), BYD_BATTERY),
        FaultInfo("EC 105", "Неизправен температурен сензор.", listOf("Намери модула през BCP или изключване.", "Ако LED остане, свържи се със сервиза.", "Махни модул с клетка под 1.5 V или над 70 °C."), BYD_BATTERY),
        FaultInfo("EC 106", "Балансирането на BIC е неуспешно.", listOf("Провери фърмуера.", "Ако продължава, провери модула."), BYD_BATTERY),
        FaultInfo("EC 107", "Отказ на клетка или аларма за разлика в напрежението (ниво 3).", listOf("Провери напреженията на клетките и смени модула с неправилно напрежение."), BYD_BATTERY),
        FaultInfo("EC 108", "Отказ на сензора за ток.", listOf("Провери фърмуера и рестартирай.", "Ако продължава, тествай друг BCU."), BYD_BATTERY),
        FaultInfo("EC 110", "Ниско напрежение.", listOf("Изключи бързо, за да спреш разреждането.", "Провери фърмуера и реда на рестартиране."), BYD_BATTERY),
        FaultInfo("EC 112", "Няма комуникация с инвертора.", listOf("Провери комуникацията по раздел 2.4 на ръководството."), BYD_BATTERY),
        FaultInfo("EC 203–803", "Модулът не е разпознат. Първата цифра е позицията отгоре.", listOf("Провери типа и броя на модулите в приложението.", "Махни засегнатия модул."), BYD_BATTERY),
    ),
    BatteryBrand.PYLONTECH to listOf(
        FaultInfo("Ref. a", "Батерията не се включва: капацитетът е твърде нисък или е прекалено разредена.", listOf("Подай 48–53.5 V от зарядно или инвертор.", "При напрежение до 45 V зареждай с до 0.05C, над 45 V с до 0.5C."), PYLON_US3000C),
        FaultInfo("Ref. b", "Червен ALR без зареждане и разреждане: температура над 60 °C или под −10 °C.", listOf("Премести батерията в диапазон 0–50 °C."), PYLON_US3000C),
        FaultInfo("Ref. c", "Червен ALR: ток над 90 A.", listOf("Провери тока на товара и захранването."), PYLON_US3000C),
        FaultInfo("Ref. d", "Червен ALR: напрежение на зареждане над 54 V.", listOf("Коригирай настройките за зареждане и разреди модула."), PYLON_US3000C),
        FaultInfo("Ref. e", "Червен ALR: ниско напрежение, до 44.5 V.", listOf("Зареди, докато червеният LED изгасне."), PYLON_US3000C),
        FaultInfo("Ref. f", "Напрежението е под 54 V и не всички SOC LED светят: голям дисбаланс между клетките.", listOf("Зареди на 53–54 V или остави системата да работи, за балансиране."), PYLON_US3000C),
        FaultInfo("Ref. g", "Червен ALR постоянно: клетка над 4.2 V или под 1.5 V, или температура над 80 °C.", listOf("Изключи модула и се свържи с дистрибутора за ремонт."), PYLON_US3000C),
        FaultInfo("Ref. h", "Няма червена светлина, без зареждане и разреждане: счупен предпазител.", listOf("Изключи и се свържи с дистрибутора."), PYLON_US3000C),
        FaultInfo("Ref. i", "Звуков сигнал: обърнати кабели.", listOf("Изключи всичко, провери кабелите и полюсите.", "Ако алармата остане без кабели, свържи се с дистрибутора."), PYLON_US3000C),
        FaultInfo("Ref. j", "Звуков сигнал: повреда на MOSFET (MOSFAIL).", listOf("Както при i.", "Ако звукът остане без кабели, MOSFET е повреден."), PYLON_US3000C),
        FaultInfo("Ref. k", "Модулът се включва веднага: отказ на BMS.", listOf("Изключи и се свържи с дистрибутора."), PYLON_US3000C),
        FaultInfo("LED: оранжев, бавно мига", "Самопроверката е неуспешна, модулът е изключен.", listOf("Виж раздел 5.1 на ръководството."), PYLON_FORCE_H2),
        FaultInfo("LED: оранжев, бързо мига", "Черният старт е неуспешен.", listOf("Изчакай над 5 минути преди нов опит."), PYLON_FORCE_H2),
        FaultInfo("LED: оранжев, свети, SOC в синьо", "Загубена комуникация или грешка на BMS.", emptyList(), PYLON_FORCE_H2),
        FaultInfo("Няма изход, няма LED", "Контролерът не се включва.", listOf("Натисни старта поне 5 s (10 s при черен старт).", "Провери батерията на контролера и напрежението (минимум 2 модула)."), PYLON_FORCE_H2),
        FaultInfo("Бавно оранжево мигане след включване", "Напрежението от DC страната се различава от батерията с повече от 20 V.", listOf("Махни DC напрежението или го настрой, после повтори старта."), PYLON_FORCE_H2),
        FaultInfo("Постоянен зумер", "Реле залепнало.", listOf("Изключи всичко DC и рестартирай.", "Ако продължава, смени контролера."), PYLON_FORCE_H2),
        FaultInfo("Статус оранжев, модулите сини", "Загубена комуникация с инвертора, защита от свръхток или авария на контролера.", listOf("Провери кабела към инвертора.", "Провери DC страната."), PYLON_FORCE_H2),
        FaultInfo("Статус оранжев, модулите оранжеви", "Защита от температура, свръхнапрежение или ниско напрежение.", listOf("При свръхнапрежение провери настройката на зареждането.", "При ниско напрежение направи черен старт, после зареди."), PYLON_FORCE_H2),
        FaultInfo("Всички LED сини, няма изход", "Изгорял предпазител.", listOf("Смени контролера."), PYLON_FORCE_H2),
    ),
    BatteryBrand.DYNESS to listOf(
        FaultInfo("Ниско напрежение при разреждане", "Зумер, изходът спира. Възстановява се при връщане в диапазона.", listOf("Зареди батерията."), DYNESS_POWERBOX),
        FaultInfo("Свръхнапрежение при зареждане", "Зареждането спира и се възстановява само.", emptyList(), DYNESS_POWERBOX),
        FaultInfo("Свръхток при зареждане над 110 A", "Токът се ограничава до 3 A, опит след 10 s.", listOf("Намали тока под 110 A."), DYNESS_POWERBOX),
        FaultInfo("Свръхток при разреждане над 115 A", "Зумер, изходът спира след 15 s. Възстановява се след 60 s или веднага при зареждане.", listOf("Намали товара."), DYNESS_POWERBOX),
        FaultInfo("Температура при зареждане извън 0 до +65 °C", "Зареждането спира.", emptyList(), DYNESS_POWERBOX),
        FaultInfo("Температура при разреждане извън −20 до +65 °C", "Изходът спира.", emptyList(), DYNESS_POWERBOX),
        FaultInfo("Късо съединение при старт", "Първо се задейства DC прекъсвачът.", listOf("Намери и махни късото съединение."), DYNESS_POWERBOX),
        FaultInfo("Саморазреждане", "При липса на товар 72 часа системата заспива.", listOf("Натисни бутона за включване."), DYNESS_POWERBOX),
        FaultInfo("Аларма: прекомерно разреждане", "Червено свети и зумер.", listOf("Зареди."), DYNESS_POWERBOX),
        FaultInfo("Аларма: висока температура", "Червено свети.", emptyList(), DYNESS_POWERBOX),
        FaultInfo("Аларма: ниско напрежение", "Жълто свети.", listOf("Зареди."), DYNESS_POWERBOX),
        FaultInfo("Ниско SOC: 0 < SOC ≤ 10%", "LED1 свети червено, ALM зелено.", listOf("Спри разреждането и зареди навреме."), DYNESS_POWERBOX),
        FaultInfo("Комуникация между устройства", "Светлинната платка цикли през три цвята.", listOf("Провери външния кабел, после вътрешния."), DYNESS_POWERBOX),
    ),
    BatteryBrand.SEPLOS to listOf(
        seplos(1, 0, "Авария на сензора за напрежение."),
        seplos(1, 1, "Авария на сензора за температура."),
        seplos(1, 2, "Авария на сензора за ток."),
        seplos(1, 3, "Авария на силовия ключ."),
        seplos(1, 4, "Авария на сензора за разлика в напреженията на клетките."),
        seplos(1, 5, "Авария на ключа за зареждане."),
        seplos(1, 6, "Авария на ключа за разреждане."),
        seplos(1, 7, "Авария на ключа за ограничение на тока."),
        seplos(2, 0, "Предупреждение за свръхнапрежение на клетка."),
        seplos(2, 1, "Защита от свръхнапрежение на клетка."),
        seplos(2, 2, "Предупреждение за ниско напрежение на клетка."),
        seplos(2, 3, "Защита от ниско напрежение на клетка."),
        seplos(2, 4, "Предупреждение за свръхнапрежение на пакета."),
        seplos(2, 5, "Защита от свръхнапрежение на пакета."),
        seplos(2, 6, "Предупреждение за ниско напрежение на пакета."),
        seplos(2, 7, "Защита от ниско напрежение на пакета."),
        seplos(3, 0, "Предупреждение за висока температура при зареждане."),
        seplos(3, 1, "Защита от висока температура при зареждане."),
        seplos(3, 2, "Предупреждение за ниска температура при зареждане."),
        seplos(3, 3, "Защита от ниска температура при зареждане."),
        seplos(3, 4, "Предупреждение за висока температура при разреждане."),
        seplos(3, 5, "Защита от висока температура при разреждане."),
        seplos(3, 6, "Предупреждение за ниска температура при разреждане."),
        seplos(3, 7, "Защита от ниска температура при разреждане."),
        seplos(4, 0, "Предупреждение за висока околна температура."),
        seplos(4, 1, "Защита от висока околна температура."),
        seplos(4, 2, "Предупреждение за ниска околна температура."),
        seplos(4, 3, "Защита от ниска околна температура."),
        seplos(4, 4, "Предупреждение за висока температура на компонент."),
        seplos(4, 5, "Защита от висока температура на компонент."),
        seplos(4, 6, "Нагряване (групирането в документа е неясно)."),
        seplos(5, 0, "Предупреждение за свръхток при зареждане."),
        seplos(5, 1, "Защита от свръхток при зареждане."),
        seplos(5, 2, "Предупреждение за свръхток при разреждане."),
        seplos(5, 3, "Защита от свръхток при разреждане."),
        seplos(5, 4, "Защита от кратковременен свръхток."),
        seplos(5, 5, "Защита от късо съединение на изхода."),
        seplos(5, 6, "Блокировка при кратковременен свръхток."),
        seplos(5, 7, "Блокировка при късо съединение на изхода."),
        seplos(6, 0, "Защита от високо напрежение при зареждане."),
        seplos(6, 1, "Изчакване на прекъсващо захранване."),
        seplos(6, 2, "Предупреждение за остатъчен капацитет."),
        seplos(6, 3, "Защита за остатъчен капацитет."),
        seplos(6, 4, "Забрана за зареждане при ниско напрежение на клетка."),
        seplos(6, 5, "Защита от обратна връзка на изхода."),
        seplos(6, 6, "Авария на връзката на изхода."),
    ),
)

private fun faultsFor(brand: Brand): List<FaultInfo> = when (brand) {
    Brand.DEYE -> DEYE_FAULTS
    Brand.GROWATT -> GROWATT_FAULTS
    Brand.VICTRON -> VICTRON_FAULTS
}

/** Марката на батерията по подразбиране: същата като на инвертора, ако има таблици за нея. */
private fun defaultBatteryBrand(brand: Brand): BatteryBrand = when (brand) {
    Brand.DEYE -> BatteryBrand.DEYE
    Brand.GROWATT -> BatteryBrand.GROWATT
    Brand.VICTRON -> BatteryBrand.VICTRON
}

private fun batteryFor(brand: BatteryBrand): List<FaultInfo> = BATTERY_ITEMS[brand].orEmpty()

private fun batteryNote(): String = pick(
    "Батерията често е от друга марка, отколкото инвертора. Избери марката на батерията. Таблиците са обобщени от ръководствата, а кодовете и защитите се различават по модел. Не зареждай и не използвай повредена батерия.",
    "The battery is often from a different brand than the inverter. Choose the battery brand. The tables are summarized from the manuals, and codes and protections differ by model. Do not charge or use a damaged battery.",
)

/** Падащото меню за марката на батерията. Работи независимо от марката на инвертора. */
@Composable
private fun BatteryBrandPicker(selected: BatteryBrand, onSelect: (BatteryBrand) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(pick("Марка на батерията: ", "Battery brand: ") + selected.label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BatteryBrand.entries.forEach { b ->
                DropdownMenuItem(
                    text = { Text(b.label) },
                    onClick = {
                        onSelect(b)
                        open = false
                    },
                )
            }
        }
    }
}

/** Английският текст за всеки български ред от данните. Ако някой ред липсва, остава българският. */
private val EN: Map<String, String> = mapOf(
    // Deye инвертор
    "DC_Inversed_Failure: обърнат полюс на PV входа." to "DC_Inversed_Failure: reversed polarity on the PV input.",
    "DC_START_Failure: шината не може да се захрани от PV или батерия." to "DC_START_Failure: the bus cannot be powered from PV or the battery.",
    "Working_Mode_change: промяна на работния режим, типа мрежа или батерията." to "Working_Mode_change: change of working mode, grid type or battery.",
    "AC_OverCurr_SW_Failure: претоварване по AC страната." to "AC_OverCurr_SW_Failure: overload on the AC side.",
    "GFCI_Failure: теч на ток." to "GFCI_Failure: current leakage.",
    "Tz_Ac_OverCurr_Fault: претоварване по AC." to "Tz_Ac_OverCurr_Fault: AC overload.",
    "Tz_Dc_OverCurr_Fault: претоварване по DC." to "Tz_Dc_OverCurr_Fault: DC overload.",
    "Tz_HV_Overcurr_fault: претоварване на шината." to "Tz_HV_Overcurr_fault: bus overload.",
    "Tz_EmergStop_Fault: дистанционно спиране." to "Tz_EmergStop_Fault: remote stop.",
    "Tz_GFCI_OC_Fault: теч на ток." to "Tz_GFCI_OC_Fault: current leakage.",
    "DC_Insulation_Fault: ниско изолационно съпротивление на PV." to "DC_Insulation_Fault: low insulation resistance on PV.",
    "BusUnbalance_Fault: дисбаланс на шината." to "BusUnbalance_Fault: bus imbalance.",
    "Parallel_Comm_Fault: авария в паралелната комуникация." to "Parallel_Comm_Fault: fault in the parallel communication.",
    "AC_Overload_Fault: претоварване на резервния товар." to "AC_Overload_Fault: backup load overload.",
    "Parallel_system_Stop: спрял е паралелен блок." to "Parallel_system_Stop: a parallel unit has stopped.",
    "Parallel_Version_Fault: различен фърмуер в блоковете." to "Parallel_Version_Fault: different firmware in the units.",
    "AC_OverFreq_Fault: честотата на мрежата е извън диапазона." to "AC_OverFreq_Fault: grid frequency is out of range.",
    "AC_UnderFreq_Fault: честотата на мрежата е извън диапазона." to "AC_UnderFreq_Fault: grid frequency is out of range.",
    "DC_VoltHigh_Fault: напрежението на шината е твърде високо." to "DC_VoltHigh_Fault: bus voltage is too high.",
    "DC_VoltLow_Fault: напрежението на шината е твърде ниско." to "DC_VoltLow_Fault: bus voltage is too low.",
    "BAT2_VoltHigh_Fault: напрежението на батерия 2 е твърде високо." to "BAT2_VoltHigh_Fault: battery 2 voltage is too high.",
    "BAT1_VoltHigh_Fault: напрежението на батерия 1 е твърде високо." to "BAT1_VoltHigh_Fault: battery 1 voltage is too high.",
    "BAT1_VoltLow_Fault: напрежението на батерия 1 е твърде ниско." to "BAT1_VoltLow_Fault: battery 1 voltage is too low.",
    "BAT2_VoltLow_Fault: напрежението на батерия 2 е твърде ниско." to "BAT2_VoltLow_Fault: battery 2 voltage is too low.",
    "Battery_comm_Lose: загубена връзка с BMS, докато BMS_Err-Stop е активен." to "Battery_comm_Lose: BMS connection lost while BMS_Err-Stop is active.",
    "DRMs0_stop: DRM функция, приложима само за Австралия." to "DRMs0_stop: DRM function, applies only to Australia.",
    "ARC_Fault: детекция на дъгов разряд, приложима само за САЩ." to "ARC_Fault: arc detection, applies only to the USA.",
    "Heatsink_HighTemp_Fault: радиаторът е твърде горещ." to "Heatsink_HighTemp_Fault: the heatsink is too hot.",
    "Провери полярността на PV входа." to "Check the polarity of the PV input.",
    "Ако не се оправи, свържи се с поддръжката." to "If it does not clear, contact support.",
    "Рестартирай инвертора." to "Restart the inverter.",
    "Ако продължава, свържи се с поддръжката." to "If it continues, contact support.",
    "Обикновено се оправя сам." to "It usually clears by itself.",
    "Ако не, изключи DC и AC за една минута и ги включи пак." to "If not, turn off DC and AC for one minute and turn them back on.",
    "Провери мощността на резервния товар." to "Check the power of the backup load.",
    "Рестартирай 2–3 пъти." to "Restart 2–3 times.",
    "Провери заземяването на PV кабелите." to "Check the grounding of the PV cables.",
    "Провери мощността на товара." to "Check the load power.",
    "Провери PV и батерията." to "Check the PV and the battery.",
    "При режим без мрежа намали големите пускови товари." to "In off-grid mode, reduce large starting loads.",
    "Провери тока на PV входа и настройките на батерията." to "Check the PV input current and the battery settings.",
    "Инверторът се управлява отдалечено. Провери дали спирането е нарочно." to "The inverter is controlled remotely. Check whether the stop was intentional.",
    "Провери PV връзките и PE заземяването." to "Check the PV connections and the PE grounding.",
    "Изчакай, провери пак и рестартирай." to "Wait, check again and restart.",
    "Провери кабела за паралелна комуникация и адреса на всеки блок." to "Check the parallel communication cable and the address of each unit.",
    "Провери дали резервният товар е в допустимите граници." to "Check that the backup load is within the allowed limits.",
    "Ако един блок е спрял, всички показват F41. Провери статуса на блоковете." to "If one unit has stopped, all show F41. Check the status of the units.",
    "Провери дали фърмуерът на всички блокове е един и същ." to "Check that all units have the same firmware.",
    "За обновяване се свържи с поддръжката." to "For an update, contact support.",
    "Провери AC кабелите." to "Check the AC cables.",
    "Същите действия като при F47." to "Same actions as for F47.",
    "Провери батерията и напрежението на PV." to "Check the battery and the PV voltage.",
    "Провери батерията." to "Check the battery.",
    "Ако е празна, зареди я от PV или мрежата." to "If it is empty, charge it from PV or the grid.",
    "Провери напрежението на батерия 2." to "Check the voltage of battery 2.",
    "Рестартирай два пъти, после възстанови фабричните настройки." to "Restart twice, then restore the factory settings.",
    "Провери напрежението на батерия 1." to "Check the voltage of battery 1.",
    "Може да се изключи от LCD дисплея." to "It can be turned off from the LCD display.",
    "Провери дали е активна." to "Check whether it is active.",
    "Провери PV кабелите." to "Check the PV cables.",
    "Провери околната температура." to "Check the ambient temperature.",
    "Изключи инвертора за 10 минути." to "Turn off the inverter for 10 minutes.",
    "SUN-5/25K-SG01HP3, стр. 42–44 (обобщение)" to "SUN-5/25K-SG01HP3, pp. 42–44 (summary)",
    "Кодовете са обобщени от ръководството на инвертора, стр. 42–44. Други модели може да имат различни кодове." to "Codes are summarized from the inverter manual, pages 42–44. Other models may have different codes.",
    // Growatt инвертор
    "Няма AC връзка." to "No AC connection.",
    "Провери AC кабелите и AC прекъсвача." to "Check the AC cables and the AC breaker.",
    "Лоша изолация на PV страната." to "Poor insulation on the PV side.",
    "Провери заземяването на панелите и инвертора." to "Check the grounding of the panels and the inverter.",
    "Провери за влага в DC прекъсвача." to "Check for moisture in the DC breaker.",
    "Измери PV+ и PV− спрямо земята: над 50 kΩ (или 1000 kΩ по VDE 0126)." to "Measure PV+ and PV− to earth: above 50 kΩ (or 1000 kΩ per VDE 0126).",
    "Residual I High: теч на ток." to "Residual I High: current leakage.",
    "Рестартирай." to "Restart.",
    "Ако продължава, свържи се с Growatt." to "If it continues, contact Growatt.",
    "PV напрежението е над максималното." to "PV voltage is above the maximum.",
    "Изключи DC веднага." to "Turn off DC immediately.",
    "Измери напрежението на всеки низ." to "Measure the voltage of each string.",
    "Ако е под 1000 V, свържи се с Growatt." to "If it is below 1000 V, contact Growatt.",
    "Рестартирай и повтори теста." to "Restart and repeat the test.",
    "AC V Outrange: напрежението на AC е извън диапазона." to "AC V Outrange: AC voltage is out of range.",
    "Изключи DC." to "Turn off DC.",
    "Провери AC кабелите, особено нулата и заземяването." to "Check the AC cables, especially neutral and grounding.",
    "Провери напрежението спрямо местните стандарти." to "Check the voltage against local standards.",
    "AC F Outrange: честотата на AC е извън диапазона." to "AC F Outrange: AC frequency is out of range.",
    "Същите стъпки като при 129, но за честотата на мрежата." to "Same steps as for 129, but for the grid frequency.",
    "Предупреждение: не е уточнено." to "Warning: not specified.",
    "Ако продължава, смени COM платката." to "If it continues, replace the COM board.",
    "Предупреждение: несъвместима версия на фърмуера." to "Warning: incompatible firmware version.",
    "Обнови фърмуера." to "Update the firmware.",
    "Предупреждение: грешка при запис в EEPROM." to "Warning: EEPROM write error.",
    "Предупреждение: късо съединение на PV1 или PV2." to "Warning: short circuit on PV1 or PV2.",
    "Провери полярността на панелите." to "Check the polarity of the panels.",
    "Предупреждение: грешка на boost на PV1 или PV2." to "Warning: boost error on PV1 or PV2.",
    "Ако продължава, смени силовата платка." to "If it continues, replace the power board.",
    "Вътрешна грешка: загуба на данни от управляващата платка за 10 s." to "Internal error: loss of data from the control board for 10 s.",
    "Рестартирай, после обнови фърмуера или се свържи с Growatt." to "Restart, then update the firmware or contact Growatt.",
    "Вътрешна грешка: несъвпадение на AC напрежението между MCU." to "Internal error: AC voltage mismatch between the MCUs.",
    "Вътрешна грешка: bus sample." to "Internal error: bus sample.",
    "Вътрешна грешка: relay." to "Internal error: relay.",
    "Вътрешна грешка: GFCI." to "Internal error: GFCI.",
    "Вътрешна грешка: дисбаланс на тока." to "Internal error: current imbalance.",
    "Вътрешна грешка: загуба на данни от COM платката за 5 s." to "Internal error: loss of data from the COM board for 5 s.",
    "Вътрешна грешка: пренапрежение на bus." to "Internal error: bus overvoltage.",
    "Вътрешна грешка: Output high DCI." to "Internal error: Output high DCI.",
    "Прегряване, когато околната температура е под 60 °C." to "Overheating when the ambient temperature is below 60 °C.",
    "Предупреждение: вентилатор." to "Warning: fan.",
    "Предупреждение: EEPROM read." to "Warning: EEPROM read.",
    "Предупреждение: несъвместим фърмуер. Грешка на модела." to "Warning: incompatible firmware. Model error.",
    "Предупреждение: EEPROM write." to "Warning: EEPROM write.",
    "Грешка: COM не получава данни от control 10 s." to "Error: COM receives no data from control for 10 s.",
    "Грешка: голямо разминаване в семплирането." to "Error: large sampling mismatch.",
    "Грешка: relay, 6 неуспешни проверки." to "Error: relay, 6 failed checks.",
    "Грешка: control не получава данни от COM 5 s." to "Error: control receives no data from COM for 5 s.",
    "Грешка: bus напрежението е твърде високо." to "Error: bus voltage is too high.",
    "Грешка: GFCI." to "Error: GFCI.",
    "Auto Test Failed. PV Isolation Low: над 1 MΩ." to "Auto Test Failed. PV Isolation Low: above 1 MΩ.",
    "PV Voltage High: над 1000 V." to "PV Voltage High: above 1000 V.",
    "Output High DCI." to "Output High DCI.",
    "AC V Outrange." to "AC V Outrange.",
    "AC F Outrange." to "AC F Outrange.",
    "Residual I High." to "Residual I High.",
    "Growatt TL3 (GR-UM-140-A)" to "Growatt TL3 (GR-UM-140-A)",
    "Growatt 10–20kUE (ver. 1.0)" to "Growatt 10–20kUE (ver. 1.0)",
    "Кодовете са обобщени от ръководствата на Growatt. Кодовете се различават по модел, затова моделът е в описанието на всеки код." to "Codes are summarized from the Growatt manuals. Codes differ by model, so the model is in each code's description.",
    // Victron инвертор (VE.Bus)
    "Устройството се изключи, защото друга фаза в системата се е изключила." to "The device switched off because another phase in the system switched off.",
    "Намери и провери фазата, която е отказала." to "Find and check the phase that failed.",
    "Открити са повече или по-малко устройства от очакваните." to "More or fewer devices were found than expected.",
    "Преконфигурирай системата." to "Reconfigure the system.",
    "Ако продължава, провери комуникационните кабели, после изключи и включи всичко." to "If it continues, check the communication cables, then turn everything off and on.",
    "Не е открито друго устройство." to "No other device was found.",
    "Провери комуникационните кабели." to "Check the communication cables.",
    "Напрежението на AC изхода е твърде високо." to "The AC output voltage is too high.",
    "Грешка при синхронизация на времето в системата. Не трябва да се случва при правилен монтаж." to "Time synchronization error in the system. It should not happen with a correct installation.",
    "Провери комуникационните кабели за късо съединение." to "Check the communication cables for a short circuit.",
    "Устройството не може да предава данни." to "The device cannot transmit data.",
    "Устройство стана master, след като оригиналният master отказа." to "A device became master after the original master failed.",
    "Провери отказалото устройство и комуникационните кабели." to "Check the failed device and the communication cables.",
    "Настъпило е пренапрежение." to "An overvoltage occurred.",
    "Устройството не може да работи като slave. Остарял, неподходящ модел." to "The device cannot work as a slave. Outdated, unsuitable model.",
    "Смени устройството." to "Replace the device.",
    "Задейства се защита при превключване. Не трябва да се случва при правилен монтаж." to "A switching protection was triggered. It should not happen with a correct installation.",
    "Изключи и включи всичко." to "Turn everything off and on.",
    "Ако се повтаря, прегледай монтажа." to "If it repeats, review the installation.",
    "Може да вдигнеш долния AC вход до 210 VAC (фабрично 180 VAC)." to "You can raise the lower AC input to 210 VAC (factory setting 180 VAC).",
    "Несъвместим фърмуер: фърмуерът на едно устройство е твърде стар за останалите." to "Incompatible firmware: the firmware of one device is too old for the others.",
    "Изключи всичко, включи устройството с грешката, после добавяй останалите едно по едно." to "Turn everything off, switch on the device with the error, then add the others one at a time.",
    "Обнови фърмуера на последното включено." to "Update the firmware of the last device switched on.",
    "Вътрешна грешка. Не трябва да се случва." to "Internal error. It should not happen.",
    "Ако продължава, свържи се с Victron." to "If it continues, contact Victron.",
    "Виж дилъра на Victron." to "See your Victron dealer.",
    // Батерии: Deye SE-G
    "RUN мига бързо" to "RUN blinks fast",
    "Обновяване на фърмуера (LED)." to "Firmware update (LED).",
    "Изчакай." to "Wait.",
    "RUN мига бавно" to "RUN blinks slowly",
    "Критична грешка (LED)." to "Critical error (LED).",
    "Свържи се със сервиз." to "Contact service.",
    "ALM мига" to "ALM blinks",
    "Аларма при разреждане или покой (LED)." to "Alarm during discharge or idle (LED).",
    "Провери алармата в мониторинг софтуера." to "Check the alarm in the monitoring software.",
    "ERROR свети" to "ERROR is on",
    "Грешка или защита (LED)." to "Error or protection (LED).",
    "Виж режима на защита в мониторинг софтуера и раздел 8 в ръководството." to "See the protection mode in the monitoring software and section 8 of the manual.",
    "BMS неизправност" to "BMS fault",
    "Отказ на веригата за напрежение или температура на клетките: разхлабена връзка, изгорял предпазител или неработещ сензор." to "Failure of the cell voltage or temperature circuit: loose connection, blown fuse or faulty sensor.",
    "Смени батерията." to "Replace the battery.",
    "Неизправност на клетка" to "Cell fault",
    "Ниско или небалансирано напрежение на клетка: разреждане под 2.0 V след дълго съхранение или механична повреда." to "Low or unbalanced cell voltage: discharged below 2.0 V after long storage, or mechanical damage.",
    "Защита от свръхнапрежение" to "Overvoltage protection",
    "Клетка над 3.65 V при зареждане или пакет над 58.4 V." to "Cell above 3.65 V during charging, or pack above 58.4 V.",
    "Ако не се възстановява, свържи се с техник." to "If it does not recover, contact a technician.",
    "Защита от ниско напрежение" to "Undervoltage protection",
    "Пакет под 40 V или най-ниска клетка под 2.5 V." to "Pack below 40 V or lowest cell below 2.5 V.",
    "Защита от висока температура" to "High temperature protection",
    "Най-гореща клетка над 60 °C." to "Hottest cell above 60 °C.",
    "Защита при ниска температура при зареждане" to "Low temperature protection during charging",
    "Най-студена клетка под 0 °C." to "Coldest cell below 0 °C.",
    "Защита при ниска температура при разреждане" to "Low temperature protection during discharging",
    "Най-студена клетка под −20 °C." to "Coldest cell below −20 °C.",
    "Deye SE-G5.1 Pro-B, Issue 03 (обобщение)" to "Deye SE-G5.1 Pro-B, Issue 03 (summary)",
    // Батерии: Growatt ARK
    "ALM мига: ниско напрежение при разреждане" to "ALM blinks: low voltage during discharge",
    "Клетка под прага." to "Cell below the threshold.",
    "Спри разреждането и зареди." to "Stop discharging and charge.",
    "ALM мига: високо напрежение при зареждане" to "ALM blinks: high voltage during charging",
    "Клетка над прага." to "Cell above the threshold.",
    "Спри зареждането. Системата се оправя сама." to "Stop charging. The system recovers by itself.",
    "ALM мига: RS485 комуникация" to "ALM blinks: RS485 communication",
    "Загубена връзка инвертор–батерия." to "Lost inverter–battery connection.",
    "Спри батерията, провери клемите, после се обади на инсталатор." to "Stop the battery, check the terminals, then call an installer.",
    "ALM мига: CAN комуникация" to "ALM blinks: CAN communication",
    "Загубена връзка между пакетите." to "Lost connection between the packs.",
    "Провери кабелите между пакетите и контролера." to "Check the cables between the packs and the controller.",
    "ALM мига: висока температура" to "ALM blinks: high temperature",
    "Над лимита на BMS." to "Above the BMS limit.",
    "Спри веднага и остави да изстине. Оправя се сама." to "Stop immediately and let it cool. It recovers by itself.",
    "ALM мига: ниска температура" to "ALM blinks: low temperature",
    "Под лимита на BMS." to "Below the BMS limit.",
    "Няма риск. Остави да се затопли." to "No risk. Let it warm up.",
    "ALM свети: късо съединение при разреждане" to "ALM on: short circuit during discharge",
    "Външно късо съединение." to "External short circuit.",
    "Спри и повикай инсталатор за ремонт." to "Stop and call an installer for repair.",
    "ALM свети: предзареждане" to "ALM on: precharge",
    "Къс на предзарядното устройство или таймаут." to "Short in the precharge device, or timeout.",
    "Опасност. Спри и повикай инсталатор." to "Danger. Stop and call an installer.",
    "ALM свети: семплиране на напрежението" to "ALM on: voltage sampling",
    "Отказ на BMS." to "BMS failure.",
    "ALM свети: семплиране на тока" to "ALM on: current sampling",
    "ALM свети: основна верига" to "ALM on: main circuit",
    "Неизправност в основната верига, отказ на BMS." to "Fault in the main circuit, BMS failure.",
    "ALM свети: EEPROM" to "ALM on: EEPROM",
    "BMS не чете данните." to "BMS cannot read the data.",
    "ALM свети: Chip" to "ALM on: Chip",
    "Загуба на връзка между два MCU." to "Lost connection between two MCUs.",
    "Growatt ARK 5.1–25.6XH-A1, GR-UM-242-A-01 (обобщение)" to "Growatt ARK 5.1–25.6XH-A1, GR-UM-242-A-01 (summary)",
    // Батерии: Victron Lynx и VE.Bus BMS
    "W-B01" to "W-B01",
    "Ниско напрежение на клетка." to "Low cell voltage.",
    "Зареди или намали товара, за да се избегне спиране." to "Charge or reduce the load to avoid a shutdown.",
    "Висок ток." to "High current.",
    "Намали тока." to "Reduce the current.",
    "Висока температура на BMS." to "High BMS temperature.",
    "Провери околната температура и вентилаторите." to "Check the ambient temperature and the fans.",
    "Ако вентилаторите не работят, виж дилъра на Victron." to "If the fans do not work, see your Victron dealer.",
    "Лош контактор." to "Bad contactor.",
    "Товарът ще се изключи." to "The load will switch off.",
    "Товарите се изключват след 30 s, ако причината не се оправи." to "Loads switch off after 30 s if the cause is not fixed.",
    "Ниско състояние на заряда." to "Low state of charge.",
    "Зареди или намали товара." to "Charge or reduce the load.",
    "VE.CAN е прекъснат." to "VE.CAN is interrupted.",
    "Провери комуникационния кабел." to "Check the communication cable.",
    "W-D01 до W-D08" to "W-D01 to W-D08",
    "Загубена връзка с дистрибутор от A до H." to "Lost connection with distributor A to H.",
    "Провери кабела между BMS и дистрибутора." to "Check the cable between the BMS and the distributor.",
    "Зареди. Товарите се включват, когато има достатъчно заряд." to "Charge. Loads switch on when there is enough charge.",
    "Намали тока или изключи част от товарите. Системата опитва отново след 5 минути." to "Reduce the current or switch off some loads. The system retries after 5 minutes.",
    "Товарът е изключен." to "The load is off.",
    "Зареди батерията. Ако не се оправи, контакторът се отваря." to "Charge the battery. If it does not clear, the contactor opens.",
    "Зареди." to "Charge.",
    "A-F01 до A-F32" to "A-F01 to A-F32",
    "Предпазител от 1 до 32 е изгорял." to "Fuse 1 to 32 has blown.",
    "Провери системата и смени предпазителя. Имената се виждат във VictronConnect." to "Check the system and replace the fuse. The names are shown in VictronConnect.",
    "Грешка при инициализация на батерията." to "Battery initialization error.",
    "Провери свързването на всички батерии." to "Check the connection of all batteries.",
    "Няма открита батерия." to "No battery found.",
    "Провери дали кабелът на BMS е затворен в контур." to "Check that the BMS cable is closed in a loop.",
    "Невалиден брой батерии." to "Invalid number of batteries.",
    "Провери свързването." to "Check the connection.",
    "Напрежението на батерията не е позволено: извън 9–60 V." to "Battery voltage not allowed: outside 9–60 V.",
    "Провери напрежението и настройките." to "Check the voltage and the settings.",
    "Хардуерна грешка." to "Hardware error.",
    "Грешка при предварително зареждане." to "Precharge error.",
    "Съпротивлението на товара е твърде ниско. Изключи част от товарите." to "The load resistance is too low. Switch off some loads.",
    "Грешка на контактора." to "Contactor error.",
    "Грешка на BMS кабела." to "BMS cable error.",
    "Провери M8 връзките на батерията." to "Check the M8 battery connections.",
    "Грешно системно напрежение." to "Wrong system voltage.",
    "Изтекло време за предварително зареждане." to "Precharge time expired.",
    "Товарът е твърде голям. Изключи част от DC товарите." to "The load is too large. Switch off some DC loads.",
    "Грешка на ATC/ATD." to "ATC/ATD error.",
    "Провери кабелите ATC/ATD." to "Check the ATC/ATD cables.",
    "Калибровъчните данни са загубени." to "Calibration data is lost.",
    "Настройките са загубени." to "Settings are lost.",
    "Върни фабричните настройки." to "Restore the factory settings.",
    "Загубена комуникация с батерията." to "Lost communication with the battery.",
    "Провери кабелите." to "Check the cables.",
    "Cellguard фърмуерът не може да се обнови." to "The Cellguard firmware cannot be updated.",
    "Нужен е цикъл на захранване." to "A power cycle is needed.",
    "Загубена комуникация." to "Lost communication.",
    "Висока температура на батерията: зареждането е блокирано." to "High battery temperature: charging is blocked.",
    "Ниска температура на батерията." to "Low battery temperature.",
    "Калибровката е върната към стойностите по подразбиране." to "The calibration was reset to the default values.",
    "Направи sector check." to "Do a sector check.",
    "Victron Lynx Smart BMS (обобщение)" to "Victron Lynx Smart BMS (summary)",
    "Victron VE.Bus BMS NG (обобщение)" to "Victron VE.Bus BMS NG (summary)",
    "Victron MultiPlus-II GX (VE.Bus)" to "Victron MultiPlus-II GX (VE.Bus)",
    // Батерии: BYD
    "BYD Battery-Box Premium HVS/HVM, BCU V2.0, Service Guideline V1.1 (обобщение)" to "BYD Battery-Box Premium HVS/HVM, BCU V2.0, Service Guideline V1.1 (summary)",
    "Отказ на сензора за напрежение или комуникация с BIC." to "Voltage sensor or BIC communication failure.",
    "Провери DC кабелите към батерията, инвертора и комбинаторната кутия." to "Check the DC cables to the battery, the inverter and the combiner box.",
    "Рестартирай. Ако продължава, тествай друг BCU." to "Restart. If it continues, test another BCU.",
    "Ненормално изключване, отказ на предзареждането или на модул." to "Abnormal shutdown, precharge failure or module failure.",
    "Провери терминалния резистор." to "Check the terminal resistor.",
    "Махни горния модул и провери дали кодът изчезва." to "Remove the top module and check whether the code disappears.",
    "Отказ на сензора за напрежение на пакета." to "Pack voltage sensor failure.",
    "Провери напреженията на клетките с BCP." to "Check the cell voltages with BCP.",
    "Махни модула с неправилни стойности." to "Remove the module with incorrect values.",
    "Обърната DC връзка или DC късо съединение." to "Reversed DC connection or DC short circuit.",
    "Изключи DC кабела инвертор–батерия и измери P+ до P− за късо." to "Disconnect the inverter–battery DC cable and measure P+ to P− for a short.",
    "Ако има късо, смени BCU." to "If there is a short, replace the BCU.",
    "Неизправен температурен сензор." to "Faulty temperature sensor.",
    "Намери модула през BCP или изключване." to "Find the module through BCP or by disconnecting.",
    "Ако LED остане, свържи се със сервиза." to "If the LED stays on, contact service.",
    "Махни модул с клетка под 1.5 V или над 70 °C." to "Remove the module with a cell below 1.5 V or above 70 °C.",
    "Балансирането на BIC е неуспешно." to "BIC balancing failed.",
    "Провери фърмуера." to "Check the firmware.",
    "Ако продължава, провери модула." to "If it continues, check the module.",
    "Отказ на клетка или аларма за разлика в напрежението (ниво 3)." to "Cell failure or voltage difference alarm (level 3).",
    "Провери напреженията на клетките и смени модула с неправилно напрежение." to "Check the cell voltages and replace the module with the wrong voltage.",
    "Отказ на сензора за ток." to "Current sensor failure.",
    "Провери фърмуера и рестартирай." to "Check the firmware and restart.",
    "Ниско напрежение." to "Low voltage.",
    "Изключи бързо, за да спреш разреждането." to "Switch off quickly to stop the discharge.",
    "Провери фърмуера и реда на рестартиране." to "Check the firmware and the restart order.",
    "Няма комуникация с инвертора." to "No communication with the inverter.",
    "Провери комуникацията по раздел 2.4 на ръководството." to "Check the communication as described in section 2.4 of the manual.",
    "Модулът не е разпознат. Първата цифра е позицията отгоре." to "The module is not recognized. The first digit is its position from the top.",
    "Провери типа и броя на модулите в приложението." to "Check the type and number of modules in the app.",
    "Махни засегнатия модул." to "Remove the affected module.",
    // Батерии: Pylontech
    "Pylontech US3000C, Information Version 1.1 (обобщение)" to "Pylontech US3000C, Information Version 1.1 (summary)",
    "Pylontech Force-H2 V2, Operation Manual 22P2FHB1018 (обобщение)" to "Pylontech Force-H2 V2, Operation Manual 22P2FHB1018 (summary)",
    "Батерията не се включва: капацитетът е твърде нисък или е прекалено разредена." to "The battery does not switch on: capacity is too low or it is over-discharged.",
    "Подай 48–53.5 V от зарядно или инвертор." to "Supply 48–53.5 V from a charger or an inverter.",
    "При напрежение до 45 V зареждай с до 0.05C, над 45 V с до 0.5C." to "At voltages up to 45 V charge at up to 0.05C, above 45 V at up to 0.5C.",
    "Червен ALR без зареждане и разреждане: температура над 60 °C или под −10 °C." to "Red ALR with no charge or discharge: temperature above 60 °C or below −10 °C.",
    "Премести батерията в диапазон 0–50 °C." to "Move the battery to a range of 0–50 °C.",
    "Червен ALR: ток над 90 A." to "Red ALR: current above 90 A.",
    "Провери тока на товара и захранването." to "Check the load current and the power supply.",
    "Червен ALR: напрежение на зареждане над 54 V." to "Red ALR: charging voltage above 54 V.",
    "Коригирай настройките за зареждане и разреди модула." to "Correct the charging settings and discharge the module.",
    "Червен ALR: ниско напрежение, до 44.5 V." to "Red ALR: low voltage, down to 44.5 V.",
    "Зареди, докато червеният LED изгасне." to "Charge until the red LED goes off.",
    "Напрежението е под 54 V и не всички SOC LED светят: голям дисбаланс между клетките." to "Voltage below 54 V and not all SOC LEDs are on: large cell imbalance.",
    "Зареди на 53–54 V или остави системата да работи, за балансиране." to "Charge to 53–54 V, or let the system run to balance.",
    "Червен ALR постоянно: клетка над 4.2 V или под 1.5 V, или температура над 80 °C." to "Solid red ALR: cell above 4.2 V or below 1.5 V, or temperature above 80 °C.",
    "Изключи модула и се свържи с дистрибутора за ремонт." to "Turn off the module and contact the distributor for repair.",
    "Няма червена светлина, без зареждане и разреждане: счупен предпазител." to "No red light, no charge or discharge: broken fuse.",
    "Изключи и се свържи с дистрибутора." to "Turn off and contact the distributor.",
    "Звуков сигнал: обърнати кабели." to "Audible alarm: reversed cables.",
    "Изключи всичко, провери кабелите и полюсите." to "Turn everything off and check the cables and the poles.",
    "Ако алармата остане без кабели, свържи се с дистрибутора." to "If the alarm stays with no cables connected, contact the distributor.",
    "Звуков сигнал: повреда на MOSFET (MOSFAIL)." to "Audible alarm: MOSFET failure (MOSFAIL).",
    "Както при i." to "As for i.",
    "Ако звукът остане без кабели, MOSFET е повреден." to "If the sound stays with no cables connected, the MOSFET is damaged.",
    "Модулът се включва веднага: отказ на BMS." to "The module switches on immediately: BMS failure.",
    "LED: оранжев, бавно мига" to "LED: orange, blinks slowly",
    "Самопроверката е неуспешна, модулът е изключен." to "Self-test failed, the module is off.",
    "Виж раздел 5.1 на ръководството." to "See section 5.1 of the manual.",
    "LED: оранжев, бързо мига" to "LED: orange, blinks fast",
    "Черният старт е неуспешен." to "Black start failed.",
    "Изчакай над 5 минути преди нов опит." to "Wait more than 5 minutes before trying again.",
    "LED: оранжев, свети, SOC в синьо" to "LED: orange, on, SOC in blue",
    "Загубена комуникация или грешка на BMS." to "Lost communication or BMS error.",
    "Няма изход, няма LED" to "No output, no LED",
    "Контролерът не се включва." to "The controller does not switch on.",
    "Натисни старта поне 5 s (10 s при черен старт)." to "Press start for at least 5 s (10 s for a black start).",
    "Провери батерията на контролера и напрежението (минимум 2 модула)." to "Check the controller battery and the voltage (at least 2 modules).",
    "Бавно оранжево мигане след включване" to "Slow orange blinking after switch-on",
    "Напрежението от DC страната се различава от батерията с повече от 20 V." to "The DC-side voltage differs from the battery by more than 20 V.",
    "Махни DC напрежението или го настрой, после повтори старта." to "Remove the DC voltage or adjust it, then repeat the start.",
    "Постоянен зумер" to "Constant buzzer",
    "Реле залепнало." to "Relay stuck.",
    "Изключи всичко DC и рестартирай." to "Turn off all DC and restart.",
    "Статус оранжев, модулите сини" to "Status orange, modules blue",
    "Загубена комуникация с инвертора, защита от свръхток или авария на контролера." to "Lost communication with the inverter, overcurrent protection or controller fault.",
    "Провери кабела към инвертора." to "Check the cable to the inverter.",
    "Провери DC страната." to "Check the DC side.",
    "Статус оранжев, модулите оранжеви" to "Status orange, modules orange",
    "Защита от температура, свръхнапрежение или ниско напрежение." to "Temperature, overvoltage or low voltage protection.",
    "При свръхнапрежение провери настройката на зареждането." to "For overvoltage, check the charging setting.",
    "При ниско напрежение направи черен старт, после зареди." to "For low voltage, do a black start, then charge.",
    "Всички LED сини, няма изход" to "All LEDs blue, no output",
    "Изгорял предпазител." to "Blown fuse.",
    "Смени контролера." to "Replace the controller.",
    // Батерии: Dyness
    "Dyness Powerbox Pro, 51.2 V / 200 Ah, manual 2024-11-14 (обобщение)" to "Dyness Powerbox Pro, 51.2 V / 200 Ah, manual 2024-11-14 (summary)",
    "Ниско напрежение при разреждане" to "Low voltage during discharge",
    "Зумер, изходът спира. Възстановява се при връщане в диапазона." to "Buzzer, the output stops. It recovers when back in range.",
    "Зареди батерията." to "Charge the battery.",
    "Свръхнапрежение при зареждане" to "Overvoltage during charging",
    "Зареждането спира и се възстановява само." to "Charging stops and recovers by itself.",
    "Свръхток при зареждане над 110 A" to "Overcurrent during charging above 110 A",
    "Токът се ограничава до 3 A, опит след 10 s." to "The current is limited to 3 A, retry after 10 s.",
    "Намали тока под 110 A." to "Reduce the current below 110 A.",
    "Свръхток при разреждане над 115 A" to "Overcurrent during discharge above 115 A",
    "Зумер, изходът спира след 15 s. Възстановява се след 60 s или веднага при зареждане." to "Buzzer, the output stops after 15 s. It recovers after 60 s, or immediately when charging.",
    "Намали товара." to "Reduce the load.",
    "Температура при зареждане извън 0 до +65 °C" to "Charging temperature outside 0 to +65 °C",
    "Зареждането спира." to "Charging stops.",
    "Температура при разреждане извън −20 до +65 °C" to "Discharging temperature outside −20 to +65 °C",
    "Изходът спира." to "The output stops.",
    "Късо съединение при старт" to "Short circuit at start",
    "Първо се задейства DC прекъсвачът." to "The DC breaker trips first.",
    "Намери и махни късото съединение." to "Find and remove the short circuit.",
    "Саморазреждане" to "Self-discharge",
    "При липса на товар 72 часа системата заспива." to "With no load for 72 hours, the system goes to sleep.",
    "Натисни бутона за включване." to "Press the power button.",
    "Аларма: прекомерно разреждане" to "Alarm: over-discharge",
    "Червено свети и зумер." to "Red light and buzzer.",
    "Аларма: висока температура" to "Alarm: high temperature",
    "Червено свети." to "Red light on.",
    "Аларма: ниско напрежение" to "Alarm: low voltage",
    "Жълто свети." to "Yellow light on.",
    "Ниско SOC: 0 < SOC ≤ 10%" to "Low SOC: 0 < SOC ≤ 10%",
    "LED1 свети червено, ALM зелено." to "LED1 red, ALM green.",
    "Спри разреждането и зареди навреме." to "Stop discharging and charge in time.",
    "Комуникация между устройства" to "Communication between devices",
    "Светлинната платка цикли през три цвята." to "The light board cycles through three colours.",
    "Провери външния кабел, после вътрешния." to "Check the external cable, then the internal one.",
    // Батерии: Seplos (битове)
    "Seplos EMU10XX, MODBUS-ASCII V2.0, битовете на предупрежденията (обобщение)" to "Seplos EMU10XX, MODBUS-ASCII V2.0, warning bits (summary)",
    "Авария на сензора за напрежение." to "Voltage sensor fault.",
    "Авария на сензора за температура." to "Temperature sensor fault.",
    "Авария на сензора за ток." to "Current sensor fault.",
    "Авария на силовия ключ." to "Power switch fault.",
    "Авария на сензора за разлика в напреженията на клетките." to "Cell voltage difference sensor fault.",
    "Авария на ключа за зареждане." to "Charge switch fault.",
    "Авария на ключа за разреждане." to "Discharge switch fault.",
    "Авария на ключа за ограничение на тока." to "Current limit switch fault.",
    "Предупреждение за свръхнапрежение на клетка." to "Cell overvoltage warning.",
    "Защита от свръхнапрежение на клетка." to "Cell overvoltage protection.",
    "Предупреждение за ниско напрежение на клетка." to "Cell undervoltage warning.",
    "Защита от ниско напрежение на клетка." to "Cell undervoltage protection.",
    "Предупреждение за свръхнапрежение на пакета." to "Pack overvoltage warning.",
    "Защита от свръхнапрежение на пакета." to "Pack overvoltage protection.",
    "Предупреждение за ниско напрежение на пакета." to "Pack undervoltage warning.",
    "Защита от ниско напрежение на пакета." to "Pack undervoltage protection.",
    "Предупреждение за висока температура при зареждане." to "High temperature warning during charging.",
    "Защита от висока температура при зареждане." to "High temperature protection during charging.",
    "Предупреждение за ниска температура при зареждане." to "Low temperature warning during charging.",
    "Защита от ниска температура при зареждане." to "Low temperature protection during charging.",
    "Предупреждение за висока температура при разреждане." to "High temperature warning during discharging.",
    "Защита от висока температура при разреждане." to "High temperature protection during discharging.",
    "Предупреждение за ниска температура при разреждане." to "Low temperature warning during discharging.",
    "Защита от ниска температура при разреждане." to "Low temperature protection during discharging.",
    "Предупреждение за висока околна температура." to "High ambient temperature warning.",
    "Защита от висока околна температура." to "High ambient temperature protection.",
    "Предупреждение за ниска околна температура." to "Low ambient temperature warning.",
    "Защита от ниска околна температура." to "Low ambient temperature protection.",
    "Предупреждение за висока температура на компонент." to "High component temperature warning.",
    "Защита от висока температура на компонент." to "High component temperature protection.",
    "Нагряване (групирането в документа е неясно)." to "Heating (the grouping in the document is unclear).",
    "Предупреждение за свръхток при зареждане." to "Overcurrent warning during charging.",
    "Защита от свръхток при зареждане." to "Overcurrent protection during charging.",
    "Предупреждение за свръхток при разреждане." to "Overcurrent warning during discharging.",
    "Защита от свръхток при разреждане." to "Overcurrent protection during discharging.",
    "Защита от кратковременен свръхток." to "Short-time overcurrent protection.",
    "Защита от късо съединение на изхода." to "Output short-circuit protection.",
    "Блокировка при кратковременен свръхток." to "Lock after a short-time overcurrent.",
    "Блокировка при късо съединение на изхода." to "Lock after an output short circuit.",
    "Защита от високо напрежение при зареждане." to "High voltage protection during charging.",
    "Изчакване на прекъсващо захранване." to "Waiting for an interrupted power supply.",
    "Предупреждение за остатъчен капацитет." to "Residual capacity warning.",
    "Защита за остатъчен капацитет." to "Residual capacity protection.",
    "Забрана за зареждане при ниско напрежение на клетка." to "Charging banned at low cell voltage.",
    "Защита от обратна връзка на изхода." to "Output reverse-feed protection.",
    "Авария на връзката на изхода." to "Output connection fault.",
    // Общо
    "Кодовете са за VE.Bus устройства, например MultiPlus и Quattro. Не са кодовете на инвертор, който не е VE.Bus." to "Codes are for VE.Bus devices such as MultiPlus and Quattro. They are not codes for an inverter that is not VE.Bus.",
)

/** Текстът на езика на приложението. На български се връща както е. */
private fun tr(s: String): String =
    if (pick("bg", "en") == "bg") s
    else EN[s] ?: s.replace("(обобщение)", "(summary)").replace("стр. ", "p. ")

/** Кодът на грешката на езика на приложението. Битовете на Seplos се показват като „bit“. */
private fun trCode(s: String): String =
    if (pick("bg", "en") == "bg") s
    else EN[s] ?: s.replace(", бит ", ", bit ")

/** Уникален ключ на грешка в падащото меню. */
private fun faultKey(f: FaultInfo): String = f.source + "|" + f.code + "|" + f.meaning

/** Кодът и краткото име, например „F01 · DC_Inversed_Failure“. Без модел. */
private fun shortLabel(f: FaultInfo): String {
    val code = trCode(f.code)
    val short = tr(f.meaning).split(": ", ". ").first().trim().take(60)
    return if (short.isEmpty() || short == code) code else "$code · $short"
}

/** Падащо меню с всички грешки за избрания раздел. */
@Composable
private fun FaultDropdown(items: List<FaultInfo>, selectedKey: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val picked = items.firstOrNull { faultKey(it) == selectedKey }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(picked?.let { shortLabel(it) } ?: pick("Избери грешка", "Choose an error"), maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                items.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(shortLabel(f)) },
                        onClick = {
                            onSelect(faultKey(f))
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/** Значението и стъпките за избраната грешка. Моделът е тук, за точност. */
@Composable
private fun FaultDetail(f: FaultInfo, model: String) {
    Text(trCode(f.code), fontWeight = FontWeight.Bold)
    Text(pick("Модел: ", "Model: ") + tr(model), style = MaterialTheme.typography.bodySmall)
    Text(tr(f.meaning))
    f.steps.forEach { Text("• ${tr(it)}") }
}

/** Бележка над полето за търсене. Null, ако марката няма кодове. */
private fun faultNote(brand: Brand): String? = when (brand) {
    Brand.DEYE -> pick(
        "Кодовете са обобщени от ръководството на инвертора, стр. 42–44. Други модели може да имат различни кодове.",
        "Codes are summarized from the inverter manual, pages 42–44. Other models may have different codes.",
    )
    Brand.GROWATT -> pick(
        "Кодовете са обобщени от ръководствата на Growatt. Кодовете се различават по модел, затова моделът е в описанието на всеки код.",
        "Codes are summarized from the Growatt manuals. Codes differ by model, so the model is in each code's description.",
    )
    Brand.VICTRON -> pick(
        "Кодовете са за VE.Bus устройства, например MultiPlus и Quattro. Не са кодовете на инвертор, който не е VE.Bus.",
        "Codes are for VE.Bus devices such as MultiPlus and Quattro. They are not codes for an inverter that is not VE.Bus.",
    )
}

/** Стъпките за вход за марката. За Deye са по официалната документация, без да са проверени в портала. */
private fun loginSteps(brand: Brand): List<String> = when (brand) {
    Brand.DEYE -> listOf(
        pick("Създай акаунт в Deye Cloud на deyecloud.com/login.", "Create a Deye Cloud account at deyecloud.com/login."),
        pick("Влез в портала за разработчици на developer.deyecloud.com и създай приложение.", "Sign in to the developer portal at developer.deyecloud.com and create an application."),
        pick("На страницата на приложението (developer.deyecloud.com/app) вземи AppId и AppSecret.", "On the application page (developer.deyecloud.com/app) get the AppId and AppSecret."),
        pick("В „Настройки“ избери марка Deye и попълни AppId, AppSecret, имейла и паролата от Deye Cloud.", "In Settings choose Deye and enter the AppId, AppSecret, your Deye Cloud email and password."),
        pick("Натисни „Запази и тествай връзката“.", "Press “Save and test connection”."),
        pick("Ако тестът мине, ще видиш станцията и инвертора си.", "If the test passes, you will see your station and inverter."),
    )
    Brand.GROWATT -> listOf(
        pick("Отвори „Настройки“ и избери марка Growatt от падащото меню.", "Open Settings and choose Growatt from the drop-down."),
        pick("Попълни API токена от Growatt Cloud.", "Enter the API token from Growatt Cloud."),
        pick("Натисни „Запази и тествай връзката“.", "Press “Save and test connection”."),
    )
    Brand.VICTRON -> listOf(
        pick("Отвори „Настройки“ и избери марка Victron от падащото меню.", "Open Settings and choose Victron from the drop-down."),
        pick(
            "Попълни VRM access token. Приложението го изпраща като „Token …“. Bearer токените са оттеглени от 1 юни 2026.",
            "Enter the VRM access token. The app sends it as “Token …”. Bearer tokens were withdrawn on 1 June 2026.",
        ),
        pick("Натисни „Запази и тествай връзката“.", "Press “Save and test connection”."),
    )
}

private fun faq(): List<Pair<String, String>> = listOf(
    pick("Защо няма нощни данни?", "Why is there no night data?") to pick(
        "Нощните данни се изтеглят при обновяване, за последните 31 дни. Ако Deye Cloud още няма историята, се появяват по-късно.",
        "Night data is downloaded on refresh, for the last 31 days. If Deye Cloud does not have the history yet, it appears later.",
    ),
    pick("Какво е прогнозата?", "What is the forecast?") to pick(
        "Прогнозата е очакваното производство за деня според слънчевата радиация и мощността на панелите. Коригира се с реалните данни от последните дни.",
        "The forecast is the expected production for the day, based on solar radiation and panel power. It is corrected with real data from recent days.",
    ),
    pick("Как се смята спестяването?", "How is the saving calculated?") to pick(
        "Спестяването е оценка: разреденият ток през деня по дневна цена, минус купеният ток през нощта по нощна цена. Показва се за последните 30 дни.",
        "The saving is an estimate: energy discharged during the day at the day price, minus energy bought at night at the night price. It is shown for the last 30 days.",
    ),
)

@Composable
fun HelpScreen(engine: Engine) {
    val ctx = LocalContext.current
    val brand = engine.brand()
    val voice = rememberVoice()
    var helped by remember { mutableStateOf<Boolean?>(null) }
    var errorTab by remember { mutableStateOf(0) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var batteryBrand by remember(brand) { mutableStateOf(defaultBatteryBrand(brand)) }
    var alarms by remember { mutableStateOf<List<String>?>(null) }
    var alarmError by remember { mutableStateOf("") }
    LaunchedEffect(brand) {
        if (brand != Brand.VICTRON) return@LaunchedEffect
        try {
            alarms = withContext(Dispatchers.IO) { engine.activeAlarms() }
            alarmError = ""
        } catch (e: Exception) {
            alarmError = pick("Аварийте не се зареждат: ", "Alarms did not load: ") + (e.message ?: e.toString())
        }
    }
    val steps = loginSteps(brand)
    val listedFaults = faultsFor(brand)
    val faultSource = if (brand == Brand.DEYE) "SUN-5/25K-SG01HP3, стр. 42–44 (обобщение)" else ""
    val listedBattery = batteryFor(batteryBrand)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(pick("Помощ", "Help"), style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pick("Вход в ${brand.cloudName}", "Signing in to ${brand.cloudName}"), style = MaterialTheme.typography.titleMedium)
                steps.forEachIndexed { i, step -> Text("${i + 1}. $step") }
                Text(
                    if (brand == Brand.DEYE) {
                        pick(
                            "Стъпките са по официалната документация на Deye. Провери ги в портала, защото може да са се променили.",
                            "The steps follow Deye's official documentation. Check them in the portal, as they may have changed.",
                        )
                    } else if (brand == Brand.VICTRON) {
                        pick(
                            "Токенът се създава с VRM API (POST /users/{idUser}/accesstokens/create). Документацията не описва първото влизане, нужно за това.",
                            "The token is created with the VRM API (POST /users/{idUser}/accesstokens/create). The documentation does not describe the first sign-in needed for this.",
                        )
                    } else {
                        pick(
                            "Откъде се взима токенът: ще се добави от официалната документация на марката.",
                            "Where to get the token: will be added from the brand's official documentation.",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { if (voice.speaking) voice.stop() else voice.say(if (pick("bg", "en") == "bg") spokenSteps(brand) else loginSteps(brand).joinToString(". ")) }) {
                    Text(
                        when {
                            voice.speaking -> pick("Спри", "Stop")
                            else -> pick("Прочети на глас", "Read aloud")
                        },
                    )
                }
                if (voice.message.isNotEmpty()) Text(voice.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (brand == Brand.VICTRON) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pick("Текущи аварии", "Current alarms"), style = MaterialTheme.typography.titleMedium)
                    val list = alarms
                    when {
                        alarmError.isNotEmpty() -> Text(alarmError, style = MaterialTheme.typography.bodySmall)
                        list == null -> Text(pick("Зареждане…", "Loading…"), style = MaterialTheme.typography.bodySmall)
                        list.isEmpty() -> Text(pick("Няма активни аварии.", "No active alarms."))
                        else -> list.forEach { Text("• $it") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pick("Грешки", "Errors"), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(pick("Инвертор", "Inverter"), pick("Батерия", "Battery")).forEachIndexed { i, title ->
                        if (errorTab == i) {
                            Button(onClick = { errorTab = i; selectedKey = null }, modifier = Modifier.weight(1f)) { Text(title) }
                        } else {
                            OutlinedButton(onClick = { errorTab = i; selectedKey = null }, modifier = Modifier.weight(1f)) { Text(title) }
                        }
                    }
                }
                if (errorTab == 1) BatteryBrandPicker(batteryBrand) {
                    batteryBrand = it
                    selectedKey = null
                }
                Text(
                    if (errorTab == 0) faultNote(brand).orEmpty() else batteryNote(),
                    style = MaterialTheme.typography.bodySmall,
                )
                val shown = if (errorTab == 0) listedFaults else listedBattery
                FaultDropdown(shown, selectedKey) {
                    selectedKey = it
                    helped = null
                }
                val picked = shown.firstOrNull { faultKey(it) == selectedKey }
                if (picked != null) {
                    FaultDetail(picked, picked.source.ifEmpty { faultSource })
                }
                if (errorTab == 0 && picked != null) {
                    Text(pick("Помогна ли ви това?", "Did this help?"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { helped = true }) { Text(pick("Да", "Yes")) }
                        OutlinedButton(onClick = { helped = false }) { Text(pick("Не", "No")) }
                    }
                    when (helped) {
                        true -> Text(pick("Радвам се, че помогна.", "Glad it helped."))
                        false -> OutlinedButton(onClick = {
                            val text = pick("Марка: ", "Brand: ") + brand.label + "\n" +
                                pick("Код на грешката: ", "Error code: ") + trCode(picked.code) + "\n" +
                                pick("Модел и сериен номер: ", "Model and serial number: ")
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            ctx.startActivity(Intent.createChooser(send, pick("Изпрати на сервиза", "Send to service")))
                        }) { Text(pick("Подготви съобщение за сервиза", "Prepare a message for service")) }
                        null -> Unit
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pick("Често задавани въпроси", "Frequently asked questions"), style = MaterialTheme.typography.titleMedium)
                faq().forEach { (q, a) ->
                    Text(q, fontWeight = FontWeight.Bold)
                    Text(a, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Състоянието на четенето: кои гласове има телефонът, дали се чете в момента и какво да се покаже. */
private class VoiceState {
    var tts: TextToSpeech? = null
    var hasBulgarian by mutableStateOf(false)
    var hasEnglish by mutableStateOf(false)
    var speaking by mutableStateOf(false)
    var message by mutableStateOf("")
}

/** Съобщението за липсващ глас на езика на приложението. */
private fun missingVoiceText(): String = pick(
    "На телефона няма български глас. Инсталирай го: в настройките на телефона отвори „Текст към реч“, избери Google текст към реч, натисни зъбното колело, после „Инсталиране на данни за глас“ и избери български.",
    "The phone has no English voice. Install it: in phone settings open “Text-to-speech”, choose Google Text-to-speech, tap the gear icon, then “Install voice data” and choose English.",
)

/** Четене на глас през Android TextToSpeech: на езика на приложението, български (bg-BG) или английски. */
@Composable
private fun rememberVoice(): VoiceState {
    val ctx = LocalContext.current
    val state = remember { VoiceState() }
    DisposableEffect(ctx) {
        val main = Handler(Looper.getMainLooper())
        val tts = TextToSpeech(ctx) { status ->
            val engine = state.tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                state.hasBulgarian = engine.isLanguageAvailable(Locale("bg", "BG")) >= TextToSpeech.LANG_AVAILABLE
                state.hasEnglish = engine.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE
            } else {
                state.message = pick("Телефонът няма работещ синтезатор на реч.", "The phone has no working speech engine.")
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post { state.speaking = false }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { state.speaking = false }
            }
        })
        state.tts = tts
        onDispose {
            tts.shutdown()
            state.tts = null
        }
    }
    return state
}

/** Чете текста на езика на приложението. Ако за този език няма глас, показва как да се инсталира. */
private fun VoiceState.say(text: String) {
    val bg = pick("bg", "en") == "bg"
    if (if (bg) !hasBulgarian else !hasEnglish) {
        message = missingVoiceText()
        return
    }
    message = ""
    tts?.setLanguage(if (bg) Locale("bg", "BG") else Locale.US)
    speaking = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "help") == TextToSpeech.SUCCESS
}

private fun VoiceState.stop() {
    tts?.stop()
    speaking = false
}

/** Текстът за четене: само български думи, без латински термини и кодове, за да се чете правилно. */
private fun spokenSteps(brand: Brand): String = when (brand) {
    Brand.DEYE ->
        "Създай акаунт в Дийе Клауд. Влез в портала за разработчици и създай приложение. " +
            "На страницата на приложението вземи идентификатор на приложението и секретен ключ. " +
            "В настройките избери марка Дийе и попълни идентификатора, секретния ключ, имейла и паролата. " +
            "Натисни Запази и тествай връзката. Ако тестът мине, ще видиш станцията и инвертора си."
    Brand.GROWATT ->
        "Избери марка Грауат в настройките. Попълни токена от Грауат Клауд. " +
            "Натисни Запази и тествай връзката."
    Brand.VICTRON ->
        "Избери марка Виктрон в настройките. Попълни токена за достъп от VRM. " +
            "Той се изпраща като токен. Старият вид токени е оттеглен от първи юни. " +
            "Натисни Запази и тествай връзката."
}
