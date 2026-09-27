# Mnote 1.16.0-test · 双端私人刊物验收

日期：2026-09-27。工作分支 `agent/private-journal-ui`，未合并或重写稳定分支。

发布状态补充（2026-09-28）：用户明确选择发布后，双端 1.16.0-test 与配套服务端 0.7.0 已上线，见 [线上发布验证](private-journal-1.16.0-publication.md)。下文“未发布边界”保留的是 9 月 27 日开发交付时的历史状态。

## 交付范围

Android 的全部记录、筛选、原地多选、随手记、截图编辑、连续阅读、记录修改、设置、账号、分享与更新采用统一的原生视觉。Windows 使用相同配色和字体层级，增加三栏布局及连续阅读。详细交互见 `private-journal-ui.md`，视觉对照见根目录 `design-qa.md`。

回退节点：

- `e735fdd`：开始 UI 改造前，保留此前未发布的二维码工作。
- `195e87f`：Windows 私人刊物布局及测试。
- `b726d78`：Android 统一视觉、交互及回归测试。

三个节点均已推送 `origin/agent/private-journal-ui`，没有 force-push。

## 自动验证

- `bash scripts/verify-mnote-v1.sh` 初轮七阶段通过且执行会话退出 `0`，日志 `/tmp/mnote-journal-verification.log`。最后布局和校验修正后的日志 `/tmp/mnote-journal-verification-final.log` 也完成全部七阶段，各子项通过，并以 `Mnote V1 automated verification passed` 结束；但收取该执行会话时返回 `143`。原因未确认，不能把最后一次会话退出码描述为 `0`，也未把它隐去。
- 随后独立重跑 `assembleDebug lintDebug`、APK manifest 身份检查、Android / Windows 打包载荷逐字节比较、`git diff --check`，整个复核命令明确退出 `0`；日志 `/tmp/mnote-journal-delivery-check.log`。两个安装包目录的 `sha256sum -c SHA256SUMS` 也分别退出 `0`。
- 最终 Android 全量 **577 项：576 通过、0 失败、0 errors、1 项可选联网检查跳过**。包括筛选取消/确认、关键词搜索、多选、独立页面上下文、焦点与小屏输入、隐藏字段校验、页面截图不冒充选区、账号隔离、同步及分享。
- 完整回归后补充更新页原生截图测试，`PrivateJournalAuxiliaryUiTest` **4 项全通过**，覆盖账号、权限、激活/登录密码规则、更新前确认及取消。日志 `/tmp/mnote-journal-update-visual.log`；该轮仅新增测试，不改变 APK 生产代码。
- Android assemble / lint 成功；lint **0 errors / 118 warnings**。警告包括布局中的中文硬编码及既有依赖/资源提醒，未添加抑制或基线，不能描述为零警告。
- Windows x64 PE 构建和无额外 MinGW 运行时 DLL 检查通过；真实 Win32 GUI 验证通过：截图、批注、完整上下文、长原文编辑、标签筛选、删除/恢复、独立上下文、账号登录/导入/同步、原地多选、导出取消与撤销。
- Windows WinHTTP 同步边界、记录库 **104 项**、临时账号往返集成、更新器 **45 项**通过。
- 浏览器扩展 manifest / JavaScript / 模块 smoke tests 通过；服务端 **45 项**全部通过，最终一轮耗时 40.414 秒。
- Android manifest 身份及入口检查通过；Application ID 继续为 `com.codex.mnote`，新增内部权限页不改变外部采集入口。
- Windows 安装器 smoke test 通过：安装、优雅等待旧进程退出后升级、卸载、载荷一致、已有记录保留。日志 `/tmp/mnote-journal-installer.log`。
- `git diff --check` 通过。最终 APK 与 build 输出、Windows 打包载荷与最终构建逐字节一致。

Robolectric/Skia 截图不是真机验收；Windows 使用 Wine/Xvfb，不宣称实体 Windows、高 DPI、多显示器或 OEM 输入法/相册已测。截图和对照过程详见 `design-qa.md`。

## 测试安装包

Android：`deliverables/mnote-android-1.16.0-test/Mnote-Android-1.16.0-test.apk`

- versionName `1.16.0-test` / versionCode `32` / minSdk `26`。
- 大小：`23803562` 字节。
- SHA-256：`46c4cb3e16bba22fe179cdebc6a2d09a445ab859a2d512b74959466e1bf2fe37`。
- 沿用原测试证书 SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`，签名验证通过。不是商店正式签名。

Windows：`deliverables/mnote-windows-1.16.0-test/`

- `Mnote-Windows-1.16.0-test-Setup.exe`，`1146689` 字节；SHA-256 `dcd26cbf07c93d14665b05024c678188f9170d6f4366b0077c8ae4e58567683e`。
- `Mnote-Windows-1.16.0-test-Portable.zip`，`1321710` 字节；SHA-256 `8f763d16bff267a2b3fc4b1085e14147ce818b48e738fc8a9aa51a4c41ace9f7`。
- 原生 x64 测试安装包，未声明具有 Windows Authenticode 商业签名。

先保存草稿，再覆盖安装，不需要卸载旧版。

## 未发布边界

本轮只生成本地测试包并推送代码：**没有更新生产更新清单，没有上传公开安装包 URL，没有部署或重启线上服务**。

此前未发布的“二维码查看本次全部分享内容”代码仍在分支中，需要配套服务端 `0.7.0`；本地端到端验证不等于已能在现有生产后端使用。UI 测试包不应被描述为已完整推送的线上版本。

扫码网页本轮保持原样：外部浏览器验收选择尚未回复，已撤回尝试的配色改动。没有将未经浏览器视觉核对的 CSS 混入交付。
