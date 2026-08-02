# iOS Port — Handoff

**状态：端到端跑通 + iPad 原生适配 + tmux 探测修复 + 设置页 + 默认凭证/隧道。真机 iPad/iPhone 验证。**
**最后更新：2026-08-02**

---

## 当前已验证可用

- ✅ frpc STCP 隧道：进程内 gomobile，登录 frps + STCP 撮合 + 本地端口监听
- ✅ SSH：Citadel 0.12.1，密码认证 + TOFU host key（Ed25519/ECDSA only，**不支持 RSA host key**）
- ✅ 终端：SwiftTerm 1.15.0，VT100 渲染 + PTY + 键盘输入
- ✅ tmux attach（`new-session -A`）
- ✅ opencode 等全屏 TUI 可用
- ✅ tmux 会话探测（连接编辑页，PTY 方案——见下方踩坑 #10）
- ✅ 额外按键行（ESC/CTRL/←↓↑→/TAB/^C/^D/Home/End/PgUp/PgDn/Enter/y/n/⌨收键盘），横向可滚动
- ✅ 滚动回滚：自定义 wheel 手势，TUI 程序发鼠标滚轮事件（对齐 Termux doScroll）
- ✅ 粘贴：PasteAwareTerminalView 重写 paste，LF→CR + 剥离 ESC/C1（对齐 Termux）
- ✅ 键盘避让：手动 keyboardWillChangeFrame，PTY resize 150ms 去抖
- ✅ **iPad 原生适配**：NavigationSplitView 分栏（sidebar + detail），iPhone 不受影响
- ✅ **默认凭证/隧道**：列表左滑"设为默认"，新建连接自动预填
- ✅ **设置页**：终端字号/保持常亮/tmux 前缀键/滑动切 tmux 窗口 + 关于 + 帮助
- ✅ **连接管理增强**：保存并连接 / 左滑复制连接 / 编辑器内删除
- ✅ **终端增强**：字号控制 / 屏幕常亮 / 左右滑切 tmux 窗口

---

## 本轮（8/1-8/2）新增的 bug 和踩坑（换 session 必读）

### 10. tmux 探测不能用 `withExec`，必须用 PTY（最关键）
Citadel 的 `client.withExec(command)` 在 frpc STCP 隧道上**始终失败**，报
`NIOCore.ChannelError error 6`（`ioOnClosedChannel`）。同一个隧道、同一套凭证，
终端的 `withPTY` 能连，exec 不能。根因是 Citadel/NIOSSH 在 STCP 链路上开 exec 子通道
有问题。

**解决方案**：`SshTerminalConnector.exec()` 改成用 `withPTY` 跑命令——开一个临时 PTY，
发送命令 + 唯一哨兵标记（`AIDEVMOB_PROBE_DONE_<UUID>`），读输出直到哨兵出现，发 `exit`
关闭 shell，然后 `client.close()`。详见 `execOnce`。

### 11. PTY 必须在 connect 的同一 async 上下文里跑（不能放 TaskGroup child task）
最初把 `client.withPTY` 放在 `withThrowingTaskGroup` 的 child task 里跑，结果 PTY channel
打开即报 `ioOnClosedChannel`。Citadel 的 SSHClient 底层 NIO event loop 在不同的
structured-concurrency 上下文里行为异常。**必须直接 `try await client.withPTY`**，和
`SSHClient.connect` 在同一个 async 上下文连续 await（和主连接 `start()` 一样的结构）。
超时用一个独立 `Task` 做（8 秒后 `client.close()` 强制结束），不要用 task group 的兄弟 task。

### 12. SSH client 必须同步 close（STCP 单连接限制）
frpc STCP visitor **只接受一个并发连接**。如果 `execOnce` 用 `defer { Task { client.close() } }`
（fire-and-forget），close 还没执行完就返回，下一次探测（或 shell flag 重试）就连不上。
必须 `try? await client.close()` 同步等待完成后再返回。`defer` 不能 `await`，所以用
`do { ... try? await client.close(); return result } catch { try? await client.close(); throw }`。

