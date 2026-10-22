# Mnote Server V1

Android 1.2.0 / Server 0.2.1 增加可选完整截图、原文与选区映射；MCP 按角色返回上下文图和选区图，沿用 AI 访问控制。详见 [上下文摘录契约](../docs/android-1.2.0-context-excerpts.md)。

Android 1.1.0 起支持邀请制用户名 / 密码账号、账号隔离、自动双向同步和离线删除；首次激活保留原知识库。部署、API 和兼容边界见 [账号同步说明](../docs/android-1.1.0-account-sync.md)。下文三个静态 Token 仍用于旧客户端和 AI 集成。

个人知识库 V1 的本地优先同步、搜索、导出和只读 MCP 服务。REST API 只依赖 Python 标准库；MCP 入口使用官方 Python SDK 2.x。

## 安全边界

- 写入、第一方读取、AI 读取使用三个不同的 Bearer Token；Write Token 不能枚举或下载知识库。
- AI Token 只能调用读取端点，且服务端会在检索前过滤 `deny` 与 `local_only` 记录。
- AI Token 不能读取包含删除墓碑的 `/v1/changes`，防止已清除私密记录的 ID 泄漏。
- 未显式设置 `ai_access` 的记录默认是 `local_only`，不会因字段缺失而远程可读。
- MCP 只注册五个只读工具，没有写入、编辑、删除或外部执行工具。
- AI 搜索与读取写入最小审计记录，只保存查询、记录 ID、时间与调用方，不复制正文。
- 图片使用 SHA-256 内容寻址；删除记录后只有无其他引用的 Blob 才会被物理清理。
- 捕获正文、OCR 与网页内容始终是不可信数据，不能改变工具权限。
- 缺失或未知 Token 返回 `401`；服务已识别但 scope 不匹配的 Token 返回 `403`。访问日志仅保留 HTTP 方法与状态，不记录搜索词、记录 ID 或令牌。

该服务的 Bearer Token 适合个人服务器和受 TLS 保护的反向代理；直接暴露公网前应改为 OAuth 2.1，并限制请求大小和速率。

## 本地运行

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -e '.[mcp]'

export HEARTNOTE_CAPTURE_DATA="$PWD/data"
export HEARTNOTE_CAPTURE_WRITE_TOKEN='replace-with-random-write-token'
export HEARTNOTE_CAPTURE_READ_TOKEN='replace-with-random-read-token'
export HEARTNOTE_CAPTURE_AI_TOKEN='replace-with-random-ai-token'

heartnote-capture-api
```

默认 REST 地址为 `http://127.0.0.1:8787`。健康检查不要求认证：

```bash
curl http://127.0.0.1:8787/health
```

浏览器打开 `http://127.0.0.1:8787/` 即可使用 Web Inbox。页面支持列表、搜索、鉴权加载截图、开放格式导出、软删除和恢复。Read Token 必填，Write Token 仅在需要删除/恢复时选填；二者分别只写入当前标签页的 `sessionStorage`。页面的所有 GET 固定使用 Read Token，写操作固定使用 Write Token，不会让上传凭证兼任知识库读取凭证。

Web Inbox 的静态资源和 API 请求支持反向代理子路径。例如把 Nginx 配置在
`/heartnote-capture/` 后，客户端 Base URL 使用
`https://example.com/heartnote-capture`，浏览器则打开末尾带斜杠的地址。

运行本地 stdio MCP：

```bash
heartnote-capture-mcp
```

运行 Streamable HTTP MCP：

```bash
heartnote-capture-mcp --transport streamable-http --host 127.0.0.1 --port 8788
```

## REST API

| 方法 | 路径 | 凭证 | 用途 |
| --- | --- | --- | --- |
| `GET` | `/health` | 无 | 存活检查 |
| `PUT` | `/v1/captures/{id}` | 写 | 创建或带 `base_revision` 更新 |
| `GET` | `/v1/captures` | 读/AI | 分页列表；AI 自动过滤 |
| `GET` | `/v1/captures/{id}` | 读/AI | 读取记录 |
| `GET` | `/v1/captures/{id}/assets/{role}` | 读/AI | 读取原图或批注图 |
| `GET` | `/v1/search?q=...` | 读/AI | 全文搜索 |
| `GET` | `/v1/changes?after=0` | 读 | 增量同步游标；AI Token 明确拒绝 |
| `DELETE` | `/v1/captures/{id}` | 写 | 使用 `If-Match: "revision:N"` 移入回收站 |
| `POST` | `/v1/captures/{id}/restore` | 写 | 带 `base_revision` 恢复 |
| `POST` | `/v1/captures/{id}/purge` | 写 | 仅已删除记录可彻底清除 |
| `GET` | `/v1/export` | 读 | ZIP 开放格式导出 |

