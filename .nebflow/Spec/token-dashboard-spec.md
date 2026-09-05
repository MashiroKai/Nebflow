# Token 消耗看板 — 前端设计规格书

| 字段 | 值 |
|---|---|
| 版本 | v1.3 |
| 日期 | 2026-08-23 |
| 状态 | frozen · v1.3 GitHub 范式修订（用户 2026-08-22 23:58 裁定，Manager 授权落盘） |
| 任务 | #310 Token 消耗看板前端设计（热力图 + 看板）· v2 迭代 |
| 作者 | design-engineer（v1.3 修订：Frontend） |
| 数据管线 | 已合入（结构化埋点 + 五维聚合，`UsageRecordStore` / `GET /api/usage/aggregate`） |
| 后端依赖 | 无——D1（filter 参数）2026-08-23 Frontend 自查已落地（§6.3），v2 零 Backend 依赖 |

### 修订记录

| 版本 | 日期 | 修订内容 | 裁定来源 |
|---|---|---|---|
| v1.0 | 2026-08-20 | 初版：热力图 + 看板 + 三维过滤 + 下钻 | design-engineer |
| v1.1 | 2026-08-20 | **修订 1**：入口定案侧边栏 Activity Bar 图标（作废 v1.0 多入口候选）；**修订 2**：新增缓存命中率统计维度（汇总卡 / 维度排行 / 下钻明细 / 热力图双着色 / 空态边界） | 2026-08-20 09:32 用户裁定 |
| v1.2 | 2026-08-20 | **P0 修正 1**：命中率公式 `cacheRead/(cacheRead+input)` → `cacheRead/input`（inputTokens 为全量输入已含 cacheRead，旧分母双重计数会把 94% 算成 48%）；**P0 修正 2**：总消耗四桶相加 → `input+output`（cacheRead 计两遍，实测虚高 +94%）；**§6.9 重写**（字段语义前提 / loadAll 静默丢弃实际行为 / provider 不上报 cache 边界 + E4 扩展点）；Q1/Q2/Q3 三定案语义不变 | cache-engineer 口径审查（9227 条实测 + 源码 + 基线三重证据链，Manager 授权落盘） |
| v1.3 | 2026-08-23 | **GitHub 范式重构**（用户裁定 2026-08-22 23:58，五点）：①热力图固定铺满一年（365/366 格，无数据日 L0 不跳格），作废 v1.2 的 7/30/90/自定义时间范围；②消耗量色阶阈值换数量级五档 `0 / <100万 / 100万–1000万 / 1000万–1亿 / ≥1亿`（五档结构不变，只换阈值，零新色）；③新增日内 24h 曲线（点格即出，`dim=hour`，SVG 轻量）；④统计卡与热力图解耦——统计区间按钮组（1 天/1 周/1 月/3 月）只控汇总卡，热力图独立固定一年；⑤热力图维度参数（总计/模型/agent 三档），filter 单选 + 热力图只重渲自己（数据依赖：D1 filter 参数已落地，见 §6.3）；⑥「点击格→下钻」交互被日内曲线取代，下钻区与着色模式切换保留为次要面板 | 2026-08-22 23:58 用户裁定（GitHub 热力图范式，Nebula 五点解读） |

## 0 · 一句话目标

在 Nebflow 客户端侧边栏 Activity Bar 新增用量看板入口图标，点击展开独立看板面板（弹窗形态，毛玻璃面板无 overlay）：**v1.3 GitHub 范式**——主视图为**固定铺满一年**（365/366 格）的「天 × 周」色阶热力图，支持总计/模型/agent 维度切换与单选 filter；点击某格即出**该日 24h 消耗曲线**（dim=hour，轻量 SVG）；顶部汇总卡由独立**统计区间按钮组**（1 天/1 周/1 月/3 月）控制，与热力图零耦合——整体保持既有玻璃质感与低调克制风格。热力图支持消耗量与缓存命中率双着色模式切换；下钻表格保留为次要面板。

## 1 · 参考与依据

### 1.1 范式引用（行为观察为主；撰写日外部链接网络不可达/验证码拦截，范式描述基于公开产品事实，链接指向权威入口可溯源）

| # | 范式 | 提炼规则 | 链接 |
|---|---|---|---|
| P1 | GitHub 贡献热力图（用户点名） | 列 = 周、行 = 星期（日~六），旧→新从左到右；4 级色阶 + 无色空档；月份标签；hover 显示日期 + 数值；格子紧凑（~11px） | https://github.com（公开 profile 贡献图，行为观察） |
| P2 | 消息搜索弹窗（内部先例，案例 001） | 居中毛玻璃弹窗、overlay 透明、Esc 两段式、focus trap、i18n 断言口径（key 先行） | `~/.nebflow/docs/Nebflow/20260817_message-search-spec-v2.md` |
| P3 | Apple HIG · Color / 数据展示克制度 | 色彩克制服务于信息传达；避免无意义红绿价值色；tabular-nums 数字对齐 | https://developer.apple.com/design/human-interface-guidelines/ |
| P4 | 对数色阶惯例（数据密集热力图） | 量级跨度 >3 个数量级时必须对数/分位数分档，否则低值被压没 | 数据可视化通用实践（d3 scaleLog 语义：https://d3js.org） |

**取舍**：P1 的 4 级色阶在本场景扩为 5 档（0 空 + 4 色阶），因为 token 量级从 0 到 2 亿+ 需容纳极大值；色值用既有 sapphire 透明度变体而非 GitHub 绿（铁律：零新色 token）。不采用线性色阶（P4：实测单日跨度 0 → 2 亿）。

### 1.2 数据形状调研结论（2026-08-20 实测）

**端点**：`GET /api/usage/aggregate?dim=provider|model|agent|hour|day&from=<epochMillis>&to=<epochMillis>`（带 `authHeaders()`，`RestApiRoutes.scala:61-68`）

**响应**（`UsageRecordStore.scala:61-69`；数值为 mock 示例，非真实数据）：
```json
{
  "totalInput": 0, "totalOutput": 0, "totalCacheRead": 0, "totalCacheWrite": 0,
  "count": 6574, "costEquivalent": 0,
  "buckets": [{ "key": "2026-08-18", "count": 2100, "inputTokens": 0, "outputTokens": 0, "cacheReadTokens": 0, "cacheWriteTokens": 0 }]
}
```

**关键事实**：
- **字段语义（v1.2 定稿）**：`inputTokens` = 全量输入，**包含** `cacheReadTokens` / `cacheWriteTokens` 子集（adapter 层已归一化，实测 9227 条 `in ≥ cr` 恒成立）——由此命中率 = `cacheRead / input`，总消耗 = `input + output`；四桶直接相加会把 cacheRead 计两遍
- dim 一次只能一个维度（provider / model / agent / hour / day）；**无 filter 参数** → 三维过滤需后端扩展（§6.3 D1）
- provider 为内部 id（实测 `"107"`），需显示名映射（§6.4）
- 数据密度实测：`~/.nebflow/usage-records/usage-records.jsonl` 6574 条 / 3 天 ≈ 2100 条/天；单次 input ~10 万 token → 单日总量常态即 2 亿+ 量级（对数分档必要）
- 90 天 ≈ 19 万条记录，聚合在服务端（`loadAll` 全量读文件）；前端只拿聚合结果，热力图 91 格 DOM 极轻

### 1.3 铁律依据

`nebflow/visual-style`（弹窗毛玻璃/无 overlay/玻璃控件/中字重/克制专业感）+ `sapphire.css` token（`--glass-bg` / `--glass-blur: 24px` / `--sapphire: 91 127 191` / `.glass-control`）。色阶零新 token。

## 2 · 布局与信息架构

### 2.1 入口：侧边栏 Activity Bar 图标（v1.1 定案）

**用户裁定（2026-08-20 09:32）：入口定为侧边栏 Activity Bar 一级图标，作废 v1.0 的设置内入口/多候选方案。**

#### 2.1.1 Activity Bar 现有图标序（`index.html:52-66`）

```
顶部群组（面板切换）:
  avatar → messages-btn → contacts-btn → files-btn
              ↓ 活动间隙
底部群组（Canvas tab + 模态触发器）:
  teams-btn → flows-btn → agents-btn → settings-btn
```

