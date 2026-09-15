# 1.7.0-test 双端更新交付校验

日期：2026-09-15。源代码：`b912691df3c8a5b37725a6c42578c21380e99505`，分支 `agent/cross-platform-updates`。基础功能提交 `2a59378`；升级退出交接修正提交 `b912691`。没有修改稳定分支、线上 Capture Server 或用户账号数据。

## 下载

- [Android APK](https://github.com/kedukeduck/Mnote/releases/download/mnote-android-v1.7.0-test/Mnote-Android-1.7.0-test.apk)
- [Windows x64 安装包](https://github.com/kedukeduck/Mnote/releases/download/mnote-windows-v1.7.0-test/Mnote-Windows-1.7.0-test-Setup.exe)
- [Windows x64 便携包](https://github.com/kedukeduck/Mnote/releases/download/mnote-windows-v1.7.0-test/Mnote-Windows-1.7.0-test-Portable.zip)

首次手动覆盖安装上述版本；之后从首页“更新”进入应用内更新。不要卸载旧版，先保存草稿。Android 沿用测试签名，Windows 未做 Authenticode 签名。便携版通过应用内安装器更新后转为安装版，请改用新快捷方式。

## SHA-256

| 文件 | SHA-256 |
| --- | --- |
| Android APK | `193526c2210f3c1b42ca9fd29733055a9191802a204058ab8881f81b6b7d51e5` |
| Windows Setup | `b9103d0580dedef1fa7270a3bb21704578f3bd9b287c9a720b72fa06a246305c` |
| Windows Portable ZIP | `b3a7c91264a99309a00f6d400f43082c647fea93b2d2e008e5c73f1ac0556fb2` |
| 包内 mnote.exe | `a0f264e7c391fddcf0f64aef10ca4142a0b2cc8edf94adbe544e64fad044e315` |

Android 包名 `com.codex.mnote`，versionCode 21，versionName `1.7.0-test`。证书 SHA-256 为 `b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`，与先前 1.6.0-test 一致。

## 已通过

- `scripts/verify-mnote-v1.sh` 完整验证：Android 440 项测试，0 失败、0 跳过；构建成功；lint 0 error / 67 warnings。警告未声称清零。
- Windows x64 静态运行时构建；40 项更新边界检查；60 项知识库检查；WinHTTP 同步冒烟、临时账号服务器激活 / 上传 / 拉取 / 编辑 / 删除恢复 / 退出测试。
- Wine 界面操作：截图圈选、笔迹、想法与标签、完整上下文、编辑原文、即时刷新、筛选、删除恢复、可选剪贴板、独立上下文、显式导入、更新入口、版本查询完成后按钮状态。修正了 UI 测试在页面尚未初始化完成就判定“就绪”的竞态。
- 隔离 Wine 安装：安装、`/UPDATE /UPDATEPID` 等待旧进程（包括窗口消失后的模拟后台收尾）、准确安装 payload、卸载及现有笔记目录保留。
- 浏览器扩展测试及本地 Capture Server 22 项测试（含 MCP）。
- 公开发布 APK 再下载后的哈希与签名核对；GitHub 生成的附件 digest 与本地 SHA-256 一致。公开 Windows 安装包通过实际 WinHTTP 更新下载器下载并重新校验，不执行该下载包。

## 仍需真机验收

Android 系统“安装未知应用”授权、系统安装确认及实际覆盖升级，Windows 10 / 11 安装向导 / SmartScreen / 安全软件交互。Wine 界面截图没有真实 Windows 窗口装饰，不能视为实机外观。自动化数据保留检查不替代用户升级前的备份与保存草稿。

更新架构与后续发布命名规则见[双端更新说明](cross-platform-1.7.0-updates.md)。
