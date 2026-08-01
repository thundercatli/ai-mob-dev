import UIKit
import SwiftUI

/// App entry point. UIKit lifecycle (the terminal is a UIKit `TerminalView`; the management
/// screens are SwiftUI hosted in a `UIHostingController`). A `@main`-equivalent is required
/// or the linker reports `_main` undefined.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let coordinator = AppCoordinator()
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = UIHostingController(rootView: RootView(coordinator: coordinator))
        window?.makeKeyAndVisible()
        return true
    }
}
