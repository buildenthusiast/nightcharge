# Victron (VRM): API достъп, endpoint-и и кодове за грешки

Проверено: 2026-10-15.

Източник за раздел 1 и 3: официалната VRM API документация https://vrm-api-docs.victronenergy.com/ (JSON API v2, live сървър `https://vrmapi.victronenergy.com/v2`). Текстът е копиран от потребителя от същата страница, без промени. Страницата сама не се чете от прокси сесията.

## 1. Вход и токен

- **Заглавка:** `x-authorization: Token <token_value>`. Токенът е JWT.
- **Access token (препоръчан):** формат `Token <token_value>`. Създава се с `POST /users/{idUser}/accesstokens/create`. Документацията не описва първото влизане, нужно за този endpoint.
- **Bearer token:** формат `Bearer <token_value>`. Оттеглен от **1 юни 2026**. Примерите в документацията още го показват, но трябва да се мигрира към access token.
- **Лимит на заявките:** около 200 заявки в плъзгащ прозорец (средно до 3 в секунда). При 429 с JSON отговор чети заглавката `Retry-After`.
- **Аларми и отчети:** експорт на отчети (CSV и XLSX за енергия и аларми, и агрегиране на аларми от няколко инсталации) е в **vrm-reports-api**: https://vrm-reports-api.victronenergy.com/docs (`POST /alarms-report`).
- **Поддръжка:** Victron не дава директна поддръжка за този API. Препоръчва форума Modifications в Victron Community.
- **Форум за токени (от общността, не от документацията):** https://community.victronenergy.com/t/vrm-api-preferring-access-tokens-to-user-pass-for-security/11295

## 2. Кодове за грешки (VE.Bus, официален източник)

Източник: официалното ръководство Victron MultiPlus-II GX, „Error indications“ (раздел 12.2):
https://www.victronenergy.com/media/pg/MultiPlus-II_GX/en/error-indications.html

Важно: това са кодове за VE.Bus устройства (MultiPlus, Quattro). Те не са кодовете на инвертора на Deye. Текстът е парафраза.

| Код | Значение | Препоръка |
|---|---|---|
| 1 | Устройството се изключи, защото друга фаза в системата се е изключила. | Намери и провери фазата, която е отказала. |
| 3 | Открити са повече или по-малко устройства от очакваните. | Преконфигурирай системата. Ако продължава: провери комуникационните кабели, после изключи и включи всичко. |
| 4 | Не е открито друго устройство. | Провери комуникационните кабели. |
| 5 | Напрежението на AC изхода е твърде високо. | Провери AC кабелите. |
| 10 | Грешка при синхронизация на времето в системата. Не трябва да се случва при правилен монтаж. | Провери комуникационните кабели. |
| 14 | Устройството не може да предава данни. | Провери комуникационните кабели за късо съединение. |
| 17 | Устройство стана master, след като оригиналният master отказа. | Провери отказалото устройство и комуникационните кабели. |
| 18 | Настъпило е пренапрежение. | Провери AC кабелите. |
| 22 | Устройството не може да работи като slave. Остарял, неподходящ модел. | Смени устройството. |
| 24 | Задейства се защита при превключване. Не трябва да се случва при правилен монтаж. | Изключи и включи всичко. Ако се повтаря, прегледай монтажа. Може да вдигнеш долния AC вход до 210 VAC (фабрично 180 VAC). |
| 25 | Несъвместим фърмуер: фърмуерът на едно устройство е твърде стар за останалите. | Изключи всичко, включи устройството с грешката, после добавяй останалите едно по едно. Обнови фърмуера на последното включено. |
| 26 | Вътрешна грешка. Не трябва да се случва. | Изключи и включи всичко. Ако продължава, свържи се с Victron. |

## 3. Endpoint-и (от VRM API документацията, копирано от потребителя)

Всички са с базов адрес `https://vrmapi.victronenergy.com/v2` и заглавка `x-authorization`.

### 3.1 Аларми: правила

- **`GET /installations/{idSite}/alarms`**: връща настроените **правила** за аларми (прагове, получатели, налични атрибути). Не е историята на аварии. Празен `alarms: []` означава само, че няма правила.
- **`POST /installations/{idSite}/alarms`**: добавя правило. Тяло: `idDataAttribute`, `instance`, `AlarmEnabled`, `NotifyAfterSeconds`, `highAlarm`, `highAlarmHysteresis`, `lowAlarm`, `lowAlarmHysteresis`. За числа полетата `AlarmEnabled` и `NotifyAfterSeconds` са PascalCase. За enum са camelCase и има `enumValues` (например `[0,4,6]`). Грешен регистър дава предупреждение.
- **`PUT /installations/{idSite}/alarms`**: редактира правило със същото тяло. Правилото се намира по `idDataAttribute` и `instance`.
- **`DELETE /installations/{idSite}/alarms`**: изтрива правило. Тяло: `idDataAttribute` и `instance`. Необратимо.

