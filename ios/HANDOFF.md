# iOS Port — Handoff

**状态：frpc-on-iOS 技术方案已验证通过，卡在「装不了 Xcode」这一步。其余全部就绪。**
**最后更新：2025-07-31**

---

## 这是什么项目

把现有 Android app（`app/`，Kotlin，用 frpc STCP visitor 打隧道 + sshj 连 SSH + Termux 终端渲染远程 tmux）移植到 iOS。**自用 sideload，不进 App Store。**

## 已确定的技术栈（研究 + 实证，非猜测）

| 层 | 选型 | 状态 |
|----|------|------|
| **frpc 隧道** | gomobile bind 薄封装 → `Frpclib.xcframework`，进程内跑 `client.Service`（iOS 不能 fork 子进程，这是和 Android 的根本差异） | ✅ Go 代码已写并验证（见下） |
| **SSH** | **Citadel**（纯 Swift, MIT, SPM）— 密码/OpenSSH 私钥认证、`SSHHostKeyValidator.custom`（TOFU 钩子）、`withPTY` 开 shell channel | 待集成 |
| **终端** | **SwiftTerm**（纯 Swift, MIT, SPM）— 原生 UIKit `TerminalView`，DECCKM 原生处理（箭头键模式不用自己解析），注入 I/O 架构同 Termux | 待集成 |
| **凭据存储** | iOS Keychain（对标 Android 的 EncryptedSharedPreferences） | 待写 |

**研究来源（已 reconcile）：** gomobile+frp 可行性、iOS SSH/终端选型均由 librarian 跑了两轮专项研究，结论有 GitHub 先例支撑（`duanhai/frpc-IOS-1` 2018、`kamoguai/frpc_mobile` 2024 均用 gomobile 出 framework）。

## 已验证的关键事实（这些不用再查）

```
go vet ./...                         → EXIT 0   我对 frp v0.70.1 的 API 调用正确
GOOS=ios GOARCH=arm64 CGO_ENABLED=1
  go build -o /dev/null ./...        → EXIT 0   frp 整棵依赖树（含 vnet/wireguard）干净编译 iOS
gobind tool                          → 已解析   gomobile bind 就绪
gomobile bind -target=ios/arm64      → "requires Xcode"  ← 唯一卡点（预期内）
```

研究中担心的两个风险**都已解除**：
1. ~~frp v0.70.1 要 Go 1.25，gomobile 可能跟不上~~ → 我们装的是 Go 1.26.5，更新。
2. ~~frpc 能否跑在 iOS 进程内~~ → 已实证整树编译通过。

## 当前卡点 + 两条出路

**卡点：装不了 Xcode。** 你当前 macOS **15.6.1**（Sequoia），App Store 里最新 Xcode 26.x 要求 **macOS 26.2+**。你选了升级 macOS。

| 环境 | 现状 |
|------|------|
| macOS | 15.6.1（Sequoia）—— 需升到 26.x |
| 芯片 | arm64（Apple silicon） |
| 磁盘 | 还剩 155GB（够） |
| Go | 1.26.5（brew 装的，已就位） |
| gomobile/gobind | 已装（`~/go/bin/`，已就位） |
| Command Line Tools | 16.4（有，但不够——gomobile bind 要完整 Xcode 的 iPhoneOS SDK） |

**出路 A（你已选）：升级 macOS 到 26.x**，然后 App Store 装 Xcode 26.x。
**出路 B（备选，不动系统）：** 从 developer.apple.com/download/all 下载 **Xcode 16.4**（跑在 macOS 15 上，带 iOS 18 SDK，足够做 sideload app）。免费 Apple ID 登录即可下。

---

## 已交付的文件

| 文件 | 作用 |
|------|------|
| `ios/frpclib/frpclib.go` | frpc 的 gomobile 封装：多隧道管理（`StartTunnel`/`StopTunnel`/`Status`/`Logs`/`IsRunning`），对标 Android 的 `FrpcRuntime` + `FrpcVisitorService`。每个隧道一个独立 `client.Service`，互不影响 |
| `ios/frpclib/go.mod` / `go.sum` | 锁定 frp v0.70.1 + gobind tool 依赖 |
| `scripts/build_frpc_ios.sh` | 出 `Frpclib.xcframework`（对标 Android 的 `build_frpc.sh`）。已验证：缺 Xcode 时干净报错并给修复命令 |
| `ios/AGENTS.md` | iOS 构建前置/架构 gotchas（已写完） |
| `.gitignore`（改） | 排除 `ios/Frameworks/`（xcframework 不进 git） |
| `AGENTS.md`（改） | 根指南已指向 `ios/AGENTS.md` |

### frpclib 关键实现点（改它时必读）

