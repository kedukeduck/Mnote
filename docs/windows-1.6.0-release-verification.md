# Windows 1.6.0-test 交付校验

发布日期：2026-09-15。发布页：<https://github.com/kedukeduck/Mnote/releases/tag/mnote-windows-v1.6.0-test>。

源码 / 发布标签目标：`46d621f56ee5600fdb1bd32f731394b5e301dcbb`。
数据层回滚点：`8258b66`。分支：`agent/windows-android-parity`。未修改稳定分支或部署线上服务器。

| 文件 | SHA-256 |
| --- | --- |
| Mnote-Windows-1.6.0-test-Setup.exe | `c006d4ae0b8cd9c17232091d180da9c2ac4e0941e1eb26f69b341fdefbbd7588` |
| Mnote-Windows-1.6.0-test-Portable.zip | `ae14d73677996ea2dcd879ca215233fa750503da44184151bc8761184b9d1f93` |
| 解压后的 mnote.exe | `6b2d0e2250d35b52a3fd5dd505e56e6e8b7472593d6932900f71a933f2ef644d` |

应用与安装器均为 PE32+ x86-64。应用无外部 MinGW 运行时 DLL，Security Directory 为零，明确为未签名测试版。

验证证据：

- `bash scripts/verify-mnote-v1.sh` 成功，含 Android 429 项单元测试、lint、Windows、浏览器及服务端 22 项测试和 Manifest 检查。
- Windows 数据层 60 项检查通过；两个客户端连接临时真实服务的上传 / 拉取 / 编辑 / 删除 / 恢复和图片字节校验通过。
- 最终 Windows 构建无编译警告；最后增加未分类与中文图片选择后重新运行 GUI 回归通过。
- 最终交付包重新执行安装 / 覆盖安装 / 卸载测试，安装 payload 与便携 EXE 完全一致，旧数据哨兵文件保留。
- 包和 SHA256SUMS 已发布到 GitHub prerelease；公开下载字节校验见本次交付记录。

测试依赖：MinGW-w64、CMake、固定哈希 nlohmann/json 3.11.3、NSIS 3.09 原生 amd64-unicode、Wine 9.0 / Xvfb。测试用临时数据和独立 Wine 前缀，无真实账号数据。Wine 用测试字体映射检查中文显示，不随产品分发测试字体。

未验证：真实 Windows 10 / 11 的全部 DPI / 多屏 / 输入法组合，真实浏览器和各第三方应用 UIA 兼容性，及真实 Android 与 Windows 设备间的操作体验。不要将 Wine 自动化等同于这些人工验收。
