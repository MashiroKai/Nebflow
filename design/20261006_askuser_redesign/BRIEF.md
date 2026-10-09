# BRIEF — Onboarding / AskUser 视觉重设计批（改前出稿位）

> 批次：AskUser 视觉重设计 · 基线盘点 + 设计简报位（作者 2026-10-06 16:35 令）
> 本位性质：零代码改动、零功能面触碰。本文 = 实现树判定 + 现实现完整面盘点 + 行为改动规格（只写规格）+ mockup 渲染规则 + 截图工艺 + 目录规约。
> 下游：三风格节点各领一个槽位，产 HTML 源 + light.png + dark.png + 设计语言说明段；作者选定后才与 ① 行为规格同批落码。

---

## 0 · 实现树判定

**live 树 = 工作区根本体 `/Users/kaiyu/Claude code/Nebflow`（executor-registry @ `0adfef219`）。**

依据：根树 `git merge-base --is-ancestor 3fd629737 HEAD` 通过 —— 前批 onboarding-ux 实施位（stable/onboarding-ux @ `3fd629737`，五点打磨批）**已全部在根树**；盘点所引组件文件全部按根树现文件给出（`src/main/resources/web/…`）。工作树仅有未跟踪杂项（`bing*.html`、`design/` 等），不影响判定。本批对该树除 `design/20261006_askuser_redesign/` 外零写入。

组件落点（全部真实存在，已逐文件读过）：

| 面 | 文件 |
|---|---|
| Onboarding 问答卡 | `src/main/resources/web/js/onboarding.js`（1195 行） |
| AskUser 通用卡 | `src/main/resources/web/js/chat.js` `showOptions()`（:2251-2657）+ `renderAskUser()`（:2830 起） |
| 待办条 / 徽标 | `src/main/resources/web/js/askPending.js` |
| 名字单一读点 | `src/main/resources/web/js/rootName.js` |
| 样式 | `src/main/resources/web/css/chat.css`（v2 onboarding 段 :1936-2185；AskUser 段 :1633-1941） |
| 文案真源 | `src/main/resources/web/js/locales/zh-CN.js`（:718-846 ob2 段）、`en.js` 同构 |

---

## 1 · 完整面盘点（现实现：组件 / 样式 / 精确文案）

### 1.1 卡片容器（Onboarding 题卡）

- 挂载点：题文伪流式气泡**内部**（`askCard(def, bubble, total, idx)`，onboarding.js:483-650）——卡不是独立层，是 `.bubble.ai` 里追进的 `div.ask-card`。
- 结构：`ask-card` > [ `ask-meta` 题头 ] + [ 题型面（输入行 / 选项组 / 模型卡）] + [ `ob-actions` 底行（跳过 / 选好了）]。
- 样式（chat.css:2049-2056）：`padding:10px; background:var(--color-frame-hover); border:1px solid var(--color-border); border-radius:10px; animation:ob-card-in 200ms`。
- 出现节奏：题文流完 → `CARD_BEAT_MS=240`（onboarding.js:169）→ 卡浮现（🔴 禁即时弹卡，设计纪律保留）。
- 已答态：整卡 `opacity:0.62` + 控件全 disabled（onboarding.js:501-506）。

### 1.2 题头（精确格式）

- 组件：`segmentMeta()`（onboarding.js:476-478）→ `meta.textContent = ${def.seg} · ${t('ob2.progress',{n,total})}`。
- 实渲字串（zh-CN）：**「第一幕 · 见面 · 第 1 / 10 题」**（幕名与进度两段拼接，中点 + 空格分隔）。
- 四幕名（zh-CN.js:720-723）：`第一幕 · 见面` / `第二幕 · 接上大脑` / `第三幕 · 你对我的期待` / `第四幕 · 认识你`；进度模板 `'ob2.progress': '第 {n} / {total} 题'`。
- 样式（chat.css:2057-2062）：`font-size:10.5px; letter-spacing:0.6px; color:var(--color-text-dim); margin-bottom:8px`。

