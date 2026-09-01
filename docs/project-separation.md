# Mnote 项目拆分边界

Mnote 从 LoveTools 仓库中提取为独立项目，但不是复制整个旧 Android 应用。

## 已独立

- 源码根目录与 Git 历史
- Android `applicationId`、namespace、桌面名称、图标和启动 Activity
- Android 本地文件目录、SharedPreferences 与加密 Token Keystore 别名
- Windows 产品名和可执行文件名
- Chrome/Edge 扩展产品名与扩展本地存储 key
- 交付包名称、校验和与发布清单

## 有意保持兼容

- Capture REST JSON Schema 和 `/v1` 路径
- 已上线的服务器 URL
- 服务端现有 SQLite/Blob 数据与三类 Token
- 服务端内部 `heartnote_capture` Python 包名和 `HEARTNOTE_CAPTURE_*` 环境变量

最后一组名称暂时作为部署兼容接口保留。它们不代表 Android 应用仍属于 LoveTools，也避免因改名导致线上知识库和客户端凭证失效。

## 数据边界

新旧 Android 应用使用不同 application ID，因此不能直接读取彼此的应用私有本地文件。已经上传到 Capture Server 的记录不受影响；未上传且只存在 LoveTools 私有目录中的旧记录不会自动复制到 Mnote。
