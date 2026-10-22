# Mnote 1.23.0-test · 后台文字稿验收

日期：2026-10-22（Asia/Shanghai）。分支 `agent/background-transcript-tasks`；服务端提交 `8485b5f`，双端客户端提交 `614d3dd`。未改写稳定分支或已有发布历史。

## 功能与数据边界

- 添加分享链接默认不解析音频；未开启时仅返回链接，不要求登录、访问音频或提交声明。开启解析后需要账号和本次权利声明。
- 客户端先持久保存提交编号和任务，再在后台请求服务端；记录保存不等待 HTTP 或转写。关闭记录页面不删除任务。同一提交重试保持相同编号，结果未知时先查历史，避免创建重复任务。
- Android 使用账号隔离的私有任务文件和 WorkManager；Windows 使用账号隔离的持久任务日志及独立工作线程。退出账号后不继续投递旧账号通知，不把账号凭据写入任务文件。
- 服务端独立收集并归档已接受任务的结果。列表只返回元数据，完整文字稿按需读取；已归档结果不再依赖核心临时任务的 24 小时保留期限。容量满时明确拒绝，不静默淘汰旧结果。
- 记录关联仅是任务元数据，不自动修改任何已有摘录。Android 确认导入时检查记录修订，Windows 导入编辑草稿后由用户保存；想法、原文、图片和标签保持原样。转写继续标记为自动生成、未经核验。
- 完成或失败后提供通知，设置内集中查看状态和结果。删除任务不删除原记录或已经导入的摘录。

Android 通知需要授权，系统省电、强制停止或无网络可能延迟本机查询与提醒；Windows 完全退出后需再次启动才能接收提醒。服务端已接受的任务继续运行，但本机离线队列尚未送达的任务不能提前在服务器执行。任务页不会伪装成系统级即时推送。

没有保存记录关联的结果可以复制或导入新草稿。Windows 的「重试原提交」用于未确认的提交；真正已失败的任务需重新添加链接并确认本次声明。旧客户端已经主动删除的临时任务不能追溯恢复。平台公开访问、媒体大小、时长、队列和提交额度限制不变，不绕过登录或访问保护。

## 验证

- 账号服务完整回归 116 项：115 项通过，1 项可选 MCP 测试跳过。包含归档、列表分页、跨账号隔离、删除竞态、幂等恢复、容量限制和备份恢复。日志 `/tmp/mnote-background-server-final.log`。
- Android 范围回归 223 项，0 失败、错误或跳过；涵盖持久队列、断网和不确定提交、24 小时边界、账号切换、元数据历史、通知、任务筛选及导入保护，以及既有记录编辑、同步、统一记录和 AI 核心流程。日志 `/tmp/mnote-background-android-release-check.log`；本次结果和 lint 报告另存于 `/tmp/mnote-background-android-reports.CvloPeVo/`。
- Android `assembleDebug`、`lintDebug` 通过；lint 为 0 errors / 128 warnings。没有执行全量 Android 测试或实体手机系统杀进程/省电验收。
- Windows x64 MinGW 构建通过，文字稿 107 项检查和更新器 45 项检查通过。最终 Wine/Xvfb GUI 验证通过，含音频关闭时立即返回、原想法与摘录保留、保存可用且没有解析请求，以及截图、编辑、AI、导出、撤销分享、多选删除等原有流程。日志 `/tmp/mnote-background-windows-final-build.log`、`/tmp/mnote-background-windows-final-tests.log`、`/tmp/mnote-background-windows-updater.log`、`/tmp/mnote-background-windows-final-gui.log`。不是实体 Windows 高 DPI、多屏或系统托盘全面验收。
- 独立只读复查未发现发布阻塞项。全部修改源码和解压后的双端应用包通过受保护服务器凭据字节检查。
- 线上 HTTPS 集成使用两个独立合成账号及自有 CC0 示例：在任务完成前保存记录并关联，随后仅轮询历史列表，证明服务端自行收集完整结果；原想法和既有摘录没有被覆盖。验证声明、幂等提交、跨账号读取/删除/关联拒绝，以及结果保存、读取、删除和任务清理。日志 `/tmp/mnote-background-live.log`。
- QA 账号凭据已撤销，合成 vault 可恢复地移入 `/var/backups/mnote-transcript-verify.nzvgk3up`。没有读取现有用户笔记、重置密码或重新生成第三方视频的完整文字稿。

