# 分享图片与扫码阅读页 · 设计审查及实现方向

范围：Android 分享图片/实时预览，以及已有 `/c/<token>` 扫码阅读页。沿用私人刊物暖纸、墨绿、铜色体系；不改记录内容、分享选择、授权、撤销或快照协议，不新增标签/记录时间/类型到图片。

## 本轮新采集的审查证据

只使用合成记录与当前生产渲染代码，未访问或分享用户笔记。以下是在本次修改生产 UI 之前新运行原生测试取得的画面，不以历史设计图作为现状证据。

1. [模块选择与实时预览](design/share-editorial/before/01-preview.png)：功能完整，预览与模块选择关系明确；现有短卡中页脚视觉权重偏大。
2. [混合分享图](design/share-editorial/before/02-card-mixed.png)：想法在上、素材在下的顺序正确，但摘录 78px、想法 52px，字号层级反向。两类内容仅靠小标题区分，阅读时容易先被摘录吸引。
3. [仅想法短卡](design/share-editorial/before/03-card-thought.png)：248px 二维码占图宽约 23%，与大号页脚说明共同占据大量短卡空间。长横线直接贴近正文末行，段落呼吸感不足。
4. 扫码阅读页：[原手机首屏](design/share-editorial/before/05-web-mobile.png)、[原桌面](design/share-editorial/before/05-web-desktop.png)、[原来源入口](design/share-editorial/before/06-web-mobile-source.png)。在获准使用本机 Chromium 后，先对真实服务端模板的合成快照采集截图，再修改代码。想法/摘录字号与字形相同、正文归属难区分，长原文把来源入口推到页面底部，桌面截图无尺寸约束导致页面过长。最初图片尚未加载的截图已拒收，接受的是两张图片 decode 完成后的画面。

证据边界：原生截图来自 Robolectric/Skia，不是实体手机；无法仅凭这些截图声称完整无障碍合规。二维码需要额外验证实际解码，不能只根据外观判断大小是否安全。

## 设计决策

- **先看见人的想法**：自写内容为第一视觉层级，使用已随 Android 打包的 Noto 衬线字体与墨绿文字，保留全部内容、段落和表情。
- **明确哪些话来自引用**：摘录字号降低，放进浅底、左侧铜色细线的引用区域；缩小装饰和标题，强调内容归属而非制造另一个大标题。
- **二维码回到辅助入口**：右下小型页脚，文案缩为“查看本次分享 / 长按识别二维码”。保持安静区域与黑白对比，尺寸以实际长 URL 在完整图、半尺寸图和长图底部都能解码为准，不能只减像素。
- **去掉装饰性的拥挤**：轻量 Mnote 字标与短下划线取代满宽分隔线；保留合理区块间距。图片仍位于文字之后，原文和来源只通过扫码页查看。
- **短内容不硬撑，长内容不失真**：保留短卡、轻度压缩、摘录截断、上下文取景与长 PNG 分块导出的既有机制；想法不截断。

扫码页采用同一字体层级和引用样式；图片保持原比例并可打开原图；原文用明确的原生展开入口，仅原文/来源页面默认展开；来源显示域名及外链入口。有三个以上内容组时生成所选分组的页内导航。保持纯外部 CSS、既有 CSP、HTML 转义、no-store/no-referrer 与选中字段边界，不加载第三方追踪或字体服务。

## 验收门槛

- 同模块/同尺寸的前后真实渲染比较，检查想法、摘录、图片、二维码的比例和边界。
- 所有 63 种分享模块组合、长想法、长摘录、页面取景、流式 PNG 与预览一致性回归。
- 二维码检查区分整图搜索与定点扫码：全部 63 种组合做原尺寸整图解码；保留仅想法/全模块、不同 token 的半尺寸整图回归；新增全部组合在 540px 图中直接取右下页脚进行定点解码，不放大、不使用 TRY_HARDER。长 PNG 也验证真实导出文件的页脚。
- 网页在获得浏览器使用确认后，检查 320/390px 手机与桌面、原文展开、图片链接、来源链接、极长文字、键盘焦点和无横向溢出。
- 原生代码、服务端模板与样式可以分别回退；生产服务器发布需确认并先备份，不触碰 SSH/代理或用户记录。

## 二维码验证的边界