### 3.2 История на аварии (alarm log)

- **`GET /installations/{idSite}/alarm-log`**: историята на аларми и аварии (мрежа, напрежение, честота, фазова последователност, загуба на мрежа, претоварване, нисък SoC, грешки на инвертора).
  - Запис: `idAlarm`, `idSite`, `instance`, `type`, `description` (например „Grid lost alarm“), `started` (Unix сек.), `cleared` (Unix сек. или `null`), `idDataAttribute`, `isActive` (1 или 0), `device` (например „Multi“), `customName`, `nameEnum`.
  - Продължителност: `cleared - started`.
  - Активните записи са първи, после приключените, и двете по `started DESC`.
  - Параметри: `start` и `end` (Unix сек., заедно), `page` (от 1, по подразбиране 1), `count` (по подразбиране 3000). Отговорът има `num_records` с общия брой.
  - Филтрирането по тип, фаза или устройство се прави от клиента по `type`, `description`, `instance`, `device`.
  - Кодове на атрибути: `agl` (загуба на мрежа), `aPr` (фазова последователност), `meHV` и `meLV` (високо и ниско напрежение), `iE` (грешка на инвертора). Съпоставят се през `idDataAttribute` и полетата `description` и `nameEnum`.
- **`POST /installations/{idSite}/clear-alarm`**: маркира аларма като прегледана в Event Logs. Тяло: `{"alarmId": <число>}`. Отговор: `success` и `idSite`. **Това е запис**, не изтрива историята.

### 3.3 Устройства и диагностика

- **`GET /installations/{idSite}/system-overview`**: свързаните устройства на инсталацията (`records.devices[]`: име, модел, фърмуер, `lastConnection`, `class`, `instance`, `idDeviceType`, `settings` и `attributes`, `identifier`, `signalRSSI`, `loggingInterval`, `remoteSupport` и други). Достъпен само за потребители с достъп до инсталацията (при липса на достъп: 403).
- **`GET /installations/{idSite}/diagnostics`**: лог данни за инсталацията. Параметри: `count` (по подразбиране 100) и `page` (по подразбиране 1). Запис: `timestamp`, `Device`, `instance`, `idDataAttribute`, `description`, `formatWithUnit`, `code`, `bitmask`, `formattedValue`, `rawValue`, `id`, `dataAttributeEnumValues` (за enum атрибути). Отговорът има `num_records`.
  - **За текущата авария на инвертора:** документацията не посочва конкретен код за авария на инвертор в този endpoint. Атрибутът `iE` (грешка на инвертора) от alarm-log е най-близкият кандидат. Трябва да се провери с реална инсталация, защото не е потвърдено в документацията.

### 3.4 Dynamic ESS (планиране на батерията)

Тези endpoint-и са за планиране на зареждането и разреждането на батерията според цените. Може да са важни за проекта за нощно зареждане.

- **`GET /installations/{idSite}/dynamic-ess`**: данни за планировчика. Параметър: `start` (Unix сек., по подразбиране текущият час). Връща `dynamicEssSettings`, `historicalData` (`essMinSoc`, `lastHourConsumption`, `lastHourProduction`), `identifier`, `timezone`, `currency`, `vehicles[]`, `evChargingSessions[]`. Кодове: 200, 400, 403, 404.
- **`GET /installations/{idSite}/dynamic-ess-settings`**: настройки на Dynamic ESS. Параметър в пътя: `id`. Полета: `batteryCapacity` (kWh), `batteryCosts`, `batteryFlowRestriction` (`unrestricted`, `noExport`, `noImport`), `batteryPrice`, `buyPriceFormula` и `sellPriceFormula` (например `1.21*p+0.04`, където `p` е суровата цена), `buyPriceSchedule` и `sellPriceSchedule` (по дни и часови интервали), `buyPriceType` и `sellPriceType` (0 или 1), `chargePower` и `dischargePower` (kW), `gridSell` (0 или 1), `idBiddingZone`, `isOn`, `isGreenModeOn`, `isDessSocDifferent`, `maxPowerFromGrid` и `maxPowerToGrid` (kW), `isPeriodicFullChargeOn`, `periodicFullChargeDuration` (часа), `periodicFullChargeInterval` (дни), `vehicle_data` (по VIN).
- **`POST /installations/{idSite}/dynamic-ess-settings`**: записва същите настройки. **Това е запис** и променя поведението на батерията.

