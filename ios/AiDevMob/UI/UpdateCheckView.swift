import SwiftUI

struct UpdateCheckView: View {
    @Environment(\.openURL) private var openURL

    private let currentVersion: String
    private let currentBuild: String

    init(bundle: Bundle = .main) {
        currentVersion = bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        currentBuild = bundle.infoDictionary?["CFBundleVersion"] as? String ?? "?"
    }

    var body: some View {
        Form {
            Section("当前版本") {
                LabeledContent("版本", value: currentVersion)
                LabeledContent("构建", value: currentBuild)
            }

            Section {
                Label("版本更新已禁用", systemImage: "nosign")
                    .foregroundStyle(.secondary)
                Text("当前版本为 iOS 侧载版本。iOS 不允许应用自行下载并覆盖安装自身；自动更新只能由 App Store、TestFlight 或设备管理渠道完成。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } footer: {
                Text("如需升级，请从发布页获取新版本，并使用与当前安装相同的签名方式安装。")
            }

            Section {
                Button {
                    openURL(UpdateChecker.releasesURL)
                } label: {
                    Label("打开发布页", systemImage: "safari")
                }
            }

        }
        .navigationTitle("应用更新")
    }
}
