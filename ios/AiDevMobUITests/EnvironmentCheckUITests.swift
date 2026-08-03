import XCTest

final class EnvironmentCheckUITests: XCTestCase {
    func testEnvironmentCheckFromSettings() {
        let app = XCUIApplication()
        app.launch()
        openSettings(in: app)

        let diagnostics = app.staticTexts["环境自检"]
        scrollToElement(diagnostics, in: app)
        XCTAssertTrue(diagnostics.isHittable)
        diagnostics.tap()

        XCTAssertTrue(app.navigationBars["环境自检"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["重新检测"].exists)
        XCTAssertTrue(app.staticTexts["检测结果"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["frpc 运行时"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["配置完整性"].exists)
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
