# 全局摘录完整目标同步、搜索与 AI 只读 API

- 文档状态：后续完整协议设计；不是 V1 Core 当前 REST 路由声明
- 更新日期：2026-08-31
- API 前缀：`/api/v1`
- 传输：HTTPS + UTF-8 JSON；附件单独传输
- 相关文档：[记录结构](capture-record-schema.md)、[权限与隐私](permissions-privacy.md)、[验收清单](acceptance-checklist.md)

> 本文定义目标接口，不表示当前仓库已有可部署同步服务。任何“已同步”“AI 只读已生效”或“删除已传播”的发布结论，都必须由运行中的服务、契约测试和多设备验收证据支持。

V1 Core 当前已实现的精简 REST `/v1/*` 与 MCP 契约以 [`capture-server/README.md`](../capture-server/README.md) 和 [V1 Core 交付契约](v1-core-release-contract.md) 为准；本文的 `/api/v1/*`、分块续传、设备游标和任务对象留作兼容演进目标。

## 1. 协议目标

1. Android、Windows 和 Web 客户端离线创建记录后可以幂等补传。
2. 记录元数据与大附件分离；附件支持校验、续传与安全去重。
3. 并发编辑产生显式冲突，不使用静默的最后写入者覆盖。
4. 删除通过墓碑传播，断网旧设备不能把已删除记录“复活”。
5. AI 使用独立只读凭证，服务端从授权层拒绝一切写操作。
6. 搜索结果保留字段来源、证据等级和可打开的记录引用。

## 2. 服务组成

```text
第一方客户端
  |- 同步 API -------- 记录、任务、修订、墓碑
  |- 附件 API -------- 分块上传、校验、下载
  |- 搜索 API -------- 关键词与筛选
  |- 审计/导出 API --- 用户可见操作
  `- 配对/令牌服务 --- 设备身份与范围

AI 客户端
  `- AI 只读网关 ---- 独立路由、独立令牌、字段过滤与审计
```

服务端数据库、对象存储、全文索引和向量索引均是同一删除与授权域的不同投影，不能把索引当成可永久保留的匿名副本。

## 3. 通用约定

### 3.1 请求头

| 请求头 | 使用场景 | 规则 |
| --- | --- | --- |
| `Authorization: Bearer <token>` | 所有非健康检查接口 | 不得出现在查询参数或日志 |
| `Content-Type: application/json` | JSON 请求 | UTF-8；拒绝重复键和无效 JSON |
| `X-Device-Id` | 第一方设备同步 | 与令牌绑定；客户端不得替他人声明设备 |
| `Idempotency-Key` | 创建上传会话、提交写批次、导出 | UUID/ULID；同一键不同请求体返回冲突 |
| `If-Match` | 单对象条件更新 | 值为服务端 ETag；不匹配返回 409 |
| `X-Request-Id` | 可选链路追踪 | 服务端验证长度并回传；不含个人信息 |

服务端响应必须包含自己的 `X-Request-Id`、`Date` 和明确的 `Content-Type`。时间均为 UTC RFC 3339；游标是不透明字符串，客户端不能解析。

### 3.2 内容类型与版本

- 记录交换模型为 [`capture-record.v1`](capture-record-schema.md)。
- 任务交换模型为 `task.v1`。
- 事件信封为 `sync-event.v1`。
- 服务端只在能力发现中列出的版本上接受写入。
- 不认识的必需主版本返回 `unsupported_schema`，不得尝试部分保存。

### 3.3 ID 与幂等

- `record_id`、`task_id`、`operation_id` 和上传会话 ID 全局稳定。
- 一个设备重试同一 `operation_id` 时，服务端返回第一次提交的结果，不创建新修订。
- `Idempotency-Key` 的结果至少保留 24 小时；准确窗口由能力发现返回。
- 客户端必须把 operation ID 与本地事务一起落盘，不能在每次网络重试时重新生成。

## 4. 身份、令牌与范围

### 4.1 第一方范围

建议范围：

- `records:read`
- `records:write`
- `attachments:read`
- `attachments:write`
- `search:read`
- `tasks:read`
- `tasks:write`
- `audit:read`
- `export:create`
- `devices:manage`