### 13. PTY 输出解析：必须归一化 `\r\n` + 剥离 ANSI 转义码
PTY 输出和 exec 不同——带大量 ANSI 转义码（颜色 `\e[1m`、光标移动 `\e[4A`、OSC 序列 `\e]2;...`），
且换行用 `\r\n`（甚至裸 `\r`）。`TmuxSessionProbe.parse` 用 `split(separator: "\n")` 切行，
但 `\r` 留在行尾把多行**粘在一起**，导致整个输出被当成一行、解析出 0 个会话。

**修复**：
1. `SshTerminalConnector.cleanProbeOutput` 用 `stripANSIEscape` 剥掉所有 CSI/OSC/ESC 序列。
2. `TmuxSessionProbe.parse` 开头把 `\r\n` 和裸 `\r` 归一化成 `\n` 再 split。
3. `parse` 加 guard：丢弃名字含 `#`/`{`/`}` 的行（防 PTY 回显的 `#{session_name}|...` 误匹配）。

### 14. `ensureTunnel` 必须始终重定向端口
探测的 `ensureTunnel`（在 `ConnectionEditView` 里）和主连接的 `redirectedThroughTunnel`
（在 `AppCoordinator` 里）必须**逻辑一致**：无论隧道是否已在运行，都要把 host/port 改成
`127.0.0.1:<bindPort>`。之前 `ensureTunnel` 在隧道已运行时直接返回未重定向的 config，
导致探测 SSH 拨原始 host:port（不可达）→ ChannelError。

### 15. iPad 适配：`horizontalSizeClass` 分叉 + NavigationSplitView
`RootView` 按 `@Environment(\.horizontalSizeClass)` 分叉：
- compact（iPhone）→ `CompactShell`：TabView + `.fullScreenCover`（和以前 UIKit push 视觉一致）
- regular（iPad）→ `IPadShell`：`NavigationSplitView`，sidebar 是分类菜单 → 列表，detail 是终端

`AppCoordinator` 从 UIKit 的 `UINavigationController.push` 改成 `@Published var activeTerminal`
（ObservableObject）。终端 VC 通过 `TerminalHostingView`（`UIViewControllerRepresentable`）
桥进 SwiftUI，coordinator 持有 VC 整个生命周期，SwiftUI 只显示不重建。

四个 ListView 加了 `embeddedInSplit: Bool` 参数，嵌入 sidebar 时跳过自己的 NavigationStack。

### 16. 按键行在 iPad 上居中限宽
`ExtraKeysAccessoryBar.applyWidthClass()` 按 size class 切换约束：
- compact（iPhone）：全宽 + 横滚（按键太多放不下）
- regular（iPad）：居中 + 限宽 760pt（贴近 iPad 软键盘宽度）
所有约束是 stored properties，`traitCollectionDidChange` 时只 toggle `isActive`，不重建。

### 17. 新建连接 `id` 要传空串
`ConnectionConfig.init` 的 `id` 有默认值 `UUID().uuidString`。判断"是否新建连接"用 `config.id.isEmpty`，
但新建时如果不显式传 `id: ""`，默认值让 `isEmpty` 永远为 false → 默认凭证/隧道预填 + "新建连接"标题
都不触发。`ConnectionListView` 新建时必须显式传 `id: ""`，保存时补真实 UUID。

### 18. port 输入去掉千分位逗号
`TextField("端口", value: $port, format: .number)` 会显示千分位逗号（如 `7,000`）。
改成 `format: .number.grouping(.never)`。

---

## 早期 bug（仍有效，保留）

### 1. frpc `common.User` 不能设
`frpclib.go` 之前设了 `User: "aidevmob"`。STCP visitor 匹配名是 `{user}.{serverName}`，
设了 user 导致撮合失败。**删掉 `User` 字段**。

