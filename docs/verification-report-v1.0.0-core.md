# Mnote V1 Core 验证报告

- 候选版本：`v1.0.0-core`
- 验证日期：2026-09-01（UTC+8）
- 状态：自动化门禁通过；Android 与真实 Windows/浏览器人工验收待执行
- 范围基线：[V1 Core 交付契约](v1-core-release-contract.md)

## 1. 结论

本次源码可以生成已签名 Android 测试 APK、Windows 11 x64 便携 EXE、Chrome/Edge 扩展和 Capture Server/MCP。自动化已经覆盖本地记录格式、Windows 真实覆盖层交互、离线保留与重试、浏览器模块契约、服务端鉴权/搜索/删除/导出以及 AI 过滤。

这份结果只支持发布为 **V1 Core 测试候选**，不等同于所有设备验收完成。当前构建环境没有已连接的 Android 设备、真实 Windows 11、多显示器或 Chrome/Edge 图形运行环境，因此相应项目保留为“待实机验收”。

## 2. 已通过的自动化证据

### Android

- `./gradlew testDebugUnitTest assembleDebug lintDebug`：通过。
- 5 个单元测试覆盖四档 `ai_access`、最保守默认值、HTTPS/私网 HTTP 地址规则和 Token 输入边界。
- `aapt dump xmltree` 确认 APK 含截图辅助功能服务、快捷设置磁贴、透明触发页、编辑页和本地 Inbox。
- Debug 测试 APK：包名 `com.codex.mnote`，桌面名称 `Mnote`，`versionCode=1`，`versionName=1.0.0-test`，`minSdk=26`，`targetSdk=35`。
- `apksigner verify --verbose --print-certs`：APK Signature Scheme v2 验证通过，单一签名者证书 SHA-256 为 `b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。

Android SDK 在构建时提示本机 Platform-Tools 许可未被该 SDK 副本接受，但所需构建工具已存在，Debug 构建、单测和 lint 均以退出码 0 完成。当前环境未连接 Android 设备，所以没有把系统授权、磁贴、厂商后台策略或 `FLAG_SECURE` 行为标成通过。

### Windows

- MinGW-w64/CMake 交叉编译通过；制品是 `PE32+ executable (GUI) x86-64`。
- 依赖检查确认未动态依赖 `libgcc`、`libstdc++` 或 `libwinpthread`，仍只依赖 Windows 系统 DLL。
- Wine/Xvfb/xdotool GUI 烟测真实启动 EXE，并完成：触发冻结画面、720×470 框选、自由笔、评论、`Ctrl+Enter` 保存、原图与批注图 PNG 校验。
- 同一 GUI 烟测先让服务离线，确认三份文件和 `error` 状态保留；随后启动 mock 服务并触发“同步待处理记录”，确认同一 ID 变为 `synced`、上传不含本地字段；最后确认 `Esc` 取消不新增文件。
- 独立 WinHTTP 烟测覆盖：拒绝公网域名明文 HTTP、接受字面量私网/回环地址、缺省 `ai_access=local_only`、Base64、Bearer 写入鉴权和 PUT 路径。

GUI 烟测使用 Wine 9.0 的 1280×800 单屏虚拟桌面。它能证明应用自身的 UI、落盘和同步闭环可以运行，不能替代真实 Windows 11 的多屏负坐标、DPI、SmartScreen、UAC、DRM 或硬件覆盖层测试。

### Chrome / Edge 扩展

- Manifest JSON 解析和全部 JavaScript 模块语法检查通过。
- `npm test` 通过，覆盖服务地址规则、隐私默认值、记录压缩，以及选中文字/截图/跨客户端规范形状。
- 服务端跨客户端契约测试确认 Windows 与浏览器记录进入同一搜索索引，Android 旧形状无损规范化。

本机没有 Chrome、Edge 或 Chromium 可执行文件，因此扩展加载、权限提示、快捷键、浏览器内部页报错和实际 Canvas 指针交互仍待桌面浏览器人工验收。

### Capture Server、Web Inbox 与 MCP

- `python3 -m unittest discover -s tests -v`：14 个测试全部通过。
- 覆盖 SQLite/FTS、内容寻址图片、幂等重试、版本冲突、软删除/恢复/彻底删除、变更游标、ZIP 导出与输入限制。
- 覆盖所有受保护 HTTP 路由的 `401/403` scope 行为、AI 记录过滤、AI 变更流拒绝、Web Inbox 静态资源及安全响应头。
- MCP 测试确认只注册 5 个只读工具，并确认 `deny`、`local_only` 记录不会被 AI 数据层返回。
- HTTP 运行日志在测试中只输出方法与状态，没有记录搜索词、记录 ID、Authorization 或正文。

## 3. Core 验收状态

| 用例 | 当前状态 | 证据或剩余工作 |
| --- | --- | --- |
| CORE-AND-01 | 待 Android 11+ 实机 | 编译与组件清单通过；需验证磁贴触发后截到目标 App |
| CORE-AND-02 | 待 Android 11+ 实机 | 保存/解析代码和单测通过；需杀进程后人工回看双图与评论 |
| CORE-AND-03 | 待 Android 11+ 实机 | `PROCESS_TEXT` 入口已在 Manifest；需用真实编辑器验证 Unicode |
| CORE-AND-04 | 待 Android 11+ 实机 | 安全窗口错误映射已实现；不宣称绕过或成功截图 |
| CORE-WIN-01 | 部分通过 | Wine 普通桌面闭环通过；仍需真实 Windows 五类 App |
| CORE-WIN-02 | 待真实 Windows 11 | 单屏尺寸一致；双屏负坐标和混合 DPI 未验证 |
| CORE-WIN-03 | 自动化通过 | Wine 中 `Esc` 后文件数不变 |
| CORE-WEB-01 | 模块/契约通过 | 精确文字、URL、前后文、Range、矩形、正文哈希已编码；需真实浏览器交互 |
| CORE-WEB-02 | 模块/契约通过 | 双图与圈/框/线数据路径已测试；需真实浏览器交互 |
| CORE-OFFLINE-01 | Windows 通过；其余部分通过 | Windows 完整离线→重试通过；扩展队列和服务幂等通过；Android 待实机联网切换 |
| CORE-AI-01 | 自动化通过 | AI Token 的查询/单条/图片读取均在服务端按 `ai_access` 过滤 |
| CORE-AI-02 | 自动化通过 | MCP 工具数固定为 5，全部声明并实现为只读 |
| CORE-DATA-01 | 自动化通过 | 导出 ZIP、JSON 和图片哈希由服务端测试覆盖 |

## 4. 可复现命令

在仓库根目录运行：

```bash
bash scripts/verify-mnote-v1.sh
```

该命令依次执行 Android Debug 单测/构建/lint、Windows x64 构建、Windows GUI 与同步烟测、扩展测试、服务端 14 个测试和 APK 组件清单检查。交付打包脚本会在干净 Git 提交上重新执行同一门禁，再复制并验证可安装的测试签名 APK：

```bash
bash scripts/package-mnote-v1.sh v1.0.0-core
```

制品的最终提交、大小和 SHA-256 以交付包根目录的 `release-manifest.json` 与 `SHA256SUMS` 为准。

## 5. 发布后优先人工验收

1. Android 11、13、15 各一台：首次披露、辅助功能授权、磁贴、普通 App、安全窗口、重启回看和断网重试。
2. Windows 11：浏览器、Office/PDF、聊天、图片查看器、IDE；单屏/双屏负坐标、125%/150% DPI、取消和离线重试。
3. Chrome 与 Edge：开发者模式加载、选中文字、网页截图圈选、受保护页失败提示、重启后待同步箱与统一 Web Inbox。

所有待验收项都是明确的发布限制，不影响把本包交付给用户测试，但在完成前不能把它称为“全平台生产版”。
