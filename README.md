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

当前 Android 增量测试版：[1.5.0-test APK](https://github.com/kedukeduck/Mnote/releases/download/mnote-android-v1.5.0-test/Mnote-Android-1.5.0-test.apk)。

Android 全部页面及应用弹窗采用 A「轻盈极简」主题；首页提供搜索、摘录/想法/待办筛选及固定底部操作区。账号登录后自动同步，支持刷新拉取和删除记录。来源与无障碍说明位于首页“快捷方式与权限设置”。

单次摘录只走截图，不读取选区或剪贴板，继续使用“圈选 → 下一步 → 想法”的原地悬浮流程，可选择保留完整截图上下文。随手记默认只写想法 / TODO；主动开启后才读取剪贴板第一条，并可另外选择读取当前页面文字或保存完整页面截图作为上下文。页面上下文不一定是剪贴板文字的原始出处；不保证任意应用的全文或链接均可读取，失败不自动转截图，也不提供 OCR 选字。详见 [1.5.0 剪贴板与上下文](docs/android-1.5.0-clipboard-context.md)。系统权限页和键盘保留系统样式。

记录页提供大预览、圈选区域 / 完整截图切换与独立想法区；详情为完整阅读页，点击“编辑”可修改想法、摘录文字和保留的原文，并按账号同步。详见[1.4.0 编辑已保存记录](docs/android-1.4.0-edit-records.md)、[记录与阅读页说明](docs/android-1.3.1-compose-review.md)及 [A 视觉规范](docs/android-1.3.0-style-a.md)。覆盖安装前先保存草稿，不要卸载；此包沿用测试签名。
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
