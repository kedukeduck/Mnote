# Mnote

Mnote 是一个独立的本地优先个人知识捕获项目，用于在 Android、Windows、Chrome 和 Edge 中记录截图、划线、圈选、网页原文、想法与 TODO，并可选择同步到自己的服务器供 Web Inbox 和只读 AI 使用。

这不是 LoveTools 的功能模块：

- Android application ID：`com.codex.mnote`
- Android 桌面名称：`Mnote`
- 独立 Android 启动页、图标、应用私有目录和 Keystore Token 别名
- 独立 Windows `mnote.exe`
- 独立 Mnote 浏览器扩展
- 独立 Git 仓库

## 目录

- `app/`：Android 11+ 单次系统截图、分享/处理文字、批注、本地 Inbox 和同步。
- `desktop-windows/`：Windows 11 x64 全局快捷键截图和本地 Inbox。
- `browser-extension/`：Chrome/Edge 网页划线、截图批注和同步。
- `capture-server/`：SQLite、图片 Blob、Web Inbox、REST API 和只读 MCP。
- `docs/`：产品、隐私、协议、安装和验收文档。
- `scripts/`：完整验证与交付打包。

## Android 构建

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
```

安装包输出到 `app/build/outputs/apk/debug/app-debug.apk`。V1 测试包使用 Android 调试证书签名，能够与 `com.codex.heartnote` 的 LoveTools 同时安装。

## 完整验证与打包

```bash
bash scripts/verify-mnote-v1.sh
bash scripts/package-mnote-v1.sh v1.0.0-core
```

同步协议继续兼容已经部署的 `https://chenyu.online/heartnote-capture`，因此拆分仓库和应用身份不会迁移、覆盖或清空服务器现有数据。
