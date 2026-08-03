# iOS Port — Handoff

**状态：端到端跑通 + iPad 原生适配 + tmux 探测/窗口菜单 + SFTP + 加密备份/恢复 + 环境自检 + 连接内凭证管理 + 应用更新检查。真机 iPad/iPhone 目标编译验证。**
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
- ✅ **SFTP 文件浏览器**：目录导航 / 面包屑 / 排序 / 隐藏文件 / 文本与图片预览 / 下载
- ✅ **配置备份/恢复**：Android v1 兼容加密格式 / 全量合并恢复 / Keychain 密钥 / 文件导入导出
- ✅ **环境自检**：frpc / CryptoKit / Keychain / 网络 / 电源后台 / 配置引用与端口检查
- ✅ **终端 tmux 菜单**：新建/上一个/下一个/窗口列表/重命名 + 重连/断开/SFTP/键盘
- ✅ **连接内凭证管理**：新建/编辑凭证，保存后自动选中并同步用户名与认证方式
- ✅ **应用更新检查**：GitHub release + 可选 Keychain token + 直连/代理回退 + 发布说明/发布页
- ✅ **原生 App Icon**：复用 Android 终端图标设计，1024×1024 无透明 PNG，可由脚本重生成

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

### 19. Xcode 26.6 的 clang 能力探测会死锁
Xcode 26.6 会卡在 `ExecuteExternalTool ... clang -v -E -dM`：build service 等待 clang
退出后才读取 pipe，而新版 clang 的 verbose 输出会先填满 pipe，双方永久等待。进程采样可见
clang 阻塞在 `write`，`SWBBuildService` 主线程空闲；相同 clang 命令直接运行则立即完成。

**解决方案**：项目级 `CC` 指向 `scripts/xcode_clang_probe_wrapper.sh`。wrapper 只在参数同时
包含 `-v -E -dM` 时移除 `-v`，其余参数和所有真实编译均原样转发到当前 Xcode 的 clang。
同时设置 `CLANG_ENABLE_EXPLICIT_MODULES = NO`，避免 Xcode 因 wrapper 路径误判编译器。
模拟器和 generic iOS device 构建均已验证通过。

### 20. 终端内打开文件必须复用 SSH transport
frpc STCP visitor 通常只能稳定承载一个 TCP 连接。终端已连接时再新建独立 SSH/SFTP
连接可能卡住或报 `ioOnClosedChannel`。`SshTerminalConnector.openSftpSession()` 在现有
`SSHClient` 上新增 SFTP subsystem；`SftpSession` 关闭时只关 subsystem，不关父 SSH，PTY
因此保持在线。没有活动终端时，文件浏览器才独立建立 SSH，并自行解析凭证、启动隧道和重定向端口。

文件浏览在 iPhone/iPad 都以 sheet 展示。iPad 不替换 detail 中的 `TerminalHostingView`，
因为移除它会触发 `viewWillDisappear` 并主动关闭终端。

### 21. 备份入口的 presentation 状态必须放在独立页面
最初把带 `@State` 的 `ConfigBackupSection` 直接放进设置 `Form`，并把 `.sheet`、
`.fileImporter`、`.fileExporter` 挂在该 `Section` 上。iOS 26 会展平自定义 Section；点击
“导出”虽然命中 Button，但 presentation 状态没有稳定保留，口令 sheet 不出现。

**解决方案**：设置页只保留“配置备份” NavigationLink，所有状态和 presentation modifier
由独立 `ConfigBackupView` 持有。iPhone 17 和 iPad Pro 13-inch UI 测试均已覆盖入口、导出按钮、
两个安全输入框和取消/确认按钮。

### 22. Keychain 往返测试不能禁用模拟器签名
`CODE_SIGNING_ALLOWED=NO` 可以用于纯编译和 generic device 构建，但模拟器 App 会失去
Keychain entitlement。此时 `CredentialStore` 的秘密字段读回 nil，备份往返测试会假失败。
运行完整测试时不要传该参数；固定 Android/OpenSSL 加密向量测试本身不依赖 Keychain。

