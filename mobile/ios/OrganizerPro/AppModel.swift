import Foundation
import SwiftUI
import Combine
import UniformTypeIdentifiers
import OrganizerCore

struct AppNotice: Identifiable { let id = UUID(); let message: String }
struct BackupPreview { let snapshot: Snapshot; let name: String }
struct Presentation: Identifiable {
    let id = UUID()
    enum Kind {
        case note(OrganizerRecord?), project(OrganizerRecord?), money(String, Int64?), task(OrganizerRecord?)
        case stage(Int64), journal(Int64), link(Int64)
        case reminder(String, Int64, OrganizerRecord?), pin, backup(BackupPreview), share(URL), preview(URL)
    }
    let kind: Kind
}
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.zip, .json] }
    var data: Data
    init(data: Data = Data()) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw OrganizerError.invalid("file") }
        guard data.count <= BackupCodec.maxJSON else { throw OrganizerError.tooLarge }; self.data = data
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

@MainActor final class AppModel: ObservableObject {
    @Published var snapshot = Snapshot.empty()
    @Published var ready = false
    @Published var fatal: String?
    @Published var notice: AppNotice?
    @Published var busy = false
    @Published var locked = true
    @Published var pinUnavailable = false
    @Published var pinEnabled = false
    @Published var presentation: Presentation?
    @Published var importer = false
    @Published var exporter = false
    @Published var saveOptions = false
    @Published var exportDocument = BackupDocument()
    @Published var importRevision = UUID()
    private var queuedBackup: BackupPreview?
    private(set) var store: OrganizerStore?
    private(set) var vault: PinVault
    let reminders = ReminderService.shared
    let files: URL
    private let automaticScheduling: Bool
    private let isUITest: Bool
    var lang: String { snapshot.settings.text("language", "ru").hasPrefix("ru") ? "ru" : "en" }
    var locale: Locale { Locale(identifier: lang == "ru" ? "ru_RU" : "en_US") }
    var dark: Bool { snapshot.settings.flag("darkTheme") }
    var contentScale: Double { min(1.45, max(0.85, snapshot.settings.number("contentFontScale", snapshot.settings.number("fontScale", 1)))) }
    var buttonScale: Double { min(1.45, max(0.85, snapshot.settings.number("buttonFontScale", 1))) }
    var bold: Bool { snapshot.settings.flag("boldText") }
    var filename: String { "Organizer-Pro-\(ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-" )).zip" }
    init(files: URL? = nil, vault: PinVault? = nil, automaticScheduling: Bool = true) {
        var testSession: String?
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-ui-testing"), let i = args.firstIndex(of: "-test-session"), args.indices.contains(i + 1), UUID(uuidString: args[i + 1]) != nil { testSession = args[i + 1] }
        #endif
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("OrganizerPro", isDirectory: true)
        self.files = files ?? testSession.map { base.appendingPathComponent("UITests/" + $0, isDirectory: true) } ?? base
        self.vault = vault ?? PinVault(service: testSession.map { "com.organizerpro.mobile.uitest." + $0 } ?? "com.organizerpro.mobile.pin.v1")
        self.automaticScheduling = automaticScheduling && testSession == nil
        self.isUITest = testSession != nil
        open()
    }
    func t(_ ru: String, _ en: String) -> String { lang == "ru" ? ru : en }
    func open() {
        do {
            try FileManager.default.createDirectory(at: files, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            var excluded = files; var values = URLResourceValues(); values.isExcludedFromBackup = true; try excluded.setResourceValues(values)
            let db = try OrganizerStore(url: files.appendingPathComponent("organizer-ios.sqlite"), language: Locale.preferredLanguages.first?.hasPrefix("ru") == true ? "ru" : "en")
            if let scope = try db.draft(name: "installation")?.text("scope"), UUID(uuidString: scope) != nil { vault = vault.scoped(installation: scope) }
            store = db; snapshot = try db.load(); try protectFiles()
            refreshPIN(); ready = true; fatal = nil
            #if DEBUG
            if isUITest { configureUITest() }
            #endif
            reschedule()
        } catch { fatal = t("Не удалось открыть сохранённые данные. Они сохранены на устройстве. Закройте приложение и повторите попытку.", "Your saved data could not be opened. It remains on this device. Close the app and try again."); ready = false }
    }
    func protectFiles() throws {
        for url in try FileManager.default.contentsOfDirectory(at: files, includingPropertiesForKeys: nil) {
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        }
    }
    func refreshPIN() {
        do {
            let enabled = try vault.isEnabled()
            if !vault.exists, try store?.draft(name: "device-security")?.flag("installed") == true { throw PinVault.Failure.unavailable }
            pinUnavailable = false; pinEnabled = enabled; locked = enabled
        } catch { pinUnavailable = true; pinEnabled = true; locked = true }
    }
    func lock() { if pinEnabled || pinUnavailable { locked = true }; clearTemporaryFiles() }
    func unlock(_ pin: String) -> Bool {
        do { if try vault.verify(pin) { locked = false; return true } }
        catch { pinUnavailable = true }
        return false
    }
    func configurePIN(_ pin: String?, old: String) throws {
        if try vault.isEnabled(), !(try vault.verify(old)) { throw PinVault.Failure.rejected }
        if let pin { try vault.set(pin: pin) } else { try vault.disable() }
        try store?.saveDraft(name: "device-security", record: OrganizerRecord(["installed": .bool(pin != nil)]))
        pinEnabled = pin != nil; pinUnavailable = false; reschedule()
    }
    @discardableResult func perform(_ action: () throws -> Void) -> Bool {
        do { try action(); if let store { snapshot = try store.load() }; return true }
        catch { report(error); return false }
    }
    func report(_ error: Error) {
        let message: String
        if case OrganizerError.invalid = error { message = t("Копия повреждена, неполна или имеет неподдерживаемый формат. Существующие данные и вложения не изменены.", "The backup is damaged, incomplete or unsupported. Your existing data and attachments have not been changed.") }
        else if case OrganizerError.tooLarge = error { message = t("Файл слишком большой. Максимум: копия — 64 МБ, одно вложение — 25 МБ, все вложения — 48 МБ.", "The file is too large. Limits: backup 64 MB, one attachment 25 MB, all attachments 48 MB.") }
        else if case OrganizerError.ownerChanged = error { message = t("Объект изменился или был восстановлен из другой копии. Откройте его повторно. Черновик и несохранённая аудиозапись остались на устройстве.", "The item changed or was restored from another backup. Open it again. Your draft and any unsaved recording have been kept on this device.") }
        else { message = t("Не удалось завершить действие. Данные сохранены. Повторите попытку.", "The action could not be completed. Your data has been kept. Please try again.") }
        notice = AppNotice(message: message)
    }
    @discardableResult func mutate(_ change: (inout Snapshot) throws -> Void) -> Bool {
        let saved = perform { guard let store else { throw OrganizerError.storage("unavailable") }; try store.mutate(change) }
        if saved { reschedule() }; return saved
    }
    func setting(_ key: String, value: JSONValue) { mutate { var s = $0.settings; s.fields[key] = value; $0.settings = s } }
    @discardableResult func save(_ collection: String, record: OrganizerRecord, expectedProjectCreatedAt: Int64? = nil) -> Int64? {
        var saved = record
        let success = mutate { state in
            if ["notes", "tasks", "projects"].contains(collection), saved.fields["id"] != nil {
                guard let current = state.record(collection, id: saved.id), current.int("createdAt") == saved.int("createdAt") else { throw OrganizerError.ownerChanged }
            }
            if collection == "money" {
                guard ["income", "expense"].contains(saved.text("type")), saved.number("amount").isFinite, saved.number("amount") > 0 else { throw OrganizerError.storage("invalid money") }
            }
            if ["money", "projectStages", "projectJournal"].contains(collection), saved.fields["id"] == nil, saved.fields["projectId"] != nil {
                guard let project = state.record("projects", id: saved.int("projectId")), let expectedProjectCreatedAt, project.int("createdAt") == expectedProjectCreatedAt else { throw OrganizerError.ownerChanged }
                if collection == "money" { saved.set("projectName", project.text("name")) }
            }
            if saved.fields["id"] == nil { saved.set("id", try state.nextID(collection)); saved.set("createdAt", Snapshot.now) }
            if ["notes", "tasks", "projects"].contains(collection) { saved.set("updatedAt", Snapshot.now) }
            state.upsert(collection, record: saved)
            if collection == "projectStages" { state.updateStageProgress(projectID: saved.int("projectId")) }
            if collection == "projects" {
                state.setRecords("money", state.records("money").map { row in var r = row; if r.int("projectId", -1) == saved.id { r.set("projectName", saved.text("name")) }; return r })
            }
        }
        return success ? saved.id : nil
    }
    func remove(_ collection: String, id: Int64) {
        mutate { state in
            let owner = state.record(collection, id: id)?.int("projectId")
            state.remove(collection, id: id)
            if collection == "projectStages", let owner { state.updateStageProgress(projectID: owner) }
        }
    }
    func records(_ name: String) -> [OrganizerRecord] { snapshot.records(name) }
    func editorDraftKey(_ kind: String, record: OrganizerRecord?) -> String {
        guard let record else { return kind + ":new" }
        return "\(kind):\(record.id):\(record.int("createdAt"))"
    }
    func projectMatches(_ id: Int64, createdAt: Int64?) -> Bool {
        guard let createdAt, let current = snapshot.record("projects", id: id) else { return false }
        return current.int("createdAt") == createdAt
    }
    func draft(_ name: String) -> OrganizerRecord? { do { return try store?.draft(name: name) } catch { report(error); return nil } }
    func draft(_ name: String, record: OrganizerRecord?) { do { guard let store else { throw OrganizerError.storage("unavailable") }; try store.saveDraft(name: name, record: record) } catch { report(error) } }
    func amount(_ value: Decimal) -> String {
        let f = NumberFormatter(); f.locale = locale; f.numberStyle = .decimal; f.minimumFractionDigits = 2; f.maximumFractionDigits = 2
        return f.string(from: value as NSDecimalNumber) ?? "—"
    }
    func amount(_ value: Double) -> String { amount(Decimal(string: String(value), locale: Locale(identifier: "en_US_POSIX")) ?? 0) }
    func date(_ ms: Int64, time: Bool = false) -> String {
        guard ms > 0 else { return "—" }
        let f = DateFormatter(); f.locale = locale; f.dateStyle = .medium; f.timeStyle = time ? .short : .none
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
    func show(_ kind: Presentation.Kind) { presentation = Presentation(kind: kind) }
    func nextPresentation() { if let queuedBackup { self.queuedBackup = nil; show(.backup(queuedBackup)) } }
    func importBackup(_ url: URL) {
        guard !busy else { return }; busy = true
        Task {
            do {
                let snapshot = try await Task.detached(priority: .userInitiated) { try BackupCodec.decode(Self.readFile(url, limit: BackupCodec.maxJSON)) }.value
                let preview = BackupPreview(snapshot: snapshot, name: url.lastPathComponent)
                if presentation == nil { show(.backup(preview)) } else { queuedBackup = preview; presentation = nil }
            } catch { report(error) }
            busy = false
        }
    }
    nonisolated static func readFile(_ url: URL, limit: Int) throws -> Data {
        let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
        var output: Data?, failure: Error?
        NSFileCoordinator().coordinate(readingItemAt: url, options: .withoutChanges, error: nil) { readable in
            do {
                let handle = try FileHandle(forReadingFrom: readable); defer { try? handle.close() }
                var data = Data()
                while let part = try handle.read(upToCount: min(256 * 1024, limit + 1 - data.count)), !part.isEmpty {
                    data.append(part); if data.count > limit { throw OrganizerError.tooLarge }
                }
                output = data
            } catch { failure = error }
        }
        if let failure { throw failure }; guard let output else { throw OrganizerError.invalid("file") }; return output
    }
    func restore(_ preview: BackupPreview) -> Bool {
        let result = perform { guard let store else { throw OrganizerError.storage("unavailable") }; try store.restore(preview.snapshot) }
        if result { reschedule(); presentation = nil }; return result
    }
    func prepareExport(toFile: Bool) {
        guard let store, !locked else { return }
        do {
            exportDocument = BackupDocument(data: try store.export())
            if toFile { exporter = true }
            else { let url = try temporary(data: exportDocument.data, name: filename); show(.share(url)) }
        } catch { report(error) }
    }
    func temporary(data: Data, name: String) throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("organizer-private/" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        let safeName = URL(fileURLWithPath: name).lastPathComponent
        let url = folder.appendingPathComponent(safeName.isEmpty ? "attachment" : safeName)
        try data.write(to: url, options: [.atomic, .completeFileProtection]); return url
    }
    func clearTemporaryFiles() {
        // UIKit viewers / share extensions may still be reading the current file. Remove old files on launch / next export.
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("organizer-private")
        let old = Date().addingTimeInterval(-86400)
        for url in (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            if ((try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantFuture) < old { try? FileManager.default.removeItem(at: url) }
        }
    }
    func addFile(_ url: URL, type: String, id: Int64, expectedOwnerCreatedAt: Int64? = nil) {
        do { try attach(Self.readFile(url, limit: BackupCodec.maxAttachment), name: url.lastPathComponent, mime: UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream", type: type, id: id, expectedOwnerCreatedAt: expectedOwnerCreatedAt) }
        catch { report(error) }
    }
    func attach(_ data: Data, name: String, mime: String, type: String, id: Int64, duration: Int64 = 0, expectedOwnerCreatedAt: Int64? = nil) throws {
        guard let store else { throw OrganizerError.storage("unavailable") }
        try store.addAttachment(data: data, displayName: name, mimeType: mime, targetType: type, targetID: id, duration: duration, expectedOwnerCreatedAt: expectedOwnerCreatedAt)
        snapshot = try store.load()
    }
    func reschedule() { if automaticScheduling { reminders.update(snapshot, protected: pinEnabled || pinUnavailable, language: lang) } }
    #if DEBUG
    private var testImportHandled = false
    func openUITestImportIfNeeded() {
        guard isUITest, !testImportHandled, let encoded = ProcessInfo.processInfo.environment["ORGANIZER_UI_IMPORT"], let data = Data(base64Encoded: encoded), data.count < 128 * 1024 else { return }
        testImportHandled = true
        do { let url = files.appendingPathComponent("test-attachment.json"); try data.write(to: url); importBackup(url) } catch { report(error) }
    }
    private func configureUITest() {
        // Compiled out of Release. Data lives in the test app's disposable simulator container.
        let args = ProcessInfo.processInfo.arguments
        if let encoded = ProcessInfo.processInfo.environment["ORGANIZER_UI_FIXTURE"], let data = Data(base64Encoded: encoded), data.count < 128 * 1024 { _ = perform { try store?.restore(BackupCodec.decode(data)) } }
        if let i = args.firstIndex(of: "-test-language"), args.indices.contains(i + 1) { setting("language", value: .string(args[i + 1])) }
        setting("darkTheme", value: .bool(args.contains("-test-dark")))
        setting("contentFontScale", value: .number(args.contains("-test-large") ? 1.45 : 1)); setting("buttonFontScale", value: .number(args.contains("-test-large") ? 1.45 : 1)); setting("boldText", value: .bool(args.contains("-test-large")))
        if args.contains("-test-pin"), !pinEnabled { try? configurePIN("2468", old: ""); locked = true }
    }
    #endif
}
