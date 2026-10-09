# Android 1.21.1-test 发布验收

日期：2026-10-09。分支：`agent/release-android-1-21-1`。发布源码提交 `c596401`，标签 `mnote-android-v1.21.1-test` 已推送 GitHub。

- 版本：`com.codex.mnote`，versionCode 39，versionName `1.21.1-test`。Windows 保持 1.21.0-test。
- 标签滚动、统一记录、标签输入／选择与更新界面共 56 项测试通过，0 失败、0 跳过；APK 构建及 lint 通过。
- 签名证书与 1.21.0-test 一致，SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。
- 公网 APK 完整下载后与本地包大小、SHA-256 一致，无重定向；下载页与更新清单均包含新版。APK 大小 23,661,167 字节，SHA-256：`b7902eebf685da71799c5a0c8819a1efecbadc83e4b0c4f11e0199e196e0d8a8`。
- 显式启用 `MNOTE_LIVE_UPDATE_CHECK=1` 和 `MNOTE_EXPECTED_UPDATE_VERSION=1.21.1-test`，实际 Android 更新网络代码从公网发现新版，并确认新版不重复提示升级，通过。
- 更新清单由 26 条增加为 27 条，旧条目逐项保持不变，包括所有 Windows 条目。业务服务未重启，健康检查正常，匿名笔记访问仍返回 HTTP 401。
- 发布前清单备份：`/var/backups/mnote-release-1.21.1.E3ITHH/releases.json`，SHA-256：`527f57a46faf0268a926032fab4b3c577221e7d0ce1f14a6c58fa132b6dd3c9b`。需要撤回更新时应先核对后续发布再原子调整清单；不会自动回退已安装客户端。旧安装包保留。

仅发布安卓安装包及更新信息，不迁移账号、笔记、聊天或模型配置。自动化使用 Robolectric，不代替手机真机验收；本版继续使用测试签名。

[下载与版本说明](android-1.21.1-release-notes.md)。升级前先保存草稿，覆盖安装，不要先卸载。
