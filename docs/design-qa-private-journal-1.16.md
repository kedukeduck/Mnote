# Mnote 私人刊物 · Native Design QA

日期：2026-09-27。版本：Android / Windows `1.16.0-test`。直接改造既有 Android View / Win32 应用，不是网页原型。历史分享卡片验收保留在 `docs/design-qa-share-card-2026-09-25.md`。

## Source visual truth

参考根目录：`/root/.codex/generated_images/01a0530d-2b94-74b3-82b6-5454b2a4461e/`。

- 主稿：`exec-90c12568-7b2d-496e-88b4-379f484a2ee8.png`，1448 × 1086，三联板：全部记录、随手记、连续阅读。
- 筛选/多选/标签：`exec-94fafd14-b5a0-4435-8fe9-f5499bc48efa.png`。
- 设置/账号/更新：`exec-9bb84528-f43d-4c32-b500-d3a429b8c62f.png`。
- 分享：`exec-acdc410b-9cab-438e-bc48-4edf87c36a67.png`。
- Windows：`exec-73141406-f1e4-4ea1-803d-9b1ebed452e0.png`，1487 × 1058。
- 交互规格：`/root/.codex/visualizations/2026/08/30/01a0530d-2b94-74b3-82b6-5454b2a4461e/mnote-private-journal-2026-09-27/design-spec.md`。

## 实现截图、视口与状态

实现根目录：`/root/codex/Mnote/`。截图均来自生产 View / Win32 控件，不是重新制作的设计图。

- `app/build/ui-previews/journal-library.png`、`journal-compose.png`、`journal-reader.png`：390 × 844，全部记录、纯想法编辑、纯想法阅读。
- `journal-filter.png`：390 × 580 筛选窗口；`journal-multiselect.png`：390 × 844 原地多选。
- `style-a-image-detail.png`、`style-a-image-context.png`：截图附件和完整页面连续阅读；文件旧名称不表示仍使用旧布局。
- `quick-note-clipboard-material.png`、`quick-note-clipboard-keyboard.png`、`overlay-ime-visible.png`、`record-edit-keyboard.png`：素材、展开选项与键盘可用空间。
- `quick-note-large-text-keyboard-space.png`：360 × 400，缩小可用高度和放大字号；保存、正文及更多选项可达。
- `settings-preview.png`、`journal-account-login.png`、`journal-account-activation.png`、`journal-account-sync.png`、`journal-permissions.png`：390 × 844，未登录、首次激活、模拟同步冲突和权限入口。
- `journal-update.png`：390 × 844，发现更新但尚未下载；与设置/更新参考板同输入检查。参考是下载中状态，实际截图故意停在明确确认前，未伪造下载进度；版本 `1.17.0-test` 和微小包大小只属于测试 fixture，不代表真实发布。
- `share-card-full-preview.png`、`share-gallery-preview.png`、`share-gallery-small.png`、`markdown-export-small.png`：分享预览、直接图文历史与小屏导出。
- `desktop-windows/build-gui-smoke/journal-library.png`、`journal-multiselect.png`：1240 × 900，实际 Win32 窗口。

Android 使用 Robolectric native graphics / Skia，mdpi，像素与测试 View 的 dp 为 1:1；不是实体设备截图，不包含真实输入法。小屏测试通过减少可用窗口高度模拟输入法空间。Windows 使用 Wine/Xvfb 按窗口像素采集；外围 Wine 窗口装饰不参与布局判断。CSS viewport 不适用于这些原生实现。

## 比较证据与归一化

多轮将源图与实现截图置于**同一次图像检查输入**，覆盖主稿、筛选多选、账号设置、分享及 Windows。没有把分别浏览的历史图片冒充并排对比。按参考板中的单页内容宽度归一化，不将整个三联板与单张手机截图直接做像素比较。

纯想法测试没有素材，留白和首屏内容量自然不同；带附件阅读另行检查。山水照片是设计示例，不能硬编码到用户记录里；实现测试图片来自合成页面，内容、比例、时间不与示例逐字逐像素比较。Windows 使用英文合成记录及临时账号，不访问个人笔记。

全视图足以清楚辨认标题、正文、按钮、边界和细线，不需要重复局部裁切图。长文、图片放大及滚动行为另有自动化测试。

