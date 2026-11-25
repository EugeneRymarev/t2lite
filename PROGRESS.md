# t2 Lite — прогресс

Лёгкий клиент t2: вход по SMS и просмотр баланса. Собран на основе реверс-инжиниринга
официального приложения «Мой t2» 6.52.0 (пакет `ru.tele2.mytele2`).

## Технологии
- Kotlin + Jetpack Compose (Material3)
- Retrofit 2.11 + OkHttp 4.12 + Gson
- DataStore (хранение токена и номера)
- minSdk 26, compileSdk/targetSdk 35, AGP 8.7.2, Gradle 8.9, Kotlin 2.0.21

## Что сделано
1. **Анализ APK.** Из DEX восстановлен контракт входа и баланса (Retrofit-интерфейсы
   `bu/a`, `bu/b`, `px/a`, перехватчики заголовков `g20/*`).
2. **Контракт восстановлен и реализован:**
   - Шаг 1 — отправка SMS: `POST https://sso.t2.ru/auth/realms/tele2-b2c/credential-management/security-codes`
     заголовок `Content-Type: application/json`, тело `{"username":"7XXXXXXXXXX"}` → `{"security_code_token":"..."}`.
   - Шаг 2 — получение токена: `POST https://sso.t2.ru/auth/realms/tele2-b2c/protocol/openid-connect/token`
     query `?msisdn=<номер>&action=auth&authType=sms`, form-поля:
     `username`, `password` (пусто), `security_code`, `security_code_token`,
     `grant_type=password`, `client_id=android-app`, `password_type=sms_code`, `scope=openid`
     → `{access_token, refresh_token, ...}`.
   - Баланс: `GET https://api.t2.ru/api/subscribers/<номер>/balance`,
     заголовки `Authorization: Bearer <token>`, `X-API-Version: 1`,
     ответ-конверт `{"meta":{...},"data":{"value":...}}`.
   - На каждый запрос ставятся заголовки оригинала: `Tele2-User-Agent`, `X-Request-Id`, `X-Session-ID`.
3. **Приложение:** три экрана — ввод номера → ввод кода из SMS → баланс.
   Токен и номер сохраняются; при повторном запуске сразу открывается баланс.
4. **Проект собран как Gradle-проект** с wrapper'ом — открывается и собирается в Android Studio.

## Как запустить
1. Открыть папку `t2lite` в Android Studio (File → Open).
2. Дождаться Gradle Sync (скачает зависимости).
3. Run ▶ на эмуляторе или устройстве.

## Структура
- `app/src/main/java/ru/myt2lite/data/` — модели, Retrofit-интерфейсы, репозиторий, хранилище токенов.
- `app/src/main/java/ru/myt2lite/ui/` — ViewModel и Compose-экраны.
- `app/src/main/java/ru/myt2lite/MainActivity.kt` — точка входа.

## Замечания / что дальше
- Полную сборку APK в облаке проверить нельзя (Google Maven недоступен из контейнера),
  поэтому финальная проверка сборки — за Android Studio на вашей машине.
- По прошлым заметкам отправка SMS с хоста sso.t2.ru может быть прикрыта anti-bot/WAF.
  Если шаг 1 вернёт 403 HTML — потребуется приблизить трафик к оригиналу
  (cookie-jar, прогревочные запросы). Сейчас выставлены штатные заголовки приложения;
  проверяем на реальном номере.
- Дальше можно добавить: детализацию баланса,
  список услуг/тарифа.

## Следующее списание (2026-11-13)
- На экране баланса — карточка «Следующее списание»: дата, сумма абонплаты и «Пополнить на»
  = max(0, сумма − баланс); если денег хватает — «пополнять не нужно».
- Источники: `GET subscribers/{number}/rests` (`abonentDate`, `tariffCost`) и
  `GET subscribers/{number}/tariff` (`nextChargeDate`/`abonentFeeDate`, `currentAbonentFee`/`abonentFee`).
  Поля взяты из строк DEX оригинала (TariffResidues / tariffresidues_redesign), точная схема
  ответа не подтверждена, поэтому JSON разбирается гибко (поиск ключей), дата сдвигается на +1 с
  как в оригинале (getCorrectedAbonentDate).
- Если что-то не разобралось — в карточке кнопка «Показать ответ сервера».
- Код: `data/NextCharge.kt`, `ui/NextChargeCard.kt`; точечные правки в T2Repository, AuthViewModel, Screens.

## Маркет минут, ГБ и SMS (2026-11-13)
Протокол разобран из RN-бандла оригинала, описание — `t2-api/market.md`.
Код в отдельных файлах, общий `T2Repository` не тронут:
- `data/market/MarketModels.kt`, `MarketApi.kt`, `MarketRepository.kt` — свой OkHttp-клиент,
  `X-API-Version: 2`, тот же токен и deviceId.
- `ui/market/MarketViewModel.kt`, `MarketScreens.kt` — экраны маркета.
- `MainActivity.kt` — кнопка «Маркет минут и ГБ» под балансом и переключение на `MarketRoot`.

Что умеет:
- вкладки ГБ / Минуты / SMS: объёмы на рынке → цены → список лотов → покупка с кодом из SMS
  (`validation/number` с `operation=buyLot`, затем `PUT exchange/lots/bought?validationCode=`);
- «Выставить лот»: объём и цена в пределах правил тарифа, комиссия и сумма «к получению»
  считаются как в оригинале, подсказка «похожих лотов»;