- 顶部群组 = 侧栏面板切换按钮（`registerSidePanel`，点击切换 #sidebar 内 panel，再点折叠，VSCode 语义）
- 底部群组 = Canvas tab 触发器（teams/flows/agents）+ 模态触发器（settings）
- `.activity-spacer` 分隔两组（`index.html:61`）

#### 2.1.2 建议插入位：Files 之后、spacer 之前

```
avatar → messages → contacts → files → 【usage-btn】 → (spacer) → teams → flows → agents → settings
```

**理由**：
1. 顶部群组语义 = 「内容浏览」类操作（消息/联系人/文件/用量），用量看板属数据查看，与 Files 同组语义一致
2. 不侵入底部群组（teams/flows/agents/settings = 配置与组织管理），避免打乱既有分组逻辑
3. 紧贴 Files 之后 = 高频浏览操作的末位，低频查看工具不抢占前序高频位
4. 与 Files 共享 `registerSidePanel` 机制或独立弹窗机制（见 §2.1.3）

#### 2.1.3 点击行为：展开看板面板（弹窗形态）

**弹窗形态**（非侧栏 panel）：点击 `usage-btn` → 打开居中毛玻璃弹窗（§2.2 布局），非切换 #sidebar 内 panel。理由：
- 看板需 ≥640px 宽（90 天热力图 13 列），#sidebar-panel 仅 220px，不适合侧栏内嵌
- 复用 chatSearch / login modal 成熟弹窗基建（P2：焦点管理 / Esc / i18n / 毛玻璃），零风格风险
- `usage-btn` 不注册为 `registerSidePanel`，独立绑定 click → `openUsageDashboard()`（与 settings-btn 同模式：`activityBar.js:215-226` bindSettingsButton 先例）

**弹窗视觉**（遵循 `nebflow/visual-style` 铁律）：
- 面板毛玻璃：`background: var(--glass-bg)` + `backdrop-filter: blur(24px) saturate(1.15)` + `border: 1px solid var(--glass-border)` + 顶部折射细线（§5.2）
- **禁 overlay 遮罩**：`--overlay-bg: transparent`，不暗化/不模糊背景
- 按钮高亮：弹窗打开时 `usage-btn` 加 `.active`（同 settings-btn 的 `observeSettingsModal` 机制，`activityBar.js:228-236`）

**图标**：lucide `bar-chart-3` 或 `activity`（数据/活动语义；`<i data-lucide="bar-chart-3">`，与现有 lucide 图标体系一致）

### 2.2 看板弹窗布局（720×560，可调；`min(92vw, 720px)`；v1.3 重排）

```
┌──────────────────────────────────────────────────────────┐
│ 用量看板                                       [× 关闭]   │ ← 标题栏（600 字重）
├──────────────────────────────────────────────────────────┤
│ (Provider ▾) (模型 ▾) (Agent ▾)                          │ ← filter 行（§2.3）
├──────────────────────────────────────────────────────────┤
│ [1天|1周|1月|3月]                                        │ ← 统计区间按钮组（§2.3，只控汇总卡）
│ [总消耗] [今日] [环比] [缓存命中率] [最耗agent] [最耗模型]│ ← 汇总卡 ×6（§2.4）
├──────────────────────────────────────────────────────────┤
│ (总计|模型|agent) 维度 │ [消耗量|命中率] 着色            │ ← 热力图控制（§2.5）
│  8月   9月   10月  11月  …（固定一年，横向滚动）          │ ← 月份标签行
│  ░ ▓ █ ░ ▒ ▓ █ …（365/366 格铺满）                      │ ← 热力图主区（§2.5）
│  色阶图例（右缘）                                        │
├──────────────────────────────────────────────────────────┤
│ ── 日内曲线（点击格子即出，24h 柱状/曲线，dim=hour）──── │ ← 日内曲线区（§2.5.1）
├──────────────────────────────────────────────────────────┤
│ ── 当日明细（曲线区下方可展开下钻，次要面板）──────────── │ ← 下钻区（§2.6）
└──────────────────────────────────────────────────────────┘
```

**v1.3 布局变化**：①时间范围分段（7/30/90/自定义）删除，替换为统计区间按钮组（1 天/1 周/1 月/3 月），且只控汇总卡；②热力图控制行（维度 + 着色）紧贴热力图上方，与统计区视觉分离；③点击格子的首要响应 = 日内 24h 曲线（§2.5.1），下钻表格降为曲线区下方的次要可展开面板。

### 2.3 控制条（v1.3 拆分为三个独立控制组）

- **filter 行（全局过滤）**：provider / 模型 / agent 三个下拉（`.glass-control` 质感，单选，含「全部」项）。语义 = **全局过滤**（汇总卡 + 热力图 + 日内曲线 + 下钻同受过滤）——后端 filter 参数（§6.3 D1）**已落地**（2026-08-23 自查：`UsageRecordStore.aggregate` 支持 `provider`/`model`/`agent` exact-match filter，与 dim 正交），无降级路径
- **统计区间按钮组**（v1.3 新增，只控汇总卡）：分段控件 `[1 天 | 1 周 | 1 月 | 3 月]`（默认 1 周）。**只影响汇总卡的 from/to 请求窗口**，热力图/日内曲线/下钻完全不受影响（零耦合，裁定④）。切换 → 仅汇总卡重新请求（7 个并行聚合，§5.5）
- **热力图控制行**（v1.3 新增，紧贴热力图）：维度分段 `[总计 | 模型 | agent]`（默认总计，裁定⑤）+ 着色模式分段 `[消耗量 | 命中率]`（v1.1 既有）。维度切换 → 热力图**只重渲自己**（§5.5 第 3 行请求拓扑：排行定 Top1 + 该值日分布，会话内缓存；不影响汇总卡/曲线区；切回总计用主视图缓存零请求）
- ~~时间范围 7/30/90/自定义~~（v1.2 设计，v1.3 作废——热力图固定一年，自定义窗口需求由统计区间覆盖）

### 2.4 汇总卡 ×6（玻璃卡片，横排；375px 视口 3×2 网格；v1.3 区间独立）

**区间语义（v1.3）**：汇总卡的 from/to 由统计区间按钮组（§2.3）决定——1 天 = 今日零点起；1 周 = 近 7 天；1 月 = 近 30 天；3 月 = 近 90 天（均以今日零点为终点基准的滑窗）。卡片内的「总消耗」即该区间合计；「今日」「环比」卡固定日粒度不受区间影响（它们是绝对指标）；「最耗 agent/模型」按当前区间排行。「缓存命中率」为区间整体 `cacheRead/input`。切换区间只触发汇总卡重请求，热力图保持不动（裁定④）。


1. **总消耗**：选定范围 input + output 合计（`inputTokens` 为全量输入已含 cacheRead/cacheWrite，四桶相加会双重计数——v1.2 修正），缩写格式 + 全量 `title`
2. **今日**：当日合计 + 与昨日同时刻对比（↑/↓ 百分比）
3. **本周环比**：本周日均 vs 上周日均（%），中性 accent 蓝箭头（不做红绿价值色，P3）
4. **缓存命中率**（v1.1 新增，v1.2 修正公式）：整体 `cacheRead / input` × 100%（`inputTokens` 为全量输入已含 cacheRead，分母无需再加），缩写一位小数 + 环比变化（本周 vs 上周，↑/↓ pp 百分点，中性蓝箭头同 §3 口径）。目标基线 94% / 目标 96%+ 可在 `title` 注明（不占主视觉）。分母为零（input=0）时显示「—」
5. **最耗 agent**：Top1（`costEquivalent` 最大）；点击可下钻该 agent 明细（交叉「agent×模型」组合列为扩展点 E1，§6.5）
6. **最耗模型**：Top1（`costEquivalent` 最大）

数值口径：**主指标 = inputTokens + outputTokens 合计 raw tokens**（`inputTokens` 为全量输入已含 cacheRead/cacheWrite——四桶直接相加会把 cacheRead 计两遍，v1.2 修正；四桶字段仍在 tooltip/下钻明细中全量展示）；`costEquivalent`（`(input - cacheRead) + cacheRead × 0.1`，v1.2.1 勘误修正——旧式「input + cacheRead×0.1」在包含语义下把 cacheRead 计 1.1x，双计虚高 ~86%；实现 @3c3c035e 已按修正公式，output 不计入=输入侧计费等效）作次级小字标注「计费等效 · cache 0.1x 假设」（`UsageRecordStore.scala` 注释口径）。