图片通过 `assets.<role>.data_base64` 上传。服务端返回资产哈希和只读 URL，不在列表中回传 Base64。

## MCP 工具

- `search_captures`
- `get_capture`
- `list_recent`
- `list_todos`
- `read_timeline`

所有工具声明 `read_only_hint=True` 和 `open_world_hint=False`。这些声明是客户端提示；真正的安全边界是服务进程完全没有注册写工具，且数据层始终执行 `ai_access` 过滤。

## 持久化部署

`deploy/` 提供以下可复用配置：

- `heartnote-capture-api.service`：以独立非登录用户运行 API，只允许写入 `/var/lib/heartnote-capture`。
- `nginx-heartnote-capture.conf`：在 HTTPS 站点的 `/heartnote-capture/` 下反向代理，且不记录包含搜索词或记录 ID 的请求路径。
- `heartnote-capture-backup.service` 与 `.timer`：每天使用 SQLite 在线备份 API 备份账号、所有 vault 记录/聊天库、内容寻址图片和活跃历史分享；默认保留 14 天。备份复制期间短暂保留数据库写锁以保护资料一致性，普通读取仍可用；压缩在释放写锁后进行。
- `heartnote-capture-mcp.service`：可选的只读 MCP，只监听 `127.0.0.1:8788`，不应直接暴露公网。

生产环境令牌放在 root-only 的 `/etc/heartnote-capture/server.env`，不要写入仓库或客户端安装包。API 本身只监听 `127.0.0.1:8787`，公网入口必须经过有效 TLS 证书的反向代理。

## 历史分享图文（账号接口）

- `GET /v1/exports`：当前账号未撤销的历史分享列表。0.5.0 起包含 `text_available` 和有界 `text_preview`，可直接显示文字摘要。
- `GET /v1/exports/{id}`：当次导出的图片清单，含 `images[].name / role / record_index / content_type / size`。
- `GET /v1/exports/{id}/assets/{name}`：当次导出保存的图片字节，支持 HEAD，`Cache-Control: no-store`。
- `GET /v1/exports/{id}/text`：0.5.0 新增，当次导出的完整文字快照，返回 `{ "text": "..." }`，限所属账号、支持 HEAD、禁止缓存。缺失文字快照的旧分享返回 404。
- `DELETE /v1/exports/{id}`：撤销分享并清理该次导出的图片及文字副本，原始记录保留。

文字快照保存在分享目录的私有 `text.json`，摘要在 `preview.json`；两者均不在公开图片资产清单中，无法通过 `/s/` 或图片接口读取。旧分享没有保存文字，不能从当前记录准确回填；列表会明确标记不可用。新增文件不改变数据库表结构，单次全文 JSON 最大 8 MiB，超限整次导出失败。

这些接口只接受所属账号的 Bearer 会话，不接受旧 read / write / AI token，也不返回公开分享 token。读取的是独立导出快照，即使原记录删除或修改也不受影响。旧分享的图片无需迁移或重新导出即可查看，但文字需新导出才会保存；已撤销分享返回 404，不能恢复预览。查看不会创建新的分享或延长链接有效期。

## 记录 AI 对话（仅账号私有同步）

- `GET /v1/chat/changes?after=0&limit=100` 返回独立的聊天变更序列、`next_cursor` 和 `has_more`。
- `GET /v1/chat/conversations/{uuid}` 读取所属账号会话及 `revision`。
- `PUT /v1/chat/conversations/{uuid}` 使用 `If-Match: "revision:N"` 创建或更新；首次为 `0`。等价重试幂等，冲突返回 `409`，不执行最后写入覆盖。
- `DELETE /v1/chat/conversations/{uuid}` 使用相同修订条件删除，保留同步墓碑但清除正文、资料和图片副本。

只接受登录账号的 Bearer 会话，包括接管旧记录库的个人账号。旧 read/write/AI token、MCP、公开分享和普通记录 ZIP 导出均不能读取聊天。账号的 `chat.sqlite3` 与 `captures.sqlite3` 分开存放，API Key、配置密钥、provider 原始错误体与本机草稿不属于聊天协议字段。服务端不请求模型；客户端直接访问用户配置的服务。

每个会话保存不可变的已选资料快照；已结束消息不可覆盖。客户端生成前用 CAS 保存 user 消息和末尾 assistant `generating` 占位，取得 5 分钟可续租锁。同一 request 可保存部分或最终回复；新 request 在锁有效时不能追加，过期后必须保留旧消息并标为中断。失败重试可以只追加新的 assistant request，不需要重复用户问题。

正文限 8 MiB、最多 500 条消息、单条最多 100,000 字符。图片以受限的 PNG/JPEG/WebP 数据提供，最多 4 张、每张 3 MiB、最长边 4096 像素且总像素不超过 4,194,304；容器和尺寸验证不会代替客户端健壮的图片解码。未选模块不允许夹带正文。所有读取和错误均不缓存。

