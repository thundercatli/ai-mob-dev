import SwiftUI

// MARK: - ConnectionListView

struct ConnectionListView: View {
    let onConnect: (ConnectionConfig) -> Void
    let onBrowseFiles: (ConnectionConfig) -> Void
    /// When embedded in the iPad sidebar, the sidebar already owns a NavigationStack, so this
    /// view skips its own outer NavigationStack (and its navigationTitle, which the sidebar
    /// sets). Defaults to false so iPhone behaviour is unchanged.
    var embeddedInSplit: Bool = false

    @State private var connections: [ConnectionConfig] = []
    @State private var editingItem: ConnectionConfig?

    private let store = ConnectionStore()

    var body: some View {
        if embeddedInSplit {
            content
        } else {
            NavigationStack { content.navigationTitle("连接") }
        }
    }

    @ViewBuilder
    private var content: some View {
        List {
            ForEach(connections) { config in
                HStack(spacing: 8) {
                    Button {
                        onConnect(config)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(config.displayName)
                                .foregroundColor(.primary)
                                .font(.headline)
                            HStack(spacing: 4) {
                                // Show "host:port" only when host is non-empty; a bare port number
                                // with no host is meaningless to the user.
                                if !config.host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                    Text("\(config.host):\(config.port)")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                if !config.tmuxSession.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                    Text("·")
                                        .foregroundColor(.secondary)
                                    Text("tmux")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    Button {
                        onBrowseFiles(config)
                    } label: {
                        Image(systemName: "folder")
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("浏览 \(config.displayName) 的文件")
                }
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) {
                        store.delete(id: config.id)
                        load()
                    } label: {
                        Label("删除", systemImage: "trash")
                    }
                    Button {
                        editingItem = config
                    } label: {
                        Label("编辑", systemImage: "pencil")
                    }
                    .tint(.orange)
                }
                .swipeActions(edge: .leading) {
                    // Duplicate: opens the editor with a copy (empty id = new), name + "(副本)".
                    Button {
                        var copy = config
                        copy = ConnectionConfig(
                            id: "",
                            name: config.displayName + "（副本）",
                            host: config.host, port: config.port,
                            credentialId: config.credentialId, username: config.username,
                            authMethod: config.authMethod, password: config.password,
                            privateKeyPem: config.privateKeyPem,
                            privateKeyPassphrase: config.privateKeyPassphrase,
                            tmuxSession: config.tmuxSession, defaultPath: config.defaultPath,
                            tunnelId: config.tunnelId
                        )
                        editingItem = copy
                    } label: {
                        Label("复制", systemImage: "doc.on.doc")
                    }
                    .tint(.blue)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editingItem = ConnectionConfig(
                        id: "",   // empty id marks a NEW connection (onSave assigns a real one)
                        name: "",
                        host: "",
                        port: 22,
                        username: "",
                        authMethod: .password,
                        password: nil,
                        privateKeyPem: nil,
                        privateKeyPassphrase: nil,
                        tmuxSession: ""
                    )
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("新建连接")
            }
        }
        .sheet(item: $editingItem) { config in
            NavigationStack {
                ConnectionEditView(
                    config: config,
                    onSave: { saved in persistAndClose(saved) },
                    onSaveAndConnect: { saved in
                        persistAndClose(saved)
                        // Find the saved profile (now has a real id) and connect.
                        if let stored = store.get(id: saved.id.isEmpty ? lastSavedId ?? "" : saved.id) {
                            onConnect(stored)
                        } else {
                            onConnect(saved)
                        }
                    },
                    onDelete: { toDelete in
                        store.delete(id: toDelete.id)
                        editingItem = nil
                        load()
                    }
                )
            }
        }
        .onAppear(perform: load)
    }

    /// Persists a connection config (assigning a fresh UUID to new ones), then closes the editor.
    /// Remembers the assigned id so "save and connect" can look it up.
    @State private var lastSavedId: String?
    private func persistAndClose(_ saved: ConnectionConfig) {
        // A NEW connection (created with id "") needs a real id before storing.
        let toSave = saved.id.isEmpty
            ? ConnectionConfig(
                id: UUID().uuidString,
                name: saved.name, host: saved.host, port: saved.port,
                credentialId: saved.credentialId, username: saved.username,
                authMethod: saved.authMethod, password: saved.password,
                privateKeyPem: saved.privateKeyPem,
                privateKeyPassphrase: saved.privateKeyPassphrase,
                tmuxSession: saved.tmuxSession, defaultPath: saved.defaultPath,
                tunnelId: saved.tunnelId
            )
            : saved
        store.upsert(toSave)
        lastSavedId = toSave.id
        editingItem = nil
        load()
    }

    private func load() {
        connections = store.list()
    }
}

// MARK: - ConnectionEditView

private enum ConnectionEditSheet: Identifiable {
    case credential(Credential)
    case tmux(TmuxSessionList)

    var id: String {
        switch self {
        case .credential:
            return "credential"
        case .tmux:
            return "tmux"
        }
    }
}

struct ConnectionEditView: View {
    @State var config: ConnectionConfig
    let onSave: (ConnectionConfig) -> Void
    /// Optional: save then immediately open the terminal (Android's "Save and Connect").
    var onSaveAndConnect: ((ConnectionConfig) -> Void)?
    /// Optional: delete this connection from the editor (only when editing an existing one).
    var onDelete: ((ConnectionConfig) -> Void)?

    @State private var credentials: [Credential] = []
    @State private var tunnels: [FrpcTunnel] = []
    @State private var activeSheet: ConnectionEditSheet?
    /// tmux-probe state: loading flag, the sessions found (when probing succeeds), and an
    /// error message (when it fails). Mirrors Android's `probing` + `showTmuxSessionPicker`.
    @State private var tmuxProbing = false
    @State private var tmuxProbeError: String?
    @State private var showingNewSessionPrompt = false
    @State private var newSessionName = ""
    @State private var showingDeleteConfirm = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section("基本信息") {
                TextField("名称", text: $config.name)
                TextField("主机", text: $config.host)
                TextField("端口", value: $config.port, format: .number.grouping(.never))
                    .keyboardType(.numberPad)
            }

            Section("认证") {
                Picker("凭证", selection: $config.credentialId) {
                    Text("无").tag(nil as String?)
                    ForEach(credentials) { cred in
                        Text(cred.displayName).tag(cred.id as String?)
                    }
                }
                HStack(spacing: 12) {
                    Button {
                        activeSheet = .credential(Credential(
                            id: "",
                            name: "",
                            username: "",
                            authMethod: .password,
                            password: nil,
                            privateKeyPem: nil,
                            privateKeyPassphrase: nil
                        ))
                    } label: {
                        Label("新建凭证", systemImage: "plus")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderless)

                    Button {
                        if let selectedCredential {
                            activeSheet = .credential(selectedCredential)
                        }
                    } label: {
                        Label("编辑凭证", systemImage: "pencil")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderless)
                    .disabled(selectedCredential == nil)
                }
                if config.credentialId == nil {
                    TextField("用户名", text: $config.username)
                    Picker("认证方式", selection: $config.authMethod) {
                        Text("密码").tag(AuthMethod.password)
                        Text("私钥").tag(AuthMethod.privateKey)
                    }
                    switch config.authMethod {
                    case .password:
                        SecureField("密码", text: Binding(
                            get: { config.password ?? "" },
                            set: { config.password = $0.isEmpty ? nil : $0 }
                        ))
                    case .privateKey:
                        Text("私钥 PEM", comment: "Section header for private key input")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextEditor(text: Binding(
                            get: { config.privateKeyPem ?? "" },
                            set: { config.privateKeyPem = $0.isEmpty ? nil : $0 }
                        ))
                        .font(.system(.caption, design: .monospaced))
                        .frame(minHeight: 120)
                        SecureField("密码短语（可选）", text: Binding(
                            get: { config.privateKeyPassphrase ?? "" },
                            set: { config.privateKeyPassphrase = $0.isEmpty ? nil : $0 }
                        ))
                    }
                }
            }

            Section {
                TextField("Tmux 会话名", text: $config.tmuxSession)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                Button {
                    probeTmux()
                } label: {
                    if tmuxProbing {
                        HStack { ProgressView(); Text("探测中…") }
                    } else {
                        Label("探测 tmux 会话", systemImage: "magnifyingglass")
                    }
                }
                .disabled(tmuxProbing)
                if let err = tmuxProbeError {
                    Text(err).font(.caption).foregroundColor(.red)
                }
            } header: {
                Text("终端")
            } footer: {
                if config.tmuxSession.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text("留空则进入普通 shell；填入会话名则用 `tmux new-session -A` 创建或附加该会话。")
                } else {
                    Text("将附加或创建 tmux 会话「\(config.tmuxSession)」。")
                }
            }

            Section("隧道") {
                Picker("隧道", selection: $config.tunnelId) {
                    Text("直连（无隧道）").tag(nil as String?)
                    ForEach(tunnels) { tunnel in
                        Text(tunnel.displayName).tag(tunnel.id as String?)
                    }
                }
            }
        }
        .navigationTitle(config.id.isEmpty ? "新建连接" : "编辑连接")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Menu {
                    Button {
                        onSave(config)
                    } label: {
                        Label("保存", systemImage: "checkmark")
                    }
                    if onSaveAndConnect != nil {
                        Button {
                            onSaveAndConnect?(config)
                        } label: {
                            Label("保存并连接", systemImage: "arrow.forward.square")
                        }
                    }
                } label: {
                    Text("保存").fontWeight(.semibold)
                }
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") {
                    dismiss()
                }
            }
            // Delete: only when editing an existing connection (not a new one with empty id).
            if !config.id.isEmpty, onDelete != nil {
                ToolbarItem(placement: .destructiveAction) {
                    Button(role: .destructive) {
                        showingDeleteConfirm = true
                    } label: {
                        Image(systemName: "trash")
                    }
                }
            }
        }
        .confirmationDialog("确定删除此连接？", isPresented: $showingDeleteConfirm, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                onDelete?(config)
                dismiss()
            }
            Button("取消", role: .cancel) {}
        }
        .onChange(of: config.credentialId) {
            applySelectedCredential()
        }
        .onAppear {
            reloadCredentials()
            tunnels = FrpcTunnelStore().list()
            // Pre-fill the default credential + tunnel on a NEW connection (empty id), so the
            // user doesn't have to pick the same ones every time. They can still override.
            if config.id.isEmpty {
                if config.credentialId == nil, let id = AppDefaults.defaultCredentialId,
                   credentials.contains(where: { $0.id == id }) {
                    config.credentialId = id
                }
                if config.tunnelId == nil, let id = AppDefaults.defaultTunnelId,
                   tunnels.contains(where: { $0.id == id }) {
                    config.tunnelId = id
                }
            }
            applySelectedCredential()
        }
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .credential(let credential):
                NavigationStack {
                    CredentialEditView(credential: credential) { saved in
                        persistCredential(saved)
                    }
                }
            case .tmux(let list):
                TmuxSessionPicker(
                    sessions: list.sessions,
                    onSelect: { name in
                        config.tmuxSession = name
                        activeSheet = nil
                    },
                    onCancel: { activeSheet = nil }
                )
            }
        }
        .alert("新建 tmux 会话", isPresented: $showingNewSessionPrompt) {
            TextField("会话名", text: $newSessionName)
                .autocapitalization(.none)
            Button("确定") {
                let trimmed = newSessionName.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    config.tmuxSession = trimmed
                }
                newSessionName = ""
            }
            Button("取消", role: .cancel) { newSessionName = "" }
        }
    }

    private var selectedCredential: Credential? {
        guard let id = config.credentialId else { return nil }
        return credentials.first { $0.id == id }
    }

    private func reloadCredentials() {
        credentials = CredentialStore().list()
    }

    private func applySelectedCredential() {
        guard let credential = selectedCredential else { return }
        config.username = credential.username
        config.authMethod = credential.authMethod
        config.password = nil
        config.privateKeyPem = nil
        config.privateKeyPassphrase = nil
    }

    private func persistCredential(_ credential: Credential) {
        let saved = credential.id.isEmpty
            ? Credential(
                id: UUID().uuidString,
                name: credential.name,
                username: credential.username,
                authMethod: credential.authMethod,
                password: credential.password,
                privateKeyPem: credential.privateKeyPem,
                privateKeyPassphrase: credential.privateKeyPassphrase
            )
            : credential
        CredentialStore().upsert(saved)
        reloadCredentials()
        config.credentialId = saved.id
        applySelectedCredential()
        activeSheet = nil
    }

    // MARK: - tmux probe

    /// Translates a raw SSH/NIO error from the probe into a hint that points at the likely cause.
    private static func friendlyProbeError(_ error: Error, hasTunnel: Bool) -> String {
        let raw = SshTerminalConnector.diagnosticDescription(error)
        let lower = raw.lowercased()
        if raw.contains("隧道启动失败") {
            return raw
        }
        if lower.contains("channel") || lower.contains("connect") || lower.contains("socket")
            || lower.contains("refused") || lower.contains("reset") || lower.contains("timed out")
            || lower.contains("broken pipe") {
            let hint = hasTunnel
                ? "无法连接：隧道未就绪或本地端口不可达，请稍候重试或检查隧道配置。"
                : "无法连接到主机，请检查地址/端口和网络。"
            return "\(hint)\n详细信息：\(raw)"
        }
        if lower.contains("auth") || lower.contains("password") {
            return "认证失败，请检查用户名/密码或私钥配置。"
        }
        if lower.contains("host key") {
            return "主机密钥校验失败。"
        }
        return "探测失败：\(raw)"
    }

    /// STCP accepts the local socket before frps validates the target proxy and secret. When that
    /// validation fails, the SSH side only sees ECONNRESET; the captured frpc lines hold the cause.
    private static func probeTunnelLogDetail(_ tunnelId: String?) -> String? {
        guard let tunnelId else { return nil }
        let lines = TunnelRuntime.shared.logs(tunnelId)
            .split(whereSeparator: \Character.isNewline)
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        guard !lines.isEmpty else { return nil }

        let diagnostic = lines.filter { line in
            let lower = line.lowercased()
            return lower.contains("visitor") || lower.contains("proxy")
                || lower.contains("auth") || lower.contains("error") || lower.contains("failed")
        }
        let selected = (diagnostic.isEmpty ? lines : diagnostic).suffix(4)
        return selected.map { String($0.suffix(300)) }.joined(separator: "\n")
    }

    /// Connects (through the configured tunnel, if any) and lists the remote tmux sessions,
    /// then shows the picker. Mirrors Android's `probeTmuxSessions`.
    private func probeTmux() {
        tmuxProbing = true
        tmuxProbeError = nil
        let resolved = CredentialStore().resolve(config)
        guard !resolved.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            tmuxProbing = false
            tmuxProbeError = "用户名不能为空，请先配置凭证。"
            return
        }

        Task {
            do {
                // If a tunnel is configured, start it (and wait) before probing — same gate the
                // terminal uses. SSH must reach sshd through the tunnel's local listener.
                let sshConfig = try await ensureTunnel(resolved)
                let sessions = try await TmuxSessionProbe.list(config: sshConfig)
                await MainActor.run {
                    tmuxProbing = false
                    if sessions.isEmpty {
                        tmuxProbeError = "远端没有运行中的 tmux 会话。"
                    } else {
                        activeSheet = .tmux(TmuxSessionList(sessions: sessions))
                    }
                }
            } catch {
                let message = Self.friendlyProbeError(error, hasTunnel: resolved.tunnelId != nil)
                let tunnelDetail = Self.probeTunnelLogDetail(resolved.tunnelId)
                await MainActor.run {
                    tmuxProbing = false
                    tmuxProbeError = tunnelDetail.map { "\(message)\nfrpc 日志：\n\($0)" } ?? message
                }
            }
        }
    }

    /// Starts the configured tunnel if needed (only when it isn't already running) and ALWAYS
    /// returns the SSH config with host/port redirected to the tunnel's local listener — the
    /// same redirection the terminal connection uses.
    ///
    /// The redirect must happen whether or not the tunnel was just started: if the tunnel is
    /// already up (e.g. a connection was opened earlier this session), we skip the start but
    /// still have to point SSH at `127.0.0.1:<bindPort>`. The earlier version returned the
    /// un-redirected config when the tunnel was already running, so `exec` dialled the raw
    /// host:port (only reachable through the tunnel) and failed with a NIO channel error.
    private func ensureTunnel(_ config: ConnectionConfig) async throws -> ConnectionConfig {
        guard let tunnelId = config.tunnelId else {
            return config
        }
        guard let tunnel = FrpcTunnelStore().get(id: tunnelId) else {
            throw TunnelError.startFailed("关联的隧道配置不存在或被删除")
        }
        guard tunnel.bindPort > 0 && tunnel.bindPort <= 65_535 else {
            throw TunnelError.startFailed("隧道本地端口无效")
        }

        // Start the tunnel only if it isn't already up.
        if !TunnelRuntime.shared.isRunning(tunnelId) {
            guard let server = FrpsServerStore().get(id: tunnel.serverId) else {
                throw TunnelError.startFailed("隧道指向的 frps 服务器配置不存在")
            }
            let params = VisitorParams(
                id: tunnel.id,
                serverAddr: server.serverAddr,
                serverPort: server.serverPort,
                token: server.authToken ?? "",
                serverName: tunnel.serverName,
                secretKey: tunnel.secretKey,
                bindPort: tunnel.bindPort
            )
            try TunnelRuntime.shared.start(params)
            let state = await TunnelRuntime.shared.awaitRunning(tunnelId, timeout: 20)
            guard state == .running else {
                let detail = TunnelRuntime.shared.lastError(tunnelId)
                throw TunnelError.startFailed(detail.isEmpty ? "等待隧道就绪超时" : detail)
            }
        }

        // Redirect SSH to the tunnel's local listener regardless of whether we just started it
        // or it was already running — exec must dial 127.0.0.1:<bindPort>, not the raw host:port.
        var redirected = config
        redirected.host = "127.0.0.1"
        redirected.port = tunnel.bindPort
        return redirected
    }
}

