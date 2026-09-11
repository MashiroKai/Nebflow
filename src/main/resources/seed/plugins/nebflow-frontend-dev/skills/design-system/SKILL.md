---
name: design-system
description: Nebflow UI/UX 软参考案例库——功能类型 → 成熟范式（微信/Spotlight/HIG 等）→ Nebflow 适配规则 → 可断言点 → 踩坑记录。Use when 设计或评审 Nebflow 前端新 UI/交互（弹窗、搜索、列表、面板、动效等）前检索同类功能范式，或沉淀 [DESIGN-LESSON] 教训时追加案例。
when_to_use: design-engineer 产出设计规格书前必读（检索同功能类型案例，复用范式与适配规则，规避已记录的坑）；视觉评审对照规格书时可查可断言点先例；用户打回已交付 UI 归因后按模板追加新案例。与同插件 visual-style 分层：那边是用户裁定硬规则（最高优先级），本 skill 是软参考，冲突时以 visual-style 与用户裁定为准。
language: zh
status: active
last_verified: 2026-08-17
---

# Design System — Nebflow UI/UX 案例库

软参考案例库（P2 质量体系载体）。每条案例 = 一次「设计先行 → 规格化实现 → 双重验收 → 教训沉淀」闭环的可复用沉淀。

**分层纪律**：本 skill 不与同插件 `visual-style`（用户裁定硬规则）混装。范式冲突时以 visual-style 铁律与用户 taste（低调克制专业感）为准。本库只记录「怎么把成熟范式适配进 Nebflow」与「踩过的坑」。

**追加案例**：按下方模板追加编号案例，Evidence 引用必须真实可溯源（规格书版本标注 / 报告文件路径 / 代码行号），终审会抽查。

## 案例模板

```
### 案例 NNN · <功能名称>（<date>，闭环结论 PASS/FAIL）
1. 功能类型：<归类，如"弹窗内搜索">
2. 范式参考：<产品/规范 + 提炼规则 + 链接>
3. Nebflow 适配规则：<铁律适配 / token 纪律 / 取舍及理由>
4. 可断言点清单：<二值断言摘要，供 qa 转 Playwright>
5. 踩坑记录：<裁定/误报/返工，每条带 Evidence 出处>
6. 附录（可选）：<测试基建/环境经验>
```

---

### 案例 001 · 消息搜索弹窗（2026-08-17，闭环 PASS）

1. **功能类型**：弹窗内搜索（跨会话消息搜索，提交式 → 渐进式升级）
2. **范式参考**
   - 微信「查找聊天内容」（用户点名）：输入即出结果、结果按会话聚合分组、组内时间倒序、点击定位高亮（产品行为观察；https://weixin.qq.com/）
   - macOS Spotlight：渐进搜索、首条 Top Hit、↑/↓+Return 键盘契约、**Esc 两段式**（有内容先清空、空时再关闭）（https://support.apple.com/guide/mac-help/search-with-spotlight-mchlp1008/mac）
   - Apple HIG · Searching：结果即时显示、空态/无结果态都有引导文案（https://developer.apple.com/design/human-interface-guidelines/searching）
   - WAI-ARIA APG Combobox：focus trap / aria-activedescendant / Enter 激活 / Esc 关闭（https://www.w3.org/WAI/ARIA/apg/patterns/combobox/）
   - 取舍：不采用 Telegram 内嵌搜索栏——跨会话搜索需承载过滤器+分组列表，居中弹窗容量合适且零风格风险
3. **Nebflow 适配规则**
   - 保留居中毛玻璃弹窗形态：overlay 透明（禁背景暗化）+ 面板 `--glass-bg`+blur(24px)（铁律 1）
   - 零新颜色 token 纪律：唯一新色值 = 既有蓝宝石蓝 rgb(91,127,191) 的透明度变体（mark 暗色 0.36、active 左缘条 0.5/0.6），其余全部 `var(--*)` 既有 token
   - 键盘选中态 = hover 底 + inset 2px 左缘条（比 hover 多一层标识，不依赖 outline，克制但可见）
   - ARIA combobox/listbox 全家桶 + status 行 aria-live="polite" + 焦点归还触发按钮
   - 300ms debounce + 递增 requestId 竞态丢弃 + AbortController 中止；reduced-motion 全量适配
