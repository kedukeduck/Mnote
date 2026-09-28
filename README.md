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

当前 Windows 测试版 **1.17.0-test**：[安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.17.0-test/Mnote-Windows-1.17.0-test-Setup.exe) · [便携包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.17.0-test/Mnote-Windows-1.17.0-test-Portable.zip)。使用 Android 上同一账号登录，自动同步；旧本机记录需要明确导入。

双端 1.8.0 新增批量选择记录导出 Markdown：包含文档说明、AI 阅读提示、索引、想法、摘录、原文、来源和完整截图/圈选图/批注图在线链接。首页先筛选再选择，最多 100 条已同步记录；需明确确认分享图片，分享快照可在导出页独立撤销。详情见[批量导出说明](docs/markdown-batch-export.md)。

双端 1.9.0 将账号和更新统一收到首页右上角“设置”，并重做导出入口与卡片勾选页。更新元数据与安装包直接由自有服务器提供，不再依赖 GitHub 可见性，也不用填写 URL / Token。**先覆盖安装 1.9.0，再将仓库设为私有**；旧版仅认识 GitHub，仓库已私有时请使用上述链接手动安装。详见[1.9.0 功能与自托管发布说明](docs/settings-1.9.0-selfhosted-updates.md)。

Windows 已补齐应用内查看 / 编辑 / 删除、标签 / 未分类筛选、两步截图与完整上下文、随手记的可选剪贴板和独立页面上下文，并统一 A 风格。Ctrl+Shift+F9 截图，Ctrl+Shift+F8 随手记。安装包不需要管理员权限，升级和卸载保留数据。未做 Authenticode 签名，尚需真实 Windows 多屏、输入法与浏览器读取能力验收。[使用说明](desktop-windows/README.md) · [功能与测试](docs/windows-1.6.0-android-parity.md) · [交付校验](docs/windows-1.6.0-release-verification.md)。

## Android 构建

当前 Android 测试版：[1.17.0-test APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.17.0-test/Mnote-Android-1.17.0-test.apk)。**1.17.0-test 首页记录卡片**已发布到自建更新源：独立纸页卡片、更小的系统分类和自定义标签，Android 首页底部截图/随手记模块移除，系统快捷方式保留。Windows 同步卡片和标签样式。本次不升级服务端、不迁移记录。1.9 及之后的测试版可从应用内检查更新；更早版本可手动下载覆盖安装。详见 [版本说明](docs/home-record-cards-1.17.0-release-notes.md)、[原生视觉验收](design-qa.md) 和 [发布验证](docs/home-record-cards-1.17.0-verification.md)。安装前保存草稿，覆盖安装，不要卸载旧版。Android 沿用测试签名，非正式商店版。

1.10.1 修复“分享管理”无法查看历史图片：点击历史分享即可查看当次导出的图片快照，兼容旧分享，不依赖原记录是否仍在本机。详见 [历史分享图片说明](docs/share-history-1.10.1-release-notes.md)。

1.6.0 新增所有安卓记录入口的多标签、已有记录补改标签、首页标签 / 未分类筛选及标签账号同步。长文支持内部滚动、全屏展开编辑及到文末，页面读取改善嵌套段落顺序并支持可见叶节点描述。修正 UI 位图提前回收风险，新增仅本机的异常诊断；尚未真机复现用户报告的偶发闪退。详见 [1.6.0 标签、长文与稳定性说明](docs/android-1.6.0-tags-readable-context.md)。

Android 页面及应用弹窗采用「私人刊物」主题；首页默认全部记录，支持搜索、类型/标签筛选和原地多选。账号登录后自动同步，支持刷新拉取和删除记录。来源与无障碍说明位于设置中的“快捷方式与权限”。

单次摘录只走截图，不读取选区或剪贴板，继续使用“圈选 → 下一步 → 想法”的原地悬浮流程，可选择保留完整截图上下文。随手记默认只写想法 / TODO；剪贴板摘录与页面上下文各自独立、主动选择，不开启剪贴板也能保留页面文字或截图。关闭剪贴板摘录不会移除上下文。页面上下文不一定是剪贴板文字的原始出处；不保证任意应用的全文或链接均可读取，失败不自动转截图，也不提供 OCR 选字。详见 [1.5.1 独立上下文与稳定性修复](docs/android-1.5.1-independent-context.md)。系统权限页和键盘保留系统样式。

记录时素材与想法优先，其他选项按需展开；详情连续展示想法、摘录、圈选截图、完整页面和原文，不再靠预览切换隐藏内容。点击“编辑”可修改想法、摘录文字和原文，并按账号同步。详见[私人刊物交互说明](docs/private-journal-ui.md)。覆盖安装前先保存草稿，不要卸载；此包沿用测试签名。
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