// MARK: - TmuxSessionPicker

/// Bottom-style sheet listing remote tmux sessions for the user to pick, plus the two escape
/// hatches Android's picker has: name a new session, or skip tmux. Mirrors
/// `showTmuxSessionPicker` + `dialog_tmux_sessions.xml`.
private struct TmuxSessionPicker: View {
    let sessions: [TmuxSession]
    let onSelect: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(sessions) { session in
                        Button {
                            onSelect(session.name)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(session.name)
                                        .font(.system(.body, design: .monospaced))
                                        .foregroundColor(.primary)
                                    Text("\(session.windows) 个窗口")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                                if session.attached {
                                    Text("已附加")
                                        .font(.caption2)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 3)
                                        .background(Color.orange.opacity(0.2))
                                        .foregroundColor(.orange)
                                        .clipShape(Capsule())
                                }
                            }
                        }
                    }
                } header: {
                    Text("远端会话（\(sessions.count)）")
                }

                Section {
                    Button {
                        onSelect("") // skip tmux
                    } label: {
                        Label("不使用 tmux", systemImage: "xmark.circle")
                            .foregroundColor(.primary)
                    }
                }
            }
            .navigationTitle("选择 tmux 会话")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { onCancel() }
                }
            }
        }
    }
}

// MARK: - TmuxSessionList (sheet item)