### 3.5 Dynamic ESS за превозно средство

- **`GET /installations/{idSite}/vehicle-dess-settings?idVehicle=<id>`**: настройки за едно превозно средство: `targetSOC`, `handOffTime` (HH:MM), `optimalChargingEnabled`, `chargeNowTriggeredAt`, `customMaxChargeRate`, `customBatteryCapacity`, `customMinChargeRate` (по подразбиране 6.9 kW), `defaults`, `effective`, `isTargetSocEditable`. Полетата `targetSoc` и `isTargetSocEditable` на корешта са оттеглени, използвай `effective`.

### 3.6 Dynamic ESS за превозно средство: запис

Източник: същата VRM API документация, копирана от потребителя.

- **`POST /installations/{idSite}/vehicle-dess-settings`**: създава настройките. Тяло: `idVehicle` (задължително), `targetSOC` (0–100), `handOffTime` (HH:MM, 24 ч.), `optimalChargingEnabled`, `customMaxChargeRate`, `customBatteryCapacity`, `customMinChargeRate`. Отговорът е като при GET.
- **`PUT /installations/{idSite}/vehicle-dess-settings`**: заменя всички настройки за превозното средство (пълна замяна).
- **`PATCH /installations/{idSite}/vehicle-dess-settings`**: частична промяна със същите полета.
- **`DELETE /installations/{idSite}/vehicle-dess-settings?idVehicle=<id>`**: изтрива настройките. Отговор: `success` и `message`.
- Важно за всички записи: ако `isTargetSocEditable` е `false` (колата сама докладва целевия SOC през Enode), `targetSOC` се отхвърля.
- Формата на `handOffTime` е проверена с шаблон `^([01]?[0-9]|2[0-3]):[0-5][0-9]$`.

### 3.7 Персонализиран график за EV, за текущата сесия

- **`POST /installations/{idSite}/custom-ev-schedule`**: задава график, който важи само за текущата сесия за зареждане. Тяло: `idVehicle` (задължително), `timestamp` (Unix сек., до кога да е готово), `targetSoc` (0–100), `handOffTime` (HH:MM). Поне едно от `timestamp` или `handOffTime` трябва да е подадено. Ако `timestamp` липсва, се смята от `handOffTime`.
- Графикът се пази в Redis и се изчиства, когато колата се включи отново.
- Ако `isTargetSocEditable` е `false`, `targetSoc` се отхвърля.
- Кодове: 200 (записано), 400, 422, 500. Отговор: `success`.

### 3.8 GPS проследяване

- **`GET /installations/{idSite}/gps-download?start=<unix>&end=<unix>`**: връща GPS трасетата за период като KML файл (`application/vnd.google-earth.kml+xml`). И двата параметъра са задължителни.

### 3.9 Снимки на инсталацията

- **`POST /installations/{idSite}/photo-upload?is_avatar=<0|1>`**: качва една снимка (`multipart/form-data`, поле `data`). Приема JPEG, PNG, WebP и HEIC/HEIF. Снимката се прекодира в WebP (до 1920x1080, плюс миниатюра 150x150). EXIF и GPS данни се премахват. Ограничения: 10 MiB на файл, до 10000x10000 пиксела, съотношение до 10:1, до 100 снимки на инсталация. Изисква достъп Full Control или Technician.
- Отговор: `success`, `data` с `idSite`, `imageName` (.webp), `url` и `is_avatar`. При грешка `success` е `false` и `errors` изброява причините.

### 3.10 Етикети на инсталацията

- **`GET /installations/{idSite}/tags?filter=<user|team|group|predefined>`**: етикетите, групирани по източник (`predefined`, `group`, `team`, `user`). Филтърът е по избор.
- **`PUT /installations/{idSite}/tags`**: добавя етикет. Тяло: `tag` и `source` (например `user`). Отговор: `success`, `idSite`, `data`.
- **`DELETE /installations/{idSite}/tags`**: премахва етикет. Тяло: `tag` и `source`. Отговор: `success`, `idSite`, `data`.
- **`POST /installations/{idSite}/bulk-tags`**: добавя етикети на няколко инсталации. Тяло: `tags` (масив, задължителен, етикетите се свалят до малки букви и се подрязват), `siteIds` (допълнителни инсталации), `siteGroupIds` (групи инсталации). Инсталацията от пътя винаги получава етикетите. Дублиращите се назначения се пропускат. Отговор: `success`, `tags`, `siteIds` (всички получили), `siteGroupIds`.

### 3.11 Експорт на данни (остарял)

