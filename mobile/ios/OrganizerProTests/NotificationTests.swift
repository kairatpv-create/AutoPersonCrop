import XCTest
import UserNotifications
import OrganizerCore
@testable import OrganizerPro

@MainActor final class FakeNotificationDelivery: NotificationDelivery {
    var authorization = UNAuthorizationStatus.authorized
    var requests: [String: UNNotificationRequest] = [:]
    var removed: [String] = []
    var deliveredCleared = false
    var delay = false
    var rejectedID: String?
    func status() async -> UNAuthorizationStatus { authorization }
    func requestPermission() async throws -> Bool { authorization == .authorized }
    func pending() async -> [UNNotificationRequest] { Array(requests.values) }
    func add(_ request: UNNotificationRequest) async throws {
        if delay { try await Task.sleep(for: .milliseconds(20)) }
        if request.identifier == rejectedID { throw NSError(domain: "SyntheticNotificationFailure", code: 1) }
        requests[request.identifier] = request
    }
    func remove(_ ids: [String]) { removed += ids; for id in ids { requests.removeValue(forKey: id) } }
    func clearDelivered() { deliveredCleared = true }
    func categories(_ categories: Set<UNNotificationCategory>) {}
}
final class NotificationTests: XCTestCase {
    func fixture() throws -> Snapshot { try BackupCodec.decode(Data(contentsOf: XCTUnwrap(Bundle(for: Self.self).url(forResource: "android-v6", withExtension: "json", subdirectory: "Fixtures")))) }
    @MainActor func snooze(_ row: OrganizerRecord, date: Date) -> UNNotificationRequest {
        let content = UNMutableNotificationContent(); content.title = "Private old title"; content.body = "Private body"
        content.userInfo = ["reminderId": row.id, "reminderCreatedAt": row.int("createdAt"), "targetId": row.int("targetId"), "targetType": row.text("targetType"), "snoozeAt": date.timeIntervalSince1970]
        return UNNotificationRequest(identifier: "snooze.r.\(row.id)", content: content, trigger: ReminderService.once(date))
    }
    @MainActor func testMetadataRefreshKeepsSnoozeAndItsAbsoluteDeadline() async throws {
        let delivery = FakeNotificationDelivery(), service = ReminderService(delivery: delivery, birthdays: { [] }), snapshot = try fixture()
        let deadline = Date().addingTimeInterval(600), request = snooze(snapshot.records("reminders")[0], date: deadline)
        delivery.requests[request.identifier] = request
        service.update(snapshot, protected: false, language: "en"); await service.wait()
        let kept = try XCTUnwrap(delivery.requests[request.identifier])
        XCTAssertEqual(kept.content.userInfo["snoozeAt"] as? Double, deadline.timeIntervalSince1970)
        XCTAssertEqual(try XCTUnwrap((kept.trigger as? UNCalendarNotificationTrigger)?.nextTriggerDate()).timeIntervalSince1970, deadline.timeIntervalSince1970, accuracy: 1)
        XCTAssertFalse(delivery.removed.contains(request.identifier)); XCTAssertEqual(kept.content.title, "Organizer Pro")
    }
    @MainActor func testDeletedOrReusedReminderCannotKeepOldSnooze() async throws {
        let delivery = FakeNotificationDelivery(), service = ReminderService(delivery: delivery, birthdays: { [] }); var snapshot = try fixture()
        let request = snooze(snapshot.records("reminders")[0], date: Date().addingTimeInterval(600)); delivery.requests[request.identifier] = request
        var rows = snapshot.records("reminders"); rows[0].set("createdAt", Int64(1)); snapshot.setRecords("reminders", rows)
        service.update(snapshot, protected: false, language: "en"); await service.wait(); XCTAssertNil(delivery.requests[request.identifier])
    }
    @MainActor func testPINReplacesPendingContentAndClearsDeliveredContent() async throws {
        let delivery = FakeNotificationDelivery(), service = ReminderService(delivery: delivery, birthdays: { [] }), snapshot = try fixture()
        service.update(snapshot, protected: false, language: "en"); await service.wait()
        XCTAssertTrue(delivery.requests.values.contains { $0.content.title.contains("Заметка") })
        service.update(snapshot, protected: true, language: "en"); await service.wait()
        XCTAssertTrue(delivery.deliveredCleared)
        XCTAssertTrue(delivery.requests.values.allSatisfy { $0.content.title == "Organizer Pro" && $0.content.userInfo["protected"] as? Bool == true })
    }
    @MainActor func testAllAlertTypesShareOneBudget() async throws {
        let delivery = FakeNotificationDelivery(); var snapshot = try fixture(), rows = snapshot.records("reminders")
        rows[0].set("daysMask", Int64(127)); rows[0].set("baseMinutes", Int64(0)); rows[0].set("dayMode", "all_day"); snapshot.setRecords("reminders", rows)
        let request = snooze(rows[0], date: Date().addingTimeInterval(600)); delivery.requests[request.identifier] = request
        let birthday = CalendarAccess.Birthday(title: "Synthetic birthday", date: Date().addingTimeInterval(60))
        let service = ReminderService(delivery: delivery, birthdays: { [birthday] })
        service.update(snapshot, protected: false, language: "en"); await service.wait()
        XCTAssertEqual(delivery.requests.count, 64); XCTAssertEqual(service.deferred, 106)
        XCTAssertNotNil(delivery.requests[request.identifier]); XCTAssertNotNil(delivery.requests["birthday.0"])
    }
    @MainActor func testCancelledOldRefreshCannotOverwriteNewerContents() async throws {
        let delivery = FakeNotificationDelivery(); delivery.delay = true
        let service = ReminderService(delivery: delivery, birthdays: { [] }); var snapshot = try fixture()
        service.update(snapshot, protected: false, language: "en")
        var notes = snapshot.records("notes"); notes[0].set("title", "Newest title"); snapshot.setRecords("notes", notes)
        service.update(snapshot, protected: false, language: "en"); await service.wait()
        XCTAssertFalse(delivery.requests.isEmpty); XCTAssertTrue(delivery.requests.values.allSatisfy { $0.content.title == "Newest title" })
    }
    @MainActor func testDeniedPermissionRemovesStaleQueuedAlerts() async throws {
        let delivery = FakeNotificationDelivery(), service = ReminderService(delivery: delivery, birthdays: { [] }), snapshot = try fixture()
        service.update(snapshot, protected: false, language: "en"); await service.wait(); XCTAssertFalse(delivery.requests.isEmpty)
        delivery.authorization = .denied; service.update(snapshot, protected: true, language: "en"); await service.wait()
        XCTAssertTrue(service.denied); XCTAssertTrue(delivery.requests.isEmpty); XCTAssertTrue(delivery.deliveredCleared)
    }
    @MainActor func testSchedulingErrorDoesNotEraseOtherValidAlerts() async throws {
        let delivery = FakeNotificationDelivery(), service = ReminderService(delivery: delivery, birthdays: { [] }), snapshot = try fixture()
        service.update(snapshot, protected: false, language: "en"); await service.wait(); let before = Set(delivery.requests.keys)
        delivery.rejectedID = before.sorted().first; service.update(snapshot, protected: false, language: "en"); await service.wait()
        XCTAssertTrue(service.failed); XCTAssertEqual(Set(delivery.requests.keys), before)
    }
}