**缓存命中率口径**（v1.1，v1.2 修正公式）：
- 公式：`hitRate = cacheRead / input`——`inputTokens` 为全量输入**已含** cacheRead 子集（§1.2 字段语义），分母无需再加 cacheRead；排除 output。cacheWrite 在包含语义下同样已计入 inputTokens，无需单独处理
- 数据来源：聚合端点 totals 的 `totalCacheRead` / `totalInput`（§1.2 响应字段已有）
- 环比：本周 hitRate − 上周 hitRate，以百分点（pp）标注，如「94.2%（↑1.3pp）」；上周无数据时显示「—」
- **无 cache 数据边界**：cacheRead=0 且 input=0 的记录 → 不参与命中率计算（§6.9）

### 2.5 热力图主区（v1.3 重构：固定一年 + 维度参数）

- **窗口（v1.3 裁定①）**：**固定铺满一年**——窗口 = 从「今日」往前推满 52/53 个完整周列（起点 = 今日所在周的周日，终点 = 今日），总格数 = 周列数 × 7，**无数据日渲染 level-0 淡底格，不跳格不补空白**（GitHub 范式：格子永远铺满，空日 = 最浅档）。请求一次 `dim=day`，from = 窗口首日零点、to = 今日零点 + 1 天（下闭上开含今日）
- **格子布局（P1 GitHub 式）**：列 = 周、行 = 星期（日~六 7 行），旧→新从左到右；月份标签悬于对应列上方。一年 ≈ 53 列 × 13px ≈ 689px——弹窗 720px 内可容纳；更窄视口热力图容器横向滚动（`overflow-x: auto`，§6.6）
- **格子尺寸**：11px × 11px + 2px gap（375px 视口缩至 9px，§6.6）；一年 = 372 格 DOM 上限（53×7），极轻
- **维度参数（v1.3 裁定⑤）**：控制行分段 `[总计 | 模型 | agent]`（默认总计）：
  - **总计**（默认）：每格值 = 当日 input+output 合计（现有行为）
  - **模型 / agent**：维度排行（dim=model|agent，窗口同一年）拉取后，热力图按**当前选中 filter 值**着色——若对应下拉为「全部」，则取该维度 Top1（最耗者）的日分布着色，图例上方标注当前着色的维度值（如「模型 · glm-5.2」，可点击切换回总计）；单选某模型/某 agent 时按该值过滤后的日分布着色
  - **色阶随维度重算**：维度切换后色阶阈值不变（§5.3 数量级五档固定），但实际落档分布随数据变化——图例标签恒定，无需动态色阶
- **着色模式切换**（v1.1，保留）：`[消耗量 | 命中率]`
  - **消耗量模式**（默认）：5 档数量级色阶（§5.3，v1.3 新阈值），值越大色越深；主指标 v = 当日 input + output 合计（v1.2 口径）
  - **命中率模式**：5 档线性色阶（§5.3.1），命中率越高色越深，图例标签「— · 25% · 50% · 75% · 100%」；无数据日渲染 H0 斜纹格（§6.9）
- **色阶**：5 档图例横条置于热力图右缘，标签随着色模式切换（消耗量模式标签随 v1.3 阈值更新：「0 · 100万 · 1000万 · 1亿」）
- **hover tooltip**：日期 + 总 token（缩写）+ 调用次数 + input/output 拆分 + 缓存命中率；玻璃气泡跟随指针（§4）
- **点击格子（v1.3 裁定③）**：**日内 24h 曲线区展开/切换**（§2.5.1）——点格即出该日曲线；再点同格收起曲线区；点其他格切换该日。下钻表格不再是点击格子的直接响应，降为曲线区下方的次要可展开面板（§2.6）
- **选中格视觉**：2px `--sapphire` 实色描边（同 v1.2 active 态），曲线区与选中格联动高亮

### 2.5.1 日内 24h 曲线（v1.3 新增，裁定③）

- **触发**：点击热力图任一格 → 热力图下方展开「日内曲线区」（200ms 下滑淡入）；切换格子 = 曲线区原地更新（不收起重开）；再次点击选中格 = 收起曲线区
- **数据**：`dim=hour & from=当日0点 & to=次日0点`（1 次请求，24 桶内）——`hourKey` 形如 `2026-08-18T13`（RestApiRoutes/UsageRecordStore 已支持，零后端改动）
- **渲染**：**轻量 SVG**（非 canvas/图表库）——24 根竖柱（小时粒度，柱宽自适应容器），高度按 input+output 合计线性缩放（日内曲线不做对数——单日内跨度有限，线性更直观；柱顶可带数值缩写）；x 轴标签 0/6/12/18/23 五档；y 轴不画刻度，峰值标注在最高柱上方（缩写数值）
- **样式**：柱体 `rgb(var(--sapphire) / 0.55)` 填充 + hover 单柱高亮 0.85 + tooltip（小时 + token 缩写 + 调用次数）；曲线区背景 = 玻璃卡（`--glass-bg` + 边框），圆角 8px；高度 ~120px 固定
- **空态**：当日无任何记录 → 曲线区显示「当日无数据」文案（i18n），仍保持展开态
- **维度联动**：当前维度为模型/agent 且有选中 filter 时，曲线请求带对应 filter 参数（该模型/该当日的 24h 分布）；总计模式不带
- **与下钻区关系**：曲线区下方保留「展开当日明细」入口（小按钮/链接，§2.6）——用户要更细的维度表格时才展开，默认收起

### 2.6 下钻区（当日明细；v1.3 降为次要面板）

**v1.3 变化**：下钻表格不再是点击格子的直接响应（点击格 = 日内曲线，§2.5.1）。曲线区下方提供「展开当日明细」入口（玻璃小按钮），点击展开维度表格抽屉（200ms 右滑，同 v1.2 交互）；再次点击/ Esc 收起：

- **维度 tabs**：agent / provider / model（glass 分段控件）——每次切换 1 次聚合请求（`dim=agent|provider|model & from=当日0点 & to=次日0点`），**无需后端组合维度**
- **维度排行**（v1.1 新增）：在表格上方显示当前维度下命中率排行 Top 5（`cacheRead / input` 降序，v1.2 修正公式）+ 倒数 Top 5（命中率最低，用于揪出拉低命中率的 agent —— 已知根因：长期闲置唤醒的 agent 全量 input 无缓存命中）。排行条用 sapphire 透明度条形（零新 token），命中率数值 tabular-nums
- **表格**：key、调用次数、input、output、cacheRead、cacheWrite、合计、**命中率**（v1.1 新增列，v1.2 修正公式 `cacheRead/input` 百分比，无 cache 数据显示「—」）；按合计降序 Top 10；tabular-nums 对齐；合计列 = input + output sum（v1.2 修正口径，四桶明细列仍全量展示）
- 关闭：Esc / 再次点击格子 / 抽屉关闭按钮

**当日明细列表**（v1.1 补充）：除维度聚合表外，可展开「逐请求明细」子视图（折叠列表，默认收起），每行带：时间戳、agent、provider/模型、input、output、cacheRead、cacheWrite、命中率。逐请求数据来自前端按日期过滤 `usage-records.jsonl`（或后端新增 `dim=hour` 已有支持，按小时聚合展示）。此子视图为可选增强（实现优先级低于维度聚合表）。

### 2.7 引用 token（零新 token）

全部视觉引用 `sapphire.css` 既有 token（§5 明细）；唯一新值 = `--sapphire` 透明度变体（色阶 4 档，案例 001 同款纪律）。

## 3 · 交互状态机表

### 3.1 热力图格子