## 发现、修复与复查历史

1. **P1，已修复：保存文字与按钮同色。** 旧 `coral` 前景随换色失去对比。改为主要按钮白色文字及独立禁用色；重新检查 `quick-note.png`、`journal-compose.png`，保存清晰可见。
2. **P2，已修复：小屏输入框最小高度挤压更多选项。** 改为按剩余空间在 100–220dp 内调整；复查 360 × 400 的 `quick-note-large-text-keyboard-space.png`，正文及持续操作可达。
3. **P2，已修复：多选数量在底部、勾选单独占行。** 数量/全选/取消固定顶部，删除/导出留在底部；勾选移到正文左侧，选中色不染日期。修改前后均与参考板同输入比较，最终 `journal-multiselect.png` 更紧凑且操作区完整。
4. **P2，已修复：筛选缺少标签搜索与显式取消。** 补充搜索、取消和搜索重置；搜索只改变候选标签，不擅自改变当前筛选。复查 `journal-filter.png`，类型、搜索、标签、确认可辨认。
5. **P2，已修复：Windows 空列表底色、品牌字体、摘录标识不一致。** 空白区统一暖白；Georgia 品牌在 Wine 配置 serif fallback；摘录使用绿标签及浅素材底色。重新与 Windows 稿比较最新 `journal-library.png`，三栏、选择色、层级和页边距统一。
6. **P2，已修复：纯想法详情有空素材分隔。** 没有素材时不重复画分隔线。仅页面上下文的截图也不再重复展示为圈选截图，不推断摘录位置。

检查同时促成了焦点恢复及隐藏字段校验修复：重组 View 后恢复输入焦点；保存时发现收起的摘录/原文无效，会展开并定位字段。API 26 保留安全导航栏配置，浅色导航栏仅在既有 v27 资源启用；没有添加 lint 基线。

## 五项必查视觉表面

- **字体：** serif Mnote 与系统中文无衬线分工；想法 18sp、正文约 16sp、辅助文字弱化。系统字形与图像稿不是同一字库，不声称逐像素相同；Windows fallback 明确记录。
- **间距与布局：** Android 20–22dp 页边距、平铺记录、日期章节、小缩略图；标签只在筛选里，详情不无提示截断原文。Windows 为侧栏/列表/阅读；窄窗保留列表和编辑入口。
- **色彩：** 暖白 `#F7F4EC`、正文 `#242B2B`、墨绿 `#24494E`、铜色 `#955530`、辅助色 `#666C66`。危险动作独立红色，以细线和浅素材块组织内容。
- **图像：** 使用真实记录附件，缺失不伪造。首页缩略图可裁切；详情完整缩放并能放大。上下文圈选边框不写入源文件。测试低分辨率截图的软化不误判为压缩缺陷。
- **文案：** 本机保存、联网同步、公开分享明确区分；预览不发布，最终动作写明“生成链接并保存到相册”。未登录、冲突、权限、删除及更新展示真实状态。

## 可接受差异及真机缺口

原生复选框、文字导航、Win32 窗口边框保留平台行为，不复刻图像稿的每个图标。纸纹与微动效为 P3 润色。分享图片本身继续使用之前确认的纸张模板；本轮统一操作页，不擅自改变内容规则。

未做实体 Android/Windows 验收；OEM 输入法、系统无障碍授权、相册、Windows 高 DPI/多显示器仍需真机确认。当前内置浏览器不可用，尚未收到外部自动化浏览器的选择答复，故已撤回本轮尝试的网页配色改动，扫码网页保持原样。不把原生测试当成网页或生产部署验证。

## 核对清单

- [x] 遵循第三张「私人刊物」及已确认扩展页，没有另换设计方向。
- [x] 同输入比较参考与实际原生实现，检查字体、空间、颜色、图片、文案。
- [x] 修复上述 P1/P2，复查对应修改后的画面。
- [x] 首屏、多选、筛选、记录、账号、设置、分享保持统一层级。
- [x] 明确合成内容、平台差异与真机测试缺口。

原生视觉范围无剩余可操作的 P0/P1/P2；功能和安装包验证另见 `docs/private-journal-1.16.0-verification.md`。

final result: passed
