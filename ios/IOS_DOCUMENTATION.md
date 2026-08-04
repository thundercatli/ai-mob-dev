# iOS 端技术文档（脱敏版）

本文说明 iOS 端的架构、数据流、构建、测试、故障排查和安全边界。示例中的主机、账号、端口、密钥、设备标识和应用标识均为占位符，不代表实际部署值。

## 1. 产品边界

iOS 客户端通过 frp STCP visitor 访问远端 SSH 服务，再使用 tmux 保存和恢复终端状态。应用包含：

- iPhone 紧凑布局和 iPad 双栏布局
- SSH 密码/私钥认证与 TOFU 主机密钥校验
- SwiftTerm 终端、tmux 会话和窗口操作
- SFTP 浏览、文本/图片预览、下载
- 配置备份恢复、环境自检和版本检查

iOS 不能像 Android 一样 fork/exec frpc 子进程，因此 frpc 以 Go runtime 的形式嵌入应用，通过 gomobile 生成 `Frpclib.xcframework`。

## 2. 总体架构

```text
SwiftUI RootView
  -> AppCoordinator
     -> TunnelRuntime (Frpclib gomobile binding)
        -> frp client.Service (one service per tunnel)
           -> frps STCP visitor
              -> local 127.0.0.1:<bind-port>
                 -> Citadel SSHClient
                    -> SwiftTerm / tmux / SFTP
```

主要目录：

| 目录 | 责任 |
| --- | --- |
| `AiDevMob/Frpc` | Swift 对 Go frpc runtime 的封装和状态轮询 |
| `frpcllib` | gomobile 导出的 Go wrapper；每条隧道独立一个 `client.Service` |
| `AiDevMob/SSH` | Citadel SSH、PTY 探测、tmux 解析、SFTP subsystem |
| `AiDevMob/UI` | 管理页、终端页、iPad split view、备份/自检/更新页面 |
| `AiDevMob/Storage` | JSON 结构化数据、Keychain 密钥、默认项 |
| `AiDevMobTests` | 加密、配置、tmux 菜单和 PTY 分帧单元测试 |
| `AiDevMobUITests` | 设置、备份、凭证和更新页面 UI 测试 |

## 3. 隧道和 SSH 数据流

### 3.1 STCP 命名约定

隧道配置的 `serverName` 必须等于服务端 STCP proxy 的原始名称，例如：

```text
服务端 proxy name: <server-name>
iOS tunnel.serverName: <server-name>
```

`ClientCommonConfig.User` 必须保持为空。frp 在 User 非空时会把目标名称组合为 `{user}.{serverName}`，这会导致服务端找不到 listener。客户端本地监听地址固定为 `127.0.0.1:<bind-port>`，SSH 连接必须重定向到该地址。

### 3.2 启动顺序

1. 从配置存储解析 frps、隧道和凭证引用。
2. 启动对应的 `TunnelRuntime` visitor。
3. 等待 visitor 状态变为 running，并保留最近的 frpc 日志。
4. 把 SSH host/port 改为 `127.0.0.1:<bind-port>`。
5. Citadel 建立 SSH 连接并执行 TOFU 主机密钥校验。
6. 终端路径打开 PTY；tmux 探测路径打开临时 PTY 并读取带边界的命令输出。

STCP visitor 通常只适合一个并发 TCP 连接。终端内打开文件时复用现有 SSH connection，只新增 SFTP subsystem；不要为同一个活动终端再建立独立 SSH 连接。

## 4. SSH、PTY 和 tmux 探测

`withExec` 在 STCP 链路上的稳定性不足，tmux 探测使用 `withPTY`：

1. 建立临时 SSH client。
2. 在同一 async 上下文中打开 PTY。
3. 发送命令和唯一 start/end marker。
4. 收集 stdout/stderr，直到 end marker 出现。
5. 去除 PTY 回显、ANSI 控制序列，并归一化 `CRLF`/`CR`。
6. 只解析符合 `session|windows|attached` 结构的 tmux 行。
7. 同步关闭 SSH client，释放 STCP visitor 的连接槽。

marker 的两部分通过两个 `printf` 参数拼接，避免完整 marker 出现在 PTY 的命令回显中。连接、PTY 和 channel 的瞬态错误使用有限次数退避重试；认证、主机密钥和 tmux 不存在属于确定性错误，不重复切换 shell flag。

## 5. 配置和密钥存储

配置分为四类：

- `FrpsServer`：frps 地址、端口和认证 token
- `FrpcTunnel`：服务端 proxy 名称、STCP secret、关联的本地端口
- `Credential`：SSH 用户名和认证方式
- `ConnectionConfig`：目标主机、tmux 会话、默认路径、凭证/隧道引用