/// Identifiable wrapper so a `.sheet(item:)` can present the probed session list.
private struct TmuxSessionList: Identifiable {
    let id = UUID()
    let sessions: [TmuxSession]
}

// MARK: - CredentialListView

struct CredentialListView: View {
    /// When embedded in the iPad sidebar, the sidebar owns the NavigationStack; this view then
    /// skips its own outer NavigationStack and navigationTitle. Defaults to false.
    var embeddedInSplit: Bool = false

    @State private var credentials: [Credential] = []
    @State private var editingItem: Credential?
    /// Id of the credential marked as default; new connections pre-select it.
    @State private var defaultId: String? = AppDefaults.defaultCredentialId

    private let store = CredentialStore()

    var body: some View {
        if embeddedInSplit {
            content
        } else {
            NavigationStack { content.navigationTitle("凭证") }
        }
    }

    @ViewBuilder
    private var content: some View {
        List {
            ForEach(credentials) { cred in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(cred.displayName)
                            .font(.headline)
                        Text(cred.authMethod == .password ? "密码" : "私钥")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    if cred.id == defaultId {
                        Label("默认", systemImage: "star.fill")
                            .font(.caption)
                            .foregroundColor(.yellow)
                    }
                }
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) {
                        store.delete(id: cred.id)
                        AppDefaults.clearCredentialDefault(id: cred.id)
                        load()
                    } label: {
                        Label("删除", systemImage: "trash")
                    }
                    Button {
                        editingItem = cred
                    } label: {
                        Label("编辑", systemImage: "pencil")
                    }
                    .tint(.orange)
                }
                .swipeActions(edge: .leading) {
                    Button {
                        defaultId = cred.id
                        AppDefaults.defaultCredentialId = cred.id
                    } label: {
                        Label("设为默认", systemImage: "star")
                    }
                    .tint(.yellow)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editingItem = Credential(
                        name: "",
                        username: "",
                        authMethod: .password,
                        password: nil,
                        privateKeyPem: nil,
                        privateKeyPassphrase: nil
                    )
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(item: $editingItem) { cred in
            NavigationStack {
                CredentialEditView(credential: cred, onSave: { saved in
                    store.upsert(saved)
                    editingItem = nil
                    load()
                })
            }
        }
        .onAppear(perform: load)
    }

