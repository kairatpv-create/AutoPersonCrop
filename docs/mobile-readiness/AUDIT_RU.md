> Обновление 22.10.2026: последующие исправления синхронизации и подготовка публикации описаны в [SYNC_SAFETY_RU.md](SYNC_SAFETY_RU.md), [ANDROID_SAFETY_TESTS_RU.md](ANDROID_SAFETY_TESTS_RU.md) и [PUBLICATION_PREPARATION_RU.md](PUBLICATION_PREPARATION_RU.md). Ниже сохранён первоначальный аудит; его таблица дефектов не является текущим списком закрытых задач.

# Organizer Pro — аудит и подготовка мобильного выпуска

Дата: **21 октября 2026**, время пользователя Asia/Qyzylorda. Проверены исходники и сохранённый APK; работа с пользовательскими аккаунтами и базами не проводилась.

**Вывод:** существующее приложение восстановлено и пригодно для дальнейшей разработки. До публичного выпуска необходимо устранить риски потери данных в синхронизации и восстановлении, добавить удаление аккаунта и подготовить полноценный iOS-клиент. Готовность к TestFlight или магазинам сейчас не подтверждена.

## 1. Фактическое состояние версий

| Объект | Проверенный результат |
|---|---|
| Репозиторий | `kairatpv-create/AutoPersonCrop`, публичный; default branch `main` относится к другому проекту и не менялась |
| Android | `personal-organizer-v1`, HEAD `69f6301d22472417da803e3f934769732b0cfdd2`, 2 октября 2026 |
| Web / API | `personal-organizer-web-v1`, HEAD `3a28807ee98aa657b90bba5a35c776ead8c2145f`, 2 октября 2026 |
| Последняя сборка Android в GitHub | Run `36988229529`, success, 2 октября; `assembleDebug`; артефакт `Organizer-Pro-v0.9.38-unsigned-stage` хранился 7 дней и теперь отсутствует |
| Сохранённый APK | `Organizer-Pro-v0.9.38-original-key.apk`, 17 423 224 байта; найден и прочитан отдельно |
| Версия внутри APK | `versionName=0.9.38`, `versionCode=71`, package `kz.kairat.organizer`; minSdk 26, targetSdk 35 |
| Подпись APK | Проверена Android apksig 8.10.1: verified=true, v2=true, v3=true |
| Сертификат SHA-256 | `a0564fdcf4e365a0077cbb0f56cf4ddce9538a45ba772b1793ab3f549b8f90b2` |
| APK SHA-256 | `ae45e46bbc45a35c4353c3145f14f1a1703994dde8c6e3ae80d7e13811160019` |
| Тип APK | `android:debuggable=true`; включает Compose PreviewActivity. Это действующая тестовая Android-сборка, не магазинный release |
| GitHub Releases | На момент проверки отсутствуют |
| iOS в проверенных исходниках | Xcode-проекта, iOS target, Swift-клиента и KMP-модуля нет |

Версия **0.9.38 (71) действительно представлена в GitHub**, но через воспроизводимую цепочку архивов/исправлений, а не готовое дерево Android Studio. Номер и пакет сохранённого APK совпадают с восстановленными исходниками; побайтовое соответствие DEX конкретному запуску GitHub не установлено. Сам телефон в этой среде не подключён, поэтому его установленный APK отдельно не считывался.

Ветки `personal-organizer-v1-final-temp`, `personal-organizer-v1-source-temp` и `personal-organizer-v1-source-temp-2` относятся к более ранним работам 1 октября и не приняты за последнюю версию. Web-ветки `*-sync-hardening` / `*-final-smoke` находятся на `77422642…`; основная web-ветка содержит последующие исправления 0.5.3. Последний успешный workflow web 0.5.3 проверял наличие файлов и синтаксис JS, а не полноценное поведение приложения.

