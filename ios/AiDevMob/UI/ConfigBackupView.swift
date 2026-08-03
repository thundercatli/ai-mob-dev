import SwiftUI
import UniformTypeIdentifiers

private enum BackupPassphraseMode: String, Identifiable {
    case export
    case restore

    var id: String { rawValue }
    var title: String { self == .export ? "导出备份" : "恢复备份" }
}

private struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json, .data] }
    static var writableContentTypes: [UTType] { [.json] }

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

@MainActor
private final class ConfigBackupViewModel: ObservableObject {
    @Published private(set) var isBusy = false
    @Published private(set) var status = "卸载会删除所有配置与设备密钥。"

    func makeBackup(passphrase: String) async -> Data? {
        isBusy = true
        status = "正在加密备份..."
        defer { isBusy = false }
        do {
            let data = try await Task.detached(priority: .userInitiated) {
                try ConfigBackup.exportData(passphrase: passphrase)
            }.value
            status = "备份已加密，选择保存位置。"
            return data
        } catch {
            status = "导出失败：\(error.localizedDescription)"
            return nil
        }
    }

    func restore(data: Data, passphrase: String) async {
        isBusy = true
        status = "正在读取并解密备份..."
        defer { isBusy = false }
        do {
            await Task.yield()
            let restored = try ConfigBackup.restore(data: data, passphrase: passphrase)
            status = "已恢复 \(restored.connections) 个连接、\(restored.credentials) 个凭证、\(restored.tunnels) 个隧道、\(restored.servers) 个服务器。"
        } catch {
            status = error.localizedDescription
        }
    }

    func exportFinished(_ result: Result<URL, Error>, byteCount: Int) {
        switch result {
        case .success:
            status = "已导出 \((byteCount + 1023) / 1024) KB 加密备份。"
        case .failure(let error):
            status = "保存失败：\(error.localizedDescription)"
        }
    }

    func reportImportError(_ error: Error) {
        status = "读取失败：\(error.localizedDescription)"
    }
}

struct ConfigBackupView: View {
    @StateObject private var model = ConfigBackupViewModel()
    @State private var passphraseMode: BackupPassphraseMode?
    @State private var pendingImport: Data?
    @State private var exportDocument: BackupDocument?
    @State private var exportByteCount = 0
    @State private var exportFilename = "aidevmob-backup"
    @State private var showingImporter = false
    @State private var showingExporter = false

    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    Button {
                        passphraseMode = .export
                    } label: {
                        Label("导出", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 7)
                            .background(Color.accentColor.opacity(0.1))
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .overlay {
                                RoundedRectangle(cornerRadius: 6)
                                    .stroke(Color.accentColor.opacity(0.35))
                            }
                    }
                    .buttonStyle(.borderless)

                    Button {
                        showingImporter = true
                    } label: {
                        Label("恢复", systemImage: "square.and.arrow.down")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 7)
                            .background(Color.accentColor.opacity(0.1))
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .overlay {
                                RoundedRectangle(cornerRadius: 6)
                                    .stroke(Color.accentColor.opacity(0.35))
                            }
                    }
                    .buttonStyle(.borderless)
                }
                .disabled(model.isBusy)

                if model.isBusy {
                    ProgressView(model.status)
                } else {
                    Text(model.status)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } footer: {
                Text("备份包含连接、凭证秘密、隧道、服务器与设置；口令不会保存在设备上。")
            }
        }
        .navigationTitle("配置备份")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $passphraseMode) { mode in
            BackupPassphraseView(mode: mode) { passphrase in
                switch mode {
                case .export:
                    createExport(passphrase: passphrase)
                case .restore:
                    restoreImport(passphrase: passphrase)
                }
            }
        }
        .fileImporter(
            isPresented: $showingImporter,
            allowedContentTypes: [.json, .data],
            allowsMultipleSelection: false,
            onCompletion: handleImportSelection
        )
        .fileExporter(
            isPresented: $showingExporter,
            document: exportDocument,
            contentType: .json,
            defaultFilename: exportFilename
        ) { result in
            model.exportFinished(result, byteCount: exportByteCount)
            exportDocument = nil
            exportByteCount = 0
        }
    }

    private func createExport(passphrase: String) {
        Task {
            guard let data = await model.makeBackup(passphrase: passphrase) else { return }
            exportDocument = BackupDocument(data: data)
            exportByteCount = data.count
            exportFilename = "aidevmob-backup-\(Self.timestamp())"
            showingExporter = true
        }
    }

    private func handleImportSelection(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }

            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size <= ConfigBackup.maximumImportBytes else {
                throw ConfigBackup.BackupError.invalidFormat("文件超过 32 MB")
            }
            pendingImport = try Data(contentsOf: url, options: .mappedIfSafe)
            passphraseMode = .restore
        } catch {
            model.reportImportError(error)
        }
    }

    private func restoreImport(passphrase: String) {
        guard let data = pendingImport else { return }
        pendingImport = nil
        Task { await model.restore(data: data, passphrase: passphrase) }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmm"
        return formatter.string(from: Date())
    }
}

private struct BackupPassphraseView: View {
    let mode: BackupPassphraseMode
    let onSubmit: (String) -> Void

    @State private var passphrase = ""
    @State private var confirmation = ""
    @State private var validationError: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("口令", text: $passphrase)
                        .textContentType(.password)
                    if mode == .export {
                        SecureField("再次输入口令", text: $confirmation)
                            .textContentType(.password)
                    }
                } footer: {
                    Text(mode == .export
                        ? "至少 8 个字符。忘记口令后无法恢复备份。"
                        : "输入导出该备份时使用的口令。")
                }

                if let validationError {
                    Text(validationError)
                        .foregroundStyle(.red)
                        .font(.caption)
                }
            }
            .navigationTitle(mode.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("确认") { validateAndSubmit() }
                        .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func validateAndSubmit() {
        guard passphrase.count >= ConfigBackup.minimumPassphraseLength else {
            validationError = "口令至少需要 \(ConfigBackup.minimumPassphraseLength) 个字符。"
            return
        }
        guard mode != .export || passphrase == confirmation else {
            validationError = "两次输入的口令不一致。"
            return
        }
        let submitted = passphrase
        passphrase = ""
        confirmation = ""
        dismiss()
        onSubmit(submitted)
    }
}