### 2. 端口重定向（iOS 独有）
`AppCoordinator.redirectedThroughTunnel(_:)` —— 有隧道时把 SSH host/port 改成 `127.0.0.1:<bindPort>`。
`connect` 和 `reconnect` 两条路径都要调。探测的 `ensureTunnel` 也要（见 #14）。

### 3. Auto Layout：setupExtraKeysBar 必须在 setupTerminalView 之前

### 4. 必须调 `TerminalView.feed` 不是 `Terminal.feed`
`TerminalView.feed(byteArray:)` 内部 `feedPrepare()/feedFinish()` 才 resume CADisplayLink。

### 5. 状态在 feed 时翻转
`connector.start()` 阻塞到会话结束，`.connected` 在 `feed()` 收到首字节时翻转。

### 6. 按键行滚动：`delaysContentTouches` 必须 true

### 7. 滚动手势：wheel event 而非 drag

### 8. PTY resize 去抖 150ms

### 9. 启动命令延迟 300ms

---

## 技术栈

| 层 | 选型 | 版本 |
|----|------|------|
| **frpc 隧道** | gomobile bind → `Frpclib.xcframework`，进程内 `client.Service` | frp v0.70.1 |
| **SSH** | Citadel（SPM），底层 Wellz26/swift-nio-ssh fork | 0.12.1 |
| **终端** | SwiftTerm（SPM） | 1.15.0 |
| **UI** | UIKit（终端页）+ SwiftUI（管理页 + 设置页 + iPad split-view） | iOS 17+ |
| **工程** | xcodegen（`project.yml` → `AiDevMob.xcodeproj`） | — |
| **SFTP** | Citadel 内置 `SSHClient.openSFTP()`（**已验证可用**，阶段 2 待接 UI） | 0.12.1 |

## 已交付文件

```
ios/
├── AGENTS.md                          构建前置/架构 gotchas（含 iPad 适配说明）
├── HANDOFF.md                         本文件
├── project.yml                        xcodegen 工程定义（DEVELOPMENT_TEAM 留空，Xcode GUI 里设）
├── AiDevMob.xcodeproj/                生成的 Xcode 工程
├── frpcllib/                          frpc gomobile 封装（不设 User!）
├── Frameworks/                        Frpclib.xcframework（gitignored，build_frpc_ios.sh 生成）
└── AiDevMob/
    ├── AppDelegate.swift              @main → RootView
    ├── AppRoot.swift                  AppCoordinator (ObservableObject) + redirect + reconnect
    ├── Frpc/TunnelRuntime.swift       frpclib Swift 封装
    ├── Models/Models.swift            4 数据模型
    ├── Storage/
    │   ├── Stores.swift               Keychain + JSON 文件存储
    │   └── AppDefaults.swift          默认凭证/隧道 ID（UserDefaults）
    ├── Settings/
    │   └── SettingsStore.swift        终端字号/常亮/tmuxPrefix/swipeWindows/previewTheme
    ├── SSH/
    │   ├── SshTerminalConnector.swift SSH + TOFU + PTY + exec（PTY 方案！见 #10-12）
    │   └── TmuxSessionProbe.swift     tmux 探测（\r\n 归一化 + ANSI 剥离，见 #13）
    └── UI/
        ├── RootView.swift             size-class 分叉：IPadShell / CompactShell
        ├── TerminalHostingView.swift  UIViewControllerRepresentable 桥
        ├── ManagementViews.swift      4+1 tab + CRUD + tmux 探测 + 默认值 + 复制/删除/保存并连接
        ├── SettingsView.swift         设置页（字号 stepper/常亮/tmux prefix/swipe + 关于 + 帮助）
        └── TerminalViewController.swift 终端页（字号/常亮/滑切窗口 + 顶栏/按键行/键盘/wheel/粘贴）
```

## iOS vs Android 协议层差异（核心）

