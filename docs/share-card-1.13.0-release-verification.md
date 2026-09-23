# Mnote Android 1.13.0-test · 分享卡片验收

日期：2026-09-23。分支：`agent/android-share-card`。服务端提交 `5655ddc`；Android 功能、字体、测试提交 `8b79c76`，均已推送 GitHub。未改稳定分支、未强制推送、未变更仓库可见性。

## 功能范围

记录详情 → 分享 → 原地勾选模块 → 实时一屏预览 → 保存相册。卡片为 1080 × 1920 PNG，使用纸色、宋体摘录、小引号；无标签、时间、记录类型或截图类型说明。原文和来源只通过确认后的二维码快照查看。完整截图保持比例，超长文字明确报错，不静默截断。

仅 Android 新增制作入口；Windows 保持 1.12.0-test。Capture Server 0.6.0 新增限定字段、独立令牌的公开落地页，复用现有分享管理及撤销能力，不迁移数据库。

## 自动化与视觉验证

- `bash scripts/verify-mnote-v1.sh` 七阶段通过，日志 `/tmp/mnote-share-card-full-verify.log`。Android 初次全量 536 项，535 通过，1 项可选联网测试默认跳过；随后对最终排版、恢复状态调整执行分享卡片 / 相册 / 记录详情专项 **36 项全部通过**，并单独验证 320 × 568 小屏。
- 最终源码再次 assemble、lint 通过：**0 errors / 82 warnings**，与既有基线数量一致，无新增忽略或抑制。中途发现的 break-strategy lint 常量/API 兼容警告已通过使用平台默认换行策略消除。
- 分享测试覆盖 63 个非空模块组合、1080 × 1920 尺寸、在最终整张图片上解码二维码、长文/空选择/缺失图片明确失败、异步过时结果丢弃、保存位图就是当前预览、勾选不发布、取消确认不发布、精确字段、重复保存不重复发布、保存失败撤销、账号变更、记录变更、旋转恢复及保存中重建防重。
- 相册测试运行 API 28 / 30 / 35，验证 PNG 实际写出、MediaStore pending 发布、写入失败或发布失败只清理本次新插入行；故意模拟磁盘失败的测试会有 native write exception 诊断输出，JUnit 结果全部通过。
- Windows GUI、同步 smoke 通过；记录库 104 项、更新器 45 项通过；临时账号上传、拉取、编辑、标签、删除、恢复通过。没有更新 Windows 安装包。
- 浏览器扩展测试通过；服务端 **41 项通过**，包括新增 5 项卡片 HTTP 测试：字段隔离、权限、同意、版本冲突、不可变快照、幂等、撤销、Markdown 令牌隔离、XSS/不安全 URL、存储失败清理。
- 实际原生卡片和预览已与批准图配对检查；原文页面由 Chromium 在 390 × 844 渲染。详见项目根目录 `design-qa.md`，final result 为 passed。
- 发布后显式启用 `MNOTE_LIVE_UPDATE_CHECK=1`，Android `AppUpdateLiveTest` 通过，使用实际原生更新客户端读取自有服务器，不依赖 GitHub 公开状态。

## 部署与公开下载

仅重启 `heartnote-capture-api.service`，服务端从 0.5.0 升至 0.6.0；备份虚拟环境、配置及数据位于 `/var/backups/mnote-account-upgrade.pjyrxy`。健康、邀请校验和服务 active 检查通过，部署未创建生产账号或笔记。未修改 SSH、代理、网络配置。

HTTPS `/health` 与 `/assets/share-card.css` 返回 200；无效二维码路径 404；匿名笔记和分享列表接口仍为 401。新接口的有效分享流程使用独立临时服务器与合成账号进行测试，不公开用户的真实笔记。

Android APK 和 SHA256SUMS 已从公网完整读取，无重定向，字节数、SHA-256 与更新元数据及本地包一致。下载页包含新 APK；更新源保留原 Windows 1.12.0 发布信息。没有覆盖任何已发布的安装包版本。

- Application ID：`com.codex.mnote`
- Version：`1.13.0-test` / versionCode `28`
- APK 字节数：`23765393`
- APK SHA-256：`c02f584f2c4ccd893e70bee23ce992ef264af1e9d3e7681800704af2b93ae387`
- 沿用原测试签名 SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`
- 下载：[Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.13.0-test/Mnote-Android-1.13.0-test.apk)

保存草稿后覆盖安装，不要卸载旧版。测试环境为 Linux、Robolectric、Wine、Headless Chromium；未做实体手机相册写入或第三方扫码软件的手动验收。普通截图中文字也可能包含私人内容，不自动打码；长截图缩进一屏后不保证小字逐字可读；超长正文需取消模块或缩短内容。