- **gomobile 只导出原始类型签名的函数**（string/int/bool/error）——别加 struct 入参，会被拒。
- **没有「登录成功」回调**：frp 只在 `client/service.go:331` 打日志 `"login to server success"`。wrapper 通过把 frp 全局 logger（`frlog.Logger`）重定向到内存 sink 来捕获这行，转 RUNNING 状态——和 Android `FrpcVisitorService.pumpOutput` grep stdout 同一逻辑。改这个启发式要两边一起改。
- **RUNNING 判定当前是简化版**：当前 `logSink.Write` 里直接翻 STARTING→RUNNING（因为每隧道独立 Service，全局登录行就属于它）。见 `ios/AGENTS.md`。
- **钉在 frp v0.70.1**：frp v0.61 之前是 `pkg/client`（非顶层 `client/`），别为旧版「简化」import。

---

## 接下来做什么（卡点解除后）

### 第 0 步：验证 xcframework（1 条命令）

```bash
./scripts/build_frpc_ios.sh --simulator
# 预期产出 ios/Frameworks/Frpclib.xcframework
```

这条过了 = 整个方案端到端证实。

### 第 1 步：Xcode 工程

- 在 `ios/` 下建 `AiDevMob.xcodeproj`
- SPM 依赖：Citadel（`https://github.com/orlandos-nl/Citadel`）、SwiftTerm（`https://github.com/migueldeicaza/SwiftTerm`）
- 集成 `Frpclib.xcframework`（嵌入二进制）
- 免费 Apple 账号 sideload 签名（7 天证书，自用够）

### 第 2 步：数据模型 + 存储（复刻 Android，1:1）

Android 的 4 个模型在：
- `app/src/main/java/com/devhc/aidevmob/frp/FrpsServer.kt` — frps 端点（id/name/serverAddr/serverPort/authToken）
- `app/src/main/java/com/devhc/aidevmob/frp/FrpcConfig.kt` — STCP visitor（id/name/serverId/secretKey/serverName/bindPort）
- `app/src/main/java/com/devhc/aidevmob/ssh/ConnectionConfig.kt` — SSH 连接（host/port/credentialId/authMethod/tmuxSession/tunnelId）
- `app/src/main/java/com/devhc/aidevmob/ssh/Credential.kt` — 凭据（username/password|privateKeyPem+passphrase）

凭据进 **Keychain**，其余三个模型用 UserDefaults 或 JSON 文件存（Android 用 EncryptedSharedPreferences 存 JSON 数组）。

### 第 3 步：SSH 连接器（Citadel，对标 `SshTerminalConnector.kt`）

要复刻的行为（都在 `SshTerminalConnector.kt` 里）：
- TOFU 主机密钥：首次信任，变更拒绝（Android: `TofuHostKeyVerifier.kt`）→ Citadel: `SSHHostKeyValidator.custom`
- PTY shell + `xterm-256color` + keepalive 15s
- tmux 附加命令：`export LANG=${LANG:-en_US.UTF-8} LC_ALL=${LC_ALL:-en_US.UTF-8}; clear; tmux -u new-session -A -s '<name>'`
- 连隧道：先 `StartTunnel`，等 `IsRunning==true`，再 SSH 连 `127.0.0.1:bindPort`（Android: `TunnelGate.kt`）

### 第 4 步：终端 UI（SwiftTerm）

- `TerminalView` + `HeadlessTerminal`，SSH stdout `feed(byteArray:)`，输入走 `send`/`sendBytes`
- 窗口尺寸变化调 PTY `window-change`（Citadel: `TTYStdinWriter.changeSize`）——否则 tmux 旋转键盘后渲染错乱
- 箭头键 extra row：SwiftTerm 原生读 `terminal.applicationCursor`，不用自己拼 ESC 序列

### 第 5 步：连接/隧道管理 UI（SwiftUI）

MVP 范围（用户已定）：核心链路先跑通——Servers/Tunnels/Connections 表单，能存配置、起隧道、连 SSH、看终端。in-app 更新、配置备份等后续迭代。

---

## iOS 专属约束（写代码时记住）

1. **没有前台服务**：app 进后台 ~30s 被挂起，隧道+SSH 断。frpc 回前台自动重连；tmux 会话服务端保留，重连+reattach 无损。Android 的 `FrpcVisitorService` 前台通知在 iOS 不存在。sideload 可开 `UIBackgroundModes` 延长后台时间。
2. **后台 socket 被挂起**：SSH/tmux 会话在后台中断，但 tmux 服务端保留 → 重连 reattach 无损。

---

## 如果换了新 session 接手

1. 先读 `AGENTS.md`（根）和 `ios/AGENTS.md`。
2. 本文件是状态快照，`ios/frpclib/frpclib.go` 是已验证的真实代码。
3. 第一步永远是确认 Xcode 装好没：`./scripts/build_frpc_ios.sh --simulator` 能出 xcframework 就继续；报错就先解决 Xcode。
4. frp/Gomobile 的 API 细节别信记忆——源码在 `~/go/pkg/mod/github.com/fatedier/frp@v0.70.1/`，直接读。
