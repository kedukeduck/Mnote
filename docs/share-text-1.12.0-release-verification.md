# Mnote 1.12.0-test · 分享文字与原地导出验收

日期：2026-09-19。分支：`agent/share-text-settings-inline-export`。功能提交：`8b39bd3`；文件选择器恢复修复：`406212a`；最终代码与测试：`f0c7a00fe13c4e57944f98fc8e53eb2615045a48`。已推送 GitHub，未修改稳定分支或仓库可见性。

## 交付行为

- Android / Windows 分享管理卡片直接显示想法、摘录、原文摘要和真实图片预览；可以展开当次分享的全部文字。Android 长文分页且可选中复制，Windows 全文滚动阅读。
- 分享管理入口统一在设置中。首页“选择导出”和长按记录共用原地多选；前者初始不选择记录，后者选中长按项。完成选择后直接确认并选择 Markdown 保存位置，不经过第二个选择页面。
- 取消确认或文件选择不创建分享，并保留列表中的选择；不可导出的条目明确报错，不悄悄遗漏。Android 恢复文件选择器和保存中重建页面不会重复创建分享。
- 文字使用导出时的私有独立快照，不随原记录编辑或删除改变；只有所属账号能读取。公开图片路径不能访问新增文字文件。撤销会清理该次分享的文字和图片，不删除原记录。
- **旧分享没有保存文字快照，无法准确恢复历史文字。**保留已有图片，明确提示重新导出后才能回看文字，不将当前内容冒充历史记录。

## 验证

`bash scripts/verify-mnote-v1.sh` 全部七阶段通过。首次执行发现 Windows `std::max` 的 `int / LONG` 参数不一致；修正后重新完整执行成功。

- Android：508 项，507 通过，1 项可选联网测试默认跳过；assemble 和 lint 通过，0 errors / 82 warnings，与上一版本一致。没有新增忽略或抑制基线。
- 覆盖设置入口与登录保护、列表原地选择、精确已选记录、不可导出项、取消确认、文件选择器恢复、保存中页面重建、卡片文字预览、完整长文分页和表情边界、旧分享缺失文字、切换账号拒绝读取。
- Windows 原生 GUI：截图批注、剪贴板、长文编辑、已有标签、同步、筛选、原地多选、取消后再次导出、真实图文分享卡片、全文查看、撤销与回收站通过。测试使用临时账号和记录，不读取用户笔记。
- Windows 记录库 104 项、更新器 45 项通过；临时真实账号上传、拉取、PNG 字节、标签、编辑、删除、恢复和退出登录通过。
- Windows 安装器：安装、等待旧程序正常退出后升级、精确二进制匹配、卸载保留记录目录通过。
- 浏览器扩展检查和测试、服务端 36 项、Android APK 身份与组件审计通过。
- 服务端新增测试覆盖独立文字快照、完整长文、纯文字分享、旧分享、其他账号 / 匿名 / 旧令牌拒绝、公开图片路径不能读取文字、撤销清理以及原记录删除后快照仍保留。
- 实际截图已检查：`app/build/ui-previews/share-gallery-preview.png`、`share-text-preview.png`、`desktop-windows/build-gui-smoke/share-gallery-preview.png`、`share-text-preview.png`。
- 公网下载页与健康检查 200；APK / Setup / Portable / 两份 SHA256SUMS 均完整下载，无重定向，大小和 SHA-256 与本地及更新元数据一致。笔记、分享列表、文字接口匿名请求均 401。
- 发布后显式启用 Android `AppUpdateLiveTest`，使用原生更新客户端读取自有服务器更新信息通过；Windows 原 WinHTTP 更新器完整下载并校验 1.12.0-test 安装包，45 项检查通过。

## 部署与升级

Capture Server 从 0.4.2 升至 0.5.0；升级前已备份虚拟环境、配置和数据，位置为 `/var/backups/mnote-account-upgrade.unlkjH`。仅重启 `heartnote-capture-api.service`，未修改 SSH、代理或网络配置。健康和邀请校验通过，服务 active，未创建生产测试账号或记录；不迁移数据库。

旧服务程序可由备份恢复，新文字文件不改变原数据库结构。旧图片链接保持兼容。Android / Windows 安装包和更新元数据均在自有服务器，更新不依赖 GitHub 公开状态。

Android：`com.codex.mnote`，versionCode 27，1.12.0-test；沿用原测试证书 SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。Windows：未签名 x64 测试版。保存草稿后覆盖安装，不要卸载旧版。

| 文件 | SHA-256 |
| --- | --- |
| Android APK | `5f618d1f9897492772ae81c14bb96d26541ef1b51bc69ee7237b640462b49e51` |
| Windows Setup.exe | `efc587fab02d880b2d857a886b7d35c4af2b29de05962f89b3caaac49d228512` |
| Windows Portable.zip | `af6428cabb5d4ffa292f7a562b8f7266b3491746c8d94aa2efd4f7b3c2dd9286` |
| Windows mnote.exe | `7b877450f2eab66c26b9d6a287cf2b489999b4e7f643bf74a98560588b260758` |

下载：[Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.12.0-test/Mnote-Android-1.12.0-test.apk) · [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.12.0-test/Mnote-Windows-1.12.0-test-Setup.exe)。

验证环境为 Linux、Robolectric API 30 / 35 和 Wine，未做实体 Android 手机或真实 Windows 10 / 11 手动验收。
