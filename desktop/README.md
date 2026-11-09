# NightCharge за Windows (Desktop)

Compose Multiplatform версия. `core/` (прогноза, планиране, тарифи) и `data/` (Deye Cloud, прогноза за времето, HTTP) са копирани от Android приложението в `app/`. Не са Android-зависими, освен `DeyeAuth.kt`, който е изнесен от `Store.kt`.

Команди (на Windows):
- `gradlew.bat run` за стартиране
- `gradlew.bat packageMsi` или `packageExe` за инсталатор

Още не е направено: `Store` (файлово съхранение вместо SharedPreferences), екраните от `ui/`, фоновото нощно зареждане (планировчик) и автоматичното обновяване през GitHub Releases.
