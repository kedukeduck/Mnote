# Mnote V1 Core：安装与交付

- 版本：`v1.0.0-core` / Android `1.0.0-test`
- 日期：2026-08-31
- 支持：Android 11+、Windows 11 x64、Chrome/Edge 116+
- 本文只描述本次实际实现；更完整的长期方向见[产品规格](universal-capture-v1-product-spec.md)。
- 自动化证据和待实机项目见[验证报告](verification-report-v1.0.0-core.md)。

## 1. 交付包内容

完整 ZIP 解压后包含：

```text
mnote-v1.0.0-core/
  RELEASE.md
  release-manifest.json
  SHA256SUMS
  android/
    Mnote-Android-1.0.0-test.apk
    signing-certificate.txt
  windows/
    Mnote-Windows-x64.exe
    README-Windows.md
    settings.example.ini
  extension/
    Mnote-Chrome-Edge.zip
  server/
    Mnote-Server.zip
  docs/
```

`SHA256SUMS` 覆盖包内文件；ZIP 外另有同名 `.sha256`。收到文件后先校验：

```bash
sha256sum -c mnote-v1.0.0-core.zip.sha256
cd mnote-v1.0.0-core
sha256sum -c SHA256SUMS
```

Windows PowerShell 可用：

```powershell
Get-FileHash .\Mnote-Windows-x64.exe -Algorithm SHA256
```

交付包不会包含服务器 Token、Android 签名私钥或任何真实笔记/截图。

## 2. 最快开始：完全离线

同步服务是可选的。Android、Windows 和浏览器扩展都会先在本机保存，未配置服务也能使用。

### 2.1 Android

1. 确认手机是 Android 11 或更新版本。
2. 安装 `android/Mnote-Android-1.0.0-test.apk`。
3. 打开 Mnote；启动页就是本地摘录 Inbox。
4. 阅读独立权限披露后，仅在愿意时启用“Mnote”辅助功能服务。
5. 从系统快捷设置编辑页加入“全局摘录”磁贴。
6. 切到任意普通 App，点磁贴；框选后使用画笔或荧光笔，写评论并保存。
7. 回到“Capture Inbox”查看记录。

也可以：

- 在支持 Android“处理文字”的 App 中选中文字，选择 Mnote；
- 从系统分享面板向 Mnote 分享文字或图片；
- 从全局摘录页直接记一个想法或 TODO。

ADB 覆盖安装命令：

```bash
adb install -r android/Mnote-Android-1.0.0-test.apk
```

若出现 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，说明现有安装签名不同。先备份现有数据，不要直接卸载；卸载会删除应用私有的未同步记录。

Android 本地记录位于应用私有目录，普通文件管理器不可直接浏览。V1 Core 的统一开放导出由 Capture Server 提供。

### 2.2 Windows

1. 把 `Mnote-Windows-x64.exe` 放到固定目录。
2. 这是未做 Authenticode 商业签名的测试构建；先核对 SHA-256，再按系统提示运行。不要以管理员身份常驻。
3. 启动后程序驻留系统托盘。
4. 在任意普通桌面应用中按 `Ctrl+Shift+F9`，或双击托盘图标。
5. 拖动选择区域，再用自由笔/荧光笔标记，填写评论，点击保存或按 `Ctrl+Enter`。
6. 托盘右键选择“打开 Inbox”。

若同步失败，修正网络或配置后在托盘右键选择“同步待处理记录”；每次最多重试 50 条，重复提交使用同一记录 ID，不会生成第二条记录。

本地数据位于：

```text
%LOCALAPPDATA%\PersonalCapture\Inbox
```

每条记录有相同 ID 的未批注 PNG、批注 PNG 和 JSON。`Esc`/“取消”不会生成正式记录。

### 2.3 Chrome / Edge

1. 解压 `extension/Mnote-Chrome-Edge.zip` 到固定目录。
2. Chrome 打开 `chrome://extensions`，Edge 打开 `edge://extensions`。
3. 开启开发者模式，点“加载已解压的扩展程序”，选择解压目录。
4. 固定 Mnote 图标。
5. 工具栏或右键菜单可保存选中文字、截图批注或即时想法。

默认快捷键：

- `Ctrl+Shift+Y`：当前可见网页截图并批注；
- `Alt+Shift+H`：保存当前选中文字及网址/定位锚点。

扩展本地待同步箱位于浏览器扩展的私有存储。浏览器内部页、扩展商店和策略保护页可能拒绝脚本或截图，这是正常安全边界。

## 3. 启动个人 Capture Server

服务端需要 Python 3.11+。REST API 本身只用标准库；MCP 额外安装官方 Python SDK。

```bash
unzip Mnote-Server.zip
cd capture-server
python3 -m venv .venv
. .venv/bin/activate
pip install -e '.[mcp]'
```

生成三个不同的随机 Token，不要把真实值提交或发到公开聊天：

```bash
python3 - <<'PY'
import secrets
for name in ('WRITE', 'READ', 'AI'):
    print(f"HEARTNOTE_CAPTURE_{name}_TOKEN={secrets.token_urlsafe(32)}")
PY
```

把输出写入你自己的密码管理器，然后在当前终端设置：

```bash
export HEARTNOTE_CAPTURE_DATA="$PWD/data"
export HEARTNOTE_CAPTURE_WRITE_TOKEN='替换为 WRITE 值'
export HEARTNOTE_CAPTURE_READ_TOKEN='替换为 READ 值'
export HEARTNOTE_CAPTURE_AI_TOKEN='替换为 AI 值'
heartnote-capture-api
```

