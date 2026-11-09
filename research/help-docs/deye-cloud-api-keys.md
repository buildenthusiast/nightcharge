# Deye Cloud: API ключове и токен

Проверено: 2026-10-15. Всички източници са на производителя (Deye) или са официални примери на Deye.

**Ограничение:** прокси сесията не позволява директен достъп до `developer.deyecloud.com` и `deyeinverter.com` (HTTP 403). Съдържанието е прочетено през WebFetch, който връща парафраза, не дословен текст. Кодовете, URL-ите и имената на полета са проверени, но не са копирани буква по буква. Потвърди ги в портала преди употреба.

## Източници

1. Официално GitHub репо с примери на Deye: https://github.com/DeyeCloudDevelopers/deye-openapi-client-sample-code (README и `clientcode/account/obtain_token.py`)
2. Документация на API: https://developer.deyecloud.com/api
3. Бързо начало: https://developer.deyecloud.com/start
4. Страница на приложението в портала (тук се виждат AppId и AppSecret): https://developer.deyecloud.com/app
5. Регистрация на акаунт: https://www.deyecloud.com/login
6. Поддръжка на API: cloudservice@deye.com.cn (според README)

## Стъпки за получаване на ключове

1. Създай акаунт в DeyeCloud (https://www.deyecloud.com/login).
2. Влез в портала за разработчици (https://developer.deyecloud.com) и създай приложение.
3. На страницата на приложението вземи **AppId** и **AppSecret** (https://developer.deyecloud.com/app).
4. Вземи токен чрез `account/token` (виж по-долу).

## Вземане на токен (официален пример)

Според `clientcode/account/obtain_token.py` в официалното репо:

- Метод: **POST**
- URL: `{baseurl}/account/token?appId={appId}`. `baseurl` идва от `clientcode/variable.py` в същото репо.
- Тяло (JSON):
  - `appSecret`: AppSecret на приложението
  - `email`: имейлът за вход (пример казва, че може да е и телефон или потребителско име)
  - `companyId`: `"0"` за личен токен; различно от 0 дава токен на член на фирма
  - `password`: паролата, хеширана със **SHA-256**, изпратена като hex
- Заглавки: само `Content-Type: application/json`. Не се подписва заявката, SHA-256 се прилага само върху паролата.

## Базов адрес в приложението

В `app/src/main/java/bg/solar/nightcharge/data/DeyeApi.kt` (ред 219) BASE е `https://eu1-developer.deyecloud.com/v1.0`. Официалният пример не дава списък по региони. Затова региона трябва да се провери в портала, ако акаунтът е в друг регион.

## Какво не е проверено

- Дали за `station/latest` и `device/latest` текущите имена на параметрите са точно такива, както са в приложението. Не съм видял официалната страница за тези endpoint-и.
- Пълният списък на `clientcode/` подпапките (GitHub дава само първите файлове, а raw/robots блокира част от страниците).