- «Мои лоты»: статистика продаж, купленные пакеты с остатком, активные лоты
  (изменить цену / отозвать), история.

## Чат поддержки (2026-11-13)
Разобрано из APK: чат в «Мой t2» сделан на стороннем **Webim SDK** `ru.webim.sdk:webimclientsdkandroid:3.39.0`
(та же версия, что в оригинале; Maven Central). Код оригинала: `e00/i` (WebimChatRepository), `n00/b`, `os/a`, `g00/g`, `h00/g`.
- Конфиг: `GET https://api.t2.ru/api/subscribers/<номер>/webim/config` (Bearer, X-API-Version: 1) →
  `data.generalConfig{accountName, domain, location}` + `data.vizitor{id, displayName, phone, roamingCountryName, hash, regionName}`.
- Сессия Webim: `accountName = "https://" + domain`, `location = "mobile_android_t2card"` (как в оригинале при обычном входе),
  `visitorFieldsJson = {"fields":{"id","display_name","phone","roaming"?,"regionId"?},"hash":...}` — подпись hash выдаёт сервер t2,
  поэтому оператор видит авторизованного абонента. Пуши выключены, история не хранится локально.
- В клиенте: пакет `ru.myt2lite.chat` (ChatConfigLoader, ChatViewModel, ChatActivity), кнопка «Чат поддержки» на экране баланса,
  ChatActivity в манифесте, зависимость в `app/build.gradle.kts`.
- Умеет: история с подгрузкой при прокрутке вверх, отправка текста, статус чата/оператора, «оператор печатает»,
  кнопки бота (KEYBOARD), открытие вложений оператора по ссылке, «Завершить» чат, «Переподключить» при ошибке.
- Пока НЕ сделано: отправка файлов/фото, пуши о новых ответах, оценка оператора.
- Токен для `webim/config` продлевается автоматически (см. «Сохранение входа»).

## Сохранение входа / автопродление токена (2026-11-13)
Как в оригинале (`ru.tele2.mytele2.auth.auth.data.AuthInterceptor`, `LoginInteractorImpl.refreshToken`,
`AccessTokenRepositoryImpl`, `JWTokenDto(exp=…)`): Bearer в каждый запрос, обновление токена под Mutex,
повтор запроса после 401, выход на экран входа при `INVALID_GRANT`.
- Код: `data/auth/AuthManager.kt` (общий объект + `AuthManager.interceptor`), в `TokenStore` добавлены
  `refreshToken()`, `saveTokens()`, `clearTokens()`.
- Обновление: `POST https://sso.t2.ru/auth/realms/tele2-b2c/protocol/openid-connect/token`,
  form `grant_type=refresh_token`, `refresh_token`, `client_id=android-app` → новая пара access+refresh (обе сохраняются).
- Когда: заранее, если до `exp` access_token меньше 60 с; и при ответе 401 (запрос повторяется один раз).
  Если access_token нет, а refresh_token есть — сессия восстанавливается при старте.
- Если Keycloak ответил 400/401 (refresh_token истёк/отозван) — токены стираются, номер остаётся,
  приложение показывает вход с сообщением «Сессия истекла». Ошибки сети/5xx токены не трогают.
- Подключено во все клиенты: `T2Repository` (баланс, списание), `MarketRepository`, `ChatConfigLoader`.
  Сам Webim-чат токен t2 не использует (только подпись `hash` из конфига).
- Сколько живёт вход без SMS, определяет сервер (`refresh_expires_in` в ответе; пишется в logcat, тег `T2Auth`).

## Остатки по тарифу (2026-11-13)
Схема взята из DTO оригинала (Retrofit `g82/c`, ответ `l82/i`, пакет `l82/e`, услуга `l82/h`):
- `GET https://api.t2.ru/api/subscribers/<номер>/rests?includePackageDescription=true` (Bearer, X-API-Version: 1).
- `data`: `tariffStatus` (active/blocked), `abonentDate`, `tariffCost`, `rests[]`.
- Пакет: `type` (tariff/service), `status` (active/unblocked/blocked), `uom` (mb/gb/min/sms/pcs), `remain`, `limit`, `unlimited`,
  `rollover`, `exchangePackage`, `giftPackage`, `roamingPackage`, `endDay`, `service{name, id, renew, renewDate, packageDescription,
  descriptionRemains, trafficRemainsInfo{daysLimit, daysLeft, remainsText}}`, `shortInfo{shortText, text}`.
- Пакеты, купленные на маркете/подключённые услугой, приходят там же (type=service); помечаются «маркет/обмен».
- На экране баланса карточка «Остатки по тарифу»: Интернет (в ГБ, сервер шлёт МБ), Минуты, SMS — «остаток из N»,
  прогресс-бар, «Пакеты тарифа обновятся <дата>». Нажатие на строку раскрывает пакеты: название, остаток/всего,
  метки (тариф, маркет/обмен, перенос, подарок, роуминг, заблокирован), срок «до <дата>», подсказка сервера.
  В сумму не входят роуминговые и заблокированные пакеты. Безлимит показывается словом «Безлимит».
- Если ответ не разобрался — «Показать ответ сервера».
- Код: `data/Rests.kt` (`RestsSource(context).load(): Result<Rests>`, `parseRests(json)` — можно переиспользовать в виджете),
  `ui/RestsCard.kt` (свой `RestsViewModel`); в `Screens.kt` одна строка `RestsCard(...)`. T2Repository/AuthViewModel не тронуты.
  Перезагружается вместе с балансом (и по кнопке «Обновить»).

