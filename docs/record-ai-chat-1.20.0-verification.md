# Mnote 1.20.0-test · 单条记录 AI 对话验收

日期：2026-09-30。用户明确要求安卓、Windows 与服务端一起发布。分支 `agent/record-ai-conversations`，基于 `a8f374e`；不重写稳定分支，不改变仓库可见性。

## 实现范围

- 双端保存并聊天、记录详情入口、每条记录多个独立会话，以及全局/记录内历史。
- 本机多模型 BYOK 配置、文字/图片连接测试，首次发送与跨设备续聊前确认外发范围。
- 按选中模块保存不可变资料快照；实际图片字节进入模型请求，不公开图片。旧会话不随笔记编辑悄悄改变资料。
- 流式回复、停止、草稿、异常中断保留、重试不重复提问；重命名、删除、按账号同步。
- 记录列表成功对话数标识及独立筛选，保留原类型/标签/搜索筛选。
- Chat Completions HTTPS 适配，不跟随重定向；密钥不进入账号同步、资料快照或日志。文本渲染不执行 HTML、不加载远程 Markdown 图片。
- 服务端独立聊天数据库与变更流、CAS、可续租生成锁、删除清理、跨账号隔离。旧 token/MCP/公开分享不能读取聊天。

## 边界

模型服务由用户配置。开发及上线烟测只使用合成内容和模拟 provider，不读取真实模型 Key、不产生真实模型调用；未声称已逐一验证所有服务商。首版不做全库问答、自动摘要压缩、工具调用、自动改笔记或长期记忆。

Android 密钥采用 Keystore 独立 alias，Windows 采用 DPAPI。聊天正文与资料在自有服务器持久保存，但不是端到端加密。客户端取消无法撤回已到服务商的数据。服务端与客户端均保留原始未完成文本；超容量明确报错，不静默截断。

删除原记录会删除会话与快照，恢复记录不恢复旧聊天。删除聊天本身不删笔记。并发冲突保留本机副本；上传成功但确认丢失的等价重放不产生假冲突。会话收到最终回调前清除正在生成状态，避免按钮继续禁用。

## 已完成的专项检查

- Android 最终专项 63 项：核心 30、界面 18、原有布局 14、原生渲染 1，全部通过。`/tmp/mnote-1.20-final-focused-android.log` 含构建和 lint 成功。
- 首次全量 637 项发现 1 个大字号/键盘空间布局回归，已修复：可选的「保存并聊天」不再作为第二条固定工具栏挤占写作空间。保留原有顶部保存入口，不放宽旧测试。
- Android 原生 Skia 预览已检查 390×844、390×500 键盘占位、320×680 聊天窗口，以及原写作页 360×400 / 1.5 倍字号。这不是实体手机输入法或系统杀进程验收。
- Windows 实际 Win32/Wine GUI 覆盖保存后进入、草稿恢复、模型表单、资料范围、全局历史、授权取消及 680×620 小窗口；旧截图/编辑/筛选/导出/撤销/多选流程均有回归。不是实体 Windows 多屏/高 DPI 全面验收。
- Windows 真实本地账号 HTTP 集成覆盖 `If-Match`、JPEG 快照、另一设备拉取和续聊、冲突副本、删除与恢复；provider 始终为模拟实现。
- 服务端最终 71 项全通过，0 跳过（含 MCP）：`/tmp/mnote-ai-chat-server-tests-final.log`。聊天数据库故障不影响普通笔记读取；聊天清理失败时拒绝返回旧资料。
- 备份测试包含完整账号/vault/chat/图片/活跃分享恢复、权限/checksum、旧库兼容、并发写锁、失败不产生半包、路径及定向保留规则。

## 最终全量验收

- `scripts/verify-mnote-v1.sh` 七阶段通过；任务 `mnote-1-20-final-verification.service` 最终 inactive / Result=success / ExecMainStatus=0，日志 `/tmp/mnote-1.20-final-verification.log`。
- Android 45 类 / 638 项，0 失败/错误，1 项默认关闭的线上更新测试已在发布后单独通过。构建成功，lint 0 errors / 124 warnings。完整 XML 与 lint 备份 `/tmp/mnote-1.20-full-reports.apXQAF/`。
- Windows x64 全构建、完整 GUI、同步、135 项库检查、45 项更新器检查、111 项聊天检查，以及真实本地账号/聊天 HTTP 集成通过。最终总验收 GUI 正常退出，消除了先前交互式组合 shell 返回 143 的不确定性。
- 浏览器扩展 manifest/module 检查通过。全量服务端阶段 71 项中旧临时 MCP 依赖缓存损坏导致 1 项跳过，随后在全新、不继承系统包的 Python 3.12.3 venv 实际安装 mcp 2.2.0，重新执行完整 71 项，0 失败/错误/跳过；日志 `/tmp/mnote-mcp-verification.PhuFFc/server-full.log`。不是用 stub 补出的通过结果。
- 验证脚本现在支持 `MNOTE_SERVER_TEST_PYTHON=/path/to/test-venv/bin/python`，显式环境会先验证真实 `mcp.Client`；不再把临时目录存在视为依赖可用。

