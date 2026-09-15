# Mnote Windows 1.6.0-test

Windows 10 / 11 x64 的原生客户端，与 Android 1.6.0 使用相同账号、记录格式和同步服务。安装版不需要管理员权限；便携版解压运行 `mnote.exe`，不需要安装其他运行时。

## 两个记录入口

- **Ctrl+Shift+F9 · 单次摘录**：冻结桌面 → 圈选 → 自由笔 / 荧光笔 / 撤销 → 下一步 → 填写想法、标签、类型、摘录和原文 → 保存。可勾选同时保留完整截图。
- **Ctrl+Shift+F8 · 随手记**：默认仅记录想法。可主动读取剪贴板当前第一条文字，或独立保留页面文字 / 页面截图；不要求先打开剪贴板摘录。
- 托盘右键有同样的两个入口；双击托盘打开知识库。关闭知识库窗口后仍在托盘运行，退出请使用托盘菜单。
- 圈选界面数字键 1 / 2 / 3 切换工具，Ctrl+Z 撤销，Ctrl+Enter 下一步，Esc 取消。记录编辑器 Ctrl+S 保存。
- 所有采集都由用户主动触发；不监听剪贴板历史、不持续截图、不模拟 Ctrl+C。

## 在哪里看、如何同步

启动应用即可看到“我的知识库”。可搜索想法、摘录、原文、来源窗口标题和标签，按标签 / 类型筛选，双击记录查看与修改；图片可切换圈选原图、批注图和完整上下文，滚轮缩放、拖动平移。

点击右上角账号，使用 **Android 上相同的用户名和密码**。默认服务器为 `https://chenyu.online/heartnote-capture`，已有账号不填写激活码。登录后自动上传和增量拉取；应用激活、每分钟和本地保存后会尝试同步。首页“刷新与同步”或 F5 可主动刷新。

同步失败不丢失本机内容，恢复网络后重试。删除移入回收站并同步到其他设备，可在回收站恢复。修订号冲突不会自动覆盖云端；本机修改会保留并提示待处理，可先复制记录 JSON 备份后核对另一台设备。

旧 `settings.ini` 的 Token 不再用于新界面。登录不会自动上传旧 Inbox 或未登录记录：需要在账号页面明确点击“导入本机旧记录”。导入保留原 ID，旧文件不删除。

## 数据与隐私

本地目录仍使用旧名称以兼容既有记录：

```text
%LOCALAPPDATA%\PersonalCapture\
  Inbox\                       旧版原始文件，保留不动
  Library\guest\               未登录时的记录
  Library\<account-scope>\     当前服务器 + 账号的独立记录与附件
  account.session               Windows 用户级 DPAPI 加密会话
  Drafts\                      编辑期间临时图片，正常保存/取消后移除
```

- 账号会话用 Windows 当前用户密钥加密；不保存密码。**记录和图片本身不是端到端加密**，磁盘访问安全由 Windows 用户权限 / 磁盘加密保障。
- 默认 AI 权限 `local_only`；账号同步不等于授权远程 AI。可在每条记录的编辑器中显式选择 AI 访问级别，扩大远程权限需确认。
- `source.text` 是摘录，`comment` 是自己的想法，`evidence.context.text.full_text` 是保留的原文；`tags` 是顶层字符串数组。
- 完整截图以 `context` 附件保存，`evidence.context.image.selection` 描述圈选区域。Windows 笔画相对裁剪图，元数据明确标记坐标系；Android 的已有坐标和其他未知元数据会原样保留。
- 页面上下文与摘录的关系标记为未验证，不能据此声称剪贴板摘录一定来自该页。
- 附件只向同源的固定角色路径下载，并验证大小、PNG 头和 SHA-256；不信任服务器提供的外站附件链接。禁止同步请求重定向。
- 正常关闭前会提醒未保存内容。意外断电或强制结束进程仍可能丢失未保存草稿；请及时保存。
- 卸载只删除程序和快捷方式，不删除笔记。备份时先退出应用，再复制整个 `PersonalCapture` 目录；加密会话不能直接迁移到其他 Windows 用户，重新登录即可。

## 能力边界

- 应用名取自前台进程，浏览器链接尝试读取 Chrome / Edge / Firefox / Brave 已知地址栏；不保证所有语言、版本或第三方应用可提供链接。支持手动修改链接；只有 HTTP(S) 链接可直接打开。
- 页面文字通过 Windows UI Automation 在独立辅助进程读取，检查窗口身份、跳过密码/隐藏节点和普通输入框，限制节点数与时间；约 4.5 秒超时结束自己的辅助进程。**不保证整篇文章完整**，遇到不支持的应用请手动粘贴或保留截图。
- 不绕过 UAC、安全桌面、受保护视频、DRM 或应用的防截图策略。
- 图片每张最多 16 MiB / 3200 万像素，请求最多 32 MiB；想法 2 万、摘录 10 万、原文 4 万 UTF-16 代码单元。标签最多 20 个，每个最多 32 个 UTF-16 代码单元；超限明确失败，不静默截断保存。
- 这是**未做 Authenticode 签名的测试版**，Windows 可能提示来源未知。仅从项目 GitHub Release 下载并核对 SHA-256。
- 已进行 Linux / Wine 自动化；尚未替代真实 Windows 10 / 11、多屏高 DPI、浏览器 UIA 和系统通知的人工验收。

## 构建与测试

```bash
bash desktop-windows/build-mingw.sh
bash desktop-windows/tests/run-library-tests.sh
bash desktop-windows/tests/run-library-live.sh
bash desktop-windows/tests/run-workspace-gui.sh
bash desktop-windows/tests/run-sync-smoke.sh
bash scripts/verify-mnote-v1.sh
# 安装 NSIS 后，或设置 MAKENSIS / NSISDIR
bash scripts/package-mnote-windows.sh
```

构建使用 MinGW-w64 / CMake，下载并校验固定的 nlohmann/json 3.11.3 头文件；无第三方运行时依赖。测试在独立 Wine 前缀和临时本地 Capture Server 运行，不写入线上服务。界面回归覆盖真实圈选/笔迹、编辑、即时刷新、标签筛选、删除恢复、剪贴板、独立截图上下文、账号激活及显式导入。Wine 下页面文字读取允许安全失败，不等同于真实浏览器兼容性验证。

第三方声明见 `THIRD-PARTY-NOTICES.txt`。
