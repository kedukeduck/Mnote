# Mnote Android 1.0.1-test：跨应用磁贴截图修复

- 发布标识：`mnote-android-v1.0.1-test`
- 包名：`com.codex.mnote`
- 版本：`1.0.1-test`，versionCode `2`
- 范围：Android 快捷设置“单次摘录”的启动与截图时机。

## 问题与修改

用户在其他 App 点击“单次摘录”，第一次没有明显响应，第二次进入 Mnote 后截到自己的页面。

代码中的确定问题是透明触发 Activity 使用默认任务归属，磁贴通过 `NEW_TASK | CLEAR_TOP` 启动它，会复用 Mnote 的已有任务。透明页下面因此可能是 Mnote 首页或旧编辑页，而非用户正在看的应用。新版本为触发页设置空 taskAffinity，磁贴使用独立新任务，避免拉起或清理已有 Mnote 页面。

截图不再仅依赖 `onResume` 后固定等待 240 毫秒。新流程等待 Activity 恢复并获得窗口焦点，再等待 350 毫秒，让快捷面板有时间收起；失去焦点或进入后台时取消待执行请求。无障碍服务已启用但正在连接时，单次请求最多等待约 3 秒，超时显示设置／重试提示。

Android 15 的磁贴 PendingIntent 显式配置创建方的启动授权，只用于交给 SystemUI 的不可变、明确目标的点击入口。该兼容处理针对首次启动可能被限制的路径；未取得用户手机日志，不能断言这是第一次点击无反应的唯一原因。

只有截图返回且触发页仍在前台时才打开编辑页；用户中途离开时清理该次临时截图。需要进入无障碍设置时结束当前采集，设置后回到目标 App 重新点击，避免自动截取设置页面。

本版不新增无障碍悬浮按钮入口。仍从下拉快捷设置中的“单次摘录”启动；Mnote 首页测试按钮有意截取当前的 Mnote 页面。

参考：[Android 任务栈规则](https://developer.android.com/guide/components/activities/tasks-and-back-stack)、[PendingIntent 启动授权](https://developer.android.com/guide/components/activities/secure-bal)。

## 自动化验证

运行：

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
```

新增 `CaptureTileFlowTest` 在 Android API 30 和 35 的 Robolectric 环境验证：

- 磁贴启动目标、独立任务标志、空 taskAffinity、非导出与最近任务隐藏。
- PendingIntent 不可变、明确目标与 Android 15 创建方授权。
- 升级后使用新的 PendingIntent 标识，不复用系统缓存的旧版任务启动标志。
- 第一次点击等待焦点，动画等待前不截图，同一请求只触发一次。
- 面板重新展开后取消旧计时，重新收起后再执行。
- 服务正在连接时自动继续原请求；超时显示可操作提示。
- 用户在计时或截图返回前离开时，不在后台打开编辑页。
- 截图回调完成前不打开编辑页；完成后在截图任务内打开编辑页。

这类测试验证启动参数与生命周期流程，截图服务由测试替身控制，不证明真实系统桌面合成、厂商快捷面板动画或图片像素已经通过实机验收。

2026-09-06（UTC+8）验证结果：新增 10 个场景在 API 30／35 各执行一次，共 20 项通过；既有配置测试 5 项通过；总计 25 项，无失败或跳过。Debug APK 构建与 lint 通过；最终 APK 的版本、隔离任务属性与签名已核对。

最终测试结果、APK SHA-256 与源码提交见同一 GitHub Release 的 `release-manifest.json`。APK 使用与 1.0.0-test 相同的测试签名；证书 SHA-256 为 `b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。

## 安装与手机验收

1. 下载同一发布页的 `Mnote-Android-1.0.1-test.apk`，直接覆盖安装，保留原记录与同步配置；无需卸载。
2. 首次升级后打开 Mnote，确认“Mnote 单次摘录”无障碍服务已启用，再回到目标应用。
3. 分别让 Mnote 首页、已有编辑页停留在后台，在浏览器／聊天／阅读器前台点击磁贴一次。
4. 确认直接出现目标应用截图的编辑页，图片没有 Mnote 首页或快捷面板；取消或保存后可回到来源应用。
5. 分别测试冷启动、锁屏解锁、正常／慢动画、重复打开面板、截图期间按 Home，以及 Android 11／14／15。
6. 对系统禁止截图的页面，仍应显示限制／失败，不要求截图成功。

本次构建环境没有连接 Android 实机。上述人工场景均待用户设备验证，不能把自动化通过写成全机型已修复。
