# Organizer Pro — технический план iOS и TestFlight

## Состояние и границы

Это подготовка iOS-цели **существующего** Organizer Pro в том же репозитории.
Android `kz.kairat.organizer`, его данные и исходная подпись сохраняются.
Готового Xcode-проекта, общего KMP framework, iOS UI или IPA пока нет. Эти
документы и preflight не являются iOS-приложением.

Использовать только существующие Bundle ID `com.organizerpro.mobile`, App ID
`6825404169`, SKU `ORGANIZER-PRO-IOS-001`. `APP_IDENTITY.json` фиксирует сведения
пользователя. Apple Team ID, signing assets и фактические capabilities ещё не
проверены; их нельзя выводить из имени разработчика. Повторно создавать карточку
и идентификатор не нужно. Первый выпуск бесплатный; StoreKit/подписки не входят
в текущий объём.

## Архитектурное решение

Рекомендуется Kotlin Multiplatform для общего domain/wire-кода и SwiftUI для
iPhone. Это позволяет переносить модели и исправленную логику постепенно, не
меняя одновременно работающий Android UI и его SQLite-адаптер. Существующий
`Models.kt` не импортирует Android и подходит для commonMain; `CloudSync.kt`,
`BackupManager.kt`, SettingsStore и SQL helper целиком туда не переносятся.

Целевая структура после безопасного выделения:

- `mobile/android/` — существующее приложение;
- `mobile/shared/` — модели, DTO, проверки, conflict policy, repository/transport
  interfaces и fixtures; iOS framework из этой цели;
- `mobile/ios/OrganizerPro.xcodeproj` — SwiftUI app с Keychain, SQLite, Files,
  UserNotifications, EventKit, AVFoundation/Speech adapters;
- исходный сервер остаётся в проверенной web-ветке до отдельной согласованной
  поставки совместимых исправлений.

Нельзя заменять Android SQLite на новую библиотеку одновременно с запуском iOS.
Сначала repository interface поверх текущего `organizer.db` schema 9; отдельная
iOS-база и общие wire fixtures. Для первого iOS deployment target предварительно
выбран iOS 17; окончательно подтвердить на macOS и нужных iPhone. Сборочный SDK
должен соответствовать действующим требованиям Apple независимо от deployment
target.

Не обещать процент общего кода заранее. Если впоследствии потребуется Compose
Multiplatform UI, отдельно выделить platform services из монолитных Composable,
проверить accessibility/safe area на iPhone и только после этого решать вопрос
его применения. Переписывание через веб-обёртку не является выбранным путём.

## Сохранность данных до подключения второго мобильного клиента

1. Строгий preflight: формат/версия, все поля ожидаемого типа, уникальность ID,
   денежные числа и ссылки. Старые форматы — через явно описанные adapters.
   Показать пользователю состав восстанавливаемой копии до замены данных.
2. Автоматическая страховочная локальная копия перед replace; проверка её
   чтения; staged attachment directory; запрет выхода storedName за каталог;
   проверка Base64/размеров до начала удаления. Исключение на любой ошибке SQL,
   rollback базы и файлов, recovery journal на случай завершения процесса.
3. Один sync coordinator, блокировка параллельных login/manual/background
   операций. Снимок данных и sync metadata согласуются транзакционно. Локальные
   новые изменения, появившиеся во время запроса, остаются pending.
4. На конфликте сохранить обе версии, не выбирать всю базу по времени одного
   проекта. Убрать dueAt/triggerAt из определения свежести. Для legacy v3 —
   безопасный конфликтный сценарий с копиями и объяснимым выбором/merge.
5. Проектировать глобальные ID, tombstones и per-record version отдельно.
   Добавление UUID нельзя провести принудительной заменой числовых ID: сначала
   совместимый mapping и проверка всех проектных/файловых связей на копии.
6. Зафиксировать owner аккаунта для local state. При смене email не загружать
   записи прошлого аккаунта автоматически. Гостевые записи привязывать к
   аккаунту только через понятный пользователю выбор с резервной копией.
7. Перед новым wire schema обеспечить безопасное сосуществование старого
   Android 0.9.38, исправленного Android, web и iOS. Старый PUT не должен стереть
   поля нового клиента. Новые API/миграции — staging и проверка rollback прежде
   production. Отключение синхронизации не заменяет её исправление.

## Совместимость аккаунта и API

