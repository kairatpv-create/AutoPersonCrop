import Foundation
import UserNotifications
import UIKit
import BackgroundTasks
import OrganizerCore
import Combine

extension Notification.Name { static let organizerOpenReminder = Notification.Name("OrganizerOpenReminder") }
@MainActor protocol NotificationDelivery: AnyObject {
    func status() async -> UNAuthorizationStatus
    func requestPermission() async throws -> Bool
    func pending() async -> [UNNotificationRequest]
    func add(_ request: UNNotificationRequest) async throws
    func remove(_ ids: [String])
    func clearDelivered()
    func categories(_ categories: Set<UNNotificationCategory>)
}
@MainActor final class SystemNotificationDelivery: NotificationDelivery {
    let center = UNUserNotificationCenter.current()
    func status() async -> UNAuthorizationStatus { await center.notificationSettings().authorizationStatus }
    func requestPermission() async throws -> Bool { try await center.requestAuthorization(options: [.alert, .sound]) }
    func pending() async -> [UNNotificationRequest] { await center.pendingNotificationRequests() }
    func add(_ request: UNNotificationRequest) async throws { try await center.add(request) }
    func remove(_ ids: [String]) { center.removePendingNotificationRequests(withIdentifiers: ids) }
    func clearDelivered() { center.removeAllDeliveredNotifications() }
    func categories(_ categories: Set<UNNotificationCategory>) { center.setNotificationCategories(categories) }
}
@MainActor final class ReminderService: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = ReminderService()
    @Published var deferred = 0
    @Published var invalid = 0
    @Published var denied = false
    @Published var failed = false
    private var operation: Task<Void, Never>?
    private let delivery: any NotificationDelivery
    private let birthdaySource: () -> [CalendarAccess.Birthday]
    override convenience init() { self.init(delivery: SystemNotificationDelivery()) }
    init(delivery: any NotificationDelivery, birthdays: (() -> [CalendarAccess.Birthday])? = nil) {
        self.delivery = delivery; birthdaySource = birthdays ?? { CalendarAccess.birthdays() }
        super.init()
        if let system = delivery as? SystemNotificationDelivery { system.center.delegate = self }
    }
    func requestPermission() async -> Bool {
        do { let allowed = try await delivery.requestPermission(); denied = !allowed; return allowed }
        catch { failed = true; return false }
    }
    func update(_ snapshot: Snapshot, protected: Bool, language: String) {
        let previous = operation; previous?.cancel()
        operation = Task { if let previous { await previous.value }; guard !Task.isCancelled else { return }; await install(snapshot, protected: protected, language: language) }
    }
    func wait() async { await operation?.value }
    func cancel() { operation?.cancel() }
    private func install(_ snapshot: Snapshot, protected: Bool, language: String) async {
        let status = await delivery.status(), old = await delivery.pending()
        guard !Task.isCancelled else { return }
        denied = status == .denied; failed = false
        if protected { delivery.clearDelivered() }
        guard status == .authorized || status == .provisional else { delivery.remove(old.map(\.identifier)); return }
        let snooze = UNNotificationAction(identifier: "snooze10", title: language == "ru" ? "Через 10 минут" : "In 10 minutes", options: [])
        delivery.categories([UNNotificationCategory(identifier: "organizer-reminder", actions: [snooze], intentIdentifiers: [], options: [])])
        let now = Date(), records = snapshot.records("reminders")
        let snoozed = old.compactMap { request -> UNNotificationRequest? in
            guard request.identifier.hasPrefix("snooze."), let id = request.content.userInfo["reminderId"] as? Int64,
                  let created = request.content.userInfo["reminderCreatedAt"] as? Int64,
                  let timestamp = request.content.userInfo["snoozeAt"] as? Double, timestamp.isFinite,
                  timestamp > now.timeIntervalSince1970, let row = records.first(where: { $0.id == id }), row.int("createdAt") == created,
                  row.int("targetId") == request.content.userInfo["targetId"] as? Int64, row.text("targetType") == request.content.userInfo["targetType"] as? String else { return nil }
            let content = Self.privateContent(request.content, language: language)
            return UNNotificationRequest(identifier: request.identifier, content: content, trigger: Self.once(Date(timeIntervalSince1970: timestamp)))
        }
        let budget = max(0, 64 - snoozed.count)
        let plan = ReminderPlan.make(records, limit: budget)
        invalid = plan.invalid
        var candidates: [(UNNotificationRequest, Date)] = []
        for item in plan.requests {
            let r = item.record, content = UNMutableNotificationContent()
            let target = snapshot.record(r.text("targetType") == "note" ? "notes" : "projects", id: r.int("targetId"))
            content.title = protected ? (language == "ru" ? "Органайзер Про" : "Organizer Pro") : target?.text(r.text("targetType") == "note" ? "title" : "name") ?? (language == "ru" ? "Напоминание" : "Reminder")
            content.body = protected ? (language == "ru" ? "Откройте приложение для просмотра" : "Open the app to view your reminder") : (language == "ru" ? "Напоминание" : "Reminder")
            content.categoryIdentifier = "organizer-reminder"; content.userInfo = ["targetType": r.text("targetType"), "targetId": r.int("targetId"), "reminderId": r.id, "reminderCreatedAt": r.int("createdAt"), "protected": protected, "language": language]
            if r.flag("sound", true) { content.sound = .default }
            candidates.append((UNNotificationRequest(identifier: item.id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: item.components, repeats: item.repeats)), item.next))
        }
        for (index, birthday) in birthdaySource().enumerated() {
            let content = UNMutableNotificationContent(); content.title = language == "ru" ? "Завтра день рождения" : "Birthday tomorrow"
            content.body = protected ? (language == "ru" ? "Откройте календарь в приложении" : "Open the calendar in the app") : birthday.title
            content.sound = .default
            content.userInfo = ["targetType": "calendar", "protected": protected, "language": language]
            candidates.append((UNNotificationRequest(identifier: "birthday.\(index)", content: content, trigger: Self.once(birthday.date)), birthday.date))
        }
        candidates.sort { $0.1 == $1.1 ? $0.0.identifier < $1.0.identifier : $0.1 < $1.1 }
        let wanted = snoozed + candidates.prefix(budget).map(\.0), ids = Set(wanted.map(\.identifier))
        deferred = plan.deferred + max(0, candidates.count - budget)
        guard !Task.isCancelled else { return }
        // Preserve still-valid requests and absolute snooze deadlines. Remove obsolete IDs only.
        // When enabling PIN, remove any old unprotected content before attempting replacement.
        delivery.remove(old.filter { !ids.contains($0.identifier) || (protected && $0.content.userInfo["protected"] as? Bool != true) }.map(\.identifier))
        for request in wanted {
            guard !Task.isCancelled else { return }
            do { try await delivery.add(request) } catch { failed = true }
        }
        if delivery is SystemNotificationDelivery { OrganizerAppDelegate.scheduleRefresh() }
    }
    nonisolated static func once(_ date: Date) -> UNCalendarNotificationTrigger {
        UNCalendarNotificationTrigger(dateMatching: Calendar.current.dateComponents([.calendar, .timeZone, .year, .month, .day, .hour, .minute, .second], from: date), repeats: false)
    }
    nonisolated static func privateContent(_ original: UNNotificationContent, language: String) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent(); content.title = language == "ru" ? "Органайзер Про" : "Organizer Pro"
        content.body = language == "ru" ? "Откройте приложение для просмотра" : "Open the app to view your reminder"
        content.sound = original.sound; content.categoryIdentifier = original.categoryIdentifier; content.userInfo = original.userInfo; content.userInfo["protected"] = true
        return content
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions { [.banner, .sound, .list] }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if response.actionIdentifier == "snooze10" {
            let original = response.notification.request.content, date = Date().addingTimeInterval(600)
            let content = Self.privateContent(original, language: original.userInfo["language"] as? String ?? "ru"); content.userInfo["snoozeAt"] = date.timeIntervalSince1970
            let id = "snooze.r.\(original.userInfo["reminderId"] as? Int64 ?? 0)"
            try? await center.add(UNNotificationRequest(identifier: id, content: content, trigger: Self.once(date)))
        } else {
            let type = response.notification.request.content.userInfo["targetType"] as? String ?? ""
            let id = response.notification.request.content.userInfo["targetId"] as? Int64 ?? 0
            await MainActor.run { NotificationCenter.default.post(name: .organizerOpenReminder, object: nil, userInfo: ["targetType": type, "targetId": id]) }
        }
    }
}
final class OrganizerAppDelegate: NSObject, UIApplicationDelegate {
    static let refreshID = "com.organizerpro.mobile.refresh"
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshID, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            let work = Task { @MainActor in
                Self.scheduleRefresh()
                do {
                    let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("OrganizerPro/organizer-ios.sqlite")
                    guard FileManager.default.fileExists(atPath: url.path) else { refresh.setTaskCompleted(success: false); return }
                    let snapshot = try OrganizerStore(url: url).load()
                    ReminderService.shared.update(snapshot, protected: true, language: snapshot.settings.text("language", "ru")); await ReminderService.shared.wait()
                    refresh.setTaskCompleted(success: !Task.isCancelled)
                } catch { refresh.setTaskCompleted(success: false) }
            }
            refresh.expirationHandler = { work.cancel(); Task { @MainActor in ReminderService.shared.cancel() } }
        }
        return true
    }
    static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshID); request.earliestBeginDate = Date().addingTimeInterval(12 * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }
}