设备令牌限定到一个用户空间与一个设备 ID。撤销设备后，令牌立即失效；服务器保留该设备的同步确认状态，直到用户决定清除或重新配对。

### 4.2 AI 只读范围

AI 凭证只允许：

- `ai:search`
- `ai:records:read`
- `ai:thoughts:read`
- `ai:tasks:read`
- `ai:memories:read_confirmed`
- `ai:timeline:read`

AI 令牌请求任一 `POST`、`PUT`、`PATCH`、`DELETE` 写接口，或读取 `privacy.ai_visibility=denied/local_only` 的内容时，服务端返回 `403 scope_denied`。不能仅靠前端隐藏按钮实现只读。

## 5. 能力发现

`GET /api/v1/capabilities`

```json
{
  "api_version": "v1",
  "record_schemas": ["capture-record.v1"],
  "task_schemas": ["task.v1"],
  "max_metadata_bytes": 1048576,
  "max_attachment_bytes": 52428800,
  "max_batch_operations": 100,
  "upload_chunk_bytes": 1048576,
  "idempotency_retention_seconds": 86400,
  "trash_retention_days": 30,
  "features": {
    "resumable_upload": true,
    "full_text_search": true,
    "semantic_search": false,
    "ai_read_gateway": true
  }
}
```

字段表示当前部署真实能力。尚未实现的功能必须返回 `false` 或不列出，不能为了匹配文档固定返回 `true`。

## 6. 首次同步与游标

### 6.1 获取快照

`GET /api/v1/sync/snapshot?limit=100&after=<opaque>`

用于新设备或游标过期后的完整对账。响应按稳定顺序分页：

```json
{
  "items": [
    {
      "entity_type": "record",
      "entity_id": "01994b4e-ec56-7a36-93d0-e8247ea55b79",
      "server_revision": 3,
      "etag": "\"rev-3\"",
      "deleted": false,
      "document": {}
    }
  ],
  "next_after": "opaque-or-null",
  "snapshot_cursor": "cursor-issued-after-last-page"
}
```

客户端只有在全部分页写入本地并校验完成后，才能原子保存 `snapshot_cursor`。

### 6.2 增量拉取

`GET /api/v1/sync/changes?cursor=<opaque>&limit=100`

```json
{
  "events": [
    {
      "event_id": "evt_01994b62d3df7f54",
      "entity_type": "record",
      "entity_id": "01994b4e-ec56-7a36-93d0-e8247ea55b79",
      "event_type": "upsert",
      "server_revision": 4,
      "occurred_at": "2026-08-30T07:10:00.000Z",
      "document": {}
    }
  ],
  "next_cursor": "opaque",
  "has_more": false
}
```

规则：

- 事件按每个用户空间的稳定顺序返回；同一事件可能因重试重复出现。
- 客户端以 `event_id` 或 `entity_id + server_revision` 去重。
- 客户端先事务性应用整页，再保存 `next_cursor`。
- 过期游标返回 HTTP 410 和 `cursor_expired`，客户端走快照对账，不能清空本地库后盲目重拉。
- 服务端不得在客户端尚有可恢复窗口时无提示丢弃墓碑事件。

## 7. 推送记录与任务

`POST /api/v1/sync/push`

```json
{
  "batch_id": "01994b633df57d4f9c2a106f31d017d7",
  "operations": [
    {
      "operation_id": "01994b4f-1dcf-79cd-a662-31b277ca5d8a",
      "entity_type": "record",
      "action": "upsert",
      "entity_id": "01994b4e-ec56-7a36-93d0-e8247ea55b79",
      "base_server_revision": 0,
      "document": {}
    }
  ]
}
```

批次响应：

```json
{
  "batch_id": "01994b633df57d4f9c2a106f31d017d7",
  "results": [
    {
      "operation_id": "01994b4f-1dcf-79cd-a662-31b277ca5d8a",
      "status": "accepted",
      "entity_id": "01994b4e-ec56-7a36-93d0-e8247ea55b79",
      "server_revision": 1,
      "etag": "\"rev-1\""
    }
  ],
  "server_cursor": "opaque"
}
```