    private func load() {
        credentials = store.list()
        defaultId = AppDefaults.defaultCredentialId
    }
}

// MARK: - CredentialEditView

struct CredentialEditView: View {
    @State var credential: Credential
    let onSave: (Credential) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section("基本信息") {
                TextField("名称", text: $credential.name)
                TextField("用户名", text: $credential.username)
            }

            Section("认证") {
                Picker("认证方式", selection: $credential.authMethod) {
                    Text("密码").tag(AuthMethod.password)
                    Text("私钥").tag(AuthMethod.privateKey)
                }
                switch credential.authMethod {
                case .password:
                    SecureField("密码", text: Binding(
                        get: { credential.password ?? "" },
                        set: { credential.password = $0.isEmpty ? nil : $0 }
                    ))
                case .privateKey:
                    Text("私钥 PEM")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    TextEditor(text: Binding(
                        get: { credential.privateKeyPem ?? "" },
                        set: { credential.privateKeyPem = $0.isEmpty ? nil : $0 }
                    ))
                    .font(.system(.caption, design: .monospaced))
                    .frame(minHeight: 120)
                    SecureField("密码短语（可选）", text: Binding(
                        get: { credential.privateKeyPassphrase ?? "" },
                        set: { credential.privateKeyPassphrase = $0.isEmpty ? nil : $0 }
                    ))
                }
            }
        }
        .navigationTitle(credential.id.isEmpty ? "新建凭证" : "编辑凭证")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") {
                    onSave(credential)
                }
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") {
                    dismiss()
                }
            }
        }
    }
}

