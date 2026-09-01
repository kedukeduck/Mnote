# 全局摘录完整目标记录与附件结构

- 文档状态：完整目标规范模型；V1 Core 服务使用其可演进子集
- 更新日期：2026-08-31
- 规范编码：UTF-8 JSON
- 相关文档：[产品规格](universal-capture-v1-product-spec.md)、[同步 API](sync-api.md)、[权限与隐私](permissions-privacy.md)

> 本文定义跨 Android、Windows、浏览器扩展和同步服务交换数据时的规范模型。它不是对现有代码完成度的声明。当前 Android 与 Windows 原型的磁盘 JSON 字段不同，V1 可以继续读取各自旧格式，但上传、导出和跨端读取必须通过本文所述适配层归一化。

V1 Core 当前线上交换使用 `schema_version: 1` 的精简记录，实际字段与兼容规则以 [`capture-server/README.md`](../capture-server/README.md) 为准；本文的 `capture-record.v1` 是后续无损扩展目标。

## 1. 设计目标

1. 原始证据、用户表达、OCR、批注、任务和 AI 派生内容可独立追溯。
2. Android camelCase 与 Windows snake_case 本地记录都能无损导入，不靠猜测提升保真等级。
3. 同步可以幂等重试、检测冲突、传播删除并校验附件。
4. 新客户端能跳过未知字段，旧客户端不会因为新字段而破坏已有证据。
5. 隐私遮挡、AI 可见性和删除状态是记录协议的一部分，而不是仅存在于界面设置中。

## 2. 文件与对象关系

一条规范记录由一个 JSON 元数据对象和零个或多个内容寻址附件组成：

```text
CaptureRecord
  |- origin                 记录来自哪台设备及哪个本地 ID
  |- revision               当前修订及冲突基线
  |- user_content           用户自己写的内容
  |- source                 来源与保真等级
  |- evidence               精确文本、OCR、锚点
  |- attachments[]          图片/快照清单，不内嵌大二进制
  |- annotations            可编辑矢量批注层或明确的降级状态
  |- relations              任务、项目、父记录等关系
  |- privacy                敏感级别与 AI 可见性
  |- lifecycle              Inbox/归档/回收站/删除状态
  `- sync                   客户端展示用同步状态
