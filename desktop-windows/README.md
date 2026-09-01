# Mnote for Windows 11 (V1)

Mnote 是 Mnote 的便携 Windows 客户端。它不依赖第三方运行时，常驻系统托盘；在普通桌面应用上按全局快捷键即可冻结整个虚拟桌面，框选内容、自由笔或荧光笔标注，再把原图、批注图、评论和来源信息保存到本地 Inbox。同步是可选能力，关闭或失败都不会影响本地记录成功。

## V1 能力

- 全局快捷键 `Ctrl+Shift+F9`，或双击托盘图标开始采集。
- 一次捕获完整 Windows 虚拟桌面，支持扩展屏以及负坐标排列的显示器。
- 冻结覆盖层支持矩形区域、红色自由笔、半透明黄色荧光笔、撤销、评论，以及 `thought`、`later`、`todo` 三种类型。
- 每条记录始终先在本地保存三份同 ID 文件：
  - `<id>-original.png`：框选区域的未批注原图；
  - `<id>-annotated.png`：合成笔迹后的批注图；
  - `<id>.json`：规范元数据、矢量笔迹、本地文件引用、屏幕坐标及同步状态。
- 可选用同一个 EXE 通过 WinHTTP 将元数据和两张图片同步到 Mnote Server。
- 托盘右键菜单可新建采集、重新同步最多 50 条未完成记录、打开 Inbox 或退出。
- 不录屏、不在后台持续读取屏幕；只有用户主动触发时才采集一帧。

## 使用方法

1. 运行 `mnote.exe`，确认托盘出现 Mnote 图标。
2. 在任意普通应用中按 `Ctrl+Shift+F9`。
3. 拖动鼠标框选要保留的区域。
4. 可选点击“自由笔”或“荧光笔”继续标注，填写评论并选择类型。
5. 点击“保存”。`Esc` 或“取消”会放弃本次采集，`Ctrl+Enter` 可直接保存。

本地数据默认保存在：

```text
%LOCALAPPDATA%\PersonalCapture\Inbox
```

`PersonalCapture` 是 V1 为兼容既有数据保留的本地目录名；产品和 EXE 名称均为 Mnote。

## 可选同步

把 [`settings.example.ini`](settings.example.ini) 复制为：

```text
%LOCALAPPDATA%\PersonalCapture\settings.ini
```

按实际服务配置编辑：

```ini
[sync]
server_url=http://127.0.0.1:8787
write_token=服务端单独生成的写入令牌
ai_access=local_only
```

配置规则：

- `server_url` 和 `write_token` 必须同时存在；两者都为空表示禁用同步。
- `server_url` 可以带部署路径前缀，但不能包含账号、密码、查询参数或片段。
- 明文 HTTP 只允许 `localhost` 或数字形式的回环、私有/链路本地 IP（如 `127.0.0.1`、`192.168.1.20`）；普通域名或公网地址必须用 HTTPS。
- `ai_access` 可取 `deny`、`local_only`、`remote_no_memory`、`remote_memory`，缺省为 `local_only`。只有显式选择后两项，服务端 AI 令牌才可读取该记录；非法值会按最保守的 `deny` 保存且跳过同步。
- 客户端向 `PUT /v1/captures/{id}` 发送 `application/json`，使用 `Authorization: Bearer <write_token>`。
- 重定向被禁用，避免 Authorization 头被转发到其他来源；连接、发送和接收均有短超时。
- 令牌只从 `settings.ini` 读入内存，不会写进记录 JSON、错误信息或日志。请限制该文件的 Windows ACL，不要把真实配置提交到版本库。

可在 PowerShell 中把配置收紧为仅当前账号和 `SYSTEM` 可访问：

```powershell
$settings = "$env:LOCALAPPDATA\PersonalCapture\settings.ini"
$account = "$env:USERDOMAIN\$env:USERNAME"
icacls $settings /inheritance:r
icacls $settings /grant:r "${account}:(F)" "SYSTEM:(F)"
```

执行后可用 `icacls $settings` 复核 ACL。若电脑由组织管理，请先遵循组织的管理员/备份账号策略，避免移除其必需访问项。

保存顺序固定为“原图 -> 批注图 -> 原子写入 `pending` JSON -> 尝试同步 -> 原子替换为 `synced/error` JSON”。两张 PNG 和 JSON 都先写入同目录临时文件、刷新落盘，再通过 `MoveFileEx(..., MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)` 完成；若最后一次状态替换本身失败，完整的 `pending` JSON 仍保留，不会出现半截记录。因此即使服务没启动、网络断开、证书不受信任、令牌错误或服务器拒绝请求，三份本地文件仍会保留。JSON 的状态含义：