// MARK: - TunnelListView

struct TunnelListView: View {
    /// When embedded in the iPad sidebar, the sidebar owns the NavigationStack; this view then
    /// skips its own outer NavigationStack and navigationTitle. Defaults to false.
    var embeddedInSplit: Bool = false

    @State private var tunnels: [FrpcTunnel] = []
    @State private var editingItem: FrpcTunnel?
    @State private var statusMap: [String: TunnelState] = [:]
    /// Id of the tunnel marked as default; new connections pre-select it.
    @State private var defaultId: String? = AppDefaults.defaultTunnelId

    private let tunnelStore = FrpcTunnelStore()
    private let serverStore = FrpsServerStore()
    private let runtime = TunnelRuntime.shared

    /// Timer for polling tunnel statuses every second.
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        if embeddedInSplit {
            content
        } else {
            NavigationStack { content.navigationTitle("隧道") }
        }
    }

    @ViewBuilder
    private var content: some View {
        List {
            ForEach(tunnels) { tunnel in
                TunnelRowView(
                    tunnel: tunnel,
                    status: statusMap[tunnel.id, default: .stopped],
                    isDefault: tunnel.id == defaultId,
                    serverStore: serverStore,
                    runtime: runtime
                )
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) {
                        runtime.stop(tunnel.id)
                        tunnelStore.delete(id: tunnel.id)
                        AppDefaults.clearTunnelDefault(id: tunnel.id)
                        load()
                    } label: {
                        Label("删除", systemImage: "trash")
                    }
                    Button {
                        editingItem = tunnel
                    } label: {
                        Label("编辑", systemImage: "pencil")
                    }
                    .tint(.orange)
                }
                .swipeActions(edge: .leading) {
                    Button {
                        defaultId = tunnel.id
                        AppDefaults.defaultTunnelId = tunnel.id
                    } label: {
                        Label("设为默认", systemImage: "star")
                    }
                    .tint(.yellow)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editingItem = FrpcTunnel(
                        name: "",
                        serverId: nil,
                        secretKey: "",
                        serverName: "",
                        bindPort: 0
                    )
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(item: $editingItem) { tunnel in
            NavigationStack {
                TunnelEditView(tunnel: tunnel, onSave: { saved in
                    tunnelStore.upsert(saved)
                    editingItem = nil
                    load()
                })
            }
        }
        .onAppear(perform: load)
        .onReceive(timer) { _ in
            refreshStatuses()
        }
    }

    private func load() {
        tunnels = tunnelStore.list()
        defaultId = AppDefaults.defaultTunnelId
        refreshStatuses()
    }

    private func refreshStatuses() {
        var map: [String: TunnelState] = [:]
        for tunnel in tunnels {
            map[tunnel.id] = runtime.status(tunnel.id)
        }
        statusMap = map
    }
}