- **`GET /installations/{idSite}/data-download`**: **оттеглен (Deprecated)** в документацията. Връща base64 низ с данни за инсталацията. Параметри: `datatype` (`log`, `benchmark`, `kwh`, по подразбиране `log`), `format` (`csv`, `excel`, `xml`, `xlsx`, `html`, по подразбиране `csv`), `start` (по подразбиране преди един ден), `end` (по подразбиране сега), `debug` (само за администратори), `async` (резултатът се праща на имейла на акаунта). Отговор: `success`.
- Вместо това ползвай vrm-reports-api за отчети и експорт: https://vrm-reports-api.victronenergy.com/docs.

### 3.12 Статистика

- **`GET /installations/{idSite}/stats`**: статистика за период. Параметри: `type` (`venus`, `dc_inputs`, `live_feed`, `consumption`, `solar_yield`, `kwh`, `generator`, `generator-runtime`, `custom`, `forecast`; по подразбиране `live_feed`), `interval` (`15mins`, `hours`, `2hours`, `days`, `weeks`, `months`, `years`; по подразбиране `hours`), `start` и `end` (Unix сек.; по подразбиране един ден назад и сега), `attributeCodes[]` (задължителен при `type=custom`, повтаря се за всеки атрибут), `show_instance` (групиране по инстанция). Отговор: `success`, `records` (серии по атрибут) и `totals`.
- Максимален период според интервала: 31 дни за 15 мин и часове, 180 дни за дни, 140 дни за седмици, 24 месеца за месеци, 5 години за години.
- **`GET /installations/{idSite}/overallstats`**: общи суми и проценти за последните година, месец, седмица и днес. Параметри: `type` (същите стойности без `dc_inputs`, `forecast` и `venus`-варианти както е в документацията) и `attributeCodes[]` при `custom`. Отговор: `success` и `records` с `year`, `month`, `week`, `today`, всяка с `totals` и `percentages`.

### 3.13 Настройки и покана

- **`POST /installations/{idSite}/settings`** (в документацията параметърът в пътя е `id`): променя настройките на инсталацията. Може да се подадат само някои полета: `description` (име), `notes`, `phonenumber`, `noDataAlarmTimeout`, `noDataAlarmActive`, `geofence` и `geofenceEnabled`, `alarmMonitoring`, `realtimeUpdates`, `restrictNodeRed`, `inverterChargerControl`, `favorite`. **Това е запис.** Особено `inverterChargerControl` влияе на управлението на инвертора. Отговор: `success`, `idSite`, `data`.
- **`POST /installations/{idSite}/invite`**: изпраща покана на потребител за достъп до инсталацията. Тяло: `name`, `email`, `accessLevel` (0 = демо, 1 = потребител, 2 = администратор). Отговор: `success`, `user`, `data` с `idUser`, `idSite`, `created`, `idInvite`. **Изпраща имейл на друг човек**, затова се прави само след потвърждение.

### 3.14 Потребители на инсталацията

- **`GET /installations/{idSite}/users`**: потребители, чакащи покани, чакащи заявки за достъп, потребителски групи и групи инсталации. Нивата на достъп: `0` = наблюдение, `1` = пълен контрол, `2` = техник. Потребител с наблюдение вижда само собственото си членство. Отговор: `success`, `users` (`idUser`, `name`, `email`, `accessLevel`), `invites` (`idInvite`, `email`, `name`, `accessLevel`, `created`), `pending` (`idUser`, `name`, `email`), `userGroups` (`idUserGroup`, `name`, `accessLevel`), `siteGroups` (`idSiteGroup`, `name`, `idOwner`, `adminName`).

- **`POST /installations/{idSite}/unlink-user`**: премахва достъпа на потребител до инсталацията. Тяло: `idUser` (задължително). Който извиква, трябва да има пълен контрол или техник достъп, освен ако премахва себе си. Последният администратор не може да бъде премахнат. **Това е запис.** Отговор: `success`, `idSite`, `error_code` и `errors`. Възможни `error_code`: `min_one_admin_required`, `user_linked_via_team` (достъпът е през екип или група, управлява се там), `user_not_unlinked`.

- **`POST /installations/{idSite}/unlink`**: премахва достъпа на текущия потребител до инсталацията. Без тяло. Инсталацията трябва вече да е свързана с акаунта. **Това е запис.** Отговор: `success`, `idSite`, `error_code` и `errors`. Възможни `error_code`: `site_not_linked`, `installation_linked_via_team` (достъпът е през екип или група, напусни там), `installation_was_not_unlinked`.

