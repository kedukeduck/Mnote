# Mnote 1.11.0-test · 分享画廊与已有标签验收

日期：2026-09-18。分支：`agent/share-gallery-tag-picker`。功能提交：`2269a8234a1ab5fda203da6cc0bd340cc126054d`；最终 Android 文案资源修整：`76fe9275d5e81f9afc9ad023f1587bb88bf4b4c6`。均已推送 GitHub，未修改稳定分支或仓库可见性。

## 交付行为

- 双端分享管理打开即自动加载历史分享封面，按需读取可见卡片；无需先点击历史条目。卡片展示日期、数量及图片角色，点击 / 双击进入原有多图查看器。
- 只读取当次分享快照，不重新导出、不创建新公开链接。优先批注图；纯文字、失败重试、撤销确认均有独立状态。
- Android 缩略图缓存上限 8 MiB，Windows 最多 16 张缩略图；没有新增磁盘图片缓存。账号或页面变化后不接收迟到回调。
- Android 记录 / 编辑共用可搜索、多选、分页的内联已有标签选择器，支持悬浮窗口上下文；Windows 下拉选择后合并到输入框。保留手写标签、大小写去重、20 个 / 每个 32 字限制。
- 候选限当前账号本机可见的非删除记录，包括已经拉取的跨设备记录；不混入其他账号或访客记录。

## 验证

`bash scripts/verify-mnote-v1.sh` 全部 7 阶段通过。首次终端会话断开导致构建取消；重新执行完整脚本成功，并非忽略失败。

- Android：496 项，495 通过、1 项可选联网测试默认跳过；构建和 lint 通过。随后只抽取文案资源，重新运行相关 40 项全部通过、重新 assemble / lint；最终 0 errors / 82 warnings，比原版 84 warnings 少 2 项，没有增加抑制或忽略基线。
- 新测试覆盖：列表未点击已显示真实封面；点击封面打开相同图片索引；预览失败后重试；撤销确认与取消；账号变化丢弃迟到图片；空列表；标签搜索、多选 / 取消、手写保留、去重、数量限制、已删除 / 其他账号排除；非 Activity 悬浮 Context 无需新弹窗即可选择及分页。
- Windows 原生 GUI：已有标签下拉选择两次只保留一次、手写内容不丢失、保存后持久化；分享列表未打开详情即完成真实图片解码；随后查看全部历史图片、撤销保留原记录。长文编辑、筛选、多选批量导出 / 删除、同步和回收站回归通过。
- Windows 记录库 99 项、更新器 45 项通过；临时真实账号上传 / 拉取 / 图片 / 标签 / 编辑 / 删除 / 恢复通过。
- Windows 安装器：安装、等待旧程序正常退出后升级、精确二进制匹配、卸载保留记录目录通过。
- 浏览器扩展检查与测试、服务端 34 项测试、Android APK 身份与组件审计通过。
- 实际 UI 已检查：`app/build/ui-previews/share-gallery-preview.png`、`share-gallery-small.png`、`existing-tag-picker.png`；Windows `desktop-windows/build-gui-smoke/share-gallery-preview.png`。截图使用测试数据，不读取用户笔记。
- 发布后 APK / Setup / Portable / 两份 SHA256SUMS 均从公开网址完整下载，大小和 SHA-256 与本地文件及元数据一致，无重定向。下载页和健康检查 200；笔记、分享列表、分享详情及图片匿名请求均 401。
- Android 原更新客户端显式联网读取自有服务器更新信息通过；Windows 原 WinHTTP 更新器从公开地址下载并验证 1.11.0-test 安装包成功，45 项检查通过。

## 发布与升级

服务端仍为现有 0.4.2，本次只发布静态安装包与更新元数据，未重启服务端、未迁移数据库、未修改用户数据。更新不依赖 GitHub 是否公开。

Android 包名 `com.codex.mnote`，versionCode 26，`1.11.0-test`，沿用原测试证书 SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。Windows 是未签名 x64 测试版。

| 文件 | SHA-256 |
| --- | --- |
| Android APK | `73293b46a9c1fe17b5c1cdd3a5e927b050770b8c94cebb8ea784e567bfd7673a` |
| Windows Setup.exe | `052026956a9732a86eb53f2627bf7cee49c50a1fcb94adf9d1223bdc70c3905f` |
| Windows Portable.zip | `561eed9da332df7c7b22e0f92037af3d5eea9091a69d7928752edded6b8a6867` |
| Windows mnote.exe | `d8b2f4b754e128a462655f51c286363f5d452900ee11fa4d004d80bca54fdb80` |

下载：[Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.11.0-test/Mnote-Android-1.11.0-test.apk) · [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.11.0-test/Mnote-Windows-1.11.0-test-Setup.exe)。保存草稿后覆盖安装，不要卸载旧版。

验证环境为 Linux、Robolectric API 30 / 35 和 Wine，未做实体 Android 手机或真实 Windows 10 / 11 手动验收。