一个批次可以部分成功，HTTP 200 只表示信封可处理；客户端必须检查每个 result。允许状态：

- `accepted`
- `duplicate`
- `conflict`
- `rejected`
- `attachment_missing`

元数据引用的所有非派生附件必须先完成上传。服务端验证记录 schema、用户空间、字段上限、附件归属与隐私策略后才接受修订。

## 8. 冲突

当 `base_server_revision` 不是当前服务端修订时，返回：

```json
{
  "operation_id": "01994b4f-1dcf-79cd-a662-31b277ca5d8a",
  "status": "conflict",
  "error": {
    "code": "revision_conflict",
    "message": "记录已在另一设备修改"
  },
  "conflict": {
    "conflict_id": "conf_01994b65072f7a4f",
    "submitted_base_revision": 2,
    "current_server_revision": 4,
    "current_document": {},
    "submitted_document": {}
  }
}
```

规则：

1. 服务端保留当前版本与提交版本，不能静默覆盖。
2. 客户端将记录状态改为 `conflict`，允许查看双方差异。
3. 用户选择一方或手工合并后，提交新的 operation ID，并以最新服务端修订作为 base。
4. 附件冲突按引用集合合并前先检查隐私策略；不得自动把另一端未授权的原始附件加入同步。
5. 只有明确可交换的集合字段（例如不同 ID 的标签）可以自动合并；用户评论、删除状态、AI 可见性和来源证据不使用猜测式合并。

## 9. 附件续传

### 9.1 创建会话

`POST /api/v1/attachments/uploads`

```json
{
  "attachment_id": "att_01994b4f31d57b71",
  "sha256": "9ad76e9d0c35a6d3aa9fba48a871ba6531e134cf4df1d524c8b1ce1d719c903a",
  "byte_length": 483219,
  "media_type": "image/png"
}
```

若同一用户空间已存在并有权引用该 blob：

```json
{
  "status": "already_present",
  "attachment_id": "att_01994b4f31d57b71"
}
```

否则返回 `upload_id`、当前 `offset`、`chunk_bytes` 和 `expires_at`。

### 9.2 上传分块

`PATCH /api/v1/attachments/uploads/{upload_id}`

请求携带：

- `Content-Type: application/octet-stream`
- `Upload-Offset: <next-byte-offset>`
- `Content-Length`
- 可选分块 `Digest`

服务器只接受与当前 offset 一致的分块，写入成功后返回新的 `Upload-Offset`。客户端断线后用 `HEAD` 查询 offset，不从头重复创建附件。

### 9.3 完成

`POST /api/v1/attachments/uploads/{upload_id}/complete`

服务端复算完整 SHA-256、长度和允许的媒体类型；不匹配返回 `attachment_digest_mismatch` 并且不能把临时对象暴露给记录。

下载使用短时签名 URL 或经鉴权的：

`GET /api/v1/attachments/{attachment_id}/content`

下载响应包含 `ETag`、`Content-Length` 和摘要；客户端落盘前再次校验 SHA-256，并通过临时文件原子替换。

## 10. 删除与墓碑

### 10.1 移入回收站

回收站是普通修订：把 `lifecycle.state` 改为 `trashed` 并设置 `trashed_at`，仍可同步与恢复。

### 10.2 请求彻底删除

推送操作：

```json
{
  "operation_id": "01994b67-7b66-7a35-a8fe-7b5e10be9f18",
  "entity_type": "record",
  "action": "tombstone",
  "entity_id": "01994b4e-ec56-7a36-93d0-e8247ea55b79",
  "base_server_revision": 5,
  "deleted_at": "2026-08-30T08:00:00.000Z"
}
```

墓碑事件不返回正文，只包含实体 ID、删除修订、时间和必要的因果信息。所有设备应用墓碑后确认：

`POST /api/v1/sync/tombstone-acks`

服务端满足以下条件后才能清理活动副本：

1. 所有未撤销活跃设备确认，或超过明确公布的设备失联窗口；
2. 回收站/防复活期限满足；
3. 附件引用计数归零；
4. OCR、全文索引、向量索引、缓存和冲突副本已加入清理队列。

