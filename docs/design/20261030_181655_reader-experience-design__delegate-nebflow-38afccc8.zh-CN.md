---
type: design
status: draft
title: 阅读器体验设计批——EPUB 根治 / 操控对标 / 格式矩阵 / 统一内核 / PDF 大文件 / 查看面留白（只设计不实施）
date: 2026-10-30
batch: delegate-nebflow-38afccc8
tags: [reader, epub, pdf, viewer, mobile-ux, design-spec]
related:
  - "[[20261028_canvas-ask-mode-design.zh-CN]]"
---

# 阅读器体验设计批（作者令 2026-10-30 18:10）

> 本件为**设计规格书（防线 1 · 设计先行）**，只设计不实施。呈作者拍板后另批落地（防线 2）。
> 结构遵循 design-spec 模板 §0-9，七大议题分章承载；每章给出：根因/依据 → 方案 → 取舍 → 可断言点。

## §0 一句话目标

把「能打开」的文件查看器升级为「读得舒服」的统一阅读器：EPUB 图片根治 + 主题可读、操控对标主流电子书阅读器、epub/mobi/azw3/pdf/txt/md 分级支持、类纸质书翻页与标注体系、PDF 解除 100MB 上限但零大资源消耗、各查看面顶部防胶囊遮挡。

## §1 参考与依据

| 来源 | 提炼规则 | 链接 |
|---|---|---|
| Apple Books（产品行为观察） | 点左/右 1/3 翻页、点中间唤菜单、滑动翻页、进度条带章节刻度、菜单含目录/字号/主题 | https://www.apple.com/apple-books/ |
| 微信读书（产品行为观察） | 上下滑动切章节、长按选词做笔记、底部进度条可拖、阅读菜单半透明浮层不遮正文 | https://weread.qq.com/ |
| Kindle（产品行为观察） | 页码「x / y 总页数」、标注落「我的剪贴/笔记」列表、跨端同步 | https://www.amazon.com/kindle |
| Apple HIG · Layout（safe area） | 内容不进入浮层控件占据带；`env(safe-area-inset-*)` 既有项目惯例（mobileLayer.css 全链在用） | https://developer.apple.com/design/human-interface-guidelines/layout |
| Apple HIG · Dark Mode | 暗色下正文对比度 ≥4.5:1（WCAG AA）；图片不强制反色 | https://developer.apple.com/design/human-interface-guidelines/dark-mode |
| EPUB 3.2 spec（W3C） | spine 为阅读序、manifest 为资源全集、资源路径相对 OPF 解析且须 URL-decode | https://www.w3.org/TR/epub-32/ |
| MOBI/KF8 格式事实 | mobi = PalmDB 记录容器 + PalmDOC LZ77 压缩 + MOBI 头（纯前端可解，零新依赖）；azw3 = KF8，内含边界标记 + 完整 EPUB 包（zip） | https://wiki.mobileread.com/wiki/MOBI · https://wiki.mobileread.com/wiki/KF8 |
| pdf.js 官方范式 | `getDocument({url})` 自带 Range 分片取数；`page.render` 视口驱动 + `page.cleanup()` 释放；官方 viewer 即 IntersectionObserver 懒渲染 | https://mozilla.github.io/pdf.js/ |
| 项目视觉铁律（visual-style，最高优先级） | 低调克制专业感；弹窗禁 overlay 但面板必须毛玻璃；零新 token 纪律（复用 `--glass-*` / `--color-*` / `--cv-*`） | 插件 `frontend-dev` skills/visual-style |
| 项目暗色面板事实 | 面板有亮/暗两档（`--color-text` / `--color-surface` 等 token 已随面板主题切换） | src/main/resources/web/css/split.css |
| 设计案例库 | 案例 001 共性：断言口径（i18n key / 显式状态语义 / 增量基线）写规格书时锁死 | 插件 `frontend-dev` skills/design-system |

**现状代码锚（侦察结论，设计依据）：**