删除父记录时，其删除事件先在原记录库事务中持久提交，作为聊天清理的 durable outbox，再独立事务清理聊天。SQLite ATTACH 的 WAL 模式不保证跨库断电原子性，因此不能依赖一次跨库提交。正常删除响应等待清理；若聊天数据库暂时不可写，父记录删除仍返回成功并记录无内容告警。所有聊天读、写和变更流在响应前必须重放清理；清理不可用返回 `503` 而不是旧内容。启动时也执行恢复。父记录恢复不恢复旧聊天，墓碑 ID 不能复活。现有记录变更历史应保留，不能在未确认聊天清理水位时裁剪删除事件。

新版 `deploy/heartnote-capture-backup` 是独立 Python 3.11+ 标准库脚本，不依赖服务 venv、sqlite3 CLI 或 rsync。沿用原环境变量和 systemd 入口，输出私有压缩包、SHA-256 和完整恢复说明。旧版仅打包 legacy `captures.sqlite3` 的已安装脚本不会随 pip 升级自动改变；发布时需另外替换 `/usr/local/sbin/heartnote-capture-backup`，保留可执行权限。安装包更新目录与 `/etc` 服务凭证不属于数据备份，恢复时应保留它们。

备份必须整套恢复账号资料、全部账号 vault 的记录库、聊天库和图片，不能把不同时间的记录库与聊天库混用，也不能覆盖到仍存在旧 WAL 的目录。恢复账号会话可能包含备份后被撤销的凭证，开放服务前应作废恢复出的会话并审核旧公开分享。服务启动先执行 outbox 重放，再对外提供聊天。聊天数据库故障不会阻止普通记录/旧客户端读取；聊天 API 会拒绝读取直到恢复。

## 视频文字稿（0.11.0，仅登录账号）

Mnote 摘录可复用 `../transcription-service/` 的公开媒体解析与本地转写能力。同步服务负责账号授权、任务代理、配额、幂等及后台归档，不解析视频、不运行浏览器或模型。原有匿名网页仍由独立网关服务提供。保存记录无需等待转写；用户是否解析音频由客户端明确选择，服务端不会因为只保存了一个链接而自动提交。

| 方法 | 路径 | 响应 |
| --- | --- | --- |
| `GET` | `/v1/transcripts/config` | `200 {ready,configured,agreement_version,max_duration_seconds,retention_hours,message}` |
| `POST` | `/v1/transcripts/jobs` | `202 {job}` |
| `GET` | `/v1/transcripts/jobs?limit=50&offset=0` | `200 {jobs,has_more,next_offset}`，所属账号历史摘要，不含正文/segments |
| `GET` | `/v1/transcripts/jobs/{uuid}` | `200 {job}`，仅所属账号 |
| `PATCH` | `/v1/transcripts/jobs/{uuid}` | `200 {job}`，仅关联 `{capture_id}`，不修改记录 |
| `POST` | `/v1/transcripts/jobs/{uuid}/capture` | 同上，兼容不支持 PATCH 的原生 HTTP 客户端 |
| `DELETE` | `/v1/transcripts/jobs/{uuid}` | `200 {deleted:true,id}`，取消核心任务并删除本账号历史/结果 |

所有接口只接受当前有效的账号 Bearer 会话；旧 read/write/AI token 均返回 `403`，缺失/过期会话返回 `401`。接口错误统一为 `{ "error": { "code": "...", "message": "..." } }`，不影响普通同步接口原来的错误格式。

提交示例（授权依据必须由用户真实选择、填写，不可默认勾选）：

```json
{
  "share_text": "用户粘贴的 HTTPS 分享链接或分享文案",
  "rights_basis": "authorized",
  "rights_note": "实际获得授权的说明，由用户填写",
  "agreement_version": "2026-10-12-v1",
  "consent": true,
  "client_request_id": "2a90c0a5-1fb6-429b-82df-92d029877052"
}
```

`rights_basis` 为 `owned`、`authorized`、`open_license` 之一；当前协议版本应从 config 获取，不能仅依赖示例常量。用户声明不等于服务已验证授权，返回的 `rights.verified` 为 `false`。链接、正文和权属说明只发送到本机转写核心，不记录到同步服务访问日志。

`job` 包含 `id/status/stage/progress/text/segments/source/rights/created_at/updated_at/expires_at/error`；`status` 为 `queued/processing/completed/failed`，进度 `0..100`，失败详情为 `job.error={code,message}`。新增元数据为 `client_request_id/capture_id/share_text/source_url`，其中 `source_url` 只来自用户提交的原始公开链接，不是解析出的媒体地址。时间字段沿用 Unix 秒。列表摘要省略 `text/segments`，增加 `has_text`；分页 `limit` 为 `1..50`、`offset` 为 `0..5000`，`next_offset:null` 表示末页。