| 维度 | Android | iOS |
|------|---------|-----|
| frpc 运行 | fork 子进程（libfrpc.so） | in-process（gomobile） |
| frpc 版本 | v0.70.1 | v0.70.1（**一致**） |
| SSH 库 | sshj 0.40.0 + BouncyCastle | Citadel 0.12.1（NIOSSH fork） |
| host key | RSA + Ed25519 + ECDSA | **Ed25519 + ECDSA only** |
| keepalive | `keepAliveInterval = 15` | **无**（Citadel 不暴露） |
| exec 通道 | sshj exec（正常） | **Citadel withExec 在 STCP 上失败**，用 PTY 替代（见 #10） |
| SFTP | ✅ 完整 | Citadel 有 `openSFTP()`，**已验证可用**，UI 待接 |

## iOS vs Android 功能缺口（当前状态）

**已补齐**：
- ✅ iPad 适配（NavigationSplitView）
- ✅ 设置页（字号/常亮/tmux prefix/swipe windows + 关于 + 帮助）
- ✅ 默认凭证/隧道 + 新建连接预填
- ✅ 连接管理：保存并连接 / 复制 / 编辑器内删除
- ✅ 终端：字号 / 屏幕常亮 / 滑动切 tmux 窗口

**仍缺失（优先级排序）**：
1. **SFTP 文件浏览器**（Citadel `openSFTP()` 已验证可用，需建 FileBrowserView）—— 计划阶段 2
2. **配置备份/恢复**（加密导出/导入全部配置）—— 计划阶段 3
3. **环境自检**（网络/frpc/配置完整性）—— 计划阶段 3
4. tmux 窗口菜单（列表/重命名）—— 次要
5. 内联新建凭证（连接编辑里直接建凭证）—— 次要
6. 应用内更新检查（iOS 通常走 App Store/TestFlight，优先级低）
7. 多语言（当前硬编码中文）

## 构建注意事项

```bash
./scripts/build_frpc_ios.sh             # 先编 Frpclib.xcframework
./scripts/build_frpc_ios.sh --simulator # 加模拟器 slice（模拟器调试需要）
cd ios && xcodegen generate             # 加了新 .swift 文件要重跑
```

**模拟器编译**：
```bash
cd ios && xcodebuild -scheme AiDevMob \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -configuration Debug build CODE_SIGNING_ALLOWED=NO
```

**真机安装**：Xcode GUI → Settings → Accounts 登录 Apple ID → 选设备 → ⌘R。
`project.yml` 里 `DEVELOPMENT_TEAM` 留空（CLI 编译用空 team 避免 GatherProvisioningInputs 卡死；
真机签名在 Xcode GUI 里选 Personal Team 即可）。

**⚠️ CLI 编译坑**：`DEVELOPMENT_TEAM` 不留空时，`xcodebuild` 会卡在 `GatherProvisioningInputs`
步骤（即使 `CODE_SIGNING_ALLOWED=NO`）。所以 project.yml 必须留空，签名只在 Xcode GUI 里设。

## SFTP 已验证的 API（阶段 2 用）

Citadel 0.12.1 的 SFTP 客户端 API（`SSHClient.openSFTP()`）：
- `sftp.listDirectory(atPath:) -> [SFTPMessage.Name]`（需 `.flatMap { $0.components }` 扁平化）
- `sftp.getAttributes(at:) -> SFTPFileAttributes`（size/permissions/mtime）
- `sftp.withFile(filePath:flags:) { file in file.readAll() }`（读文件）
- `sftp.createDirectory(atPath:)` / `sftp.remove(at:)` / `sftp.rename(at:to:)`
- 协议版本仅 v3；15 秒连接超时（复用单个 SFTPClient）；写操作 32KB 分块
- SFTP 是 SSH subsystem（同一 TCP 连接上的子通道），不像 exec 那样开新连接，预期在 STCP 上更稳
