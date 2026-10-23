import XCTest
@testable import OrganizerCore

final class CoreTests: XCTestCase {
    var directory: URL!
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: directory) }
    func fixture(_ name: String = "android-v6", ext: String = "json") throws -> Data {
        try Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: name, withExtension: ext, subdirectory: "Fixtures")))
    }
    func sample() throws -> Snapshot { try BackupCodec.decode(fixture()) }
    func store() throws -> OrganizerStore { try OrganizerStore(url: directory.appendingPathComponent("test.sqlite")) }
    func encoded(_ snapshot: Snapshot) throws -> Data { try JSONEncoder().encode(JSONValue.object(snapshot.root)) }
    func checkRejected(_ data: Data, file: StaticString = #filePath, line: UInt = #line) throws {
        let db = try store(); try db.restore(sample()); try db.saveDraft(name: "note:new", record: OrganizerRecord(["body": .string("unsaved draft")]))
        let before = try db.fullSnapshot(), safety = try db.safetyCopies().map(\.id)
        XCTAssertThrowsError(try db.restore(BackupCodec.decode(data)), file: file, line: line)
        XCTAssertEqual(try db.fullSnapshot(), before, file: file, line: line)
        XCTAssertEqual(try db.safetyCopies().map(\.id), safety, file: file, line: line)
        XCTAssertEqual(try db.draft(name: "note:new")?.text("body"), "unsaved draft", file: file, line: line)
    }
    func testActualAndroidJSON() throws {
        let snapshot = try sample()
        for key in Snapshot.collections + ["attachments"] { XCTAssertFalse(snapshot.records(key).isEmpty, key) }
        XCTAssertEqual(snapshot.records("money").first { $0.text("type") == "expense" }?.number("amount"), 19.95)
        XCTAssertEqual(Data(base64Encoded: snapshot.records("attachments")[0].text("data")), Data([0,1,2,127,128,255,10,13]))
    }
    func testActualAndroidZIPWithDataDescriptor() throws {
        let zip = try XCTUnwrap(Data(base64Encoded: String(decoding: fixture("android-v6.zip", ext: "base64"), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)))
        XCTAssertEqual(try BackupCodec.decode(zip), try sample())
    }
    func testSwiftZIPRoundtrip() throws {
        let source = try sample(), zip = try BackupCodec.encode(source)
        let restored = try BackupCodec.decode(zip)
        for key in Snapshot.collections + ["attachments"] { XCTAssertEqual(source.records(key), restored.records(key)) }
    }
    func testNotesOnlyCannotEraseMoneyOrAttachments() throws { try checkRejected(Data(#"{"notes":[]}"#.utf8)) }
    func testWrongFormat() throws { var s = try sample(); s.root["format"] = .string("other-app"); try checkRejected(encoded(s)) }
    func testUnsupportedVersion() throws { var s = try sample(); s.root["version"] = .integer(99); try checkRejected(encoded(s)) }
    func testTruncatedJSON() throws { try checkRejected(fixture().dropLast(17)) }
    func testWrongRoot() throws { try checkRejected(Data("[]".utf8)) }
    func testWrongSectionType() throws { var s = try sample(); s.root["money"] = .object([:]); try checkRejected(encoded(s)) }
    func testMissingSection() throws { var s = try sample(); s.root.removeValue(forKey: "projects"); try checkRejected(encoded(s)) }
    func testMissingLocalSettings() throws { var s = try sample(); s.root.removeValue(forKey: "settings"); try checkRejected(encoded(s)) }
    func testMissingLocalAttachments() throws { var s = try sample(); s.root.removeValue(forKey: "attachments"); try checkRejected(encoded(s)) }
    func testWrongMoneyType() throws {
        var s = try sample(), rows = s.records("money"); rows[0].set("amount", "19.95"); s.setRecords("money", rows); try checkRejected(encoded(s))
    }
    func testBooleanCannotBeAmount() throws {
        var s = try sample(), rows = s.records("money"); rows[0].set("amount", true); s.setRecords("money", rows); try checkRejected(encoded(s))
    }
    func testWrongBooleanType() throws {
        var s = try sample(), rows = s.records("tasks"); rows[0].set("done", Int64(1)); s.setRecords("tasks", rows); try checkRejected(encoded(s))
    }
    func testFractionalID() throws {
        var s = try sample(), rows = s.records("notes"); rows[0].set("id", 1.5); s.setRecords("notes", rows); try checkRejected(encoded(s))
    }
    func testExactLargeID() throws {
        var s = try sample(), rows = s.records("tasks"); rows[0].set("id", Int64.max - 1); s.setRecords("tasks", rows)
        XCTAssertEqual(try BackupCodec.decode(encoded(s)).records("tasks")[0].id, Int64.max - 1)
    }
    func testDuplicateIDs() throws { var s = try sample(); s.setRecords("notes", s.records("notes") + s.records("notes")); try checkRejected(encoded(s)) }
    func testDuplicateLinks() throws { var s = try sample(); s.setRecords("projectNoteLinks", s.records("projectNoteLinks") + s.records("projectNoteLinks")); try checkRejected(encoded(s)) }
    func testAttachmentTraversal() throws {
        var s = try sample(), rows = s.records("attachments"); rows[0].set("storedName", "../finance.db"); s.setRecords("attachments", rows); try checkRejected(encoded(s))
    }
    func testDamagedBase64() throws {
        var s = try sample(), rows = s.records("attachments"); rows[0].set("data", "invalid!base64"); s.setRecords("attachments", rows); try checkRejected(encoded(s))
    }
    func testAttachmentSizeMismatch() throws {
        var s = try sample(), rows = s.records("attachments"); rows[0].set("size", Int64(700)); s.setRecords("attachments", rows); try checkRejected(encoded(s))
    }
    func testDamagedZIPCRC() throws { var zip = try BackupCodec.encode(sample()); zip[50] ^= 0x80; try checkRejected(zip) }
    func testTruncatedZIP() throws { try checkRejected(BackupCodec.encode(sample()).dropLast(10)) }
    func testDeepJSONRefusedBeforeDecode() throws { try checkRejected(Data((String(repeating: "[", count: 40) + "0" + String(repeating: "]", count: 40)).utf8)) }
    func testValidEmptyIsExplicitAndRestorable() throws {
        let db = try store(); try db.restore(sample()); try db.restore(BackupCodec.decode(BackupCodec.encode(.empty())))
        XCTAssertTrue(try db.load().records("money").isEmpty)
        let safety = try XCTUnwrap(db.safetyCopies().first)
        XCTAssertFalse(try db.safetyCopy(id: safety.id).records("money").isEmpty)
        try db.restore(db.safetyCopy(id: safety.id)); XCTAssertEqual(try db.fullSnapshot().records("money"), try sample().records("money"))
    }
    func testLegacyLocalVersions() throws {
        for version in 3...6 { var s = try sample(); s.root["version"] = .integer(Int64(version)); XCTAssertNoThrow(try BackupCodec.decode(encoded(s))) }
    }
    func testCloudV7PreservesLocalSettingsAndAttachments() throws {
        let db = try store(); try db.restore(sample()); let before = try db.fullSnapshot()
        try db.restore(BackupCodec.decode(fixture("android-v7")))
        XCTAssertEqual(try db.fullSnapshot().records("attachments"), before.records("attachments"))
        XCTAssertEqual(try db.load().settings, before.settings)
    }
    func testCloudV7CannotReassignLocalAttachment() throws {
        let db = try store(); try db.restore(sample()); let before = try db.fullSnapshot()
        var cloud = try BackupCodec.decode(fixture("android-v7")), notes = cloud.records("notes"); notes[0].set("createdAt", Int64(1)); cloud.setRecords("notes", notes)
        XCTAssertThrowsError(try db.restore(cloud)); XCTAssertEqual(try db.fullSnapshot(), before)
    }
    func testSQLRollbackIncludesBytesMetadataAndSafety() throws {
        let db = try store(); try db.restore(sample()); let before = try db.fullSnapshot(), safety = try db.safetyCopies().map(\.id)
        XCTAssertThrowsError(try db.restore(.empty()) { throw OrganizerError.interrupted })
        XCTAssertEqual(try db.fullSnapshot(), before); XCTAssertEqual(try db.safetyCopies().map(\.id), safety)
    }
    func testDraftIsDurableAndNotExported() throws {
        let db = try store(); try db.saveDraft(name: "new-note", record: OrganizerRecord(["body": .string("private draft")]))
        XCTAssertEqual(try db.draft(name: "new-note")?.text("body"), "private draft")
        XCTAssertFalse(String(decoding: try db.export(zip: false), as: UTF8.self).contains("private draft"))
    }
    func testAttachmentInsertRemoveAndReopen() throws {
        var db: OrganizerStore? = try store(); try db!.restore(sample())
        let note = try XCTUnwrap(db!.load().records("notes").first)
        try db!.addAttachment(data: Data([5,6,7]), displayName: "new.bin", mimeType: "application/octet-stream", targetType: "note", targetID: note.id)
        let attachment = try XCTUnwrap(db!.load().records("attachments").last)
        db = nil; db = try store(); XCTAssertEqual(try db!.attachment(attachment.text("storedName")), Data([5,6,7]))
        try db!.mutate { $0.remove("notes", id: note.id) }; XCTAssertThrowsError(try db!.attachment(attachment.text("storedName")))
        XCTAssertEqual(try db!.load().records("money").count, 2)
    }
    func testProjectDeleteRetainsFinancialHistory() throws {
        let db = try store(); try db.restore(sample()); let id = try XCTUnwrap(db.load().records("projects").first).id
        try db.mutate { $0.remove("projects", id: id) }
        XCTAssertEqual(try db.load().records("money").count, 2); XCTAssertTrue(try db.load().records("projectStages").isEmpty)
        XCTAssertFalse(try db.load().records("money").contains { $0.fields["projectId"] != nil })
    }
    func testDecimalMoneyAndInput() throws {
        XCTAssertEqual(Money.parse("19,95"), 19.95); XCTAssertNil(Money.parse("NaN")); XCTAssertNil(Money.parse("1e20")); XCTAssertNil(Money.parse("-5")); XCTAssertNil(Money.parse("0"))
        XCTAssertEqual(Money.total([OrganizerRecord(["type": .string("income"), "amount": .number(0.1)]), OrganizerRecord(["type": .string("income"), "amount": .number(0.2)])]), Decimal(string: "0.3"))
    }
    func testExistingUnrecognizedDatabaseNeverResets() throws {
        try Data("not a database".utf8).write(to: directory.appendingPathComponent("test.sqlite"))
        XCTAssertThrowsError(try store()); XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent("test.sqlite")), Data("not a database".utf8))
    }
    func testLateAttachmentCannotMoveToRestoredOwnerWithReusedID() throws {
        let db = try store(); try db.restore(sample())
        let owner = try XCTUnwrap(db.load().records("notes").first)
        var incoming = try sample(), notes = incoming.records("notes"); notes[0].set("createdAt", owner.int("createdAt") + 1); incoming.setRecords("notes", notes)
        try db.restore(incoming); let before = try db.fullSnapshot()
        XCTAssertThrowsError(try db.addAttachment(data: Data([9]), displayName: "late.bin", mimeType: "application/octet-stream", targetType: "note", targetID: owner.id, expectedOwnerCreatedAt: owner.int("createdAt")))
        XCTAssertEqual(try db.fullSnapshot(), before)
    }
}
