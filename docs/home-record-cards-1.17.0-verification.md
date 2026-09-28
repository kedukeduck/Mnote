# Mnote 1.17.0-test · 首页卡片与发布验证

日期：2026-09-28。用户批准首页稿后要求缩小标签，直接实现并发布更新。分支 `agent/home-record-cards-1-17`，未合并或改写稳定分支；代码及视觉验收已有独立 GitHub 检查点。

## 交付结果

- Android / Windows `1.17.0-test` 已发布至自建更新源，匿名下载不依赖 GitHub 可见性，也无需笔记账号或 Token。
- 首页改为边界清晰的暖白纸页卡片；每条记录展示时间、紧凑系统分类和更小的自定义标签。系统分类与自定义标签使用不同底色、边框及 `#` 标识。
- Android 首页底部截图/随手记模块移除，系统快捷方式保留；Windows 窄窗口快捷入口移到侧栏。多选的删除/导出操作不受影响。
- 无记录模型、同步协议或服务端变更。类型只做展示归类，不迁移或改写用户记录。

下载：

- [Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.17.0-test/Mnote-Android-1.17.0-test.apk)
- [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.17.0-test/Mnote-Windows-1.17.0-test-Setup.exe)
- [Windows 便携包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.17.0-test/Mnote-Windows-1.17.0-test-Portable.zip)
- [固定下载页](https://chenyu.online/heartnote-capture/updates/)

## 本地自动化与视觉验收

完整入口 `bash scripts/verify-mnote-v1.sh` 最终成功：独立任务 `mnote-1-17-verification-complete.service` 结束后为 `inactive/dead`、`Result=success`、`ExecMainStatus=0`，日志以 `Mnote V1 automated verification passed` 结束。

- Android 全量测试共 580 项：579 通过、1 项正常跳过（默认关闭的在线更新测试），41 个测试类、0 失败/错误。APK 构建成功，lint 为 0 errors / 118 warnings。
- 新增 `RecordCardsTest` 验证类别、时间、自定义标签字号和间距；长按多选、取消、刷新；移除底部入口；200px 窄宽度、1.5 倍字号和 RTL 的长标签换行。
- Windows x64 构建、GUI 全流程、同步边界、115 项记录库检查、45 项更新器检查通过；隔离本地账号集成验证激活、上传、拉取、PNG、标签、编辑、删除/恢复和退出。
- Windows 安装烟测通过：安装、升级进程交接、安装文件精确匹配、卸载保留已有记录目录。
- 浏览器扩展 manifest/module 检查通过；服务端 45 项数据、HTTP、Web、MCP 测试通过；Android manifest 检查通过。
- 原生视觉比较见根目录 `design-qa.md` 和 `docs/design/record-cards-comparison.png`，多选及 Windows 宽窄窗口也有截图证据。

中途遇到的两项测试环境问题已经修正并重跑：Windows 测试关闭账号窗口后仍有设置父窗口遮挡卡片，现显式关闭并等待首页无遮挡；systemd 默认 PATH 没有 Node，完整验证任务现使用明确的 Node 路径，验证脚本也新增前置检查。先前失败/中断的执行不计为通过。最终完整脚本的 Android 步骤命中缓存，全量 XML 结果来自此前已成功执行的 Android 全套测试；在线更新测试另行强制重跑。

## 发布保护与独立审计

- 发布前锁定 `.publish.lock`，将当时 15 条更新清单备份至 `/var/backups/mnote-1.17-release.bQm4Hg/releases.json`，备份目录权限 0700。
- 使用 `scripts/publish-update-server.py` 发布两个不可变版本目录，逐包校验 SHA-256 后原子替换清单。未覆盖旧版安装包。
- 独立只读比对确认旧 15 条逐项完全相同，现有 17 条无重复，仅新增双端 1.17.0-test；公网清单与本地一致，说明与本轮 release notes 一致。
- 公网 health 返回 `{"status":"ok"}`，`heartnote-capture-api.service` 保持 active/running、`NRestarts=0`，启动时间仍为 2026-09-28 07:20:19 CST。本轮未重启服务、未访问用户账号或笔记。

如需撤回更新提示，应在发布锁保护下原子恢复本次清单备份，不删除不可变包，不恢复记录数据库，不使用较早 1.16 服务部署时的旧清单备份。已经安装的客户端不会自动降级，修复应发布更高版本。

## 真实 HTTPS 与客户端更新验证

- `python3 scripts/verify-published-update.py --version 1.17.0-test` 成功退出 0：APK、Setup、Portable 和两份 SHA256SUMS 全部匿名完整下载，无重定向；大小、清单摘要及本地验收文件摘要完全一致，下载页包含全部安装包。
- Android `AppUpdateLiveTest` 使用生产 `AppUpdateClient`，启用 `MNOTE_LIVE_UPDATE_CHECK=1` 和 `MNOTE_EXPECTED_UPDATE_VERSION=1.17.0-test`，以 `--rerun-tasks` 重跑：确实获取 1.17.0-test，同版本不再提示升级。1 项通过、0 跳过/失败/错误；独立任务 `mnote-1-17-live-android.service` 最终退出 0。
- Windows `run-updater-tests.sh --live` 使用原 WinHTTP 更新器，实际下载并校验 1.17.0-test 安装器；45 项检查通过。独立任务 `mnote-1-17-live-windows.service` 最终退出 0。
- 新版本沿用已发布的自建更新地址与选择协议。1.9 及之后的测试版可应用内检查更新；仍使用 GitHub 更新源的早期 1.7/1.8 客户端可从固定下载页手动覆盖安装一次。

## 包身份与校验

Android 为 `com.codex.mnote` / code33 / `1.17.0-test`；APK 签名验证通过，证书 SHA-256 为 `b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`，与上一版一致。Windows Setup 与主程序均为 x64，便携包内主程序与构建文件逐字节一致。发布后没有重新打包或覆盖同版本资产。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Android APK | 23565012 | `040b484c6cab726a0bdd6d8659edac367cd2b6fafad55b377f43e65543273c66` |
| Windows Setup | 1153178 | `6dac7a15a88fd7849cb60d7adff075489e2d97b64ea51903f0de9a8e621e6ed4` |
| Windows Portable | 1332567 | `91703bfbd878e85d82b2120afcd04cb32ab23fb40d0b8237fd3c9f5b733f9beb` |

测试包不等于正式商店发行：Android 沿用测试签名，Windows 未做 Authenticode 签名。覆盖安装前保存草稿，不要卸载旧版。

## 证据位置及边界

- 完整验证日志：`/tmp/mnote-1.17-verification-complete.log`；此前实际执行全量 Android 测试的日志为 `/tmp/mnote-1.17-verification-independent.log`。
- 全量 XML 与 lint 备份：`/tmp/mnote-1.17-full-test-results.HxsuyN/`，保留在线单项测试覆盖输出前的结果。
- 在线客户端日志：`/tmp/mnote-1.17-live-android.log`、`/tmp/mnote-1.17-live-windows.log`。
- 安装烟测日志：`/tmp/mnote-1.17-windows-installer.log`。

Android 截图来自生产 View 的 Robolectric/Skia 渲染，Windows 为真实 Win32 程序的 Wine/Xvfb 运行。没有宣称实体 Android OEM 字体、TalkBack、触摸长按，或实体 Windows 高 DPI、多屏和输入法均已手动验收。视觉 QA 技能用于按批准稿检查原生画面、标签尺寸、状态和平台差异；未用静态网页原型代替实际 APP。