```

正式同步对象使用 `snake_case`。本地实现可以使用平台惯例，但必须有确定性映射。

## 3. 规范记录示例

```json
{
  "schema_version": "capture-record.v1",
  "record_id": "01994b4e-ec56-7a36-93d0-e8247ea55b79",
  "origin": {
    "device_id": "dev_7f9098f8d7b946f0",
    "local_id": "20260830T142233127-4f2a19c0",
    "platform": "android",
    "client_name": "Mnote",
    "client_version": "1.10.0-test"
  },
  "revision": {
    "number": 1,
    "base_number": 0,
    "operation_id": "01994b4f-1dcf-79cd-a662-31b277ca5d8a"
  },
  "record_type": "thought",
  "created_at": "2026-08-30T06:22:33.127Z",
  "updated_at": "2026-08-30T06:22:33.127Z",
  "user_content": {
    "comment": "这段内容让我想到……",
    "title": null
  },
  "source": {
    "fidelity_level": "L2",
    "acquisition": "screen_capture",
    "captured_at": "2026-08-30T06:22:32.901Z",
    "application": {
      "platform_id": "com.example.reader",
      "display_name": "Example Reader",
      "window_title": null,
      "process_path": null
    },
    "uri": null,
    "document_id": null,
    "degradation_reason": null,
    "capture_geometry": {
      "unit": "physical_pixel",
      "virtual_screen": null,
      "selection_screen": {
        "x": 81,
        "y": 114,
        "width": 960,
        "height": 540
      },
      "crop_size": {
        "width": 960,
        "height": 540
      },
      "display_scale": 2.75,
      "rotation_degrees": 0
    }
  },
  "evidence": {
    "exact_text": null,
    "ocr": {
      "status": "pending",
      "engine": null,
      "engine_version": null,
      "languages": [],
      "blocks": []
    },
    "anchor": null
  },
  "attachments": [
    {
      "attachment_id": "att_01994b4f31d57b71",
      "role": "source_crop",
      "media_type": "image/png",
      "byte_length": 483219,
      "sha256": "9ad76e9d0c35a6d3aa9fba48a871ba6531e134cf4df1d524c8b1ce1d719c903a",
      "width": 960,
      "height": 540,
      "local_name": "original.png",
      "privacy_variant": "original"
    },
    {
      "attachment_id": "att_01994b4f46a8798e",
      "role": "annotated_preview",
      "media_type": "image/png",
      "byte_length": 501338,
      "sha256": "af4d4292a83c7e84665fe7372fd6480831cccea73c324cff882cad10fceddbf1",
      "width": 960,
      "height": 540,
      "local_name": "annotated.png",
      "privacy_variant": "original"
    }
  ],
  "annotations": {
    "status": "editable",
    "coordinate_space": {
      "origin": "source_crop_top_left",
      "unit": "physical_pixel",
      "width": 960,
      "height": 540
    },
    "items": [
      {
        "annotation_id": "ann_01994b4f5b8c77d9",
        "type": "highlight",
        "color": "#FFF176",
        "opacity": 0.55,
        "width": 18.0,
        "points": [
          {"x": 102.5, "y": 211.0},
          {"x": 438.0, "y": 213.5}
        ]
      }
    ]
  },
  "relations": {
    "task_ids": [],
    "project_id": null,
    "area_id": null,
    "parent_record_id": null,
    "tags": []
  },
  "privacy": {
    "sensitivity": "normal",
    "ai_visibility": "denied",
    "sync_allowed": true,
    "contains_unredacted_pixels": true
  },
  "lifecycle": {
    "state": "inbox",
    "starred": false,
    "trashed_at": null,
    "purge_requested_at": null
  },
  "sync": {
    "state": "local_only",
    "server_revision": null,
    "last_error_code": null
  }
}
```

示例中的 ID、哈希和文件大小只是结构示例，不是已产生的发布证据。

## 4. 顶层字段

| 字段 | 必填 | 规则 |
| --- | --- | --- |
| `schema_version` | 是 | V1 固定为 `capture-record.v1`；数字 1 只用于旧格式识别 |
| `record_id` | 是 | 全局稳定、不复用；新记录推荐 UUIDv7。导入旧记录时可由设备 ID 与本地 ID 确定性生成 |
| `origin` | 是 | 保留原始设备、本地 ID、平台和创建记录的客户端版本 |
| `revision` | 是 | 新建为 1；每次用户可见编辑增加；`operation_id` 用于幂等 |
| `record_type` | 是 | `capture`、`thought`、`task_source`、`journal` |
| `created_at` | 是 | UTC RFC 3339，毫秒精度；原始时间不因同步改变 |
| `updated_at` | 是 | 当前修订时间，UTC RFC 3339 |
| `user_content` | 是 | 用户输入，与来源文字/OCR 分字段 |
| `source` | 是 | 实际来源、采集方法、保真等级和降级原因 |
| `evidence` | 是 | 精确文本、OCR 和 L4 锚点；不可将 OCR 放入精确文本 |
| `attachments` | 是 | 可为空数组；每个待同步附件必须有 SHA-256 |
| `annotations` | 是 | 可编辑层，或明确标记 `none` / `flattened_only` |
| `relations` | 是 | 任务、项目、父子及标签关系 |
| `privacy` | 是 | 敏感级别、AI 可见性、同步许可和未脱敏像素提示 |
| `lifecycle` | 是 | Inbox、归档、回收站和彻底删除请求 |
| `sync` | 否 | 设备本地显示状态；导出时可以保留，服务端不以它作为真相 |

未知顶层或嵌套字段必须被读取方忽略；执行“读—改—写”的客户端应保留自己不理解的字段。字段缺失与显式 `null` 语义不同：协议要求存在但当前不可取得时使用 `null`。

## 5. 来源与保真等级

### 5.1 `source.acquisition`

允许值：

- `screen_capture`
- `shared_image`
- `process_text`
- `share_text`
- `windows_uia_selection`
- `browser_extension`
- `quick_note`
- `import`

### 5.2 保真规则

| 等级 | 必须存在 | 禁止 |
| --- | --- | --- |
| L2 | 至少一个像素证据附件；采集与坐标元数据 | 把 OCR 填入 `evidence.exact_text` |
| L3 | 来源直接交付的 `exact_text.text`、接入方法和来源应用身份 | 仅凭 OCR 或剪贴板猜测来源 |
| L4 | L3 全部内容、稳定 URI/文档 ID、锚点、版本/快照哈希及一次成功校验 | 只有 URL 就标 L4 |

`degradation_reason` 使用稳定值，例如：

- `source_text_unavailable`
- `source_identity_unavailable`
- `anchor_not_unique`
- `anchor_replay_failed`
- `protected_content`
- `capture_api_denied`
- `ocr_unavailable`

保真等级只能由证据验证器决定。显示层不得根据 OCR 置信度自行升级。

## 6. 精确文本、OCR 与锚点

### 6.1 精确文本

```json
{
  "text": "来源直接交付的 Unicode 文本",
  "selection_count": 1,
  "delivered_by": "process_text",
  "content_sha256": "小写十六进制 SHA-256"
}
```

原始来源文字不执行自动改写或空白折叠。需要索引规范化时生成独立派生字段，不覆盖原值。

### 6.2 OCR

```json
{
  "status": "complete",
  "engine": "engine-id",
  "engine_version": "version",
  "languages": ["zh-Hans", "en"],
  "blocks": [
    {
      "block_id": "ocr_1",
      "text": "识别文字",
      "confidence": 0.96,
      "bounds": {"x": 12, "y": 40, "width": 180, "height": 32}
    }
  ]
}
```

- `status` 为 `pending`、`complete`、`failed` 或 `not_requested`。
- 坐标使用批注 `coordinate_space`；置信度为 0 到 1。
- OCR 失败保留错误码，但普通日志不记录文字。

### 6.3 L4 锚点

锚点至少包含：

- 文字引用：`exact`、`prefix`、`suffix`；
- 位置锚点：采集时的起止偏移；
- DOM/文档辅助路径（若来源适用）；
- 规范 URI 或文档 ID；
- 来源版本、快照或规范化正文哈希；
- `validation_status`、`validated_at` 和验证器版本。

辅助路径不能单独构成 L4；锚点回放失败后保留采集时证据并展示失效状态。

## 7. 附件

### 7.1 角色

| `role` | 含义 |
| --- | --- |
| `source_crop` | 未压平批注的原始选区 |
| `context_image` | 用户可见并确认保存的有限上下文 |
| `annotated_preview` | 便于查看的压平预览 |
| `redacted_source` | 隐私遮挡已经不可逆应用的来源证据 |
| `thumbnail` | 可重新生成的缩略图 |
| `source_snapshot` | 网页/文档来源快照 |

每个附件必须声明 `media_type`、`byte_length`、`sha256` 和 `privacy_variant`。图片还必须声明像素宽高及 EXIF 方向处理结果；上传前不需要的 EXIF、位置和设备信息应删除。

`privacy_variant`：

- `original`：可能包含未遮挡内容。
- `redacted`：隐私遮挡已不可逆应用。
- `derived`：可由其他证据重建，如缩略图。

只要任一可访问附件保留遮挡下的原像素，`privacy.contains_unredacted_pixels` 就必须为 `true`。用户选择“只同步脱敏版本”时，客户端只能上传 `redacted` 附件。

### 7.2 完整性

- SHA-256 对文件原始字节计算，以 64 位小写十六进制表示。
- 服务端在完成上传前复算哈希和长度。
- 相同哈希可以内容寻址去重，但授权和引用计数按用户空间隔离。
- 删除记录不能因为另一个合法引用而提前删除共享 blob；最后一个引用清除后按保留策略物理清理。

## 8. 批注

`annotations.status`：

- `editable`：存在可编辑矢量项。
- `none`：用户没有批注。
- `flattened_only`：旧客户端只留下压平图片；这是兼容降级，不满足 CAP-005 的完整验收。

V1 项目类型至少支持：

- `pen`
- `highlight`
- `rectangle`
- `ellipse`
- `arrow`
- `redaction`

通用字段包括 `annotation_id`、`type`、`color`、`opacity`、`width` 和几何数据。所有点必须为有限数值且位于合理边界；读取方需要限制点数和 JSON 大小，防止恶意文件耗尽资源。

`redaction` 项只是编辑意图。只有生成并校验 `redacted_source` 后，才能对外声称像素已经脱敏。

## 9. 任务对象

记录标为 TODO 时不得只改 `record_type`。需要创建独立任务并建立双向关系：

```json
{
  "schema_version": "task.v1",
  "task_id": "01994b51-59f5-785a-93f6-50d234ca84a9",
  "revision": {
    "number": 1,
    "base_number": 0,
    "operation_id": "01994b51-8f22-7567-83cb-1fd90cabbe90"
  },
  "title": "验证这个想法",
  "status": "open",
  "source_record_ids": ["01994b4e-ec56-7a36-93d0-e8247ea55b79"],
  "due_at": null,
  "completed_at": null,
  "created_at": "2026-08-30T06:25:00.000Z",
  "updated_at": "2026-08-30T06:25:00.000Z"
}
```

`status` 为 `open`、`in_progress`、`waiting`、`done` 或 `cancelled`。任务采用与记录相同的修订、冲突、隐私和删除规则。

## 10. 生命周期与同步状态

`lifecycle.state`：

- `inbox`
- `active`
- `archived`
- `trashed`
- `purge_requested`

`sync.state`：

- `local_only`
- `queued`
- `uploading`
- `synced`
- `conflict`
- `failed`

客户端不得用 `sync.state` 推断服务端数据；它只是最近一次已持久化同步结果。删除使用墓碑事件而不是上传一个“空记录”。

## 11. 现有格式兼容

### 11.1 Android 本地 `record.json`

当前方向是目录内：

```text
capture_inbox/<local-id>/
  original.png
  annotated.png
  record.json
