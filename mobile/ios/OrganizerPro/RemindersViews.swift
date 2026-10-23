import SwiftUI
import OrganizerCore

struct RemindersView: View {
    @EnvironmentObject private var model: AppModel
    @State private var deleting: OrganizerRecord?
    @StateObject private var service = ReminderService.shared
    var body: some View {
        Page {
            Card(color: "yellow") {
                Text(model.t("Уведомления iPhone", "iPhone notifications")).font(.headline)
                Text(model.t("Звук и вибрация зависят от настроек уведомлений iOS. Приложение планирует до 64 ближайших сигналов. Для повторов каждый час и большого числа напоминаний периодически открывайте приложение. Фоновое обновление зависит от решения системы.", "Sound and vibration follow iOS notification settings. The app schedules up to 64 upcoming alerts. For hourly repeats and many reminders, open the app periodically. Background refresh is controlled by the system.")).font(.caption)
                Button(model.t("Разрешить уведомления", "Allow notifications")) { Task { _ = await service.requestPermission(); model.reschedule() } }.buttonStyle(.bordered)
                if service.denied { Button(model.t("Настройки уведомлений", "Notification settings")) { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }.buttonStyle(.bordered) }
                if service.deferred > 0 { Text(model.t("В очереди: \(service.deferred) сигналов. Ближайшие запланированы.", "\(service.deferred) alerts are waiting. The nearest alerts are scheduled.")).font(.caption) }
                if service.invalid > 0 || service.failed { Text(model.t("Некоторые напоминания не удалось запланировать. Проверьте дату, дни недели и разрешения.", "Some reminders could not be scheduled. Check dates, weekdays and permissions.")).foregroundStyle(.red).font(.caption) }
            }
            if model.records("reminders").isEmpty { EmptyCard(title: model.t("Добавьте напоминание в заметке или проекте", "Add a reminder from a note or project"), symbol: "bell") }
            ForEach(model.records("reminders").sorted { $0.int("triggerAt") < $1.int("triggerAt") }) { r in
                Card {
                    let target = model.snapshot.record(r.text("targetType") == "note" ? "notes" : "projects", id: r.int("targetId"))
                    UserText(target?.text(r.text("targetType") == "note" ? "title" : "name") ?? model.t("Объект недоступен", "Item unavailable"), strong: true)
                    Text(model.date(r.int("triggerAt"), time: true))
                    Text(r.text("scheduleType") == "weekly" ? model.t("По дням недели", "Weekly") : model.t("Однократно", "Once"))
                    if r.text("dayMode") == "all_day" { Text(model.t("Каждый час до конца дня", "Every hour until the end of the day")).font(.caption) }
                    HStack { Button(model.t("Изменить", "Edit")) { model.show(.reminder(r.text("targetType"), r.int("targetId"), r)) }; Spacer(); Button(model.t("Удалить", "Delete"), role: .destructive) { deleting = r } }
                }
            }
        }.navigationTitle(model.t("Напоминания", "Reminders")).background(Brand.background(model.dark))
            .confirmationDialog(model.t("Удалить напоминание?", "Delete this reminder?"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) { Button(model.t("Удалить", "Delete"), role: .destructive) { if let deleting { model.remove("reminders", id: deleting.id) }; deleting = nil } }
    }
}
struct ReminderEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let targetType: String; let targetID: Int64; let original: OrganizerRecord?
    @State private var date = Date().addingTimeInterval(3600)
    @State private var weekly = false
    @State private var mask = 0
    @State private var hourly = false
    @State private var sound = true
    @State private var loaded = false
    var weekdays: [String] { model.lang == "ru" ? ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"] : ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] }
    var body: some View {
        Form {
            Toggle(model.t("По дням недели", "Repeat weekly"), isOn: $weekly)
            if weekly {
                ForEach(0..<7, id: \.self) { i in Toggle(weekdays[i], isOn: Binding(get: { mask & (1 << i) != 0 }, set: { if $0 { mask |= 1 << i } else { mask &= ~(1 << i) } })) }
                DatePicker(model.t("Время", "Time"), selection: $date, displayedComponents: .hourAndMinute)
            } else { DatePicker(model.t("Дата и время", "Date and time"), selection: $date, in: Date()..., displayedComponents: [.date, .hourAndMinute]) }
            Toggle(model.t("Повторять каждый час до конца дня", "Repeat hourly until end of day"), isOn: $hourly)
            Toggle(model.t("Звук", "Sound"), isOn: $sound)
            Text(model.t("После сохранения разрешите уведомления, если iOS спросит. Вибрация и вид сигнала задаются в настройках iPhone.", "Allow notifications after saving if iOS asks. iPhone settings control vibration and alert appearance.")).font(.caption)
        }.navigationTitle(model.t("Напоминание", "Reminder"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(model.t("Отмена", "Cancel")) { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button(model.t("Сохранить", "Save")) { save() }.disabled(weekly ? mask == 0 : date <= Date()) } }
            .onAppear {
                guard !loaded else { return }; loaded = true
                if let r = original { weekly = r.text("scheduleType") == "weekly"; mask = Int(r.int("daysMask")); hourly = r.text("dayMode") == "all_day"; sound = r.flag("sound", true); date = Date(milliseconds: r.int("triggerAt")); if weekly { date = Calendar.current.date(bySettingHour: Int(r.int("baseMinutes", 540)) / 60, minute: Int(r.int("baseMinutes", 540)) % 60, second: 0, of: Date()) ?? Date() } }
            }
    }
    func save() {
        guard model.snapshot.record(targetType == "note" ? "notes" : "projects", id: targetID) != nil else { model.report(OrganizerError.storage("missing reminder owner")); return }
        let c = Calendar.current, minutes = c.component(.hour, from: date) * 60 + c.component(.minute, from: date)
        var r = original ?? OrganizerRecord(); r.set("targetType", targetType); r.set("targetId", targetID); r.set("triggerAt", date.milliseconds); r.set("sound", sound); r.set("vibrate", original?.flag("vibrate", true) ?? true); r.set("soundKey", original?.text("soundKey", "system") ?? "system"); r.set("scheduleType", weekly ? "weekly" : "once"); r.set("daysMask", Int64(mask)); r.set("dayMode", hourly ? "all_day" : "single"); r.set("baseMinutes", Int64(minutes))
        if weekly, let next = ReminderPlan.make([r]).requests.first?.next { r.set("triggerAt", next.milliseconds) }
        if model.save("reminders", record: r) != nil { Task { _ = await model.reminders.requestPermission(); model.reschedule() }; dismiss() }
    }
}
