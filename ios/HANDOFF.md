# iOS Port — Handoff

**状态：完整 app 编译通过（BUILD SUCCEEDED），待真机 sideload 实测。**
**最后更新：2025-07-31**

---

## 这是什么项目

把现有 Android app（`app/`，Kotlin，用 frpc STCP visitor 打隧道 + sshj 连 SSH + Termux 终端渲染远程 tmux）移植到 iOS。**自用 sideload，不进 App Store。** 位于 `ios/`。

## 已验证的关键事实（不用再查）

```
go vet ./...                         → EXIT 0   frpclib Go 封装 API 调用正确
GOOS=ios GOARCH=arm64 CGO_ENABLED=1
  go build -o /dev/null ./...        → EXIT 0   frp 整棵依赖树干净编译 iOS
gomobile bind -target=ios/arm64,
  iossimulator/arm64                 → Frpclib.xcframework 已产出 (35MB, 真机+模拟器)
xcodebuild ... build                 → BUILD SUCCEEDED  完整 app 编译通过
```

研究中担心的两个风险均已解除：
1. ~~frp v0.70.1 要 Go 1.25，gomobile 可能跟不上~~ → 装 Go 1.26.5，更新。
2. ~~frpc 能否跑在 iOS 进程内~~ → 实证整树编译通过 + gomobile bind 成功。

## 技术栈

| 层 | 选型 | 状态 |
|----|------|------|
| **frpc 隧道** | gomobile bind 薄封装 → `Frpclib.xcframework`，进程内跑 `client.Service` | ✅ 已产出 + 集成 |
| **SSH** | Citadel 0.12.1（SPM, MIT, iOS 17+） | ✅ 已集成 |
| **终端** | SwiftTerm v1.15.0（SPM, MIT, iOS 14+） | ✅ 已集成 |
| **凭据** | iOS Keychain（密钥）+ JSON 文件（结构数据） | ✅ 已写 |
| **工程** | xcodegen 生成 `project.yml` → `AiDevMob.xcodeproj` | ✅ 编译通过 |

## 已交付文件（8 Swift + 1 Go + 脚本 + 文档）

```
ios/
├── AGENTS.md                          构建前置/架构 gotchas
├── HANDOFF.md                         本文件
├── project.yml                        xcodegen 工程定义
├── AiDevMob.xcodeproj/                生成的 Xcode 工程（提交）
├── frpcllib/                          frpc 的 gomobile 封装（Go 源码，提交）
│   ├── frpclib.go                     多隧道管理：StartTunnel/Stop/Status/Logs
│   ├── go.mod / go.sum                锁定 frp v0.70.1 + gobind tool
├── Frameworks/                        （gitignored，build_frpc_ios.sh 生成）
│   └── Frpclib.xcframework/
└── AiDevMob/                          Swift app（2556 行）
    ├── AppDelegate.swift              @main 入口，挂载 AppRootCoordinator
    ├── AppRoot.swift                  端到端协调：隧道→SSH→终端 + 指数退避重连
    ├── Frpc/TunnelRuntime.swift       frpclib Swift 封装（启停/状态/日志/awaitRunning）
    ├── Models/Models.swift            4 数据模型 1:1 复刻 Android
    ├── Storage/Stores.swift           Keychain + JSON 文件存储
    ├── SSH/SshTerminalConnector.swift Citadel SSH + TOFU + PTY + tmux
    └── UI/
        ├── ManagementViews.swift      SwiftUI 4 tab（连接/凭据/隧道/服务器）+ CRUD
        └── TerminalViewController.swift SwiftTerm 终端 + extra keys row
```

根目录: `scripts/build_frpc_ios.sh`（出 xcframework），根 `AGENTS.md` 已指向 `ios/AGENTS.md`。

## 构建坑（都已在 project.yml / build_frpc_ios.sh 解决，记住即可）

1. **Frpclib.xcframework 不在 git** → `./scripts/build_frpc_ios.sh --simulator` 先编。
2. **Go cgo resolver 要 `-lresolv`** → project.yml `OTHER_LDFLAGS: -lresolv`（否则 `_res_9_ninit` 未定义）。
3. **xcframework 只有 arm64** → `EXCLUDED_ARCHS[sdk=iphonesimulator*]: x86_64`。
4. **gomobile 常量在 Swift 是 String 不是 enum** → `FrpcllibStateRunning` 直接用，别加 `.rawValue`。
5. **Obj-C BOOL+NSError** 桥接成 Swift `throws` → 不传显式 `error:` 参数。
6. **要 `@main` 入口** → `AppDelegate` 标 `@main`，否则链接报 `_main` undefined。
7. **要 `GENERATE_INFOPLIST_FILE: YES`** → xcodegen 默认不生成 Info.plist。
8. **Citadel 的 NIOSSH fork 砍了 RSA 主机密钥** → Ed25519/ECDSA 服务器才行（RSA 用户认证仍可用）。
9. **frp 没有登录成功回调** → 只打日志 `"login to server success"`（service.go:331），wrapper 重定向全局 logger 捕获这行转 RUNNING 状态。

## 待做：真机 sideload 实测

代码全写完、模拟器编译通过，但端到端（隧道→SSH→终端→tmux）必须真机跑一次。这步只能用户做。

---

## 如果换了新 session 接手

1. 读 `AGENTS.md`（根）+ `ios/AGENTS.md` + 本文件。
2. `ios/frpcllib/frpclib.go` + `ios/AiDevMob/*.swift` 是已验证的真实代码。
3. 第一步确认 xcframework 在不在：`ls ios/Frameworks/Frpclib.xcframework`；不在就 `./scripts/build_frpc_ios.sh --simulator`。
4. 编译验证：`cd ios && xcodebuild -project AiDevMob.xcodeproj -scheme AiDevMob -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -configuration Debug build CODE_SIGNING_ALLOWED=NO`。
5. frp/Gomobile/Citadel/SwiftTerm 的 API 细节别信记忆——源码在 `~/go/pkg/mod/github.com/fatedier/frp@v0.70.1/` 和 Xcode DerivedData 的 SPM checkout 里，直接读。
