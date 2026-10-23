import SwiftUI
import OrganizerCore

struct NotesView: View {
    @EnvironmentObject private var model: AppModel
    @State private var search = ""
    var notes: [Record] { model.records("notes").filter { search.isEmpty || ($0.text("title") + " " + $0.text("body")).localizedCaseInsensitiveContains(search) }.sorted { $0.int("updatedAt") > $1.int("updatedAt") } }
    var body: some View {
        Page {
            TextField(model.t("Поиск по заметкам", "Search notes"), text: $search).textFieldStyle(.roundedBorder).accessibilityIdentifier("noteSearch")
            if notes.isEmpty { EmptyCard(title: model.t("Пока нет заметок", "No notes yet"), symbol: "note.text") }
            ForEach(notes) { note in
                NavigationLink(value: Route.note(note.id)) {
                    Card(color: note.text("colorKey", "blue")) {
                        UserText(note.text("title"), strong: true)
                        if !note.text("body").isEmpty { UserText(note.text("body"), lines: 3) }
                        Text(model.date(note.int("updatedAt"), time: true)).font(.caption).foregroundStyle(.secondary)
                    }
                }.buttonStyle(.plain).accessibilityIdentifier("note-\(note.id)")
            }
        }
    }
}
struct NoteDetail: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let id: Int64
    @State private var deleting = false
    var note: Record? { model.snapshot.record("notes", id: id) }
    var body: some View {
        Group {
            if let note {
                Page {
                    Card(color: note.text("colorKey", "blue")) { UserText(note.text("title"), strong: true); UserText(note.text("body")).textSelection(.enabled); Text(model.date(note.int("updatedAt"), time: true)).font(.caption).foregroundStyle(.secondary) }
                    HStack {
                        Button(model.t("Редактировать", "Edit")) { model.show(.note(note)) }.buttonStyle(Pill())
                        Button { model.show(.reminder("note", id, nil)) } label: { Label(model.t("Напомнить", "Remind"), systemImage: "bell") }.buttonStyle(.bordered)
                    }
                    FilesSection(targetType: "note", targetID: id)
                    Button(model.t("Удалить заметку", "Delete note"), role: .destructive) { deleting = true }.padding(.top)
                }
            } else { EmptyCard(title: model.t("Заметка недоступна", "Note unavailable"), symbol: "note.text") }
        }.background(Brand.background(model.dark)).navigationTitle(model.t("Заметка", "Note"))
            .confirmationDialog(model.t("Удалить заметку и её вложения?", "Delete this note and its attachments?"), isPresented: $deleting, titleVisibility: .visible) { Button(model.t("Удалить", "Delete"), role: .destructive) { model.remove("notes", id: id); if model.snapshot.record("notes", id: id) == nil { dismiss() } } }
    }
}
struct NoteEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var speech = SpeechInput()
    let original: Record?
    @State private var title = ""
    @State private var text = ""
    @State private var color = "blue"
    @State private var loaded = false
    @State private var speechBase = ""
    @ScaledMetric(relativeTo: .body) private var point = 17
    var key: String { "note:\(original?.id.description ?? "new")" }
    var body: some View {
        Form {
            Section {
                TextField(model.t("Заголовок", "Title"), text: $title, axis: .vertical).accessibilityIdentifier("noteTitle").font(.system(size: point * model.contentScale, weight: .semibold))
                TextEditor(text: $text).frame(minHeight: 230).font(.system(size: point * model.contentScale, weight: model.bold ? .semibold : .regular)).disabled(speech.listening).accessibilityIdentifier("noteBody")
                ColorPickerRow(selected: $color)
            }
            Section {
                Button { if speech.listening { speech.stop() } else { speechBase = text; speech.start(language: model.lang) } } label: { Label(speech.listening ? model.t("Остановить диктовку", "Stop dictation") : model.t("Диктовать", "Dictate"), systemImage: speech.listening ? "stop.circle" : "mic") }
                Text(model.t("Распознавание речи использует службы Apple и может требовать интернет. Текст сохраняется только после вашего подтверждения.", "Speech recognition uses Apple services and may need an internet connection. Save the text when you are ready.")).font(.caption).foregroundStyle(.secondary)
                if let error = speech.error { Text(model.t("Диктовка недоступна. Проверьте разрешения на микрофон и распознавание речи в Настройках iPhone.", "Dictation is unavailable. Check microphone and speech permissions in iPhone Settings.") + " " + error).font(.caption) }
            }
        }.navigationTitle(model.t(original == nil ? "Новая заметка" : "Редактировать", original == nil ? "New note" : "Edit note"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(model.t("Закрыть", "Close")) { speech.stop(); persist(); dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button(model.t("Сохранить", "Save")) { save() }.disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty).accessibilityIdentifier("saveNote") }
            }
            .onAppear {
                guard !loaded else { return }; loaded = true
                let draft = model.draft(key)
                title = draft?.text("title") ?? original?.text("title") ?? ""; text = draft?.text("body") ?? original?.text("body") ?? ""; color = draft?.text("colorKey") ?? original?.text("colorKey", "blue") ?? "blue"
            }
            .onChange(of: title) { _, _ in persist() }.onChange(of: text) { _, _ in persist() }.onChange(of: color) { _, _ in persist() }
            .onChange(of: speech.transcript) { _, value in text = speechBase + (speechBase.isEmpty ? "" : "\n") + value }
            .onDisappear { speech.stop() }
    }
    func persist() { if loaded { model.draft(key, record: Record(["title": .string(title), "body": .string(text), "colorKey": .string(color)])) } }
    func save() {
        speech.stop(); var record = original ?? Record(); record.set("title", title.trimmingCharacters(in: .whitespacesAndNewlines)); record.set("body", text); record.set("colorKey", color)
        if model.save("notes", record: record) != nil { loaded = false; model.draft(key, record: nil); dismiss() }
    }
}
struct TasksView: View {
    @EnvironmentObject private var model: AppModel
    @State private var deleting: Record?
    var body: some View {
        Page {
            Button(model.t("Новая задача", "New task")) { model.show(.task(nil)) }.buttonStyle(Pill())
            if model.records("tasks").isEmpty { EmptyCard(title: model.t("Задач пока нет", "No tasks yet"), symbol: "checklist") }
            ForEach(model.records("tasks").sorted { a, b in a.flag("done") == b.flag("done") ? a.int("updatedAt") > b.int("updatedAt") : !a.flag("done") }) { task in
                Card {
                    HStack(alignment: .top) {
                        Button { var r = task; r.set("done", !task.flag("done")); _ = model.save("tasks", record: r) } label: { Image(systemName: task.flag("done") ? "checkmark.circle.fill" : "circle").font(.title2).frame(minWidth: 44, minHeight: 44) }.accessibilityLabel(model.t("Готово", "Complete"))
                        VStack(alignment: .leading) { UserText(task.text("title"), strong: true).strikethrough(task.flag("done")); UserText(task.text("details")) }
                        Spacer(minLength: 0)
                        Menu { Button(model.t("Изменить", "Edit")) { model.show(.task(task)) }; Button(model.t("Удалить", "Delete"), role: .destructive) { deleting = task } } label: { Image(systemName: "ellipsis").frame(minWidth: 44, minHeight: 44) }
                    }
                }
            }
        }.navigationTitle(model.t("Задачи", "Tasks")).background(Brand.background(model.dark))
            .confirmationDialog(model.t("Удалить задачу?", "Delete this task?"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) { Button(model.t("Удалить", "Delete"), role: .destructive) { if let deleting { model.remove("tasks", id: deleting.id) }; deleting = nil } }
    }
}
struct TaskEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let original: Record?
    @State private var title = ""
    @State private var details = ""
    @State private var loaded = false
    var key: String { "task:\(original?.id.description ?? "new")" }
    var body: some View {
        Form { TextField(model.t("Заголовок", "Title"), text: $title, axis: .vertical); TextField(model.t("Описание", "Details"), text: $details, axis: .vertical).lineLimit(4...12) }
            .navigationTitle(model.t("Задача", "Task"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(model.t("Закрыть", "Close")) { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button(model.t("Сохранить", "Save")) { var r = original ?? Record(["done": .bool(false)]); r.set("title", title); r.set("details", details); if model.save("tasks", record: r) != nil { loaded = false; model.draft(key, record: nil); dismiss() } }.disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) } }
            .onAppear { guard !loaded else { return }; let r = model.draft(key) ?? original; title = r?.text("title") ?? ""; details = r?.text("details") ?? ""; loaded = true }
            .onChange(of: title) { _, _ in persist() }.onChange(of: details) { _, _ in persist() }
    }
    func persist() { if loaded { model.draft(key, record: Record(["title": .string(title), "details": .string(details)])) } }
}
