# Deye Cloud: кое поле носи текущата авария

Проверено: 2026-10-15.

## Резултат

**Не е намерено в официална документация, достъпна от тази сесия.** Не давам име на поле, защото не съм го видял в официален източник.

## Какво е проверено

- https://developer.deyecloud.com/api: страницата върна само мета тагове (SPA без съдържание). Не е прочетена.
- https://developer.deyecloud.com/: същото.
- Официалното GitHub репо https://github.com/DeyeCloudDevelopers/deye-openapi-client-sample-code: README и `obtain_token.py` са прочетени. В тях няма device/alarm/fault пример. Папките `clientcode/` не са изброени напълно.
- Търсене: без официален резултат за alarm полето в Deye Cloud.

## Текущо използвано в приложението

`DeyeApi.kt` вика `/station/latest` и `/device/latest` (BASE `https://eu1-developer.deyecloud.com/v1.0`). Отговорът е JSON; полето за авария още не е идентифицирано в кода. Най-бърз начин: в таб „Данни“ (предложената промяна в `changes/raw-data-screen.md`) да се покаже пълният `device/latest` отговор, когато инверторът има авария. Така се вижда кое поле се променя.

## Какво да се направи

1. Извикай `device/latest` и `station/latest` при активна авария и сравни ключовете.
2. Потърси в портала (https://developer.deyecloud.com/app) страницата с API за аларми и история. Тя не е достъпна от тази сесия.