Локальные рабочие копии созданы заново и первоначально были чистыми. Изменения, появившиеся при запуске исторического реконструктора, учтены; его правка старого `v0115/apply.py` отменена. **Наличие незакоммиченных файлов на Windows-компьютере пользователя остаётся неизвестным:** эта среда не имеет доступа к его диску. Утверждать, что таких файлов нет, нельзя.

## 2. Сохранение исходного состояния и структура

До изменений создан Git bundle всех доступных refs; оригинальные ветки и исторические входные файлы сохранены. Точное восстановленное дерево 0.9.38 зафиксировано отдельным коммитом `bd2d800239645a2930a89a45bcc8a71efc76001e`. Для всех **42 файлов** сохранены SHA-256 в `mobile/BASELINE_PROVENANCE.json`.

| Путь | Назначение |
|---|---|
| `organizer/v090tar/part00…03` | Base64-части исходного tar.xz 0.9.0 |
| `organizer/v091…v0128/` | Исторические исправления, часть в сжатых Base64-пакетах |
| `organizer/v0106/rebuild.py` | Восстановление до 0.9.16 |
| `organizer/build_v0930.sh` и последующие `build_v093*_ci.sh` | Цепочка до версии 0.9.38 |
| `organizer/v0128/apply.py` | Финальная правка 0.9.38, карточка веб-адреса и versionCode 71 |
| `.github/workflows/organizer-build-v0938.yml` | Историческая сборка Android 0.9.38 |
| `mobile/android/` | Теперь читаемые исходники того же существующего приложения, без повторного проигрывания цепочки |
| `organizer-sync-server/` **в web-ветке** | Node/Express API и PostgreSQL |
| `organizer-web/static/` **в web-ветке** | Фактически отдаваемый сервером веб-интерфейс |
| `organizer-web/src/` **в web-ветке** | Отдельная React/TypeScript/Firebase-реализация; не код текущего native Android и не основной static UI Render |
| `render.yaml` **в web-ветке** | Декларативная конфигурация сервиса и базы; не подтверждение их текущего тарифа |
| `tools/audit/` | Изолированные проверки Kotlin/SQLite и Express API |
| `mobile/ios/` | Зафиксированные параметры приложения и план сборки; полноценного iOS-клиента пока нет |

Архитектура Android — **Kotlin + Jetpack Compose + Material 3**, одно приложение/Activity, прямые обращения UI к SQLite и файловому хранилищу. Room, MVVM-слоя, DI и Kotlin Multiplatform в существующей версии нет. В baseline 16 Kotlin-файлов, 3 256 строк, из них 1 406 — `OrganizerUi.kt`.

Основные компоненты: `NoteDatabase.kt` — SQL/CRUD; `Models.kt` — модели; `OrganizerUi.kt` — экраны; `Theme.kt` — палитры/шрифты; `AppStrings.kt` — локализация; `SettingsStore.kt` — SharedPreferences и PIN; `BackupManager.kt`/`AttachmentStore.kt` — копии и файлы; `CloudSync.kt` — REST и автообновление; `ReminderSystem.kt`/`CalendarSystem.kt` — уведомления/календарь; `VoiceRecorder.kt` — AAC/M4A.

Обнаружены дополнительные функции, которые важно учесть при переносе: дела, вложения и фотографии, запись/воспроизведение аудио, распознавание речи, этапы/журнал/связанные заметки проектов, напоминания, календарь и дни рождения. Текущий интерфейс предлагает **8 языков**: kk, ru, en, zh, de, es, fr, tr. Они сохранены на Android; обязательный минимум iOS — ru/en.

## 3. Библиотеки и сборка

| Компонент | Фактическая версия Android |
|---|---|
| Gradle / Java | 8.11.1 / 17 |
| Android Gradle Plugin | 8.10.1 |
| Kotlin / Compose compiler plugin | 2.1.21 |
| Compile / target / min SDK | 35 / 35 / 26 |
| core-ktx | 1.16.0 |
| lifecycle-runtime-ktx | 2.9.1 |
| activity-compose | 1.10.1 |
| Compose UI / foundation / tooling | 1.8.2 |
| Material 3 | 1.3.2 |
| material-icons-extended | 1.7.8 |
| coroutines-android | 1.7.3 |

