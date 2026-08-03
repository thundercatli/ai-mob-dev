import SwiftUI
import XCTest
@testable import AiDevMob

final class IPadShellTests: XCTestCase {
    func testTerminalUsesDetailOnlyVisibility() {
        XCTAssertEqual(
            IPadShell.preferredColumnVisibility(hasActiveTerminal: true),
            .detailOnly
        )
    }

    func testEmptyDetailRestoresSidebar() {
        XCTAssertEqual(
            IPadShell.preferredColumnVisibility(hasActiveTerminal: false),
            .all
        )
    }
}
