import Foundation

public struct PlannedReminder: Identifiable, Sendable {
    public let id: String
    public let record: Record
    public let components: DateComponents
    public let repeats: Bool
    public let next: Date
}
public struct ReminderPlan: Sendable {
    public let requests: [PlannedReminder]
    public let deferred: Int
    public let invalid: Int
    public static func make(_ records: [Record], now: Date = Date(), calendar: Calendar = .current, limit: Int = 64) -> ReminderPlan {
        let budget = min(1024, max(0, limit))
        var requests: [PlannedReminder] = [], invalid = 0, total = 0
        func ordered(_ a: PlannedReminder, _ b: PlannedReminder) -> Bool { a.next == b.next ? a.id < b.id : a.next < b.next }
        func add(_ request: PlannedReminder) {
            total += 1
            guard budget > 0 else { return }
            requests.append(request)
            if requests.count > budget * 2 + 8 { requests.sort(by: ordered); requests = Array(requests.prefix(budget)) }
        }
        for row in records {
            let weekly = row.text("scheduleType", "once") == "weekly", hourly = row.text("dayMode", "single") == "all_day"
            if weekly {
                let minutes = Int(row.int("baseMinutes", 540)), mask = Int(row.int("daysMask")) & 127
                guard (0..<1440).contains(minutes), mask != 0 else { invalid += 1; continue }
                for index in 0..<7 where mask & (1 << index) != 0 {
                    for hour in (minutes / 60)...(hourly ? 23 : minutes / 60) {
                        var components = DateComponents(); components.weekday = (index + 1) % 7 + 1; components.hour = hour; components.minute = minutes % 60; components.second = 0
                        if let date = calendar.nextDate(after: now, matching: components, matchingPolicy: .nextTime, repeatedTimePolicy: .first) {
                            add(PlannedReminder(id: "r.\(row.id).w.\(index).\(hour)", record: row, components: components, repeats: true, next: date))
                        }
                    }
                }
            } else if row.text("scheduleType", "once") == "once" {
                let start = Date(timeIntervalSince1970: Double(row.int("triggerAt")) / 1000)
                var date = start
                for index in 0..<(hourly ? 25 : 1) {
                    guard calendar.isDate(date, inSameDayAs: start) else { break }
                    if date > now {
                        let components = calendar.dateComponents([.calendar, .timeZone, .year, .month, .day, .hour, .minute, .second], from: date)
                        add(PlannedReminder(id: "r.\(row.id).o.\(index)", record: row, components: components, repeats: false, next: date))
                    }
                    guard let next = calendar.date(byAdding: .hour, value: 1, to: date) else { break }; date = next
                }
            } else { invalid += 1 }
        }
        requests.sort(by: ordered)
        return ReminderPlan(requests: Array(requests.prefix(budget)), deferred: max(0, total - budget), invalid: invalid)
    }
}