SQLite, SharedPreferences, `HttpURLConnection`, `org.json`, Android CalendarContract, MediaRecorder и RecognizerIntent используются напрямую. Текущий Android CloudSync не использует Firebase, хотя исторические настройки Firebase и старый web-код ещё присутствуют.

Сервер: Node >=20; `express ^4.21.2`, `pg ^8.13.3`, `bcryptjs ^2.4.3`, `jsonwebtoken ^9.0.2`, `cors ^2.8.5`, `express-rate-limit ^7.5.0`, `helmet ^8.1.0`. В исходной серверной ветке нет package-lock; точные установленные на Render версии зависимостей не установлены. Нужна фиксация dependency graph до релиза.

В архиве Android есть `gradle-wrapper.properties`, но нет полного wrapper с исполняемыми скриптами/JAR. Исторические workflow устанавливают Gradle отдельно. В старых скриптах также встречается открытый пароль тестового signing store; сам приватный ключ в проверяемом дереве не найден. Значение пароля в отчёт не копируется. Ключ нельзя менять без проверки совместимости обновления.

## 4. Локальные данные, PIN и резервные копии

База `organizer.db`, schema **9**, Android package остаётся `kz.kairat.organizer`. Таблицы: `notes`, `tasks`, `projects`, `money_entries`, `reminders`, `hidden_suggestions`, `project_stages`, `project_journal`, `project_note_links`. ID — локальные INTEGER AUTOINCREMENT; деньги/бюджеты — REAL/Double. SQL-операции параметризованы. `onUpgrade` добавляет столбцы/таблицы; DROP TABLE при обычном обновлении нет. `onOpen` повторяет DDL и ловит все исключения ALTER, из-за чего ошибки миграции могут остаться незаметными.

Настройки — `organizer_settings` SharedPreferences. PIN: случайная соль 16 байт + SHA-256, сравнение постоянного времени; проверка на запуске/ручная блокировка. База и файлы самим PIN не шифруются. Попытки ввода не ограничены; автоматическая повторная блокировка при уходе в фон не реализована в проверенном коде. Сессия хранится в обычных prefs. Системные backup rules включают все prefs, базу и файлы, в том числе session/PIN hash; отдельный encrypted credential store отсутствует.

Вложения — `files/attachments/index.json` и `files/attachments/data/`, лимит добавляемого файла 25 MiB. Локальная копия — `format=my-organizer-backup`, version **6**, JSON с данными восьми разделов, настройками и Base64-вложениями; ZIP содержит тот же JSON. Это сжатие без шифрования. В копию не входят токен, пароль аккаунта и PIN, а также скрытые подсказки из `hidden_suggestions`. Передавать такую копию следует как файл с личными данными.

Восстановление сначала разбирает JSON, затем заменяет восемь таблиц через `replaceAll`, после чего отдельно восстанавливает вложения. Табличная замена обёрнута транзакцией, но обычный `insert()` возвращает -1 при ошибке и результат не проверяется. Файлы не участвуют в транзакции SQLite. Предпросмотра, проверки всего формата/ссылок/дубликатов, безопасного промежуточного каталога и автоматической страховочной копии нет. Экспорт/импорт читает файлы целиком в память; ручной backup запускается синхронно из UI.

## 5. Авторизация и синхронизация

Связь: Android → HTTPS `organizer-pro.onrender.com` → Node/Express → PostgreSQL. Веб-страница и API обслуживаются одним сервером, но native-клиент использует только REST. Удаление карточки веб-адреса не меняет API или базу.

