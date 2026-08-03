import SwiftUI

@MainActor
private final class EnvironmentCheckViewModel: ObservableObject {
    @Published private(set) var results: [EnvironmentCheck.Result] = []
    @Published private(set) var isRunning = false
    @Published private(set) var errorMessage: String?

    var summary: String {
        guard !results.isEmpty else { return "尚未检测" }
        let failures = results.count { $0.status == .failure }
        let warnings = results.count { $0.status == .warning }
        if failures > 0 { return "\(failures) 项失败，\(warnings) 项警告" }
        if warnings > 0 { return "\(warnings) 项警告，其余正常" }
        return "全部正常（共 \(results.count) 项）"
    }

    func run() async {
        guard !isRunning else { return }
        isRunning = true
        errorMessage = nil
        defer { isRunning = false }
        results = await EnvironmentCheck.run()
    }
}

struct EnvironmentCheckView: View {
    @StateObject private var model = EnvironmentCheckViewModel()

    var body: some View {
        Form {
            Section {
                if model.isRunning && model.results.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("正在检测...")
                    }
                } else if let errorMessage = model.errorMessage {
                    Label(errorMessage, systemImage: "xmark.circle.fill")
                        .foregroundStyle(.red)
                } else {
                    Label(model.summary, systemImage: summaryIcon)
                        .foregroundStyle(summaryColor)
                }
            }

            Section("检测结果") {
                ForEach(model.results) { result in
                    EnvironmentResultRow(result: result)
                }
            }
        }
        .navigationTitle("环境自检")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    Task { await model.run() }
                } label: {
                    if model.isRunning {
                        ProgressView()
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .disabled(model.isRunning)
                .accessibilityLabel("重新检测")
            }
        }
        .task {
            if model.results.isEmpty { await model.run() }
        }
    }

    private var worstStatus: EnvironmentCheck.Status {
        model.results.map(\.status).max() ?? .ok
    }

    private var summaryIcon: String {
        switch worstStatus {
        case .ok: return "checkmark.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        case .failure: return "xmark.circle.fill"
        }
    }

    private var summaryColor: Color {
        switch worstStatus {
        case .ok: return .green
        case .warning: return .orange
        case .failure: return .red
        }
    }
}

private struct EnvironmentResultRow: View {
    let result: EnvironmentCheck.Result

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .foregroundStyle(color)
                .frame(width: 22)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(result.title)
                    .font(.subheadline.weight(.semibold))
                Text(result.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(statusLabel)，\(result.title)，\(result.detail)")
    }

    private var icon: String {
        switch result.status {
        case .ok: return "checkmark.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        case .failure: return "xmark.circle.fill"
        }
    }

    private var color: Color {
        switch result.status {
        case .ok: return .green
        case .warning: return .orange
        case .failure: return .red
        }
    }

    private var statusLabel: String {
        switch result.status {
        case .ok: return "正常"
        case .warning: return "警告"
        case .failure: return "失败"
        }
    }
}
