import XCTest
import Security
import OrganizerCore
@testable import OrganizerPro

final class IntegrationTests: XCTestCase {
    func fixture() throws -> Data { try Data(contentsOf: XCTUnwrap(Bundle(for: Self.self).url(forResource: "android-v6", withExtension: "json", subdirectory: "Fixtures"))) }
    @MainActor func model() -> AppModel { AppModel(files: FileManager.default.temporaryDirectory.appendingPathComponent("ios-tests-" + UUID().uuidString), vault: PinVault(service: "com.organizerpro.tests." + UUID().uuidString), automaticScheduling: false) }
    @MainActor func testActualDeviceIdentityAndPrivacyManifest() throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.organizerpro.mobile")
        XCTAssertNotNil(Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"))
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "ITSAppUsesNonExemptEncryption") as? Bool, false)
        XCTAssertNotNil(Bundle.main.object(forInfoDictionaryKey: "NSCalendarsFullAccessUsageDescription"))
    }
    @MainActor func testPINSetupVerificationAndDeviceOnlyBackup() throws {
        let m = model(); XCTAssertTrue(m.ready); try m.configurePIN("2468", old: "")
        XCTAssertTrue(m.pinEnabled); m.lock(); XCTAssertTrue(m.locked); XCTAssertFalse(m.unlock("1111")); XCTAssertTrue(m.unlock("2468")); XCTAssertFalse(m.locked)
        let json = String(decoding: try XCTUnwrap(m.store).export(zip: false), as: UTF8.self)
        XCTAssertFalse(json.contains("2468")); XCTAssertFalse(json.contains("device-security")); XCTAssertFalse(json.contains("pin_hash"))
        XCTAssertThrowsError(try m.configurePIN(nil, old: "1111")); XCTAssertTrue(m.pinEnabled)
        try m.configurePIN(nil, old: "2468"); XCTAssertFalse(m.pinEnabled)
    }
    func testPINRateLimitSurvivesVaultRecreation() throws {
        let service = "com.organizerpro.tests." + UUID().uuidString, a = PinVault(service: service)
        try a.set(pin: "2468")
        for _ in 0..<5 { XCTAssertFalse(try a.verify("0000")) }
        let b = PinVault(service: service); XCTAssertGreaterThanOrEqual(try b.retrySeconds(), 29); XCTAssertFalse(try b.verify("2468"))
    }
    func testMalformedKeychainRecordFailsClosed() throws {
        let service = "com.organizerpro.tests." + UUID().uuidString, vault = PinVault(service: service)
        try vault.set(pin: "2468")
        let q: [String: Any] = [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service]
        XCTAssertEqual(SecItemUpdate(q as CFDictionary, [kSecValueData as String:Data("broken".utf8)] as CFDictionary), errSecSuccess)
        XCTAssertThrowsError(try vault.isEnabled()); XCTAssertThrowsError(try vault.verify("2468"))
    }
    @MainActor func testMissingPINRecordWithMarkerNeverUnlocks() throws {
        let m = model(); try XCTUnwrap(m.store).saveDraft(name: "device-security", record: OrganizerRecord(["installed": .bool(true)])); m.refreshPIN()
        XCTAssertTrue(m.pinUnavailable); XCTAssertTrue(m.locked); XCTAssertFalse(m.unlock("2468"))
    }
    @MainActor func testFileImportOnlyPreviewsUntilExplicitRestore() async throws {
        let m = model(), url = m.files.appendingPathComponent("synthetic.json")
        try fixture().write(to: url); let before = try XCTUnwrap(m.store).fullSnapshot()
        m.importBackup(url)
        for _ in 0..<100 { if !m.busy { break }; try await Task.sleep(for: .milliseconds(20)) }
        guard case .backup(let preview) = m.presentation?.kind else { return XCTFail("No validated preview") }
        XCTAssertEqual(try m.store?.fullSnapshot(), before)
        XCTAssertTrue(m.restore(preview)); XCTAssertEqual(m.records("money").count, 2)
        XCTAssertEqual(try m.store?.attachment(m.records("attachments")[0].text("storedName")), Data([0,1,2,127,128,255,10,13]))
    }
    @MainActor func testIncompleteEmailAttachmentLeavesEverythingUntouched() async throws {
        let m = model(); try XCTUnwrap(m.store).restore(BackupCodec.decode(fixture())); m.snapshot = try XCTUnwrap(m.store).load()
        let before = try XCTUnwrap(m.store).fullSnapshot(), url = m.files.appendingPathComponent("incomplete.json")
        try Data(#"{"notes":[]}"#.utf8).write(to: url); m.importBackup(url)
        for _ in 0..<100 { if !m.busy { break }; try await Task.sleep(for: .milliseconds(20)) }
        XCTAssertNil(m.presentation); XCTAssertNotNil(m.notice); XCTAssertEqual(try m.store?.fullSnapshot(), before)
    }
    @MainActor func testDraftAndMoneyPersistAcrossReopen() throws {
        let m = model(); m.draft("note:new", record: OrganizerRecord(["body": .string("Draft after interruption")]))
        let id = m.save("money", record: OrganizerRecord(["type": .string("expense"), "amount": .number(19.95), "category": .string("Test"), "projectName": .string(""), "note": .string(""), "colorKey": .string("yellow")]))
        XCTAssertNotNil(id)
        let reopened = AppModel(files: m.files, vault: m.vault, automaticScheduling: false)
        XCTAssertEqual(reopened.draft("note:new")?.text("body"), "Draft after interruption")
        XCTAssertEqual(reopened.records("money").first?.number("amount"), 19.95)
    }
    @MainActor func testPINIsUnaffectedByBackupRestore() throws {
        let m = model(); try m.configurePIN("2468", old: "")
        let snapshot = try BackupCodec.decode(fixture())
        XCTAssertTrue(m.restore(BackupPreview(snapshot: snapshot, name: "Synthetic")))
        XCTAssertTrue(m.pinEnabled); m.lock(); XCTAssertTrue(m.unlock("2468"))
    }
    @MainActor func testStageProgressIsAtomicAndJournalDeleteDoesNotChangeIt() throws {
        let m = model(); try XCTUnwrap(m.store).restore(BackupCodec.decode(fixture())); m.snapshot = try XCTUnwrap(m.store).load()
        var stage = try XCTUnwrap(m.records("projectStages").first); stage.set("done", true)
        XCTAssertNotNil(m.save("projectStages", record: stage)); XCTAssertEqual(m.records("projects").first?.int("progress"), 100)
        m.remove("projectJournal", id: try XCTUnwrap(m.records("projectJournal").first).id)
        XCTAssertEqual(m.records("projects").first?.int("progress"), 100)
        m.remove("projectStages", id: stage.id); XCTAssertEqual(m.records("projects").first?.int("progress"), 0)
    }
}
