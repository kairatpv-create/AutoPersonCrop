import SwiftUI
import OrganizerCore

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var restoreOptions = false
    @State private var safety = false
    var body: some View {
        Page {
            Card {
                Text(model.t("Интерфейс", "Appearance")).font(.headline)
                Picker(model.t("Язык", "Language"), selection: Binding(get: { model.lang }, set: { model.setting("language", value: .string($0)) })) { Text("Русский").tag("ru"); Text("English").tag("en") }.pickerStyle(.segmented).accessibilityIdentifier("languagePicker")
                Toggle(model.t("Тёмная тема", "Dark theme"), isOn: Binding(get: { model.dark }, set: { model.setting("darkTheme", value: .bool($0)) })).accessibilityIdentifier("darkTheme")
                Toggle(model.t("Жирный шрифт", "Bold text"), isOn: Binding(get: { model.bold }, set: { model.setting("boldText", value: .bool($0)) })).accessibilityIdentifier("boldText")
                Text(model.t("Размер текста заметок и записей", "Notes and entries text size")).fixedSize(horizontal: false, vertical: true)
                HStack { Text("A").font(.caption); Slider(value: Binding(get: { model.contentScale }, set: { value in model.mutate { var settings = $0.settings; settings.set("fontScale", value); settings.set("contentFontScale", value); $0.settings = settings } }), in: 0.85...1.45, step: 0.05).accessibilityLabel(model.t("Размер текста записей", "Entry text size")); Text("A").font(.title2) }
                Text(model.t("Размер текста кнопок", "Button text size")).fixedSize(horizontal: false, vertical: true)
                HStack { Text("A").font(.caption); Slider(value: Binding(get: { model.buttonScale }, set: { model.setting("buttonFontScale", value: .number($0)) }), in: 0.85...1.45, step: 0.05).accessibilityLabel(model.t("Размер текста кнопок", "Button text size")); Text("A").font(.title2) }
                Toggle(model.t("Показывать проекты", "Show projects"), isOn: Binding(get: { model.snapshot.settings.flag("projectsEnabled", true) }, set: { model.setting("projectsEnabled", value: .bool($0)) }))
                UserText(model.t("Пример текста заметки", "Sample note text"))
            }
            Card(color: "yellow") {
                Text(model.t("Сохранение данных", "Your backups")).font(.headline)
                Button { model.saveOptions = true } label: { Label(model.t("Сохранить файл", "Save to file"), systemImage: "square.and.arrow.up").frame(maxWidth: .infinity, alignment: .leading) }.buttonStyle(.bordered).accessibilityIdentifier("saveBackup")
                Button { restoreOptions = true } label: { Label(model.t("Восстановить из файла", "Restore from file"), systemImage: "square.and.arrow.down").frame(maxWidth: .infinity, alignment: .leading) }.buttonStyle(.bordered).accessibilityIdentifier("restoreBackup")
                Text(model.t("Копия содержит заметки, финансы, проекты, настройки и вложения. Её можно отправить через ваше почтовое приложение. Для восстановления сохраните вложение из письма или откройте его в Органайзере Про.", "A backup includes notes, finances, projects, preferences and attachments. Send it using your e-mail app. To restore, save the attachment from the message or open it in Organizer Pro.")).font(.caption).fixedSize(horizontal: false, vertical: true)
                Text(model.t("В копии нет PIN-кода. Храните файл в надёжном месте: он содержит ваши записи.", "Your PIN is excluded. Keep the file in a safe place: it contains your records.")).font(.caption).foregroundStyle(.secondary)
            }
            Card {
                Text(model.t("Безопасность", "Security")).font(.headline)
                Button(model.pinEnabled ? model.t("Изменить PIN-код", "Change PIN code") : model.t("Установить PIN-код", "Set PIN code")) { model.show(.pin) }.buttonStyle(.bordered).accessibilityIdentifier("pinSettings")
                if model.pinEnabled { Button(model.t("Заблокировать", "Lock now")) { model.lock() }.buttonStyle(.bordered) }
                Text(model.t("Данные хранятся на этом iPhone. Регулярно сохраняйте копию, чтобы перенести записи на другое устройство или восстановить их после потери телефона.", "Your data is stored on this iPhone. Save backups regularly to transfer your records or recover them if your phone is lost.")).font(.caption)
            }
            NavigationLink(value: Route.privacy) { Label(model.t("Приватность", "Privacy"), systemImage: "hand.raised") }.padding(.horizontal, 4)
            Text("Organizer Pro · iOS \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0") (\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"))").font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity)
        }.confirmationDialog(model.t("Выбрать копию", "Choose a backup"), isPresented: $restoreOptions, titleVisibility: .visible) {
            Button(model.t("Выбрать файл или сохранённое вложение", "Choose a file or saved attachment")) { model.importer = true }
            Button(model.t("Данные до последнего восстановления", "Data before a previous restore")) { safety = true }
        }.navigationDestination(isPresented: $safety) { SafetyView() }
    }
}
struct PINSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var old = ""
    @State private var pin = ""
    @State private var repeatPIN = ""
    @State private var message = ""
    @State private var disabling = false
    var valid: Bool { (4...12).contains(pin.count) && pin == repeatPIN && pin.allSatisfy { $0.isASCII && $0.isNumber } }
    var body: some View {
        Form {
            if model.pinEnabled { SecureField(model.t("Текущий PIN-код", "Current PIN"), text: $old).keyboardType(.numberPad).textContentType(.password).accessibilityIdentifier("currentPIN") }
            SecureField(model.t("Новый PIN-код: 4–12 цифр", "New PIN: 4–12 digits"), text: $pin).keyboardType(.numberPad).textContentType(.newPassword).accessibilityIdentifier("newPIN")
            SecureField(model.t("Повторите PIN-код", "Repeat PIN"), text: $repeatPIN).keyboardType(.numberPad).textContentType(.newPassword).accessibilityIdentifier("repeatPIN")
            if !message.isEmpty { Text(message).foregroundStyle(.red) }
            Text(model.t("Запомните PIN-код. Он хранится только на этом устройстве и не входит в резервную копию.", "Remember your PIN. It stays on this device and is excluded from backups.")).font(.caption)
            if model.pinEnabled { Button(model.t("Отключить PIN-код", "Disable PIN"), role: .destructive) { disabling = true } }
        }.navigationTitle(model.t("PIN-код", "PIN code"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(model.t("Закрыть", "Close")) { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button(model.t("Сохранить", "Save")) { change(pin) }.disabled(!valid).accessibilityIdentifier("savePIN") } }
            .confirmationDialog(model.t("Отключить защиту PIN-кодом?", "Disable PIN protection?"), isPresented: $disabling, titleVisibility: .visible) { Button(model.t("Отключить", "Disable"), role: .destructive) { change(nil) } }
    }
    func change(_ pin: String?) {
        do { try model.configurePIN(pin, old: old); old = ""; self.pin = ""; repeatPIN = ""; dismiss() }
        catch { message = model.t("Не удалось изменить PIN-код. Проверьте текущий PIN и повторите попытку. После пяти ошибок действует пауза 30 секунд.", "Could not change the PIN. Check your current PIN and try again. Five failed attempts trigger a 30-second pause.") }
    }
}
struct BackupConfirmation: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let preview: BackupPreview
    @State private var confirm = false
    @State private var pin = ""
    @State private var rejected = false
    var body: some View {
        Form {
            Section(model.t("Проверенная копия", "Validated backup")) {
                Text(preview.name).textSelection(.enabled)
                LabeledContent(model.t("Дата", "Date"), value: model.date(preview.snapshot.root["createdAt"]?.integer ?? 0, time: true))
                LabeledContent(model.t("Заметки", "Notes"), value: "\(preview.snapshot.records("notes").count)")
                LabeledContent(model.t("Финансовые записи", "Financial entries"), value: "\(preview.snapshot.records("money").count)")
                LabeledContent(model.t("Проекты", "Projects"), value: "\(preview.snapshot.records("projects").count)")
                LabeledContent(model.t("Задачи", "Tasks"), value: "\(preview.snapshot.records("tasks").count)")
                LabeledContent(model.t("Вложения", "Attachments"), value: preview.snapshot.root["attachments"] == nil ? model.t("Не входят в снимок", "Excluded from this snapshot") : "\(preview.snapshot.records("attachments").count)")
            }
            Section {
                Text(model.t("Восстановление заменит текущие записи содержимым копии. Перед заменой приложение сохранит страховочную копию на этом устройстве.", "Restoring replaces your current records with this backup. Before replacing them, the app saves a safety copy on this device.")).fixedSize(horizontal: false, vertical: true)
                if Snapshot.collections.allSatisfy({ preview.snapshot.records($0).isEmpty }) { Text(model.t("Это корректная пустая копия. После восстановления список записей будет пустым.", "This is a valid empty backup. Restoring it will leave your record lists empty.")).fontWeight(.semibold).foregroundStyle(.red) }
                if model.pinEnabled { SecureField(model.t("Введите PIN-код для восстановления", "Enter PIN to restore"), text: $pin).keyboardType(.numberPad).textContentType(.password).accessibilityIdentifier("restorePIN") }
                if rejected { Text(model.t("Неверный PIN-код или действует временная блокировка.", "Incorrect PIN or a temporary lock is active.")).foregroundStyle(.red) }
                Button(model.t("Восстановить данные", "Restore data"), role: .destructive) {
                    if model.pinEnabled { do { guard try model.vault.verify(pin) else { rejected = true; pin = ""; return } } catch { model.refreshPIN(); return } }
                    confirm = true
                }.disabled(model.pinEnabled && pin.count < 4).accessibilityIdentifier("confirmRestore")
            }
        }.navigationTitle(model.t("Восстановление", "Restore"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(model.t("Отмена", "Cancel")) { dismiss() } } }
            .confirmationDialog(model.t("Заменить текущие данные этой копией?", "Replace your current data with this backup?"), isPresented: $confirm, titleVisibility: .visible) { Button(model.t("Восстановить", "Restore"), role: .destructive) { _ = model.restore(preview) } }
    }
}
struct SafetyView: View {
    @EnvironmentObject private var model: AppModel
    @State private var copies: [SafetyCopy] = []
    var body: some View {
        List {
            Section { Text(model.t("Эти копии сохраняются автоматически перед восстановлением. Просмотрите содержимое перед заменой текущих данных.", "These copies are saved automatically before a restore. Review the contents before replacing your current data.")) }
            ForEach(copies) { copy in Button(model.date(copy.createdAt, time: true)) { do { if let snapshot = try model.store?.safetyCopy(id: copy.id) { model.show(.backup(BackupPreview(snapshot: snapshot, name: model.t("Данные до восстановления", "Pre-restore data")))) } } catch { model.report(error) } } }
            if copies.isEmpty { Text(model.t("Восстановлений ещё не было", "No previous restores")) }
        }.navigationTitle(model.t("Данные до восстановления", "Pre-restore data")).onAppear { do { copies = try model.store?.safetyCopies() ?? [] } catch { model.report(error) } }
    }
}
struct PrivacyView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        List {
            Section(model.t("Ваши записи", "Your records")) { Text(model.t("Заметки, финансы, проекты и вложения хранятся в защищённом контейнере приложения на iPhone. Аккаунт не требуется. Приложение не отправляет записи на сервер разработчика.", "Notes, finances, projects and attachments stay in the protected app container on your iPhone. No account is needed. The app does not send your records to the developer's server.")) }
            Section(model.t("Резервные копии и e-mail", "Backups and e-mail")) { Text(model.t("Вы выбираете место сохранения и почтовое приложение. Отправку письма подтверждаете самостоятельно. Приложение не читает почтовый ящик и не запрашивает пароль от e-mail. Файл копии не зашифрован PIN-кодом.", "You choose where to save and which e-mail app to use. You confirm sending the message yourself. Organizer Pro does not read your mailbox or ask for your e-mail password. The backup file is not encrypted by your PIN.")) }
            Section(model.t("Разрешения", "Permissions")) { Text(model.t("Камера и микрофон используются для выбранных вами вложений. Доступ к календарям нужен только для показа событий и дней рождения. Выбор фотографий работает через системное окно без доступа ко всей медиатеке. Распознавание речи использует службы Apple и может обрабатывать звук через интернет, если распознавание на устройстве недоступно.", "Camera and microphone access is used for attachments you choose. Calendar access displays events and birthdays. Photo selection uses the system picker without access to your entire library. Speech recognition uses Apple services and may process audio online when on-device recognition is unavailable.")) }
            Section(model.t("Защита", "Protection")) { Text(model.t("PIN-код защищает вход в приложение. База данных защищается средствами iOS. PIN-код не восстанавливается из файла. Удаление приложения удаляет его локальные данные: заранее сохраните полную копию.", "The PIN protects app access. iOS Data Protection protects the database. A file backup does not restore the PIN. Removing the app removes its local data, so save a full backup first.")) }
            Section { Text("Kairat Kaliyev · Organizer Pro") }
        }.navigationTitle(model.t("Приватность", "Privacy"))
    }
}
