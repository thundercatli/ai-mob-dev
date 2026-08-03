import XCTest

final class ConnectionCredentialUITests: XCTestCase {
    func testCreatesCredentialFromConnectionEditor() {
        let app = XCUIApplication()
        app.launch()
        openConnections(in: app)

        let addConnection = app.buttons["新建连接"]
        XCTAssertTrue(addConnection.waitForExistence(timeout: 3))
        addConnection.tap()
        XCTAssertTrue(app.navigationBars["新建连接"].waitForExistence(timeout: 3))

        let addCredential = app.buttons["新建凭证"]
        XCTAssertTrue(addCredential.waitForExistence(timeout: 3))
        addCredential.tap()

        let credentialNavigationBar = app.navigationBars["新建凭证"]
        XCTAssertTrue(credentialNavigationBar.waitForExistence(timeout: 3))
        XCTAssertTrue(app.textFields["名称"].exists)
        XCTAssertTrue(app.textFields["用户名"].exists)
        XCTAssertTrue(app.secureTextFields["密码"].exists)
        XCTAssertTrue(credentialNavigationBar.buttons["保存"].isHittable)
    }

    private func openConnections(in app: XCUIApplication) {
        let connectionsTab = app.tabBars.buttons["连接"]
        if connectionsTab.waitForExistence(timeout: 2) {
            connectionsTab.tap()
        } else {
            let connectionsSidebarItem = app.buttons["连接"].firstMatch
            XCTAssertTrue(connectionsSidebarItem.waitForExistence(timeout: 3))
            connectionsSidebarItem.tap()
        }
    }
}
