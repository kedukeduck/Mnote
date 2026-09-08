# Mnote Android 1.2.3-test：过渡页身份识别修复

包名 `com.codex.mnote`，versionCode `13`；沿用测试签名。保存当前编辑后覆盖安装，不要卸载；账号、已有记录与在线服务端不变。

## 根据实机诊断定位

用户反馈 1.2.2 的结果为“当前窗口无法确认为外部来源应用”，应用窗口 1、来源根节点 0、检查节点 0、有效范围 0。该分支发生在读取文字之前，意味着遇到缺少包名的根节点，或没被认作过渡页的 Mnote 窗口；并不能据此认定来源应用不支持文字选区。“应用窗口 1”是退出前检查过的数量，也不代表系统只返回了一个窗口。

代码中存在一个可复现错误：`onCreate` 只调用 `Window.setTitle`，没有更新 Activity 标题。Android 的 `onPostCreate` 会通过 `onTitleChanged(getTitle(), ...)` 再次设置窗口标题，导致自定义的过渡页标识被默认的 `Mnote` 覆盖。读取器随后将其视为普通 Mnote 页面并退出。

加入完整 `create → start → postCreate → resume → visible` 生命周期的回归测试后，未修改实现时在 API 30 / 35 均复现失败：期望 `Mnote one-shot capture bridge`，实际 `Mnote`。此前只调用 create / start / resume 的测试未覆盖这一步。

参考：[Android Activity 初始化与标题处理源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/app/Activity.java)。

## 本次修改

- 同时设置 Activity 与 Window 标题，使其在 postCreate 后保持一致。
- 过渡页在本次读取时，通过自己已附着的 DecorView 的 `createAccessibilityNodeInfo().getWindowId()` 获取实际窗口 ID；读取器据此识别当前过渡窗口，标题变化或缺失时也可跳过。只查询 Mnote 自己的 View，不使用隐藏 API，不保存历史 ID。
- 只允许跳过匹配本次窗口 ID 的过渡窗口，或带精确标识的 Mnote 过渡窗口；普通 Mnote 首页、编辑器、其他应用及无法确认的来源仍为读取边界。ID 与明确的其他应用包名冲突时停止，不扩大向后读取范围。
- 未取得有效 ID 时保留修复后的精确标题识别。未读到选区仍进入原截图流程，原文选项与记录想法不变。
- 诊断拆分“来源根节点未提供应用包名”“当前是 Mnote 窗口，但未匹配本次过渡页”“过渡窗口 ID 与应用身份冲突”。新增返回窗口总数、已检查应用窗口数和跳过过渡页数，继续不记录文字、链接、包名或窗口 ID，不上传或写入文件。

## 手机验证

覆盖安装后，在其他 App 选中文字，再点快捷设置里的 **单次摘录**。如仍进入截图，取消后打开 Mnote → **快捷按钮与截图设置** → **来源识别与无障碍设置**，反馈最近一次文字摘录诊断。重点看是否已跳过过渡页，以及来源根节点是否大于零。

本次修复与反馈的退出位置相符，但旧诊断合并了两种原因，不能把它当作已证实的唯一实机原因。应用不提供选区、取消选区或系统查询限制仍可能导致截图回退；尚未连接用户手机验证。

## 自动化验证

新增生命周期标题、实际窗口 ID 传递、改名 / 空根节点 / 无包名的已知过渡窗口、非本次 Mnote 窗口边界、来源包名缺失与身份冲突测试。完整跨端验证结果在发布前补充。
