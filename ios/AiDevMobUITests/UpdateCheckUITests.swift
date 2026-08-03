import XCTest

final class UpdateCheckUITests: XCTestCase {
    func testUpdateCheckFitsSettingsFlow() {
        let app = XCUIApplication()
        app.launch()
        openSettings(in: app)

        let updateEntry = app.staticTexts["应用更新"]
        scrollToElement(updateEntry, in: app)
        XCTAssertTrue(updateEntry.isHittable)
        updateEntry.tap()

        XCTAssertTrue(app.navigationBars["应用更新"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["当前版本"].exists)
        XCTAssertTrue(app.secureTextFields["GitHub token（可选）"].exists)
        XCTAssertTrue(app.buttons["检查更新"].isHittable)
        XCTAssertTrue(app.buttons["打开发布页"].isHittable)

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Update check settings"
        screenshot.lifetime = .keepAlways
        add(screenshot)
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
        for _ in 0..<5 where !element.isHittable {
            app.swipeUp()
        }
    }
}
