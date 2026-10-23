import Foundation
import CSQLite

public struct SafetyCopy: Identifiable, Sendable { public let id: Int64; public let createdAt: Int64 }

/// iOS storage is independent of Android schema 9. Metadata and attachment bytes commit together.
/// There are no external attachment-file generations to publish outside the SQLite transaction.
public final class OrganizerStore {
    private var db: OpaquePointer?
    private let lock = NSRecursiveLock()
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    public let url: URL
    public init(url: URL, language: String = "ru") throws {
        self.url = url
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let existed = FileManager.default.fileExists(atPath: url.path)
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else { throw OrganizerError.storage("open") }
        do {
            sqlite3_busy_timeout(db, 5000)
            try exec("PRAGMA journal_mode=WAL"); try exec("PRAGMA synchronous=FULL")
            guard try scalarInt("PRAGMA user_version") <= 1 else { throw OrganizerError.storage("newer database") }
            try transaction {
                try exec("CREATE TABLE IF NOT EXISTS organizer_state(id INTEGER PRIMARY KEY CHECK(id=1), document BLOB NOT NULL)")
                try exec("CREATE TABLE IF NOT EXISTS organizer_attachments(name TEXT PRIMARY KEY, body BLOB NOT NULL)")
                try exec("CREATE TABLE IF NOT EXISTS organizer_safety(id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, document BLOB NOT NULL)")
                try exec("CREATE TABLE IF NOT EXISTS organizer_drafts(name TEXT PRIMARY KEY, document BLOB NOT NULL)")
                if try scalarInt("SELECT count(*) FROM organizer_state") == 0 {
                    guard !existed else { throw OrganizerError.storage("missing state; database retained") }
                    try saveMetadata(Snapshot.empty(language: language))
                }
                try exec("PRAGMA user_version=1")
            }
            _ = try load()
        } catch { sqlite3_close(db); db = nil; throw error }
    }
    deinit { sqlite3_close(db) }
    private func statement(_ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw OrganizerError.storage("prepare") }
        return statement
    }
    private func exec(_ sql: String) throws { guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw OrganizerError.storage("execute") } }
    private func scalarInt(_ sql: String) throws -> Int64 {
        let s = try statement(sql); defer { sqlite3_finalize(s) }
        guard sqlite3_step(s) == SQLITE_ROW else { throw OrganizerError.storage("read") }
        return sqlite3_column_int64(s, 0)
    }
    private func bind(_ data: Data, to statement: OpaquePointer, index: Int32) throws {
        let result = data.isEmpty ? sqlite3_bind_zeroblob(statement, index, 0) : data.withUnsafeBytes { sqlite3_bind_blob(statement, index, $0.baseAddress, Int32(data.count), transient) }
        if result != SQLITE_OK { throw OrganizerError.storage("bind") }
    }
    private func blob(_ statement: OpaquePointer, _ index: Int32) -> Data {
        let size = Int(sqlite3_column_bytes(statement, index))
        return sqlite3_column_blob(statement, index).map { Data(bytes: $0, count: size) } ?? Data()
    }
    private func done(_ statement: OpaquePointer) throws { if sqlite3_step(statement) != SQLITE_DONE { throw OrganizerError.storage("write") } }
    private func transaction<T>(_ operation: () throws -> T) throws -> T {
        try exec("BEGIN IMMEDIATE")
        do { let result = try operation(); try exec("COMMIT"); return result }
        catch { try? exec("ROLLBACK"); throw error }
    }
    private func json(_ snapshot: Snapshot) throws -> Data {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(JSONValue.object(snapshot.root))
    }
    public func load() throws -> Snapshot {
        lock.lock(); defer { lock.unlock() }
        let s = try statement("SELECT document FROM organizer_state WHERE id=1"); defer { sqlite3_finalize(s) }
        guard sqlite3_step(s) == SQLITE_ROW, let root = try JSONDecoder().decode(JSONValue.self, from: blob(s, 0)).object else { throw OrganizerError.storage("read state") }
        let snapshot = Snapshot(root: root); try BackupCodec.validate(snapshot, attachmentData: false)
        return snapshot
    }
    private func saveMetadata(_ snapshot: Snapshot) throws {
        try BackupCodec.validate(snapshot, attachmentData: false)
        let data = try json(snapshot)
        let encodedSize = snapshot.records("attachments").reduce(Int64(data.count)) { $0 + (($1.int("size") + 2) / 3 * 4) }
        guard encodedSize <= BackupCodec.maxJSON else { throw OrganizerError.tooLarge }
        let s = try statement("INSERT OR REPLACE INTO organizer_state(id, document) VALUES(1, ?)"); defer { sqlite3_finalize(s) }
        try bind(data, to: s, index: 1); try done(s)
    }
    public func attachment(_ name: String) throws -> Data {
        lock.lock(); defer { lock.unlock() }
        let s = try statement("SELECT body FROM organizer_attachments WHERE name=?"); defer { sqlite3_finalize(s) }
        sqlite3_bind_text(s, 1, name, -1, transient)
        guard sqlite3_step(s) == SQLITE_ROW else { throw OrganizerError.missingAttachment }
        return blob(s, 0)
    }
    public func fullSnapshot() throws -> Snapshot {
        lock.lock(); defer { lock.unlock() }
        var snapshot = try load()
        snapshot.setRecords("attachments", try snapshot.records("attachments").map { row in
            var copy = row
            let data = try attachment(row.text("storedName"))
            guard data.count == row.int("size") else { throw OrganizerError.missingAttachment }
            copy.set("data", data.base64EncodedString()); return copy
        })
        try BackupCodec.validate(snapshot)
        return snapshot
    }
    public func export(zip: Bool = true) throws -> Data { lock.lock(); defer { lock.unlock() }; return try BackupCodec.encode(fullSnapshot(), zip: zip) }
    public func mutate(_ change: (inout Snapshot) throws -> Void) throws {
        lock.lock(); defer { lock.unlock() }
        try transaction {
            let old = try load(); var next = old; try change(&next)
            // New attachment bytes may only enter through addAttachment / restore.
            let oldNames = Set(old.records("attachments").map { $0.text("storedName") })
            guard next.records("attachments").allSatisfy({ oldNames.contains($0.text("storedName")) }) else { throw OrganizerError.missingAttachment }
            try saveMetadata(next)
            for row in old.records("attachments") where !next.records("attachments").contains(where: { $0.text("storedName") == row.text("storedName") }) { try deleteBody(row.text("storedName")) }
        }
    }
    private func deleteBody(_ name: String) throws {
        let s = try statement("DELETE FROM organizer_attachments WHERE name=?"); defer { sqlite3_finalize(s) }
        sqlite3_bind_text(s, 1, name, -1, transient); try done(s)
    }
    private func insertBody(_ name: String, _ data: Data) throws {
        let s = try statement("INSERT INTO organizer_attachments(name,body) VALUES(?,?)"); defer { sqlite3_finalize(s) }
        sqlite3_bind_text(s, 1, name, -1, transient); try bind(data, to: s, index: 2); try done(s)
    }
    public func addAttachment(data: Data, displayName: String, mimeType: String, targetType: String, targetID: Int64, duration: Int64 = 0, expectedOwnerCreatedAt: Int64? = nil) throws {
        guard data.count <= BackupCodec.maxAttachment, ["note", "project"].contains(targetType) else { throw OrganizerError.tooLarge }
        lock.lock(); defer { lock.unlock() }
        try transaction {
            var state = try load()
            guard let owner = state.record(targetType == "note" ? "notes" : "projects", id: targetID), expectedOwnerCreatedAt == nil || owner.int("createdAt") == expectedOwnerCreatedAt else { throw OrganizerError.invalid("attachment owner") }
            let name = UUID().uuidString.lowercased()
            let row = OrganizerRecord(["id": .integer(try state.nextID("attachments")), "targetType": .string(targetType), "targetId": .integer(targetID), "displayName": .string(displayName), "storedName": .string(name), "mimeType": .string(mimeType), "size": .integer(Int64(data.count)), "createdAt": .integer(Snapshot.now), "durationMs": .integer(duration)])
            state.upsert("attachments", record: row)
            guard state.records("attachments").reduce(Int64(0), { $0 + $1.int("size") }) <= BackupCodec.maxDecoded else { throw OrganizerError.tooLarge }
            try insertBody(name, data); try saveMetadata(state)
        }
    }
    public func restore(_ incoming: Snapshot, beforeCommit: (() throws -> Void)? = nil) throws {
        try BackupCodec.validate(incoming)
        lock.lock(); defer { lock.unlock() }
        try transaction {
            let old = try fullSnapshot(); var next = incoming
            if next.root["settings"] == nil { next.root["settings"] = old.root["settings"] }
            if next.root["attachments"] == nil {
                for row in old.records("attachments") {
                    let collection = row.text("targetType") == "note" ? "notes" : "projects", id = row.int("targetId")
                    guard let prior = old.record(collection, id: id), let target = next.record(collection, id: id), prior.int("createdAt") == target.int("createdAt") else { throw OrganizerError.invalid("attachment owner") }
                }
                next.root["attachments"] = old.root["attachments"]
            }
            next.root["version"] = .integer(6); next.root.removeValue(forKey: "cloudSchema")
            try BackupCodec.validate(next)
            let safety = try BackupCodec.encode(old, zip: false)
            _ = try BackupCodec.decode(safety)
            let s = try statement("INSERT INTO organizer_safety(created_at, document) VALUES(?,?)"); defer { sqlite3_finalize(s) }
            sqlite3_bind_int64(s, 1, Snapshot.now); try bind(safety, to: s, index: 2); try done(s)
            try exec("DELETE FROM organizer_attachments")
            var metadata: [OrganizerRecord] = []
            for var row in next.records("attachments") {
                guard let data = Data(base64Encoded: row.text("data")) else { throw OrganizerError.invalid("attachment") }
                try insertBody(row.text("storedName"), data)
                row.fields.removeValue(forKey: "data"); metadata.append(row)
            }
            next.setRecords("attachments", metadata); try saveMetadata(next)
            try beforeCommit?()
        }
    }
    public func safetyCopies() throws -> [SafetyCopy] {
        lock.lock(); defer { lock.unlock() }
        let s = try statement("SELECT id,created_at FROM organizer_safety ORDER BY id DESC"); defer { sqlite3_finalize(s) }
        var result: [SafetyCopy] = []
        while sqlite3_step(s) == SQLITE_ROW { result.append(SafetyCopy(id: sqlite3_column_int64(s, 0), createdAt: sqlite3_column_int64(s, 1))) }
        return result
    }
    public func safetyCopy(id: Int64) throws -> Snapshot {
        lock.lock(); defer { lock.unlock() }
        let s = try statement("SELECT document FROM organizer_safety WHERE id=?"); defer { sqlite3_finalize(s) }
        sqlite3_bind_int64(s, 1, id); guard sqlite3_step(s) == SQLITE_ROW else { throw OrganizerError.storage("missing safety copy") }
        return try BackupCodec.decode(blob(s, 0))
    }
    public func saveDraft(name: String, record: OrganizerRecord?) throws {
        lock.lock(); defer { lock.unlock() }
        let s = try statement(record == nil ? "DELETE FROM organizer_drafts WHERE name=?" : "INSERT OR REPLACE INTO organizer_drafts(name, document) VALUES(?,?)"); defer { sqlite3_finalize(s) }
        sqlite3_bind_text(s, 1, name, -1, transient)
        if let record { try bind(try JSONEncoder().encode(JSONValue.object(record.fields)), to: s, index: 2) }
        try done(s)
    }
    public func draft(name: String) throws -> OrganizerRecord? {
        lock.lock(); defer { lock.unlock() }
        let s = try statement("SELECT document FROM organizer_drafts WHERE name=?"); defer { sqlite3_finalize(s) }
        sqlite3_bind_text(s, 1, name, -1, transient)
        guard sqlite3_step(s) == SQLITE_ROW else { return nil }
        return try JSONDecoder().decode(JSONValue.self, from: blob(s, 0)).object.map(OrganizerRecord.init)
    }
}
