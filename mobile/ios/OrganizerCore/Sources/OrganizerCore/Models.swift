import Foundation

public enum JSONValue: Codable, Equatable, Sendable {
    case object([String: JSONValue]), array([JSONValue]), string(String), integer(Int64), number(Double), bool(Bool), null
    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Int64.self) { self = .integer(v) }
        else if let v = try? c.decode(Double.self), v.isFinite { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([JSONValue].self) { self = .array(v) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .object(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .integer(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }
    public var object: [String: JSONValue]? { if case .object(let v) = self { return v }; return nil }
    public var array: [JSONValue]? { if case .array(let v) = self { return v }; return nil }
    public var text: String? { if case .string(let v) = self { return v }; return nil }
    public var integer: Int64? { if case .integer(let v) = self { return v }; return nil }
    public var double: Double? {
        if case .number(let v) = self { return v }
        if case .integer(let v) = self { return Double(v) }; return nil
    }
    public var boolean: Bool? { if case .bool(let v) = self { return v }; return nil }
}

public struct OrganizerRecord: Equatable, Identifiable, Sendable {
    public var fields: [String: JSONValue]
    public init(_ fields: [String: JSONValue] = [:]) { self.fields = fields }
    public var id: Int64 { int("id") }
    public func text(_ key: String, _ fallback: String = "") -> String { fields[key]?.text ?? fallback }
    public func int(_ key: String, _ fallback: Int64 = 0) -> Int64 { fields[key]?.integer ?? fallback }
    public func number(_ key: String, _ fallback: Double = 0) -> Double { fields[key]?.double ?? fallback }
    public func flag(_ key: String, _ fallback: Bool = false) -> Bool { fields[key]?.boolean ?? fallback }
    public mutating func set(_ key: String, _ value: String) { fields[key] = .string(value) }
    public mutating func set(_ key: String, _ value: Int64) { fields[key] = .integer(value) }
    public mutating func set(_ key: String, _ value: Double) { fields[key] = .number(value) }
    public mutating func set(_ key: String, _ value: Bool) { fields[key] = .bool(value) }
}

public struct Snapshot: Equatable, Sendable {
    public static let collections = ["notes", "tasks", "projects", "projectStages", "projectJournal", "projectNoteLinks", "money", "reminders"]
    public var root: [String: JSONValue]
    public init(root: [String: JSONValue]) { self.root = root }
    public static func empty(language: String = "ru") -> Snapshot {
        var root: [String: JSONValue] = ["format": .string("my-organizer-backup"), "version": .integer(6), "createdAt": .integer(now)]
        for key in collections + ["attachments"] { root[key] = .array([]) }
        root["settings"] = .object(["darkTheme": .bool(false), "fontScale": .number(1), "contentFontScale": .number(1), "buttonFontScale": .number(1), "boldText": .bool(false), "projectsEnabled": .bool(true), "backupMode": .string("manual"), "email": .string(""), "language": .string(language)])
        return Snapshot(root: root)
    }
    public static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    public func records(_ key: String) -> [OrganizerRecord] { (root[key]?.array ?? []).compactMap { $0.object.map(OrganizerRecord.init) } }
    public mutating func setRecords(_ key: String, _ records: [OrganizerRecord]) { root[key] = .array(records.map { .object($0.fields) }) }
    public var settings: OrganizerRecord {
        get { OrganizerRecord(root["settings"]?.object ?? [:]) }
        set { root["settings"] = .object(newValue.fields) }
    }
    public func record(_ key: String, id: Int64) -> OrganizerRecord? { records(key).first { $0.id == id } }
    public mutating func upsert(_ key: String, record: OrganizerRecord) {
        var list = records(key)
        if let i = list.firstIndex(where: { $0.id == record.id }) { list[i] = record } else { list.append(record) }
        setRecords(key, list)
    }
    public func nextID(_ key: String) throws -> Int64 {
        let last = records(key).map(\.id).max() ?? 0
        guard last < Int64.max else { throw OrganizerError.invalid("id") }
        return max(1, last + 1)
    }
    public mutating func updateStageProgress(projectID: Int64) {
        guard var project = record("projects", id: projectID) else { return }
        let stages = records("projectStages").filter { $0.int("projectId") == projectID }
        project.set("progress", stages.isEmpty ? Int64(0) : Int64(stages.filter { $0.flag("done") }.count * 100 / stages.count))
        project.set("updatedAt", Self.now); upsert("projects", record: project)
    }
    public mutating func remove(_ key: String, id: Int64) {
        setRecords(key, records(key).filter { $0.id != id })
        let type = key == "notes" ? "note" : key == "projects" ? "project" : ""
        if !type.isEmpty {
            setRecords("attachments", records("attachments").filter { !($0.text("targetType") == type && $0.int("targetId") == id) })
            setRecords("reminders", records("reminders").filter { !($0.text("targetType") == type && $0.int("targetId") == id) })
        }
        if key == "notes" { setRecords("projectNoteLinks", records("projectNoteLinks").filter { $0.int("noteId") != id }) }
        if key == "projects" {
            for table in ["projectStages", "projectJournal", "projectNoteLinks"] { setRecords(table, records(table).filter { $0.int("projectId") != id }) }
            setRecords("money", records("money").map { record in
                var r = record
                if r.int("projectId", -1) == id { r.fields.removeValue(forKey: "projectId") }
                return r
            })
        }
    }
}

public enum OrganizerError: Error, LocalizedError {
    case invalid(String), tooLarge, storage(String), missingAttachment, interrupted
    public var errorDescription: String? {
        switch self {
        case .invalid(let field): return "Копия повреждена, неполна или имеет неподдерживаемый формат. Данные не изменены. / The backup is damaged, incomplete or unsupported. Your data has not been changed. (\(field))"
        case .tooLarge: return "Копия слишком велика или сложна для безопасного восстановления. / The backup is too large or complex to restore safely."
        case .storage: return "Не удалось сохранить данные. Повторите попытку. / Could not save your data. Please try again."
        case .missingAttachment: return "Вложение недоступно. Данные сохранены. / The attachment is unavailable. Your data has been kept."
        case .interrupted: return "Операция прервана. / The operation was interrupted."
        }
    }
}

public enum Money {
    public static func parse(_ raw: String, allowZero: Bool = false) -> Double? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
        guard text.range(of: "^[0-9]+(?:\\.[0-9]+)?$", options: .regularExpression) != nil,
              let value = Double(text), value.isFinite, allowZero ? value >= 0 : value > 0 else { return nil }
        return value
    }
    public static func total(_ records: [OrganizerRecord], type: String? = nil) -> Decimal {
        records.filter { type == nil || $0.text("type") == type }.reduce(Decimal.zero) { result, row in
            let amount = Decimal(string: String(row.number("amount")), locale: Locale(identifier: "en_US_POSIX")) ?? 0
            return result + (type == nil && row.text("type") == "expense" ? -amount : amount)
        }
    }
}
