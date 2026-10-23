import Foundation
import UserNotifications
import UIKit
import BackgroundTasks
import OrganizerCore
import Combine

extension Notification.Name { static let organizerOpenReminder = Notification.Name("OrganizerOpenReminder") }
@MainActor final class ReminderService: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = ReminderService()
    @Published var deferred = 0
    @Published var invalid = 0
    @Published var denied = false
    @Published var failed = false
    private var operation: Task<Void, Never>?
    private let center = UNUserNotificationCenter.current()
    override init() { super.init(); center.delegate = self }
    func requestPermission() async -> Bool {
        do { let allowed = try await center.requestAuthorization(options: [.alert, .badge, .sound]); denied = !allowed; return allowed }
        catch { failed = true; return false }
    }
    func update(_ snapshot: Snapshot, protected: Bool, language: String) {
        let previous = operation; previous?.cancel()
        operation = Task { if let previous { await previous.value }; guard !Task.isCancelled else { return }; await install(snapshot, protected: protected, language: language) }
    }
    func wait() async { await operation?.value }
    private func install(_ snapshot: Snapshot, protected: Bool, language: String) async {
        let settings = await center.notificationSettings()
        denied = settings.authorizationStatus == .denied
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        let plan = ReminderPlan.make(snapshot.records("reminders")); deferred = plan.deferred; invalid = plan.invalid; failed = false
        let snooze = UNNotificationAction(identifier: "snooze10", title: language == "ru" ? "Через 10 минут" : "In 10 minutes", options: [])
        center.setNotificationCategories([UNNotificationCategory(identifier: "organizer-reminder", actions: [snooze], intentIdentifiers: [], options: [])])
        center.removeAllPendingNotificationRequests()
        if protected { center.removeAllDeliveredNotifications() }
        for item in plan.requests {
            if Task.isCancelled { return }
            let r = item.record, content = UNMutableNotificationContent()
            let target = snapshot.record(r.text("targetType") == "note" ? "notes" : "projects", id: r.int("targetId"))
            content.title = protected ? (language == "ru" ? "Органайзер Про" : "Organizer Pro") : target?.text(r.text("targetType") == "note" ? "title" : "name") ?? (language == "ru" ? "Напоминание" : "Reminder")
            content.body = protected ? (language == "ru" ? "Откройте приложение для просмотра" : "Open the app to view your reminder") : (language == "ru" ? "Напоминание" : "Reminder")
            content.categoryIdentifier = "organizer-reminder"; content.userInfo = ["targetType": r.text("targetType"), "targetId": r.int("targetId")]
            if r.flag("sound", true) { content.sound = .default }
            do { try await center.add(UNNotificationRequest(identifier: item.id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: item.components, repeats: item.repeats))) } catch { failed = true }
        }
        let birthdays = CalendarAccess.birthdays()
        let available = max(0, 64 - plan.requests.count)
        deferred += max(0, birthdays.count - available)
        for (index, birthday) in birthdays.prefix(available).enumerated() {
            if Task.isCancelled { return }
            let content = UNMutableNotificationContent(); content.title = language == "ru" ? "Завтра день рождения" : "Birthday tomorrow"
            content.body = protected ? (language == "ru" ? "Откройте календарь в приложении" : "Open the calendar in the app") : birthday.title
            content.sound = .default
            let components = Calendar.current.dateComponents([.calendar, .timeZone, .year, .month, .day, .hour, .minute], from: birthday.date)
            do { try await center.add(UNNotificationRequest(identifier: "birthday.\(index)", content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))) } catch { failed = true }
        }
        OrganizerAppDelegate.scheduleRefresh()
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions { [.banner, .sound, .list] }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if response.actionIdentifier == "snooze10" {
            let content = (response.notification.request.content.mutableCopy() as! UNMutableNotificationContent)
            try? await center.add(UNNotificationRequest(identifier: "snooze." + response.notification.request.identifier, content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: 600, repeats: false)))
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
            refresh.expirationHandler = { work.cancel() }
        }
        return true
    }
    static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshID); request.earliestBeginDate = Date().addingTimeInterval(12 * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }
}
