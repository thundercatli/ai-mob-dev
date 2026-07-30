# ai-mob-dev

从手机操作电脑上 tmux 会话的 Android 客户端：内置 frpc（STCP visitor）打通网络，再通过 SSH 连接到远端 shell / tmux，用 Termux 的终端引擎渲染。

## 功能

- **连接管理**：多个 SSH 连接配置，支持命名、复制、删除。列表里每条连接上方标出所走的隧道名（以及是否在运行），编辑页可以直接探测远端已有的 tmux session 来选，也可以新建一个或不用 tmux。选了隧道后 Host/Port 由隧道的本地端口决定，不用手填
- **认证管理**：用户名 + 密码/私钥单独存成「认证」，多个连接复用同一份；私钥可以从手机本地文件选（SAF）或从剪贴板粘贴。全部存在 EncryptedSharedPreferences 里。老版本里写在连接上的凭据会在首次启动时自动抽成认证条目
- **隧道管理**：多条 frpc STCP 隧道，各自独立进程、独立本地端口，可单独启停并查看日志
- **自动打隧道**：连接可关联某条隧道，进入终端时若隧道未启动会自动拉起并等待就绪
- **终端**：VT100 全功能终端（基于 Termux 的 terminal-view / terminal-emulator），附加功能键行、断线自动重连（配合 tmux 可无损续接）

## 构建

需要 JDK 17、Android SDK、Android NDK、Go。

`app/src/main/jniLibs/arm64-v8a/libfrpc.so` **不在版本库里**，首次构建前需要生成：

```bash
./scripts/build_frpc.sh          # 默认构建 frp v0.70.1
./scripts/build_frpc.sh --version v0.71.0
./gradlew assembleDebug
```

脚本会克隆 frp 源码（或用 `FRP_ROOT` 指向已有的 checkout），然后用 NDK 的 clang 以 `GOOS=android CGO_ENABLED=1` 交叉编译。

**为什么必须自己编译**：frp 官方 release 里的 `linux_arm64` 和 `android_arm64` 两个包都是纯 Go 静态编译（`CGO_ENABLED=0`），不链接 Bionic，因此不走 Android 自己的网络/解析实现。必须用 NDK 工具链开 cgo 重新编译。

**为什么叫 `.so`**：Android 10+ 不允许执行应用私有目录下的文件，但作为 native library 打包的文件会在安装时被解压到 `nativeLibraryDir` 并带上可执行权限。配合 `app/build.gradle.kts` 里的 `packaging.jniLibs.useLegacyPackaging = true`（强制 `extractNativeLibs=true`），`ProcessBuilder` 才能真正 exec 它。

目前只打 `arm64-v8a`，所以在常见的 x86_64 模拟器上隧道功能无法使用。

## CI 与发布

`.github/workflows/android.yml` 在 push / PR 时自动构建 frpc 和 release APK，产物作为 artifact `app-release` 上传。也可以在 Actions 页面手动触发（`workflow_dispatch`），可指定 frp 版本号。

打 `v*` 开头的 tag 会额外创建 GitHub Release 并把 APK 附在上面，方便直接下载：

```bash
git tag v0.1.0
git push origin v0.1.0
```

tag 构建会把版本号对齐到 tag（`v0.1.0` → versionName `0.1.0`，versionCode 取 CI run number 以保证递增）。

签名是可选的：配置 `STORE_FILE_BASE64`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 四个 repository secret 即用自己的密钥签名，否则回退到 Android 调试密钥（可安装，但不适合分发）。`STORE_FILE_BASE64` 用 `base64 -i release.jks` 生成。

## 第三方代码

- `app/src/main/java/com/termux/**` — 来自 [termux/termux-app](https://github.com/termux/termux-app) 的 terminal-view / terminal-emulator 模块（Apache-2.0）。其中 `TerminalSession.java` 被改写：原版把终端绑定到本地 JNI pty 子进程，这里替换成注入的输入/输出流，以便由 SSH 通道驱动；其余 VT100 解析与渲染代码未改动。
- frpc 来自 [fatedier/frp](https://github.com/fatedier/frp)（Apache-2.0），license 随包放在 `app/src/main/assets/licenses/`。
