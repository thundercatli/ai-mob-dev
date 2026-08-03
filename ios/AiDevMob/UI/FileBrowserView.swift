import SwiftUI
import UIKit

enum FileBrowserSort: String, CaseIterable, Identifiable {
    case name
    case size
    case modified

    var id: String { rawValue }

    var label: String {
        switch self {
        case .name: return "名称"
        case .size: return "大小（从大到小）"
        case .modified: return "修改时间（从新到旧）"
        }
    }
}

struct RemoteFilePreview: Identifiable {
    enum Content {
        case text(String, truncated: Bool)
        case image(Data)
        case unavailable(String)
    }

    let id = UUID()
    let entry: RemoteFileEntry
    let content: Content
}

struct DownloadedRemoteFile: Identifiable {
    let id = UUID()
    let url: URL
}

@MainActor
final class FileBrowserViewModel: ObservableObject {
    let config: ConnectionConfig

    @Published private(set) var currentPath = ""
    @Published private(set) var entries = [RemoteFileEntry]()
    @Published private(set) var isConnecting = true
    @Published private(set) var isBusy = false
    @Published private(set) var connectionError: String?
    @Published var showHidden = false
    @Published var sort: FileBrowserSort = .name
    @Published var preview: RemoteFilePreview?
    @Published var downloadedFile: DownloadedRemoteFile?
    @Published var notice: String?
    @Published var operationError: String?

    private let sessionProvider: () async throws -> SftpSession
    private var session: SftpSession?
    private var operation: Task<Void, Never>?

    init(config: ConnectionConfig, sessionProvider: @escaping () async throws -> SftpSession) {
        self.config = config
        self.sessionProvider = sessionProvider
    }

    var visibleEntries: [RemoteFileEntry] {
        entries
            .filter { showHidden || !$0.name.hasPrefix(".") }
            .sorted { lhs, rhs in
                if lhs.isDirectory != rhs.isDirectory { return lhs.isDirectory }
                switch sort {
                case .name:
                    return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
                case .size:
                    if lhs.size != rhs.size { return lhs.size > rhs.size }
                    return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
                case .modified:
                    let left = lhs.modified ?? .distantPast
                    let right = rhs.modified ?? .distantPast
                    if left != right { return left > right }
                    return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
                }
            }
    }

    var hasOnlyHiddenEntries: Bool {
        !entries.isEmpty && visibleEntries.isEmpty
    }

    func connect() async {
        guard session == nil else {
            await refresh()
            return
        }
        isConnecting = true
        connectionError = nil

        do {
            let opened = try await sessionProvider()
            session = opened
            let preferred = config.defaultPath.trimmingCharacters(in: .whitespacesAndNewlines)

            if !preferred.isEmpty {
                do {
                    let resolved = try await opened.canonicalize(preferred)
                    let listing = try await opened.list(resolved)
                    apply(path: resolved, entries: listing)
                } catch {
                    let home = try await opened.homePath()
                    let listing = try await opened.list(home)
                    apply(path: home, entries: listing)
                    notice = "无法打开默认目录 \(preferred)，已显示登录目录。"
                }
            } else {
                let home = try await opened.homePath()
                let listing = try await opened.list(home)
                apply(path: home, entries: listing)
            }
        } catch {
            connectionError = Self.friendly(error)
        }
        isConnecting = false
    }

    func navigate(to path: String) {
        guard let session else { return }
        startOperation {
            let resolved = try await session.canonicalize(path)
            return (resolved, try await session.list(resolved))
        }
    }

    func open(_ entry: RemoteFileEntry) {
        if entry.isDirectory {
            navigate(to: entry.path)
        } else {
            loadPreview(entry)
        }
    }

    func refresh() async {
        guard let session, !currentPath.isEmpty else { return }
        isBusy = true
        do {
            entries = try await session.list(currentPath)
        } catch {
            operationError = Self.friendly(error)
        }
        isBusy = false
    }

