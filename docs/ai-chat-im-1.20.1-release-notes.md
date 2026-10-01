# Mnote 1.20.1-test · 更像聊天的 AI 对话

Android 与 Windows 同步更新。

- 双方消息都有气泡：你的消息位于右侧、深青色；AI 位于左侧、浅色，保留清晰的角色标识。
- 长按消息打开复制与文字选择菜单；Windows 另支持右键和键盘菜单。可复制原始文字，也能选择需要的部分，不再让每条回复下方堆满按钮。
- 气泡随内容和窗口宽度排版，长回复保留完整内容及基本 Markdown 格式。
- 流式回复不再反复重建整段对话；查看旧消息时保留阅读位置，打开菜单时复制内容固定为当时的版本。
- Android 输入框与发送按钮同排，生成时提供停止操作，改进窄屏、大字号及键盘弹出时的布局。
- 未完成回复的恢复操作移入消息菜单。不会因为打开页面或长按消息自动调用模型。

既有记录、聊天历史、模型配置及同步方式不变。本次不修改服务端，无需迁移数据或重新填写模型密钥。

## 更新方式

在「设置 → 版本与更新」检查更新，也可从自有服务器下载安装包：

- [Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.20.1-test/Mnote-Android-1.20.1-test.apk)
- [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.20.1-test/Mnote-Windows-1.20.1-test-Setup.exe)
- [Windows 便携包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.20.1-test/Mnote-Windows-1.20.1-test-Portable.zip)

请先保存草稿，直接覆盖安装，不要先卸载。Android 延续测试签名，Windows 未作商业代码签名；均为测试版。

[交互细节与验证记录](ai-chat-im-ui.md)
