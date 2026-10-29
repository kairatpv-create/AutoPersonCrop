import XCTest

// Store presentation capture runs only on a disposable simulator, using actual UI flows.
// No JSON fixtures are injected; records are created through the public interface.
final class StoreScreenshots: OrganizerUITestCase {
    func testRussianStoreScreens() throws {
        try launch(language: "ru", seed: false)
        tap("createButton")
        let title = textInput("noteTitle")
        title.tap()
        title.typeText("План на неделю")
        let body = app.textViews["noteBody"]
        XCTAssertTrue(body.waitForExistence(timeout: 10))
        body.tap()
        body.typeText("Подготовить встречу\nПроверить бюджет\nЗакончить важные дела")
        tap("saveNote")
        XCTAssertTrue(app.staticTexts["План на неделю"].waitForExistence(timeout: 10))
        screenshot("ru-store-01-notes")

        tap("tab-projects")
        tap("createButton")
        let project = textInput("projectName")
        project.tap()
        project.typeText("Ремонт квартиры")
        tap("saveProject")
        XCTAssertTrue(app.staticTexts["Ремонт квартиры"].waitForExistence(timeout: 10))
        screenshot("ru-store-02-projects")

        tap("tab-money")
        let income = app.buttons["Доход"]
        XCTAssertTrue(income.waitForExistence(timeout: 10))
        income.tap()
        let amount = app.textFields["moneyInput"]
        XCTAssertTrue(amount.waitForExistence(timeout: 10))
        amount.tap()
        amount.typeText("85000")
        let category = app.textFields["moneyCategory"]
        category.tap()
        category.typeText("Работа")
        tap("saveMoney")

        let expense = app.buttons["Расход"]
        XCTAssertTrue(expense.waitForExistence(timeout: 10))
        expense.tap()
        let outAmount = app.textFields["moneyInput"]
        XCTAssertTrue(outAmount.waitForExistence(timeout: 10))
        outAmount.tap()
        outAmount.typeText("12500")
        let outCategory = app.textFields["moneyCategory"]
        outCategory.tap()
        outCategory.typeText("Покупки")
        tap("saveMoney")
        screenshot("ru-store-03-finance")

        tap("finance-tab-3")
        screenshot("ru-store-04-analytics")

        tap("tab-settings")
        screenshot("ru-store-05-settings")
    }
}
