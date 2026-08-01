# iOS Port — Handoff

**状态：端到端跑通（隧道→SSH→终端→tmux→opencode），真机 iPhone 17 Pro 验证可用。**
**最后更新：2026-08-01**

---

## 当前已验证可用

- ✅ frpc STCP 隧道：进程内 gomobile，登录 frps + STCP 撮合 + 本地端口监听
- ✅ SSH：Citadel 0.12.1，密码认证 + TOFU host key（Ed25519/ECDSA only，**不支持 RSA host key**）
- ✅ 终端：SwiftTerm 1.15.0，VT100 渲染 + PTY + 键盘输入
- ✅ tmux attach（`new-session -A`）
- ✅ opencode 等全屏 TUI 可用
- ✅ tmux 会话探测（连接编辑页，1:1 对齐 Android `TmuxSessionProbe`）
- ✅ 额外按键行（ESC/CTRL/←↓↑→/TAB/^C/^D/Home/End/PgUp/PgDn/Enter/y/n/⌨收键盘），横向可滚动
- ✅ 滚动回滚：自定义 wheel 手势，TUI 程序（mouse tracking on）时发鼠标滚轮事件（对齐 Termux doScroll）
- ✅ 粘贴：PasteAwareTerminalView 重写 paste，LF→CR + 剥离 ESC/C1（对齐 Termux）
- ✅ 键盘避让：手动 keyboardWillChangeFrame，PTY resize 150ms 去抖

---

## 本轮修复的 bug（踩坑记录，换 session 必读）

### 1. frpc `common.User` 不能设
`frpclib.go` 之前设了 `User: "aidevmob"`。STCP visitor 在 frps 上撮合 proxy 时，匹配名是
`{user}.{serverName}`（`naming.BuildTargetServerProxyName`）。设了 user 导致 visitor 找
`aidevmob.<serverName>`，而服务端 proxy 名是裸 `<serverName>` → 撮合失败 → SSH 连本地 visitor
端口时报 `NIOCore.IOError`。**删掉 `User` 字段**，和 Android TOML 一致。

### 2. 端口重定向（iOS 独有）
Android 靠用户手动把 `config.port` 填成隧道的 `bindPort`。iOS 自动重定向：
`AppRoot.redirectedThroughTunnel(_:)` —— 有隧道时把 SSH host/port 改成 `127.0.0.1:<bindPort>`。
`connect` 和 `reconnect` 两条路径都要调。

### 3. Auto Layout 崩溃
`TerminalViewController.viewDidLoad` 里 `setupExtraKeysRow` 必须在 `setupTerminalView` **之前**
调用——terminalView 的 bottom 约束引用了 extraKeysBar，后者必须先在视图层级里。

### 4. 必须调 `TerminalView.feed` 不是 `Terminal.feed`
`getTerminal().feed(byteArray:)` 只更新内存 buffer，不触发渲染。SwiftTerm 的 CADisplayLink
默认 suspended，只有 `TerminalView.feed(byteArray:)` 内部的 `feedPrepare()/feedFinish()` 才会
`startDisplayUpdates()` + `queuePendingDisplay()`。调错了 → 黑屏（但 DECSET 日志正常，因为
解析是另一条路径）。

### 5. 状态在 feed 时翻转
`connector.start()` 是**阻塞到会话结束**的 async 方法（内部 `for try await output in inbound`）。
所以 `hideStatus()` 不能放在 `start()` 成功返回后——那永远不会执行。`.connected` 状态在
`feed()` 收到第一个字节时翻转（`TerminalViewController.feed` 里）。

### 6. 按键行滚动：`delaysContentTouches` 必须 true
`UIScrollView` 里放按钮，`delaysContentTouches = false` 会导致触摸立刻转发给按钮，scrollView
永远没机会识别拖动手势 → 滚不动。设回 `true`（默认值）。

### 7. 滚动手势：wheel event 而非 drag
SwiftTerm `allowMouseReporting = true` 时，pan 会发鼠标 drag 事件（文本选择），TUI 程序收到后
"copied to clipboard"。修复：自定义 `wheelScroll` UIPanGestureRecognizer，发鼠标**滚轮**事件
（button 64=up, 65=down），通过 `shouldBeRequiredToFailBy` 互斥（不同时触发）。

### 8. PTY resize 去抖
键盘动画每帧触发 `sizeChanged` → 每次发 PTY resize → tmux 来不及 redraw → 漂移。150ms 去抖。
PTY channel 打开后主动同步一次当前尺寸（`requestedCols/Rows`），连接过程中错过的 resize 补上。