    func goHome() {
        guard let session else { return }
        startOperation {
            let preferred = self.config.defaultPath.trimmingCharacters(in: .whitespacesAndNewlines)
            if !preferred.isEmpty, let resolved = try? await session.canonicalize(preferred),
               let listing = try? await session.list(resolved) {
                return (resolved, listing)
            }
            let home = try await session.homePath()
            return (home, try await session.list(home))
        }
    }

    func goUp() {
        guard let parent = remoteParentPath(currentPath) else { return }
        navigate(to: parent)
    }

    func loadPreview(_ entry: RemoteFileEntry) {
        guard let session else { return }
        let kind = Self.previewKind(for: entry.name)

        if kind == .unavailable {
            preview = RemoteFilePreview(
                entry: entry,
                content: .unavailable("该文件类型不支持预览，请下载后用其他应用打开。")
            )
            return
        }
        if kind == .image && entry.size > SftpSession.maxImagePreviewBytes {
            preview = RemoteFilePreview(
                entry: entry,
                content: .unavailable("图片超过 12 MB，请下载后查看。")
            )
            return
        }

        isBusy = true
        operation?.cancel()
        operation = Task {
            do {
                let limit = kind == .image
                    ? SftpSession.maxImagePreviewBytes
                    : SftpSession.maxTextPreviewBytes
                let result = try await session.readBytes(at: entry.path, limit: limit)
                guard !Task.isCancelled else { return }
                switch kind {
                case .image:
                    guard UIImage(data: result.data) != nil else {
                        preview = RemoteFilePreview(entry: entry, content: .unavailable("无法解码这张图片。"))
                        isBusy = false
                        return
                    }
                    preview = RemoteFilePreview(entry: entry, content: .image(result.data))
                case .text:
                    let text = String(data: result.data, encoding: .utf8)
                        ?? String(decoding: result.data, as: UTF8.self)
                    preview = RemoteFilePreview(entry: entry, content: .text(text, truncated: result.truncated))
                case .unavailable:
                    break
                }
            } catch {
                operationError = Self.friendly(error)
            }
            isBusy = false
        }
    }

    func download(_ entry: RemoteFileEntry) {
        guard let session else { return }
        isBusy = true
        operation?.cancel()
        operation = Task {
            do {
                let url = try await session.download(entry)
                guard !Task.isCancelled else { return }
                downloadedFile = DownloadedRemoteFile(url: url)
            } catch {
                operationError = Self.friendly(error)
            }
            isBusy = false
        }
    }

    func copyPath(_ entry: RemoteFileEntry) {
        UIPasteboard.general.string = entry.path
        notice = "路径已复制"
    }

    func close() {
        operation?.cancel()
        guard let session else { return }
        self.session = nil
        Task { await session.close() }
    }

    private func startOperation(
        _ action: @escaping () async throws -> (String, [RemoteFileEntry])
    ) {
        isBusy = true
        operation?.cancel()
        operation = Task {
            do {
                let (path, listing) = try await action()
                guard !Task.isCancelled else { return }
                apply(path: path, entries: listing)
            } catch {
                operationError = Self.friendly(error)
            }
            isBusy = false
        }
    }

    private func apply(path: String, entries: [RemoteFileEntry]) {
        currentPath = path
        self.entries = entries
        connectionError = nil
    }

    private enum PreviewKind { case text, image, unavailable }

    private static func previewKind(for name: String) -> PreviewKind {
        let ext = URL(fileURLWithPath: name).pathExtension.lowercased()
        if imageExtensions.contains(ext) { return .image }
        if textExtensions.contains(ext) || ext.isEmpty || name.hasPrefix(".") { return .text }
        return .unavailable
    }

    private static func friendly(_ error: Error) -> String {
        let message = error.localizedDescription
        if message.contains("auth") || message.contains("Auth") {
            return "SSH 认证失败，请检查凭证。"
        }
        if message.lowercased().contains("host key") {
            return "主机密钥校验失败：\(message)"
        }
        return message
    }

