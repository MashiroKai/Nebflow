# 任务列表三处显示缺陷修复（tasklist-display-fixes 批，2026-10-19）

作者截图（uploads/d7c3cb54…/346043258666041_截屏2026-10-19 15.24.52.jpg）点出三处：
① stderr 碎片泄漏进列表行；② 「8 tasks」居中悬置不对、应靠边；③ Z/codex 执行器
图标与状态环、标题挤在标题行里位置不对。本批只动任务列表面板显示层（taskList.js /
taskList.css + 两件 spec 翻转）；状态不翻/不消失类 bug 归 #568 批，未触碰；零 push。

## 一、根因（实测）

| # | 症状 | 根因 | 证据（shots/report.json 修前读数） |
|---|---|---|---|
| ① | #567 行下 `[stderr]}`、#563 行下 `}` | 外部执行器 stderr 由引擎以 `[stderr] ` 前缀写进**同一**输出缓冲（ExternalSessionRunner.scala:363；另有 `[read-loop crashed]`/`[launch-failed]` 标记），行尾提取 `progressTailText` 取「最后一条非空行」逐字上行——stderr 尾巴、stdout JSON 裸括号行全数渲染 | `567.meta = "[stderr] }"`、`563.meta = "}"`（before-dark/light 完全复现作者截图） |
| ② | 「8 tasks」居中悬置 | `.task-stats { margin-left: 44px }`（2026-10-07 ui-train-threefix C 列对齐令：stats 与行文本列同线 x=72）。宽面板上 72px 缩进读作悬置；且该方程随 ③ 的徽标去重失效 | `stats.x=403` vs `toggle 右缘 351`（52px 空气） |
| ③ | 图标挤在标题行、位置不对 | `.task-delegate-icon` **零 CSS**：14px `<span>` 无对齐槽，随 13px/1.4 行盒长到 18.2px 高、顶边高出状态环 1.5px；内部执行器（nebflow/空）也挂牌——nebflow 行双 logo（违反 L1 卡面已落的九点②去重纪律，delegateChainView.executorHeadHtml：`!id ‖ 'nebflow' → ''`），空 executor 行渲染中性齿轮点幽灵标 | icon 盒 `14×18.2 y=357.3` vs 环 `15×15 y=358.8`（错位 1.5px）；内部行 icon 全数在挂（`executor-icon-nebflow` / 空串齿轮点） |

## 二、修法（全部在显示层；缓冲与详情视图零触碰）

- **① 行尾守卫**（taskList.js `progressTailText`）：行渲染缝三重兜底——
  引擎控制行（`[stderr]`/`[read-loop crashed]`/`[launch-failed]` 前缀）不上行
  （stderr 只进详情视图：bgAgentPopup/bgTaskOutputPopup 吃原始 `output` 面不动）；
  纯标点碎片行（`}`、`]}` 之类）视同空行跳过；>200 字符行 JS 硬截断加省略号
  （CSS ellipsis 视觉截断 + textContent 转义之外第三腿）。
- **② 靠边**（taskList.css）：摘 `.task-stats` 44px 缩进（含 ≤480px 窄档 48px）——
  任务数紧跟折叠箭头（同 flex 行 8px gap）。2026-10-19 作者令取代列对齐令。
- **③ 徽标槽**：九点②去重纪律上板行——内部执行器（nebflow/空）零挂牌（状态 glyph
  即行身份视觉，幽灵槽/双 logo 一并消灭）；外部执行器徽标保留，14px→12px 进字形槽
  节奏（与 twisty 槽同款 margin-top:3px 首行垂直居中），`.task-delegate-icon`
  inline-flex 12×12 槽 + img/svg display:block（不再吃行盒基线数学）。

## 三、验收

| 门 | 结果 |
|---|---|
| `tasklist-chain-blocks.spec.mjs`（T1–T7） | **7/7 绿**——T1 翻转钉：内部行零徽标、外部行徽标 12px 且与环同 y（±2px）、盒 ≤13px、stats margin-left 0px、stats 与 toggle 间距 6–12px；新增 T7：stderr/碎片行不上行（行尾=`patch plan written (3 files)`）、累计缓冲里的旧 `[stderr] }` 持续被拒、260 字符行落 200+省略号 |
| `delegate-chain-stub.spec.mjs`（14 例） | **14/14 绿**——T2 翻转钉（nebflow 行零徽标/外部徽标保留）、T13 重钉（任务数靠边：stats 左缘 = toggle 右缘 + 8px ±0.75，取代 2026-10-07 同线钉，注释声明取代关系） |
| `check-js-types.mjs` | 总量 **251 < 基线 273**（本批触面 taskList.js 零告警）；1 败例 `askAttachments.js` = askuser #551 已落库批（427e0ed1b）存量——该文件与 HEAD 逐字节同（`git diff HEAD` 空），本批未触碰，不越界修（与 avatar-sharp 批 MANIFEST §三 同读数） |
| `build-web.mjs` | **164 源全入图，orphans 0** |
| 前后对照实拍 | 同一 8 任务双组夹具 + 同一 stderr 混装缓冲，修前/修后各拍暗亮双面 |

## 四、对比图（同夹具同缓冲，修前 vs 修后）

- `shots/before-dark-panel.png` vs `shots/after-dark-panel.png` —— 修前：`[stderr]}`/
  `}` 挂行下、8 tasks 悬在 403px、内部行双 logo 幽灵槽、14px 图标挤压；修后：行尾为
  真实进度行、8 tasks 贴箭头（359px）、内部行 [环][#N 标题] 干净、外部徽标 12px 同心。
- `shots/after-dark-row-567.png` / `-563.png`（2× 特写）—— 徽标与环同心、尾行可读。
- `shots/before-light-*` / `after-light-*` 亮面同矩阵；`shots/report.json` 逐格几何读数。

复现：`NODE_PATH=/opt/homebrew/lib/node_modules node shoot-tasklist.mjs [before|after]`
（隔离 8193 端口，永不触 8080；before 首拍于码面改动前 = HEAD 5f973f36d，夹具与缓冲
两轮逐字节同，只差源码）。

## 五、边界

- 改动仅限任务列表面板显示层：taskList.js（行尾守卫 + 徽标槽）、taskList.css
  （stats 靠边 + 徽标槽样式）、两件 spec 翻转。引擎输出缓冲/端点/详情视图零触碰
  （stderr 在详情视图照常全量可读）；#568 批的状态语义 bug 未触碰。
- 零 locale 键值 diff；九点②纪律引用自 L1 卡面既落事实（delegateChainView.js §九点②），
  非新造第二套规则。
- pathspec 小步提交于 delegate/delegate-nebflow-d8bdb31f；零 push。