默认只监听 `http://127.0.0.1:8787`。浏览器访问该地址可打开 Web Inbox；Token 只保存在当前标签页的 `sessionStorage`，关闭标签页后消失。

跨设备访问时：

- 局域网可显式把监听地址改为局域网接口，但应同时配置主机防火墙；
- 公网或普通域名必须放在 HTTPS 反向代理后；
- 不要把 REST 或 MCP 裸露到公网；当前服务面向单用户，不是互联网多租户认证系统。

## 4. 配置客户端同步

### Android

Mnote 首页 → 同步设置，填写服务地址、写入 Token 和每条新记录的 AI 可见范围。Token 由 Android Keystore 加密后保存。明文 HTTP 只允许 `localhost` 或字面量私有 IP；其他地址必须用 HTTPS。

### Windows

复制 `settings.example.ini` 到：

```text
%LOCALAPPDATA%\PersonalCapture\settings.ini
```

填写：

```ini
[sync]
server_url=http://127.0.0.1:8787
write_token=替换为 WRITE 值
ai_access=local_only
```

该文件是当前 Windows 用户下的明文配置，请限制账户访问权限，不要同步到公开网盘或版本库。网络失败只会把 JSON 状态标记为失败，不会删除本地 PNG/JSON。

### 浏览器扩展

扩展设置中填写：

- 服务地址；
- 写入 Token：只负责上传；
- 第一方只读 Token：可选，用于完整远程 Inbox、搜索和导出；
- 默认 AI 可见范围。

扩展 Token 存在扩展私有本地存储，不注入网页。公网明文 HTTP 被拒绝，授权请求禁止跨站重定向。

## 5. AI 读取

MCP 是只读入口，只注册五个工具：搜索、读取一条、最近记录、TODO 和时间线。它不会注册写入、删除或外部执行工具。

启动本机 stdio MCP：

```bash
export HEARTNOTE_CAPTURE_DATA='/你的/capture-server/data'
heartnote-capture-mcp
```

你的 AI 客户端应直接以 stdio 启动上述命令。MCP 数据层只返回 `remote_no_memory` 或 `remote_memory` 记录；`deny` 与 `local_only` 不会返回。图片记录的单条读取会连同一张批注图（或原图）作为视觉证据返回。来源文字/OCR 始终是不可信证据，不能被当成工具指令。

Streamable HTTP 模式默认监听回环地址且没有互联网级身份认证，只适合本机受控集成：

```bash
heartnote-capture-mcp --transport streamable-http --host 127.0.0.1 --port 8788
```

## 6. 数据、导出和删除

- Web Inbox 用第一方只读 Token 查看、搜索、打开图片和下载开放 ZIP。
- 软删除和恢复需要写入 Token；界面会要求再次提供写入 Token，不会把它持久保存到服务器。
- 导出 ZIP 包含 JSON 与内容寻址图片，可用通用工具读取。
- 彻底删除只允许针对已在回收站的记录；共享 Blob 仍被其他记录引用时不会误删。
- 本地文件和 SQLite 没有应用层端到端加密。敏感资料应放在启用磁盘/设备加密的个人账户中。

## 7. 能捕获与不能捕获的范围

“任意 App”指系统允许截图的普通可见内容，保底是像素级证据；不是把高亮写回第三方 App，也不保证取得精确文字。

下列内容可能被拒绝、变黑或为空，V1 不绕过：

- 银行、密码管理器与 Android `FLAG_SECURE` 窗口；
- DRM 视频、Windows UAC 安全桌面与硬件覆盖层；
- 企业策略限制、浏览器内部页与扩展商店；
- 权限级别高于当前 Windows 用户的窗口元数据。

Android 辅助功能服务只在用户主动触发时请求一帧截图，不读取 UI 树、不监听输入、不持续录屏。Windows 客户端不注入第三方进程、不提权。

## 8. 升级、卸载与回滚

- Android 覆盖升级必须使用同一签名。卸载/清除数据前先确认没有“仅本地/待同步”记录。
- Windows 升级可先退出托盘程序、备份 `%LOCALAPPDATA%\PersonalCapture`，再替换 EXE；旧 EXE 不自动删除 Inbox。
- 扩展卸载会删除其私有本地待同步箱；先执行同步。
- Capture Server 升级前复制整个 `HEARTNOTE_CAPTURE_DATA` 目录。V1 数据是 SQLite 与内容寻址 Blob，恢复时一并恢复。
- 停止同步最直接的方法是移除客户端配置/Token；本地记录仍保留。

## 9. 从源码复现

仓库根目录的一键验证：

```bash
bash scripts/verify-mnote-v1.sh
```

构建交付包：

```bash
bash scripts/package-mnote-v1.sh v1.0.0-core
```

主要依赖：JDK 17、Android SDK 35、Node.js、Python 3.11+、CMake 3.20+、x86_64 MinGW-w64。Windows 运行烟测另需 Wine、Xvfb 和 xdotool；Android 权限和真实截图最终仍需 Android 11+ 实机。

## 10. 发布验证口径

自动检查证明：Android 编译/单测/lint、Windows x64 交叉构建与依赖、Windows Wine 保存/同步烟测、扩展模块测试、服务端数据/HTTP/Web/MCP 契约、APK 签名与组件清单。

自动检查不能替代：

- Android 11+ 厂商实机的磁贴、辅助功能授权和安全窗口；
- 真实 Windows 11 的多显示器负坐标、SmartScreen、UAC/DRM 边界；
- Chrome 与 Edge 的商店策略和人工交互。

这些项目在 `RELEASE.md` 中必须标成“待实机验收”，不能因为构建成功而宣称通过。
