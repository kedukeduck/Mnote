# Mnote 1.10.0-test 交付验收

日期：2026-09-17。功能分支：`agent/record-multiselect-image-preview`。源提交：`e044380d8b37445cce80f96f1d7cc6e150fed643`。稳定分支、用户笔记和仓库可见性未变更。

## 功能与界面

- 沿用 A「轻盈极简」原生设计。Android 导出卡片展示三种截图的真实缩略图，点击可查看对应大图；完整页面可滚动并显示圈选位置，当前角色突出显示。图片异步解码、有限缓存，重新布局不会清空已加载的预览。
- Windows 导出列表显示真实缩略图，大图沿用原有角色切换和缩放。纯文字记录不显示空缩略图。
- Android 长按记录、Windows 长按 / 右键 / 多选入口，在列表原地选择；计数、全选当前、取消、删除确认、仅导出已选记录。每次最多 100 条。
- 改变筛选移除不匹配的选择；切换账号清空选择；删除确认后账号变化不会跨账号删除。Android 全部校验后一次性持久化删除；Windows 每条持久化，部分失败时分别报告成功和失败数量。
- 预览不创建分享链接。确认导出后，Markdown 保留图片语法，并附每张原始图片链接；不改变分享授权、撤销或账号隔离规则。阅读器仍需联网且支持远程图片，不能保证所有外部 AI 自动抓取图片。

已实际检查原生渲染截图（测试数据，不含用户笔记）：

- `app/build/ui-previews/record-multiselect.png`
- `app/build/ui-previews/markdown-images-preview.png`
- `app/build/ui-previews/markdown-full-image-preview.png`
- `desktop-windows/build-gui-smoke/multiselect-preview.png`
- `desktop-windows/build-gui-smoke/markdown-preview.png`

## 自动化验收

`bash scripts/verify-mnote-v1.sh` 全部 7 阶段通过。

- Android：474 项，473 通过，1 项显式联网检查默认跳过；构建通过，lint 0 errors / 81 warnings（没有新增基线或忽略配置）。新测试包含 API 30 / 35 原地多选、删除确认 / 取消、未选记录保留、账号变化拒绝删除、批量原子校验、100 条上限、状态恢复、精确预选导出、真实图片加载与重布局保持、角色大图、预览零网络请求。
- Windows 原生 Wine GUI：截图、批注、上下文、长文编辑、标签、同步；3 条记录多选导出、4 张图片成功访问、撤销后图片失效且原始库保留；多选预选导出、打开实际大图、取消删除 / 确认删除、回收站保留均通过。
- Windows：83 项记录库检查，45 项更新器检查，同步边界及临时真实账号全流程通过。
- Windows 安装器：安装、等待旧进程退出后升级、精确二进制比较、卸载保留原记录目录通过。
- Capture Server：33 项全部通过，包含图片分享授权 / 撤销、Markdown 结构和原图链接、静态安装包及路径隔离。浏览器扩展模块测试通过。
- 发布后公网复核：APK、Setup、Portable 及两份 SHA256SUMS 均匿名完整下载，大小与 SHA-256 全部匹配，无重定向。公开下载页 / 健康检查返回 200，私有记录匿名访问仍返回 401。显式启用 Android 原网络更新检查后，1 项通过且未跳过；Windows 原 WinHTTP 更新器下载 1.10.0-test 安装包并验证成功，45 项检查通过。

## 发布与数据保护

Capture Server 更新到 0.4.1，只修改 Markdown 图片输出格式，没有数据库迁移。部署前保留服务、配置和数据备份：`/var/backups/mnote-account-upgrade.peZmCv`。只短暂重启 `heartnote-capture-api.service`，健康检查和错误激活码拒绝检查通过；未修改 SSH、代理、网络或其他服务，未创建生产测试账号、导出或删除用户笔记。

安装包发布至自有服务器 `/var/lib/heartnote-capture/releases`；版本文件不可覆盖，索引原子切换。下载不依赖 GitHub 可见性。本次不额外创建 GitHub 安装包镜像；1.9 及以后的内置更新直接使用自有服务器，旧版本仍可手动下载覆盖安装。

- [Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.10.0-test/Mnote-Android-1.10.0-test.apk)
- [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.10.0-test/Mnote-Windows-1.10.0-test-Setup.exe)
- [Windows 便携包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.10.0-test/Mnote-Windows-1.10.0-test-Portable.zip)

## 安装包校验

Android：`com.codex.mnote`，versionCode 24，versionName `1.10.0-test`。延用原测试证书，SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。

Windows：x64，`1.10.0-test`，未签名。打包二进制与完整验收构建一致。

| 文件 | SHA-256 |
| --- | --- |
| Android APK | `8b64b3f251bbd2e8bebf056dbd3dfdabd083d05350f893b65ed1edf8e85c8a0b` |
| Windows Setup.exe | `d82a6a1481c0d29ccc5a138e0a57102d4d6304da79b15a2f1b0bac3288b2feb0` |
| Windows Portable.zip | `bee566d524e6f7b0c4f65222f1d5937195d725ced30f72bd4adf54c1ac6aa4d2` |
| Windows mnote.exe | `2b981231c0e2cb8ba4c2b5b8b508ed4fb2997a3817ed64f5cf47bb2103989a68` |

自动化运行于 Linux、Robolectric 和 Wine，不能替代实体 Android 手机 / Windows 10、11 的触摸、输入法、高 DPI 与系统安装提示验收。安装前保存草稿，覆盖安装，不要卸载旧版。