## Сборка APK и версии (2026-11-13)
Скрипт `build.ps1` (или `build.bat` — то же самое для cmd и двойного щелчка) в корне t2lite.
- **Без параметров показывает меню.** Enter = собрать release (+1 к версии) и поставить на телефон по USB.
  Ещё пункты: только сборка; поставить последний APK без сборки; debug на телефон; release на эмулятор;
  новая minor-версия; список устройств adb. Из меню окно в конце ждёт Enter, с параметрами — нет.
- Готовый файл: `dist\t2lite-1.0.4-build5-release.apk`. Проверено 2026-11-13: первая сборка 1.0.1 (build 2), 21,6 МБ.
- Параметры для запуска без меню: `-Bump patch|minor|major|code|none`, `-Type release|debug|both`, `-Install`,
  `-Target usb|phone|emulator|all|auto` (по умолчанию usb — телефон по проводу), `-Serial <из adb devices>`,
  `-NoBuild` (поставить последний APK из dist), `-Adb <путь>`, `-Clean`, `-Menu`.
- adb ищется в PATH, `C:\soft\adb`, Android SDK. Скрипт подсказывает, если телефон не разрешил отладку (unauthorized),
  если подпись не совпадает с установленной версией или если телефон запретил установку через USB (Xiaomi).
- Версия хранится в `version.properties` (VERSION_NAME, VERSION_CODE), её читает `app/build.gradle.kts`.
  Если сборка упала, версия откатывается. Сборка из Android Studio версию не меняет, но берёт её из этого же файла.
- APK в `app/build/outputs/apk/...` тоже называется с версией (`t2lite-<версия>-<тип>.apk`, через `base.archivesName`).
- Подпись release: свой ключ `keystore\t2lite-release.jks` + пароль в `keystore.properties` (созданы 2026-11-13 при первой
  сборке, в .gitignore). **Сохраните их копию**: без них обновить установленный release не получится.
  Если keystore.properties нет — release подписывается debug-ключом.
- Release и debug из Android Studio подписаны разными ключами: чтобы поставить release поверх debug, debug-версию
  нужно один раз удалить (`adb uninstall ru.myt2lite`, вход по SMS заново).
- JDK скрипт ищет сам: тот, что выбран в Android Studio (`.gradle/config.properties`), затем JAVA_HOME, jbr студии, `~/.jdks`.

## История
- 2026-11-10: проект создан с нуля в `C:\projects\t2-new\t2lite`; реализованы вход по SMS и баланс.
- 2026-11-13: добавлены дата следующего списания, сумма и сумма к пополнению.
- 2026-11-13: добавлен маркет минут/ГБ/SMS (покупка, продажа, мои лоты) — `ui/market`, `data/market`.
- 2026-11-13: добавлен чат поддержки через Webim SDK 3.39.0.
- 2026-11-13: автопродление токена по refresh_token — повторный вход по SMS больше не нужен, пока жива сессия Keycloak.
- 2026-11-13: виджет домашнего экрана (доплата, баланс, остатки) с настройкой содержимого — `widget/`.
- 2026-11-13: остатки по тарифу (интернет, минуты, SMS) — `data/Rests.kt`, `ui/RestsCard.kt`.
- 2026-11-13: скрипт сборки `build.ps1`/`build.bat` с версиями в `version.properties` и своим ключом подписи release.
- 2026-11-14: напоминания о пополнении баланса за 3 дня до списания — `reminder/` (версия 1.1.0).
- 2026-11-14: R8 + сжатие ресурсов + только arm64 в release — APK 21,4 → 5,7 МБ (1.1.1).
- 2026-11-22: экран «История версий» в приложении — `changelog/`, текст в `app/src/main/assets/changelog.md`.

## Виджет на главном экране (2026-11-13)
Виджет «t2 Lite: баланс и остатки» (Jetpack Glance 1.1.1 + WorkManager 2.9.1). Код — пакет `ru.myt2lite.widget`:
- `T2Widget.kt` — сам виджет (Glance), ресивер `T2WidgetReceiver`, кнопка ↻ (`RefreshAction`). Тап по виджету открывает приложение.
- `WidgetRefresher.kt` — загрузка данных: баланс (`T2Repository.balance`), списание и «Доплатить» (`nextCharge`, data/NextCharge.kt),
  остатки (`RestsSource`, data/Rests.kt). Токен — через `AuthManager` (сам продлевает сессию). Фоновое обновление —
  `WidgetRefreshWorker` (периодически, только при наличии сети), планировщик `WidgetUpdater`.
- `WidgetSnapshot.kt` — кэш последних данных (SharedPreferences, JSON). Если часть запросов упала, показываются прежние значения и ⚠.
- `WidgetSettings.kt` + `WidgetSettingsActivity.kt` — что показывать: баланс, «Доплатить», дата/сумма списания, интернет, минуты, SMS,
  прочие пакеты, «N из M», время обновления; интервал фонового обновления (30 мин / 1 ч / 3 ч / 6 ч). Есть живое превью и кнопка
  «Добавить на экран» (requestPinAppWidget).
- `WidgetFormat.kt` — общие строки для виджета и превью.
- Ресурсы: `res/xml/t2_widget_info.xml`, `res/values/widget_strings.xml`.
- Точечные правки: зависимости в `app/build.gradle.kts`, receiver + activity в манифесте, кнопка «Виджет на экран» в `Screens.kt`,
  2 строки в `AuthViewModel` (обновить виджет после загрузки баланса и после выхода).

