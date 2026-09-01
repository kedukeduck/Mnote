# Mnote Chrome / Edge 扩展 V1

Manifest V3 扩展，用于网页上的精确文字捕获、当前可视区域截图批注、即时想法与本地待同步箱。

## 安装

1. 解压交付包。
2. Chrome 打开 `chrome://extensions`，Edge 打开 `edge://extensions`。
3. 开启“开发者模式”，选择“加载已解压的扩展程序”，指向本目录。
4. 在扩展的“同步设置”中填写 Capture Server 地址与写入 Token；如需浏览完整服务端 Inbox 或导出，再填写独立的第一方只读 Token。
5. 可在 `chrome://extensions/shortcuts` 或 `edge://extensions/shortcuts` 修改快捷键。

## 捕获方式

- 工具栏按钮：截图圈选、保存选中文字、即时想法。
- 网页右键菜单：记录选中文字，或截图后批注。
- `Ctrl+Shift+Y`：当前可视标签页截图并打开划线/荧光笔/圈选/方框编辑器。
- `Alt+Shift+H`：保存当前选中文字及 TextQuote、DOM Range、矩形定位信息。

网络失败或未配置 Token 时，完整记录进入扩展私有的本地待同步箱；后台每分钟尝试重传。设置页也可手动同步。

## 权限边界

- `activeTab`：只在用户点击或快捷键触发后访问当前标签页。
- `scripting`：读取用户已经选择的文字及其定位，不注入常驻脚本。
- `tabs`：获取来源标题/URL，并打开批注页。
- `storage` / `unlimitedStorage`：保存设置、最近记录和带图片的离线待同步记录。
- `contextMenus` / `alarms`：右键入口和离线重试。
- 默认网络权限仅含本机；配置其他 HTTPS 地址或私有 IP 时浏览器会再次询问该单一 Origin 的权限。公网或域名上的明文 HTTP 会被拒绝。
- 写入 Token 只用于上传；第一方只读 Token 才能读取完整知识库。请求禁止跨站重定向，避免凭证被带往意外来源。

浏览器内部页、扩展商店和受保护页面不允许脚本或截图时，扩展会明确报错，不会绕过浏览器安全限制。
