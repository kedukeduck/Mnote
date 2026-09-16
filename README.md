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
- `desktop-windows/`：Windows 10 / 11 x64 截图、随手记、应用内知识库和账号双向同步。
- `browser-extension/`：Chrome/Edge 网页划线、截图批注和同步。
- `capture-server/`：SQLite、图片 Blob、Web Inbox、REST API 和只读 MCP。
- `docs/`：产品、隐私、协议、安装和验收文档。
- `scripts/`：完整验证与交付打包。

## Windows 下载

当前 Windows 测试版 **1.8.0-test**：[安装包](https://github.com/kedukeduck/Mnote/releases/download/mnote-windows-v1.8.0-test/Mnote-Windows-1.8.0-test-Setup.exe) · [便携包](https://github.com/kedukeduck/Mnote/releases/download/mnote-windows-v1.8.0-test/Mnote-Windows-1.8.0-test-Portable.zip)。使用 Android 上同一账号登录，自动同步；旧本机记录需要明确导入。

双端 1.8.0 新增批量选择记录导出 Markdown：包含文档说明、AI 阅读提示、索引、想法、摘录、原文、来源和完整截图/圈选图/批注图在线链接。首页先筛选再选择，最多 100 条已同步记录；需明确确认分享图片，分享快照可在导出页独立撤销。详情见[批量导出说明](docs/markdown-batch-export.md)。

Android / Windows 均已新增首页“更新”：查询版本和说明、下载校验、用户确认安装。首次手动覆盖安装 1.7.0-test，后续使用应用内入口，不用填写更新 URL / Token。Windows 安装前正常退出并等待后台同步收尾；便携版通过此入口转为安装版。Android 沿用测试签名并由系统确认安装。详见[双端更新说明](docs/cross-platform-1.7.0-updates.md)及[测试与交付校验](docs/cross-platform-1.7.0-release-verification.md)。

Windows 已补齐应用内查看 / 编辑 / 删除、标签 / 未分类筛选、两步截图与完整上下文、随手记的可选剪贴板和独立页面上下文，并统一 A 风格。Ctrl+Shift+F9 截图，Ctrl+Shift+F8 随手记。安装包不需要管理员权限，升级和卸载保留数据。未做 Authenticode 签名，尚需真实 Windows 多屏、输入法与浏览器读取能力验收。[使用说明](desktop-windows/README.md) · [功能与测试](docs/windows-1.6.0-android-parity.md) · [交付校验](docs/windows-1.6.0-release-verification.md)。

## Android 构建

当前 Android 增量测试版：[1.8.0-test APK](https://github.com/kedukeduck/Mnote/releases/download/mnote-android-v1.8.0-test/Mnote-Android-1.8.0-test.apk)。新增批量导出 Markdown 和可撤销图片分享链接；保留应用内更新。安装前保存草稿，覆盖安装，不要卸载旧版。使用原调试/测试签名，非正式商店版。

1.6.0 新增所有安卓记录入口的多标签、已有记录补改标签、首页标签 / 未分类筛选及标签账号同步。长文支持内部滚动、全屏展开编辑及到文末，页面读取改善嵌套段落顺序并支持可见叶节点描述。修正 UI 位图提前回收风险，新增仅本机的异常诊断；尚未真机复现用户报告的偶发闪退。详见 [1.6.0 标签、长文与稳定性说明](docs/android-1.6.0-tags-readable-context.md)。

Android 全部页面及应用弹窗采用 A「轻盈极简」主题；首页提供搜索、摘录/想法/待办筛选及固定底部操作区。账号登录后自动同步，支持刷新拉取和删除记录。来源与无障碍说明位于首页“快捷方式与权限设置”。

单次摘录只走截图，不读取选区或剪贴板，继续使用“圈选 → 下一步 → 想法”的原地悬浮流程，可选择保留完整截图上下文。随手记默认只写想法 / TODO；剪贴板摘录与页面上下文各自独立、主动选择，不开启剪贴板也能保留页面文字或截图。关闭剪贴板摘录不会移除上下文。页面上下文不一定是剪贴板文字的原始出处；不保证任意应用的全文或链接均可读取，失败不自动转截图，也不提供 OCR 选字。详见 [1.5.1 独立上下文与稳定性修复](docs/android-1.5.1-independent-context.md)。系统权限页和键盘保留系统样式。

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