POST 可选 `capture_id`（`[A-Za-z0-9][A-Za-z0-9._-]{0,127}`，拒绝 `..`）；记录仍在本地未同步时也可关联。之后可用 PATCH/POST capture 关联保存好的记录，不改变原提交幂等指纹。此关联是惰性元数据，不会读取该记录、授予别的账号权限或自动覆盖摘录。客户端取得完整结果后由用户确认导入。删除任务不会删除已经导入记录的文字。

HTTP 服务启动一个后台收集线程，每 15 秒检查已被核心接收的任务，暂时故障按 15–300 秒退避；重启后继续，不要求 App 保持在前台。成功或失败结果持久保存到本账号历史，客户端可据此通知用户。核心的 24 小时保留期只针对临时任务：已归档结果不随其过期；未归档任务确定过期时显示 `failed/task_expired`。每份结果限制 100000 个字符和 1 MiB，超限显示 `failed/result_too_large`，不会悄悄截短正文。完成/失败状态不会被迟到的旧轮询响应覆盖。未配置核心时已有归档仍可读取、删除。升级前仍有效的已接收任务可迁入，但已从旧核心过期清理的结果无法补回。

可选 `client_request_id` 必须为小写标准 UUID。相同账号、相同 ID 和同一请求内容重复提交时返回原任务（仍为 `202`，已归档结果可超过核心 24 小时保留期），不重复解析、不再消耗配额；内容改变返回 `409 request_conflict`。提交结果不确定时返回 `429 submission_uncertain`，收集线程也不会自动重新 POST。原任务已删除或从未确认接收且已过期时返回 `404`，同一个 ID 不会重新创建任务。用户编辑链接/声明后重新开始时应生成新 ID。只有核心明确以 `4xx` 拒绝、确认没有接收的提交可退还名额并释放该 ID。

账号隔离使用服务器根据账号 ID 派生的独立 SHA-256 owner，拒绝客户端 `X-Owner-Id`/cookie/upstream 等覆盖。代理只访问固定 `http://127.0.0.1:8791/v1/...`，不跟随重定向，绝不把账号令牌发送给核心；核心专用 Token 不下发客户端。JSON 请求限制 16 KiB、响应限制 8 MiB、核心请求总期限 25 秒；只转发固定响应字段，不暴露媒体签名 URL、私有 URL、模型配置或 Token。

原生 App 入口与匿名网页入口的配额分别计算：每账号同时一个进行中的任务、每小时最多 3 次、全部账号合计每 UTC 日最多 40 次；`429` 响应带 `Retry-After`。已被接收的任务无论后续失败还是删除均计数；网络结果不明/进程中断的预留也不自动退还，24 小时后解除占用。私有 `transcripts/admissions.sqlite3` 的原配额/幂等表仍只保存派生 owner、任务 ID、时间及内容哈希；新增同库 `catalog` 保存原始分享链接、用户声明、关联 ID 和解析结果，不保存音视频、核心私有媒体地址或凭证。目录 0700、数据库 0600，已有备份工具同一份 SQLite 快照包含两部分。

历史不会静默清除：每账号最多 5000 条、100 MiB，全部账号历史合计 512 MiB；提交前预留在途结果空间，达到容量返回 `409 history_full`，用户删除不需要的任务后可继续。删除立即隐藏并清除归档正文、链接和声明；核心暂不可用时保留不含内容的清理标识并后台重试，最小幂等墓碑仍保留，不能通过删除绕过频次配额。数据库物理空间和私有备份可能仍包含删除前快照，备份按自身保留期过期。

部署时，先按转写服务文档启动本机核心；仅在 root-only 的 `/etc/heartnote-capture/server.env` 加入 `HEARTNOTE_CAPTURE_TRANSCRIPT_TOKEN`，值为核心专用 Token。不要把整个核心 EnvironmentFile 引入同步服务，也不要将密钥放进仓库、APK 或 Windows 程序。无需修改 Nginx 路径或开放新端口。同步服务重启后，通过登录会话检查 `/v1/transcripts/config` 的 `ready/configured`。缺失/格式无效的可选 Token 令 config 返回 `ready:false,configured:false`，普通同步仍可启动；访问文字稿任务返回 `503 not_configured`。核心暂不可用返回 `503 upstream_unavailable`，不会透传内部认证错误或诊断。

回滚可恢复旧版同步服务；保留上述 ledger，不要手动清空配额或幂等数据。数据恢复需和账号库一起恢复 ledger；转写核心自己的临时任务/模型和服务密钥不在本服务备份内。

## 测试

不安装 MCP 依赖时，数据层和 HTTP 测试仍可运行：

```bash
PYTHONPATH=src python3 -m unittest discover -s tests -v
```

安装 MCP extra 后，同一命令还会验证工具列表、只读声明和 AI 过滤。