// MARK: - TunnelRowView

private struct TunnelRowView: View {
    let tunnel: FrpcTunnel
    let status: TunnelState
    var isDefault: Bool = false
    let serverStore: FrpsServerStore
    let runtime: TunnelRuntime

    @State private var errorAlert: String?

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(tunnel.displayName)
                    .font(.headline)
                    .foregroundColor(.primary)
                Text("\(tunnel.serverName) · \(tunnel.bindPort)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            if isDefault {
                Label("默认", systemImage: "star.fill")
                    .font(.caption)
                    .foregroundColor(.yellow)
            }

            // Status indicator
            HStack(spacing: 4) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 8, height: 8)
                Text(statusLabel)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            // Start/Stop button
            Button {
                toggleTunnel()
            } label: {
                Text(status == .running ? "停止" : "启动")
                    .font(.caption)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(status == .running ? Color.red.opacity(0.15) : Color.green.opacity(0.15))
                    .cornerRadius(6)
            }
            .buttonStyle(.plain)
            .disabled(status == .starting)
        }
        .alert("隧道错误", isPresented: .init(get: { errorAlert != nil }, set: { if !$0 { errorAlert = nil } })) {
            Button("确定") { errorAlert = nil }
        } message: {
            Text(errorAlert ?? "")
        }
    }

    private var statusColor: Color {
        switch status {
        case .running:  return .green
        case .starting: return .orange
        case .error:    return .red
        case .stopped:  return .gray
        }
    }

    private var statusLabel: String {
        switch status {
        case .running:  return "运行中"
        case .starting: return "启动中"
        case .error:    return "错误"
        case .stopped:  return "已停止"
        }
    }

    private func toggleTunnel() {
        if status == .running {
            runtime.stop(tunnel.id)
        } else {
            guard let server = serverStore.get(id: tunnel.serverId) else {
                errorAlert = "未找到关联的 frps 服务器配置"
                return
            }
            let params = VisitorParams(
                id: tunnel.id,
                serverAddr: server.serverAddr,
                serverPort: server.serverPort,
                token: server.authToken ?? "",
                serverName: tunnel.serverName,
                secretKey: tunnel.secretKey,
                bindPort: tunnel.bindPort
            )
            do {
                try runtime.start(params)
            } catch {
                errorAlert = "启动失败：\(error.localizedDescription)"
            }
        }
    }
}

