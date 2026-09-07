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

当前 Android 增量测试版：[1.0.6-test APK](https://github.com/kedukeduck/Mnote/releases/download/mnote-android-v1.0.6-test/Mnote-Android-1.0.6-test.apk)。
支持“全屏选区 → 下一步 → 底部想法卡片”的两步摘录、独立随手记，以及主动摘录时一次性识别来源应用 / 已适配浏览器的地址栏。
浏览器适配包含 Chrome、Edge、Brave、Firefox；完整程度取决于浏览器暴露的地址栏。升级后可能需要重新启用无障碍服务以获得窗口内容读取能力。
微博等原生 App 的内部页面链接仍不能保证自动取得；可收起批注、复制原帖链接，再恢复并粘贴。原生选中文字后“记到 Mnote”或分享入口仍保留，本版不提供截图 OCR 选字。
本版新增首页“刷新”，拉取云端记录及截图到离线缓存；“上传本机”（原“同步全部”）负责上传本机记录。截图工具更新为细线图标、半透明圆角控件和蓝色选中态，保留可移位布局、顶部选区预览、键盘避让和保存 Toast。详见[本版说明与验收边界](docs/android-1.0.6-refresh-capture-design.md)。

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
```

安装包输出到 `app/build/outputs/apk/debug/app-debug.apk`。V1 测试包使用 Android 调试证书签名，能够与 `com.codex.heartnote` 的 LoveTools 同时安装。

## 完整验证与打包

```bash
bash scripts/verify-mnote-v1.sh
bash scripts/package-mnote-v1.sh v1.0.0-core
```

上面的整套打包脚本对应 V1 Core 归档交付；Android 增量版本以各自 Release 的独立 APK 和校验清单为准。

同步协议继续兼容已经部署的 `https://chenyu.online/heartnote-capture`，因此拆分仓库和应用身份不会迁移、覆盖或清空服务器现有数据。
