# Промяна: екран „Данни“ (сурови ключове от Deye Cloud)

Приложи само това. Не променяй настройки и креденшъли. След това компилирай, инсталирай и отвори таб „Данни“.

## 1. data/DeyeApi.kt
Преди функцията `history(...)` добави:

```kotlin
fun rawKeys(): String {
    val a = store.auth()
    if (a.stationId == 0L || a.deviceSn.isBlank()) throw IOException("Първо натисни „Запази и тествай връзката“ в Настройки.")
    val station = call("/station/latest", JSONObject().put("stationId", a.stationId))
    val device = call("/device/latest", JSONObject().put("deviceList", JSONArray().put(a.deviceSn)))
    return "/station/latest\n" + station.toString(2) + "\n\n/device/latest\n" + device.toString(2)
}
```

## 2. Engine.kt
Преди `testConnection()` добави:

```kotlin
fun rawKeys(): String = api.rawKeys()
```

## 3. ui/MainActivity.kt
- Импорти: `android.content.ClipData`, `android.content.ClipboardManager`, `androidx.compose.foundation.text.selection.SelectionContainer`, `androidx.compose.ui.text.font.FontFamily`.
- Табове: `listOf("Начало", "Статистика", "Настройки", "Данни")`.
- `when`: `0 -> Home(engine)`, `1 -> Stats(engine)`, `2 -> SettingsScreen(engine)`, `else -> RawScreen(engine)`.
- Нов composable `RawScreen(engine: Engine)` преди `azimuthLabel`:
  - Заглавие „Сурови данни от Deye Cloud“ и кратък текст, че нищо не се записва.
  - Бутон „Прочети“: на `Dispatchers.IO` вика `engine.rawKeys()`. При грешка показва „Грешка: …“.
  - Бутон „Копирай“: `ClipboardManager.setPrimaryClip(ClipData.newPlainText("Deye", text))`.
  - Текстът в `Card` с `SelectionContainer` и `FontFamily.Monospace`.