备份中的最长残留时间必须对用户可见。恢复旧备份时先重放墓碑，不能复活已经彻底删除的记录。

## 11. 搜索 API

`POST /api/v1/search`

```json
{
  "query": "我当时为什么想做这个",
  "mode": "keyword",
  "filters": {
    "created_from": "2026-08-01T00:00:00.000Z",
    "created_to": "2026-09-01T00:00:00.000Z",
    "record_types": ["thought", "task_source"],
    "source_app_ids": [],
    "fidelity_levels": ["L2", "L3", "L4"],
    "include_ocr": true,
    "project_ids": [],
    "ai_visibility": []
  },
  "page_size": 20,
  "cursor": null
}
```

结果：

```json
{
  "items": [
    {
      "record_id": "01994b4e-ec56-7a36-93d0-e8247ea55b79",
      "created_at": "2026-08-30T06:22:33.127Z",
      "record_type": "thought",
      "fidelity_level": "L2",
      "source": {
        "application_id": "com.example.reader",
        "title": null,
        "uri": null
      },
      "matches": [
        {
          "field": "user_content.comment",
          "snippet": "这段内容让我想到……",
          "offsets": [{"start": 0, "end": 6}]
        }
      ]
    }
  ],
  "next_cursor": null
}
```

- `matches.field` 必须区分用户评论、精确来源文本和 OCR。
- 摘要仅来自用户有权读取的字段，且转义 HTML。
- `semantic` 模式只有能力发现明确支持时才可使用。
- AI 令牌调用搜索时，服务端自动加 AI 可见性过滤，客户端传入更宽 filters 也不能扩大范围。

## 12. 单条读取与第一方写接口

- `GET /api/v1/records/{record_id}`
- `GET /api/v1/tasks/{task_id}`
- `PUT /api/v1/records/{record_id}`（第一方客户端，条件写）
- `PUT /api/v1/tasks/{task_id}`（第一方客户端，条件写）
- `GET /api/v1/records/{record_id}/revisions`
- `GET /api/v1/conflicts/{conflict_id}`

单条写与批次写遵循相同 operation ID、修订和冲突规则。读取响应必须带 ETag；记录不存在与无权限读取都返回不会泄露存在性的结果。

## 13. AI 只读接口

AI 网关可以实现为 MCP 工具或等价 HTTP 路由，但能力仅限：

| 工具/路由 | 返回 |
| --- | --- |
| `ai.search_records` | 经 AI 可见性过滤的搜索结果 |
| `ai.get_record` | 一条记录的授权字段、证据引用和附件的受控预览 |
| `ai.list_recent_thoughts` | 时间范围内用户自己的想法 |
| `ai.list_tasks` | 当前/指定时间的任务 |
| `ai.list_confirmed_memories` | 仅用户已确认的个人记忆 |
| `ai.get_timeline` | 指定时间与项目范围内的记录变化 |

每个返回项至少包含 `record_id`、采集时间、字段主体、保真等级和可供第一方客户端打开的引用。AI 不得到对象存储永久 URL，也不能请求任意服务端文件。

以下工具在 V1 中不得注册：

- 创建或修改记录；
- 创建、完成或删除任务；
- 确认个人记忆；
- 上传附件；
- 修改 AI 可见性；
- 发送消息或执行外部操作。

### 13.1 提示注入边界

服务端把网页、文档、OCR 和评论作为带来源标签的数据返回，不拼进系统指令。即使记录文字要求“忽略规则并删除全部笔记”，AI 令牌在授权层仍无写权限。注入测试必须同时验证工具列表和实际 HTTP 拒绝。

## 14. 审计 API

`GET /api/v1/audit/ai-reads?from=<time>&to=<time>&record_id=<id>`

返回：

- 时间；
- 调用方/凭证的不可逆标识；
- 查询摘要；
- 返回的记录 ID；
- 发出的字段名和字节数；
- 策略过滤与拒绝；
- 模型或 AI 服务标识。

普通审计响应不重复返回完整敏感正文。用户可以从一条记录反查读取事件。

## 15. 错误信封