结构化记录保存在应用沙盒的 Application Support JSON 文件中。凭证的密码、私钥和私钥口令保存在 Keychain。当前 MVP 中 frps token 和 STCP secret 仍位于应用私有 JSON，不能把该文件当作可公开导出的文件。

配置备份使用 Android 兼容的 v1 envelope：PBKDF2-HMAC-SHA256 派生密钥，AES-256-GCM 加密，恢复前先完整校验再按 id 合并。导出的文件、日志和 issue 描述必须删除真实 host、用户名、token、secret、私钥、指纹和设备标识。

## 6. iPhone / iPad UI

- iPhone 使用 `CompactShell`、TabView 和全屏终端。
- iPad 使用 `IPadShell`、NavigationSplitView，左侧为分类导航，右侧为终端 detail。
- 打开终端时默认切换为 detail-only；关闭终端后恢复 sidebar。
- SwiftUI 重绘不能重新创建活动的 `TerminalViewController`，coordinator 必须持有整个终端会话周期。
- iPad 按键行居中并限制最大宽度；iPhone 按键行支持横向滚动。

## 7. 构建环境

要求：

- macOS + 完整 Xcode（不是只安装 Command Line Tools）
- Go 1.25 或更高版本
- `gomobile` / `gobind`
- Apple Silicon 模拟器使用 arm64 slice；发布真机使用 ios/arm64 slice

先生成被 `.gitignore` 忽略的 framework：

```bash
./scripts/build_frpc_ios.sh
./scripts/build_frpc_ios.sh --simulator
```

然后打开 Xcode 工程或使用命令行构建。Xcode 26.6 的 clang 探测由 `scripts/xcode_clang_probe_wrapper.sh` 处理；不要移除该 wrapper 或把 `CLANG_ENABLE_EXPLICIT_MODULES` 改回默认值。

## 8. 测试和验证

完整模拟器测试必须保留正常签名，否则 Keychain 往返测试会读不到密钥：

```bash
cd ios
xcodebuild -scheme AiDevMob \
  -destination 'platform=iOS Simulator,name=<simulator-name>' \
  -configuration Debug test
```

仅做 generic device 编译时可以禁用签名：

```bash
cd ios
xcodebuild -scheme AiDevMob \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO build
```

真机安装需要在 Xcode 中选择开发团队和目标设备。`Frpclib.xcframework` 是忽略文件，修改 Go wrapper 后必须重新生成并重新安装 app；旧安装包不会自动使用新 wrapper。

## 9. 故障排查

### `custom listener for [<user>.<server-name>] doesn't exist`

检查三项：

1. 隧道编辑页的 `serverName` 是否只是服务端 proxy 的原始名称。
2. 服务端是否已注册同名 STCP proxy，且 secret 完全一致。
3. 当前 app 是否由最新 `Frpclib.xcframework` 构建并重新安装。

不要通过给 `serverName` 人工增加用户名前缀来修复；应保持 User 为空。

### `Connection reset by peer` / `NIOCore.IOError`

先查看 UI 展示的 frpc 最近日志。若日志同时出现 visitor/proxy 撮合失败，根因在 STCP listener、secret 或名称，不在 tmux。若没有 frpc 错误，再检查隧道 running 状态、本地 bind port、SSH 凭证和 TOFU host key。

### tmux 会话列表为空

远端没有运行中的 tmux server 时这是正常结果。确认远端存在 `tmux`，并让登录 shell 能找到它；探测器会尝试受限的 login/interactive shell 组合，但不会掩盖 SSH 或认证错误。

### 新增 Go 导出 API 后 framework 构建失败

确认 `go list` 能正常加载 `ios/frpcllib`，并在沙盒外运行构建脚本以访问 Go build cache 和 Xcode SDK。构建失败后应检查输出 framework 是否完整，再重新构建 app。

## 10. 已知限制

- Citadel 当前不支持仅提供 RSA host key 的服务端；优先使用 Ed25519 或 ECDSA。
- iOS 没有 Android foreground service；应用长期后台运行时隧道和 SSH 可能被系统暂停，回到前台会尝试恢复。
- 每条 STCP visitor 的并发承载能力有限，应复用 SSH transport。
- iOS 更新检查只能打开发布页，不能像 Android 一样覆盖安装自身可执行文件。
- 当前界面主要是中文，尚未完成多语言资源化。

## 11. 脱敏发布检查清单

提交 issue、日志或文档前确认：

- host、IP、域名和端口已替换为占位符
- 用户名、邮箱、Apple Team、bundle/profile 信息已删除
- frps token、STCP secret、密码、私钥和 Keychain 内容未出现
- UUID、设备 UDID、安装路径和签名日志已删除
- frpc/SSH 日志中的 proxy 名称不包含真实业务名称
- 备份文件和截图未包含应用内配置或远端终端输出