### 23. `NWPathMonitor.cancel()` 不会恢复等待中的 continuation
环境自检最初考虑用 task group 竞争网络回调和超时，但 loser 被取消时，单独调用
`NWPathMonitor.cancel()` 不会触发 `pathUpdateHandler`，等待它的 continuation 永远不恢复，
task group 退出时仍会等待 child，最终整次自检卡死。

**解决方案**：`NetworkPathProbeOperation` 让网络回调和 2 秒超时都进入同一个 `finish`，用
`NSLock` 保证只取走并恢复 continuation 一次，然后取消 monitor。不要改回只取消不 resume 的结构。

### 24. iOS 更新检查不能照搬 APK 自安装
`UpdateChecker` 与 Android 保持同一 GitHub release API、数字版本比较、可选 token 和
`p.all3n.top` 回退规则；401/403/404/429 是确定响应，不走代理重试。token 使用 Keychain，
并复用 Android 兼容备份原先保留的同一 account。

iOS 应用不能下载后覆盖自己的可执行文件，因此 `UpdateCheckView` 在发现新版时展示发布说明并
打开 release 页面，安装仍走当前使用的签名/TestFlight/侧载渠道。`project.yml` 显式设置
`MARKETING_VERSION` 和 `CURRENT_PROJECT_VERSION`，否则生成的 Info.plist 可能使用 Xcode
默认版本，导致比较错误。

### 25. iPad 打开终端后默认收起侧栏
`IPadShell` 用受控的 `NavigationSplitViewVisibility`：`activeTerminal` 出现时切到
`.detailOnly`，避免侧栏挤占终端宽度；终端关闭时恢复 `.all`。用户仍可用系统侧栏按钮手动展开，
展开不会卸载 detail 中的 `TerminalHostingView`，因此不会断开 PTY。

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
| **SFTP** | Citadel `SSHClient.openSFTP()` + 长连接 actor 会话 | 0.12.1 |

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
    │   ├── ConfigBackup.swift         Android 兼容 PBKDF2 + AES-GCM 备份/合并恢复
    │   ├── EnvironmentCheck.swift     6 项自检 + 纯配置完整性验证
    │   ├── UpdateChecker.swift        GitHub release 查询/版本比较/代理回退/Keychain token
    │   └── SettingsStore.swift        终端字号/常亮/tmuxPrefix/swipeWindows/previewTheme
    ├── SSH/
    │   ├── SshTerminalConnector.swift SSH + TOFU + PTY + exec（PTY 方案！见 #10-12）
    │   ├── SftpSession.swift          长连接 SFTP actor + 浏览/预览/流式下载
    │   └── TmuxSessionProbe.swift     tmux 探测（\r\n 归一化 + ANSI 剥离，见 #13）
    └── UI/
        ├── RootView.swift             size-class 分叉：IPadShell / CompactShell
        ├── TerminalHostingView.swift  UIViewControllerRepresentable 桥
        ├── ManagementViews.swift      4+1 tab + CRUD + tmux 探测 + 默认值 + 复制/删除/保存并连接
        ├── ConfigBackupView.swift     加密导出/文件导入/口令 sheet
        ├── EnvironmentCheckView.swift 自检摘要/逐项状态/重新检测
        ├── FileBrowserView.swift      iPhone/iPad SFTP 浏览、预览、下载 sheet
        ├── UpdateCheckView.swift      iPhone/iPad 更新检查、发布说明与 release 入口
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
| SFTP | ✅ 完整 | ✅ 浏览/预览/下载；终端内复用 SSH transport |

## iOS vs Android 功能缺口（当前状态）

