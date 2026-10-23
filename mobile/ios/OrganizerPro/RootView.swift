import SwiftUI
import OrganizerCore

enum Route: Hashable { case note(Int64), project(Int64), tasks, reminders, calendar, privacy, safety }
enum MainTab: String, CaseIterable { case notes, projects, money, settings }
struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var tab = MainTab.notes
    @State private var path: [Route] = []
    @ScaledMetric(relativeTo: .body) private var point = 15
    var tabs: [MainTab] { MainTab.allCases.filter { $0 != .projects || model.snapshot.settings.flag("projectsEnabled", true) } }
    var body: some View {
        Group {
            if !model.ready { ContentUnavailableView(model.t("Данные недоступны", "Data unavailable"), systemImage: "lock.doc", description: Text(model.fatal ?? "")) }
            else {
                NavigationStack(path: $path) {
                    VStack(spacing: 0) {
                        if tab != .settings { header }
                        Group {
                            switch tab {
                            case .notes: NotesView()
                            case .projects: ProjectsView()
                            case .money: FinanceView()
                            case .settings: SettingsView()
                            }
                        }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    }.background(Brand.background(model.dark))
                        .navigationTitle(model.t("Органайзер Про", "Organizer Pro"))
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbarBackground(Brand.header(model.dark), for: .navigationBar)
                        .toolbarBackground(.visible, for: .navigationBar)
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                Menu {
                                    NavigationLink(model.t("Задачи", "Tasks"), value: Route.tasks)
                                    NavigationLink(model.t("Календарь", "Calendar"), value: Route.calendar)
                                    if model.pinEnabled { Button(model.t("Заблокировать", "Lock")) { model.lock() } }
                                } label: { Image(systemName: "ellipsis.circle").frame(minWidth: 44, minHeight: 44) }.accessibilityLabel(model.t("Ещё", "More"))
                            }
                        }
                        .navigationDestination(for: Route.self) { route in
                            switch route {
                            case .note(let id): NoteDetail(id: id)
                            case .project(let id): ProjectDetail(id: id)
                            case .tasks: TasksView()
                            case .reminders: RemindersView()
                            case .calendar: CalendarView()
                            case .privacy: PrivacyView()
                            case .safety: SafetyView()
                            }
                        }
                        .safeAreaInset(edge: .bottom, spacing: 0) { bottomBar }
                }
            }
        }.font(.system(size: point * model.buttonScale, weight: model.bold ? .semibold : .regular)).tint(Brand.blue)
            .sheet(item: $model.presentation, onDismiss: { model.nextPresentation() }) { SheetHost(presentation: $0).environmentObject(model).preferredColorScheme(model.dark ? .dark : .light).environment(\.locale, model.locale) }
            .fileImporter(isPresented: $model.importer, allowedContentTypes: [.json, .zip], allowsMultipleSelection: false) { result in
                switch result { case .success(let urls): if let url = urls.first { model.importBackup(url) }; case .failure(let error): if (error as NSError).code != NSUserCancelledError { model.report(error) } }
            }
            .fileExporter(isPresented: $model.exporter, document: model.exportDocument, contentType: .zip, defaultFilename: model.filename) { result in
                switch result {
                case .success: model.notice = AppNotice(message: model.t("Полная копия сохранена. Её можно отправить по e-mail. Для восстановления откройте вложение в Органайзере Про или выберите «Восстановить из файла».", "Your full backup has been saved. You can send it by e-mail. To restore, open the attachment in Organizer Pro or choose Restore from file."))
                case .failure(let error): if (error as NSError).code != NSUserCancelledError { model.report(error) }
                }
            }
            .confirmationDialog(model.t("Сохранить полную копию", "Save a full backup"), isPresented: $model.saveOptions, titleVisibility: .visible) {
                Button(model.t("Сохранить в Файлы", "Save to Files")) { model.prepareExport(toFile: true) }
                Button(model.t("Отправить по e-mail", "Send by e-mail")) { model.prepareExport(toFile: false) }
            }
            .alert(model.t("Органайзер Про", "Organizer Pro"), isPresented: Binding(get: { model.notice != nil && model.presentation == nil }, set: { if !$0 { model.notice = nil } })) { Button("OK") { model.notice = nil } } message: { Text(model.notice?.message ?? "") }
            .overlay { if model.busy { ZStack { Color.black.opacity(0.2).ignoresSafeArea(); ProgressView(model.t("Проверка копии…", "Checking backup…")).padding(24).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16)) } } }
            .onChange(of: model.snapshot.settings.flag("projectsEnabled", true)) { _, enabled in if !enabled && tab == .projects { tab = .notes; path = [] } }
            .onReceive(NotificationCenter.default.publisher(for: .organizerOpenReminder)) { message in
                guard let type = message.userInfo?["targetType"] as? String, let id = message.userInfo?["targetId"] as? Int64 else { return }
                path = type == "note" ? [.note(id)] : [.project(id)]
            }
    }
    var header: some View {
        HStack(spacing: 8) {
            NavigationLink(value: Route.calendar) { Image(systemName: "calendar").frame(minWidth: 22) }.buttonStyle(Pill()).accessibilityLabel(model.t("Календарь", "Calendar"))
            NavigationLink(value: Route.reminders) { Text(model.t("Напоминания", "Reminders")).frame(maxWidth: .infinity).multilineTextAlignment(.center) }.buttonStyle(Pill())
            Button { switch tab { case .notes: model.show(.note(nil)); case .projects: model.show(.project(nil)); case .money: model.show(.money("expense", nil)); case .settings: break } } label: {
                Text(model.t("Создать", "Create")).frame(maxWidth: .infinity)
            }.buttonStyle(Pill()).accessibilityIdentifier("createButton")
        }.padding(10).background(Brand.header(model.dark))
    }
    func title(_ tab: MainTab) -> String {
        switch tab { case .notes: return model.t("Заметки", "Notes"); case .projects: return model.t("Проекты", "Projects"); case .money: return model.t("Финансы", "Finances"); case .settings: return model.t("Настройки", "Settings") }
    }
    var bottomBar: some View {
        HStack(spacing: 5) {
            ForEach(tabs, id: \.self) { item in
                Button { tab = item; path = [] } label: {
                    Text(title(item)).fontWeight(.semibold).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, minHeight: 44).padding(.vertical, 5).padding(.horizontal, 3)
                        .foregroundStyle(tab == item ? Brand.ink : model.dark ? .white : Brand.blue)
                        .background(tab == item ? Brand.yellow : Brand.header(model.dark), in: RoundedRectangle(cornerRadius: 17))
                        .overlay(RoundedRectangle(cornerRadius: 17).stroke(Brand.blue.opacity(0.45), lineWidth: tab == item ? 0 : 1))
                }.accessibilityIdentifier("tab-\(item.rawValue)").accessibilityAddTraits(tab == item ? [.isSelected] : [])
            }
        }.padding(.horizontal, 6).padding(.vertical, 7).background(Brand.header(model.dark))
    }
}
struct SheetHost: View {
    @EnvironmentObject private var model: AppModel
    let presentation: Presentation
    var body: some View {
        NavigationStack {
            Group {
                switch presentation.kind {
                case .note(let record): NoteEditor(original: record)
                case .project(let record): ProjectEditor(original: record)
                case .money(let type, let project): MoneyEditor(type: type, projectID: project)
                case .task(let record): TaskEditor(original: record)
                case .stage(let id): ProjectEntryEditor(projectID: id, kind: "projectStages")
                case .journal(let id): ProjectEntryEditor(projectID: id, kind: "projectJournal")
                case .link(let id): LinkNotesView(projectID: id)
                case .reminder(let type, let id, let record): ReminderEditor(targetType: type, targetID: id, original: record)
                case .pin: PINSettingsView()
                case .backup(let preview): BackupConfirmation(preview: preview)
                case .share(let url): ShareSheet(url: url)
                case .preview(let url): FilePreview(url: url)
                }
            }
        }.alert(model.t("Органайзер Про", "Organizer Pro"), isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) { Button("OK") { model.notice = nil } } message: { Text(model.notice?.message ?? "") }
    }
}