- EPUB viewer：`src/main/resources/web/js/viewers/epub.js`——ZIP 自解析（`shared.js::parseZipEntries/decompressZipEntry`，预算族 `ZIP_BUDGET` 单 entry 32MB / 总量 128MB，`shared.js:972-976`）；章节 = 取 `<body>` innerHTML **直接注入应用页面 div**（`epub.js:162` `content.innerHTML = chapters[idx].content`）；翻页 = 视口 scrollTop 按视口高度整页跳（`epub.js:169-184`）；底栏 = prev / `Ch x/y · Pg a/b` / next（`epub.js:112-116`）。
- **图片不显示根因（初判，实施批须先取证固化）**：① 章节 HTML 中的相对资源（`<img src="images/pic.jpg">`、CSS `url(...)`）在应用页基 URL 下解析 → 404；② `epub.js:80` 路径拼接 `(opfDir + item.href)` **未做 URL-decode**（OPF href 中 `%20`/中文名资源命中不到 zip entry）；③ `../` 归一用一句 regex，非健壮路径归一；④ manifest 外资源（部分书 spine 引 `OEBPS/...` 裸路径）无回查；⑤ 全书图片从未从 ZIP 取出（只取了 spine 文本），故图片 100% 不可见。
- **配色看不清根因（初判）**：注入内容继承应用面板 token，但书内硬编码色（inline `style="color:#333"`、章节内 `<style>` 块——当前实现连 `<head>` 里的 `<style>` 都丢了）与面板暗底冲突；无主题档位；字体/行距/段距无档位。
- PDF viewer：`viewers/pdf.js`——pdf.js UMD 懒加载（`pdf.js:17-32`），`getDocument({url})` 走 `nfViewerUrl`（已支持 Range），**但 `renderAll()`（`pdf.js:112-156`）一次性顺序渲染全部页 canvas**，无懒渲染、无 `page.cleanup()`。
- 100MB 打开闸：`TextStreamSearch.MaxPopReadFileBytes = 100MB`（`TextStreamSearch.scala:277`；WS readFile/pop.readFile 共用，`WsFileOpsHandlers.scala:602`），前端镜像 `attachmentPreview.js:52 MAX_TEXT_BYTES`——管「一件附件能不能进页面打开」。附件总闸 `AttachContract.MaxFileBytes = 1 GiB`（`AttachContract.scala:35`）。附件字节直显预算 `MAX_INLINE_IMAGE_BYTES = 10MB` 刻意解耦（`attachmentPreview.js:54-65`，**本批不碰**）。
- 底部控件现状（作者截图）：左返回钮 / 中「Ch 5/101 · Pg 2/9」/ 右刷新钮；手机端悬浮消息钮（`ask-fab`，`askDock.js`）叠内容右下（`ask.css:64-69`）。
- 胶囊（头像球）：canvas 卡头胶囊环 `#canvas-capsule-ring`（`split.css:1384-1490`，fixed 浮层、z-246）；手机层 canvas 独立页、桌面卡头在 mobileLayer 退场（`mobileLayer.css:1013-1020`）。
- 格式矩阵现状：`FileTypeRegistry.BuiltIn`（`FileTypeRegistry.scala:37-84`）有 epub / pdf / txt(md 走 markdown) ，**无 mobi / azw3**；`epub-page-change` 事件已派发（`epub.js:137`）但无消费方（记忆/追踪待接，本批不强制）。

---

## 一、EPUB 渲染根治

### 1.1 图片不显示——根因树与根治方案

**根因树**（实施批第一步必须用 2-3 本真实书固化取证，含图片在 `OEBPS/images/`、href 带 `%20`、spine 外引用三类）：

```
R1 相对基 URL 错误      章节 innerHTML 注入应用页 div，相对 src 对页面 URL 解析 → 404
R2 href 未 URL-decode   zip entry 名是解码后的，OPF href 是编码后的 → 命中不到
R3 路径归一不健壮       一句 regex 处理 ../，多级/反斜杠/纯文件名场景失守
R4 spine 外资源无回查   图片不在 manifest 或被裸路径引用 → 直接丢弃
R5 资源从未解压         全书只解压 spine 文本，图片/字体/CSS 一律没出 ZIP
```

**方案（渲染前改写，零 iframe、零新依赖）**：

