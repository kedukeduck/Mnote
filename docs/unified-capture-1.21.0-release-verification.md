# Mnote 1.21.0-test 发布验收

日期：2026-10-08。发布分支：`agent/release-1-21-0`。

## 版本及源码

- 发布源码提交：`2d4a8b1`。Android 与 Windows 的 `mnote-*-v1.21.0-test` 标签均指向此提交，已推送 GitHub。
- Android：`com.codex.mnote`，versionName `1.21.0-test`，versionCode **38**。
- Android 签名证书 SHA-256：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`，与 1.20.1-test 一致，可覆盖升级。
- Windows：x64，应用、资源、安装器及更新器均为 `1.21.0-test`，未作商业代码签名。

## 验证结果

功能基线 `ef24828` 已通过七阶段完整回归：Android 699 项通过、1 项可选联网检查跳过，Windows GUI／同步／数据／更新器／AI 检查、浏览器扩展、服务端 71 项及 APK 清单检查通过，详见 [功能验证记录](unified-capture-editor.md)。

发布分支仅更新版本、打包配置及说明，另行完成：

- Android 7 项更新界面与辅助界面测试通过，APK 构建及 lint 通过。
- Windows 构建与打包通过；安装器实际测试安装、等待旧进程正常退出再更新、卸载、二进制一致性与保留已有记录目录，通过。
- 开启 `MNOTE_LIVE_UPDATE_CHECK=1`，Android 原更新网络代码实际读取公网更新源，确认新版为 `1.21.0-test`，并验证当前版本不会重复提示更新，通过。
- Windows 原更新代码从公网发现并完整下载 `1.21.0-test` 安装包，验证格式、大小和 SHA-256；更新器 45 项检查通过。
- `verify-published-update.py --version 1.21.0-test` 完整下载 APK、Setup、Portable 和双端校验文件，全部与本地校验一致，无重定向；公开下载页包含新版本。
- 更新清单由 24 条增加为 26 条，原 24 条逐项保持不变。
- 公网健康检查正常，匿名访问笔记仍返回 HTTP 401。生产业务服务未重启，账号、笔记和模型配置未改动。

测试环境为 Robolectric 与 Wine，不能代替 Android 真机和原生 Windows 设备验收；未调用真实 AI 模型。

## 发布与回退

安装包位于自有服务器 `/var/lib/heartnote-capture/releases`，逐平台先写入完整不可变资产，再原子替换索引；不依赖 GitHub Release 资产。

发布前清单备份：`/var/backups/mnote-release-1.21.0.lSo6St/releases.json`，SHA-256 为 `c4a08b0d8f12fab6616ee814e0f3d383d7266d87ad2fddd54e16d78fb118c9f4`。若需撤回更新，应核对后续发布，移除本次索引条目或原子恢复备份；不会自动回退设备上已经安装的客户端。旧版本资产保留。

## 安装包校验

| 文件 | SHA-256 |
| --- | --- |
| Android APK | `cbd2668a2f554445eb313b72d60a773a6b322e242bac74e814a772c40c044ce5` |
| Windows Setup.exe | `b0252fdbd45e224e101a423a1e3c424fc9860703f46e9a458fb35334b9f72676` |
| Windows Portable.zip | `c6d9c5ea1416fb6fe533cec318f5f649015d81b6b2cace8a63e8822fb7a28b6d` |
| Windows mnote.exe | `95bed9c5f79055d1f0c5386a3178fe423ac56a721ae8e5ec153cd5375e870f21` |

下载入口见 [版本说明](unified-capture-1.21.0-release-notes.md)。安装前保存草稿，直接覆盖安装，不要先卸载。