Сохранить текущие email/password аккаунты, bcrypt-хеши, HTTPS host и REST paths.
Текущий контракт: register/login → `{token,email}`, GET sync →
`{exists,rev,updatedAt,payload}`, PUT sync → `{payload,baseRev}` и 409 с
серверной версией. Никакой регистрации аккаунтов заново из-за iPhone.

У iOS transport должны быть отмена, разумный timeout/backoff, обработка 401,
явный статус сохранения/синхронизации и постоянное хранилище pending state.
После истечения JWT офлайн-заметки и финансы остаются доступны. Не хранить токен
в UserDefaults/backup; использовать Keychain. Logout и смена аккаунта требуют
изоляции локального набора данных; серверный отзыв сессий и удаление аккаунта
проектируются совместимо с Android.

Собственная email/password авторизация не требует автоматически добавлять
Sign in with Apple согласно исключению guideline 4.8. Если появится сторонний
social login, требования пересматриваются. Удаление аккаунта должно быть
доступно внутри приложения, с reauthentication и ясным выбором судьбы локальных
копий; облачные данные удаляются только по явному действию владельца.

## Сценарии и platform adapters

| Функция | iPhone-реализация и проверка |
|---|---|
| Заметки/дела | Локальное создание, редактирование, поиск, Unicode RU/EN, цвета; draft recovery при фоне/закрытии |
| Финансы | Все записи/категории/проектные связи; Decimal или minor units в новом domain-слое, без округляющей перезаписи legacy Double; точные totals и отображение дробей |
| Проекты | Статусы, этапы, сроки/бюджет, клиент/адрес/контакт, журнал, привязанные заметки, вложения; отмена редактирования ничего не меняет |
| Settings | Светлая/тёмная тема, независимые размеры UI/содержимого, bold, RU/EN; настройки сохраняются после перезапуска |
| PIN | Keychain/защищённые файлы, relock при возвращении, задержка попыток; PIN не объявляется шифрованием, пока шифрование не реализовано |
| Backup | Files picker, JSON/ZIP старого формата, staged restore, interrupted restore recovery; share sheet только по действию пользователя |
| Вложения | Локальные защищённые файлы, MIME/размеры, безопасное открытие; облачная передача не обещается до отдельной реализации |
| Фото | System picker; камера по действию пользователя с NSCameraUsageDescription |
| Голос | AVAudioRecorder/Player AAC/M4A, NSMicrophoneUsageDescription, корректный stop/cancel/background/interruption |
| В текст | Speech с NSSpeechRecognitionUsageDescription; объяснить, когда требуется сеть; отказ в доступе не блокирует обычную заметку |
| Напоминания | UserNotifications, повторения/перенос, отказ в разрешении, time zone/DST; без обещания Android full-screen на iOS |
| Календарь | EventKit, NSCalendarsFullAccessUsageDescription при чтении событий на iOS 17+, без записи если она не нужна; пользовательские события не отправлять в sync |
| Sync | Local-first; foreground/reconnect; BGTask как opportunistic, без гарантии 30-секундного фонового цикла |

Не запрашивать контакты, геолокацию или доступ ко всем фото без фактической
потребности. PrivacyInfo.xcprivacy формируется по реально используемым API/SDK,
а не пустым шаблоном. App Privacy/Data safety должны соответствовать тому, что
email, заметки, проекты и финансовые данные уходят в sync, а вложения текущего
v3 остаются на устройстве. Внешняя служба распознавания речи рассматривается
отдельно. Политика приватности обязательна в интерфейсе и карточке магазина.

## Дизайн и размеры экранов

Сохранить сине-голубую/жёлтую палитру, узнаваемые карточки, цвета заметок и
логику разделов. SwiftUI layout должен учитывать safe areas, клавиатуру,
Dynamic Type/VoiceOver, маленький и большой iPhone, длинные русские подписи.
Не переносить Android dp/фиксированную высоту окна 30% в iOS буквально.

Тестировать минимальный поддерживаемый iPhone/OS и современные размеры около
375/390/430 pt, portrait/landscape, все размеры текста приложения и accessibility
sizes. Для login на малой ширине — поля друг под другом; controls должны иметь
достаточную область нажатия; длинные кнопки могут расти/переноситься. «Веб-страницу»
в iOS не добавлять; аккаунт/sync остаётся самостоятельным разделом.

## macOS, Xcode и сборочный маршрут