// MARK: - TunnelEditView

struct TunnelEditView: View {
    @State var tunnel: FrpcTunnel
    let onSave: (FrpcTunnel) -> Void

    @State private var servers: [FrpsServer] = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section("基本信息") {
                TextField("名称", text: $tunnel.name)
            }

            Section("frps 服务器") {
                Picker("服务器", selection: $tunnel.serverId) {
                    Text("请选择").tag(nil as String?)
                    ForEach(servers) { server in
                        Text(server.displayName).tag(server.id as String?)
                    }
                }
            }

            Section("STCP 代理") {
                TextField("服务名（serverName）", text: $tunnel.serverName)
                SecureField("密钥（secretKey）", text: $tunnel.secretKey)
                TextField("本地端口（bindPort）", value: $tunnel.bindPort, format: .number.grouping(.never))
                    .keyboardType(.numberPad)
            }
        }
        .navigationTitle(tunnel.id.isEmpty ? "新建隧道" : "编辑隧道")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") {
                    onSave(tunnel)
                }
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") {
                    dismiss()
                }
            }
        }
        .onAppear {
            servers = FrpsServerStore().list()
        }
    }
}

// MARK: - ServerListView

struct ServerListView: View {
    /// When embedded in the iPad sidebar, the sidebar owns the NavigationStack; this view then
    /// skips its own outer NavigationStack and navigationTitle. Defaults to false.
    var embeddedInSplit: Bool = false