### 1.3 输入框（自由输入行）

- 组件：`freeRow`（onboarding.js:513-536）——`div.free-row` > `input.option-input` + 主按钮。
- 样式（chat.css:2104-2130）：`free-row{display:flex;gap:8px;margin-top:8px}`；`option-input{flex:1;padding:5px 10px;border:1px solid var(--color-border);border-radius:14px;font-size:13px;background:transparent}`；focus 边框变 `var(--color-primary)`。
- IME：组字态 Enter 交还输入法（imeGuard 单点，onboarding.js:524,531-535）——视觉稿不受影响，落码时禁拆。

### 1.4 主按钮（精确文案 **「用这句」**）

- 组件：`freeBtn`（onboarding.js:517-520）→ `t('ob2.btn.useThis')`；`class="glass-control ob-primary"`；输入为空时 disabled，输入非空解锁（:525）。
- 样式：`.glass-control` 基座（sapphire.css:147）+ `.ob-primary{background:rgba(7,193,96,0.42)}`（chat.css:2014-2016）——绿色系主按钮。
- 同款主按钮还有：multi 的 **「选好了」**（`ob2.btn.picked`，:640）、模型卡的 **「用这个窗口」**（`ob2.model.ctxApply`，:690）、fallback 面的 **「测试并保存」**（`onboarding.btn.testAndSave`，:742）。

### 1.5 候选 chips

- 组件：`chipRow`（onboarding.js:554-567）——`div.chip-row` > 一组 `button.option-btn`；点击 = 填入输入框并聚焦（不是直接提交）。
- 现用 chips（SCRIPT，onboarding.js:352,361）：名字题 `星尘 / 洛书 / 阿涅 / Nova / 海豹`；称呼题 `阿凯 / 老板 / 船长 / 直接叫我名字`。
- 样式：`chip-row{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}`（chat.css:2131-2136）；chip 复用 `.option-btn`（:1652-1679：`padding:5px 12px;border-radius:14px;border:1px solid rgba(145,170,210,.25)`，picked 态 `background:var(--color-primary)` 白字）。

### 1.6 跳过链接（精确文案 **「跳过这一题」**）

- 组件：`skipBtn`（onboarding.js:541-546）→ `t('ob2.btn.skip')`；`class="glass-control ob-ghost"`；`data-skip=<题id>`。
- 样式：`.ob-ghost{background:transparent;box-shadow:none;border-color:var(--color-border);color:var(--color-text-dim)}`（chat.css:2025-2034）；底行 `ob-actions{display:flex;gap:8px;margin-top:10px}` + `ob-spacer{flex:1}` 把跳过推到右侧。
- 纪律：**每题必有跳过，无一例外**（含模型 / 名字 / 称呼题，task book §2 B）——视觉稿不得移除，只可改形。
- 伴生控件：伪流式期间的 **「跳过打字」**（`ob2.typing.skip`，onboarding.js:274-282，`.ob-skip` 绝对定位于气泡右上，流完即消失，chat.css:1993-2002）；选择题的兜底入口 **「都不是，我自己说」**（`ob2.btn.sayMyself`，`.option-btn.opt-ghost`，onboarding.js:617-634，点击展开输入行）。

### 1.7 AskUser 通用卡（选项按钮 / 确认 / 取消）

