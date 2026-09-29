# Mnote 1.19.0-test · 分享图片与扫码阅读页验证

范围：Android 分享图片 renderer、服务端 0.8.0 扫码页 HTML/CSS；不修改 Windows 本机代码，不迁移数据库，不改变分享快照字段、授权或撤销协议。用户已授权安卓与网页联合发布，并授权本机浏览器只打开本地合成页面验证。

## 设计与交互

完整前后截图、逐步审查与边界见 [设计审查](share-editorial-1.19.0-design.md)。

- 分享图想法置顶完整保留，使用 Noto 衬线字体；摘录较小并置于浅底引用块；图片仍位于文字之后。短卡、截断提示、取景、长图流式保存和实时选择保持。
- 二维码 248px → 208px，面积缩小约 30%；黑白对比、4 模块静区及 M 级纠错不变。63 组合验证原尺寸整图与半尺寸定点扫码，代表组合/不同 token 另做半尺寸整图检查。未声称任意软件、任意压缩或实机长按均验收。
- 扫码页有三个以上分组时展示所选模块导航；想法/摘录/截图/原文/来源分层，图片可打开原图。混合记录原文可折叠，只有原文/来源时默认展开原文。
- 本地 Chromium 320/390/768/1280px × 六类内容，共 24 个响应式用例通过。包含 Space/Enter、焦点可见、导航目标、图片 HTTP 跳转、原文无 JS 展开及极长连续文本/域名无溢出。
- 浏览器无脚本异常、无外部请求；主文/标题/辅助文字/摘录的实测颜色对比度分别为 8.94/5.28/4.79/6.17。平台字体回退可能改变字形，未将模拟器或 Chromium 当作实体手机、Safari/TalkBack 的验收。

## 自动化

- 分享专项 4 类、63 项通过，0 失败/错误/跳过；日志 `/tmp/mnote-share-native-final.log`。
- Android 完整 42 类、589 项：588 通过，1 项默认关闭的在线更新检查在发布后单独执行并通过，0 失败/错误。构建通过；lint 0 errors / 116 warnings。
- Android 完整 XML/lint 已备份到 `/tmp/mnote-1.19-full-test-results.BJUb0f/`，防止后续在线单项覆盖报告。
- 服务端分享专项 12 项通过，包含 63 种模块组合、未选字段不泄漏、完整原文保留、旧分享兼容、恶意文本转义、严格 CSP/缓存头、撤销后不可访问。每组测试都撤销临时分享，保持 50 个有效分享的产品上限不变。
- 浏览器日志 `/tmp/mnote-1.19-browser-verification.log`；服务端专项日志 `/tmp/mnote-1.19-card-server-tests.log`。
- `scripts/verify-mnote-v1.sh` 七阶段全部通过。独立任务 `mnote-1-19-full-verification.service` 最终 inactive / Result=success / ExecMainStatus=0，日志 `/tmp/mnote-1.19-full-verification.log` 以 `Mnote V1 automated verification passed` 结束。
- Windows x64 构建、真实 GUI 捕获/编辑/筛选/导出/撤销/多选、同步烟测、135 项记录库检查、隔离本地账号集成和 45 项更新器检查通过。没有因本轮改动发布 Windows 空升级。
- 浏览器扩展 manifest/module 测试通过；服务端全部 48 项数据、HTTP、Web、MCP 测试通过；Android manifest 检查通过。

## 安装身份与发布边界

Android `com.codex.mnote` / versionCode 35 / `1.19.0-test`；APK 签名验证通过，SHA-256 证书仍为 `b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。是保留原测试证书的测试版，不是商店正式签名。Windows 安装包仍为 1.18.0-test。

发布前检查生产服务为 0.7.0 active/running，数据约 175MB、venv 16MB，磁盘可用约 7.1GB；当时清单 19 条。

## 联合发布与线上验证

- 服务端 0.8.0 已部署；完整数据、旧 venv 与配置保存在权限 0700 的 `/var/backups/mnote-account-upgrade.S4bIss`。仅重启 `heartnote-capture-api.service`，没有更改 SSH、代理、网络或数据库结构。部署任务 `mnote-1-19-deploy.service` 退出 0，健康与无效邀请码检查通过。
- 公网 CSS 4771 字节，与源码逐字节相同，SHA-256 `ebe1623a551355567b9f7bd492ee62dd7c1440199b024f12a1ed7a21b5e903f0`；HTTPS 无重定向，正确的 MIME、no-store、no-referrer 与同源 CSP 保持。安装元数据确认服务端 0.8.0，生产健康正常、服务 active/running、NRestarts=0。
- `verify-deployed-accounts.py --card-shares --editorial` 使用全新临时账号通过线上激活/登录/上传/PNG 下载/拉取/删除/退出、全模块/仅想法/旧字段分享以及新版模板模块/原文展开状态检查。删除原记录不改变分享快照，撤销后页面与图片不可访问。仅合成数据被临时分享；凭据已撤销、测试 vault 移到私有备份中，既有 legacy 记录与附件表未变化。独立任务 `mnote-1-19-live-server.service` 退出 0。
- 在发布锁保护下备份原 19 条更新清单到 `/var/backups/mnote-1.19-release.SZAnXU/releases.json`。发布后逐项确认共 20 条且无重复：原 19 条完全未改，仅新增 Android 1.19.0-test。Windows 最新仍为 1.18.0-test。
- `verify-published-update.py --version 1.19.0-test --platform android` 退出 0：APK、SHA256SUMS 均匿名完整下载，无重定向，字节数及 SHA-256 与本地已验收包一致；固定下载页包含新版链接。
- 发布后启用 `MNOTE_LIVE_UPDATE_CHECK=1`、`MNOTE_EXPECTED_UPDATE_VERSION=1.19.0-test` 单独运行 `AppUpdateLiveTest`，1 项通过、0 失败/错误/跳过。真实 Android 更新客户端通过公网传输从 1.8.0-test 检测到 1.19.0-test，验证第一方下载地址合法，当前版本检查不重复提示更新；不需要 GitHub 或账号凭据。独立任务 `mnote-1-19-live-android.service` 最终 inactive / Result=success / ExecMainStatus=0，日志 `/tmp/mnote-1.19-live-android.log`。这是 Robolectric 中的真实传输检查，不代替实体手机安装测试。

| 安装包 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Mnote-Android-1.19.0-test.apk | 23569141 | `b3749e31c0f6cc9e0f852d36c2140d92a31728ef253f8e2e8c4c38b41e01b4ff` |

[下载 Android 测试版 APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.19.0-test/Mnote-Android-1.19.0-test.apk)。请覆盖安装，不要卸载旧版；已有有效扫码链接会显示新版网页，旧分享图片本身不会自动重绘。

如需回退服务端，仅回退备份中的服务二进制并保留当前用户数据；不要恢复旧数据覆盖发布后的记录。如需撤下本次更新，在发布锁下仅撤下本次新增条目、保留后来发布的版本，不删除不可变安装包。已经安装的客户端不自动降级，应通过更高版本修复。