4. **可断言点清单**（规格书 §8 A1-A10，qa 已转 Playwright 全过）
   A1 无遮罩+毛玻璃 computed 值 · A2 渐进搜索 800ms 内出状态 · A3 Esc 两段式 · A4 ↓/Enter/activeIndex 语义+flash · A5 无结果态双 i18n key · A6 mark 亮 0.28/暗 0.36 · A7 跨会话分组三重断言（≥2 组头/唯一性/计数=条目数）· A8 focus trap 20 次 Tab 不逃逸 · A9 375px 增量口径（见踩坑③）· A10 reduced-motion animation ≤0.01s
5. **踩坑记录**（三轮裁定，Evidence = 规格书版本标注 `~/.nebflow/docs/Nebflow/20260817_message-search-spec-v2.md`）
   - ① **i18n key 先行**：v1 无结果态建议句文案未指定 key，断言只能比字面硬编码中文 → v2 裁定独立 key `search.noResultsSuggestion` 走 `t()`，断言比对运行时 locale 输出（规格书头 v2 标注①、§3 无结果态行、A5「**(v2)**」；qa README「规格 v2 裁定」第 1 条）
   - ② **activeIndex 显式语义**：「首条自动 active」是口头语义，§3 与 A4 断言链存在歧义 → v2 裁定显式语义「渲染完成瞬间 activeIndex=0，↓ 后 =1」（规格书头 v2 标注②、§3 结果态行「**(v2)**」；qa README 第 2 条）
   - ③ **增量断言口径**：A9 原「全文档 scrollWidth ≤375」误伤既有 #daemon-panel/#canvas-panel 在 375px 视口的本底溢出（与本特性无关）→ v2.1 改为增量口径「打开态 === 关闭态基线 + 弹窗子树无溢出」，既有问题另立任务（规格书头 v2.1 标注③、A9 行「**(v2.1)**」）
   - **共性提炼**：**二值断言的口径必须在规格书里显式到可执行**——凡涉及文案（→i18n key）、顺序/选中（→显式状态语义）、布局度量（→增量基线口径）的断言，写规格书时就锁死口径，否则验收阶段必然返工
6. **附录 · qa 测试基建三坑**（Playwright 隔离实例经验，Evidence = `~/.nebflow/docs/Nebflow/assets/20260817_msg-search-qa/`）
   - onboarding 异步遮罩：`.onboarding-overlay` 无 id、随 configData WS 消息异步出现并拦截全部指针事件 → seed `onboarding.json`(state=skipped) + 防御性等待分支（helpers.mjs:63-79）
   - 单会话架构无侧栏列表 DOM：`.session-item` 已移除（sidebar.js:1504），激活会话须 `import('/js/sidebar.js')` 调 `switchToSession()` 并轮询 state.activeSessionId（helpers.mjs:91-110）
   - 后台监视器误杀：长驻隔离实例会被后台任务机制 SIGTERM（instance.log 末行 runner exit 143）——跑长任务用 `run_in_background` 受跟踪，勿用 `&`/`nohup` 逃逸

**全链证据**：规格书 v2.1 `~/.nebflow/docs/Nebflow/20260817_message-search-spec-v2.md`（v3.1 迭代版迁入 docs 后此处补链） · 实现 archive/scala @ 9e2b6d2a（4 commits）· qa 报告 `~/.nebflow/docs/Nebflow/assets/20260817_msg-search-qa/`（report.json + run-a1-a10.mjs + shots/）· 视觉评审 PASS `~/.nebflow/docs/Nebflow/20260817_msg-search-visual-review.md`（含交叉验证声明与 F-1 muted 对比度非阻断发现）

---

### 案例 002 · Flow Map 图谱视图原型（2026-09-03，v3.1 被作者打回 → v3.2 闭环）