| Метод / путь | Текущее поведение |
|---|---|
| `POST /api/auth/register` | Email/пароль, bcrypt cost 12, JWT; пароль минимум 6 символов |
| `POST /api/auth/login` | Проверка хеша; существующие аккаунты совместимы с будущим iOS REST-клиентом |
| `GET /api/account` | Email из проверенного JWT |
| `GET /api/sync` | `exists`, `rev`, `updatedAt`, `payload` — строка JSON-снимка |
| `PUT /api/sync` | `payload` и `baseRev`; при несовпадении revision возвращает 409 с текущим снимком |
| `GET /health` | `SELECT 1` к базе |
| `GET /api/version` | Захардкоженные метаданные API; не доказательство фактической версии исходников сервера |

JWT подписывается серверным секретом, issuer/audience проверяются, срок по умолчанию 30 дней. Refresh, password reset, email verification, отзыв отдельной сессии и удаление аккаунта в проверенном сервере отсутствуют. Android ставит локальный expiry в Long.MAX_VALUE; реальное истечение обнаруживает по HTTP 401. Logout удаляет локальную сессию, а не отзывает JWT на сервере.

PostgreSQL: `organizer_users(id,email,password_hash,created_at)` и `organizer_sync(user_id,payload,rev,updated_at)`. Хранится один текущий снимок пользователя без серверной истории версий. Payload проверяется по размеру, но не разбирается/валидируется как backup. Лимит тела Express — 1 MiB; снимка — 850 000 байт; JSON-экранирование увеличивает общий запрос. Для существующей строки применяется `SELECT ... FOR UPDATE`; гонка первого создания строки двумя устройствами отдельно требует реального PostgreSQL-теста. TLS к PostgreSQL в production задаётся с `rejectUnauthorized:false`; это нужно исправить с проверенной CA-конфигурацией, без изменения данных.

Android экспортирует локальный backup, затем убирает `attachments` и `settings`, ставит version 7 и `cloudSchema=organizer-sync-v3`. **Вложения между устройствами сейчас не синхронизируются.** База и файлы продолжают работать офлайн, но безопасное объединение последующих изменений не реализовано.

В foreground Compose-корутина проверяет сервер каждые 30 секунд. Период не гарантируется в фоне. При первом входе и одновременных изменениях выбирается целый снимок по максимальному времени; в сравнение ошибочно входят будущие `dueAt`/`triggerAt`. При HTTP 409 Android без согласования применяет серверный снимок через восстановление базы. Нет UUID, tombstones, журнала операций и полноценного merge. При смене аккаунта используется та же локальная база, что создаёт риск загрузки записей предыдущего владельца в следующий аккаунт.

В static web 0.5.3 уже есть частичное объединение по ID и страховочные снимки localStorage, но это не полноценное решение коллизий/удалений. **`location.reload()` в текущем `static/sync.js` не найден:** прежнее описание этой проблемы не принято за актуальный факт. CORS разрешает native-запросы без Origin; ограничения браузера сами по себе не требуют отключать API.

## 6. Подтверждённые дефекты и приоритеты

Обозначения: **H** — воспроизведено из исходного Kotlin-кода в host-среде; **A** — воспроизведено HTTP-тестом исходного Express с test double базы; **S** — установлено чтением исходников, реальное устройство/production для этого сценария не тестировались.

