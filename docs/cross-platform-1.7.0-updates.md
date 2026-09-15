# Mnote 1.7.0-test · Android / Windows 应用内更新

## 使用

两端首页右上角新增“更新”。打开后查询官方最新可用版本，显示当前版本、更新说明、下载状态；用户分别确认下载与安装。首次先手动覆盖安装本次 1.7.0-test，不要卸载旧版；旧版没有该入口，无法自行获得这项功能。

- Android：下载后验证 APK 的包名、版本名称、递增 versionCode、系统最低版本及与已安装应用完全相同的签名。点击安装时如未授权，先前往系统“允许安装未知应用”设置，再返回点击安装。由系统安装器最后确认，不静默安装。悬浮记录未关闭时先提示保存。APK 沿用测试签名。
- Windows：下载并验证安装包后，提醒处理未保存的编辑，用户确认退出旧版并启动安装器。`/UPDATE /UPDATEPID=<pid>` 等待旧进程（含后台同步收尾）正常退出，最长 30 秒；超时中止安装，不强杀进程，也不代表静默安装。取消安装后可重新打开旧版。便携版通过更新入口转为安装版，旧便携 EXE 不覆盖，请改用新快捷方式。安装器没有 Authenticode 签名。
- 网络失败、GitHub 限流、签名或文件校验失败均提示并允许重试；不修改现有记录。部分网络可能无法访问 GitHub，可使用“官方发布页”尝试浏览器下载。
- 下载不支持断点续传；已完成且校验通过的同一包可复用缓存。没有后台自动下载安装，也不强制更新。

## 发布与安全约定

客户端只查询公共 `kedukeduck/Mnote` Releases API，最多最近 100 个发布，区分平台。升级元数据请求不接触账号同步服务，不携带账号 Token、密码、笔记或设备内容。

| 平台 | Tag | 精确附件名 |
| --- | --- | --- |
| Android | `mnote-android-v<version>` | `Mnote-Android-<version>.apk` |
| Windows | `mnote-windows-v<version>` | `Mnote-Windows-<version>-Setup.exe` |

版本格式为数字 `major.minor.patch`，测试版加 `-test`。按数字而非字符串排序；同版本号正式版高于测试版。忽略草稿、其他平台和未知版本格式。测试版接收测试 / 正式版，正式版不接收 prerelease。Windows / Android 必须各自递增版本；Android 还必须递增 versionCode。本次为 versionCode 21。

精确校验仓库、Tag、文件名、GitHub Release asset `digest` 的 SHA-256 及声明大小（最多 128 MiB），重定向只允许 HTTPS 官方 GitHub 下载地址和 `release-assets.githubusercontent.com`。不接受任意服务器返回的更新地址。缺少匹配附件不会作为更新候选；匹配附件的校验元数据不合法则中止，不能省略 digest 发布。元数据通过 HTTPS 信任 GitHub，不是单独签名的离线更新清单；Windows SHA-256 校验不能替代发行者代码签名。

Android 通过私有 `cache/updates` 的受限 FileProvider 临时授权安装器读取，不能暴露笔记或会话目录。Windows 缓存在 `%LOCALAPPDATA%\PersonalCapture\Updates`；启动前锁定安装包防止校验期间被覆盖，再次验证哈希和 x64 PE 头。安装器升级与卸载均保留数据目录。

后续发布必须保留旧版本回退链接，不覆盖既有附件；先测试再按上述命名上传，确认 GitHub 生成 digest 后公开。若未来换正式 Android 签名或应用商店渠道，需单独设计迁移方案，不能让用户卸载丢失本地记录来解决签名冲突。

## 验证入口

```bash
bash scripts/verify-mnote-v1.sh
bash desktop-windows/tests/run-updater-tests.sh --live
bash desktop-windows/tests/run-workspace-gui.sh
bash scripts/package-mnote-windows.sh
bash desktop-windows/tests/run-installer-smoke.sh
```

新增 Android 测试覆盖平台 / 版本 / 渠道、URL / digest / 大小、APK 同签名与升级身份、损坏文件、FileProvider 范围、首页入口、检查成功 / 失败及重试、显式下载确认和 Activity 销毁回调。Windows 测试覆盖版本选择、下载地址、SHA / 大小 / PE 架构、缓存复用、错误安装阻止；公开 Release 下载测试不执行下载的安装器。安装器测试只在隔离 Wine 前缀检查升级等待与数据保留。

自动化不能代替 Android 真机系统授权及覆盖安装、真实 Windows 10 / 11 安装向导、SmartScreen 和第三方安全软件兼容性的人工验收。
