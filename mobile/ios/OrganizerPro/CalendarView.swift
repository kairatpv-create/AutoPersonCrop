import SwiftUI
import EventKit
import OrganizerCore

@MainActor enum CalendarAccess {
    static let store = EKEventStore()
    static var allowed: Bool { EKEventStore.authorizationStatus(for: .event) == .fullAccess }
    static func request() async throws -> Bool { try await store.requestFullAccessToEvents() }
    static func events(_ start: Date, _ end: Date) -> [EKEvent] {
        guard allowed else { return [] }
        return store.events(matching: store.predicateForEvents(withStart: start, end: end, calendars: nil)).sorted { $0.startDate < $1.startDate }
    }
    struct Birthday { let title: String; let date: Date }
    static func birthdays() -> [Birthday] {
        guard allowed else { return [] }
        let now = Date(), calendar = Calendar.current
        guard let end = calendar.date(byAdding: .day, value: 370, to: now) else { return [] }
        var seen = Set<String>()
        return events(now, end).filter { $0.calendar.type == .birthday }.compactMap { event in
            let key = (event.eventIdentifier ?? event.title ?? "") + "-" + event.startDate.description
            guard seen.insert(key).inserted, let day = calendar.date(byAdding: .day, value: -1, to: event.startDate), let alert = calendar.date(bySettingHour: 9, minute: 0, second: 0, of: day), alert > now else { return nil }
            return Birthday(title: event.title ?? "", date: alert)
        }
    }
}
struct CalendarView: View {
    @EnvironmentObject private var model: AppModel
    @State private var date = Date()
    @State private var system: [EKEvent] = []
    @State private var granted = CalendarAccess.allowed
    @State private var denied = false
    var calendar: Calendar { Calendar.current }
    var projects: [Record] { model.records("projects").filter { $0.int("dueAt") > 0 && calendar.isDate(Date(milliseconds: $0.int("dueAt")), inSameDayAs: date) } }
    var stages: [Record] { model.records("projectStages").filter { $0.int("dueAt") > 0 && calendar.isDate(Date(milliseconds: $0.int("dueAt")), inSameDayAs: date) } }
    var body: some View {
        Page {
            DatePicker(model.t("Дата", "Date"), selection: $date, displayedComponents: .date).datePickerStyle(.graphical).padding(8).background(Brand.card(model.dark), in: RoundedRectangle(cornerRadius: 16))
            if !granted {
                Card(color: "yellow") {
                    Text(model.t("Сроки проектов видны без разрешений. Подключите календарь iPhone, чтобы видеть события и дни рождения и получать напоминания о днях рождения накануне в 09:00.", "Project due dates work without permission. Connect your iPhone calendars to see events and birthdays and get birthday reminders at 09:00 the day before.")).font(.caption)
                    Button(model.t("Показать календари iPhone", "Show iPhone calendars")) { Task { do { granted = try await CalendarAccess.request(); denied = !granted; reload(); if granted { _ = await model.reminders.requestPermission(); model.reschedule() } } catch { denied = true } } }.buttonStyle(.bordered)
                    if denied { Button(model.t("Разрешения в Настройках", "Permissions in Settings")) { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } } }
                }
            }
            ForEach(projects) { p in NavigationLink(value: Route.project(p.id)) { Card(color: p.text("colorKey", "blue")) { Label(model.t("Срок проекта", "Project due"), systemImage: "folder"); UserText(p.text("name"), strong: true) } }.buttonStyle(.plain) }
            ForEach(stages) { s in NavigationLink(value: Route.project(s.int("projectId"))) { Card(color: "yellow") { Label(model.t("Срок этапа", "Stage due"), systemImage: "checklist"); UserText(s.text("title"), strong: true); UserText(model.snapshot.record("projects", id: s.int("projectId"))?.text("name") ?? "") } }.buttonStyle(.plain) }
            ForEach(Array(system.enumerated()), id: \.offset) { _, event in
                Card(color: event.calendar.type == .birthday ? "pink" : "cyan") {
                    Label(event.calendar.type == .birthday ? model.t("День рождения", "Birthday") : model.t("Событие календаря", "Calendar event"), systemImage: event.calendar.type == .birthday ? "gift" : "calendar")
                    UserText(event.title ?? "", strong: true)
                    if !event.isAllDay { Text(model.date(event.startDate.milliseconds, time: true)) }
                    if let location = event.location, !location.isEmpty { UserText(location) }
                    if let notes = event.notes, !notes.isEmpty { UserText(notes) }
                }
            }
            if projects.isEmpty && stages.isEmpty && system.isEmpty { Text(model.t("На этот день событий нет", "No events on this day")).foregroundStyle(.secondary).frame(maxWidth: .infinity).padding() }
        }.navigationTitle(model.t("Календарь", "Calendar")).background(Brand.background(model.dark)).onAppear { reload() }.onChange(of: date) { _, _ in reload() }.onReceive(NotificationCenter.default.publisher(for: .EKEventStoreChanged)) { _ in reload(); model.reschedule() }
    }
    func reload() { granted = CalendarAccess.allowed; let start = calendar.startOfDay(for: date); system = CalendarAccess.events(start, calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86400)) }
}