| 状态 | 视觉 | 行为 |
|---|---|---|
| 默认 | 档位色块（§5.3），圆角 2px | — |
| hover | 同色块 + 1px sapphire 描边（`--sapphire-edge-strong`） | tooltip 出现（80ms fade） |
| focus | 同 hover + `outline: 2px` sapphire（focus ring，§7） | ↑↓←→ 在格子间移动（grid 模式） |
| active（点击，v1.3） | 色块 + 2px 描边（`--sapphire` 实色） | **日内曲线区展开/切换到该日**（§2.5.1）；再次点击同格收起曲线区 |
| loading（请求中） | 格子保持原色块，控制条旁 loading 指示（glass 小 spinner；本地即时，理论不触发） | 禁重复点击（`pointer-events: none`） |
| error（请求失败） | 曲线区/下钻区显示错误态文案（§6.7） | 重试按钮；热力图本身保留上次成功数据 |
| empty（无数据日） | level-0 淡底格（§5.3），仍可点击 | tooltip 显示「0 token · 0 次」；点击出曲线区显示「当日无数据」（§2.5.1 空态） |

### 3.2 维度下拉 / 分段控件（v1.3 三组）

| 状态 | 视觉 | 行为 |
|---|---|---|
| 默认 | `.glass-control` 质感 | — |
| hover | `.glass-control` hover 态（`--glass-control-bg-hover`） | — |
| focus | focus ring（`--glass-etched-border-focus`） | ↑↓ 选择、Enter 确认 |
| active（filter 下拉） | 选中项高亮（`--sapphire-edge-strong` 底 + 600 字重文本） | 全局重请求（汇总卡 + 热力图 + 曲线区若在展开态） |
| active（统计区间分段，v1.3） | 选中段高亮（同 active 样式） | **只重请求汇总卡**（热力图不动，裁定④） |
| active（热力图维度分段，v1.3） | 选中段高亮（同 active 样式） | 热力图本地重渲（重算着色值），不发新请求；图例上方标注当前维度值 |
| active（着色模式切换，v1.1） | 选中段高亮（`--sapphire-edge-strong` 底 + 600 字重） | 切换热力图着色维度（消耗量 ↔ 命中率），图例标签与色阶同步切换，150ms 淡入过渡（§4） |

### 3.3 汇总卡

| 状态 | 视觉 | 行为 |
|---|---|---|
| 默认 | 玻璃卡（`--glass-bg` + `--glass-border`），数字 tabular-nums 600 | — |
| loading | 数值区显示「—」（不闪烁、不占位移） | 数据到达后原地替换 |
| empty | 显示「—」 | — |
| 无 cache 数据（v1.1，缓存命中率卡） | 显示「—」+ `title`="暂无缓存数据" | 不参与命中率统计 |

### 3.4 日内曲线区 / 下钻抽屉 / 弹窗（v1.3）

| 状态 | 视觉 | 行为 |
|---|---|---|
| 曲线区默认（v1.3） | 玻璃卡 + 24 柱（§2.5.1），下滑淡入 | 点格即出；切格原地更新 |
| 曲线区关闭 | 再点选中格 / 弹窗关闭 | 淡出收起（150ms） |
| 下钻抽屉（次要面板，v1.3） | 曲线区下方「展开当日明细」按钮触发，右滑展开（§4） | — |
| 抽屉关闭 | Esc / 再点展开按钮 / 关闭按钮 | 反向动画收起 |
| 弹窗打开 | 缩放 + 淡入（§4） | 焦点移入弹窗（focus trap，P2） |
| 弹窗关闭 | Esc / × | 焦点归还 Activity Bar `usage-btn`（案例 001 口径）；两段式：抽屉/曲线区展开时先收它们（P2 口径） |

## 4 · 动效规范

| 触发 | 时长 | 缓动 | 位移/属性 | reduced-motion |
|---|---|---|---|---|
| 弹窗打开 | 150ms | ease-out | scale 0.98→1 + opacity 0→1 | 时长 0.01s |
| 弹窗关闭 | 100ms | ease-in | scale 1→0.98 + opacity 1→0 | 0.01s |
| tooltip 出现/消失 | 80ms | ease-out | opacity 0→1，无位移（跟随指针） | 0.01s |
| 下钻抽屉展开 | 200ms | ease-out | translateX 16px→0 + opacity 0→1 | 0.01s |
| 下钻抽屉收起 | 150ms | ease-in | 反向 | 0.01s |
| 维度/范围切换重渲染 | 150ms | ease-out | opacity 0→1（整区淡入） | 0.01s |
| 着色模式切换（v1.1） | 150ms | ease-out | 热力图色阶 + 图例标签同步淡入（opacity 0→1） | 0.01s |
| 日内曲线区展开（v1.3） | 200ms | ease-out | translateY 8px→0 + opacity 0→1（下滑淡入） | 0.01s |
| 日内曲线区切换他日（v1.3） | 120ms | ease-out | 柱体 opacity 0.3→1（原地更新，不收起重开） | 0.01s |
| 日内曲线区收起（v1.3） | 150ms | ease-in | 反向 | 0.01s |
| 汇总卡数值更新 | 无动画（直接替换，克制） | — | — | — |

全部动效尊重 `prefers-reduced-motion`：media query 下 `animation-duration: 0.01s`（案例 001 A10 断言口径）。

## 5 · 视觉规格

### 5.1 字体与字重（铁律 3）

| 元素 | 字重 |
|---|---|
| body / 表格正文 | 400 |
| 弹窗标题 / 汇总卡数值 / 维度选中项 | 600 |
| 按钮 / 下拉 | 500 |
| 数字 | `font-variant-numeric: tabular-nums`（无新字体） |

### 5.2 玻璃与边框（铁律 1/2）

- 弹窗面板：`background: var(--glass-bg)` + `backdrop-filter: blur(24px) saturate(1.15)` + `border: 1px solid var(--glass-border)` + 顶部折射细线（`.glass::before` 既有样式）——**无 overlay 遮罩**（`--overlay-bg: transparent`，禁背景暗化/模糊）
- 交互控件（下拉/分段/按钮/tooltip 底）：`.glass-control` 既有质感（`--glass-control-*` 全套，blur 10px）
- 下钻表格行 hover：`--glass-etched-bg` 底 + 无边框
- 圆角：弹窗 12px；卡片/控件 8px（`--glow-radius`）；格子 2px
- 阴影：弹窗/卡片 `0 4px 24px var(--glass-control-glow)` 轻投影（克制度，不发光不彩色）

### 5.3 色阶（5 档数量级分档，零新 token；v1.3 阈值）

| 档 | 条件（当日 input + output 合计 v，v1.2 口径） | 色值（`--sapphire` 91 127 191 透明度变体） | 亮色 | 暗色 |
|---|---|---|---|---|
| L0 | v = 0（无消耗） | `--glass-etched-bg`（淡底，非 sapphire） | rgba(0,0,0,0.025) | rgba(255,255,255,0.04) |
| L1 | 0 < v < 1e6（1 ~ 99.9 万） | sapphire 15% | rgba(91,127,191,0.15) | rgba(120,160,220,0.25) |
| L2 | 1e6 ≤ v < 1e7（100 万 ~ 999.9 万） | sapphire 32% | rgba(91,127,191,0.32) | rgba(120,160,220,0.45) |
| L3 | 1e7 ≤ v < 1e8（1000 万 ~ 9999.9 万） | sapphire 55% | rgba(91,127,191,0.55) | rgba(120,160,220,0.68) |
| L4 | v ≥ 1e8（1 亿+，含 2 亿事故级） | sapphire 85% / 暗色近实色 + 微 glow | rgba(91,127,191,0.85) | rgba(120,160,220,0.92) |

- **v1.3 阈值变更**：v1.2 的 `1/1e4/1e6/1e8` → `0/1e6/1e7/1e8`（用户裁定 2026-08-22：区间按 0 / 100万 / 1000万 / 1亿 分）。五档结构、色值、CSS class（`level-0..4`）全部不变——**只换 `costLevel()` 阈值，零新色**（裁定②）
- 分档规则 = 数量级区间（1e6/1e7/1e8 三档界），**可断言**（§8 A2）
- 档间可区分性：相邻档色块 ΔL* ≥ 15（§7）；文本/工具提示文字对比度 ≥ 4.5:1
- 图例：横条 5 段（L0-L4），置于热力图右缘，标签「0 · 100万 · 1000万 · 1亿 · 1亿+」（缩写；en：「0 · 1M · 10M · 100M · 100M+」；五段对应五档，v1.3.1 修正——初版四段致 L4 无标签）

### 5.3.1 命中率模式色阶（v1.1 新增，5 档线性分档）

