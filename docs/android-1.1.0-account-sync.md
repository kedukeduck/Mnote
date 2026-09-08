# Mnote Android 1.1.0-test：账号、自动同步与删除

包名 `com.codex.mnote`，versionCode `9`，调试 / 测试签名。保存编辑中的内容后覆盖安装，不要卸载旧版。

## 使用

1. 首页 → **账号与同步** → **首次激活账号**。服务地址已预填；输入私下收到的一次性激活码，自行设置用户名和密码。
2. 用户名为 3–32 位英文、数字、下划线、点或短横线，首位不能是点或短横线；不区分大小写。密码为 12–128 位，区分大小写。
3. 激活码只用一次，48 小时有效。专用激活码把现有服务器知识库绑定到个人账号，不移动或删除原数据。其他设备以后只需用户名和密码登录。
4. 登录、回到首页、保存和删除后尝试双向同步；首页“刷新 / 立即同步”可手动检查。后台每 15 分钟安排一次补同步，实际执行受联网状态、Doze 和厂商省电策略影响，不承诺实时推送。
5. 点开记录 → **删除记录**，或长按卡片。确认后立即从本机列表隐藏，有成功 / 失败 Toast；联网后同步服务器软删除，其他账号客户端下次同步时移除。
6. 旧 APK 本机记录留在未登录空间，不自动归属下一个登录者。在自己的账号页选择 **导入未登录时的本机旧记录**，确认后复制并补传。重复导入按 ID 跳过；已删除旧记录不导入。

## 安全与边界

- 现有知识库只允许持专用激活码的首个账号认领，不开放公共注册。普通激活码创建的账号有独立数据库和附件目录。旧记录不做破坏性迁移。
- Android 按服务地址和稳定账号 ID 隔离本机记录、缓存及删除队列。退出保留未上传记录，重登同一账号继续；换账号不上传旧账号记录。编辑器绑定打开时的账号，阻止跨账号误存。
- 密码不落手机磁盘或日志，通过 HTTPS 传输。账号页禁止系统截图和最近任务预览。服务端随机盐 + PBKDF2-HMAC-SHA256（600,000 次）；手机以 Android Keystore AES-GCM 加密会话，禁止系统应用备份。
- 会话最长 30 天，过期需重登。在线退出撤销当前会话；离线退出清除手机凭证，未撤销的服务端会话仍受有效期限制。登录有持久化速率限制。
- 删除是可重试的软删除，非永久清除。App 暂无回收站管理界面；服务端已有恢复 API，明确恢复操作可重新显示记录。先拉取云端删除标记再处理上传，防止旧副本复活。
- 同 ID 不同未上传内容发生冲突时保留本机副本并报告失败，不静默覆盖。已同步副本显示较新的服务器修订。
- 本版不含改密、密码找回、设备列表或多因素认证；管理员需保留备份，忘记密码需管理员协助。来源识别继续搁置。
- Windows、浏览器扩展和原 Web 页面尚未改账号登录。旧读 / 写 / AI Token 保留原权限，只连接原知识库；认领账号不会自动撤销它们。泄漏时需单独撤销。账号会话不是 AI 专用只读凭证，不应交给 AI 集成。

## API 与管理员

新增 `POST /v1/auth/activate`（username/password/invitation）、`POST /v1/auth/login`（username/password）、`POST /v1/auth/logout`（Bearer session）。登录 / 激活返回 account_id、username、access_token、expires_at；其余现有 `/v1/*` 路由按会话账号隔离。

生成 48 小时有效的一次性激活码（输出是秘密，不能进入 Git 或公开 Release）：

```sh
python -m heartnote_capture.accounts --data /path/to/data --legacy-owner
# 省略 --legacy-owner 则创建独立新知识库的激活码。
```

自动化覆盖账号间记录、附件、搜索、导出和删除隔离，一次性认领，登录 / 退出 / 过期 / 限流，稳定缓存，旧记录导入，离线删除，墓碑阻止复活，云端恢复，以及原有截图 / 悬浮层 / 键盘 / 刷新回归。

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
PYTHONPATH=capture-server/src python3 -m unittest discover -s capture-server/tests -v
```

准确测试数量、SHA-256 和提交见发布附件。Robolectric 原生 View 预览不是手机实拍；真实手机后台同步时机仍需验收。

参考：[Android PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest)、[OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)。
