# 单条记录 AI 对话 · IM 气泡改版

日期：2026-10-01。实现分支 `agent/ai-chat-im-bubbles`，基于已发布的 1.20.0-test。此次仅调整客户端聊天体验，不变更模型接口、账号同步协议或历史消息格式。

## 布局与操作

- 保留暖白底色、深青强调色。自己的消息靠右，深青底白字；AI 消息靠左，浅色底深色字，细描边。角色标识位于气泡外，不仅靠颜色区分。
- 短消息按内容收窄，长消息自动换行；气泡上限为可用聊天区域的约 86%，保留另一侧留白。正文完整保留，不截断聊天内容。
- Windows 极长回复达到原生控件高度安全阈值后，自动使用带滚动条的消息内阅读区域，并显示提示；可用 Ctrl+End 到达末尾。普通回复仍随外层对话滚动，整条复制始终保留完整原文。
- Android 输入框与发送按钮同排，生成时切换为停止；新建会话移至顶部。输入框保留多行、草稿和键盘避让。
- 长按气泡显示复制/选择文字操作，不再为每条回复常驻一颗复制按钮。复制整条保留原始 Markdown；Android 在独立可滚动视图中选择文字，Windows 在原消息内选择，均不更改原消息。
- 仅最后一条停止/失败的 AI 回复提供恢复操作。Android「重试本轮」保留原问题、追加新的回复；Windows「用上一问继续…」沿用原来的重新提问方式，将上一问填回输入框，用户确认后再发送一条新问题。有草稿时先确认，不悄悄覆盖；不自动调用模型。
- Windows 另支持右键及键盘上下文菜单；Android 无障碍服务可以直接执行复制/选择动作，外接键盘支持菜单键或 Shift+F10。
- 消息按 ID 复用控件，流式追加不销毁已打开菜单。阅读旧消息时保留位置；位于底部时跟随新内容，保留返回最新的入口。

## 保持不变的边界

菜单不提供消息编辑/删除、隐式重新发送或修改笔记。原来的会话授权、固定记录快照、密钥本地保护、账号隔离和同步流程不变。文本渲染不执行 HTML，不加载 Markdown 远程图片，也不自动打开链接。

## 验证

使用合成记录与模拟消息，不访问真实模型服务，不读取用户模型密钥。

- Android 原生预览覆盖普通长回复、多轮气泡、320dp 窄屏、键盘压缩空间及 1.5 倍字号。
- 新增气泡专项测试覆盖两侧对齐/宽度/文字颜色、双方复制原文、长按不自动复制、可滚动选择、流式菜单稳定、重试范围、空回复操作禁用、键盘与无障碍入口，以及阅读旧消息时不跳转。
- 保留已有保存后聊天、模型配置、会话历史、授权取消、快照及采集界面回归。

## 1.20.1-test 验收与交付

- Android：versionCode 37；最终源码全量 46 类 / 650 项，0 失败/错误，1 项默认关闭的线上更新检查。构建通过，lint 0 errors / 127 warnings，没有新增 baseline 或关闭检查。全量阶段日志 `/tmp/mnote-im-final-verification.log`；该次 Windows 在未完成 DPI 接口期间链接失败，不能据此宣称整套通过，最终以冻结源码后的复验为准。
- Windows：最终构建通过，原生气泡组件 47 项通过，覆盖文字及气泡留白处长按、拖选取消长按、原文复制、菜单资格、流式内外滚动、100k 长文完整阅读、96→192 DPI 实际行距缩放及菜单打开期间销毁窗口。日志 `/tmp/mnote-im-build-final.log`、`/tmp/mnote-im-transcript-final.log`。
- 原生预览来自真实 Android View/Skia 与 Win32/Wine 控件，不是概念图；未声称已经在实体 Android 输入法、Windows 多屏触摸设备上全面人工验收。
- 代码回退点：Android `cfb638a`；Windows `a0023b4`。均保留原有 Git 历史，发布分支 `agent/ai-chat-im-bubbles`。
- 服务端未修改或重新部署，不迁移账号、记录、聊天或密钥；安装包只通过已有自托管更新渠道发布。

冻结源码后重新执行 `scripts/verify-mnote-v1.sh`，七阶段全部通过：`mnote-im-final-verified.service` 最终 inactive / Result=success / ExecMainStatus=0，日志 `/tmp/mnote-im-final-verified.log`。包括全应用 GUI、47 项气泡检查、135 项 Windows 库检查、45 项更新器检查、111 项聊天检查、真实本地 HTTP 集成、浏览器扩展与服务端 71 项（真实 MCP，0 跳过）。Android 最终完整 XML 与 lint 保存于 `/tmp/mnote-im-final-reports.b2wxoU/`。

Windows 安装器安装、正常退出后的升级交接、精确 payload 校验、卸载和原记录保留通过：`mnote-im-installer.service` 退出 0，日志 `/tmp/mnote-im-installer.log`。

## 自托管发布

- 双端 1.20.1-test 已发布；发布前在同一发布锁下保存更新清单至 `/var/backups/mnote-1.20.1-release.DzHmEs/releases.json`。原 22 条逐项不变，仅新增双端两条，共 24 条。旧版本文件未覆盖，未重启或修改笔记服务及其他服务。
- `verify-published-update.py --version 1.20.1-test` 通过：公网完整下载 APK、Setup、Portable 与两份 SHA256SUMS，大小/摘要均与本地验收文件一致，无重定向，固定下载页有新链接。`mnote-im-public-downloads.service` 退出 0，日志 `/tmp/mnote-im-public-downloads.log`。
- 双端真实更新传输验证通过：Android `AppUpdateLiveTest` 实际识别 1.20.1-test（1 项，0 跳过），Windows 更新器实际下载并校验 1.20.1-test 安装包，45 项检查通过。`mnote-im-live-android.service` 与 `mnote-im-live-windows.service` 均退出 0；对应日志位于 `/tmp/mnote-im-live-android.log`、`/tmp/mnote-im-live-windows.log`。这只读更新清单与公开安装包，不访问用户账号或模型。
- Android 保持旧测试证书 SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`；Windows 未作商业 Authenticode 签名。请先保存草稿，覆盖安装，不要卸载旧版。

| 制品 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Mnote-Android-1.20.1-test.apk | 23637205 | `61b6ad28b3b5d01820257dd357bb327b29dd44a0e0bfdfe9a42bdef43a567d80` |
| Mnote-Windows-1.20.1-test-Setup.exe | 1277794 | `a0df4ea7cc61f0e1b5ef439cf9aa95857c23a80b0b258379c43dcf60a66f2d88` |
| Mnote-Windows-1.20.1-test-Portable.zip | 1495123 | `3483995a4de02e3a0b2219583678122e48cd33198e044efc59fa19dafcbb2e19` |