命中率模式色阶语义与消耗量模式**相同方向**（深色 = 高值），但分档规则改为线性（命中率 0-100% 均匀分布）：

| 档 | 条件（当日命中率 hr） | 色值（`--sapphire` 透明度变体） | 亮色 | 暗色 | 语义 |
|---|---|---|---|---|---|
| H0 | 无数据（cache 字段缺失） | `--glass-etched-bg` + 斜纹纹理 | rgba(0,0,0,0.025) | rgba(255,255,255,0.04) | 旧数据，不参与统计 |
| H1 | 0 ≤ hr < 25% | sapphire 15% | rgba(91,127,191,0.15) | rgba(120,160,220,0.25) | 极低命中（差） |
| H2 | 25% ≤ hr < 50% | sapphire 32% | rgba(91,127,191,0.32) | rgba(120,160,220,0.45) | 低命中 |
| H3 | 50% ≤ hr < 75% | sapphire 55% | rgba(91,127,191,0.55) | rgba(120,160,220,0.68) | 中等命中 |
| H4 | 75% ≤ hr ≤ 100% | sapphire 85% | rgba(91,127,191,0.85) | rgba(120,160,220,0.92) | 高命中（好） |

- **色阶语义说明**：命中率模式下 **高命中 = 深色 = 好**，与消耗量模式（高消耗 = 深色）方向一致（深色 = 高值），但语义不同——消耗量模式深色 = 消耗大（中性），命中率模式深色 = 命中率高（好）。**不使用红绿价值色**（P3 铁律：克制），色阶仅表达量级，价值判断由 tooltip 数值 + 目标基线（94%/96%）文字辅助传达
- **斜纹纹理**（H0 无数据格）：CSS `repeating-linear-gradient(45deg, transparent 0 2px, rgba(128,128,128,0.08) 2px 4px)` 叠加在淡底上，视觉区分「无数据」与「命中率 0%」（H1 实色淡蓝 vs H0 斜纹淡底）
- 图例标签：「— · 25% · 50% · 75% · 100%」（H0 用「—」表示无数据）

### 5.4 数字格式化

| 量级 | 中文 | 英文 |
|---|---|---|
| ≥ 1e8 | x.x 亿 | x.xB |
| ≥ 1e4 | x.x 万 | x.xK / x.xM |
| < 1e4 | 千分位原值 | 千分位原值 |

统一 `Intl.NumberFormat(locale, { notation: 'compact' })` 兜底，缩写单位词走 i18n（§6.8）。

### 5.5 数据请求拓扑（v1.3）

| 请求 | 时机 | dim + filter | 用途 |
|---|---|---|---|
| 热力图主请求 | 打开弹窗 / filter 变化 | `dim=day`，from=窗口起点 to=明日，带全局 filter | 热力图 365/366 格 |
| 汇总卡聚合 ×7 | 打开弹窗 / 统计区间切换 / filter 变化 | 与 v1.2 `loadMain` 同构（主区间 + 昨日 + 本周/上周 + dim=agent + dim=model 等），from/to = 统计区间窗口 | 六张汇总卡 |
| 维度排行（模型/agent 维度着色用） | 热力图维度切到模型/agent 且尚无缓存时 | ① `dim=model|agent`（定 Top1，仅 filter=全部时）② 带维度 filter 的 `dim=day`（该值日分布）；窗口同一年，会话内缓存 | Top1 日分布着色（§2.5 维度参数） |
| 日内曲线 | 点击格子（每次切格） | `dim=hour`，from=当日 0 点 to=次日 0 点，带全局 filter + 维度 filter（若有） | §2.5.1 曲线 |
| 下钻表格 | 点击「展开当日明细」 | `dim=provider|model|agent`，当日窗口（v1.2 既有） | §2.6 表格 |

**零耦合纪律（裁定④）**：统计区间切换只触发第 2 行请求；热力图维度切换只走第 3 行（或纯本地重渲）；切格只走第 4 行。三组请求互不连带。

## 6 · 边界与异常

### 6.1 空态

| 场景 | 表现 |
|---|---|
| 整周无消耗 | 该周格全部 L0 淡底，周标签照常显示；hover 显示「0 token · 0 次」 |
| 全量无数据（usage-records.jsonl 不存在/空） | 热力图区显示空态：图标 + 「暂无用量数据」+ 副文案「完成一次 AI 对话后，此处将开始统计」 |
| 下钻当日无数据（filtered 空） | 表格区显示「当日无该维度记录」（i18n key） |
| 日内曲线当日无数据（v1.3） | 曲线区保持展开，显示「当日无数据」文案（`usage.curveEmpty`）；无柱体 |

### 6.2 极大值（2 亿 token 事故级）

- 数量级分档天然容纳：v ≥ 1e8 落 L4，不撑爆色阶（v1.3 阈值下 2 亿仍为 L4）
- 数值缩写：≥ 1e8 → 「x.x 亿」；汇总卡全量数值放 `title` 属性
- 环比计算：今日/本周对比用日均（分母非零保护：昨日/上周无数据时显示「—」而非除零/∞）
- **日内曲线峰值**（v1.3）：单小时峰值可达千万级（2 亿事故日小时分布），柱高线性缩放取当日最大值归一，峰值标注缩写数值（「2.1 亿」）

### 6.3 后端依赖 D1（三维过滤）——已落地（2026-08-23 自查）

**状态：D1 已落地，v2 零后端依赖。** 2026-08-23 Frontend 源码自查（`UsageRecordStore.scala:142` aggregate / `RestApiRoutes.scala:64` 路由）确认：

```
GET /api/usage/aggregate?dim=day|hour|provider|model|agent&provider=<exact>&model=<exact>&agent=<exact>&from=<epochMillis>&to=<epochMillis>
```

- filter 参数 `provider` / `model` / `agent` 为 **exact-match**，与 dim 正交（先 filter 后 group），三个可同时携带
- `dim=hour` 桶 key 形如 `2026-08-18T13`（日内 24h 曲线 §2.5.1 的数据源）；`dim=day` 桶 key `2026-08-18`
- `from`/`to` epoch ms，下闭上开（`>= from && < to`）——今日数据 `to` 取次日零点
- v1.2 时代的「降级路径」（下拉置灰 + `usage.filterNeedsBackend` 提示）**作废**——下拉始终可用；对应 i18n key 保留不删（死键无害，避免破坏既有引用）

### 6.4 provider 显示名映射

usage 记录中 provider 为内部 id（实测 `"107"`）。前端从设置 provider 配置（modelEntry.id 的 `provider/model` 前缀）构建 id → 显示名映射；未知 id 显示原始值 + 工具提示「未知 provider id」。映射构建失败不阻塞看板（仅影响显示）。

### 6.5 扩展点（本期不做，记录待办）

- E1：最贵「agent×模型」交叉组合卡 —— 需后端组合 dim（如 `dim=agent,model`）
- E2：~~Activity Bar 快捷图标入口~~（v1.1 已定案为主入口，此项作废）
- ~~E3：热力图按维度着色/叠加~~（v1.3 已落地维度参数：总计/模型/agent 三档，裁定⑤）；多维叠加（如模型×agent 组合着色）仍为 E3
- E4：provider 缓存上报黑名单配置面——不上报 cache 字段的 provider 从命中率统计剔除或可视化隔离（v1.2 暂以 §6.9c 的前端内置名单 + title 标注兜底；名单现空——kimi 08-21 起已恢复上报，见 §6.9c 名单更新注）

### 6.6 长时段与窄视口（v1.3 更新）

- **一年窗口 = 最多 53 周列 × 7 行 = 372 格 DOM**（v1.3 固定一年），极轻；**前端零聚合计算**（只格式化渲染，聚合全在服务端）；一年请求单次 `dim=day`（≤366 桶），日内曲线单次 `dim=hour`（≤24 桶）
- 720px 弹窗内容纳 ~53 列 × 13px ≈ 689px 刚好；**更窄视口热力图容器横向滚动**（`overflow-x: auto`，滚动条细而低调），不做格子压缩
- 375px 视口：弹窗 `min(92vw, 720px)`；汇总卡 6 → 3×2 网格；热力图格子 11px → 9px（横向滚动）；控制行换行（filter 行 / 统计区间 / 热力图控制各占一行）；增量口径断言（§8 A10）

