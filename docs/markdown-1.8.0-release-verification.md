# Mnote 1.8.0-test 交付校验

发布源提交：`737967a6a39f69f92b20738242088db0446fcd99`，分支 `agent/markdown-batch-export`。稳定分支及历史发布未改写。

## 验证

- Android：454 个单元测试通过（含 Android 30 / 35 的 14 项新导出用例）；Debug APK 构建通过；lint 0 errors / 70 warnings。测试隔离进程每 12 个测试类回收 SDK 缓存，避免完整双 SDK 回归的堆内存耗尽。
- Windows：x64 静态交叉构建、83 项知识库逻辑检查、40 项更新边界检查、真实本地账号服务器的上传/拉取/编辑/删除/恢复通过。
- Windows 界面：真实圈选、批注、记录、编辑、过滤、账号同步继续通过。通过原生文件保存对话框导出 3 条记录，完整长原文保留；4 个图片链接匿名访问返回正确 PNG；在界面中撤销后全部返回 404，原记录仍在。
- Server：28 项 HTTP/数据/账号/Web/MCP 测试通过，其中 6 项针对 Markdown 快照、账号隔离、权限、容量、失败清理和公开图片访问。
- Browser：原扩展模块及清单 smoke tests 通过。
- NSIS：安装、正常退出旧进程后升级、卸载、精确 payload 和现有知识库保留通过。
- 生产 API：健康检查 200，未登录导出管理 401，未签发图片链接 404。线上不创建测试账号，不导出真实记录；数据验证使用隔离测试库。

没有实体手机或真实 Windows 设备验证；Wine 与 Robolectric 自动验证不能代替真实设备试用。Windows 未做 Authenticode 签名；Android 保留原调试/测试证书。

## 安装包身份

Android：`com.codex.mnote`，versionCode `22`，versionName `1.8.0-test`。

证书 SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`，与 1.7.0-test 一致，可直接覆盖安装，不要先卸载。

Windows：x64 `1.8.0-test`，NSIS 当前用户安装，不需要管理员权限。使用原账号和知识库目录。

| 文件 | SHA-256 |
| --- | --- |
| Mnote-Android-1.8.0-test.apk | `07053a64c2cec704f56ddd7c16165effe2f30e8b1b9d176ea1fd50872a6c95d7` |
| Mnote-Windows-1.8.0-test-Setup.exe | `066324de7c4591e190725b2110bc45f03c296ea55ff14e1446a0dabbc9b76c2c` |
| Mnote-Windows-1.8.0-test-Portable.zip | `83eefe6388005215b83a8f9a019cb258a19d7c261d3c70e700ca85dfd8cf36fb` |
| mnote.exe | `b361dc24459ef3d9ed6b042edeab30dd71a993efe5af5c9e9ee9d1983f7c4f80` |

GitHub 的资产 digest 与本地校验一致；每个平台发布均附 `SHA256SUMS`，符合旧版应用内更新器的名称和哈希校验规则。

## 服务与分享边界

已部署 Capture Server 0.3.0，先备份虚拟环境、配置及数据库，只重启 `heartnote-capture-api.service`，未修改 SSH、代理或网络规则。新增 `MNOTE_PUBLIC_BASE_URL=https://chenyu.online/heartnote-capture`。

只有明确选择并确认导出的图片产生公开能力链接；没有开放整个知识库或原资产读取接口。图片快照保留至撤销或服务不可用，撤销不能收回已下载副本。Markdown 自身为用户保存的静态文件，不受后续撤销影响。
