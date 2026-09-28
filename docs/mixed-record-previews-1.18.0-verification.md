# Mnote 1.18.0-test · 多分类与混合预览验证

发布日期：2026-09-29（Asia/Shanghai）。分支 `agent/mixed-record-previews-1-18`；Android 检查点 `e460d11`，Windows 与原生视觉证据检查点 `b52ead8`，均已推送 GitHub。未合并或改写稳定分支。

## 实现与数据边界

| 实际内容 | 列表系统分类 | 预览 |
| --- | --- | --- |
| 自己的文字＋素材 | 想法、摘录 | 上方想法，下方独立引用块 |
| 只有自己的文字 | 想法 | 正文预览，无空素材块 |
| 只有摘录文字、页面原文或图片 | 摘录 | 素材预览，无空想法块 |
| 待办＋素材 | 待办、摘录 | 待办内容与素材分开 |
| Windows 稍后回顾＋素材 | 稍后回顾、摘录 | 保留原类型身份 |

有自写文字的旧 `comment` 记录也显示“想法”；不写回 `kind`，不迁移记录、不改正文或同步协议。仅空白不算内容；URL-only 不新增“摘录”，旧的空 `thought` 保留“想法”兜底，其他全空记录显示“记录”。因此是内容优先的展示分类，不宣称全部历史类型都被删除。

两类筛选复用同一内容判断，混合记录同时可在“想法”与“摘录”筛选找到。自定义标签、搜索、长按多选、删除、导出继续使用原流程。

## 视觉与交互检查

- “想法/待办”系统标签为墨绿，“摘录”为铜色，自定义标签仍为更小的浅底描边 `# 标签`。
- Android 想法正文 17sp，素材文字 14sp，以独立标题、浅底、铜色引用边线区分；混合记录各预览最多 3 行，纯想法最多 4 行。摘录预览另有 420 字符上限，详情和存储不截断。
- 选中文字优先显示；无选中文字时才回退页面原文，标题明确写“页面原文”。截图显示真实缩略图，不用想法内容代替素材。
- Android 系统标签 12sp、自定义标签 11sp，支持窄宽度、放大字号及 RTL 换行；选中卡片后仍能辨别两个内容区。
- Windows 采用原生可变行高，混合/纯素材/纯文字分别为 276/212/196 DIP，DPI 变化时重新测量；保留选中记录 ID 和多选集合。思想区、素材区、自定义标签及同步状态互不覆盖。

实际实现截图：

- [Android 混合记录](design/mixed-record-cards-home.png)
- [Android 多选](design/mixed-record-cards-selected.png)
- [Android 320dp、1.5 倍字号](design/mixed-record-cards-large-text.png)
- [Android 图片与页面原文](design/mixed-record-cards-materials.png)
- [Windows 宽窗口](design/windows-mixed-record-cards.png)
- [Windows 窄窗口](design/windows-mixed-record-cards-compact.png)

截图使用合成测试记录；Android 为生产 View 的 Robolectric/Skia 渲染，Windows 为真实 Win32 应用的 Wine/Xvfb 采集。没有宣称实体 Android 的 OEM 字体、TalkBack 或触摸长按，以及实体 Windows 多屏/输入法均已手动验收。Windows 150% DPI 为自动化模拟，不代替多显示器实机验收。

## 自动化结果

`scripts/verify-mnote-v1.sh` 全部七个阶段完成；独立任务 `mnote-1-18-full-verification.service` 最终 `inactive/dead`、`Result=success`、`ExecMainStatus=0`，日志以 `Mnote V1 automated verification passed` 结束。