| ID | Приоритет | Дефект и следствие | Основание |
|---|---|---|---|
| D01 | P0 | Конфликт синхронизации заменяет весь снимок: теряются уникальные записи другого устройства | H: обе dirty-ветки и HTTP 409; CloudSync.kt |
| D02 | P0 | Будущий срок проекта перевешивает свежую заметку при выборе снимка | H: первая синхронизация с будущим dueAt |
| D03 | P0 | Смена аккаунта может загрузить локальные записи аккаунта A в аккаунт B | H: SettingsStore без account-scoped базы + первый sync |
| D04 | P0 | `{"notes":[]}` принимается как backup и очищает другие таблицы, включая финансы | H: BackupManager.restore |
| D05 | P0 | Дубликаты ID при restore приводят к успешному частичному восстановлению | H: SQLite insert -1 не проверен |
| D06 | P0 | Неверное Base64 вложения стирает старые файлы, ошибка скрывается; storedName допускает выход из data-каталога | H: два сценария AttachmentStore.restoreBackup |
| D07 | P1 / блокер магазина | Нет удаления аккаунта/связанных облачных данных | A: API 404; S: нет UI/обработчика |
| D08 | P1 | Сервер сохраняет заведомо некорректный JSON-снимок | A: malformed payload принят PUT и возвращён GET |
| D09 | P1 | targetSdk 35 и debug-сборка не подготовлены к новой публикации | APK и Gradle; актуальное требование Play — API 36 |
| D10 | P1 | Приватность: bearer в обычных prefs, все prefs включены в системную копию; PIN не шифрует базу; нет задержки попыток | S: SettingsStore / backup rules |
| D11 | P1 | На заблокированном экране показывается содержание напоминания; ReminderAlertActivity не проверяет PIN | S: VISIBILITY_PUBLIC, showWhenLocked; устройство не тестировалось |
| D12 | P1 | Серверный тариф, срок жизни PostgreSQL и наличие пригодного backup не подтверждены | render.yaml содержит free; текущая конфигурация Render не прочитана |
| D13 | P1 | При каждом cloud export сначала читаются и Base64-кодируются все вложения, потом поле удаляется | S: canonicalBackup → createBackup → exportBackup; лишние память/IO каждые 30 с |
| D14 | P1 | SQL и файловое восстановление не атомарны вместе; unbounded JSON/ZIP import; нет страховочной копии | S + H повреждённого вложения |
| D15 | P1 | Восстановление remote snapshot может нарушить соответствие оставшихся локальных вложений к числовым ID | S: attachments исключены из sync, локальный индекс сохраняется |
| D16 | P1 | Проект редактирования бюджета преобразует исходное Double в Long; UI сумм округляет до целых | S: ProjectEditDialog / `%,.0f`; дробный бюджет может измениться даже при сохранении другого поля |
| D17 | P1 | Недостаточна проверка финансовых значений и связей импортируемых записей | S: optDouble/defaults и отсутствующие foreign-key constraints |
| D18 | P2 | Малые фиксированные высоты и масштабированные Switch/Slider; жёсткие maxLines, фиксированная высота поиска | S: нужно UI-тестирование на разных экранах и шрифтах |
| D19 | P2 | `Archive` находится в узкой третьей колонке рядом с checkbox | S: исправлено в подготовительной ветке; визуальная проверка на телефоне ещё нужна |
| D20 | P2 | Системный fontScale принудительно заменяется на 1; у множества иконок нет доступного описания | S: Theme.kt, Icon(...,null); влияет на accessibility |
| D21 | P2 | Ошибки и сообщения синхронизации частично остаются русскими при EN | S: request/syncOnce/SyncOutcome |
| D22 | P2 | Android 26+ использует общий звуковой канал, индивидуальные sound/vibrate вне старой ветки не применяются | S: ReminderNotifications |
| D23 | P2 | Жёсткая высота reminder alert в 30% экрана может обрезать содержимое; нет прокрутки | S: ReminderAlertActivity; UI-тест не выполнен |
| D24 | P2 | Удаление заметки не удаляет/не отменяет её напоминания | S: NoteReader / NoteDatabase.delete; проверить пользовательский сценарий |
| D25 | P2 | Нет полного wrapper, серверного lockfile, release signing workflow и свежего полного UI-регресса | Исходники / CI |

`Buttone text size` **не найдено ни в исходнике, ни в DEX сохранённого APK**: там `Button text size`. Это не объявлено исправленной нами ошибкой. Конкретный экран телефона ещё нужно проверить.

## 7. Что проверено автоматически

**27 изолированных сценариев: 17 обычных проверок прошли, 10 сценариев воспроизвели риски.** Это не 27 успешных проверок безопасности.