1. **解析期建资源索引**：`manifest` 遍历同时建两份映射——`id → {href(原始), mediaType}`（spine 序用）与 **`href 归一形 → zip entry 名`**（资源回查用）。归一形 = `decodeURIComponent(href)` 后相对 OPF 目录做标准路径归一（`/` 分隔、`.`/`..` 段展开、去重斜杠）。
2. **章节 DOM 化改写**：不再存 innerHTML 字符串，解析时即 `DOMParser` 成 document，对每个资源引用属性（`img[src]`、`image[xlink:href]`、`[srcset]`、`link[href]`、`source[src]`、`audio/video[src]`、内联 `style` 的 `url(...)`）：取原始值 → 相对章节路径归一 → 查资源索引 → 命中则 `decompressZipEntry` 取出（受 `ZIP_BUDGET` 既有预算族约束）→ 按 mediaType 生成 `blob:` object URL（同书缓存，关闭/切书统一 revoke）→ 回写属性为 object URL。未命中 → 给该节点打 `data-nf-res-miss` 标记，**不阻塞渲染**，章节加载完成后在底栏旁以一次性 toast 报「N 个资源缺失」（可见降级纪律）。
3. **图片体积闸**：单图 > 8MB 不解压为 object URL，回写 `data-nf-res-too-big` + 占位框（可见降级）；全书解压资源总量沿用 `ZIP_BUDGET.maxTotalBytes`（128MB），图片字节计入同一账本（WeakMap 已按 source buffer 记账，`shared.js:978-986` 天然兼容）。
4. **样式保真与隔离**：章节 `<head>` 内 `<style>` 与 `link[rel=stylesheet]`（在 manifest 内）提取、资源改写后**注入 scoped 容器**（`.epub-reader-content` 下生效），恢复书内排版（这也是「配色/排版不对」的另一半根因）；脚本维持既有剥离（安全口径不动，`epub.js:86` 同纪律）。

### 1.2 主题体系（配色可看清）

**三主题档位**：`纸质`（米白底 #F7F3EA / 深棕墨 #3B342C）·`明亮`（跟随面板亮 token `--color-surface`/`--color-text`）·`暗夜`（跟随面板暗 token）。默认 = **跟随面板明暗**（面板暗则暗夜，面板亮则纸质——纸质为阅读器类的成熟默认）。主题切换钮常驻阅读菜单。

**对比度硬指标（可断言）**：

| 主题 | 底 | 文 | 对比度 |
|---|---|---|---|
| 纸质 | #F7F3EA | #3B342C | ≈11.5:1 |
| 明亮 | `--color-surface` | `--color-text` | 面板 token 已达标（既有口径） |
| 暗夜 | `--color-surface`（暗） | `--color-text`（暗） | ≥4.5:1（WCAG AA） |

**书内硬编码色冲突治理（「看不清」的另一半）**：章节 `<style>` 提取后做一遍**只读清洗 pass**——对 `color/background/background-color/-webkit-text-fill-color` 四条属性，凡值是具体色值（非 `inherit/none/currentColor`）→ 删除该声明（让位主题层）；`background` 图片类声明保留。inline `style` 同上四属性删除。字体、字号、行距、对齐、斜体等排版声明**全保留**（保真优先）。取舍理由：EPUB 阅读器全行业口径（Apple Books / 微信读书）都是「书内排版保留、颜色归主题管」；保留书内色在暗面板下必然不可读。

**字体/行距/段距档位**（阅读菜单「Aa」面板）：

| 项 | 档位 | 默认 |
|---|---|---|
| 字号 | 14 / 16 / 18 / 20 / 22 px | 16 |
| 行距 | 1.5 / 1.6 / 1.8 / 2.0 | 1.8 |
| 段距 | 紧凑（0.5em）/ 标准（1em）/ 宽松（1.5em） | 标准 |
| 字族 | 系统衬线（Georgia/Songti 系，沿用 `split.css:2115` 既定族）/ 无衬线 | 衬线 |

档位存 `localStorage`（key：`nf.reader.prefs`），跨书跨会话生效；改档即重排（无动画）。

---

## 二、操控逻辑（对标主流阅读器）