### 9. 启动命令延迟
PTY channel 打开后等 300ms 再发 startup command（`export LANG...; clear; tmux...`），
否则 shell 还没 ready，命令字节和 banner 交错，命令文本残留在屏幕上。

---

## 技术栈

| 层 | 选型 | 版本 |
|----|------|------|
| **frpc 隧道** | gomobile bind → `Frpclib.xcframework`，进程内 `client.Service` | frp v0.70.1 |
| **SSH** | Citadel（SPM），底层 Wellz26/swift-nio-ssh fork | 0.12.1 |
| **终端** | SwiftTerm（SPM） | 1.15.0 |
| **UI** | UIKit（终端页）+ SwiftUI（管理页）混搭 | iOS 17+ |
| **工程** | xcodegen（`project.yml` → `AiDevMob.xcodeproj`） | — |

## 已交付文件

```
ios/
├── AGENTS.md                          构建前置/架构 gotchas
├── HANDOFF.md                         本文件
├── project.yml                        xcodegen 工程定义
├── AiDevMob.xcodeproj/                生成的 Xcode 工程（需 xcodegen generate 同步新文件）
├── frpcllib/                          frpc 的 gomobile 封装（Go 源码）
│   └── frpclib.go                     StartTunnel/Stop/Status/Logs（不设 User!）
├── Frameworks/                        （gitignored，build_frpc_ios.sh 生成）
│   └── Frpclib.xcframework/
└── AiDevMob/
    ├── AppDelegate.swift              @main 入口
    ├── AppRoot.swift                  端到端协调 + 重定向 + reconnect
    ├── Frpc/TunnelRuntime.swift       frpclib Swift 封装
    ├── Models/Models.swift            4 数据模型
    ├── Storage/Stores.swift           Keychain + JSON 文件存储
    ├── SSH/
    │   ├── SshTerminalConnector.swift SSH + TOFU + PTY + exec（探测用）
    │   └── TmuxSessionProbe.swift     tmux list-sessions 探测
    └── UI/
        ├── ManagementViews.swift      SwiftUI 4 tab + CRUD + tmux 探测 sheet
        └── TerminalViewController.swift 终端页（顶栏/按键行/键盘避让/wheel手势/粘贴）
```

## iOS vs Android 协议层差异（核心）

| 维度 | Android | iOS |
|------|---------|-----|
| frpc 运行 | fork 子进程（libfrpc.so） | in-process（gomobile） |
| frpc 版本 | v0.70.1 | v0.70.1（**一致**） |
| SSH 库 | sshj 0.40.0 + BouncyCastle | Citadel 0.12.1（NIOSSH fork） |
| host key | RSA + Ed25519 + ECDSA | **Ed25519 + ECDSA only**（不支持 RSA host key!） |
| keepalive | `keepAliveInterval = 15` | **无**（Citadel 不暴露） |
| SFTP | ✅ 完整 | ❌ Citadel 有模块但 app 没用 |

## iOS vs Android 功能缺口（待补）

**完全缺失**：
1. **文件浏览 + SFTP**（Citadel 底层有 SFTP 模块，app 没接）
2. **设置页**（无字体大小/tmux prefix/加密备份/更新检查/环境自检）
3. **iPad 适配**（布局写死手机宽度）

**功能点缺口**：
4. tmux 窗口菜单（列表/重命名）
5. 滑动切 tmux 窗口手势
6. 连接编辑：保存并连接 / 复制连接 / 内联新建凭证
7. 隧道：保存并启动 / 复制 / 端口冲突校验 / 日志查看 UI
8. 后台保活（iOS 限制大）

## 已知小问题

- 终端尺寸自适应：键盘弹/收时 PTY resize 基本工作，但偶尔有轻微漂移（tmux redraw 跟不上）
- 诊断日志还残留（`dumpLayout` 等 print），跑稳定后清理
- `ios/AGENTS.md` 未更新本轮踩坑（user/端口/feed/状态/滚动/resize/粘贴）

## 构建

```bash
./scripts/build_frpc_ios.sh             # 先编 Frpclib.xcframework（改了 frpclib.go 要重跑）
cd ios && xcodegen generate             # 加了新 .swift 文件要重跑（同步 pbxproj）
xcodebuild -project AiDevMob.xcodeproj -scheme AiDevMob \
  -destination 'generic/platform=iOS' -configuration Debug build CODE_SIGNING_ALLOWED=NO
# 真机安装：Xcode GUI → 选设备 → ▶ Run（需 Apple ID 签名）
```

**首次构建**：Go 1.25+ + 完整 Xcode + `./scripts/build_frpc_ios.sh` + `xcodegen generate`。