Эта рабочая среда — Linux без Xcode, Swift/iOS SDK и iPhone Simulator. Здесь
нельзя собрать/подписать IPA или проверить работу UIKit/SwiftUI. Подходящий
следующий маршрут — macOS runner GitHub Actions (`macos-26`, фактический image и
версии проверять при запуске) либо доступный Mac с Xcode. Не нужно покупать Mac
или подключать платный builder для завершения аудита. Изменения тарифа/платёжные
операции не выполнялись.

Apple на дату аудита требует Xcode 26+ / iOS 26 SDK+ для загрузок. Kotlin/Native
версию для shared framework выбирать по официальной compatibility matrix с
конкретной установленной версией Xcode; существующий Android Kotlin 2.1.21 не
обновлять вслепую. Локальный `tools/ios/preflight.sh` проверяет OS/Xcode/SDK и
наличие настоящего проекта; он не создаёт приложение.

После появления реальных targets:

1. В macOS CI проверить toolchain, зависимости/locks и собрать shared framework.
2. `xcodebuild -list -project mobile/ios/OrganizerPro.xcodeproj` должен показывать
   настоящий scheme OrganizerPro; fixture/domain tests запускать до UI.
3. Собрать simulator app с `CODE_SIGNING_ALLOWED=NO`, затем выполнить XCTest/UI
   tests на выбранных **реально доступных** simulator destinations. Имена/UDID
   брать из `xcrun simctl list devices available`, не вписывать выдуманный iPhone.
4. До подписи проверить bundle ID, privacy manifest, permissions, RU/EN resources,
   отсутствие debug tools/платежей и отсутствие секретов в артефакте.
5. Для archive нужны проверенный Apple Team ID, distribution certificate/private
   key и App Store provisioning profile либо поддерживаемый managed signing.
   Хранить их в защищённых CI secrets/keychain, не в репозитории или отчёте.
6. Выполнить archive для `generic/platform=iOS`, export для App Store Connect,
   сохранить dSYM и log. Сборка сама по себе не является публикацией.
7. Проверить archive и review notes, затем получить отдельное подтверждение
   загрузки в существующую карточку TestFlight. Внешнее тестирование может
   потребовать Beta App Review; приглашения друзьям — только по явной команде.

## Обязательная матрица качества

| Группа | Критерий готовности |
|---|---|
| Upgrade Android | Установка поверх исходной 0.9.38 тем же ключом, без uninstall/clear; все заметки/суммы/связи/вложения/PIN сохраняются |
| Backup | Android JSON/ZIP → iOS → экспорт → чистый тестовый Android; семантически одинаковые записи и байты вложений |
| Негативный restore | Повреждение ZIP/JSON/Base64, traversal, дубль ID, нет памяти/места, прерывание процесса: исходные данные остаются восстанавливаемыми |
| Два устройства | Разные офлайн-правки заметок/денег/проектов, удаление/правка одной записи, одинаковые локальные ID, reconnect в разном порядке, 409: ни одна сторона молча не теряет запись |
| Аккаунты | Смена A→B, guest→account, logout/expired token: нет автоматической передачи чужих локальных данных |
| Сеть | Offline, timeout, 401, 409, 413, 500, cold server, повтор PUT после обрыва: pending changes сохранены и статус правдив |
| UI | RU/EN, две темы, обычный/жирный текст, все масштабы, длинные строки, клавиатура/safe area/VoiceOver; реальные iPhone screenshots |
| Система | Notification/calendar/mic/camera denied/revoked; foreground/background/kill/reboot; даты и time zones/DST |
| Приватность | Запертое приложение не раскрывает заметку в уведомлении; Keychain и backup exclusions; account deletion подтверждена на тестовом аккаунте |
| Сервер | PostgreSQL transaction/concurrency tests на отдельной БД; проверенная страховочная копия и restore drill; production API не используется для destructive tests |
| Release | Полный release AAB и iOS archive, фиксированные зависимости, исходные идентификаторы, бесплатная цена; подпись и разрешения проверены |

Финальный выпуск обеих платформ синхронизировать по критериям, а не по одному
номеру версии. Успешная компиляция Android или shell iOS без функциональности не
означает прохождение этой матрицы.

Источники: [Apple SDK minimum](https://developer.apple.com/news/?id=ueeok6yw),
[App Review](https://developer.apple.com/app-store/review/guidelines/),
[KMP existing-app tutorial](https://kotlinlang.org/docs/multiplatform/multiplatform-integrate-in-existing-app.html),
[macOS runner contents](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md).
