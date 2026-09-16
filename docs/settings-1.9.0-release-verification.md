# Mnote 1.9.0-test 交付验收

日期：2026-09-16。功能分支：`agent/settings-export-selfhosted-updates`。

源代码检查点：`e334198`（功能）、`86704d9`（打包）、`22dfeca9904c6855f2ae8729339c6b8d410f6d2f`（最终测试与静态检查修正）。稳定分支、用户笔记、仓库可见性保持不变。

## 自动验证

- Android：最终常规回归 458 项，其中 457 项通过、1 项联网检查按默认规则跳过；随后显式开启 `MNOTE_LIVE_UPDATE_CHECK=1`，Android 原更新网络代码从真实服务器查询版本 / 判断已是最新版通过。构建及 lint 通过（0 errors、77 warnings）。首轮发现的设置页字体常量 lint 报错已修复。
- Android 原生渲染：查看了首页、设置、导出、320dp 小屏导出截图；选中卡片同时有色彩和勾选标记。沿用 A 的背景、圆角、字体和按钮规范，未引入新设计系统。
- Windows：原生 Wine GUI 实际完成截图、批注、长文编辑、独立上下文、标签、删除恢复、账号登录导入、3 条记录导出、4 张图片访问及分享撤销；设置可进入账号与更新子页。
- Windows 安装器：静默安装、等待旧进程正常退出再升级、精确二进制比较、卸载保留原记录目录均通过。
- Capture Server：33 项测试通过，新增 5 项覆盖公开安装包、HEAD、缓存、散列、页面转义、路径 / 符号链接隔离以及失败发布保留旧版本。
- 公网更新：Android 元数据查询通过；Windows 原 WinHTTP 更新器实际下载、核对 SHA-256 / 大小 / x64 安装包格式并检查最新版本，45 项检查通过。所有 APK、Setup、Portable、SHA256SUMS 从自有服务器匿名完整下载，大小 / 散列全部匹配，无重定向、无 GitHub 或账号凭据。
- 全平台总验收：`bash scripts/verify-mnote-v1.sh` 全部 7 阶段通过，包括 Windows 同步边界、83 项记录库检查、真实临时账号激活 / 上传 / 拉取 / 图片 / 标签 / 编辑 / 删除 / 恢复 / 退出登录、45 项更新器检查、浏览器扩展模块测试、服务端 33 项及 APK 身份 / 组件审计。最终打包文件与完整回归构建产物散列一致。

界面截图由测试绘制实际原生控件，非设计 mockup：`app/build/ui-previews/{style-a-library,settings-preview,markdown-export-preview,markdown-export-small}.png` 和 `desktop-windows/build-gui-smoke/{markdown-preview,settings-preview}.png`。

## 服务部署

Capture Server 已升级到 0.4.0，备份：`/var/backups/mnote-account-upgrade.pmDneN`（服务、配置、数据）。只短暂重启 `heartnote-capture-api.service`；未修改 SSH、代理或网络。部署后本地和公网健康检查正常，错误激活码仍被拒绝，匿名请求 `/v1/captures` 返回 401；未创建测试用户或发布任何用户笔记。

安装包与静态索引位于 `/var/lib/heartnote-capture/releases`。固定下载页：<https://chenyu.online/heartnote-capture/updates/>。资产先完整落盘、索引后原子切换；同版本文件不可覆盖，私有笔记路由没有放开。

公网直接链接已逐个下载复核：

- [Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.9.0-test/Mnote-Android-1.9.0-test.apk)
- [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.9.0-test/Mnote-Windows-1.9.0-test-Setup.exe)
- [Windows 便携包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.9.0-test/Mnote-Windows-1.9.0-test-Portable.zip)

GitHub 的 `mnote-android-v1.9.0-test` 与 `mnote-windows-v1.9.0-test` 预发布作为旧版迁移镜像；两个 tag 均指向上述最终源提交，安装包 digest 与服务器资产一致。新版程序不会访问这些 GitHub 地址。

## 安装包校验

Android：`com.codex.mnote`，versionCode 23，versionName `1.9.0-test`。仍为原调试 / 测试证书，SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。

Windows：x64，版本 `1.9.0-test`，未做 Authenticode 签名。

| 文件 | SHA-256 |
| --- | --- |
| Android APK | `1da5661538599ec720877efbbd62f1bd1c4ea6df7cf1e6dd4c00eb50c638b150` |
| Windows Setup.exe | `be5d062534496117e0a03d5d6a47faabd1a796ecd783854c26a2a04937f6096f` |
| Windows Portable.zip | `3f54b27c05a79487dff5910998123c27177b58c42d19fc770195eae8e9eca2be` |
| Windows mnote.exe | `8354ed67282bcff65bcb9db279f989b9aa64fe2f8e028e4512029dce010b56e5` |

## 迁移与限制

新版从自有服务器检查和下载，不依赖 GitHub。旧 1.8 / 1.7 仍只认识 GitHub，必须先升级或手动使用上述下载页覆盖安装；本次未更改仓库可见性。Windows 便携版通过内置更新会转为安装版。

自动化环境为 Linux + Robolectric API 30 / 35 与 Wine，不等于实体手机或真实 Windows 10 / 11 高 DPI、输入法、安装提示验收。安装前先保存草稿，覆盖安装，不要卸载旧版。