Как открыть настройки: кнопка «Виджет на экран» на экране баланса, или долгое нажатие на виджет → «Настроить» (Android 12+).
Если не вошли — виджет пишет «Войдите в t2 Lite».

Обновление 2026-11-13 (компактный вид): подписи убраны, раскладка зависит от размера (Glance SizeMode.Responsive):
1 ряд (2×1…4×1) — «+267,50 ₽ до 23 ноября   923/1102 мин · 12,5 ГБ ↻»; узкий 2×2/3×2 — колонка;
4×2 и больше — доплата с датой сверху, остатки ячейками («923» / «из 1102 мин»). «+сумма» красным = сколько доплатить,
«✓ хватает» — пополнять не нужно. Сумма абонплаты — отдельный тумблер «Сумма списания» (по умолчанию выключен).
По умолчанию виджет ставится 4×1, ужимается до 2×1.

Обновление 2026-11-13 (v1.0.3): раскладка по реальному размеру (SizeMode.Exact): строка при высоте < 80dp,
ячейки остатков при ширине ≥ 250dp, иначе колонка — «дата · баланс» и остатки одной строкой (построчно, если высота ≥ 150dp).
Оформление как в оригинале (WidgetConfigActivity «Мой t2»): Светлое / Тёмное / Как на устройстве + ползунок
«Прозрачность» (0–100%, альфа фона; фоны #FFFFFF / #030405 как bg_widget_white/black). Превью в настройках — на «обоях».

## Исправление «Доплатить 735 вместо 267,50» (2026-11-14)
Сумма абонплаты берётся из `rests.data.tariffCost` (со скидкой, 467,50). Если rests не ответил, код брал сумму из
`subscribers/{n}/tariff` (`currentAbonentFee`/`abonentFee`/`fullAbonentFee`), а там, похоже, полная цена без скидки (935):
935 − 200 = 735. Что сделано (`data/NextCharge.kt`, `widget/WidgetRefresher.kt`):
- rests при сбое запрашивается повторно (через 0,7 с);
- если rests всё же не ответил — берётся последняя абонплата из rests за время работы процесса, затем только
  `currentAbonentFee`, и лишь в крайнем случае `abonentFee`/`fullAbonentFee`; такой результат помечается `feeFromTariff`;
- виджет при `feeFromTariff` оставляет прежнюю абонплату из своего кэша и пересчитывает доплату от свежего баланса;
- в logcat (тег `T2Charge`) пишется, откуда взята сумма — если 735 повторится, будет видно, какой ответ сервера виноват.

## Напоминания о пополнении баланса (2026-11-14, v1.1.0)
Уведомление «Пополните баланс на 267,50 ₽ · Через 2 дня, 23 ноября, списание абонплаты 467,50 ₽» —
каждый день, начиная за 3 дня до списания и в сам день списания, пока на счёте меньше абонплаты.
Код — пакет `ru.myt2lite.reminder`:
- `TopUpReminder.kt` — настройки (`ReminderStore`, SharedPreferences `t2lite_reminder`), проверка `check()`,
  уведомление (канал `topup` «Пополнение баланса»), фоновая задача `TopUpReminderWorker` (WorkManager, раз в час, только с сетью).
- `TopUpReminderCard.kt` — карточка на экране баланса под «Следующим списанием»: тумблер «Напоминать о пополнении»
  (по умолчанию включён), «Не раньше 10:00» (8–20 ч), предупреждение и кнопка «Разрешить уведомления», «Проверить сейчас».
- Данные: `T2Repository.balance()` + `nextCharge()` (data/NextCharge.kt), токен — через `AuthManager.interceptor`.
  Сумма абонплаты — только точная из rests; если rests не ответил (`feeFromTariff`), берётся последняя точная сумма,
  а если её нет — напоминание откладывается до следующей проверки (чтобы не напугать «735 ₽»).
- Правила: не раньше выбранного часа и не позже 22:00; одно напоминание в день (исчезает в полночь);
  до «окна» сервер спрашивается раз в сутки; если денег хватает — уведомление снимается
  (и при открытии приложения, когда баланс уже пополнен).
- Android 13+: разрешение `POST_NOTIFICATIONS` спрашивается один раз при первом показе экрана баланса;
  если запрещено — в карточке красная строка и кнопка (диалог или системные настройки уведомлений).
- Точечные правки: манифест (разрешение), `Screens.kt` (карточка), `MainActivity.kt` (планирование + onResume),
  `AuthViewModel.kt` (`onChargeLoaded` после загрузки списания, `onLogout`).
- Лог: тег `T2Reminder` (результат каждой проверки).

## Размер APK: R8 и только arm64 (2026-11-14, v1.1.1)
- Release: `isMinifyEnabled = true`, `isShrinkResources = true`, `ndk { abiFilters += listOf("arm64-v8a") }` (app/build.gradle.kts). Debug не тронут — там все ABI для эмулятора.
- Keep-правила — `app/proguard-rules.pro`, блок «t2 Lite (R8)»: весь `ru.myt2lite.**`, Retrofit/Gson, Webim (`ru.webim.**`), SQLCipher (`net.sqlcipher.**`), -dontwarn для Tink и OkHttp.
- Было 21,4 МБ (1.1.0): ~12,6 МБ — libsqlcipher.so из Webim SDK под 4 ABI, ~8 МБ — неужатый dex. Стало 5,7 МБ: dex 1,8 МБ, libsqlcipher.so (arm64) 3,3 МБ.
- Если после R8 что-то сломалось (обычно это Gson-модели/рефлексия) — смотреть logcat и `app/build/outputs/mapping/release/mapping.txt`, добавлять -keep.
- Сборку удобнее запускать с `GRADLE_OPTS=-Dorg.gradle.daemon=false`, если вывод скрипта «зависает» из-за демона Gradle.

## Звонки голосового помощника (2026-11-19)
Кнопка «Звонки помощнику» на экране баланса → `VoiceAssistantActivity`: список звонков, принятых автоответчиком
(номер, дата, длительность, краткое содержание, метки «спам» и «не прослушан»), по нажатию — запись с плеером
(▶/пауза, перемотка ползунком) и расшифровка разговора репликами «Помощник» / «Звонящий»; кнопки «Перезвонить» (набор номера)
и «Удалить» (с подтверждением). При открытии непрослушанного звонка он помечается прочитанным на сервере.
Код — пакет `ru.myt2lite.voice`:
- `VoiceModels.kt` — DTO и форматирование (номер, дата, длительность).
- `VoiceRepository.kt` — Retrofit `VoiceApi`, заголовок `channel: voiceSecretaryApp`, X-API-Version 1, токен через `AuthManager.interceptor`;
  скачивание записи в `cacheDir/voice` (тем же клиентом с Bearer, без логирования тела), кэш до 20 файлов.
- `VoicePlayer.kt` — MediaPlayer; `VoiceViewModel.kt` — список с подгрузкой страниц, открытие, прочитано/удаление.
- Точечные правки: `Screens.kt` (кнопка), `AndroidManifest.xml` (activity).
API (из DEX «Мой t2» 6.52.0: Retrofit ss2/b, DTO us2/l, us2/k, маппер rs2/n):
- `GET api/voice-assistant/subscribers/{n}/voice-messages?page=1&per_page=20` → `data: [{type TEXT_MESSAGE|TRANSCRIPT_MESSAGE, id,
  description, is_spam, date, status READ|UNREAD, number, avatar_link, chat_messages[{sender BOT|HUMAN, name, text}],
  audio_link, audio_message, duration(мс)}]`; пагинация в заголовках X-Page, X-Per-Page, X-Total. Страницы с 1.
- `GET .../voice-messages-stat` → `{unread_count}`; `GET .../connected-assistants` → `[{id,name,avatar_link,description}]`.
- `PATCH .../voice-messages/statuses` тело `[{"id":..,"status":"READ"|"DELETE"}]`.
- Запись: оригинал играет `audio_message` (не `audio_link`) и отдаёт плееру access_token → качаем с `Authorization: Bearer`.
- Ошибки meta.status: CLIENT_NOT_FOUND / ACTIVE_ASSISTANT_NOT_FOUND → «помощник не подключён», VOICE_ASSISTANT_DISABLED.
Проверено: компилируется (kotlinc 2.0.21 + Compose Desktop 1.7 + заглушки android.*). Живьём не проверено.
Лог: тег `T2Voice` (загрузка записи, ошибки плеера) + OkHttp-лог запросов.

## Имена из телефонной книги в звонках помощнику (2026-11-21)
- `voice/ContactNames.kt`: поиск имени по номеру через `ContactsContract.PhoneLookup` (варианты +7…, 8…, как пришёл), только с разрешением `READ_CONTACTS` (добавлено в манифест).
- `VoiceViewModel.resolveContacts()`: после загрузки страниц ищет имена для новых номеров в фоне, кэш номер → имя в `VoiceUiState.contactNames`; `onResume` активности перепроверяет (вернулись из настроек).
- UI: в списке и в заголовке карточки — имя контакта, под ним номер; нет в книге или нет доступа — просто номер.
- Разрешение спрашивается один раз при первом открытии списка; при отказе над списком строка «нет доступа к контактам» с кнопкой «Разрешить» (после двух отказов ведёт в настройки приложения). Флаги в SharedPreferences `t2lite_voice`.

## История версий в приложении (2026-11-22)
Экран «История версий»: какая версия установлена и что нового в каждой версии (текущая подсвечена «установлена»).
- Текст — `app/src/main/assets/changelog.md`. Формат: заголовок `## 1.1.6 · build 12 · 22.11.2026`, под ним пункты `- ...`;
  строки с `#` в начале — комментарии. Раздел `## Готовится` — изменения для следующей сборки
  (в приложении виден как «В разработке», только если в нём есть пункты, то есть в debug-сборке из Android Studio).
- **Как обновлять:** после каждой правки дописать строку `- что поменялось` под `## Готовится`. `build.ps1` при сборке
  с новой версией (любой `-Bump`, кроме `none`) сам заменит «Готовится» на `## <версия> · build <N> · <сегодня>` и добавит
  сверху новый пустой «Готовится». Если ничего не записали — версия попадёт в историю с пунктом «Мелкие исправления».
  Если сборка упала, файл откатывается вместе с version.properties.
- Код — пакет `ru.myt2lite.changelog`: `Changelog.kt` (разбор файла, установленная версия из PackageManager),
  `ChangelogActivity.kt` (экран, `ChangelogActivity.start(context)`), `VersionFooter.kt` (строка «Версия X · что нового»).
  Activity в манифесте. Кнопку на экран добавляет тред «Навести порядок в интерфейсе» (вкладка «Настройки» → «История версий»).
- Записи 1.0.1–1.1.6 восстановлены задним числом по этому файлу и датам APK в `dist\`.

## Вкладки внизу экрана (2026-11-22)
Раньше всё (баланс, остатки, списание, напоминания, кнопки чата/звонков/виджета/маркета) было одним длинным столбцом.
Теперь после входа — нижняя панель с 4 вкладками (`ui/MainTabs.kt`, `MainTabs(vm)`; MainActivity выбирает: вход → `AppRoot`, иначе `MainTabs`):
- **Главная**: номер, кнопка ⟳ «Обновить», карточка баланса, «Следующее списание», «Остатки по тарифу».
- **Маркет**: `MarketRoot` прямо во вкладке (раньше — кнопка внизу и флаг `MarketNav.isOpen`, флаг больше не нужен для открытия).
- **Связь**: «Звонки помощнику» (`VoiceAssistantActivity.start`) и «Чат поддержки» (`ChatActivity.start`).
- **Настройки**: напоминания о пополнении (`TopUpReminderCard`), «Виджет» (`WidgetSettingsActivity.start`),
  «История версий» (`ChangelogActivity.start`), «Выйти» с номером.
- «Назад» на любой вкладке, кроме главной, возвращает на «Главную»; в маркете сначала листает его страницы назад.
- `ui/Screens.kt` теперь только вход (номер → код). AuthViewModel при автовходе подставляет сохранённый номер (для шапки).
- Значки: зависимость `androidx.compose.material:material-icons-core` (версия из Compose BOM).

## Финансы: списания, пополнения, выписка на почту (2026-11-24)
Пункт «Финансы» на вкладке «Главная» (под балансом) → `finance/FinanceActivity`. Пакет `ru.myt2lite.finance`:
`FinanceModels.kt` (DTO, даты, `rub()`), `FinanceRepository.kt` (OkHttp + `AuthManager.interceptor`, ошибки через `Errors`, область `Errors.Area.FINANCE`),
`FinanceViewModel.kt`, `FinanceActivity.kt` (месяц ‹ › за последние 12 месяцев, вкладки «Списания»/«Пополнения», заказ выписки).
API из оригинала 6.52.0 (пакеты `ru.tele2.mytele2.expenses`, `.paymenthistory`, `.pep`):
- Списания: `GET subscribers/{n}/charges?month=yyyy-MM` (X-API-Version: 2) → группы `{amount{amount,currency}, type, typeName, subGroups[{name, amount, consumingServices[{billingServiceName, amount}]}]}`.
- Пополнения: `GET subscribers/{n}/payments?fromDate=…&toDate=…` (формат `yyyy-MM-dd'T'HH:mm:ssxxx`) → `[{payDate, sum{amount}, type, description, imageUrl}]`.
- Выписка: 1) `POST /api/subscribers/{n}/action-confirmation/sms/request {"targetSystem":"ds_detalization"}` — SMS-код;
  2) `POST .../action-confirmation/sms/check {"system":"ds_detalization","code","number"}`;
  3) `POST details/subscribers/{n}/details/email {email, format PDF|XLSX, fromDate (00:00:00), toDate (23:59:59), securityCode, serviceId:"ds_detalization", detalizationType ALL|PAID_ONLY|NO_INTERNET|INTERNET_ONLY}`.
  Не больше 6 месяцев за одно письмо. Последняя почта запоминается (SharedPreferences `t2lite_finance`).
Статус: компилируется в облаке (kotlinc + Compose Desktop + заглушки), живьём не проверено.
- 2026-11-24 (после 1.1.9): режим «Лентой» — переключатель `FinanceView` (MONTHS/FEED, хранится в prefs `t2lite_finance` → `view`).
  Лента = месяцы подряд от текущего к старым (до 12 мес.), `FinanceViewModel.loadMoreFeed()` грузит по одному месяцу,
  `FeedList` зовёт его через snapshotFlow, когда до конца списка < 3 строк. Месяц без обеих вкладок (нет сети) — «Повторить».
- 1.1.10 на телефоне: t2 отдаёт charges/payments только за 6 месяцев (текущий + 5), старше — 400 `INVALID_PERIOD`
  «History fetch depth exceeded, maximum allowed history depth is 6 months». `HISTORY_MONTHS = 6`, `oldestMonth = now − 5`;
  в ленте INVALID_PERIOD = конец, не ошибка; текст ошибки для INVALID_PERIOD добавлен в Errors.kt.

## Салоны связи t2 (2026-11-24)
Пакет `ru.myt2lite.offices`: `OfficesActivity` (пункт «Салоны связи t2» во вкладке «Связь»).
В оригинале 6.52.0 своего API со списком салонов нет: экран «Салоны связи T2» (событие `NavigateToOfficesScreen(officesUrl=…)`)
открывает сайт `https://t2.ru/offices?utm_source=my_tele2&utm_medium=app&mode=webView` внутри приложения (адрес из удалённых настроек).
Есть только `GET api/offices/locations/{id}` (один салон: id, title, city, cityId, address, latitude, longitude, locationType, posId, salePointId) — для заказа SIM.
Сайт закрыт проверкой «вы не робот» (ngenix bot-challenge, страница «T2 - validation», HTTP 503, решается JS сам) — поэтому
прямой JSON-список из кода не получить; делаем как оригинал: WebView с JS+cookies, 503 проверки не считается ошибкой.
Ссылки: t2.ru остаются внутри, tel:/geo:/intent:/чужие сайты — в своих приложениях; «Назад» листает страницы сайта.
Местоположение (ACCESS_COARSE/FINE_LOCATION) спрашивается, только если сайт попросит. Статус: не собрано, живьём не проверено.

## Вопросы и ответы (2026-11-24)
Пакет `ru.myt2lite.help`: `HelpActivity` (пункт «Вопросы и ответы» во вкладке «Связь», рядом с чатом).
Как в оригинале 6.52.0 (`ru.tele2.mytele2.qa`, `presentation.support.qa`): один запрос
`GET api/subscribers/{номер}/articles/support/categories` → `data: [{id, name, slug, description, articles: [{id, name, slug, url, description}]}]`.
Поиск — на телефоне по названиям статей (в оригинале `contains`, без учёта регистра, повторы по id убраны); у нас ещё ё=е и по словам.
Статья — веб-страница по `url` (оригинал: `QAWebViewActivity` → `SpecialOpenUrlWebViewActivity`); у нас WebView с JS, к t2.ru добавляется `mode=webView`,
ссылки на t2.ru — внутри, остальное — в своих приложениях, «Назад» листает страницы. Последний удачный список сохраняется в `files/qa_categories.json`
(раздел открывается сразу и без интернета). Ошибки — `Errors.Area.HELP` + `ErrorText`. Статус: не собрано, живьём не проверено.

## Мои заявки (2026-11-24)
Пакет `ru.myt2lite.issues`: `IssuesRepository` (OkHttp + `AuthManager.interceptor`, ошибки `Errors.Area.ISSUES`), `IssuesViewModel`, `IssuesActivity`
(пункт «Мои заявки» во вкладке «Связь», после чата). Как в оригинале 6.52.0 (`ru.tele2.mytele2.issues`, Retrofit `wn0/a`, экран `presentation.support.myissues`):
`GET api/subscribers/{номер}/issues` → `data: [{id, status, creationDate, title, resolveDate, description}]`,
status: `created` («Рассматриваем заявку») | `inProgress` («Готовим решение», «примерно к resolveDate») | `closed` («Заявка решена») | `canceled` («Заявка отменена»).
Порядок как в оригинале: сначала открытые, потом закрытые, новые выше. `description` — текст/ответ специалиста (HTML-теги убираются).
`POST .../issues/{id}/reaction {"status"}` в оригинале есть, у нас не используется. У решённой/отменённой заявки кнопка «Остались вопросы? Написать в чат».
Формат дат сервера не известен — `IssueDates.parse` пробует ISO с поясом/без, дату и dd.MM.yyyy. Статус: не собрано, живьём не проверено.

## Проверка безопасности (2026-11-25)
Ревизия всего проекта (код, манифест, скрипт сборки, .gitignore). Собрать в облаке нельзя, поэтому изменённые
не-UI файлы проверены компиляцией kotlinc 2.0.21 с настоящими OkHttp 4.12 / Retrofit 2.11 / Gson / coroutines 1.9,
классами Android 14 (robolectric android-all) и Webim SDK 3.39.0; DataStore и lifecycle — заглушки. Compose-экраны
целиком не компилировались (только изменённые куски). **После сборки проверить на телефоне: вход сохранился, баланс,
чат, звонки помощника (запись играет), «Вопросы и ответы», «Салоны связи».**

Что изменено:
- **Журнал (logcat).** Раньше во всех клиентах стоял `HttpLoggingInterceptor(BODY)` и в release: в logcat уходили
  `Authorization: Bearer …` (в voice/finance/issues/rests/market логгер стоял после AuthManager.interceptor), SMS-коды
  (форма входа, `validationCode`, выписка), ответ Keycloak с access/refresh токенами, баланс, почта. Теперь
  `data/HttpLog.kt`: `HttpLog.interceptor(context)` — в release пустой перехватчик, в debug — лог с
  `redactHeader(Authorization/Cookie/Set-Cookie)` и маскировкой токенов/кодов в теле. `isDebugBuild(context)` —
  по `ApplicationInfo.FLAG_DEBUGGABLE` (BuildConfig в проекте выключен). Подробный лог Webim (`VERBOSE`, переписка и
  `visitorFields` с hash) — тоже только в debug. Новые клиенты подключать так же: `.addInterceptor(HttpLog.interceptor(appContext))`.
  Оставлены короткие диагностические логи T2Auth / T2Charge / T2Reminder / T2Voice / T2Widget (суммы, коды HTTP, без токенов).
- **Хранение токенов.** `data/TokenStore.kt` + новый `data/TokenCipher.kt`: access/refresh токены шифруются AES-256-GCM
  ключом из Android Keystore (alias `t2lite_tokens_v1`, без новых зависимостей), формат `v1:` + base64(IV + шифртекст),
  AAD = имя записи. Ключи DataStore: `access_token_enc`, `refresh_token_enc`. **Миграция:** старые открытые
  `access_token` / `refresh_token` при первом чтении шифруются и удаляются одной транзакцией — вход не слетает.
  Если Keystore недоступен (encrypt вернул null) — токен пишется по-старому открытым текстом (приложение не ломается),
  перенос повторится при следующем запуске. Если запись не расшифровалась (ключ пропал) — токена «нет», нужен вход по SMS.
  Расшифрованные значения кэшируются в памяти (Keystore медленный, токен читается перед каждым запросом).
  Номер телефона и deviceId по-прежнему открытым текстом (не секрет).
- **Резервные копии.** Манифест: `allowBackup="false"`, `dataExtractionRules="@xml/data_extraction_rules"` (исключено всё
  и для cloud-backup, и для device-transfer), `usesCleartextTraffic="false"` (явно; по умолчанию для targetSdk 35 и так false).
- **Токен только на t2.** `AuthManager.interceptor` добавляет Bearer только для https-хостов `t2.ru`, `*.t2.ru`, `tele2.ru`,
  `*.tele2.ru`. Раньше запись звонка (`audio_message` — произвольный URL от сервера) качалась клиентом с токеном на любой хост.
  Если t2 начнёт отдавать записи с другого домена и они перестанут играть — добавить домен в `isTokenHost`.
- **WebView.** `HelpActivity`: статья открывается внутри приложения, только если это https + сайт t2 (`isT2PageUrl`), иначе
  экран с кнопкой «Открыть в браузере»; http-ссылки на t2 переводятся на https; `allowFileAccess/allowContentAccess=false`,
  `MIXED_CONTENT_NEVER_ALLOW`. `OfficesActivity`: то же про файлы; геолокация выдаётся только origin'ам t2 по https;
  `intent:`-ссылки — без флагов выдачи URI-прав, без `file:`/`content:` внутри, запасная ссылка только http(s).
  Наружу (ACTION_VIEW) не передаются схемы `file`, `content`, `javascript`, `data`, `about`, `blob` (`isUnsafeScheme`);
  ловится любое исключение запуска (раньше `file://` дал бы FileUriExposedException и падение).
  Вложения в чате (`ChatActivity.openUrl`) — только http/https.
  Не трогал: JavaScript включён (сайт без него не работает), сторонние cookie в «Салонах» (нужны проверке «вы не робот»/карте),
  `onReceivedSslError` не переопределён (ошибки TLS отклоняются), `addJavascriptInterface` нет, токены в WebView не передаются.
- **Файлы.** `VoiceRepository.audioFile`: id звонка от сервера чистится до `[A-Za-z0-9_-]` перед использованием в имени файла
  (path traversal). Записи лежат в `cacheDir/voice` (приватно). `T2Repository.logout()` теперь стирает `cacheDir/voice`,
  `files/qa_categories.json` и почту из prefs `t2lite_finance`.
- **Ошибка с падением.** `MemoryCookieJar` (T2Repository) правил общий `MutableList` из разных потоков OkHttp —
  возможен ConcurrentModificationException, а в асинхронных вызовах OkHttp 4 это падение приложения. Теперь списки неизменяемые,
  замена через `ConcurrentHashMap.compute`.
- **Сборка.** `build.ps1`: пароль keystore передаётся keytool через переменную окружения (`-storepass:env`), а не аргументом
  командной строки; на экран пароль не выводился и раньше. `.gitignore`: `*.jks`, `*.keystore`, `*.p12`, `*.pem`, `*.key`,
  `keystore*.properties`, `.env`, `*.apk`, `*.aab`, перехваты трафика (`*.har`, `*.pcap`, `*.flow`, …).
- **Личные данные в исходниках.** В комментариях (`MainTabs.kt`, `VoiceModels.kt`) был настоящий на вид номер — заменён на 79001234567.
  Других секретов в репозитории нет (ни токенов, ни паролей, ни почты).

Что из оригинального приложения и лежит в коде открыто (это нормально): `client_id=android-app`, realm `tele2-b2c`,
хосты `api.t2.ru` / `sso.t2.ru` / `t2.ru`, `Tele2-User-Agent "mytele2-app/6.52.0"`, заголовок `channel: voiceSecretaryApp`,
локация Webim `mobile_android_t2card`, `targetSystem/serviceId = ds_detalization`, `{"sender":"t2.ru"}`.
Аккаунт Webim (domain/accountName) в коде не зашит — приходит с сервера.

Что осталось как есть (осознанно):
- Если нет `keystore.properties`, release подписывается debug-ключом (`app/build.gradle.kts`) — удобно, но такой APK
  потом не обновить «настоящим» ключом. `keystore.properties` хранит пароль открытым текстом — держать вне git, иметь копию.
- `FLAG_SECURE` на экране входа/кода не включён (мешает скриншотам при отладке; при желании — одна строка в MainActivity).
- `WidgetSettingsActivity` и `T2WidgetReceiver` экспортированы — этого требует механизм виджетов; наружу данные не отдают.
- Кэш виджета (`t2lite_widget_data`: баланс, остатки) и состояние напоминаний — открытым текстом в приватных prefs.
- proguard: `-keep class ru.myt2lite.** { *; }` — широко, но это свой код; на безопасность не влияет.
- Выход не отзывает refresh_token на сервере (Keycloak logout) — в оригинале запрос не разбирали; токены стираются локально.
- Certificate pinning не добавлялся (оригинал не пинит; риск «окирпичить» приложение при смене сертификата t2).

## Публикация на GitHub (2026-11-25)
Репозиторий: https://github.com/EugeneRymarev/t2lite
- `README.md` — описание для GitHub (что умеет, установка, сборка, выпуск версий, дисклеймер).
- `.github/workflows/build.yml` — проверочная сборка debug APK на каждый push/PR (артефакт на 14 дней).
- `.github/workflows/release.yml` — по тегу `v<VERSION_NAME>`: сверяет тег с version.properties, берёт раздел `## <версия> ·` из changelog.md (если нет — «Готовится»), собирает `assembleRelease`, подписывает ключом из секретов, проверяет что подпись не debug, публикует релиз `t2lite-<версия>-build<код>.apk` + SHA-256.
- Секреты репозитория: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Добавляет `github-secrets.bat`/`.ps1` из keystore.properties (через `gh secret set` или вручную через буфер обмена).
- `.gitattributes`: gradlew — LF, *.bat/*.ps1 — CRLF.