1. **功能类型**：全屏图谱视图（DAG 执行拓扑可视化：层级布局 + 连线 + 节点卡 + 相机交互）
2. **范式参考**：Obsidian 图谱/Canvas 交互壳 · CAD 式滚轮锚点缩放 · 苹果 HIG Direct Manipulation（https://developer.apple.com/design/human-interface-guidelines/direct-manipulation）；取舍：力导向布局否决（执行拓扑需要确定的方向语义与 WS 稳定性），层级分层 + 空间打包
3. **Nebflow 适配规则**：节点卡逐字对齐产品 `.solar-node.fm-node`（零新样式）；相机数学 = canvas-zoom-follow-cursor-spec 方案 A；详情面板毛玻璃无遮罩（铁律 1）
4. **可断言点**：V1-V20 见 `~/.nebflow/docs/Nebflow/20260903_flowmap-graphview-v3-final.md` §可断言验收点（回归 28/28）
5. **踩坑记录**（v3.1 被打回「平的/禁 emoji/文字出卡」三点，归因与 Evidence = `assets/20260903_flowmap-graphview/shots-v3.2/` + git 47d61ea vs 17dbda3）
   - ① **实现走样 + 规格缺方向断言**：v3.1 布局 Kahn 分层 `(ups.get(id)||[]).size` —— 空数组无 `.size`（undefined 非 0）→ 入度 0 节点永不入队 → 全部 level=0 → 连线全水平成「平图」；**24/24 回归全绿却没拦住，因为没有任何「上游 y < 下游 y」方向性断言** → 教训：布局类规格书必须含**几何方向断言**（方向/层级/相邻性），不能只断言画幅比例与分布计数
   - ② **flex column + align-items:center 的 ellipsis 陷阱**：nowrap 文本子盒未设 `max-width:100%` 时按内容宽撑出容器，overflow:hidden 只裁显示——scrollWidth 仍计入溢出、且省略号永不触发（盒宽=内容宽）→ 教训：卡内每个文本行都要 `max-width:100%`，且「不溢出」断言落在卡盒级 scrollW/H≤clientW/H（截断行自身 scrollW>clientW 是 ellipsis 固有形态，不能拿来当断言）
   - ③ **相机型画布的 DOM 滚动残留**：overflow:hidden 容器仍可被程序化滚动（element.focus()/scrollIntoView/Playwright auto-scroll），残留 scrollTop 让「相机已居中」的断言假失败 → 教训：相机独管平移的画布要加 `scroll` 事件归零防线，自动化测试点击前用应用自身相机带入目标（勿依赖 Playwright 自动滚动）
   - ④ **快照驱动原型 + 活系统**：从活实例直写的 flow-map.json 取数，两次 build 间隔 2 分钟节点数就变（35→38）→ 教训：回归断言勿硬编码节点数/状态分布（动态计数），快照时间戳写进头注释与文档
   - ⑤ **滚动可落在祖先级 + 离屏面板制造幻影溢出区**（2026-09-04 Galaxy v2.0 原型 19 断言收尾，归因=规格缺失，Evidence = `~/.nebflow/docs/Nebflow/20260904_flowmap-galaxy-v2-verify-closeout.md` §2 + 探针 probe-chain.mjs）：③ 的升级版——(a) 原生 **focus scroll-into-view**（Tab 导航/`el.focus()`，无法 preventDefault，只有 `focus({preventScroll:true})` 可抑）会把视口外聚焦目标的滚动写进**任一** overflow:hidden 祖先，与相机 transform 叠加后「数学全对、落点全错」；(b) 关闭态面板 `translateX(100%+24px)` 的位移**计入可滚动溢出区**，使 `.fm-stage` 这类中间容器变成可编程滚动容器——面板开合过渡中 `dClose.focus()` 落在屏外坐标 → stage scrollLeft=483 → 整个舞台被推出窗，后续所有绝对坐标交互（含自动化 mouse.down）落空 → 教训：**归零防线必须挂满整条 positioned 祖先链**（stage+viewport，不能只挂 viewport），且验收断言前先 `elementFromPoint` 探活落点
