import XCTest
@testable import OrganizerCore

final class ReminderTests: XCTestCase {
    func calendar(_ zone: String = "UTC") -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: zone)!; return c }
    func date(_ string: String) -> Date { ISO8601DateFormatter().date(from: string)! }
    func row(_ fields: [String: JSONValue] = [:]) -> Record {
        var values: [String: JSONValue] = ["id": .integer(1), "scheduleType": .string("weekly"), "daysMask": .integer(1), "baseMinutes": .integer(570)]
        values.merge(fields) { _, new in new }; return Record(values)
    }
    func testMondayBitAndNextWeek() {
        let c = calendar(), now = date("2026-10-26T10:00:00Z")
        let plan = ReminderPlan.make([row()], now: now, calendar: c)
        XCTAssertEqual(plan.requests.first?.next, date("2026-11-02T09:30:00Z")); XCTAssertTrue(plan.requests[0].repeats)
    }
    func testSundayBit() {
        let plan = ReminderPlan.make([row(["daysMask": .integer(64)])], now: date("2026-10-26T10:00:00Z"), calendar: calendar())
        XCTAssertEqual(plan.requests.first?.next, date("2026-11-01T09:30:00Z"))
    }
    func testHourlyEndsAtMidnight() {
        let r = row(["scheduleType": .string("once"), "dayMode": .string("all_day"), "triggerAt": .integer(Int64(date("2026-10-26T21:30:00Z").timeIntervalSince1970 * 1000))])
        let plan = ReminderPlan.make([r], now: date("2026-10-26T21:00:00Z"), calendar: calendar())
        XCTAssertEqual(plan.requests.count, 3); XCTAssertEqual(plan.requests.last?.next, date("2026-10-26T23:30:00Z"))
    }
    func testIOSBudgetDoesNotDeleteDefinitions() {
        let source = [row(["daysMask": .integer(127), "baseMinutes": .integer(0), "dayMode": .string("all_day")])]
        let plan = ReminderPlan.make(source, now: date("2026-10-26T10:00:00Z"), calendar: calendar())
        XCTAssertEqual(plan.requests.count, 64); XCTAssertEqual(plan.deferred, 104); XCTAssertEqual(source.count, 1)
    }
    func testDSTKeepsLocalTime() {
        let c = calendar("America/New_York"), now = date("2026-10-31T12:00:00Z")
        let plan = ReminderPlan.make([row(["daysMask": .integer(64)])], now: now, calendar: c)
        XCTAssertEqual(c.component(.hour, from: plan.requests[0].next), 9)
        XCTAssertEqual(c.component(.minute, from: plan.requests[0].next), 30)
    }
    func testPastOnceDoesNotFireImmediatelyAndInvalidIsVisible() {
        let once = row(["scheduleType": .string("once"), "triggerAt": .integer(1)])
        let invalid = row(["daysMask": .integer(0)])
        let plan = ReminderPlan.make([once, invalid]); XCTAssertTrue(plan.requests.isEmpty); XCTAssertEqual(plan.invalid, 1)
    }
}