- Kotlin/SQLite: 12 сценариев, 4 PASS и 8 RISK_REPRODUCED. Компилировались реальные Models, AppStrings, NoteDatabase, SettingsStore, BackupManager, AttachmentStore и non-UI часть CloudSync. SQLite JDBC исполнял реальные SQL. Context/prefs/URI и системные планировщики заменены маленькими host-адаптерами, сеть перенаправлена только на loopback. Подтверждены CRUD, правильный JSON/ZIP roundtrip с файлами/проектными связями/предпочтениями, PIN, RU/EN и rollback явной SQL-транзакции. Описанные выше опасные сценарии также воспроизведены.
- Express: 15 сценариев, 13 PASS и 2 RISK_REPRODUCED. Использовался исходный server.js, реальные Express/bcrypt/JWT и in-memory test double `pg`. Проверены register/login, неверные credentials, повторный email, JWT 30 дней, защита sync, PUT/GET/revision 409, Origin и API 404. Это не тест настоящей PostgreSQL-конкурентности.
- Сохранённый APK: manifest/version/package/debuggable, SHA-256, криптографическая проверка v2/v3 подписи. ELF LOAD alignment имеющейся `libandroidx.graphics.path.so` — 16 384 байт для всех четырёх ABI; это положительный статический результат, но не проверка AAB/zipalign и запуска Android в режиме 16 KiB.
- Публичный сервис: только read-only GET. `/health` → 200 и `ok:true`; `/api/version` → 200, API 2 / web 0.5.1 / android 0.9.30; `/api/account` и `/api/sync` без токена → 401. Один ранний health-запрос завершился таймаутом, следующий успешен. Измеренные через эту среду 8–13 с не являются достоверными метриками задержки сервера или SLA.
- Сборка подготовительной ветки `39643357026` успешно завершилась: реальная `assembleDebug` с компиляцией Kotlin и APK из читаемого дерева. Это проверка компиляции; UI/device-тесты не выполнены. Подробности в `VALIDATION.md`.

Не выполнены: Android instrumentation/эмулятор, UI-снимки реального телефона, iPhone Simulator/устройство, реальный обмен между аккаунтами, offline-reconnect на двух устройствах, восстановление production PostgreSQL, проверка сертификата установленного на телефоне APK. Никакие production POST/PUT/DELETE для тестов не выполнялись.

## 8. Render и веб-версия

В blueprint указаны `plan: free` у web-сервиса и PostgreSQL, branch `personal-organizer-web-v1`, `autoDeploy:true`, `healthCheckPath:/health`. Это описание желаемой конфигурации, а не подтверждённый текущий счёт/тариф. Доступный workspace — `My Workspace`, но он не был выбран для resource-запросов; тарифы, expiration и backup inventory не запрашивались.

Текущая документация Render подтверждает: бесплатный web-service засыпает после 15 минут простоя; free PostgreSQL истекает через 30 дней, а управляемые backups для free не предоставляются. **Нельзя заключать, что действующая база точно бесплатная или когда она истечёт, по одному YAML.** Проверка её текущего плана и проверяемой резервной копии — ближайшая инфраструктурная задача. Перевод на платный тариф и любое перемещение данных требуют отдельного решения пользователя.

План проверки без изменения данных: read-only service/DB metadata и expiration; последний deploy/commit; error metrics; доступные backup/PITR/exports; описание срока хранения/RPO/RTO. Фактический restore drill выполняется только на отдельной тестовой базе, с предварительной защитой дампа. Production базу не восстанавливать и не копировать в публичные артефакты.

Веб-интерфейс заморожен. В подготовительной Android-ветке удалена только карточка «Веб-страница», `CloudSyncSettingsCard`, endpoint, API и серверная база не изменены. Сам сервер нельзя отключать вместе с веб-страницей: он нужен обоим мобильным клиентам.

## 9. iOS: повторное использование и новая реализация

