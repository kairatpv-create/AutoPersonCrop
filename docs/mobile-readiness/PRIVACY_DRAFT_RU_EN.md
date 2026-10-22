# Organizer Pro — privacy policy draft / черновик политики

**НЕ ПУБЛИКОВАТЬ БЕЗ УТОЧНЕНИЙ НИЖЕ.** Основано на исходниках 22.10.2026; будущий iOS не описывается как уже выпущенный продукт. Справка в Android не заменяет публичную политику и заполнение форм магазинов.

Требует подтверждения: юридический оператор и юрисдикция/адрес при необходимости; актуальность контакта; производственные инфраструктурные обработчики, технические логи и их сроки; наличие/сроки хранения PostgreSQL backup/PITR и порядок удаления из них; публичные URL политики и удаления. Не выдумывать «30 дней» для копий: JWT 30 дней не является сроком хранения БД.

## Русский

Разработчик Organizer Pro («Органайзер Про»): Kairat Kaliyev. Контакт, указанный в приложении: kairat.pv@gmail.com.

Приложение хранит заметки, задачи, проекты, финансовые записи, напоминания, настройки и вложения на устройстве. Локальные функции доступны без аккаунта. Вход включает синхронизацию записей с сервером. Email и пароль передаются по HTTPS; сервер хранит email, идентификатор аккаунта и хеш пароля. Текущий сервер использует Render и PostgreSQL. Состав инфраструктурных логов и срок хранения технических копий нужно указать после проверки действующего окружения.

При синхронизации на сервер передаются заметки, задачи, проекты, этапы/журнал/связи проектов, финансовые записи и напоминания. Вложения текущей реализацией синхронизации не загружаются. Данные используются для функций аккаунта и обмена между устройствами. Подключённых SDK рекламы/аналитики и платёжных механизмов в этой сборке нет.

Микрофон и календарь запрашиваются для выбранных пользователем функций. Системное распознавание речи может использовать сервис выбранного поставщика; его условия и возможность обработки онлайн нужно проверять отдельно.

PIN ограничивает открытие интерфейса и скрывает содержание новых уведомлений при включённой защите; он не шифрует SQLite. Экспорт JSON/ZIP не зашифрован. Android system backup может включать базу, файлы и настройки в соответствии с настройками устройства и текущими правилами приложения. Экспортированные пользователем копии могут находиться вне приложения.

После согласованного развёртывания функции удаления пользователь сможет удалить аккаунт и активную серверную копию с подтверждением пароля в настройках либо через внешнюю страницу удаления. До развёртывания эта функция на действующем сервере не подтверждена. Удаление не стирает автоматически копии на устройствах или экспортированные файлы. Срок и порядок удаления технических backup/PITR-копий должен быть уточнён до публикации этой политики.

Запросы о персональных данных можно направить разработчику. Изменения политики публикуются с датой вступления в силу после проверки фактического поведения выпускаемой версии.

## English

Organizer Pro is developed by Kairat Kaliyev. The contact currently shown in the app is kairat.pv@gmail.com.

The app stores notes, tasks, projects, financial records, reminders, settings and attachments on the device. Local features can be used without an account. Signing in enables server sync. Your email and password are transmitted over HTTPS; the server stores your email, account identifier and password hash. The current server uses Render and PostgreSQL. Infrastructure logs and technical backup retention must be specified after verifying the live environment.

Sync transfers notes, tasks, projects, project stages/journal/links, financial records and reminders to the server to support your account and cross-device exchange. The current implementation does not upload attachments. This build includes no advertising or analytics SDKs or payment mechanisms.

Microphone and calendar permissions support features you choose. System speech recognition may use your selected provider's service; its terms and online processing require separate verification.

A PIN restricts opening the interface and hides the content of new notifications when enabled; it does not encrypt SQLite. Exported JSON/ZIP files are not encrypted. Android system backup may include app data, files and settings according to device settings and current app backup rules. Files you export may be stored outside the app.

After approved deployment, account deletion will remove the account and active server snapshot after password confirmation, through app settings or a separate external deletion page. This functionality has not yet been verified on the live server. It does not automatically erase copies on devices or exported files. Retention and deletion of technical database backup/PITR copies must be specified before publishing this policy.

Contact the developer with data requests. Policy changes will be published with an effective date after verifying the released version's actual behavior.