- 容器：`div.option-box`（chat.js:2251-2260；chat.css:1633-1639：`padding:8px;background:var(--color-frame-hover);border:1px solid var(--color-border);border-radius:10px`），整卡渐显 `.option-box.ob-fade-in`（chat.css:1936）。挂在自己的 `.row.ai > .bubble.ai` 里（renderAskUser，chat.js:2830-2855）。
- 问句：`.option-q`（`q.innerHTML = item.question`，chat.js:2323-2326；chat.css:1640 起，13px）。
- 选项按钮：`.option-opts` > `button.option-btn`（chat.js:2404-2457）——与 onboarding chips **同一 class**（红线上两卡共用按钮材质）；multi 版本带 `.option-check` 方框、纵排（chat.css:1692-1725）；带 desc 的选项渲染 `.option-text` + `.option-desc`。
- 「其他...」按钮：`allowOther!==false` 时追加（chat.js:2459-2539，文案 `t('chat.other')='其他...'`），点开 textarea `.option-custom-input`（placeholder `t('chat.typeAnswer')='输入你的回答...'`，chat.css:1736）。
- 底行：`.option-btn-row` > `.option-cancel`（文案 **「取消」** `chat.cancel`）+ `.option-confirm`（文案 **「确认」** `chat.confirm`，带 ✓ 图标，绿 `--color-primary` 系，未答全 disabled；chat.js:2577-2624；chat.css:1757-1798）。
- 已决态：`.option-box.resolved` 置灰 + 卡内交互全锁（lockOptionBox，chat.js:2226-2249）；作答回显 `.option-answer`（`-> 答案` 文本）。
- 来源徽标：`renderAskUser` 里 `badgeLabel !== 'Nebula'` 才挂 `.ask-user-source` 徽标（chat.js:2843-2854，内联 11px 600 字重小胶囊）——**Nebula 自问不显徽标**（这是现成的「自名不显示」先例，见 §2）。
- 待办条：`#ask-pending-bar`（chat.css:2308-2382，`#top-overlays` 顶部浮层内），行 = `ask-pending-source`（来源标签）+ `ask-pending-summary`（首问 40 字截断）；头部计数徽标 `#pending-asks-indicator`。

### 1.8 问卷全程文案串（zh-CN 真源，落 mockup 时逐字用）

- 开场四段（greet1-4，zh-CN.js:733-736）：「你好。第一次见面。」「我是为你而生的那个智能体 —— 不过此刻，我还没有名字，没有性格，也没有关于你的任何记忆。」「接下来我会一个问题一个问题地问你，像做一次很短的问卷。答不上的、不想说的，随时跳过 —— 跳过不会卡住我们，只会让那一页先空着。」「你说的话最后会变成两份记忆：Soul.md 是「我是谁」，由你对我的期待塑成；User.md 是「你是谁」。我按你（这个人）记事，不按项目记事。」
- 十题题干 / 占位 / 选项全文：见 zh-CN.js:738-830（`ob2.q.*` / `ob2.ph.*` / `ob2.opt.*` / `ob2.fh.*` / `ob2.label.*`）——本表不誊抄全文，mockup 引键名 + 逐字取值。常用样例：名字题题干「给我起个名字吧 —— 你希望以后怎么叫我？」、占位「输入名字，1–16 个字…（想不出来也可以跳过）」；角色题「你希望我在你生活里扮演什么角色？」选项「交心的伙伴 / 靠谱的管家 / 思想陪练 / 创作缪斯 / 全都要」。
- 作答回执 / 承接句（`ob2.echo.*` / `ob2.follow.*`，:834-840）：跳过回显「（这一题先跳过）」等——mockup 若画已答态需照抄。
- 书写仪式（`ob2.writing.title` / `ob2.write.1-4`，:842-846）与收尾——本批视觉面如覆盖，同规则照抄。

### 1.9 主题与字体基座

- 主题机制 = **`prefers-color-scheme` 媒体查询**（index.html 高亮双 CSS、logo `<source>` 均按它切；无类切换、无 data-theme）——mockup 必须同机制。
- 颜色 token（base.css）：`--color-primary:#07c160`（微信绿系）、`--color-bubble-user:#95ec69`、`--color-bubble-ai` 亮 `#fff` / 暗 `#1c1f27`。
- 字体：`-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif`（base.css:79）；气泡正文 14.5px/1.55（`.bubble`，chat.css:826-833）。
- 现 BIP 气泡语态：AI 左对齐白/深卡、用户右对齐绿泡、圆角 16px、AI 左下角 4px 收角（chat.css:1025-1031）。