### 2.1 交互集归纳（Apple Books / 微信读书 / Kindle 共交集）

| 交互 | 桌面 | 移动 | 采纳 |
|---|---|---|---|
| 点左 1/3 / 右 1/3 | 上/下一页 | 同 | ✅（现状是 1/2 切分，**改 1/3-1/3-1/3**） |
| 点中间 1/3 | 唤阅读菜单 | 同 | ✅（新增；现状中间点也翻页 = 作者指「操控逻辑不对」之一） |
| 水平滑动 | — | 翻页 | ✅（touch 横滑整页翻） |
| 垂直滑动/滚轮 | 滚动阅读 | 滚动阅读（微信读书式：到章尾续滑进下章） | ✅ |
| 长按/划选文本 | 唤标注菜单 | 同 | ✅（标注见 §4） |
| 进度条拖动 | ✅ | ✅ | ✅（见 2.2 底栏重排） |
| 章节跳转（目录抽屉） | ✅ | ✅（底部弹出） | ✅（解析 OPF NCX / nav.xhtml 出 TOC） |
| 物理键盘 ←/→/Space | ✅ | — | ✅（现状已有，保留） |
| 双栏排版（宽视口） | 可选 | — | ⏸ 二期候选，本批不做（防增额） |

**文本选择与翻页点击的冲突治理**：现状点击翻页在选择文本后仍触发（虽有 `getSelection` 守卫，`epub.js:190-196`，但中间区点按唤菜单是新增面）。裁定：有选区时点按**不翻页不唤菜单**（维持现状守卫）；唤菜单后点正文任意处收菜单（不翻页——菜单开时正文点按只收菜单，这是 Apple Books 口径）。

### 2.2 底部控件布局重排

**现状问题**：返回钮 / 进度文本 / 刷新钮 一字排开 + 手机悬浮消息钮叠右下内容。

**重排（统一阅读底栏，epub/txt/md/mobi/azw3 共用件）**：

```
桌面（≥769px）：  [‹ ›]  [══════ 进度条（带章节刻度）══════]  [Pg 12/368 · Ch 3/20]  [Aa] [☰目录] [⋯]
移动端（≤768px）：[‹]   [══════ 进度条 ══════]                [12/368]              [Aa] [☰]  [⋯]
```

- **进度条**（slider）取代纯文本：拖动跳全书位置（跨章跳章）；条上叠加章节刻度线（来自 TOC，密度过高时抽样显示）；常态显示当前位置 tooltip「第三章 · 35%」。
- **翻页钮**：移动端收成左侧单 `‹`（下一页交给点右区/滑动），桌面保留 `‹ ›` 对。触控热区 ≥40×40px。
- **刷新钮退出底栏**：刷新语义属于「文件变了重读」（`canvas-refresh-toggle` 是面板 chrome 件），不 belongs to 阅读器底栏——移动端收入 `⋯` 溢出菜单，桌面维持面板 chrome 原位（`shared.js:940-958` 不动）。
- **返回钮**：维持面板/页面导航原语义，位置不动；仅移动端底栏高度让位 `env(safe-area-inset-bottom)`（项目既有惯例）。
- **悬浮消息钮（`ask-fab`）冲突**：阅读菜单（中间唤出）打开时，`ask-fab` 与角落圆钮簇让位隐藏（`askDock.js` 已有 `gate-off`/`--ask-fab-bottom` 让位机制，新增「阅读菜单开 ⇒ gate-off」一条联动即可）；底栏自身不与 fab 重叠（底栏在文档流内，fab 浮层——现状压内容的就是 fab 浮动性，阅读态让位后解决）。

### 2.3 阅读菜单（中间点唤出）

毛玻璃浮层（铁律：禁 overlay 暗化，面板 `--glass-bg` + blur）锚定视口顶部：章节名 + 目录入口 / 主题三档 / Aa 档位 / 进度百分比输入跳转 / 刷新（移动）/ 关闭。菜单开时正文点按只收菜单（§2.1）。

---

## 三、格式矩阵

### 3.1 支持分级