Рекомендация: **постепенное добавление Kotlin Multiplatform для моделей и проверенной логики + нативный SwiftUI интерфейс iPhone**. Существующий Android Compose/SQLite остаётся работающим; перенос не должен начинаться с переписывания базы. Полный переход всего UI на Compose Multiplatform возможен позже, но потребует значительного выделения Android API из монолитного UI-файла. Само наличие Jetpack Compose не делает проект iOS-совместимым.

| Компонент | Что сохраняется | Что потребуется |
|---|---|---|
| Сервер и аккаунты | Email/password, существующие хеши, URL, JWT, текущие REST-пути | Сначала исправить контракты и безопасный conflict flow; не менять хеши/аккаунты ради iOS |
| `Models.kt` | Чистые Kotlin data classes без Android-imports можно перенести в commonMain | Отдельный совместимый wire DTO; финансовый Decimal/minor units проектировать без изменения старых записей |
| Формат данных | JSON/ZIP и значения старых полей/ID/миллисекунд | Общие fixtures, строгая валидация и контролируемая конвертация; version 6/7 ≠ SQLite schema 9 |
| Дизайн/тексты | Палитры, цвета заметок, смысл экранов, строки RU/EN и логотип | Resource-экспорт, SwiftUI-компоновка с safe area, Dynamic Type, VoiceOver |
| Локальная база | Смысл таблиц, отношения, данные | iOS SQLite adapter и repositories. Безопасное извлечение общего кода; не использовать Android SQLiteOpenHelper на iOS |
| PIN и настройки | Поведение защиты/предпочтения | Keychain и локальный storage, foreground relock, защищённые файлы, новый безопасный KDF без сброса Android PIN |
| Сеть | HTTPS, совместимые endpoints | Shared networking adapter или native URLSession; сериализация, отмена, backoff, видимый статус и сохранение pending changes |
| Backup / файлы | Совместимый экспорт/импорт | Files/document picker, share sheet, staged validation и rollback |
| Медиа/камера/речь | Пользовательские сценарии и AAC/M4A | AVFoundation, PhotosUI/камера, Speech с contextual permissions; распознавание не объявлять полностью офлайн |
| Напоминания | Расписания/связи | UserNotifications и корректная очередь повторов. Android full-screen alarm нельзя обещать как обычное iOS-уведомление |
| Календарь | Логика дат/проектных сроков | EventKit; запрашивать доступ только при включении функции |
| Фоновая синхронизация | Offline-first и сохранение данных | iOS не гарантирует цикл 30 с в фоне; sync при foreground/reconnect и разрешённых background tasks |

Полный план реализации, критерии и macOS-сборка описаны в `mobile/ios/BUILD_AND_TEST_RU.md`. Проценты повторного использования не выдумываются: сейчас без изменения исходного кода переносим прежде всего модели и контракты; остальное требует выделения интерфейсов.

## 10. Требования магазинов и следующие шаги

Apple: существующие параметры сохранены в `mobile/ios/APP_IDENTITY.json`: Bundle ID `com.organizerpro.mobile`, App ID `6825404169`, SKU `ORGANIZER-PRO-IOS-001`, RU «Органайзер Про», EN «Organizer Pro: Smart Life», категории Productivity/Finance. Это сведения пользователя, а не новая проверка его App Store Connect. Новая карточка и Bundle ID не создавались. Нужны удаление аккаунта в приложении, privacy policy в интерфейсе и метаданных, фактические App Privacy disclosures, purpose strings/PrivacyInfo.xcprivacy, reviewer credentials и корректный release archive. Для собственной email/password системы обязательное добавление social login не следует из guideline 4.8. Политика Apple требует Xcode 26+ и iOS 26 SDK+ для загрузки; deployment target — отдельное решение, в плане предварительно iOS 17.

