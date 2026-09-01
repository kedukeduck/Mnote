# Mnote Server V1

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
- `heartnote-capture-backup.service` 与 `.timer`：每天使用 SQLite 在线备份 API 创建一致数据库快照，再打包内容寻址图片；默认保留 14 天。
- `heartnote-capture-mcp.service`：可选的只读 MCP，只监听 `127.0.0.1:8788`，不应直接暴露公网。

生产环境令牌放在 root-only 的 `/etc/heartnote-capture/server.env`，不要写入仓库或客户端安装包。API 本身只监听 `127.0.0.1:8787`，公网入口必须经过有效 TLS 证书的反向代理。

## 测试

不安装 MCP 依赖时，数据层和 HTTP 测试仍可运行：

```bash
PYTHONPATH=src python3 -m unittest discover -s tests -v
```

安装 MCP extra 后，同一命令还会验证工具列表、只读声明和 AI 过滤。
