# Mnote 1.21.0-test · 统一记录入口

Android 与 Windows 同步更新。

- 「随手记」与「单次摘录」合并为「记录」：点击后先截图，再进入记录编辑页。
- 想法、摘录、页面原文、来源应用与链接、完整截图、圈选批注、标签可分别选择保留。取消勾选不清空本次草稿，未选内容不会进入最终记录或同步。
- 初始截图作为完整页面保留；「圈选与批注」从这张原图编辑，结果单独保存，不再次截屏，也不覆盖原图。
- 剪贴板与页面文字仅在主动点击后读取；可手动编辑文字。截图失败时可以继续仅记录文字。
- 保留已有标签选择、保存后聊天，以及原有记录、分享和同步功能。改进长原文输入、键盘遮挡与重复保存保护。

安卓系统已固定的旧快捷按钮均转到新流程。如仍显示两个按钮，可手动移除一个；新增列表只提供一个「记录」按钮。Windows 使用 Ctrl+Shift+F9，旧 Ctrl+Shift+F8 保留为同一入口的别名。

本次不修改服务端业务程序，不迁移记录或重置账号、模型配置。新建记录支持模块选择；已有记录保持原有文字编辑能力及图片附件。

## 更新方式

在「设置 → 版本与更新」检查更新，或从自有服务器下载安装：

- [Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.21.0-test/Mnote-Android-1.21.0-test.apk)
- [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.21.0-test/Mnote-Windows-1.21.0-test-Setup.exe)
- [Windows 便携包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.21.0-test/Mnote-Windows-1.21.0-test-Portable.zip)

安装前先保存草稿，直接覆盖安装，不要先卸载。Android 沿用测试签名，Windows 未作商业代码签名；均为测试版。更早的 GitHub 更新源客户端可以使用上述链接手动覆盖安装。

[交互、兼容与自动化验证说明](unified-capture-editor.md)。自动化验证不代替 Android 真机及原生 Windows 设备验收。
