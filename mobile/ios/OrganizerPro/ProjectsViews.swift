import SwiftUI
import OrganizerCore

struct ProjectsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var search = ""
    var body: some View {
        Page {
            TextField(model.t("Поиск проектов", "Search projects"), text: $search).textFieldStyle(.roundedBorder)
            if model.records("projects").isEmpty { EmptyCard(title: model.t("Проектов пока нет", "No projects yet"), symbol: "folder") }
            ForEach(model.records("projects").filter { search.isEmpty || ($0.text("name") + " " + $0.text("description") + " " + $0.text("client")).localizedCaseInsensitiveContains(search) }.sorted { $0.int("updatedAt") > $1.int("updatedAt") }) { project in
                NavigationLink(value: Route.project(project.id)) {
                    Card(color: project.text("colorKey", "blue")) {
                        UserText(project.text("name"), strong: true); UserText(project.text("description"), lines: 2)
                        HStack { Text(statusTitle(project.text("status"), model)); Spacer(); Text("\(project.int("progress"))%") }
                        ProgressView(value: min(100, max(0, Double(project.int("progress")))), total: 100).tint(Brand.blue)
                        if project.int("dueAt") > 0 { Label(model.date(project.int("dueAt")), systemImage: "calendar") }
                    }
                }.buttonStyle(.plain).accessibilityIdentifier("project-\(project.id)")
            }
        }
    }
}
@MainActor func statusTitle(_ key: String, _ model: AppModel) -> String {
    switch key { case "in_progress", "active": return model.t("В работе", "In progress"); case "paused": return model.t("Пауза", "Paused"); case "completed": return model.t("Завершён", "Completed"); default: return model.t("Запланирован", "Planned") }
}
struct ProjectDetail: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let id: Int64
    @State private var selected = 0
    @State private var deleting = false
    @State private var deleteEntry: (String, Int64)?
    var project: OrganizerRecord? { model.snapshot.record("projects", id: id) }
    var titles: [String] { [model.t("Обзор", "Overview"), model.t("Этапы", "Stages"), model.t("Финансы", "Finances"), model.t("Файлы", "Files"), model.t("Записи", "Journal")] }
    var body: some View {
        Group {
            if let project {
                VStack(spacing: 0) {
                    ScrollView(.horizontal) { HStack { ForEach(titles.indices, id: \.self) { i in Button(titles[i]) { selected = i }.buttonStyle(.borderedProminent).tint(selected == i ? Brand.blue : Brand.blue.opacity(0.2)).foregroundStyle(selected == i ? .white : .primary) } }.padding(10) }.scrollIndicators(.hidden)
                    if selected == 2 { FinanceView(projectID: id) }
                    else {
                        Page {
                            if selected == 0 { overview(project) }
                            else if selected == 1 { stages }
                            else if selected == 3 { FilesSection(targetType: "project", targetID: id) }
                            else { journal }
                        }
                    }
                }
            } else { EmptyCard(title: model.t("Проект недоступен", "Project unavailable"), symbol: "folder") }
        }.background(Brand.background(model.dark)).navigationTitle(model.t("Проект", "Project"))
            .toolbar { if let project { ToolbarItem(placement: .topBarTrailing) { Button(model.t("Изменить", "Edit")) { model.show(.project(project)) } } } }
            .confirmationDialog(model.t("Удалить проект? Финансовые записи сохранятся.", "Delete this project? Financial records will be kept."), isPresented: $deleting, titleVisibility: .visible) { Button(model.t("Удалить", "Delete"), role: .destructive) { model.remove("projects", id: id); if model.snapshot.record("projects", id: id) == nil { dismiss() } } }
            .confirmationDialog(model.t("Удалить запись?", "Delete this entry?"), isPresented: Binding(get: { deleteEntry != nil }, set: { if !$0 { deleteEntry = nil } }), titleVisibility: .visible) { Button(model.t("Удалить", "Delete"), role: .destructive) { if let (table, id) = deleteEntry { model.remove(table, id: id) }; deleteEntry = nil } }
    }
    @ViewBuilder func overview(_ p: OrganizerRecord) -> some View {
        Card(color: p.text("colorKey", "blue")) {
            UserText(p.text("name"), strong: true); UserText(p.text("description")).textSelection(.enabled)
            LabeledContent(model.t("Статус", "Status"), value: statusTitle(p.text("status"), model))
            LabeledContent(model.t("Прогресс", "Progress"), value: "\(p.int("progress"))%")
            ProgressView(value: min(100, max(0, Double(p.int("progress")))), total: 100)
            LabeledContent(model.t("Бюджет", "Budget"), value: model.amount(p.number("budget")))
            LabeledContent(model.t("Начало", "Start"), value: model.date(p.int("startAt")))
            LabeledContent(model.t("Срок", "Due"), value: model.date(p.int("dueAt")))
            forField(model.t("Клиент", "Client"), p.text("client")); forField(model.t("Адрес", "Address"), p.text("address")); forField(model.t("Контакт", "Contact"), p.text("contact"))
        }
        Button { model.show(.reminder("project", id, nil)) } label: { Label(model.t("Напомнить о проекте", "Project reminder"), systemImage: "bell") }.buttonStyle(Pill())
        Button(model.t("Удалить проект", "Delete project"), role: .destructive) { deleting = true }.padding(.top)
    }
    @ViewBuilder func forField(_ name: String, _ value: String) -> some View { if !value.isEmpty { VStack(alignment: .leading) { Text(name).foregroundStyle(.secondary); UserText(value).textSelection(.enabled) } } }
    var stages: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(model.t("Добавить этап", "Add stage")) { model.show(.stage(id)) }.buttonStyle(Pill())
            ForEach(model.records("projectStages").filter { $0.int("projectId") == id }) { stage in
                Card {
                    HStack(alignment: .top) {
                        Button { var r = stage; r.set("done", !stage.flag("done")); _ = model.save("projectStages", record: r) } label: { Image(systemName: stage.flag("done") ? "checkmark.circle.fill" : "circle").font(.title2).frame(minWidth: 44, minHeight: 44) }.accessibilityLabel(model.t("Этап завершён", "Stage completed"))
                        VStack(alignment: .leading) { UserText(stage.text("title"), strong: true); Text(model.date(stage.int("dueAt"))).font(.caption) }
                        Spacer(); Button(role: .destructive) { deleteEntry = ("projectStages", stage.id) } label: { Image(systemName: "trash").frame(minWidth: 44, minHeight: 44) }.accessibilityLabel(model.t("Удалить этап", "Delete stage"))
                    }
                }
            }
        }
    }
    var journal: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(model.t("Добавить запись", "Add journal entry")) { model.show(.journal(id)) }.buttonStyle(Pill())
            ForEach(model.records("projectJournal").filter { $0.int("projectId") == id }.sorted { $0.int("createdAt") > $1.int("createdAt") }) { entry in
                Card { UserText(entry.text("text")); HStack { Text(model.date(entry.int("createdAt"), time: true)).font(.caption); Spacer(); Button(role: .destructive) { deleteEntry = ("projectJournal", entry.id) } label: { Image(systemName: "trash").frame(minWidth: 44, minHeight: 44) }.accessibilityLabel(model.t("Удалить запись", "Delete entry")) } }
            }
            Text(model.t("Связанные заметки", "Linked notes")).font(.headline).padding(.top)
            Button(model.t("Выбрать заметки", "Choose notes")) { model.show(.link(id)) }.buttonStyle(.bordered)
            ForEach(model.records("notes").filter { note in model.records("projectNoteLinks").contains { $0.int("projectId") == id && $0.int("noteId") == note.id } }) { note in
                NavigationLink(value: Route.note(note.id)) { Card { UserText(note.text("title"), strong: true); UserText(note.text("body"), lines: 2) } }.buttonStyle(.plain)
            }
        }
    }
}
struct ProjectEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let original: OrganizerRecord?
    @State private var record = OrganizerRecord()
    @State private var budget = "0"
    @State private var due = false
    @State private var loaded = false
    var key: String { "project:\(original?.id.description ?? "new")" }
    func text(_ key: String) -> Binding<String> { Binding(get: { record.text(key) }, set: { record.set(key, $0) }) }
    func date(_ key: String) -> Binding<Date> { Binding(get: { Date(milliseconds: record.int(key, Snapshot.now)) }, set: { record.set(key, $0.milliseconds) }) }
    var body: some View {
        Form {
            Section(model.t("Проект", "Project")) {
                TextField(model.t("Название", "Name"), text: text("name"), axis: .vertical).accessibilityIdentifier("projectName")
                TextField(model.t("Описание", "Description"), text: text("description"), axis: .vertical).lineLimit(3...10)
                ColorPickerRow(selected: text("colorKey"))
                Picker(model.t("Статус", "Status"), selection: text("status")) {
                    ForEach(["planned", "in_progress", "paused", "completed"], id: \.self) { Text(statusTitle($0, model)).tag($0) }
                    if !["planned", "in_progress", "paused", "completed"].contains(record.text("status")) { Text(statusTitle(record.text("status"), model)).tag(record.text("status")) }
                }
                LabeledContent(model.t("Прогресс", "Progress"), value: "\(record.int("progress"))%")
                Slider(value: Binding(get: { Double(record.int("progress")) }, set: { record.set("progress", Int64($0)) }), in: 0...100, step: 1)
            }
            Section(model.t("Даты и бюджет", "Dates and budget")) {
                DatePicker(model.t("Начало", "Start"), selection: date("startAt"), displayedComponents: .date)
                Toggle(model.t("Указать срок", "Set a due date"), isOn: $due)
                if due { DatePicker(model.t("Срок", "Due"), selection: date("dueAt"), displayedComponents: .date) }
                TextField(model.t("Бюджет", "Budget"), text: $budget).keyboardType(.decimalPad).accessibilityIdentifier("projectBudget")
            }
            Section(model.t("Контакты", "Contacts")) { TextField(model.t("Клиент", "Client"), text: text("client")); TextField(model.t("Адрес", "Address"), text: text("address"), axis: .vertical); TextField(model.t("Контакт", "Contact"), text: text("contact"), axis: .vertical) }
        }.navigationTitle(model.t(original == nil ? "Новый проект" : "Изменить проект", original == nil ? "New project" : "Edit project"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(model.t("Закрыть", "Close")) { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button(model.t("Сохранить", "Save")) { save() }.disabled(record.text("name").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || Money.parse(budget, allowZero: true) == nil).accessibilityIdentifier("saveProject") } }
            .onAppear {
                guard !loaded else { return }
                record = model.draft(key) ?? original ?? OrganizerRecord(["name": .string(""), "description": .string(""), "status": .string("planned"), "progress": .integer(0), "startAt": .integer(Snapshot.now), "dueAt": .integer(0), "budget": .number(0), "client": .string(""), "address": .string(""), "contact": .string(""), "colorKey": .string("blue")])
                budget = record.fields["_budgetText"]?.text ?? String(record.number("budget")); due = record.flag("_due", record.int("dueAt") > 0); loaded = true
            }
            .onChange(of: record) { _, _ in persist() }.onChange(of: budget) { _, _ in persist() }.onChange(of: due) { _, _ in persist() }
    }
    func persist() { guard loaded else { return }; var draft = record; draft.set("_budgetText", budget); draft.set("_due", due); model.draft(key, record: draft) }
    func save() {
        guard let value = Money.parse(budget, allowZero: true) else { return }
        var r = record; r.fields.removeValue(forKey: "_budgetText"); r.fields.removeValue(forKey: "_due"); r.set("budget", value); if !due { r.set("dueAt", Int64(0)) } else if r.int("dueAt") == 0 { r.set("dueAt", Snapshot.now) }
        if model.save("projects", record: r) != nil { loaded = false; model.draft(key, record: nil); dismiss() }
    }
}
struct ProjectEntryEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let projectID: Int64; let kind: String
    @State private var text = ""
    @State private var due = false
    @State private var date = Date()
    @State private var loaded = false
    var key: String { "\(kind):\(projectID):new" }
    var body: some View {
        Form { TextField(model.t(kind == "projectStages" ? "Название этапа" : "Запись", kind == "projectStages" ? "Stage title" : "Entry"), text: $text, axis: .vertical).lineLimit(4...15)
            if kind == "projectStages" { Toggle(model.t("Указать срок", "Set a due date"), isOn: $due); if due { DatePicker(model.t("Срок", "Due"), selection: $date, displayedComponents: .date) } }
        }.navigationTitle(model.t(kind == "projectStages" ? "Новый этап" : "Журнал проекта", kind == "projectStages" ? "New stage" : "Project journal"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(model.t("Закрыть", "Close")) { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button(model.t("Сохранить", "Save")) {
                guard model.snapshot.record("projects", id: projectID) != nil else { model.report(OrganizerError.storage("missing project")); return }
                var r = OrganizerRecord(["projectId": .integer(projectID)])
                if kind == "projectStages" { r.set("title", text); r.set("done", false); r.set("dueAt", due ? date.milliseconds : 0) } else { r.set("text", text) }
                if model.save(kind, record: r) != nil { model.draft(key, record: nil); loaded = false; dismiss() }
            }.disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) } }
            .onAppear { guard !loaded else { return }; if let draft = model.draft(key) { text = draft.text("text"); due = draft.flag("due"); date = Date(milliseconds: draft.int("date", Snapshot.now)) }; loaded = true }
            .onChange(of: text) { _, _ in persist() }.onChange(of: due) { _, _ in persist() }.onChange(of: date) { _, _ in persist() }
    }
    func persist() { if loaded { model.draft(key, record: OrganizerRecord(["text": .string(text), "due": .bool(due), "date": .integer(date.milliseconds)])) } }
}
struct LinkNotesView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let projectID: Int64
    var body: some View {
        List {
            ForEach(model.records("notes")) { note in
                let linked = model.records("projectNoteLinks").contains { $0.int("projectId") == projectID && $0.int("noteId") == note.id }
                Button {
                    model.mutate { state in
                        var links = state.records("projectNoteLinks").filter { !($0.int("projectId") == projectID && $0.int("noteId") == note.id) }
                        if !linked { links.append(OrganizerRecord(["projectId": .integer(projectID), "noteId": .integer(note.id)])) }; state.setRecords("projectNoteLinks", links)
                    }
                } label: { HStack { UserText(note.text("title")); Spacer(); if linked { Image(systemName: "checkmark") } } }
            }
        }.navigationTitle(model.t("Связанные заметки", "Linked notes")).toolbar { ToolbarItem(placement: .confirmationAction) { Button(model.t("Готово", "Done")) { dismiss() } } }
    }
}