## 已发布及线上验证

版本：Android `com.codex.mnote` / versionCode 36 / `1.20.0-test`；Windows `1.20.0-test`；服务端 `0.9.0`。Android 保持旧测试证书，Windows 未作商业 Authenticode 签名。覆盖安装保留用户数据。

- 服务端 0.9.0 已部署。完整旧数据、venv、配置保存在权限 0700 的 `/var/backups/mnote-account-upgrade.JfkypV`；部署任务退出 0，健康检查与无效邀请码拒绝通过，仅重启笔记 API。公网健康正常，服务 active/running，NRestarts=0。
- `verify-deployed-accounts.py --chat --card-shares --editorial` 使用单独临时账号通过线上激活/登录、上传/拉取、聊天 CAS 与旧修订拒绝、另一会话登录读取、独立删除聊天、父记录删除后聊天墓碑、分享快照/图片/撤销及退出测试。无模型调用；旧 legacy 记录与附件行保持不变。临时凭据已撤销，测试 vault 移入私有备份隔离；`mnote-1-20-live-server.service` 退出 0。
- 已替换每日备份程序，旧程序保存为上述备份内 `previous-backup-program`。实际完整备份任务 `mnote-1-20-backup-check.service` 退出 0；压缩包及 checksum 位于 `complete-backup/`，权限 0600。此次运行使用独立新目录，没有删除原历史备份，不改变 timer/service 配置。
- 发布前在锁内备份原清单到 `/var/backups/mnote-1.20-release.TGaNqh/releases.json`。发布后原 20 条逐项保持不变，仅增加双端 1.20.0-test，共 22 条；版本文件不可覆盖、清单原子更新。
- `verify-published-update.py --version 1.20.0-test` 全部通过：三种安装包和两份 SHA256SUMS 均匿名完整下载，无重定向，字节数/摘要与本地验收文件一致，固定下载页有有效链接。任务 `mnote-1-20-public-downloads.service` 退出 0。
- Android 实际更新客户端在 Robolectric 中从公网检测到 1.20.0-test，当前版本不重复提示；`mnote-1-20-live-android.service` 退出 0。Windows 更新器也实际完整下载并校验了公网 1.20.0-test 安装包，`mnote-1-20-live-windows.service` 退出 0。
- Windows 安装、等待旧进程正常退出后升级、精确 payload 校验、卸载与旧记录保留 smoke 通过；`mnote-1-20-installer.service` 退出 0。Android 签名证书 SHA-256 保持 `b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。

| 制品 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Mnote-Android-1.20.0-test.apk | 23632765 | `2dd2790ff7c6a3faa8bbe3f29e10ee5e7a6009fc9868fb17349b2277c21b4464` |
| Mnote-Windows-1.20.0-test-Setup.exe | 1264518 | `406116bd8306917ed366d69caccf71ae78ffd7dce77d467cce2b96b15a5a006c` |
| Mnote-Windows-1.20.0-test-Portable.zip | 1482140 | `16211df5202262728a58a6204379aae5fd9d07f9f0efe9b28c883e03bb368618` |

[Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.20.0-test/Mnote-Android-1.20.0-test.apk) · [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.20.0-test/Mnote-Windows-1.20.0-test-Setup.exe) · [Windows 便携包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.20.0-test/Mnote-Windows-1.20.0-test-Portable.zip)

## 回退约束

服务端发布前保留完整 data、venv 与配置；仅短暂重启笔记 API，不修改 SSH、代理、网络或其他服务。回退服务二进制时保留升级后新写入的数据，不用旧备份覆盖当前记录。新版每日备份程序单独保留旧文件再安装，无需改变 systemd 入口。

安装包使用不可变版本目录，更新清单原子替换。若撤下新版本，只在发布锁下移除本次新增条目，不能覆盖后来新增的版本。已经安装的客户端不自动降级，使用更高版本修复。
