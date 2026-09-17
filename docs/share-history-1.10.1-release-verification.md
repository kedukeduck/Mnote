# Mnote 1.10.1-test · 历史分享图片验收

日期：2026-09-17。分支：`agent/share-history-images`。源提交：`dd075bda35f1bfd97d5e7a02a560d09571c3aec8`。未修改稳定分支或仓库可见性。

## 修复原因与行为

1.10.0 的预览只覆盖“导出前”的记录选择页；分享管理仍只有列表与撤销功能，历史条目没有图片入口。现在 Android 点击条目进入 `ShareHistoryActivity`，Windows 点击“查看分享图片”或双击条目打开图片窗口。

图片通过所属账号鉴权，读取当次导出的独立图片副本，不重新导出、不创建公开链接、不依赖当前本机记录。接口从既有 `exports.assets` 字段读取清单，旧分享不需要迁移。原记录已删除或修改不影响快照；已撤销分享的图片已清理，无法恢复预览。

- `GET /v1/exports/{id}`：历史图片清单。
- `GET /v1/exports/{id}/assets/{name}`：历史图片字节，支持 HEAD，禁止缓存。
- 两个接口均限所属账号，其他账号、匿名请求和旧 read / write / AI 凭证均不能读取。
- Android 按需读取一张图片、限制传输大小并采样解码；切换角色、页面关闭或账号变化会丢弃过期回调。Windows 通过原 WinHTTP 通道读取后在内存解码，保留缩放 / 拖动。
- 撤销单独确认；空图片分享、失效分享、网络失败有明确反馈。

## 验收

`bash scripts/verify-mnote-v1.sh` 全部 7 阶段通过。

- Android：482 项测试，481 通过、1 项显式联网更新检查默认跳过；构建通过，lint 0 errors / 84 warnings。未新增忽略基线。
- 新 Android 测试覆盖：点击历史条目打开详情而非撤销框、无本机原记录时读取真实图片、切换角色不重复加载、文字-only / 已撤销 / 损坏图片提示、撤销确认和取消、账号变化阻止迟到图片显示。首轮测试发现的重复首图请求已修复。
- Windows 原生 GUI：实际从临时账号服务器读取历史分享的图片、成功解码显示、切换图片、关闭后撤销；此前的摘录、批注、同步、多选导出、删除和恢复也通过。
- Windows：93 项记录库检查，45 项更新器检查；临时真实账号上传、拉取、PNG 字节、标签、编辑、删除 / 恢复和退出登录验证通过。
- Windows 安装器：安装、等待旧进程退出后升级、精确二进制匹配、卸载保留原记录目录通过。
- 服务端：34 项通过。新增验证包括原记录清除后仍能读取历史副本、旧快照字段兼容、所属账号限制、HEAD、禁止缓存、路径越界 / 错误文件名拒绝、撤销后无法访问。
- 实际原生截图已检查：`app/build/ui-previews/share-history-images.png`、`desktop-windows/build-gui-smoke/history-preview.png`。使用测试数据，未读取用户笔记。
- 发布后联网复核：Android 原更新通道显式联网检查通过；Windows 原 WinHTTP 更新器下载并验证 1.10.1-test 安装包成功，45 项检查通过。APK、Setup、Portable 和两份 SHA256SUMS 均从公开网址完整下载，大小 / 散列与本地包一致，无重定向。下载页和健康检查返回 200，私有记录、历史分享详情和历史图片匿名请求均返回 401。

## 服务与安装包

Capture Server 已更新到 0.4.2，没有数据库迁移。备份：`/var/backups/mnote-account-upgrade.ni9plP`。只短暂重启笔记 API 服务，健康检查和错误激活码拒绝验证通过；未修改 SSH、代理或其他服务。

双端安装包发布于自有服务器，更新不依赖 GitHub。Android 包名仍为 `com.codex.mnote`，versionCode 25，版本 `1.10.1-test`。原测试证书 SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。Windows 为未签名 x64 测试版。

| 文件 | SHA-256 |
| --- | --- |
| Android APK | `0b0dbbed866bc8a4f75665ecbda240bd471c8b6d577e12b94904564a338d1964` |
| Windows Setup.exe | `45e77eaae2f7e311a37c94b7f02918b2ba610fc1b54e99c217b88ed96dc8978d` |
| Windows Portable.zip | `25092b8fdbbba2168fff1c4c42535215d963ca29215ee19057a837481a2e8659` |
| Windows mnote.exe | `653561d42b798e6ee1937bfa391a545827f489f86b1dfacb2298da290486c727` |

下载：[Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.10.1-test/Mnote-Android-1.10.1-test.apk) · [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.10.1-test/Mnote-Windows-1.10.1-test-Setup.exe)。覆盖安装，不要卸载旧版。

自动化环境为 Linux、Robolectric API 30 / 35 与 Wine；未做实体手机或真实 Windows 10 / 11 上的手动验收。