- `disabled`：没有配置同步；
- `pending`：本地已成功，正在尝试同步；若进程意外退出，记录会安全地保留为此状态；
- `synced`：服务返回 2xx；
- `error`：配置、图片编码或网络请求失败，`sync_error` 只保存无凭据的简短原因。

恢复网络或修正配置后，在托盘图标上点右键并选择“同步待处理记录”。应用会重新处理 `pending`、`error`、`disabled` 等尚未成功的记录，每批最多 50 条；服务端按记录 ID 幂等接收，丢失响应后再次提交不会创建重复条目。

V1 每张同步图片上限 16 MiB，整个 JSON 请求上限 32 MiB；超过上限时只影响同步，不影响本地保存。

## 数据格式

记录与 Capture Server 使用同一份 `schema_version: 1` 语义。`source` 是采集时前台窗口的来源信息；`capture.selection_screen` 使用 Windows 虚拟桌面的绝对物理像素坐标；笔画点以裁剪后图片左上角为原点。示意：

```json
{
  "schema_version": 1,
  "id": "20260830-142233-127-1234-0001",
  "created_at": "2026-08-30T06:22:33.127Z",
  "kind": "thought",
  "comment": "这段内容让我想到……",
  "source": {
    "type": "screen",
    "app_name": "example.exe",
    "app_id": "C:\\Apps\\example.exe",
    "window_title": "示例窗口",
    "text": ""
  },
  "capture": {
    "virtual_screen": { "x": -1920, "y": 0, "width": 4480, "height": 1440 },
    "selection_screen": { "x": 81, "y": 114, "width": 960, "height": 540 },
    "coordinate_space": "windows_virtual_desktop_physical_pixels"
  },
  "annotations": [],
  "ai_access": "local_only",
  "local_files": {
    "original": "20260830-142233-127-1234-0001-original.png",
    "annotated": "20260830-142233-127-1234-0001-annotated.png"
  },
  "sync_state": "synced",
  "sync_error": ""
}
```

上传请求使用相同的规范字段，另含 `assets.original` 和 `assets.annotated` 的 PNG base64；本地路径和本地同步状态不会上传。Capture Server 可直接规范化和索引该请求。

## 构建

Linux 交叉构建需要 CMake 3.20+ 和 64 位 MinGW-w64 C++ 工具链：

```bash
bash build-mingw.sh
```

也可指定编译器：

```bash
MINGW_CXX=/path/to/x86_64-w64-mingw32-g++ bash build-mingw.sh
```

产物为 `build-mingw/mnote.exe`。MinGW 构建静态链接 GCC C++ 运行时；GDI+、WinHTTP 等 Windows 系统库仍由 Windows 11 提供。

Visual Studio 2022 Developer PowerShell 本机构建：

```powershell
cmake -S . -B build-vs -A x64
cmake --build build-vs --config Release
```

产物通常位于 `build-vs\Release\mnote.exe`。

### 可复现 Wine smoke tests

Linux 上另需 `wine`、`xvfb-run` 和 `xdotool`。同步测试会交叉编译一个临时控制台程序，启动一个只处理单次请求且不输出凭据的本地 mock 服务，并验证：公网 HTTP 被拒绝、缺省 `ai_access=local_only`、base64 编码、Bearer 鉴权以及 WinHTTP PUT 路径。GUI 测试则会真实启动便携 EXE，在 1280x800 虚拟桌面中触发冻结层，模拟框选、自由笔、评论及保存；它先确认服务离线时三份文件与 `error` 状态保留，再启动 mock 服务、触发托盘重试，并校验同一记录变为 `synced`，最后确认 `Esc` 取消不会新增文件。测试数据只写入一次性 Wine prefix：

```bash
bash tests/run-sync-smoke.sh
bash tests/run-gui-smoke.sh
```

成功时分别输出 `sync smoke: passed` 和 `gui smoke: passed`。同步测试默认使用 `127.0.0.1:18765`，GUI 测试默认使用 `127.0.0.1:18766`；端口冲突时可分别设置 `HEARTNOTE_SMOKE_PORT` 与 `HEARTNOTE_GUI_SMOKE_PORT`。

## 边界与安全说明

- 本程序不会也不能绕过 DRM 视频、密码保护界面、UAC 安全桌面或其他系统保护。此类区域可能变黑、为空，或无法捕获。
- GDI 桌面捕获覆盖绝大多数普通 Windows 应用；独占全屏、部分硬件覆盖层或远程桌面策略下可能不可用。
- 未框选的屏幕内容不会写入磁盘或发送到服务端。
- 来源进程路径只是采集时前台窗口的本地元数据，不保证能取得；权限边界较高的窗口可能只留下标题或空值。启用同步即表示该来源元数据也会发送到所配置服务。
- 本地数据是当前 Windows 账户下的明文文件。敏感截图应放在启用了设备加密且受保护的账户中；V1 不宣称端到端加密。
