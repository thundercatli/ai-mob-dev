import SwiftUI
import UIKit

/// Bridges the UIKit `TerminalViewController` into SwiftUI. The view controller is created and
/// owned by `AppCoordinator` for the lifetime of the session — this wrapper only hands the
/// existing instance to SwiftUI and never recreates it.
///
/// Recreating the VC on every SwiftUI re-render would tear down the live SSH channel and reset
/// the terminal buffer; that's why `makeUIViewController` returns the instance passed in at init
/// and `updateUIViewController` is a no-op.
struct TerminalHostingView: UIViewControllerRepresentable {
    let viewController: TerminalViewController

    init(vc: TerminalViewController) {
        self.viewController = vc
    }

    func makeUIViewController(context: Context) -> TerminalViewController {
        viewController
    }

    func updateUIViewController(_ uiViewController: TerminalViewController, context: Context) {
        // Intentionally empty — the coordinator owns the VC and its state.
    }
}
