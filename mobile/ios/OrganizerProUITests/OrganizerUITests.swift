import XCTest

class OrganizerUITestCase: XCTestCase {
    var app: XCUIApplication!
    var session = UUID().uuidString
    override func setUpWithError() throws { continueAfterFailure = false }
    func launch(language: String = "en", dark: Bool = false, large: Bool = false, pin: Bool = false, seed: Bool = true, importData: Data? = nil) throws {
        if app == nil { app = XCUIApplication() }
        app.launchArguments = ["-ui-testing", "-test-session", session, "-test-language", language]
        if dark { app.launchArguments.append("-test-dark") }; if large { app.launchArguments.append("-test-large") }; if pin { app.launchArguments.append("-test-pin") }
        if seed {
            let url = try XCTUnwrap(Bundle(for: OrganizerUITestCase.self).url(forResource: "android-v6", withExtension: "json", subdirectory: "Fixtures"))
            app.launchEnvironment["ORGANIZER_UI_FIXTURE"] = try Data(contentsOf: url).base64EncodedString()
        } else { app.launchEnvironment.removeValue(forKey: "ORGANIZER_UI_FIXTURE") }
        if let importData { app.launchEnvironment["ORGANIZER_UI_IMPORT"] = importData.base64EncodedString() } else { app.launchEnvironment.removeValue(forKey: "ORGANIZER_UI_IMPORT") }
        app.launch()
        screenshot("launch-\(language)-\(dark ? "dark" : "light")-\(large ? "large" : "normal")")
    }
    func tap(_ id: String) { let element = app.buttons[id]; XCTAssertTrue(element.waitForExistence(timeout: 10), id); XCTAssertTrue(element.isHittable, "Covered or offscreen: " + id); element.tap() }
    func textInput(_ id: String) -> XCUIElement {
        let element = app.descendants(matching: .any).matching(identifier: id).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: 5), id)
        if element.elementType == .textField || element.elementType == .textView { return element }
        return app.textViews[id].exists ? app.textViews[id] : app.textFields[id]
    }
    func screenshot(_ name: String) { let item = XCTAttachment(screenshot: app.screenshot()); item.name = name; item.lifetime = .keepAlways; add(item) }
}
final class OrganizerUITests: OrganizerUITestCase {
    func testNoteDraftSaveAndRelaunch() throws {
        try launch(); tap("createButton")
        let title = textInput("noteTitle"), body = app.textViews["noteBody"]
        XCTAssertTrue(title.waitForExistence(timeout: 5)); title.tap(); title.typeText("iPhone test note")
        body.tap(); body.typeText("Synthetic draft survives closing")
        app.buttons["Close"].tap(); tap("createButton")
        XCTAssertEqual(title.value as? String, "iPhone test note"); XCTAssertEqual(body.value as? String, "Synthetic draft survives closing")
        tap("saveNote"); XCTAssertTrue(app.staticTexts["iPhone test note"].waitForExistence(timeout: 5))
        app.terminate(); try launch(seed: false)
        XCTAssertTrue(app.staticTexts["iPhone test note"].waitForExistence(timeout: 8)); screenshot("en-notes-created")
    }
    func testProjectAndFinanceCreation() throws {
        try launch(); tap("tab-projects"); tap("createButton")
        let field = textInput("projectName"); field.tap(); field.typeText("iPhone synthetic project"); tap("saveProject")
        XCTAssertTrue(app.staticTexts["iPhone synthetic project"].waitForExistence(timeout: 5)); screenshot("en-projects-created")
        tap("tab-money"); tap("createButton")
        let amount = app.textFields["moneyInput"]; XCTAssertTrue(amount.waitForExistence(timeout: 5)); amount.tap(); amount.typeText("19.95")
        let category = app.textFields["moneyCategory"]; category.tap(); category.typeText("iPhone expense"); tap("saveMoney")
        XCTAssertTrue(app.staticTexts["iPhone expense"].waitForExistence(timeout: 5)); screenshot("en-finances-created")
    }
    func testPINWindowProtectsOpenEditorAfterBackground() throws {
        try launch(pin: true)
        let pin = app.secureTextFields["unlockPIN"]; XCTAssertTrue(pin.waitForExistence(timeout: 8)); pin.tap(); pin.typeText("2468"); tap("unlockButton")
        tap("createButton"); let title = textInput("noteTitle"); title.tap(); title.typeText("Protected draft")
        XCUIDevice.shared.press(.home); app.activate()
        XCTAssertTrue(pin.waitForExistence(timeout: 8)); XCTAssertFalse(title.isHittable); screenshot("pin-covers-editor")
        pin.tap(); pin.typeText("0000"); tap("unlockButton"); XCTAssertTrue(app.staticTexts["Incorrect PIN code"].waitForExistence(timeout: 5))
        pin.tap(); pin.typeText("2468"); tap("unlockButton"); XCTAssertTrue(title.waitForExistence(timeout: 5)); XCTAssertEqual(title.value as? String, "Protected draft")
    }
    func testSettingsHaveTwoBackupActionsWithoutAccountOrArchive() throws {
        try launch(); tap("tab-settings")
        let save = app.buttons["saveBackup"], restore = app.buttons["restoreBackup"]
        if !save.isHittable { app.swipeUp() }; XCTAssertTrue(save.waitForExistence(timeout: 5)); XCTAssertTrue(restore.exists)
        XCTAssertFalse(app.staticTexts["Account and sync"].exists); XCTAssertFalse(app.staticTexts["Archive"].exists); XCTAssertFalse(app.staticTexts["Buttone text size"].exists)
        screenshot("en-settings-local-backups")
        save.tap(); XCTAssertTrue(app.buttons["Send by e-mail"].waitForExistence(timeout: 5)); XCTAssertTrue(app.buttons["Save to Files"].exists)
    }
    func testIncompleteAttachmentShowsErrorAndPreservesRecords() throws {
        try launch(importData: Data(#"{"notes":[]}"#.utf8))
        let message = app.staticTexts["The backup is damaged, incomplete or unsupported. Your existing data and attachments have not been changed."]
        XCTAssertTrue(message.waitForExistence(timeout: 10)); screenshot("en-incomplete-import-refused"); app.buttons["OK"].tap()
        tap("tab-money"); XCTAssertTrue(app.staticTexts["Synthetic expense"].waitForExistence(timeout: 5))
    }
    func testValidEmptyBackupNeedsExplicitConfirmation() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "android-v6", withExtension: "json", subdirectory: "Fixtures"))
        var backup = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        for key in ["notes","tasks","projects","projectStages","projectJournal","projectNoteLinks","money","reminders","attachments"] { backup[key] = [] as [Any] }
        let data = try JSONSerialization.data(withJSONObject: backup)
        try launch(importData: data)
        XCTAssertTrue(app.buttons["confirmRestore"].waitForExistence(timeout: 10)); screenshot("en-empty-backup-preview")
        app.buttons["Cancel"].tap(); tap("tab-money"); XCTAssertTrue(app.staticTexts["Synthetic expense"].waitForExistence(timeout: 5))
        app.terminate(); try launch(seed: false, importData: data)
        tap("confirmRestore"); let restore = app.buttons["Restore"].firstMatch; XCTAssertTrue(restore.waitForExistence(timeout: 5)); restore.tap()
        XCTAssertTrue(app.staticTexts["No notes yet"].waitForExistence(timeout: 8)); tap("tab-money"); XCTAssertTrue(app.staticTexts["No entries yet"].waitForExistence(timeout: 5))
    }
}
final class LayoutTests: OrganizerUITestCase {
    func testRussianEnglishLightDarkAndLargeText() throws {
        for (language,dark,large) in [("ru",false,false),("en",false,false),("ru",true,true),("en",true,true)] {
            session = UUID().uuidString; try launch(language: language, dark: dark, large: large)
            for tab in ["notes","projects","money","settings"] {
                tap("tab-" + tab)
                let bar = app.buttons["tab-" + tab]; XCTAssertTrue(bar.isHittable); XCTAssertGreaterThanOrEqual(bar.frame.height, 44)
                screenshot("\(language)-\(dark ? "dark" : "light")-\(large ? "large" : "normal")-\(tab)")
            }
            app.terminate()
        }
    }
}