**已补齐**：
- ✅ iPad 适配（NavigationSplitView）
- ✅ 设置页（字号/常亮/tmux prefix/swipe windows + 关于 + 帮助）
- ✅ 默认凭证/隧道 + 新建连接预填
- ✅ 连接管理：保存并连接 / 复制 / 编辑器内删除
- ✅ 终端：字号 / 屏幕常亮 / 滑动切 tmux 窗口
- ✅ SFTP 文件浏览器（默认目录回退、面包屑、上级/主页/刷新、排序、隐藏项、预览、下载、复制路径）
- ✅ 配置备份/恢复（与 Android 共用 v1 envelope；PBKDF2-HMAC-SHA256 + AES-256-GCM）
- ✅ 环境自检（按 iOS 架构覆盖 frpc、加密、Keychain、网络、电源后台、配置完整性）
- ✅ tmux 窗口菜单（新建/切换/列表/重命名）
- ✅ 连接编辑器内新建/编辑凭证并自动选中
- ✅ 应用更新检查（GitHub release + token + 直连/代理回退；iOS 通过原签名渠道安装）

**仍缺失（优先级排序）**：
1. 多语言（当前约 287 个中文字符串，含 SwiftUI、UIKit 与动态错误状态）

## 构建注意事项

```bash
./scripts/build_frpc_ios.sh             # 先编 Frpclib.xcframework
./scripts/build_frpc_ios.sh --simulator # 加模拟器 slice（模拟器调试需要）
xcrun swift scripts/generate_ios_app_icon.swift ios/AiDevMob/Assets.xcassets/AppIcon.appiconset/AppIcon.png
cd ios && xcodegen generate             # 加了新 .swift 文件要重跑
```

**模拟器编译**：
```bash
cd ios && xcodebuild -scheme AiDevMob \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -configuration Debug build CODE_SIGNING_ALLOWED=NO
```

**测试（需要模拟器正常签名，不能加 `CODE_SIGNING_ALLOWED=NO`）**：
```bash
cd ios && xcodebuild -quiet -scheme AiDevMob \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -configuration Debug test

# iPad 布局回归
cd ios && xcodebuild -quiet -scheme AiDevMob \
  -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' \
  -configuration Debug test \
  -only-testing:AiDevMobUITests/ConfigBackupUITests \
  -only-testing:AiDevMobUITests/EnvironmentCheckUITests \
  -only-testing:AiDevMobUITests/ConnectionCredentialUITests \
  -only-testing:AiDevMobUITests/UpdateCheckUITests
```

**真机安装**：Xcode GUI → Settings → Accounts 登录 Apple ID → 选设备 → ⌘R。
`project.yml` 里 `DEVELOPMENT_TEAM` 留空（CLI 编译用空 team 避免 GatherProvisioningInputs 卡死；
真机签名在 Xcode GUI 里选 Personal Team 即可）。

**⚠️ CLI 编译坑**：`DEVELOPMENT_TEAM` 不留空时，`xcodebuild` 会卡在 `GatherProvisioningInputs`
步骤（即使 `CODE_SIGNING_ALLOWED=NO`）。所以 project.yml 必须留空，签名只在 Xcode GUI 里设。

## SFTP 实现所用 API

Citadel 0.12.1 的 SFTP 客户端 API（`SSHClient.openSFTP()`）：
- `sftp.listDirectory(atPath:) -> [SFTPMessage.Name]`（需 `.flatMap { $0.components }` 扁平化）
- `sftp.getAttributes(at:) -> SFTPFileAttributes`（size/permissions/mtime）
- `sftp.withFile(filePath:flags:) { file in file.readAll() }`（读文件）
- `sftp.createDirectory(atPath:)` / `sftp.remove(at:)` / `sftp.rename(at:to:)`
- 协议版本仅 v3；15 秒连接超时（复用单个 SFTPClient）；写操作 32KB 分块
- SFTP 是 SSH subsystem（同一 TCP 连接上的子通道），不像 exec 那样开新连接，预期在 STCP 上更稳
