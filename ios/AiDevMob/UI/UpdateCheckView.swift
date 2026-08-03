import SwiftUI

struct UpdateCheckView: View {
    @Environment(\.openURL) private var openURL

    @State private var token = UpdateTokenStore.token ?? ""
    @State private var outcome: UpdateChecker.Outcome?
    @State private var isChecking = false

    private let checker: UpdateChecker
    private let currentVersion: String
    private let currentBuild: String

    init(checker: UpdateChecker = UpdateChecker(), bundle: Bundle = .main) {
        self.checker = checker
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
                SecureField("GitHub token（可选）", text: $token)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } footer: {
                Text("公开仓库无需 token；仅在遇到 GitHub 匿名请求频率限制时填写。token 保存在钥匙串中。")
            }

            Section {
                Button {
                    checkForUpdate()
                } label: {
                    if isChecking {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("正在查询…")
                        }
                    } else {
                        Label("检查更新", systemImage: "arrow.clockwise")
                    }
                }
                .disabled(isChecking)

                Button {
                    openURL(UpdateChecker.releasesURL)
                } label: {
                    Label("打开发布页", systemImage: "safari")
                }
            }

            resultSection
        }
        .navigationTitle("应用更新")
    }

    @ViewBuilder
    private var resultSection: some View {
        if let outcome {
            Section {
                switch outcome {
                case .upToDate(let latestVersion):
                    Label("已是最新版本", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                    LabeledContent("最新发布", value: latestVersion)

                case .failed(let message):
                    Label("查询失败", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.secondary)

                case .updateAvailable(let release):
                    Label("发现新版本 \(release.version)", systemImage: "arrow.down.circle.fill")
                        .foregroundStyle(.orange)
                    LabeledContent("版本", value: "\(currentVersion) → \(release.version)")
                    if let notes = release.notes {
                        Text(notes)
                            .font(.footnote)
                            .textSelection(.enabled)
                    }
                    Button {
                        openURL(release.pageURL)
                    } label: {
                        Label("查看新版本", systemImage: "safari")
                    }
                }
            } header: {
                Text("查询结果")
            } footer: {
                Text("iOS 不允许应用自行覆盖安装。请从发布页取得新版本，并使用与当前安装相同的签名方式更新。")
            }
        }
    }

    private func checkForUpdate() {
        guard !isChecking else { return }
        UpdateTokenStore.token = token
        isChecking = true
        outcome = nil

        Task {
            let result = await checker.check(
                currentVersion: currentVersion,
                token: UpdateTokenStore.token
            )
            await MainActor.run {
                outcome = result
                isChecking = false
            }
        }
    }
}