    @State private var servers: [FrpsServer] = []
    @State private var editingItem: FrpsServer?

    private let store = FrpsServerStore()

    var body: some View {
        if embeddedInSplit {
            content
        } else {
            NavigationStack { content.navigationTitle("服务器") }
        }
    }

    @ViewBuilder
    private var content: some View {
        List {
            ForEach(servers) { server in
                VStack(alignment: .leading, spacing: 2) {
                    Text(server.displayName)
                        .font(.headline)
                    Text("\(server.serverAddr):\(server.serverPort)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) {
                        store.delete(id: server.id)
                        load()
                    } label: {
                        Label("删除", systemImage: "trash")
                    }
                    Button {
                        editingItem = server
                    } label: {
                        Label("编辑", systemImage: "pencil")
                    }
                    .tint(.orange)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editingItem = FrpsServer(
                        name: "",
                        serverAddr: "",
                        serverPort: 7000,
                        authToken: nil
                    )
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(item: $editingItem) { server in
            NavigationStack {
                ServerEditView(server: server, onSave: { saved in
                    store.upsert(saved)
                    editingItem = nil
                    load()
                })
            }
        }
        .onAppear(perform: load)
    }

    private func load() {
        servers = store.list()
    }
}

// MARK: - ServerEditView

struct ServerEditView: View {
    @State var server: FrpsServer
    let onSave: (FrpsServer) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section("基本信息") {
                TextField("名称", text: $server.name)
                TextField("服务器地址", text: $server.serverAddr)
                    .keyboardType(.URL)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                TextField("端口", value: $server.serverPort, format: .number.grouping(.never))
                    .keyboardType(.numberPad)
            }

            Section("认证") {
                SecureField("Auth Token（可选）", text: Binding(
                    get: { server.authToken ?? "" },
                    set: { server.authToken = $0.isEmpty ? nil : $0 }
                ))
            }
        }
        .navigationTitle(server.id.isEmpty ? "新建服务器" : "编辑服务器")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") { onSave(server); dismiss() }
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") { dismiss() }
            }
        }
    }
}

// MARK: - MainTabView

struct MainTabView: View {
    let onConnect: (ConnectionConfig) -> Void
    let onBrowseFiles: (ConnectionConfig) -> Void

    var body: some View {
        TabView {
            ConnectionListView(onConnect: onConnect, onBrowseFiles: onBrowseFiles)
                .tabItem {
                    Label("连接", systemImage: "terminal")
                }

            CredentialListView()
                .tabItem {
                    Label("凭证", systemImage: "key")
                }

            TunnelListView()
                .tabItem {
                    Label("隧道", systemImage: "network")
                }

            ServerListView()
                .tabItem {
                    Label("服务器", systemImage: "server.rack")
                }

            SettingsView()
                .tabItem {
                    Label("设置", systemImage: "gearshape")
                }
        }
    }
}
