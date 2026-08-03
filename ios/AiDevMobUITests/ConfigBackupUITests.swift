import XCTest

final class ConfigBackupUITests: XCTestCase {
    func testBackupPassphraseSheetFitsSettingsFlow() {
        let app = XCUIApplication()
        app.launch()
        openSettings(in: app)

        let backupHeader = app.staticTexts["配置备份"]
        scrollToElement(backupHeader, in: app)
        XCTAssertTrue(backupHeader.isHittable)
        backupHeader.tap()

        let exportButton = app.buttons["导出"]
        scrollToElement(exportButton, in: app)
        XCTAssertTrue(exportButton.isHittable)
        exportButton.tap()

        XCTAssertTrue(app.navigationBars["导出备份"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.secureTextFields.count, 2)
        XCTAssertTrue(app.buttons["取消"].isHittable)
        XCTAssertTrue(app.buttons["确认"].isHittable)
    }

    private func openSettings(in app: XCUIApplication) {
        let settingsTab = app.tabBars.buttons["设置"]
        if settingsTab.waitForExistence(timeout: 2) {
            settingsTab.tap()
        } else {
            let settingsSidebarItem = app.buttons["设置"].firstMatch
            XCTAssertTrue(settingsSidebarItem.waitForExistence(timeout: 3))
            settingsSidebarItem.tap()
        }
    }

    private func scrollToElement(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<4 where !element.isHittable {
            app.swipeUp()
        }
    }
}