### 6.7 错误态

- 请求失败：下钻区/汇总卡显示「加载失败」+ 重试按钮（glass 按钮）；热力图保留上次成功数据；连续失败 3 次不再自动重试
- 空响应（buckets 空但 totals 有值）：按 totals 渲染汇总卡，热力图全 L0

### 6.8 多语言

- i18n key 清单见 §7.5（`usage.*` namespace，zh-CN.js + en.js 同步新增）
- 数字格式化走 `Intl.NumberFormat(locale)`；缩写单位词（亿/万 vs B/K/M）走 i18n key（§7.5）
- 日期格式：`Intl.DateTimeFormat(locale, { month: 'short' })` 月份标签、`{ month: 'short', day: 'numeric' }` tooltip

### 6.9 cache 字段边界（v1.1 新增，v1.2 重写）

**字段语义前提（v1.2 定稿）**：`inputTokens` = 全量输入，**包含** `cacheReadTokens` / `cacheWriteTokens` 子集（adapter 层已归一化：OpenAiAdapter 取 `prompt_tokens` 全量、AnthropicAdapter 显式 `totalInput = input + cacheRead + cacheWrite`；2026-08-20 实测 9227 条记录 `in ≥ cr` 恒成立）。由此：**命中率 = `cacheRead / input`，总消耗 = `input + output`**——四桶直接相加会把 cacheRead 计两遍（实测虚高 +94%），`cacheRead/(cacheRead+input)` 会把命中率砍半（94% → 48%）。

**a) 管线合入前旧记录**：usage-records.jsonl 为五维聚合管线合入（2026-08-18）时**新建**文件，实测全部记录四字段齐备——「缺字段旧记录」场景现实不存在。90 天视图中 08-18 之前为整日无记录，走 §6.1 空态（L0 淡底格），非本节场景。判定逻辑 `cacheRead=0 && cacheWrite=0 && input=0 → 无 cache 数据` 保留作防御（显示「—」，不参与命中率统计）。

**b) 缺字段行的实际行为**：`UsageRecordStore.loadAll` 对解析失败行**静默丢弃**（`decode(...).toOption`，circe deriveDecoder 对缺失 Int 字段必失败）——若未来记录格式变更出现缺字段行，该行整条消失（消耗量同步少算），**不会**以 0 值进聚合稀释命中率。聚合数字与逐条记录数不一致时优先排查此路径。

**c) provider 不上报 cache（边界机制保留，名单已空）**：部分 provider 可能不返回缓存字段——其记录呈 `cacheRead=0 && input>0`，语义是「**不知道**」而非「未命中」。此形态与「真实未命中」（如 107 冷启动首请求）在数据上不可区分，需按 provider 特性判定：前端内置不上报名单（机制保留），名单内 provider 的记录在命中率显示处（tooltip / 下钻表格 / 排行）`title` 标注「provider 未上报缓存数据」（i18n key `usage.noCacheReported`），不拉黑不剔除；名单外 provider 的 `cr=0 && in>0` 属真实未命中，正常参与统计（实测 154 条 / 占总 input 1.86%）。配置面记入扩展点 E4（§6.5）。

> **名单更新（2026-08-22）**：初版名单 `['kimi']` 基于早期单条旧样本的推断；对账报告（~/.nebflow/docs/Nebflow/20260822_cache-reconciliation-report.md §4 F2，@f5e80ca）确认 kimi 自 2026-08-21 04:49 起已正常上报 cache（169/177 条 `cacheRead>0`，命中率 94.3% 与主力 provider 同档）——名单改为空数组 `[]`，新案例出现时在 E4 配置面或此常量扩展。

| 场景 | 表现 | 说明 |
|---|---|---|
| 汇总卡「缓存命中率」无数据 | 显示「—」+ `title`="暂无缓存数据" | 全窗口 `input` 与 `cacheRead` 合计均为 0 时 |
| 热力图命中率模式无数据日 | 该日格子渲染 H0 斜纹纹理格（§5.3.1） | 与「命中率 0%」（H1 实色）视觉区分 |
| 热力图消耗量模式 | 该日格子正常按 input+output 合计着色（L0-L4） | 消耗量不受 cache 数据缺失影响 |
| hover tooltip | 命中率字段显示「—」；不上报 provider 标注 `usage.noCacheReported` | 其余字段（消耗/次数/拆分）正常 |
| 下钻表格命中率列 | 无 cache 数据显示「—」；不上报 provider 同标注 | 正常未命中记录照常显示 0.0% |
| 维度命中率排行 | 无 cache 数据的 key 不参与排行 | 排行只统计有 cache 数据的 agent/provider/model |

## 7 · 无障碍

### 7.1 键盘导航

- **热力图**：`role="grid"` + 格 `role="gridcell"`；格 `tabindex=0`，↑↓←→ 在格子间移动（grid roving tabindex，WAI-ARIA APG Grid 模式）；Enter/Space 打开下钻；Esc 关闭下钻抽屉（再按关闭弹窗，两段式，P2 口径）
- **弹窗**：focus trap（Tab 20 次不逃逸，案例 001 A8 口径）；打开时焦点移入弹窗首焦点元素；关闭后焦点归还 Activity Bar `usage-btn`
- **下拉/分段**：原生 select 或 ARIA listbox 语义；↑↓ 选择、Enter 确认、Esc 收起
- **着色模式切换**（v1.1）：`role="radiogroup"` + 两选项 `role="radio"`；←→ 切换、Enter 确认；`aria-label`="热力图着色模式"

### 7.2 ARIA

- 热力图容器：`role="grid"` + `aria-label` = 「Token 消耗热力图」
- 格子：`aria-label` 模板 = 日期 + 消耗量 + 次数 + 命中率（如「8 月 18 日，消耗 2.1 亿 token，3421 次，缓存命中率 94.2%」；无 cache 字段时省略命中率段）——**tooltip 内容在 aria-label 中冗余**（hover 内容 = 屏幕阅读器内容）
- 汇总卡：`role="group"` + 每卡 `aria-label`（「总消耗 2.1 亿」「缓存命中率 94.2%」等）
- 着色模式切换（v1.1）：`role="radiogroup"` + `aria-label`="热力图着色模式"，选项 `aria-label`="消耗量"/"命中率"
- 下钻表格：`role="table"` + 行/列头语义（`role="rowheader"` / `role="columnheader"`）；命中率列列头 `aria-sort` 可切换
- 加载/错误态：`aria-live="polite"` 区域（下钻区）

### 7.3 对比度

- 文本/工具提示文字对比度 ≥ 4.5:1（亮暗双主题各自验证）
- 色阶相邻档色块 ΔL* ≥ 15（可区分性；色块为装饰性，不承担唯一信息——档位同时反映在 tooltip/aria-label 数值上，色盲友好）
- focus ring：`outline: 2px` sapphire 实色，亮暗主题均可见

### 7.4 非鼠标可达

全部操作键盘可达（开弹窗 / 切维度 / 切范围 / hover 信息 / 下钻 / 关闭）；无 hover-only 信息（tooltip 与 aria-label 冗余）；`prefers-reduced-motion` 全量适配（§4）。

### 7.5 i18n key 清单

`usage.*` namespace，`locales/zh-CN.js` + `locales/en.js` 同步新增（扁平 key，`t('usage.xxx')` 调用，案例 001 ① 口径：**key 先行，断言比运行时 locale 输出**）。