| 格式 | 级别 | 路线 | 说明 |
|---|---|---|---|
| .epub | **原生渲染** | 现有 ZIP 管线 + §1 根治 | spine/manifest 全量支持 |
| .pdf | **原生渲染** | 现有 pdf.js 管线 + §5 懒渲染 | Range 分片已有 |
| .txt | **转换渲染** | 大文本 → 统一内核分页（复用 `largeText.js` 分块读取能力，外壳换统一阅读器 chrome） | 统一主题/翻页/进度条 |
| .md | **转换渲染** | 现有 markdown 渲染 → 包统一内核 chrome（正文渲染不动） | 先统一操控，排版二期 |
| .mobi | **转换渲染** | **纯前端 PalmDOC/MOBI 解析**（零新依赖，见 3.2） | 覆盖未加密 mobi（含 KF7）；加密书降级「仅下载」 |
| .azw3 | **转换渲染** | KF8 内嵌 EPUB 抽取（零新依赖，见 3.2） | 抽 EPUB 边界后**全量复用 EPUB 渲染管线** |
| .azw / 加密 mobi / djvu / cbz | **仅下载** | 不给解析，附件卡/文件行提供下载 | 加密/低频，防增额 |

### 3.2 mobi / azw3 解析路线选型（零新依赖优先）

**选型对比**：

| 路线 | 依赖 | 体积 | 判定 |
|---|---|---|---|
| A. 纯前端自实现 PalmDOC + MOBI/KF8 解析 | 零 | ~400-600 行（PalmDB 记录表 + LZ77 解压 + MOBI 头定位 + KF8 边界扫描） | ✅ **选定** |
| B. 引入 ebook-convert/Calibre 系（服务端转换） | 外部二进制 + 进程面 | 重 | ❌ 新增部署面 |
| C. 引入 JS 转换库（如 epub.js / kindle 解析 npm 包） | npm 依赖 | 中 | ❌ 与「零新重依赖优先」令冲突 |

**mobi 路线（A）**：PalmDB 头（0x4D4F4249 'MOBI' 不在头——是 'BOOKMOBI' 于 record 0）→ 记录表定位 → record 0 起 PalmDOC LZ77 解压出 HTML 流 → MOBI 头（exth 记录）提取书名/作者/目录（INDX 太复杂则降级「无目录」）→ HTML 清洗（去内嵌 binary 标记）→ 进统一内核当单书流（按 `<mbp:pagebreak>`/`<h2>` 切段为伪章节）。加密检测（encryption type ≠ 0）→ 仅下载。
**azw3 路线（A）**：文件 = MOBI 前缀 + `BOUNDARY` 标记 + 标准 zip（KF8）。扫描 `PK\x03\x04` 边界 → 切出 zip 字节 → **直接喂现有 EPUB viewer**（现有管线零改动复用）。

---

## 四、统一阅读内核

### 4.1 类纸质书翻页动效（纯文本风格动画纪律）

- **翻页动画**：整页内容容器 `transform: translateX(±100%)` 双页夹（现页左移出 / 下页右移入），时长 **240ms**，缓动 `cubic-bezier(0.22, 0.61, 0.36, 1)`（项目既有温和缓出族，`split.css:1439` 同族）；页缘 8px 宽投影随位移线性衰减（模拟纸页遮挡，纯 CSS 渐变，无图片资源）。
- **跟随手势**：touch 横滑时页随手指位移（`transform` 跟手），释放时速率判定：|v| > 0.3px/ms 或位移 > 1/4 页宽 → 完成翻页（240ms 缓出收束），否则回弹（180ms）。
- **纪律**：纯文本/排版内容动画，禁 canvas 粒子、禁 3D 翻书（克制基调 + 移动端 GPU 成本）；**一次只动两页**（现/下），N 连翻 = 队列逐次执行，不叠层。
- **prefers-reduced-motion**：全程降级为 0.01s 直切（项目既有降级口径，`split.css:1448-1453` 同族）。
- **滚轮/触控板**：保持滚动阅读（不用翻页动画）；翻页动画只服务点按与横滑。
- **翻页模式开关**：`翻页模式`（点按/横滑 + 动画）/ `滚动模式`（现状行为，默认）。设置存 `nf.reader.prefs`。