二维码最终采用 208px（原为 248px），面积减小约 30%，保留 M 级纠错和至少 4 个模块的静区，摆放在偶数像素坐标。减小后，通用 ZXing 的整张长图快速搜索会跳过部分扫描行，扩展到全部组合的半尺寸整图搜索曾漏检；这不是二维码本身缺失。测试因此明确分别报告整图和定点识别，不能声称所有软件都能自动识别任意压缩后的长图。二维码专用 reader 保留默认搜索参数，避免把正文笔画误识别成一维条形码；没有用放大或 TRY_HARDER 隐藏结果。仍需要真实手机及常用分享软件的长按识别验收。

本轮不改 Windows 本机代码；更新校验脚本新增可选平台参数，默认依旧检查双端，本次只发布 Android 包。扫码页是跨平台网页，已随服务端 0.8.0 部署，历史有效分享链接也会采用新版样式。最终发布与线上检查见 [发布验证](share-editorial-1.19.0-verification.md)。

## Android 实现检查点

- 分享相关 4 个测试类共 63 项通过，0 失败、0 错误、0 跳过；覆盖 API 30/35、全部模块组合、授权/撤销/保存失败、实时勾选和 PNG/预览一致性。日志：`/tmp/mnote-share-native-final.log`。
- Android debug 构建、lint 通过（0 errors / 116 warnings）；独立任务 `mnote-share-native-final.service` 最终 inactive、Result=success、ExecMainStatus=0。这是分享子系统检查点，不代替发布前完整项目回归。
- 新版实际渲染：[预览界面](design/share-editorial/after/01-preview.png)、[想法与摘录](design/share-editorial/after/02-card-mixed.png)、[仅想法](design/share-editorial/after/03-card-thought.png)、[包含圈选与上下文截图](design/share-editorial/after/04-card-images.png)。截图全部使用合成记录；图中二维码没有发布为真实分享。
- 原图不被拉伸；窄图按原比例完整呈现，页面上下文继续按已有取景选择展示局部，不假定其首页顶部就是用户圈选位置。实体手机的分享软件压缩/长按扫码尚未验收。
- 更新校验脚本的 `--platform android` 已对线上现有 1.18 包做只读校验通过，不代表 1.19 已经上线。
- 此检查点之后已获浏览器及联合发布授权；最终完整回归和发布信息以对应发布验证文档为准。

## 扫码页实现与浏览器验收

1. **扫码阅读首屏，通过**：[390px 手机](design/share-editorial/after/05-web-mobile.png)、[320px 窄屏](design/share-editorial/after/05-web-narrow.png)、[桌面](design/share-editorial/after/05-web-desktop.png)。想法 22/26px 衬线、摘录 17/18px 浅底引用，品牌和说明降为次要信息；短分享没有多余导航。
2. **图片原图与来源，通过**：[来源与折叠原文](design/share-editorial/after/06-web-mobile-source.png)、[仅图片](design/share-editorial/after/08-web-image.png)、[仅来源](design/share-editorial/after/08-web-source.png)。桌面双图并排；预览不裁切原图，以 560px 最大高度控制纵向长度。原图 HTTP 跳转实测为 PNG；来源链接地址和安全属性与原快照一致。
3. **原文展开与收起，通过**：[展开状态](design/share-editorial/after/07-web-mobile-original.png)、[旧版原文/来源分享](design/share-editorial/after/08-web-legacy.png)。原生 details 不依赖 JavaScript，Space/Enter 可切换；完整原文未从 HTML 删除。第一次焦点截图显示展开按钮外框与下方说明过近，增加 8px 内距后复查。
4. **短内容状态，通过**：[仅想法](design/share-editorial/after/08-web-thought.png)、[仅摘录](design/share-editorial/after/08-web-excerpt.png)。内容自然收尾，不为填满屏幕放大字或加入无意义卡片。

`scripts/verify-share-page-browser.py` 在 320/390/768/1280px 四种宽度和六种内容组合中通过 24 项响应式检查；无横向溢出、无页面脚本异常、无外部请求。页内导航、原图链接、原文键盘焦点/切换、无 JavaScript 展开、长连续文字和长域名换行均验证。文字对比度：主文 8.94、标题 5.28、辅助文字 4.79、摘录 6.17，均高于 4.5。日志：`/tmp/mnote-1.19-browser-verification.log`。

证据边界：浏览器为本机无头 Chromium，记录全部合成；网页衬线字体使用本机字体回退，不同手机实际字形可能不同。没有把浏览器模拟当成微信/相册实机、Safari、TalkBack 或完整 WCAG 合规验收。来源按钮仅核对目标与安全属性，测试不访问外部原文网站。
