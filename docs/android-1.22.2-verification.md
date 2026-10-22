# Mnote 1.22.2-test 文字稿修复验证

日期：2026-10-22（Asia/Shanghai）。分支 `agent/transcript-failure-diagnostics`；服务端修复 `1799b2f`，分享链接及 Android 修复 `5442f0e`。

## 实际原因

用户提供的抖音短链 `https://v.douyin.com/P3DO6rRvucA/` 对应约 339.243 秒的视频。账号范围内的失败任务错误为 `invalid_media`，进度 42，说明已下载、尚未完成解码，不是登录或等待连接问题。

普通公开页面解析给出的第一个低码率候选可返回 HTTP 200，下载完整大小为 7,151,539 字节，但本地 FFprobe 只发现 HEVC 视频轨，没有音轨。旧代码在 HTTP 下载成功后直接停止候选选择，因此 FFmpeg 返回“没有可输出的流”。第二个正常播放候选为 29,810,315 字节，包含 AAC 音轨和 H.264 视频轨，本地两秒解码检查成功。检查过程中未转发 Cookie、未输出签名媒体 URL，也未绕过平台限制。一次普通公开页面读取也出现过暂时不可用，不保证每次平台访问都成功。

另一个独立问题是完整分享文案可能带 Markdown 链接，使同一 URL 出现多次，旧的解析规则将其误判为多个链接。现已支持相同链接去重、格式化包装、保留裸链接的签名参数；多个不同链接和畸形裸链接仍拒绝。脱敏规则不变。

## 修复边界

- HTTP 成功后必须先解码为有效 WAV。抖音候选无法解码时尝试同一作品的正常播放候选，最多三个候选，共享 120 秒下载和解码预算；每次替换输入和残留 WAV，不拼接文件。
- 保留 HTTPS、公开 DNS/IP 校验、受控 CDN、禁止跨域自动重定向、80 MiB/30 分钟限制。FFmpeg 仍只读取完整本地文件，协议仅 `file,pipe`，没有启用 HLS、DASH 或网络解复用。
- 超时、取消、文件/时长超限、危险 URL 和来源限流不会被视为可以放宽规则重试的解码错误。
- Android 使用固定错误码白名单解释失败，不展示服务端原始异常、凭据或签名 URL。请求正文和幂等 UUID 保持不变，升级不会把旧的恢复提交变成新任务。
- 已失败任务保持失败，不自动重新提交、不覆盖原摘录。用户需重新发起解析并确认本次声明。

## 验证结果

- Python 核心完整回归 146 项通过；含 14 项真实 FFmpeg 本地合成媒体回归，覆盖无音轨后回退、残留 WAV 替换、全部候选无音轨、MP4/AAC、分片 MP4、损坏文件、禁止播放列表、候选上限和共享预算。测试没有下载第三方媒体。
- 浏览器辅助服务 Node 回归 8 项通过。
- 账号服务回归 96 项，95 项通过、1 项可选 MCP 跳过；账号服务源码和进程未改动。
- Android 范围回归 160 项通过，0 失败、0 跳过。包含真实客户端 30 项（SDK 30/35）、文字稿页面 19 项及统一记录、草稿、编辑、同步、AI 核心、更新页与 URL 提取。
- `assembleDebug`、`lintDebug`、APK 签名检查通过。版本 `1.22.2-test`，versionCode 42，包名 `com.codex.mnote`；签名证书 SHA-256 `b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`，与上一版一致。
- 线上独立 QA 账号完成 HTTPS 登录鉴权、声明检查、幂等提交、跨账号读/删隔离、原始 CC0 示例的完整 ASR、转写记录保存/读取/删除及临时任务清理。QA 凭据已撤销，合成测试 vault 可恢复地移至 `/var/backups/mnote-transcript-verify.rv55p7vu`。没有修改现有用户的密码、笔记或任务。
- 本次针对用户链接验证了实际媒体下载和音轨解码，未重新生成该视频的完整文字稿；上述完整 ASR 验证使用自有 CC0 示例。没有真机验收，也不声称全量 Android 测试通过。
- 修改源码及 APK 解压内容均未包含受保护的服务器凭据值。

## 上线及回退

仅在没有活动转写任务时原子替换核心源码、重启 `mnote-transcription-demo.service`；账号同步、浏览器辅助及出口服务均保持运行。最终安装源码和仓库文件的 SHA-256 均为 `438743fa43fe5637ff1e842b69c380a79f00a02bba7277781ad657d25510eed8`，线上配置返回 ready。

修复前源码保存在 `/var/backups/mnote-transcript-no-audio.38prFEBo/service.py`（SHA-256 `b706e79986ad3eb0eb8ebc0b84e5f0350b74dd65d3d4db107a5feefb5691eab1`）。仅回退后续裸链接标点保护时可使用 `/var/backups/mnote-transcript-query-preserve.xaZbw9Tn/service.py`。回退前确认没有后续部署与活动任务，仅恢复目标源码并重启该核心服务，不替换 env 或账号数据。

Android 已发布到自有更新源；公共清单由 30 条增至 31 条，原条目逐项保持不变。发布前清单保存在 `/var/backups/mnote-release-1.22.2.sQZ4TpLs/releases.json`。撤回时核对之后的发布，仅移除本次 Android 索引，不覆盖较新的清单、不删除旧包，不会自动降级已安装设备。

完整下载验证通过，无重定向，下载页包含新版。APK 为 23,681,855 字节，SHA-256 `34ed7b11acabda518018300d70234952f846d76408e5b0689484f000a5871924`。Windows 安装包保持 `1.22.0-test`，共享本次服务端修复。

[下载 Android 1.22.2-test](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.22.2-test/Mnote-Android-1.22.2-test.apk)。覆盖安装前保存草稿，不要卸载旧应用。
