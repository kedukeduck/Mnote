# 1.9.0：统一设置、导出界面与自托管更新

## 使用入口

双端首页右上角“设置”包含“账号与同步”和“版本与更新”。登录、导入、退出登录等能力仍在账号子页；更新检查、下载、安装和说明仍在版本子页，不改变同步凭据或记录路径。

首页“选择导出”继承当前搜索 / 标签 / 类型筛选。导出页使用 A 风格的浅灰背景、白色圆角卡片、轻量导航和蓝紫主按钮。直接点击卡片勾选，最多 100 条；全选只选择可用记录。Android 的未同步 / 禁止 AI 的记录显示不可选状态；Windows 沿用仅列出可导出记录的行为。底部操作显示数量，空选不能导出。

图片公开前依然必须明确确认；取消文件选择不创建分享。数据语义、配额、撤销、保存失败自动清理及账号隔离与 [1.8 导出协议](markdown-batch-export.md)一致。导出的 Markdown 自包含全部文字，图片为独立快照链接，不承诺所有 AI 自动联网读图。

## 自有服务器协议

固定根地址：`https://chenyu.online/heartnote-capture/updates/`。

- `GET /updates/releases.json`：匿名读取版本索引，`Cache-Control: no-store`。
- `GET /updates/`：下载页；`/updates/style.css` 是配套样式。
- `GET/HEAD /updates/files/<tag>/<filename>`：不可变版本文件，包含长度、附件文件名与长期缓存头。当前不提供 Range 断点续传，失败后重新下载；双端客户端已有完整缓存校验。
- Android tag：`mnote-android-v1.9.0-test`；资产：`Mnote-Android-1.9.0-test.apk`、`SHA256SUMS`。
- Windows tag：`mnote-windows-v1.9.0-test`；资产：`Mnote-Windows-1.9.0-test-Setup.exe`、`Mnote-Windows-1.9.0-test-Portable.zip`、`SHA256SUMS`。

索引保留原解析器的 release-list 字段名，但**不是 GitHub 代理**，没有运行时 GitHub API、GitHub Token 或重定向依赖：

```json
[
  {
    "tag_name": "mnote-android-v1.9.0-test",
    "draft": false,
    "prerelease": true,
    "body": "版本说明",
    "assets": [
      {
        "name": "Mnote-Android-1.9.0-test.apk",
        "size": 123,
        "digest": "sha256:<64位小写散列>",
        "browser_download_url": "https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.9.0-test/Mnote-Android-1.9.0-test.apk"
      }
    ]
  }
]
```

客户端只接受固定 HTTPS 域名与精确平台 / 版本文件路径，拒绝凭据、查询串、fragment、外站和原 GitHub CDN。索引最多 100 项 / 8 MiB，包最多 128 MiB。测试版接收正式与测试更新，正式版不接收测试更新；禁止降级。SHA-256 保障下载一致性，不等于 Windows Authenticode 发行者签名；HTTPS 与服务器管理权限仍是信任边界。Android 保留原包名 / versionCode / 签名校验。

## 发布方式

服务端 0.4.0 从 Capture Store 根目录下的 `releases/` 读静态文件。现有反向代理会把 `/heartnote-capture/updates/` 路由到 API 的 `/updates/`，无需修改 SSH、网络或账号配置。

发布前先运行完整验证，再执行 `bash scripts/package-mnote-android.sh` 与 `bash scripts/package-mnote-windows.sh`（需 NSIS）。打包后核对 Android 原签名和 Windows x64 格式，两脚本均生成各平台 `SHA256SUMS`。从仓库根运行：

```bash
python3 scripts/publish-update-server.py \
  --root /var/lib/heartnote-capture/releases --platform android --version 1.9.0-test \
  --notes docs/settings-1.9.0-release-notes.md \
  deliverables/mnote-android-1.9.0-test/Mnote-Android-1.9.0-test.apk \
  deliverables/mnote-android-1.9.0-test/SHA256SUMS

python3 scripts/publish-update-server.py \
  --root /var/lib/heartnote-capture/releases --platform windows --version 1.9.0-test \
  --notes docs/settings-1.9.0-release-notes.md \
  deliverables/mnote-windows-1.9.0-test/Mnote-Windows-1.9.0-test-Setup.exe \
  deliverables/mnote-windows-1.9.0-test/Mnote-Windows-1.9.0-test-Portable.zip \
  deliverables/mnote-windows-1.9.0-test/SHA256SUMS
```

脚本在本机管理员发布路径加锁，检查包集合、大小、格式头、散列清单；先落完整版本目录，再原子替换索引。已经发布的版本拒绝内容覆盖，修改包必须升版本。仅清理本次分配的 staging 临时目录。没有远程上传接口。发布脚本不是代码签名工具，APK 签名检查仍是打包步骤。

发布后匿名获取索引、HEAD 和完整下载，核对大小与散列，再运行 `bash desktop-windows/tests/run-updater-tests.sh --live`。回滚时可在备份后原子恢复旧索引；不要覆盖同版本包，也不要删除已经发出的下载文件。需要修复时发布更高补丁版本，不靠降级。

## 迁移与数据边界

**先安装 1.9.0，再将仓库设为私有。** 已安装的 1.8 / 1.7 程序不可能在未升级时知道新地址；GitHub 仍公开时可通过桥接 Release 升级，已私有时使用服务器下载页覆盖安装即可。新版本不再依赖 GitHub；源码及可选迁移镜像仍可放 GitHub。此次工作不改变仓库可见性。

匿名路由仅允许固定资产名和版本路径，不允许目录遍历、符号链接逃逸或查看数据目录。私有笔记仍要求账号权限；导出图片只有用户明确创建分享时才公开。发布安装包不等于发布笔记。HTTPS 证书、域名、磁盘容量和服务器可用性需要持续维护。

## 验证边界

自动测试覆盖 Android API 30 / 35 导出选择、过滤、取消 / 保存 / 撤销，以及设置导航、账号摘要、小屏底部按钮和原生渲染；Windows 使用 Wine 原生 GUI 与真实临时账号服务器测试导出 / 撤销和设置导航；服务端覆盖匿名下载、HEAD、缓存、散列一致性、目录遍历与符号链接阻止、失败发布不覆盖旧索引。真实 Android 手机与 Windows 10 / 11 高 DPI / 输入法仍需实际试用。
