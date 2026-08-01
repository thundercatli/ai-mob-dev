import SwiftUI

/// Root of the app. Forks by horizontal size class so iPhone stays exactly as it was (TabView +
/// full-screen terminal) while iPad gets a two-column split layout (persistent sidebar + terminal
/// detail that never covers the sidebar).
///
/// Both branches share the same `AppCoordinator` — only the presentation container differs. The
/// coordinator owns the terminal VC for the whole session; SwiftUI merely observes it.
struct RootView: View {
    @StateObject var coordinator: AppCoordinator
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                IPadShell(coordinator: coordinator)
            } else {
                CompactShell(coordinator: coordinator)
            }
        }
        // Global error alert — surfaced by the coordinator on host-key mismatch, empty creds, etc.
        .alert(item: $coordinator.errorMessage) { err in
            Alert(title: Text(err.title), message: Text(err.message))
        }
    }
}

// MARK: - iPhone (compact)

/// The original layout: a 4-tab `TabView`, with the terminal presented as a full-screen cover
/// when a connection is opened. Visually identical to the previous UIKit push (full-screen
/// terminal with its own back chevron), but driven through SwiftUI state.
struct CompactShell: View {
    @ObservedObject var coordinator: AppCoordinator

    var body: some View {
        MainTabView(onConnect: { config in coordinator.connect(config) })
            .fullScreenCover(item: $coordinator.activeTerminal) { host in
                TerminalHostingView(vc: host.vc)
                    // A cover ignores safe areas by default; the terminal VC manages its own
                    // top bar + safe-area insets, so let it own the full screen.
                    .ignoresSafeArea()
            }
    }
}

// MARK: - iPad (regular)

/// Two-column `NavigationSplitView`: a persistent sidebar (category menu → list) on the left and
/// the terminal (or a placeholder) in the detail column on the right. Opening a connection fills
/// the detail column WITHOUT dismissing the sidebar, so the user can switch connections or edit
/// tunnels while a terminal stays live.
struct IPadShell: View {
    @ObservedObject var coordinator: AppCoordinator

    var body: some View {
        NavigationSplitView {
            SidebarView(coordinator: coordinator)
        } detail: {
            DetailColumn(coordinator: coordinator)
        }
        .navigationSplitViewStyle(.balanced)
    }
}

/// Top-level management categories shown in the sidebar. Mirrors the four iPhone tabs.
enum ManagementCategory: String, CaseIterable, Identifiable {
    case connections
    case credentials
    case tunnels
    case servers

    var id: String { rawValue }

    var label: String {
        switch self {
        case .connections:  return "连接"
        case .credentials:  return "凭证"
        case .tunnels:      return "隧道"
        case .servers:      return "服务器"
        }
    }

    var systemImage: String {
        switch self {
        case .connections:  return "terminal"
        case .credentials:  return "key"
        case .tunnels:      return "network"
        case .servers:      return "server.rack"
        }
    }
}

/// Sidebar: a `NavigationStack` whose root is the category menu. Selecting a category pushes the
/// matching list view into the sidebar's own navigation (NOT the detail column), so the terminal
/// in the detail column is undisturbed while the user browses connections/credentials/tunnels.
///
/// The list views are reused from `ManagementViews` with `embeddedInSplit: true` so they skip
/// their own outer `NavigationStack` (this sidebar already provides one).
struct SidebarView: View {
    @ObservedObject var coordinator: AppCoordinator

    var body: some View {
        NavigationStack {
            List(ManagementCategory.allCases) { cat in
                NavigationLink(value: cat) {
                    Label(cat.label, systemImage: cat.systemImage)
                }
            }
            .navigationTitle("AiDevMob")
            .navigationDestination(for: ManagementCategory.self) { cat in
                categoryListView(for: cat)
                    .navigationTitle(cat.label)
                    .navigationBarTitleDisplayMode(.inline)
            }
        }
        // Keep the sidebar narrow — it's a menu, not a content pane.
        .navigationSplitViewColumnWidth(min: 260, ideal: 300, max: 380)
    }

    @ViewBuilder
    private func categoryListView(for cat: ManagementCategory) -> some View {
        switch cat {
        case .connections:
            // ConnectionListView drives the detail column via coordinator.connect.
            ConnectionListView(onConnect: { config in coordinator.connect(config) }, embeddedInSplit: true)
        case .credentials:
            CredentialListView(embeddedInSplit: true)
        case .tunnels:
            TunnelListView(embeddedInSplit: true)
        case .servers:
            ServerListView(embeddedInSplit: true)
        }
    }
}

/// Right-hand detail column: shows the live terminal when a connection is active, otherwise a
/// placeholder hint.
struct DetailColumn: View {
    @ObservedObject var coordinator: AppCoordinator

    var body: some View {
        if let host = coordinator.activeTerminal {
            TerminalHostingView(vc: host.vc)
                .ignoresSafeArea()
        } else {
            VStack(spacing: 12) {
                Image(systemName: "terminal")
                    .font(.system(size: 56, weight: .light))
                    .foregroundColor(.secondary)
                Text("从左侧选择一个连接")
                    .font(.title3)
                    .foregroundColor(.secondary)
                Text("配置好凭证与隧道后，点击连接即在右侧打开终端。")
                    .font(.callout)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.systemBackground))
        }
    }
}