---

## 2 · 未命名态「NEBULA」全部渲染位（行为规格 ① 的靶面）

### 2.1 存取机制（取名状态）

- **会话内 override**：`state.rootDisplayName`（state.js:173，初始 `null`）——仅由 onboarding 起名写入（`renameEverywhere()`，onboarding.js:315-326：写 override + 改 `agentsData` 根行 `displayName` + `dispatchEvent('nebflow-root-name-change')`）。
- **持久层**：`agents/<root>/agent.json` 的 `displayName`（后端 finishOnboarding 落盘；重启后经 WS `agentList` 帧 → `state.agentsData` 回来，main.js:2669-2678）。
- **单一读点**：`rootDisplayName()`（rootName.js:17-22）——优先级 `state.rootDisplayName` → agentsData 根行 `displayName || name` → 硬编码兜底 **`'Nebula'`**。机制名恒为 `"Nebula"`（RootAgentIdentity.Name），判面禁用字面量比对（rootName.js 文件头铁律）。
- **起名后生效链**：`record()`（onboarding.js:464-472，name 题非 skip 且有值 → :471 `renameEverywhere`）→ 事件 → `updateHeaderSessionName()`（sidebar.js:2149-2153 监听）即时刷新 Header。

### 2.2 渲染位清单（未命名态显示默认名的全部位）

| # | 面 | 组件 / 文件：行 | 未命名态现显 | 起名后 |
|---|---|---|---|---|
| N1 | **Header 名字区**（中置标题） | sidebar.js:2126-2147 `updateHeaderSessionName()` → `#session-name`（index.html:193；chat.css:73-85，15px/600） | **「Nebula」**（rootDisplayName 兜底） | 新名即时刷（事件链 2.1） |
| N2 | **Onboarding 气泡发送者名**（题文 / 承接句泡） | onboarding.js:247-249 `simBubble` → `.ask-user-source`，缺省 `sourceLabel()`=onboarding.js:310-312 `rootDisplayName().toUpperCase()` | **「NEBULA」**（大写） | 新名大写 |
| N3 | 开场两泡的占位发送者名 | onboarding.js:904-905 显式 `source: t('ob2.source.unnamed')` | **「还没有名字的我」**（刻意占位文案，非默认名；en 'The one without a name yet'） | 不变（这两泡只在开场出现） |
| N4 | AskUser 卡来源徽标 | chat.js:2843-2854：`badgeLabel === 'Nebula'` 时**不挂**徽标 | **已隐**（现成先例：自问不显名） | 命名后显新名 |
| N5 | 待办条行来源标签 | askPending.js:180 `askSourceLabel(entry) \|\| t('askUser.pendingTitle')`（:51-54：无 project → 裸 agentName） | **「Nebula」**（before 截图实渲可见） | 新名 |
| N6 | 其余 `{agent}` 参数面（语音 orb 状态名 micOrb.js:1231、设备邮件 chat.js:532、modelPanel / orbSettingsUI / projectTab / flowViewers 的 `rootNameParams()` 面） | 各文件见左 | 「Nebula」 | 新名 | 

> N6 不在本批视觉稿范围（非 onboarding/AskUser 卡面），列出仅为防误伤：落码时 ① 若改 rootName.js 兜底语义必须全量回归这些面；mockup 阶段零接触。

---

## 3 · ①行为改动规格（只写规格，作者选定后与视觉稿同批落码）

**目标**：未命名态去名——起名前 Header 名字区留空、气泡发送者名不显示默认名；起名后一切照旧。

**改动面与分支**：

