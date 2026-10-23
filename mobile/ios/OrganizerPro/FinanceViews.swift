import SwiftUI
import Charts
import OrganizerCore

struct FinanceView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dynamicTypeSize) private var dynamicType
    @ScaledMetric(relativeTo: .title3) private var amountPoint = 20
    var projectID: Int64? = nil
    @State private var selected = 0
    @State private var search = ""
    @State private var deleting: OrganizerRecord?
    var entries: [OrganizerRecord] { model.records("money").filter { row in (projectID == nil || row.int("projectId", -1) == projectID) && (search.isEmpty || (row.text("category") + " " + row.text("note") + " " + row.text("projectName")).localizedCaseInsensitiveContains(search)) }.sorted { $0.int("createdAt") > $1.int("createdAt") } }
    var titles: [String] { [model.t("Обзор", "Overview"), model.t("Доходы", "Income"), model.t("Расходы", "Expenses"), model.t("Аналитика", "Analytics")] }
    var body: some View {
        Page {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) { ForEach(titles.indices, id: \.self) { i in financeTab(i, compact: true) } }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: dynamicType.isAccessibilitySize ? 1 : 2), spacing: 6) {
                    ForEach(titles.indices, id: \.self) { i in financeTab(i, compact: false) }
                }
            }
            if selected == 0 {
                summary
                HStack { Button(model.t("Доход", "Income")) { model.show(.money("income", projectID)) }.buttonStyle(Pill(bold: model.bold)); Button(model.t("Расход", "Expense")) { model.show(.money("expense", projectID)) }.buttonStyle(Pill(bold: model.bold)) }
            } else if selected < 3 { Button(model.t("Добавить", "Add")) { model.show(.money(selected == 1 ? "income" : "expense", projectID)) }.buttonStyle(Pill(bold: model.bold)) }
            if selected == 3 { analytics }
            else {
                TextField(model.t("Поиск записей", "Search entries"), text: $search, prompt: Text(model.t("Поиск записей", "Search entries")).foregroundColor(model.dark ? Color(white: 0.75) : .secondary)).textFieldStyle(.roundedBorder)
                if entries.isEmpty { EmptyCard(title: model.t("Записей пока нет", "No entries yet"), symbol: "chart.bar") }
                ForEach(entries.filter { selected == 0 || $0.text("type") == (selected == 1 ? "income" : "expense") }) { entry in
                    Card(color: entry.text("colorKey", entry.text("type") == "income" ? "blue" : "yellow")) {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text((entry.text("type") == "expense" ? "−" : "+") + model.amount(entry.number("amount"))).font(.system(size: amountPoint * model.contentScale, weight: .bold)).foregroundStyle(entry.text("type") == "expense" ? .red : .primary).accessibilityIdentifier("moneyAmount")
                                UserText(entry.text("category"), strong: true); if !entry.text("note").isEmpty { UserText(entry.text("note")) }
                                if !entry.text("projectName").isEmpty { UserText(entry.text("projectName"), lines: 2) }
                                Text(model.date(entry.int("createdAt"), time: true)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 2)
                            Button(role: .destructive) { deleting = entry } label: { Image(systemName: "trash").frame(minWidth: 44, minHeight: 44) }.accessibilityLabel(model.t("Удалить запись", "Delete entry"))
                        }
                    }
                }
            }
        }.confirmationDialog(model.t("Удалить финансовую запись?", "Delete this financial entry?"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) { Button(model.t("Удалить", "Delete"), role: .destructive) { if let deleting { model.remove("money", id: deleting.id) }; deleting = nil } }
    }
    func financeTab(_ index: Int, compact: Bool) -> some View {
        Button { selected = index } label: {
            Text(titles[index]).lineLimit(1).minimumScaleFactor(0.7)
                .frame(maxWidth: compact ? nil : .infinity, minHeight: 28)
                .fixedSize(horizontal: compact, vertical: false)
        }.buttonStyle(.borderedProminent)
            .tint(selected == index ? Brand.accent(model.dark) : Brand.accent(model.dark).opacity(0.15))
            .foregroundStyle(selected == index ? (model.dark ? Brand.ink : Color.white) : Color.primary)
            .accessibilityIdentifier("finance-tab-\(index)")
            .accessibilityAddTraits(selected == index ? [.isSelected] : [])
    }
    var summary: some View {
        Card {
            LabeledContent(model.t("Доходы", "Income"), value: model.amount(Money.total(entries, type: "income")))
            LabeledContent(model.t("Расходы", "Expenses"), value: model.amount(Money.total(entries, type: "expense")))
            Divider()
            LabeledContent(model.t("Баланс", "Balance"), value: model.amount(Money.total(entries))).fontWeight(.semibold).accessibilityIdentifier("moneyBalance")
        }
    }
    struct Month: Identifiable { let id: Date; let income: Double; let expense: Double }
    var months: [Month] {
        let c = Calendar.current, now = Date()
        return (-5...0).compactMap { offset in
            guard let date = c.date(byAdding: .month, value: offset, to: now), let start = c.dateInterval(of: .month, for: date)?.start, let end = c.date(byAdding: .month, value: 1, to: start) else { return nil }
            let rows = entries.filter { $0.int("createdAt") >= start.milliseconds && $0.int("createdAt") < end.milliseconds }
            return Month(id: start, income: NSDecimalNumber(decimal: Money.total(rows, type: "income")).doubleValue, expense: NSDecimalNumber(decimal: Money.total(rows, type: "expense")).doubleValue)
        }
    }
    var analytics: some View {
        VStack(spacing: 12) {
            summary
            Card {
                Text(model.t("Последние 6 месяцев", "Last 6 months")).font(.headline)
                Chart(months) { month in
                    BarMark(x: .value(model.t("Месяц", "Month"), month.id, unit: .month), y: .value(model.t("Доходы", "Income"), month.income)).foregroundStyle(Brand.accent(model.dark)).position(by: .value("Type", model.t("Доходы", "Income")))
                    BarMark(x: .value(model.t("Месяц", "Month"), month.id, unit: .month), y: .value(model.t("Расходы", "Expenses"), month.expense)).foregroundStyle(Brand.yellow).position(by: .value("Type", model.t("Расходы", "Expenses")))
                }.frame(height: 230).accessibilityLabel(model.t("Доходы и расходы по месяцам", "Monthly income and expenses"))
                HStack { Label(model.t("Доходы", "Income"), systemImage: "circle.fill").foregroundStyle(Brand.accent(model.dark)); Label(model.t("Расходы", "Expenses"), systemImage: "circle.fill").foregroundStyle(.secondary) }
            }
            Card(color: "yellow") {
                Text(model.t("Расходы по категориям", "Expenses by category")).font(.headline)
                ForEach(Array(Set(entries.filter { $0.text("type") == "expense" }.map { $0.text("category") })).sorted(), id: \.self) { category in
                    LabeledContent { Text(model.amount(Money.total(entries.filter { $0.text("category") == category }, type: "expense"))) } label: { UserText(category) }
                }
            }
        }
    }
}
struct MoneyEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let type: String; let projectID: Int64?
    @State private var amount = ""
    @State private var category = ""
    @State private var note = ""
    @State private var color = "blue"
    @State private var project: Int64 = 0
    @State private var projectOwner: Int64?
    @State private var fixedOwner: Int64?
    @State private var projectChanged = false
    @State private var loaded = false
    var key: String { "money:\(type):\(projectID.map { "\($0):\(fixedOwner?.description ?? "unavailable")" } ?? "all"):new" }
    var body: some View {
        Form {
            Section {
                TextField(model.t("Сумма", "Amount"), text: $amount).keyboardType(.decimalPad).accessibilityIdentifier("moneyInput")
                TextField(model.t("Категория", "Category"), text: $category).accessibilityIdentifier("moneyCategory")
                let suggestions = Array(Set(model.records("money").filter { $0.text("type") == type && !$0.text("category").isEmpty }.map { $0.text("category") })).sorted()
                if !suggestions.isEmpty { Menu(model.t("Выбрать категорию", "Choose category")) { ForEach(suggestions, id: \.self) { value in Button(value) { category = value } } } }
                TextField(model.t("Примечание", "Note"), text: $note, axis: .vertical).lineLimit(3...8)
                Picker(model.t("Проект", "Project"), selection: $project) {
                    Text(model.t("Без проекта", "No project")).tag(Int64(0)); ForEach(model.records("projects")) { Text($0.text("name")).tag($0.id) }
                }.disabled(projectID != nil)
                if projectChanged {
                    Text(model.t("Проект черновика изменился после восстановления. Сумма и текст сохранены. Выберите проект заново.", "The draft's project changed after restore. Your amount and text were kept. Please choose the project again.")).font(.caption).foregroundStyle(.secondary)
                }
                ColorPickerRow(selected: $color)
            }
        }.navigationTitle(model.t(type == "income" ? "Доход" : "Расход", type == "income" ? "Income" : "Expense"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(model.t("Закрыть", "Close")) { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button(model.t("Сохранить", "Save")) { save() }.disabled(Money.parse(amount) == nil).accessibilityIdentifier("saveMoney") } }
            .onAppear {
                guard !loaded else { return }
                fixedOwner = projectID.flatMap { model.snapshot.record("projects", id: $0)?.int("createdAt") }
                if let draft = model.draft(key) {
                    amount = draft.text("amount"); category = draft.text("category"); note = draft.text("note"); color = draft.text("color", "blue")
                    project = projectID ?? draft.int("project")
                    projectOwner = projectID != nil ? fixedOwner : draft.fields["projectCreatedAt"]?.integer
                    if project != 0 && !model.projectMatches(project, createdAt: projectOwner) { project = projectID ?? 0; projectOwner = fixedOwner; projectChanged = true }
                } else { color = type == "income" ? "blue" : "yellow"; project = projectID ?? 0; projectOwner = fixedOwner }
                loaded = true
            }
            .onChange(of: amount) { _, _ in persist() }.onChange(of: category) { _, _ in persist() }.onChange(of: note) { _, _ in persist() }.onChange(of: color) { _, _ in persist() }
            .onChange(of: project) { _, value in if loaded { projectOwner = model.snapshot.record("projects", id: value)?.int("createdAt"); persist() } }
    }
    func persist() {
        if loaded { model.draft(key, record: OrganizerRecord(["amount": .string(amount), "category": .string(category), "note": .string(note), "color": .string(color), "project": .integer(project), "projectCreatedAt": projectOwner.map { .integer($0) } ?? .null])) }
    }
    func save() {
        guard let value = Money.parse(amount) else { return }
        if project != 0 && !model.projectMatches(project, createdAt: projectOwner) { model.report(OrganizerError.ownerChanged); return }
        var r = OrganizerRecord(["type": .string(type), "amount": .number(value), "category": .string(category), "note": .string(note), "colorKey": .string(color), "projectName": .string("")])
        if project != 0 { r.set("projectId", project) }
        if model.save("money", record: r, expectedProjectCreatedAt: projectOwner) != nil { loaded = false; model.draft(key, record: nil); dismiss() }
    }
}
