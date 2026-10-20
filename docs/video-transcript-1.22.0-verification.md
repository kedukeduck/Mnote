# Mnote 1.22.0-test 验证与发布

日期：2026-10-20。开发分支：`agent/mnote-video-transcript`。

## 版本与范围

- Android：`com.codex.mnote`，versionCode 40，versionName `1.22.0-test`。
- Windows：x64，应用、更新器、安装器与打包配置均为 `1.22.0-test`。
- Capture Server：`0.10.0`，账号级转写代理；既有独立转写核心、匿名网页、隔离浏览器与出口代理保持原部署。
- 后端实现提交：`4060b12`；双端功能提交：`59c7202`。后续提交仅包含测试等待修正和发布验证文档。

## 自动化与运行验证

- Android：最终范围回归 **114 项通过，0 失败、0 跳过**，包含 12 项文字稿测试、统一记录、草稿恢复、编辑、同步读取、AI 资料核心和更新界面；`assembleDebug`、`lintDebug` 通过。
- 新测试覆盖：主动同意、无自动剪贴板读取、显式导入、独立模块保留、账号切换、旋转和 UUID 重试、晚到回调清理、超限不截断、失败重置、校对后 ASR 标记跨端同步。弹窗按钮的测试需执行主线程队列后断言；修正的是测试等待，不是移除覆盖保护。
- 曾尝试 Android 全量测试，进程以 143 中断，未得到全量结果，故本次**不声称全部 Android 测试通过**。上述范围回归是之后独立完成的最终验收。
- Windows：最终源码交叉构建通过；文字稿专项 **65 项检查通过**；更新器 **45 项检查通过**。动态依赖均为 Windows 系统 DLL，不依赖额外 MinGW 运行时。
- Windows 最终二进制的完整 workspace GUI 回归通过，包括新入口、普通与窄窗口、声明不预选、不自动读取、服务未配置时安全提示、返回后草稿保持，以及原截图、标签、长文编辑、AI 气泡、账号同步、导出和删除恢复。没有调用真实聊天模型。
- Capture Server：**95 项测试，94 项通过、1 项可选 MCP 测试跳过**；其中 24 项转写测试、6 项备份相关测试通过。覆盖凭据隔离、外账号 404、固定上游、超时和大小限制、重定向拒绝、持久配额、并发、未知提交与幂等重放。
- 转写核心及隔离浏览器回归：125 项 Python 测试和 8 项 Node 测试通过。
- 公网 HTTPS 使用两个临时测试账号和自有 CC0 语音，完整验证：账号认证、未同意拒绝、同 UUID 重提不重复创建、伪造 owner 不生效、外账号不能读或取消、完整 ASR、文字稿记录保存/读取/删除、任务删除后 404。测试凭据已撤销，自己的 QA vault 可恢复地隔离在 `/var/backups/mnote-transcript-verify.dqcleaq9`，没有读取现有用户笔记。
- 保护的服务凭据未进入暂存源码、APK 解压内容或 Windows EXE。安卓证书 SHA-256 与 1.21.1 相同：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。

测试环境是 Robolectric、Linux/Wine 和部署服务器；不能代替 Android 真机、原生 Windows 多 DPI / 输入法以及各平台实际视频的人工验收。端到端转写使用自有测试音频，不代表任意第三方视频都能解析。

## 部署、备份与撤回

只升级并重启 `heartnote-capture-api.service`；更新已安装的备份工具，使配额和幂等账本纳入 SQLite 在线快照。独立转写核心与浏览器服务未重启，SSH、Nginx 与网络配置未修改。

转写核心密钥通过 root-only 的 `/etc/heartnote-capture/server.env` 提供，权限为 0600；只增加代理所需的一项凭据，不把整个浏览器密钥环境交给同步服务。部署前完整回退点：`/var/backups/mnote-account-upgrade.l33zak`，包含原 venv、环境文件、备份工具和数据副本。回退代码和配置时保留既有业务数据与新账本，不把旧数据覆盖回线上。

更新清单备份：`/var/backups/mnote-release-1.22.0.kM3ULn/releases.json`，SHA-256 `06983dc5355bf3bbab46c21218386e424656bbcddaf6f751da6147e54956a3e1`。双端包发布到自有更新服务器，先完成不可变文件再原子替换清单。若需撤回，核对之后的发布再移除本次两条索引；不要直接覆盖后来发布的清单。撤回更新提示不会自动降级已安装设备，旧版本资产保留。

公网验证完整下载 APK、Setup、Portable 和双端 SHA256SUMS，格式、大小和 SHA-256 与本地一致，无重定向；下载页已包含新版本。清单由 27 条增至 29 条，原来的 27 条逐项保持不变。发布后五项业务服务均为 active，笔记健康检查正常。

## 安装包 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| Android APK | `3ffadfc03b09ea835049407d5663cc05a059b121d194cb7d85869bacce6b9986` |
| Windows Setup | `5963cf47c59ce79a1d4daf9634e2efa84f55b4e62659a4218d5a4cdd2c4ae99c` |
| Windows Portable | `88a5f86f7150529c0dab06d1231a14e2a2383503642122e82327e8f0b6fefc99` |
| Windows mnote.exe | `ac0c0276e91e6dbd82c7d9a6ace988bd17b8eb0a737ab947494ce84832cc0a4a` |

安卓沿用测试签名，Windows 未作 Authenticode 签名。覆盖安装前保存草稿，不要卸载已有客户端。