| key | zh-CN | en |
|---|---|---|
| usage.title | 用量看板 | Usage Dashboard |
| usage.activityEntry | 用量看板 | Usage Dashboard |
| usage.activityEntryHint | 查看 Token 消耗热力图与缓存命中率 | View token usage heatmap & cache hit rate |
| usage.open | 打开用量看板 | Open usage dashboard |
| ~~usage.range.7 / .30 / .90 / .custom / .tooLong~~ | （v1.3 作废，键保留不删避免破坏引用） | — |
| usage.filterAll | 全部 | All |
| ~~usage.filterNeedsBackend~~ | （v1.3 作废，D1 已落地） | — |
| usage.stats.1d | 1 天 | 1 day |
| usage.stats.1w | 1 周 | 1 week |
| usage.stats.1m | 1 月 | 1 month |
| usage.stats.3m | 3 月 | 3 months |
| usage.statsRangeLabel | 统计区间 | Stats range |
| usage.heatDim | 热力图维度 | Heatmap dimension |
| usage.heatDim.total | 总计 | Total |
| usage.heatDim.model | 模型 | Model |
| usage.heatDim.agent | agent | Agent |
| usage.heatDimLabel | {dim} · {value} | {dim} · {value} |
| usage.curveTitle | {date} 24 小时分布 | {date} hourly breakdown |
| usage.curveEmpty | 当日无数据 | No data for this day |
| usage.curvePeak | 峰值 {tokens}（{hour}） | Peak {tokens} ({hour}) |
| usage.curveBar | {hour} 时，{tokens} token，{calls} 次 | {hour}:00, {tokens} tokens, {calls} calls |
| usage.drillOpen | 展开当日明细 | Show daily breakdown |
| usage.legend.cost | 0 · 100万 · 1000万 · 1亿 · 1亿+ | 0 · 1M · 10M · 100M · 100M+ |
| usage.total | 总消耗 | Total tokens |
| usage.today | 今日 | Today |
| usage.weekTrend | 本周环比 | Week-over-week |
| usage.cacheHitRate | 缓存命中率 | Cache hit rate |
| usage.cacheHitRateHint | 目标 96%+（当前基线 ~94%） | Target 96%+ (current baseline ~94%) |
| usage.cacheHitRateNoData | 暂无缓存数据 | No cache data |
| usage.noCacheReported | provider 未上报缓存数据 | Provider does not report cache data |
| usage.topAgent | 最耗 agent | Top agent |
| usage.topModel | 最耗模型 | Top model |
| usage.costEquivalent | 计费等效 · cache 0.1x 假设 | Cost-equivalent · cache 0.1x |
| usage.calls | 调用次数 | Calls |
| usage.input | 输入 | Input |
| usage.output | 输出 | Output |
| usage.cacheRead | 缓存读 | Cache read |
| usage.cacheWrite | 缓存写 | Cache write |
| usage.hitRate | 命中率 | Hit rate |
| usage.hitRateUnit | {pct}% | {pct}% |
| usage.hitRateDelta | {pct}%（{dir}{delta}pp） | {pct}% ({dir}{delta}pp) |
| usage.colorMode | 着色模式 | Color mode |
| usage.colorMode.cost | 消耗量 | Cost |
| usage.colorMode.hitRate | 命中率 | Hit rate |
| usage.drilldownTitle | {date} 明细 | {date} breakdown |
| usage.dim.agent | 按 agent | By agent |
| usage.dim.provider | 按 provider | By provider |
| usage.dim.model | 按模型 | By model |
| usage.hitRateRankTop | 命中率最高 | Highest hit rate |
| usage.hitRateRankBottom | 命中率最低 | Lowest hit rate |
| usage.heatmapLabel | Token 消耗热力图 | Token usage heatmap |
| usage.heatmapCell | {date}，消耗 {tokens}，{calls} 次，命中率 {hitRate} | {date}, {tokens} tokens, {calls} calls, hit rate {hitRate} |
| usage.heatmapCellNoCache | {date}，消耗 {tokens}，{calls} 次 | {date}, {tokens} tokens, {calls} calls |
| usage.empty | 暂无用量数据 | No usage data yet |
| usage.emptyHint | 完成一次 AI 对话后，此处将开始统计 | Stats appear after your first AI conversation |
| usage.drillEmpty | 当日无该维度记录 | No records for this dimension that day |
| usage.loadError | 加载失败 | Failed to load |
| usage.retry | 重试 | Retry |
| usage.unit.billion | 亿 | B |
| usage.unit.million | 万 | M |
| usage.trend.up | 较昨日 ↑{pct}% | ↑{pct}% vs yesterday |
| usage.trend.down | 较昨日 ↓{pct}% | ↓{pct}% vs yesterday |
| usage.trend.flat | 与昨日持平 | Flat vs yesterday |
| usage.close | 关闭 | Close |
| usage.loading | 加载中… | Loading… |

## 8 · 可断言验收点

供 qa-frontend 转 Playwright 断言（隔离实例 + mock `/api/usage/aggregate` 响应）。全部二值化，口径按案例 001 教训显式锁定（文案→i18n key、选中/顺序→显式语义、布局→增量基线）。

| # | 断言（二值） | 口径锁定 | 截图 |
|---|---|---|---|
| A1 | **（v1.3 改写）**热力图铺满一年：`[data-date]` 格数 = 窗口天数（今日往前推满整周列，364~371 格，= 周列数 × 7 中属于窗口内的天数），**无数据日也在**（class `level-0`，不跳格）；窗口首格 = 起点周日、末格 = 今日 | 用 `data-date` 属性计数；断言首末格日期精确值 + 无空格跳格（相邻格 data-date 连续） | 否 |
| A2 | **（v1.3 阈值）**色阶五档边界正确（消耗量模式）：mock 当日值 {0, 999999, 1e6, 1e7, 1e8}（v = input+output 合计）→ 格 class `level-0/1/2/3/4` | 边界值 100万/1000万/1亿（左闭右开：1e6 → L2、1e7 → L3、1e8 → L4）；999999 → L1 | 否 |
| A3 | hover 格子出现 tooltip：DOM 存在且文本含日期 + 消耗 + 次数 + 命中率（mock 格式化输出，中文 locale） | 比对运行时 i18n 输出（key 先行），非硬编码中文；无 cache 字段时省略命中率段 | 是（亮主题） |
| A4 | **（v1.3 改写）**点击格子出日内曲线：Playwright route 拦截到 `dim=hour&from=<当日0点>&to=<次日0点>` 请求 + 曲线区（`#usage-curve`）DOM 出现且含 24 小时内柱体；再点同格曲线区消失 | from/to 为当日零点/次日零点 epoch ms；柱体数 = mock hour 桶数（无数据小时无柱或 0 高柱，口径二选一实现时锁定） | 否 |
| A5 | 汇总卡六项数值 = mock 聚合 totals 格式化：总消耗/今日/环比/缓存命中率/最耗 agent/最耗模型 | mock 响应固定值 → 断言卡内文本；今日环比无昨日数据时显示「—」（§6.2）；缓存命中率无数据时显示「—」（§6.9） | 否 |
| A6 | 无数据周渲染 L0 淡色格：mock 某周全 0 → 该周 7 格 class 均 `level-0` | class 断言 + `--glass-etched-bg` computed 值 | 是 |
| A7 | 弹窗无遮罩 + 面板毛玻璃：`#usage-dashboard` 无 overlay 暗化层（computed background transparent）+ 面板 `backdrop-filter` 含 `blur(24px)` | 增量口径：弹窗打开态不新增背景遮罩 DOM | 是（亮/暗各一） |
| A8 | **（v1.3 改写）**统计区间切换只重请求汇总卡：点击 `1天/1周/1月/3月` 分段，route 拦截 from/to = today 零点起 / today-6 / today-29 / today-89 零点的汇总请求（7 并行聚合）；**期间无新 `dim=day` 热力图请求**（零耦合，裁定④） | 断言四次汇总请求 from/to 精确值 + 区间切换后热力图 `dim=day` 请求计数不变 | 否 |
| A9 | 键盘可达：Tab 聚焦格子 → → 键移动 focus → Enter 打开日内曲线 → Esc 关曲线区 → Esc 关弹窗，焦点归还 Activity Bar `usage-btn` | Playwright keyboard 断言 focus 序列；两段式 Esc（P2 口径） | 否 |
| A10 | 375px 增量口径：打开态 === 关闭态基线 + 弹窗子树无横向溢出 | 案例 001 踩坑③口径：只断言弹窗子树 `scrollWidth ≤ clientWidth`，不碰既有本底溢出 | 是（375px） |
| A11 | reduced-motion：`prefers-reduced-motion: reduce` 下弹窗/抽屉/着色切换 `animation-duration ≤ 0.01s`（computed） | 案例 001 A10 口径 | 否 |
| A12 | i18n key 先行：Activity Bar 入口 title/弹窗标题/空态文本走 `t('usage.*')`，zh↔en 切换运行时输出随 locale 变 | 断言运行时文本 ≠ 硬编码；比对 locales 文件两 key 存在 | 否 |
| A13 | 侧边栏入口存在：`#usage-btn` 在 `#activity-bar` 内，位置在 `#files-btn` 之后、`.activity-spacer` 之前 | DOM 顺序断言：`usage-btn.previousElementSibling.id === 'files-btn'` 且 `usage-btn.nextElementSibling.classList.contains('activity-spacer')` | 否 |
| A14 | 缓存命中率汇总卡数值 = mock `totalCacheRead / totalInput` × 100%，一位小数（v1.2 公式：inputTokens 已含 cacheRead，分母不再加） | mock {totalCacheRead: 94000, totalInput: 100000} → 文本含「94.0%」；分母为零时显示「—」 | 否 |
| A15 | 着色模式切换：点击「命中率」→ 热力图格 class 从 `level-*` 切为 `hit-*`，图例标签从「0 · 100万…」切为「— · 25%…」 | class + 图例文本同步断言；150ms 淡入过渡 | 是（亮主题，两模式各一） |
| A16 | 命中率模式色阶边界：mock 当日命中率 {无数据, 0%, 25%, 50%, 75%, 100%} → 格 class `hit-0/1/2/3/4` | 无数据 = H0 斜纹格（cacheRead=0 && input=0）；0% = H1（cacheRead=0 && input>0） | 否 |
| A17 | 下钻表格含命中率列：曲线区下方「展开当日明细」→ 下钻表格 `role="table"` 含 8 列（key/次数/input/output/cacheRead/cacheWrite/合计/命中率），命中率列值 = `cacheRead/input`（v1.2 公式）；合计列 = input+output | 列计数 + 命中率列文本断言；无 cache 数据行显示「—」 | 否 |
| A18 | 无 cache 数据边界：mock 无数据记录（cacheRead=0, input=0）→ 汇总卡命中率显示「—」+ 热力图命中率模式该日 H0 斜纹格 | 汇总卡文本 + 格 class `hit-0` + computed `background-image` 含 `repeating-linear-gradient` | 是 |
| A19 | **（v1.3 新增，裁定⑤）**热力图维度切换：点击 `模型/agent` 分段 → 热力图按该维度 Top1（filter=全部时）日分布重着色，图例上方出现维度值标注（`usage.heatDimLabel` 格式）；切回总计恢复全量着色 | 首次切维度产生 ≤2 请求（`dim=<dim>` 排行定 Top1 + 带维度 filter 的 `dim=day` 日分布，同会话缓存）；**切回总计不产生新请求**（主视图缓存）；标注文本含维度名 + Top1 值 | 否 |
| A20 | **（v1.3 新增，裁定②）**图例标签 = 数量级五档：消耗量模式图例文本 = `usage.legend.cost`（「0 · 100万 · 1000万 · 1亿 · 1亿+」/ en「0 · 1M · 10M · 100M · 100M+」，五段对应五档） | 运行时 i18n 输出断言，双 locale 各一 | 否 |
| A21 | **（v1.3 新增，裁定③）**日内曲线数据点正确：mock `dim=hour` 响应 3 桶（如 09/13/20 时）→ 曲线区柱体数 = 3，峰值柱标注 = 最大桶合计缩写（`usage.curvePeak` 格式） | 柱体数 + 峰值标注文本断言；当日无桶时显示 `usage.curveEmpty` 文案 | 否 |
| A22 | **（v1.3 新增，裁定①④）**热力图与统计区间零耦合 + 一年窗口请求：初始加载 `dim=day` 请求 from = 窗口起点周日零点、to = 明日零点（含今日）；切换统计区间四次后该 `dim=day` 请求总计数仍 = 1（或仅随 filter 变化重发） | URL 参数精确断言 + 请求计数断言 | 否 |