### 4.2 标注 / 做笔记（高亮 + 批注）

**推荐：引擎通用 annotation 面（新通用件），电子书库项目作消费方——不接项目卡片。**

理由：① 标注是**跨格式**诉求（epub/txt/md/pdf 全都要），落电子书库卡片 = 把它绑死在单项目语境，别的项目读书即断链；② 引擎已有跨面通用件的成熟先例（附件预览、文件查看器都是引擎级）；③ 电子书库项目未来以「卡片 ↔ annotation 查询」方式消费通用面，两边都不受损。

**数据面（设计，非实现）**：

```
annotation = {
  id, bookRef(文件路径锚), format,
  spine: { spineId | chapterIdx },        // epub/mobi：spine 项
  locator: { quote(选文前 60 字), offset?, page? },  // txt/md：quote+模糊偏移；pdf：page+矩形
  color: 黄/绿/蓝/粉 4 色,
  note: 批注文本（可空 = 纯高亮）,
  createdAt, updatedAt
}
```

- **定位鲁棒性**：quote 为主键 + offset 兜底重定位（书文件变了 quote 失配 → 按 offset 模糊找回，再失配 → 标注灰显不丢数据）。
- **存储面**：随工作区落库（具体载体实现批定夺：wiki 页 or json 件，原则=跟人走、可备份、零服务端迁移）。
- **UI**：划选唤出浮动小条（四色 + 「写笔记」）；阅读菜单内「笔记列表」抽屉（按章分组，点击回跳定位）；高亮回渲于重开本/跳章时。
- **PDF 标注**：二期（pdf.js 文本层选区取矩形是另一摊事，本批只 epub 系格式落标注，pdf 先只看不标——防增额）。

---

## 五、PDF 大文件：解除 100MB 上限 + 聪明加载

### 5.1 闸门定案

| 闸 | 现状 | 定案 | 理由 |
|---|---|---|---|
| 附件总闸 `AttachContract.MaxFileBytes` | 1 GiB | 不动 | 已到位 |
| **文件打开闸** `MaxPopReadFileBytes`（`TextStreamSearch.scala:277` / 前端 `MAX_TEXT_BYTES` `attachmentPreview.js:52`） | 100MB，文本/二进制**共用** | **拆分**：文本路由维持 100MB；**二进制查看路由（nf-file 族）解除到 1 GiB 与附件闸对齐** | 100MB 卡的就是「PDF 能不能打开」；二进制走 Range 流，不整件进内存，解除无资源代价 |
| 前端打开闸镜像 | `MAX_TEXT_BYTES` 一值两用 | 镜像拆分为 `MAX_TEXT_BYTES`（文本，不动）/ `MAX_VIEWER_BYTES`（= `AttachContract.MaxFileBytes`） | 禁第二把尺纪律 = 两端同源同值，但文本/二进制分两闸 |
| 字节直显预算 `MAX_INLINE_IMAGE_BYTES` | 10MB | **不碰**（`attachmentPreview.js:54-65` 作者既有裁定） | 越面 |

**解除 ≠ 全量加载**。以下护栏让 1 GiB PDF 的资源曲线与 10MB PDF 等价：

### 5.2 懒渲染（核心聪明方案）

现状 `renderAll()`（`pdf.js:112-156`）开书即顺序渲染**全部页** —— 这是大文件唯一真瓶颈（内存 + 首屏时延）。定案：

1. **视口驱动渲染**：每页占位 canvas 持 `data-page`；`IntersectionObserver`（root = `.pdf-pages`，rootMargin = ±2 屏）命中 → 入渲染队列，离开 → `page.cleanup()` 释放（pdf.js 官方 viewer 同范式）。首屏只渲可见 ±2 页。
2. **渲染队列**：按「视口距离」优先级排序，并发上限 2；`renderToken` 失效模式沿用（`pdf.js:112-116` 现有竞态纪律）。
3. **页尺寸预占**：canvas 无 CSS 高度会导致滚动跳变——开书时先 `getPage` 遍历取各页 `view` 尺寸（只读元数据，不渲染），设 `style.width/height` 预占布局；大文件此遍历一次过（页数 > 2000 时抽样 50 页 + 均高估算，加载中允许 ±5% 跳动）。
4. **缩放重渲**：缩放到新档位只重渲**当前可见 ±2 页**，离屏页在下次入视口时按新 scale 补渲（沿用 120ms debounce，`pdf.js:169-193`）。
5. **预取边界**：pdf.js 网络层自身按 Range 取数；前端只保证「当前页 ±5 页的数据可用」（pdf.js 内部 page cache 自然覆盖），**不主动整卷预取**。