Google Play: требуется release AAB с исходной идентичностью/ключом и target API 36 для новой подачи на дату аудита; Data safety/удаление аккаунта внутри приложения и внешняя страница запроса удаления; проверка permissions full-screen/exact alarms, accessibility и 16 KiB на устройстве/AAB. Регистрация Play пользователя ещё не завершена. Платежи и подписки не добавлялись: первый выпуск планируется бесплатным.

Очередность следующей разработки:

1. Зафиксировать подходящую копию данных/вложений и реальный план/backup PostgreSQL; подтвердить исходный ключ для последующих Android-обновлений. Не выполнять reset/reinstall.
2. Закрыть D04–D06: preflight backup, preview и явное восстановление, проверка ID/ссылок/размеров, staged attachments, insertOrThrow/rollback, страховочная копия и тест сбоя на каждом шаге. Старые форматы принимать через проверенные adapters, а не молчаливые defaults.
3. Закрыть D01–D03: account-scoped state, один координатор sync, сохранение обеих конфликтующих версий, исключение dueAt из свежести, устойчивые идентификаторы/удаления. Совместимый API развертывать отдельно после проверки; старые клиенты не должны перезаписать новый формат. Любая миграция только на копии с rollback-планом.
4. Добавить подтверждённое удаление аккаунта с reauthentication и защитой локальных данных, privacy/purpose disclosures; это функция для пользователя, не команда удалять чьи-либо текущие данные в ходе разработки.
5. Подготовить shared domain/wire fixtures и нативные iOS repositories; затем экраны Notes → Finance → Projects → Settings и дополнительные существующие функции.
6. Выполнить simulator/device matrix и Android upgrade tests; собрать unsigned simulator build, затем подписанный archive и только после проверки — загрузка в TestFlight по отдельному подтверждению. Публикацию согласовать отдельно.

Этапы 1–4 аудита выполнены в доступной среде с перечисленными ограничениями. Этап 5 начат ограниченной UI-правкой и восстановлением нормального дерева исходников. Этапы 6–7 не объявлены завершёнными: готового iOS-приложения/IPA и готовности к TestFlight пока нет.

## Проверенные первичные источники

- [Исходный Android commit](https://github.com/kairatpv-create/AutoPersonCrop/commit/69f6301d22472417da803e3f934769732b0cfdd2), [успешная историческая сборка](https://github.com/kairatpv-create/AutoPersonCrop/actions/runs/36988229529).
- [Исходный web/server commit](https://github.com/kairatpv-create/AutoPersonCrop/commit/3a28807ee98aa657b90bba5a35c776ead8c2145f), [подготовительная ветка](https://github.com/kairatpv-create/AutoPersonCrop/tree/organizer-mobile-readiness-20261021).
- [Apple: актуальный минимальный SDK](https://developer.apple.com/news/?id=ueeok6yw), [App Review Guidelines, 4.8/5.1](https://developer.apple.com/app-store/review/guidelines/), [удаление аккаунта](https://developer.apple.com/help/app-review/guideline-reference/5-1-1-account-deletion/).
- [Google Play: target API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-GB), [удаление аккаунта](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en), [16 KiB](https://developer.android.com/guide/practices/page-sizes).
- [Render Free — прочитан текущий Markdown](https://render.com/docs/free.md), [Postgres backups — прочитан текущий Markdown](https://render.com/docs/postgresql-backups.md).
- [JetBrains: добавление iOS к существующему Android](https://kotlinlang.org/docs/multiplatform/multiplatform-integrate-in-existing-app.html), [GitHub: macOS 26 runner image](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md).

Административное чтение Render не выполнено: [render-monitor SKILL.md](skill://plugin_asdk_app_6a624c56bfe081918f7544f7d58f6faf/render-monitor/SKILL.md) требует «establish access and verify the intended workspace»; связанная [render-access.md](skill://plugin_asdk_app_6a624c56bfe081918f7544f7d58f6faf/render-monitor/references/render-access.md) требует выбора пространства перед resource-запросами. Это ограничение проверки, а не заключение о состоянии базы.