    private static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "gif", "webp", "bmp", "heic"]
    private static let textExtensions: Set<String> = [
        "txt", "md", "log", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "env",
        "properties", "gradle", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs",
        "c", "h", "cpp", "hpp", "cs", "rb", "php", "swift", "sh", "bash", "zsh", "fish", "sql",
        "html", "css", "scss", "lua", "vim", "diff", "patch", "csv", "tsv"
    ]
}

struct FileBrowserView: View {
    @StateObject private var model: FileBrowserViewModel
    @Environment(\.dismiss) private var dismiss

    init(host: FileBrowserHost) {
        _model = StateObject(wrappedValue: FileBrowserViewModel(
            config: host.config,
            sessionProvider: host.sessionProvider
        ))
    }

    var body: some View {
        NavigationStack {
            Group {
                if model.isConnecting {
                    ProgressView("正在连接 SFTP...")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let error = model.connectionError {
                    ContentUnavailableView {
                        Label("无法打开文件", systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(error)
                    } actions: {
                        Button("重试") { Task { await model.connect() } }
                    }
                } else {
                    browserContent
                }
            }
            .navigationTitle(model.config.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { browserToolbar }
            .overlay(alignment: .top) {
                if model.isBusy {
                    ProgressView()
                        .padding(9)
                        .background(.regularMaterial, in: Circle())
                        .padding(.top, 8)
                }
            }
        }
        .task { await model.connect() }
        .onDisappear { model.close() }
        .sheet(item: $model.preview) { preview in
            RemoteFilePreviewView(preview: preview) {
                model.download(preview.entry)
            }
        }
        .sheet(item: $model.downloadedFile) { file in
            ActivityView(activityItems: [file.url])
        }
        .alert("提示", isPresented: Binding(
            get: { model.notice != nil },
            set: { if !$0 { model.notice = nil } }
        )) {
            Button("好", role: .cancel) { model.notice = nil }
        } message: {
            Text(model.notice ?? "")
        }
        .alert("操作失败", isPresented: Binding(
            get: { model.operationError != nil },
            set: { if !$0 { model.operationError = nil } }
        )) {
            Button("好", role: .cancel) { model.operationError = nil }
        } message: {
            Text(model.operationError ?? "")
        }
    }

    private var browserContent: some View {
        VStack(spacing: 0) {
            BreadcrumbView(path: model.currentPath, onNavigate: model.navigate)
            Divider()

            if model.entries.isEmpty {
                ContentUnavailableView("目录为空", systemImage: "folder")
            } else if model.hasOnlyHiddenEntries {
                ContentUnavailableView {
                    Label("这里只有隐藏项目", systemImage: "eye.slash")
                } description: {
                    Text("共 \(model.entries.count) 项")
                } actions: {
                    Button("显示隐藏文件") { model.showHidden = true }
                }
            } else {
                List(model.visibleEntries) { entry in
                    Button { model.open(entry) } label: {
                        RemoteFileRow(entry: entry)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        if !entry.isDirectory {
                            Button { model.loadPreview(entry) } label: {
                                Label("预览", systemImage: "eye")
                            }
                            Button { model.download(entry) } label: {
                                Label("下载", systemImage: "square.and.arrow.down")
                            }
                        }
                        Button { model.copyPath(entry) } label: {
                            Label("复制路径", systemImage: "doc.on.doc")
                        }
                    }
                }
                .listStyle(.plain)
                .refreshable { await model.refresh() }
            }
        }
    }

    @ToolbarContentBuilder
    private var browserToolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button { dismiss() } label: { Image(systemName: "xmark") }
                .accessibilityLabel("关闭")
        }
        ToolbarItemGroup(placement: .primaryAction) {
            Button { model.goUp() } label: { Image(systemName: "arrow.up") }
                .disabled(remoteParentPath(model.currentPath) == nil || model.isBusy)
                .accessibilityLabel("上一级")
            Button { model.goHome() } label: { Image(systemName: "house") }
                .disabled(model.isBusy)
                .accessibilityLabel("默认目录")
            Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }
                .disabled(model.isBusy)
                .accessibilityLabel("刷新")
            Menu {
                Toggle("显示隐藏文件", isOn: $model.showHidden)
                Picker("排序", selection: $model.sort) {
                    ForEach(FileBrowserSort.allCases) { sort in
                        Text(sort.label).tag(sort)
                    }
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel("更多")
        }
    }
}

private struct BreadcrumbView: View {
    let path: String
    let onNavigate: (String) -> Void

    private var components: [(label: String, path: String)] {
        var result = [("/", "/")]
        var accumulated = ""
        for component in path.split(separator: "/") {
            accumulated += "/\(component)"
            result.append((String(component), accumulated))
        }
        return result
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 5) {
                ForEach(Array(components.enumerated()), id: \.offset) { index, component in
                    if index > 0 {
                        Image(systemName: "chevron.right")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    Button(component.label) { onNavigate(component.path) }
                        .buttonStyle(.plain)
                        .font(.callout.weight(component.path == path ? .semibold : .regular))
                        .foregroundStyle(component.path == path ? Color.accentColor : Color.secondary)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
    }
}

private struct RemoteFileRow: View {
    let entry: RemoteFileEntry

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: entry.isDirectory ? "folder.fill" : iconName)
                .foregroundStyle(entry.isDirectory ? Color.accentColor : Color.secondary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    Text(entry.name)
                        .lineLimit(1)
                        .foregroundStyle(.primary)
                    if entry.isLink {
                        Image(systemName: "link")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if entry.isDirectory {
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
        .contentShape(Rectangle())
        .padding(.vertical, 3)
    }

    private var iconName: String {
        let ext = URL(fileURLWithPath: entry.name).pathExtension.lowercased()
        return ["png", "jpg", "jpeg", "gif", "webp", "bmp", "heic"].contains(ext)
            ? "photo"
            : "doc.text"
    }

    private var detail: String {
        var parts = [permissionString(entry.permissions)]
        if !entry.isDirectory {
            parts.append(ByteCountFormatter.string(fromByteCount: Int64(clamping: entry.size), countStyle: .file))
        }
        if let modified = entry.modified {
            parts.append(modified.formatted(date: .abbreviated, time: .shortened))
        }
        return parts.joined(separator: "  ")
    }
}

private struct RemoteFilePreviewView: View {
    let preview: RemoteFilePreview
    let onDownload: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                switch preview.content {
                case .text(let text, let truncated):
                    ScrollView([.horizontal, .vertical]) {
                        Text(text.isEmpty ? "此文件为空" : text)
                            .font(.system(.body, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                            .padding()
                    }
                    .safeAreaInset(edge: .bottom) {
                        if truncated {
                            Text("仅显示前 512 KB，下载后可查看完整文件。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity)
                                .padding(8)
                                .background(.bar)
                        }
                    }
                case .image(let data):
                    ScrollView([.horizontal, .vertical]) {
                        if let image = UIImage(data: data) {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .padding()
                        }
                    }
                case .unavailable(let message):
                    ContentUnavailableView("无法预览", systemImage: "doc.questionmark", description: Text(message))
                }
            }
            .navigationTitle(preview.entry.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("关闭预览")
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        dismiss()
                        onDownload()
                    } label: {
                        Image(systemName: "square.and.arrow.down")
                    }
                    .accessibilityLabel("下载")
                }
            }
        }
        .presentationDetents([.large])
    }
}

private struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

private func permissionString(_ permissions: UInt32) -> String {
    let symbols = Array("rwxrwxrwx")
    return String((0..<9).map { index in
        permissions & (1 << UInt32(8 - index)) != 0 ? symbols[index] : "-"
    })
}
