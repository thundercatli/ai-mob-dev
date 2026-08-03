import UIKit
import XCTest
@testable import AiDevMob

@MainActor
final class TerminalMenuTests: XCTestCase {
    func testTmuxTerminalMenuContainsWindowActions() throws {
        let controller = TerminalViewController(config: makeConfig(tmuxSession: "work"))
        controller.loadViewIfNeeded()

        let titles = try menuTitles(in: controller)
        XCTAssertTrue(titles.contains("显示键盘"))
        XCTAssertTrue(titles.contains("浏览文件"))
        XCTAssertTrue(titles.contains("新建窗口"))
        XCTAssertTrue(titles.contains("上一个窗口"))
        XCTAssertTrue(titles.contains("下一个窗口"))
        XCTAssertTrue(titles.contains("窗口列表"))
        XCTAssertTrue(titles.contains("重命名窗口"))
        XCTAssertTrue(titles.contains("重新连接"))
        XCTAssertTrue(titles.contains("断开并返回"))
    }

    func testPlainShellMenuOmitsTmuxActions() throws {
        let controller = TerminalViewController(config: makeConfig(tmuxSession: ""))
        controller.loadViewIfNeeded()

        let titles = try menuTitles(in: controller)
        XCTAssertFalse(titles.contains("新建窗口"))
        XCTAssertFalse(titles.contains("窗口列表"))
        XCTAssertFalse(titles.contains("重命名窗口"))
        XCTAssertTrue(titles.contains("重新连接"))
    }

    private func menuTitles(in controller: UIViewController) throws -> [String] {
        let button = try XCTUnwrap(findButton(label: "更多操作", in: controller.view))
        let menu = try XCTUnwrap(button.menu)
        return flatten(menu.children)
    }

    private func findButton(label: String, in view: UIView) -> UIButton? {
        if let button = view as? UIButton, button.accessibilityLabel == label { return button }
        for subview in view.subviews {
            if let match = findButton(label: label, in: subview) { return match }
        }
        return nil
    }

    private func flatten(_ elements: [UIMenuElement]) -> [String] {
        elements.flatMap { element -> [String] in
            if let menu = element as? UIMenu { return flatten(menu.children) }
            return [element.title]
        }
    }

    private func makeConfig(tmuxSession: String) -> ConnectionConfig {
        ConnectionConfig(
            id: "menu-test",
            name: "Menu test",
            host: "example.com",
            port: 22,
            username: "dev",
            authMethod: .password,
            password: "secret",
            privateKeyPem: nil,
            privateKeyPassphrase: nil,
            tmuxSession: tmuxSession
        )
    }
}