1. **N1 Header**（sidebar.js `updateHeaderSessionName`）：
   - 现分支：`agentName === 'Nebula'` → `label = rootDisplayName()`（rootName.js 兜底给 'Nebula'）。
   - 规格分支：**未命名 → `sessionNameEl.textContent = ''`（或 display:none，二选一由作者定；建议置空串 + 保留占位布局不动，避免 header 中置内容跳动）**；已命名 → 现行为不变。
2. **N2 气泡发送者名**（onboarding.js `simBubble` :247-249 / `sourceLabel` :310-312）：
   - 现分支：`src.textContent = source || sourceLabel()`。
   - 规格分支：**未命名且无显式 source → 不创建 / 隐藏 `.ask-user-source` 元素**（同 N4 的现成手法：先判名再挂）。已命名 → `sourceLabel()` 照旧（含大写样式）。
   - **N3 开场占位「还没有名字的我」**：它是文案位不是默认名。建议口径 A（规格默认）：一并隐藏，未命名一律无发送者名，视觉最净；口径 B：保留（它传达「我是谁还没定」的叙事，与 greet2 文案呼应）。**候作者定**；mockup 按任务令渲染为无发送者名（口径 A 形态）。
3. **N5 待办条行**（askPending.js:180）：未命名且无 project → `askSourceLabel` 返回空 → 现有 `|| t('askUser.pendingTitle')` 兜底自然落到「待回答问题」，**建议顺手纳入同一判定**（属同一未命名族），零新机制。
4. **N4 AskUser 徽标**：现状已隐（`!== 'Nebula'` 字面量判定）——落码时把该判定与 3.1 的统一 `isRootNamed()` 对齐（消双口径），行为不变。

**「已命名」判定依据（现成键，禁新增存储）**：

- 判已命名 = `Boolean((state.rootDisplayName || '').trim()) || Boolean(((state.agentsData || []).find(x => x.name === 'Nebula') || {}).displayName && _.trim())`——两键皆现成：会话 override（onboarding.js:316 写）+ 持久 displayName（agentList 帧）。建议在 rootName.js 落一个 `isRootNamed()` 单点（该文件即「名字单一读点」的家），N1/N2/N4/N5 共用，禁各处自判。
- 🔴 禁用 `rootDisplayName() !== 'Nebula'` 做判定：用户把 agent 起名为「Nebula」时该式误判为未命名；且机制名恒为 Nebula，字面量比对是 rootName.js 文件头明令的禁区（判面 vs 显示面之分）。

**边界情形（建议口径，候作者裁）**：

| 情形 | 建议 |
|---|---|
| 空串名 / 纯空白名（输入框 trim 后空） | **算未命名**（留空渲染）。现行为本就提交不了空白名（freeBtn trim 校验 onboarding.js:525-530），此处只是判定层同口径兜底 |
| skip 名字段（「跳过这一题」） | 未命名（现行为：`record` :471 仅非 skip 才 rename） |
| 起名后改名 / 重跑 onboarding 改名 | 已命名，新名即时生效（现事件链不变） |
| 历史会话（displayName 已在 agent.json）重启进未跑 onboarding 的新机器 | 以 agentsData displayName 为准——有值即已命名 |
| displayName 恰为字面量 'Nebula' | **算已命名**（用户真实选择；判定看键不看值） |
| 首帧时序（agentsData 未到 + 无 override） | 算未命名 → Header 留空；agentList 到达后若已命名 → 走现有 `updateHeaderSessionName` 重刷（sessionList/agentList 渲染路径已在） |

**回归面（落码批必测）**：起名前 onboarding 全程截屏（Header 空、气泡无名、开场两泡按裁定口径）；起名瞬间 Header/气泡同步换新名（事件链）；改名后重跑 onboarding；AskUser 项目节点问（project 徽标仍在，不受 ① 影响）；N6 各 `{agent}` 面零变化。

---

## 4 · 硬要求（三风格共同红线）

