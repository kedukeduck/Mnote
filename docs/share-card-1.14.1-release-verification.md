# Mnote Android 1.14.1-test · 分享顺序验收

日期：2026-09-25。分支 `agent/share-card-thought-first`，功能提交 `add1184` 已推送 GitHub；未修改稳定分支或重写历史。

## 改动

分享内容顺序固定为我的想法、摘录、圈选截图、页面截图，二维码仍在底部。未勾选模块直接跳过。预览、滚动阅读与 PNG 保存共用 Document 的实际绘制顺序；没有修改原记录、原截图、公开范围或高度分配规则。

## 验证

- Android 全量 560 项：559 通过、1 项可选联网检查默认跳过。测试实际检查全部 63 种非空模块组合的 Document 顺序，并额外检查完整长想法、截断摘录及长 PNG 的模块顺序；原有二维码解码、相册写入和流式 PNG 一致性检查仍通过。
- 初次七阶段脚本在 Android 测试和 assemble 已完成、lint 进行中时被信号终止（exit 143），没有测试失败；重新执行同一脚本，Android 构建与 lint 成功，已完成的测试任务复用结果。日志分别为 `/tmp/mnote-share-card-1.14.1-verify.log` 和 `/tmp/mnote-share-card-1.14.1-verify-resumed.log`。
- 最终 lint 为 **0 errors / 83 warnings**。报告包括未改动的资源、兼容性和依赖新版本提示；本次没有新增抑制、基线或升级依赖，也不将警告数量描述为与 1.14.0 相同。
- 实际生产卡片 1080 × 1920 和原生预览 390 × 844 已人工检查：想法位于顶部，两张截图连续位于文字下方，二维码仍在底部，无重叠。详见 `design-qa.md` 的 1.14.1 增量部分。
- 发布后使用 `MNOTE_LIVE_UPDATE_CHECK=1` 运行原生 AppUpdateLiveTest，1 项通过、无跳过；日志 `/tmp/mnote-share-card-1.14.1-live-update.log`。
- 续跑的七阶段回归全部完成：Windows GUI、同步和临时账号集成通过，记录库 104 项、更新器 45 项通过；浏览器扩展通过；服务端 41 项通过；最终 manifest 检查通过。没有部署服务端代码或更新 Windows 安装包。

## 发布

- Application ID：`com.codex.mnote`
- Version：`1.14.1-test` / versionCode `30`
- APK 字节数：`23550552`
- APK SHA-256：`c0b46fc917a7b3ffd046c83c274b153a005b36c9ae6d5e16f5b29f7b486e036f`
- 沿用原测试证书 SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`
- 下载：[Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.14.1-test/Mnote-Android-1.14.1-test.apk)

APK 与 SHA256SUMS 已从公网 HTTPS 完整读取，无重定向，与本地包逐字节一致，大小和摘要与更新源一致。下载页包含新安装包；Windows 最新条目仍为 1.12.0-test；健康检查返回 `ok`。仅新增不可变版本目录和更新源条目，没有覆盖旧安装包或重启后台服务。

保存草稿后直接覆盖安装，不要卸载旧版。已经保存的图片不自动改写，需要重新生成；实体手机相册及第三方软件的长图兼容性未进行手动验收。