## 服务端上线

账号服务已部署 `0.11.0`；升级前 venv、配置和数据保存在 `/var/backups/mnote-account-upgrade.5AWTBB`，目录权限 0700。部署只重启 `heartnote-capture-api.service`，未重启转写核心、修改 SSH、代理或网络。健康检查和无效邀请码拒绝通过，账号 API 与转写核心均为 active。

安装的 `transcripts.py` 与仓库源码完全一致，SHA-256 为 `26513c47a6e513bca70aaf8e84386f2590214046bc9bc07ab667634764e73dc1`。归档表位于原有 `transcripts/admissions.sqlite3`，已纳入现有一致性备份测试。

## 双端发布

Android 包名 `com.codex.mnote`，versionCode 43，版本 `1.23.0-test`；仍使用原测试签名，证书 SHA-256 为 `b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。Windows 的 CMake、PE 资源、安装器、更新器及打包脚本版本均为 `1.23.0-test`；没有商业 Authenticode 签名。

| 制品 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Mnote-Android-1.23.0-test.apk | 24078032 | `0459f1e4c61f7115f19111f9dc1b6920fe31b488fd6d5d4fbb513983f1884396` |
| Mnote-Windows-1.23.0-test-Setup.exe | 1363742 | `afcd9cba7e8f500207909938455e5f2afbf2c9ab5d70a80c90bb156ec7238ce0` |
| Mnote-Windows-1.23.0-test-Portable.zip | 1613076 | `20b6a043d75d4ed4830429d082fbabc1c759a168d504e1cc33f670604f04a5b7` |

Windows 被打包的 EXE 为 `82e0e90e760fde13f1f7f9edded495fba6a6922ba74b722281030e42cba03db8`。两端产物均在最终生产源码之后构建，不重用旧版本文件。

双端均已发布至自有更新源。发布前清单保存在 `/var/backups/mnote-release-1.23.0.pZFjxRmQ/releases.json`；原 31 条逐项不变，仅增加本次两个版本，共 33 条。`verify-published-update.py --version 1.23.0-test --platform both` 通过：三种应用包和两份 SHA256SUMS 均匿名完整下载，无重定向，大小及摘要与本地一致，公共下载页包含新版链接。

发布后实际客户端检查亦通过：Android `AppUpdateLiveTest` 1 项通过、0 跳过，使用真实 HTTPS 更新传输，从旧版检查得到 `1.23.0-test`，相同版本不重复提示；Windows 更新器通过公网读取清单、完整下载新版安装包并校验，再检查当前版本，45 项检查通过。日志 `/tmp/mnote-background-android-live-update.log`、`/tmp/mnote-background-windows-live-update.log`。测试没有安装到实体设备。

[Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.23.0-test/Mnote-Android-1.23.0-test.apk) · [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.23.0-test/Mnote-Windows-1.23.0-test-Setup.exe) · [Windows 便携包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.23.0-test/Mnote-Windows-1.23.0-test-Portable.zip)。覆盖安装前保存草稿，不要卸载旧应用。

## 回退约束

服务端归档表是增量新增；回退二进制时保留升级后数据，不拿旧数据备份覆盖现有笔记。停止新版收集器前检查进行中的任务，再按已验证备份恢复目标服务版本，不恢复或输出凭据到客户端。旧版客户端继续使用原任务接口，但不具备后台历史体验。

发布包目录不可覆盖。需要撤回时先检查后续发布，仅在发布锁下移除本次两个索引，不覆盖较新的清单、不删除历史包。已经安装的设备不会自动降级，客户端问题优先发布更高版本修复。功能提交、验收文档和双端版本标签均推送 GitHub 保留回退点。