1. **简约干净**：留白克制、层级单一，禁堆装饰；每屏一个主行动。
2. **按钮清晰**：主按钮（用这句 / 确认 / 选好了）一眼可辨；跳过类次级动作 visibly 退后但可点（🔴 每题必有跳过，禁删）。
3. **文字清晰**：正文对比度达标（暗档尤其），题干 / 选项 / 占位层次分明。
4. **字号合适**：正文 ≥13px、题干 14.5px 基准（现 `.bubble` 尺寸）——可微调但**不小于现值**；题头小字 ≥10.5px。
5. 文案零变更（§6）；IME / 键盘链路、跳过逻辑、候选词「点击=填充」语义等功能面不动。
6. 双主题一等公民：light/dark 同稿同布局，禁只出一档。

---

## 5 · 三套设计语言槽位

| 槽位 | 默认建议 | 一句话意象 |
|---|---|---|
| style-1 | **留白极简线框** | 去底色、细线描边、大留白；卡片退成「纸上的表格」，按钮走描边/实底对比 |
| style-2 | **轻浮层卡片** | 卡片成为柔和浮层（大圆角、柔影、微渐变），气泡退后，控件精致化 |
| style-3 | **气泡融合式** | 卡片长在气泡里：题卡与对话流同材质同语态，弱化「表单感」，强化「对话感」 |

- 风格节点可用更强替代语言，但**须在交付 README 里申报替换**（防三套趋同；申报一句即可，不必请示）。
- 三套必须布局同源（同一张内容结构 §1），语言差异落在材质 / 形状 / 层级 / 色彩，不改信息架构。

---

## 6 · Mockup 渲染规则

1. **未命名态一律按去名渲染**：Header 名字区留空（不写 Nebula/NEBULA，也不写占位斜体注释——就是空）；聊天气泡**无发送者名**（不渲染 `.ask-user-source` 位）。before 截图（§8.3）是「不去名的现样」对照。
2. **文案零变更**：题干 / 幕名 / 进度 / 按钮字 / chips / 跳过字 / 占位符全部逐字取 §1.8 真源；禁改写、禁增删标点。
3. 场景覆盖（每风格至少）：
   - **场景 A（必交）**：onboarding 第一幕第一题全景 —— Header（空名）+ 开场/题文气泡（无发送者名）+ 题卡（输入框 + 「用这句」+ chips 星尘/洛书/阿涅/Nova/海豹 + 「跳过这一题」）。
   - **场景 B（必交）**：AskUser 通用卡 —— 问句 + 选项按钮 + 「其他...」 + 输入区 + 「取消 / 确认」。
   - 场景 C（可选加分）：multi 勾选态 / 已答置灰态 / 模型卡。
4. 单文件自包含 HTML（内联 CSS，零外链零网络字体——截图管线离线跑）；主题切换只用 `prefers-color-scheme` + CSS 变量（见 §7 级联陷阱）。
5. 视口 1280×800 构图（管线锁定），内容垂直方向自然流，底部可留输入区示意（非考核面）。

---

## 7 · 截图工艺（下游逐字照抄）

管线已建好并跑通验证（产物在 `pipeline/_validate/`）。

### 7.1 逐字命令

```bash
cd "/Users/kaiyu/Claude code/Nebflow"
node "design/20261006_askuser_redesign/pipeline/shot.cjs" \
  "design/20261006_askuser_redesign/style-1/mockup.html" \
  "design/20261006_askuser_redesign/style-1"
# → style-1/light.png + style-1/dark.png（style-2/3 同式，换目录名）
```

- 依赖：仓根 `node_modules` 的 playwright + 本机 ms-playwright Chromium（已装，零下载）。
- 固定参数（脚本内锁死，禁改）：视口 1280×800、`deviceScaleFactor:2`、`colorScheme:'light'|'dark'`、`file://` 直开（零端口零进程，不碰 :8080）、截图前 400ms 稳定窗。
- 自检：脚本断言非空白渲染 + 主题真切上 + 产物 ≥2KB，任一失败 exit 1——出图必验。