### 5.3 资源消耗护栏（可断言）

| 护栏 | 值 | 断言法 |
|---|---|---|
| 常驻已渲染页 | ≤ 6 页 canvas 有位图 | DOM 计数 |
| 单页渲染并发 | ≤ 2 | 代码审 / 队列断言 |
| 首屏可交互 | 1 GiB 稀疏测试 PDF（50 页）≤ 3s 出首屏 | QA 计时 |
| JVM 侧 | nf-file 路由全程流式/Range，零整件堆读（`WsFileOpsHandlers.scala:610-611` 既有注释即此意） | 既有口径回归 |
| 解压/渲染失败 | 沿用「重试一次 + 占位可见降级」（`pdf.js:127-153` 现有纪律） | 既有断言 |

---

## 六、代码查看器顶部留白（防胶囊遮挡）

**问题**：canvas 卡头胶囊（含头像球/环，`#canvas-capsule-ring` z-246 浮层）叠在卡头带上方；文档查看面（代码/monaco、markdown、图片）内容顶行与胶囊浮层同带，首行内容/面包屑被压。

**safe-area 定案（复用项目既有 safe-area 惯例，零新 token）**：

| 面 | 方案 | 值 |
|---|---|---|
| 代码查看面（monaco 及代码类 viewer） | 内容容器 `padding-top` 增量 | `calc(var(--capsule-h, 36px) + 16px)` |
| markdown / 文本查看面 | 同左（统一查看面顶垫 token） | 同上 |
| 图片查看面 | 同左 | 同上 |
| 阅读器（本批新底栏） | 顶垫给卡头；底栏高度含 `env(safe-area-inset-bottom)` | 顶垫同左；底 `calc(56px + env(safe-area-inset-bottom, 0px))` |

- **实现形态**：一个查看面共享类（如 `.canvas-viewer-safe-top`）挂统一 padding，四个面各自引用——**禁四处各写数值**（单源纪律）。
- **桌面与移动区分**：桌面卡头胶囊常态在卡头带内（`split.css:1949` 既有叠法，压的是卡头不是内容）——顶垫主要收益在**手机层**（canvas 独立页 chip 条 + 工具条形态）与卡头收起/环展开态；桌面常态不额外垫（避免白洞），环展开态（`.open`）由 JS 给当前查看面加垫 240ms（与环过渡同长，收起即撤）。
- 顶垫**不进入滚动内容**（padding 而非 margin，滚动到顶仍留垫）——防的是浮层遮挡，不是文档流排版。

---

## 七、分期与验收

### 7.1 分期

| 期 | 内容 | 依赖 |
|---|---|---|
| **P0 止血** | EPUB 图片根治（§1.1）+ 主题三档与硬编码色清洗（§1.2 对比度硬指标）+ 中间唤菜单 / 1/3 点按分区（§2.1）+ 查看面顶部留白（§6） | 无 |
| **P1 统一** | 底栏重排 + 进度条 + TOC（§2.2/2.3）+ 统一内核 chrome 套 txt/md（§3.1）+ 翻页动效（§4.1） | P0 |
| **P2 大文件** | PDF 闸拆分 + 懒渲染 + 护栏（§5） | P0（可并行 P1） |
| **P3 生态** | mobi/azw3 解析（§3.2）+ 标注/笔记（§4.2） | P1（内核 chrome 就位后） |

### 7.2 验收表（可断言点，供 QA 转 Playwright；口径此件锁死）

