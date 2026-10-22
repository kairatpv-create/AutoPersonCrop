import Foundation

public enum BackupCodec {
    public static let maxJSON = 64 * 1024 * 1024
    public static let maxAttachment = 25 * 1024 * 1024
    public static let maxDecoded = 48 * 1024 * 1024
    public static func decode(_ input: Data) throws -> Snapshot {
        guard input.count <= maxJSON else { throw OrganizerError.tooLarge }
        let data = input.starts(with: [0x50, 0x4b]) ? try BackupZIP.read(input) : input
        try preflight(data)
        guard let root = try JSONDecoder().decode(JSONValue.self, from: data).object else { throw OrganizerError.invalid("JSON") }
        let snapshot = Snapshot(root: root)
        try validate(snapshot)
        return snapshot
    }
    public static func encode(_ snapshot: Snapshot, zip: Bool = true) throws -> Data {
        var copy = snapshot
        copy.root["format"] = .string("my-organizer-backup"); copy.root["version"] = .integer(6)
        copy.root["createdAt"] = .integer(Snapshot.now)
        copy.root.removeValue(forKey: "cloudSchema")
        try validate(copy)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(JSONValue.object(copy.root))
        try preflight(data)
        return zip ? try BackupZIP.write(data) : data
    }
    public static func validate(_ snapshot: Snapshot, attachmentData: Bool = true) throws {
        let root = snapshot.root
        guard root["format"]?.text == "my-organizer-backup", let version = root["version"]?.integer, (3...7).contains(version), root["createdAt"]?.integer != nil else { throw OrganizerError.invalid("format/version") }
        func fields(_ object: [String: JSONValue], _ string: String = "", _ integer: String = "", _ number: String = "", _ boolean: String = "", optional: String = "") throws {
            let optional = Set(optional.split(separator: " ").map(String.init))
            for (keys, type) in [(string, 0), (integer, 1), (number, 2), (boolean, 3)] {
                for key in keys.split(separator: " ").map(String.init) {
                    guard let value = object[key] else { if optional.contains(key) { continue }; throw OrganizerError.invalid(key) }
                    let valid = type == 0 ? value.text != nil : type == 1 ? value.integer != nil : type == 2 ? value.double?.isFinite == true : value.boolean != nil
                    if !valid { throw OrganizerError.invalid(key) }
                }
            }
        }
        func records(_ key: String, identity: [String] = ["id"], check: ([String: JSONValue]) throws -> Void) throws {
            guard let list = root[key]?.array else { throw OrganizerError.invalid(key) }
            var seen = Set<[Int64]>()
            for value in list {
                guard let object = value.object else { throw OrganizerError.invalid(key) }
                try check(object)
                let id = try identity.map { name -> Int64 in guard let id = object[name]?.integer else { throw OrganizerError.invalid(name) }; return id }
                guard seen.insert(id).inserted else { throw OrganizerError.invalid("duplicate \(key)") }
            }
        }
        try records("notes") { try fields($0, "title body colorKey", "id createdAt updatedAt", optional: "colorKey") }
        try records("tasks") { try fields($0, "title details", "id createdAt updatedAt", "", "done") }
        try records("projects") {
            try fields($0, "name description status client address contact colorKey", "id createdAt updatedAt startAt dueAt progress", "budget", optional: "status client address contact colorKey updatedAt startAt dueAt progress budget")
            if let progress = $0["progress"]?.integer, !(Int64(Int32.min)...Int64(Int32.max)).contains(progress) { throw OrganizerError.invalid("progress") }
        }
        try records("projectStages") { try fields($0, "title", "id projectId dueAt createdAt", "", "done") }
        try records("projectJournal") { try fields($0, "text", "id projectId createdAt") }
        try records("projectNoteLinks", identity: ["projectId", "noteId"]) { try fields($0, "", "projectId noteId") }
        try records("money") { try fields($0, "type category projectName note colorKey", "id projectId createdAt", "amount", optional: "projectId colorKey") }
        try records("reminders") {
            try fields($0, "targetType soundKey scheduleType dayMode", "id targetId triggerAt createdAt daysMask baseMinutes", "", "sound vibrate", optional: "soundKey scheduleType dayMode sound vibrate daysMask baseMinutes")
            for key in ["daysMask", "baseMinutes"] { if let n = $0[key]?.integer, !(Int64(Int32.min)...Int64(Int32.max)).contains(n) { throw OrganizerError.invalid(key) } }
        }
        if version < 7 || root["settings"] != nil {
            guard let settings = root["settings"]?.object else { throw OrganizerError.invalid("settings") }
            try fields(settings, "backupMode email language", "", "fontScale contentFontScale buttonFontScale", "darkTheme boldText projectsEnabled", optional: "projectsEnabled contentFontScale buttonFontScale")
            for key in ["fontScale", "contentFontScale", "buttonFontScale"] { if let n = settings[key]?.double, !Float(n).isFinite { throw OrganizerError.invalid(key) } }
        }
        if version < 7 || root["attachments"] != nil {
            var names = Set<String>(), decoded = 0
            guard let attachments = root["attachments"]?.array, attachments.count <= 10_000 else { throw OrganizerError.invalid("attachments") }
            try records("attachments") { object in
                try fields(object, "targetType displayName storedName mimeType" + (attachmentData ? " data" : ""), "id targetId size createdAt durationMs", optional: "durationMs")
                let name = object["storedName"]!.text!
                guard !name.isEmpty, ![".", ".."].contains(name), !name.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 || "/\\:".unicodeScalars.contains($0) }), names.insert(name).inserted,
                      let size = object["size"]?.integer, size >= 0, size <= Int64(maxAttachment) else { throw OrganizerError.invalid("attachment path/size") }
                if attachmentData {
                    let text = object["data"]!.text!
                    guard text.utf8.count <= (maxAttachment + 2) / 3 * 4, let bytes = Data(base64Encoded: text), bytes.count == size else { throw OrganizerError.invalid("attachment data") }
                    decoded += bytes.count
                    if decoded > maxDecoded { throw OrganizerError.tooLarge }
                }
            }
        }
    }
    public static func preflight(_ data: Data) throws {
        guard data.count <= maxJSON, String(data: data, encoding: .utf8) != nil else { throw OrganizerError.tooLarge }
        var depth = 0, units = 0, quoted = false, escaped = false
        for byte in data {
            if quoted {
                if escaped { escaped = false } else if byte == 92 { escaped = true } else if byte == 34 { quoted = false }
            } else {
                if byte == 34 { quoted = true; units += 1 }
                else if byte == 123 || byte == 91 { depth += 1; units += 1 }
                else if byte == 125 || byte == 93 { depth -= 1 }
                else if byte == 44 || byte == 58 { units += 1 }
            }
            if depth > 32 || units > 1_000_000 { throw OrganizerError.tooLarge }
        }
    }
}