### 7.2 主题写法规约（实跑踩过的坑）

- **必须用 CSS 变量模式**：`:root` 定义 `--bg/--ink/--card…`，`@media (prefers-color-scheme: dark){ :root{…} }` 覆盖变量值，规则体只引用 `var(--x)`。
- 🔴 禁「base 规则写死色值 + 后置 @media 覆盖同属性」：同特异性下源序后者胜，暗档会静默漏染（验证件第一版实翻车：暗档卡片仍是白底）。参考正确写法：`pipeline/_validate/test.html`。

### 7.3 before 实渲对照（本位已产，作者对照用）

- `before/light.png`、`before/dark.png` —— 真实前端树实渲（route 磁盘直服 + MockWebSocket 注真帧）：未命名态现样 = Header「Nebula」、气泡「NEBULA/还没有名字的我」徽标、待办条「Nebula」行、题卡 + AskUser 卡同屏。
- 复现命令（只读树，零端口）：`node "design/20261006_askuser_redesign/pipeline/before-shot.cjs"`
- 注意：before 图里 AskUser 卡问题文字为合成演示内容（截图线上无真 pending ask），UI 面即真实渲染。

---

## 8 · 目录规约（交付物落点）

```
design/20261006_askuser_redesign/
├── BRIEF.md                      ← 本文
├── before/{light,dark}.png       ← 本位已产（现样对照）
├── pipeline/
│   ├── shot.cjs                  ← mockup 出图管线（下游照抄）
│   ├── before-shot.cjs           ← 现样实渲管线（存证复现用）
│   └── _validate/{test.html,light.png,dark.png}   ← 管线验证件（跑通证据）
└── style-{1,2,3}/                ← 各风格节点交付
    ├── mockup.html               ← 单文件源（场景 A + B 同页两屏，或分文件见下）
    ├── light.png / dark.png      ← 管线产物（1280×800 @2x）
    └── README.md                 ← 设计语言说明段：语言名 / 核心意象 / 与现样差异点 /（若换槽位语言）替换申报
```

- 场景 A、B 若分文件：`mockup-onboarding.html` / `mockup-askuser.html`，各跑一次管线，产物命名为 `light.png`/`dark.png` 放对应子目录（`style-N/onboarding/`、`style-N/askuser/`），README 说明结构。同页两屏亦可（一张图上下两段）。
- 🔴 交付只写 `design/20261006_askuser_redesign/` 子树；`src/` 零触碰（落码是作者裁定后的下一批）。

---

## 9 · 附带发现（本位只记录，不修）

1. **reduced-motion 下 onboarding 流程中断（真缺陷）**：`streamBubble` 的 `onDone: () => resolve(row)`（onboarding.js:287-292）——`prefers-reduced-motion: reduce` 时 `typeInto` 同步 `fireDone`（:227-230），回调触发时执行器里 `const row` 尚未赋值完 → TDZ `Cannot access 'row' before initialization` → 流程死在第一个气泡后（开场泡在，后续全不渲染）。实录：`before-shot` 首版即命此雷（debug probe 捕获 pageerror）。影响：reduced-motion 用户无法完成 onboarding。建议落码批顺手修（`let row` 或 `resolve` 改微任务）。
2. **待办条未命名显「Nebula」**：§2 N5 —— 卡徽标已隐而待办条行仍显裸 agentName，同屏两口径；已纳入 ① 规格第 3 条。
3. **before 截图工艺帧序**：MockWS 注帧必须 `sessionList → historyPage → configData(onboarding:'pending')`——configData 是 GLOBAL 帧保活当前 view，若在 historyPage 前发，问卷流启动时 `activeView` 为 null，全程静默空转（管线注释已记）。

—— BRIEF 完 ——