- **`POST /installations/{idSite}/user-rights`**: сменя нивата на достъп на потребители. Тяло: `idUser` (масив) и `accessLevel` (масив със същата дължина; `0` = наблюдение, `1` = пълен контрол, `2` = техник). Отказва, ако инсталацията остане без администратор (ниво 1). Изисква пълен контрол или техник. **Това е запис.** Отговор: `success`, `error_code` и `errors`. Възможни `error_code`: `min_one_admin_required`, `accesslevel_and_user_required`.
- **`POST /installations/{idSite}/invite-rights`**: сменя нивата на достъп на чакащи покани. Тяло: `email` (масив) и `accessLevel` (масив със същата дължина). Променя само вече съществуващи покани. Изисква пълен контрол или техник. Отговор: `success`, `records` (имейл → резултат), `error_code` и `errors`. Възможен `error_code`: `accesslevel_and_invite_keys_required`.

- **`POST /installations/{idSite}/link-user-groups`**: свързва потребителски групи с инсталацията. Тяло: `userGroups` (масив от обекти с `idUserGroup` и `accessLevel`: `0` = наблюдение, `1` = пълен контрол, `2` = техник). Изисква пълен контрол или техник. Отговор: `success`.

- **`POST /installations/{idSite}/user-group-access-level`**: сменя нивото на достъп на потребителска група. Тяло: `idUserGroup` и `accessLevel` (`0`, `1`, `2`, или `null`). При `null` групата се отвързва от инсталацията. Изисква пълен контрол или техник. **Това е запис.** Отговор: `success`.

### 3.15 Нулиране на прогнозите

- **`POST /installations/{idSite}/reset-forecasts`**: записва текущото време и `resetType` в InfluxDB. Прогнозният модел игнорира всички данни преди този момент, така инсталацията се третира като нова. **Това е запис.** Отговор: `success`.
- **`GET /installations/{idSite}/reset-forecasts`**: връща `last_reset` (Unix време на последното нулиране, или `0`, ако никога не е нулирано).

### 3.16 Персонални уиджети

- **`GET /installations/{idSite}/custom-widget?idCustomWidget=<id>`**: връща данните на един персонализиран уиджет (`widget`). Параметрите `idCustomWidget` и `idSite` са задължителни.

- **`POST /installations/{idSite}/custom-widget`**: създава уиджет. Тяло: `name`, `onlyAdmins` (0 или 1), `yAxisScaling`, `parameters` (обект). Отговор: `success`, `idCustomWidget`.
- **`PATCH /installations/{idSite}/custom-widget`**: променя уиджет. Тяло: `idCustomWidget` и полетата за промяна (`name`, `onlyAdmins`, `yAxisScaling`, `parameters`). Отговор: `success`.
- **`DELETE /installations/{idSite}/custom-widget`**: изтрива уиджет. Тяло: `idCustomWidget`. Отговор: `success`.

### 3.17 Обзор на инсталациите и търсене

- **`POST /installation-overview-download`** (без `idSite` в пътя): обзор на избрани инсталации с избрани колони. Тяло: `columns` (обект с `header`, `enabled`, `hide` за всяка колона), `siteIds` (масив), `onlyVisible` (булево), `jsonResponse` (булево; при `false` връща XLSX). Отговор при JSON: `success` и `data` по инсталация (например „Installation name“, „Battery Voltage“, „Battery %“, „Battery Mode“, „Load“, „Solar Yield“, „Grid“, „Generator“, „Tanks“, „Temperature“, „Last Update“).
- **`GET /installation-data-attributes?query=<filter>`**: намира инсталации, чиято последна стойност отговаря на условията. Филтърът е разделен със запетая, например `bs>=50,au=(1,2),IV1!`. Не-администраторите могат да ползват до 5 условия. Параметри: `count` (по подразбиране 100, макс. 1000) и `page` (от 1). Отговор: `success`, `records` (`idSite`, `description`, `lastTimestamp` и стойностите по атрибут), `attributes` (`idDataAttribute`, `code`, `dataType`, `formatWithUnit`). Кодове: 200, 401, 422.

### 3.18 Любими

- **`POST /installations/{idSite}/favorite`**: маркира (`favorite: 1`) или премахва (`favorite: 0`) инсталацията като любима за текущия потребител. Отговор: `success`, `idSite`, `data`.

## 4. Какво липсва

- Първото влизане за `users/{idUser}/accesstokens/create` (как се взима първоначален token или сесия). Не е описано в страницата, която е копирана.
- Пример с реален отговор от инсталация, за да се потвърди кой `idDataAttribute` е текущата авария на инвертора.
- Таблици с кодове за други Victron устройства (например Inverter RS, SmartSolar).