## 9 · 参考链接

| 链接 | 用途 |
|---|---|
| https://github.com | P1 GitHub 贡献热力图（行为观察：列=周/行=星期/4 级色阶/月份标签/hover 数值） |
| https://developer.apple.com/design/human-interface-guidelines/ | P3 Apple HIG（色彩克制/tabular-nums；Color 子页需 JS 渲染，行为观察为准） |
| https://d3js.org | P4 d3-scale log 语义（对数分档先例） |
| https://www.w3.org/WAI/ARIA/apg/patterns/grid/ | 热力图 grid 键盘模式（roving tabindex） |
| https://www.w3.org/WAI/ARIA/apg/patterns/tooltip/ | tooltip 可访问性（hover 信息非唯一载体） |
| `~/.nebflow/docs/Nebflow/20260817_message-search-spec-v2.md` | P2 弹窗先例（案例 001）：焦点管理/Esc 两段式/i18n 断言口径/增量口径 |
| `~/.nebflow/skills/nebflow/visual-style/SKILL.md` | 铁律：弹窗毛玻璃/无 overlay/玻璃控件/中字重/克制 |
| `src/main/resources/web/css/sapphire.css` | token：`--glass-bg`/`--glass-blur: 24px`/`--sapphire: 91 127 191`/`.glass-control` |
| `src/main/resources/web/index.html:52-66` | Activity Bar HTML 结构（avatar→messages→contacts→files→spacer→teams→flows→agents→settings） |
| `src/main/resources/web/js/activityBar.js` | Activity Bar 模块：`registerSidePanel` / `bindSettingsButton`（弹窗触发先例）/ `observeSettingsModal`（按钮高亮先例） |
| `src/main/scala/nebflow/gateway/RestApiRoutes.scala:61-68` | `GET /api/usage/aggregate` 端点定义 |
| `src/main/scala/nebflow/core/UsageRecordStore.scala:61-69` | `UsageAggregate` 响应形状（totals + buckets + costEquivalent） |
| `~/.nebflow/usage-records/usage-records.jsonl` | 实测数据密度（6574 条/3 天、单次 input ~10 万、provider 内部 id） |

> 注：撰写日（2026-08-20）外部网络（GitHub Docs / Material / Anthropic）连接超时或被验证码拦截，范式链接指向权威入口、规则以产品行为观察为准（先例：案例 001 微信行为观察链接）。

## 10 · 待确认项（请用户/Manager 裁决）

| # | 问题 | 选项 A（推荐） | 选项 B |
|---|---|---|---|
| Q1 | 三维过滤后端依赖 D1（§6.3） | **D1 先行：已定案（2026-08-20 10:06 Nebula 代用户裁决）**；**2026-08-23 Frontend 自查确认已落地**（§6.3），降级路径作废 | ~~降级交付~~ |
| ~~Q2~~ | ~~入口位置（§2.1）~~ | ~~设置内入口 + 独立看板弹窗~~ | ~~直接嵌设置弹窗 / 侧栏 tab~~ → **v1.1 已裁定：侧边栏 Activity Bar 图标** |
| Q3 | 色阶主指标（§5.3） | **四桶合计 raw tokens：已定案（2026-08-20 10:06 Nebula 代用户裁决）**——用户心智模型=token 消耗量，raw 与 usage 字段对齐。v1.2 注：定案心智不变；「四桶相加」表述系双重计数 bug，实际口径 = input+output 合计（inputTokens 已含 cacheRead）——cache-engineer 口径审查修正。v1.3 注：主指标语义不变，仅色阶阈值换数量级五档（裁定②） | ~~costEquivalent~~ |

全部定案（Q1 D1 先行 / Q2 侧边栏 / Q3 raw tokens）——2026-08-20 10:06 spec 冻结；同日 v1.2 口径修正落盘（P0×2：命中率公式 `cacheRead/input`、总消耗 `input+output`；§6.9 重写；Q1/Q2/Q3 三定案语义不变），Manager 授权，可直接派发实施。

**v1.3 修订状态（2026-08-23）**：用户 2026-08-22 23:58 GitHub 范式五点裁定直接生效（无待确认项）——五点 = ①一年铺满 ②数量级五档色阶 ③日内 24h 曲线 ④统计区间与热力图解耦 ⑤热力图维度参数。Q1/Q2/Q3 三定案语义在 v1.3 下全部保持（入口/主指标不变；D1 确认已落地）。Nebula 验收五条对应断言：①→A1/A22 ②→A2/A20 ③→A4/A21 ④→A8/A22 ⑤→A19。v2 实施零 Backend 依赖，可直接派发（分支 `feat/dashboard-v2`，基线 `bbe8bd66`）。