| # | 断言 | 二值判据 | 截图 |
|---|---|---|---|
| A1 | EPUB 图片可见 | 测试书（含 `OEBPS/images/` 相对引用 + href 带 `%20`）打开后 `img[src^="blob:"]` 计数 = 该书图片数，0 个 404 网络请求 | 是 |
| A2 | 资源缺失可见降级 | 构造 spine 外引用书 → 出现「N 个资源缺失」toast，正文其余正常 | 是 |
| A3 | 暗色对比度 | 暗夜主题下章节正文 computed color/background 对比度 ≥4.5:1；书内 inline `style="color:#333"` 被清洗（computed 值为主题色） | 否（computed 断言） |
| A4 | 点按分区 | 视口左 1/3 点按 → 上一页；右 1/3 → 下一页；中间 1/3 → 菜单开；菜单开时点正文 → 仅关菜单不翻页 | 是 |
| A5 | 底栏布局 | 移动端底栏含且仅含：返回 / 进度条 slider / 页码文本 / Aa / 目录 / ⋯；刷新钮不在底栏直显位 | 是 |
| A6 | 进度条跳章 | 拖进度条至 >50% → 当前章变化，`epub-page-change` 事件 progress 单调 | 否 |
| A7 | PDF 懒渲染 | 100 页测试 PDF：首屏后 `canvas.pdf-page` 有位图数 ≤6，离屏页无 `width` 属性位图 | 否 |
| A8 | PDF 大文件闸 | 100MB+1 的测试 PDF 可打开（走 nf-file 族）；100MB+1 的**文本**件仍被文本闸拒（两门各归各位） | 否 |
| A9 | 查看面顶垫 | 手机层打开代码/md/图片查看面：内容容器 `padding-top ≥ calc(36px + 16px)`；胶囊浮层盒与首行内容盒零重叠（矩形断言） | 是 |
| A10 | reduced-motion | `prefers-reduced-motion` 下翻页动画时长 ≤0.01s | 否 |
| A11 | 档位持久化 | 改字号/主题 → 重开面板 → 档位保持（localStorage `nf.reader.prefs`） | 否 |
| A12 | mobi/azw3 | 测试 .mobi（未加密）与 .azw3 打开出正文与目录；加密 .mobi 落「仅下载」降级 | 是 |

### 7.3 明确不做（防增额边界）

- 不做双栏自适应排版（二期候选）；PDF 标注（P3 后另批）；翻页动效 3D 化；电子书库项目卡片本批不动；`MAX_INLINE_IMAGE_BYTES` 直显预算不碰；EPUB Fixed-Layout（漫画式固定版）不专门支持（按普通流式渲，版式失真可接受——鲜叶）。

## §8 可断言验收点汇总

见 §7.2 表 A1-A12（≥5 条二值判断，截图列已标；文案类断言一律走 i18n key——「资源缺失」toast、底栏 aria-label、菜单项 label 全部锁 `t()` key，key 名实施批回报后回填本表）。

## §9 参考链接

**关联文档**：[[20261028_canvas-ask-mode-design.zh-CN]]（canvas 独立页语境——本批 §6 顶垫与 §2.2 底栏的手机层形态承接其页内 chip 条/工具条结构）；[[20261030_merge-lane-state-machine.zh-CN]]（本件同批姊妹件，评审流参照）。

- Apple Books / 微信读书 / Kindle（产品行为观察，见 §1 表）
- EPUB 3.2：https://www.w3.org/TR/epub-32/
- MOBI：https://wiki.mobileread.com/wiki/MOBI · KF8：https://wiki.mobileread.com/wiki/KF8 · PalmDOC：https://wiki.mobileread.com/wiki/PalmDOC
- pdf.js 懒渲染范式：https://mozilla.github.io/pdf.js/examples/ · https://github.com/mozilla/pdf.js/wiki/Frequently-Asked-Questions#range
- WCAG 对比度：https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html
- Apple HIG safe area / dark mode（见 §1 表）
- 代码锚：viewers/epub.js · viewers/pdf.js · viewers/shared.js · attachmentPreview.js · css/split.css（epub-reader 段 2110-2200 / capsule-ring 段 1384-1490）· css/mobileLayer.css · scala TextStreamSearch.scala:277 · AttachContract.scala:35 · FileTypeRegistry.scala:37-84