- Android：41 个测试类、586 项测试，585 通过、1 项默认关闭的在线测试跳过，0 失败/错误；构建通过；lint 为 0 errors / 116 warnings。先前定向 30 项也全部通过。
- `RecordCardsTest` 共 8 项，覆盖多分类、双预览区分、交叉筛选、待办＋摘录、原文＋图片、空白、详情完整性、无写回、标签换行和长按多选。
- Windows：x64 构建、135 项记录库检查、45 项离线更新器检查、同步边界和隔离本地账号集成全部通过。
- Windows GUI：捕获/编辑/筛选/同步/删除恢复/导出及撤销/多选流程通过；新增无障碍双字段、想法/摘录筛选、素材块位置、可变行高与 DPI 往返检查通过。
- Windows 安装烟测通过：安装、升级进程交接、精确 payload 匹配、卸载保留已有记录目录。独立任务 `mnote-1-18-installer-smoke.service` 退出 0。
- 浏览器扩展 manifest/module 与服务端 45 项数据、HTTP、Web、MCP 测试通过；Android manifest 检查通过。

Android 和 Windows 代码分别做过独立只读审阅，未发现本次改动引入的 P1/P2。DPI 非首行多选保持的更直接测试仍可追加；当前实现保留 ID 集合，已检查重建顺序及索引一致性。

## 发布与线上验证

- 发布前在 `.publish.lock` 保护下，备份当时 17 条清单至 `/var/backups/mnote-1.18-release.l8PuvB/releases.json`，备份目录权限 0700。
- 使用 `scripts/publish-update-server.py` 发布双端不可变安装包及原子更新清单。逐项比对确认原 17 条完全不变，当前共 19 条且无重复，仅新增双端 1.18.0-test。
- `scripts/verify-published-update.py --version 1.18.0-test` 退出 0：APK、Setup、Portable 及两份 SHA256SUMS 全部匿名完整下载，无重定向，大小和 SHA-256 与本地验收包一致；固定下载页包含有效链接。
- Android 生产 `AppUpdateClient` 联网测试指定期望 1.18.0-test 并强制重跑：发现该版本，同版本无更新；1 项通过、0 跳过/失败/错误。独立任务 `mnote-1-18-live-android.service` 退出 0。
- Windows WinHTTP 更新器实际下载并校验 1.18.0-test 安装器，45 项检查通过；独立任务 `mnote-1-18-live-windows.service` 退出 0。
- 服务器 health 返回 `{"status":"ok"}`，服务仍 active/running、`NRestarts=0`，启动时间未变。本轮没有重启/部署服务，没有访问或迁移用户账号和笔记。

如需撤回更新提示，只应在发布锁保护下原子恢复本次 17 条清单备份，不删除不可变包、不恢复用户数据，也不复用更早的清单备份。已经安装的客户端不自动降级，后续修复应发布更高版本。

## 安装包

Android 为 `com.codex.mnote` / code34 / `1.18.0-test`，签名验证通过，证书与 1.17 完全相同：`b8facf6a55636be9138264aaf85f4462cd8b25ba00a49e46b2defe3ee83182ae`。Windows 为未做 Authenticode 签名的 x64 测试安装器。保存草稿后覆盖安装，不要卸载旧版。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Android APK | 23811750 | `f80346819be21bf827ab8d26e378c08474ff0cc9f1d37f5b44cd3a29a2767a26` |
| Windows Setup | 1155607 | `0cf8d17b592da9b911b26fda353e199c597a52c9cb3e958f71509039958467cc` |
| Windows Portable | 1335227 | `d3451d07a8c7ae96d963bf3f4feeaa523d3fd8474ed2a188acc66d6c31c1b446` |

- [Android APK](https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.18.0-test/Mnote-Android-1.18.0-test.apk)
- [Windows 安装包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.18.0-test/Mnote-Windows-1.18.0-test-Setup.exe)
- [Windows 便携包](https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v1.18.0-test/Mnote-Windows-1.18.0-test-Portable.zip)

1.9 及之后的测试版可在设置中检查更新；仍依赖 GitHub 更新源的 1.7/1.8 旧客户端可从固定下载页手动覆盖一次。发布后没有重打或覆盖同版本资产。

证据：`/tmp/mnote-1.18-full-verification.log`、`/tmp/mnote-1.18-full-test-results.UBtoJw/`、`/tmp/mnote-1.18-installer-smoke.log`、`/tmp/mnote-1.18-live-android.log`、`/tmp/mnote-1.18-live-windows.log`。全量 XML 及 lint 在在线单项测试覆盖输出前已备份。