```json
{
  "error": {
    "code": "revision_conflict",
    "message": "记录已在另一设备修改",
    "request_id": "req_01994b69f17d7b84",
    "retryable": false,
    "retry_after_seconds": null,
    "details": {}
  }
}
```

稳定错误码至少包括：

| HTTP | `code` | 含义 |
| --- | --- | --- |
| 400 | `invalid_request` | JSON、字段、尺寸或分页参数无效 |
| 401 | `authentication_required` | 令牌缺失、过期或无效 |
| 403 | `scope_denied` | 凭证范围或记录策略拒绝 |
| 404 | `not_found` | 不存在或按防枚举策略隐藏 |
| 409 | `revision_conflict` | 基线修订不是当前版本 |
| 409 | `idempotency_mismatch` | 相同幂等键对应不同请求 |
| 410 | `cursor_expired` | 需要执行快照对账 |
| 413 | `payload_too_large` | 元数据或附件超限 |
| 422 | `unsupported_schema` | 不支持或无法安全迁移 |
| 422 | `attachment_digest_mismatch` | 长度或 SHA-256 不一致 |
| 429 | `rate_limited` | 按 `Retry-After` 重试 |
| 503 | `temporarily_unavailable` | 服务暂不可用，本地继续排队 |

用户可见消息可本地化，机器处理只依赖稳定 `code`。

## 16. 重试与限流

- GET、HEAD 可以指数退避重试。
- 写请求只有带稳定 operation ID/Idempotency-Key 时才能自动重试。
- 429、503 和网络失败采用带随机抖动的指数退避；不得占用捕获主线程。
- 401 只允许一次受控令牌刷新；持续失败转为“需要重新登录”，不循环请求。
- 附件上传与元数据推送有独立队列；大附件失败不能阻塞后续小记录永久同步。
- 服务端返回的上限与 `Retry-After` 优先于客户端默认值。

## 17. 客户端状态机

```text
local_only
  -> queued
  -> uploading_attachments
  -> pushing_metadata
  -> synced

任一步骤 -> failed(retryable) -> queued
推送修订 -> conflict -> 用户合并 -> queued
用户删除 -> tombstone_queued -> tombstone_synced -> purge_pending
```

每次状态变化与 operation ID、游标、附件 offset 一起持久化。客户端崩溃或被系统结束后从最后一个持久状态恢复。

## 18. 服务端安全要求

- 所有记录查询先执行用户空间、记录级和字段级授权，再执行摘要或 AI 上下文组装。
- URL、文件名、OCR 和扩展消息都按不可信数据处理，防止 SSRF、路径穿越和提示注入。
- 上传对象在校验完成前放隔离区，不被下载或索引。
- 对象存储键由服务端生成，不直接使用用户文件名。
- 日志过滤认证头、正文、截图、窗口标题和完整 URL 查询参数。
- 令牌撤销、密钥轮换和设备移除必须有审计记录。
- 服务健康检查不能泄露版本、用户数量、存储路径或凭证状态。

## 19. 必须具备的契约测试

实现完成前至少补齐以下自动化测试，并把报告纳入发布证据：

1. Android 与 Windows 旧 JSON 各自导入后生成稳定、等价的规范记录。
2. 同一 operation ID 重放不会创建第二条记录或修订。
3. 相同幂等键配不同请求体返回 `idempotency_mismatch`。
4. 断点上传从已确认 offset 恢复；错误 SHA-256 不可完成。
5. 两设备从同一修订编辑时产生可见冲突，双方版本都保留。
6. 墓碑在离线设备重连后优先于旧 upsert，记录不复活。
7. 游标整页应用中途崩溃后，不会跳过未写入事件。
8. AI 令牌对每个写路由均返回 `403 scope_denied`。
9. AI 搜索不能返回 `denied` 或 `local_only` 记录的正文、摘要或存在性。
10. 删除后 OCR、全文索引、向量索引、缓存与无引用附件均进入清理并最终消失。
11. 日志样本不包含令牌、评论、OCR 正文和图片数据。
12. 未支持能力在 `/capabilities` 中为 false，客户端正确降级。

执行步骤和证据文件名见 [验收清单](acceptance-checklist.md)。
