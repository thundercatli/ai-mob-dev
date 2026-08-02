import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// The settings screen — iOS counterpart of Android's `SettingsFragment`.
///
/// Exposes terminal preferences (font size, keep-screen-on, tmux prefix, swipe-to-switch-windows),
/// plus an About section and a Help section. All settings are read/written through `SettingsStore`
/// (UserDefaults-backed) and take effect the next time a terminal is opened (font size) or
/// immediately (tmux prefix, swipe, keep-screen-on).
struct SettingsView: View {
    /// When embedded in the iPad sidebar detail, the sidebar owns the NavigationStack; this view
    /// then skips its own outer NavigationStack and navigationTitle. Defaults to false.
    var embeddedInSplit: Bool = false

    private let store = SettingsStore.shared

    var body: some View {
        if embeddedInSplit {
            content
        } else {
            NavigationStack { content.navigationTitle("设置") }
        }
    }

    @ViewBuilder
    private var content: some View {
        Form {
            terminalSection
            aboutSection
            helpSection
        }
    }

    // MARK: - Terminal settings

    @ViewBuilder
    private var terminalSection: some View {
        Section {
            // Font-size stepper: − / value / +, mirroring Android's buttonFontSmaller/Bigger.
            HStack {
                Button {
                    store.terminalFontSize -= 1
                } label: {
                    Image(systemName: "minus.circle.fill")
                        .font(.title2)
                }
                .buttonStyle(.borderless)
                .disabled(store.terminalFontSize <= SettingsStore.minFontSize)

                Spacer()

                Text("\(store.terminalFontSize)")
                    .font(.headline.monospacedDigit())

                Spacer()

                Button {
                    store.terminalFontSize += 1
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.title2)
                }
                .buttonStyle(.borderless)
                .disabled(store.terminalFontSize >= SettingsStore.maxFontSize)
            }
            .padding(.vertical, 2)

            Toggle("保持屏幕常亮", isOn: binding(.keepScreenOn))

            Picker("tmux 前缀键", selection: binding(.tmuxPrefix)) {
                ForEach(prefixOptions(), id: \.self) { letter in
                    Text("Ctrl-\(String(letter).uppercased())").tag(PrefixOption(letter))
                }
            }

            Toggle("滑动切换 tmux 窗口", isOn: binding(.swipeSwitchesWindows))
        } header: {
            Text("终端")
        } footer: {
            Text("字号在下次打开终端时生效；其余设置即时生效。滑动切换：在终端上左滑下一个窗口，右滑上一个窗口。")
        }
    }

    // MARK: - About

    @ViewBuilder
    private var aboutSection: some View {
        Section("关于") {
            aboutRow("版本", appVersion())
            aboutRow("设备", deviceModel())
            aboutRow("系统", systemVersion())
            aboutRow("终端引擎", "Termux terminal-view/emulator (Apache-2.0) → SwiftTerm")
            aboutRow("隧道", "bundled frpc (STCP visitor)")
            aboutRow("SSH", "Citadel (swift-nio-ssh)")
        }
    }

    // MARK: - Help

    @ViewBuilder
    private var helpSection: some View {
        Section("帮助") {
            helpBlock("配置顺序",
                "凭证 → 隧道 → 连接。先创建凭证（用户名 + 密码/私钥），再创建隧道（关联 frps 服务器），最后创建连接（选凭证和隧道）。")
            helpBlock("tmux 会话",
                "在连接编辑里点「探测 tmux」可列出远端已有会话。使用 tmux 后，断线重连会自动恢复会话；不用 tmux 则断线即丢失 shell。")
            helpBlock("按键",
                "ESC/CTRL/方向键/TAB 在终端下方的按键行。CTRL 是粘滞键——点一下高亮，再按字母发出 Ctrl-字母。按键行可左右滑动查看更多。")
            helpBlock("排错",
                "连不上时：检查隧道是否运行（隧道页状态）→ 检查凭证是否正确 → 确认远端 sshd 可达。终端状态栏可点击重连。")
        }
    }

    // MARK: - Helpers

    /// Wraps the shared SettingsStore in a binding so Form controls read/write it directly.
    private func binding(_ keyPath: ReferenceWritableKeyPath<SettingsStore, Bool>) -> Binding<Bool> {
        Binding(
            get: { store[keyPath: keyPath] },
            set: { store[keyPath: keyPath] = $0 }
        )
    }

    private func binding(_ keyPath: ReferenceWritableKeyPath<SettingsStore, Character>) -> Binding<PrefixOption> {
        Binding(
            get: { PrefixOption(store[keyPath: keyPath]) },
            set: { store[keyPath: keyPath] = $0.letter }
        )
    }

    private func prefixOptions() -> [PrefixOption] {
        ("a"..."z").map { PrefixOption($0) }
    }

    private func aboutRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundColor(.secondary)
            Spacer()
            Text(value)
                .foregroundColor(.primary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    private func helpBlock(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.subheadline.bold())
            Text(body).font(.caption).foregroundColor(.secondary)
        }
        .padding(.vertical, 2)
    }

    private func appVersion() -> String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    private func deviceModel() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let mirror = Mirror(reflecting: systemInfo.machine)
        let identifier = mirror.children.reduce("") { $0 + String(describing: $1.value) }
        return identifier.isEmpty ? UIDevice.current.model : identifier
    }

    private func systemVersion() -> String {
        "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)"
    }
}

/// Wrapper so `Character` can be used in a `Picker` selection (Character isn't Identifiable).
private struct PrefixOption: Hashable {
    let letter: Character
    init(_ letter: Character) { self.letter = letter }
}