```

已观察到的 V1 本地字段映射：

| Android 字段 | 规范字段/处理 |
| --- | --- |
| `schemaVersion: 1` | 识别为 `android-capture-local.v1`，适配后输出 `capture-record.v1` |
| `id` | `origin.local_id`；结合稳定设备 ID 生成 `record_id` |
| `createdAt`（Unix 毫秒） | 转为 UTC `created_at`，保留毫秒 |
| `kind=comment` | `record_type=capture` |
| `kind=thought` | `record_type=thought` |
| `kind=todo` | `record_type=task_source`；只有真正生成任务对象后才能填 `task_ids` |
| `comment` | `user_content.comment` |
| `sourceType` | 映射到 `source.acquisition`；未知值保存在兼容扩展字段 |
| `sourceText` | 只有接入路径证明来自分享/PROCESS_TEXT 时进入 `exact_text`；否则保持未验证文本并不得提升 L3 |
| `sourcePackage` | `source.application.platform_id` |
| `originalFile` | `attachments[role=source_crop]` |
| `annotatedFile` | `attachments[role=annotated_preview]` |
| `width`、`height` | 附件尺寸及批注坐标空间 |

当前 Android 本地记录若没有矢量批注 JSON，导入时必须使用 `annotations.status=flattened_only`，不能伪造空的可编辑层。

### 11.2 Windows 本地 JSON

当前方向是同名 `<id>.png` 与 `<id>.json`，使用 `snake_case`：

| Windows 字段 | 规范字段/处理 |
| --- | --- |
| `schema_version: 1` | 识别为 `windows-capture-local.v1` |
| `id` | `origin.local_id`；结合设备 ID 生成 `record_id` |
| `created_at_utc` | `created_at` |
| `kind=thought` | `record_type=thought` |
| `kind=later` | `record_type=capture`，可添加迁移标签 `later` |
| `kind=todo` | `record_type=task_source`，任务关系按上节规则 |
| `comment` | `user_content.comment` |
| `image` | 通常作为 `annotated_preview`；如果已经压平且没有独立原图，不得同时声称为 `source_crop` |
| `capture.virtual_screen` | `source.capture_geometry.virtual_screen` |
| `capture.selection_screen` | `source.capture_geometry.selection_screen` |
| `capture.source.window_title` | `source.application.window_title` |
| `capture.source.process_path` | `source.application.process_path` |
| `annotations` | 坐标以裁剪左上角为原点，逐项映射到规范批注 |

旧 Windows 记录只有一张已压平 PNG 时是可读兼容记录，但不满足“原始裁剪与渲染预览分别保存”的最终 V1 验收。

### 11.3 确定性 ID

适配器必须稳定地把同一个 `device_id + local_id` 映射为同一 `record_id`。建议使用固定产品命名空间下的 UUIDv5；新建记录直接使用 UUIDv7。禁止每次导入随机生成新 ID，否则会重复同步。

迁移前必须先保存原文件哈希。无法解析或字段越界的旧记录进入隔离区并返回明确错误，不得删除原文件。

## 12. 校验限制

建议的协议上限；服务端可以更小，但必须通过能力发现返回：

| 项目 | V1 上限 |
| --- | --- |
| 元数据 JSON | 1 MiB |
| 用户评论 | 20,000 Unicode code points |
| 精确来源文字 | 100,000 Unicode code points |
| 单条记录附件数 | 16 |
| 单附件 | 50 MiB |
| OCR block 数 | 10,000 |
| 单批注点数 | 100,000 |
| 标签数 | 100 |

解析要求：

- 拒绝重复 JSON 键、非有限数值、负尺寸、越界长度和无效 UTF-8。
- 不执行附件文件名中的路径；`local_name` 只是显示/迁移提示。
- URI 只作为数据，不在服务端自动抓取；抓取来源快照需要独立、受限的连接器。
- 用户文字推荐 NFC 用于搜索，但原始精确来源文字按字节语义保留。

## 13. 版本演进

- 小字段扩展不改变 `capture-record.v1`，读取方忽略并保留未知字段。
- 改变必填字段、坐标语义、哈希算法或删除语义时发布新的主版本。
- 服务端通过 [同步 API](sync-api.md) 的能力发现列出可接受版本。
- 导出必须携带原始本地格式版本与规范化版本，便于未来重新迁移。
- 在正式冻结协议前，应补充机器可执行 JSON Schema、正反例 fixtures 和 Android/Windows 双向兼容测试；本文不把这些尚未出现的文件视为已完成。
