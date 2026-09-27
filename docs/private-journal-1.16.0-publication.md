# Mnote 1.16.0-test · 线上发布验证

日期：2026-09-28。用户选择 A，明确授权发布双端测试版并升级配套服务端。发布分支 `agent/release-private-journal-1-16`，从已验证的 `8552c45` 创建；没有合并或重写稳定分支，没有更改仓库可见性。

## 结果

- Android / Windows `1.16.0-test` 均已发布到自建更新源。下载不依赖 GitHub，不需要笔记账号或 Token。
- Capture Server 从 `0.6.0` 升级到 `0.7.0`，完整分享二维码已经具备配套服务端支持。
- 原来 13 个更新条目逐项一致保留，当前共 15 项；同版本安装包保持不可变，只原子替换更新清单。

下载：

- [Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.16.0-test/Mnote-Android-1.16.0-test.apk)
- [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.16.0-test/Mnote-Windows-1.16.0-test-Setup.exe)
- [Windows 便携包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.16.0-test/Mnote-Windows-1.16.0-test-Portable.zip)
- [固定下载页](https://chenyu.online/heartnote-capture/updates/)

## 备份、升级顺序和数据保护

先复跑服务端 45 项测试全部通过，再用既有部署脚本在独立 systemd 一次性任务中升级。任务 `mnote-release-1-16-deploy-20260928.service` 最终 `Result=success` / `ExecMainStatus=0`，不依赖对话终端存活。

备份：`/var/backups/mnote-account-upgrade.NeW1Ss`，权限 0700，含旧 venv、配置和停服后复制的完整 data（含原更新清单）。只短暂重启 `heartnote-capture-api.service`，未改 SSH、代理或网络。

升级后确认 installed distribution 为 `0.7.0`、服务 active/running、本地及公网 health 200、匿名私有记录请求 401、无效激活码 403。原记录数据库 `quick_check` 通过，与备份比对现有 39 条记录和附件元数据完全一致。

只有服务端线上验证完成后才发布客户端更新。部署脚本的失败回退恢复二进制、保留最新数据，不能将旧备份覆盖到已经接收新记录的数据目录。0.7 创建的新增分享模块无法由 0.6 完整呈现，若需回退应先撤回更新清单并评估服务兼容；不要直接降级后声称所有新链接仍正常。

## 真实 HTTPS 验证

`scripts/verify-deployed-accounts.py --backup /var/backups/mnote-account-upgrade.NeW1Ss --card-shares` 成功退出 0：

- 使用隔离的临时账号，不认领 legacy vault，不读取或公开用户笔记。
- 激活、登录、上传、拉取变化、PNG 下载、删除及注销均通过。
- 全六模块、仅想法、旧版仅原文/来源的分享请求均通过；公网页面只含选中字段，原图/批注图不额外泄露另一版本。
- 圈选图和上下文图通过真实公网 URL 获取，PNG 字节精确一致。
- 删除原记录不改写快照；撤销后页面与图片均返回 404。
- 临时公开链接已撤销、测试账号和会话已删除；仅合成测试 vault 移入私有备份，没有删除用户记录。

## 客户端及安装包

- Android 原生 `AppUpdateClient` 联网测试：指定期望 `1.16.0-test`，确实发现该版本；再检查该版本返回无更新。1 项通过、0 跳过，Gradle 退出 0。
- Windows 原 WinHTTP 更新器：实际发现并下载 `1.16.0-test` 安装器，SHA-256 / 大小 / x64 PE 校验通过；45 项检查通过，执行退出 0。
- 1.9 发布版到当前的双端更新地址/选择实现兼容；旧 1.7/1.8 仍使用 GitHub，若检查受阻请从固定下载页手动覆盖一次。
- `scripts/verify-published-update.py --version 1.16.0-test` 对 APK、Setup、Portable 及两个 SHA256SUMS 逐个匿名完整下载，不接受重定向；大小、清单摘要及本地已验收文件的摘要全部一致。下载页包含三种包的有效链接。
- Android ID `com.codex.mnote` / code32，沿用原测试证书；Windows x64 测试安装器未做 Authenticode 签名。生产代码和安装包未因发布再修改。

| 文件 | SHA-256 |
| --- | --- |
| Android APK | `46c4cb3e16bba22fe179cdebc6a2d09a445ab859a2d512b74959466e1bf2fe37` |
| Windows Setup | `dcd26cbf07c93d14665b05024c678188f9170d6f4366b0077c8ae4e58567683e` |
| Windows Portable | `8f763d16bff267a2b3fc4b1085e14147ce818b48e738fc8a9aa51a4c41ace9f7` |

日志：`/tmp/mnote-release-1.16-{server-tests,live-accounts,public-downloads,android-live,windows-live}.log`。本轮只验证接口、更新运输和安装包，没有宣称实体 Android 相册/输入法或 Windows 高 DPI 已手动验收。覆盖安装前先保存草稿，不要卸载旧版。
