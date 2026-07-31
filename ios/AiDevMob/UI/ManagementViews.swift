import SwiftUI

// MARK: - ConnectionListView

struct ConnectionListView: View {
    let onConnect: (ConnectionConfig) -> Void

    @State private var connections: [ConnectionConfig] = []
    @State private var editingItem: ConnectionConfig?

    private let store = ConnectionStore()

    var body: some View {
        NavigationStack {
            List {
                ForEach(connections) { config in
                    Button {
                        onConnect(config)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(config.displayName)
                                .foregroundColor(.primary)
                                .font(.headline)
                            HStack(spacing: 4) {
                                Text("\(config.host):\(config.port)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                if !config.tmuxSession.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                    Text("·")
                                        .foregroundColor(.secondary)
                                    Text("tmux")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
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
                }
            }
            .navigationTitle("连接")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        editingItem = ConnectionConfig(
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
                }
            }
            .sheet(item: $editingItem) { config in
                NavigationStack {
                    ConnectionEditView(config: config, onSave: { saved in
                        store.upsert(saved)
                        editingItem = nil
                        load()
                    })
                }
            }
            .onAppear(perform: load)
        }
    }

    private func load() {
        connections = store.list()
    }
}

// MARK: - ConnectionEditView

struct ConnectionEditView: View {
    @State var config: ConnectionConfig
    let onSave: (ConnectionConfig) -> Void

    @State private var credentials: [Credential] = []
    @State private var tunnels: [FrpcTunnel] = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section("基本信息") {
                TextField("名称", text: $config.name)
                TextField("主机", text: $config.host)
                TextField("端口", value: $config.port, format: .number)
                    .keyboardType(.numberPad)
            }

            Section("认证") {
                Picker("凭证", selection: $config.credentialId) {
                    Text("无").tag(nil as String?)
                    ForEach(credentials) { cred in
                        Text(cred.displayName).tag(cred.id as String?)
                    }
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

            Section("终端") {
                TextField("Tmux 会话名", text: $config.tmuxSession)
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
                Button("保存") {
                    onSave(config)
                }
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") {
                    dismiss()
                }
            }
        }
        .onAppear {
            credentials = CredentialStore().list()
            tunnels = FrpcTunnelStore().list()
        }
    }
}

// MARK: - CredentialListView

struct CredentialListView: View {
    @State private var credentials: [Credential] = []
    @State private var editingItem: Credential?

    private let store = CredentialStore()

    var body: some View {
        NavigationStack {
            List {
                ForEach(credentials) { cred in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(cred.displayName)
                            .font(.headline)
                        Text(cred.authMethod == .password ? "密码" : "私钥")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            store.delete(id: cred.id)
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
                }
            }
            .navigationTitle("凭证")
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
    }

    private func load() {
        credentials = store.list()
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
    @State private var tunnels: [FrpcTunnel] = []
    @State private var editingItem: FrpcTunnel?
    @State private var statusMap: [String: TunnelState] = [:]

    private let tunnelStore = FrpcTunnelStore()
    private let serverStore = FrpsServerStore()
    private let runtime = TunnelRuntime.shared

    /// Timer for polling tunnel statuses every second.
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            List {
                ForEach(tunnels) { tunnel in
                    TunnelRowView(
                        tunnel: tunnel,
                        status: statusMap[tunnel.id, default: .stopped],
                        serverStore: serverStore,
                        runtime: runtime
                    )
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            runtime.stop(tunnel.id)
                            tunnelStore.delete(id: tunnel.id)
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
                }
            }
            .navigationTitle("隧道")
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
    }

    private func load() {
        tunnels = tunnelStore.list()
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
                TextField("本地端口（bindPort）", value: $tunnel.bindPort, format: .number)
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
    @State private var servers: [FrpsServer] = []
    @State private var editingItem: FrpsServer?

    private let store = FrpsServerStore()

    var body: some View {
        NavigationStack {
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
            .navigationTitle("服务器")
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
                TextField("端口", value: $server.serverPort, format: .number)
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

    var body: some View {
        TabView {
            ConnectionListView(onConnect: onConnect)
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
        }
    }
}
