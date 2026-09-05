> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow 前端设计质量体系 — HCI/UI/UX 设计工程师 + 设计规格书 + 视觉评审关卡

> 2026-08-17 · 响应问题："前端 UI/交互/动画设计总欠火候，需要人介入修改"
> 纯分析 + 方案文档，未改动任何源码与 agent 定义。数据与依据全部来自现存资产（visual-style skill / card-design skill / 前端质量路线图 / Frontend & qa-frontend 定义与记忆 / User.md）。

---

## 0. 执行摘要

**结论：设计欠火候不是"AI 没创造力"，是流程缺了四个环节——没有设计先行、没有参考检索、没有规格化验收、没有范式沉淀。** 这四个环节全部可以用现有机制补齐（agent + skill + 关卡），核心是新增一名 **design-engineer（HCI/UI/UX 设计工程师）** 站在"设计质量"这个职责点上，配一套"设计规格书 → 规格化实现 → 视觉评审 → 教训沉淀"的闭环。

- **根因**：现有链条是「需求 → Frontend 实现 → qa-frontend 功能验收」。Frontend 是实现者不是设计者（按直觉写，不检索、不规格化）；qa-frontend 是验证者但只对照 6 条铁律（毛玻璃/字重/阴影等**硬规则**），不对照"这个功能应该长什么样"的**规格**；用户是唯一的质量裁判——**质量反馈回路完全外置给了用户**（与质量路线图发现的"bug 反馈回路外置"同构）。
- **方案**：新增 **design-engineer**（standalone 全局 agent，Vision preset，可看截图），职责 = 上游出**设计规格书**（动手前检索 Apple HIG / Material / 微信等成熟范式）+ 下游做**视觉评审**（实现后截图 + vision 对照规格书 PASS/FAIL）。Frontend 按规格书实现；qa-frontend 管功能/契约/资源 + 把规格书的**可断言点**转成 Playwright 断言（视觉回归层）；用户打回 → [DESIGN-LESSON] 归因沉淀进 **design-system 案例库**（与 [USER-RULING] 同构的软参考层）。
- **试点**：客户端**消息搜索**（微信/Telegram 范式，用户点名、边界清晰、全在 nebflow-project 内），全流程 P0 走通后扩到登录页。
- **投入**：P0 角色+模板+试点 0.5-1 天；P1 评审关卡+视觉断言 1-2 天；P2 案例库沉淀持续。复用 C2 Playwright 基建，无新架构。

---

## 1. 问题根因 — 设计质量链路的四个缺口

用户原话（2026-08-17 09:14）："前端设计在人机交互、UI、动画设计上总还是欠缺点意思……很多功能是有优秀设计可参考的……我在想是不是可以专门设计一个人机交互、UI、UX 工程师，让它可以先检索标准、参考设计。"

对照现状（全都有据可查）：

| # | 缺口 | 现状证据 | 后果 |
|---|---|---|---|
| G1 **无设计先行** | Frontend 拿需求直接实现，system.md 是"实现者 + 视觉自查"定位，无"先出规格再动手"环节 | 设计决策散落在实现过程中，无人在动手前定义"应该长什么样" |
| G2 **无参考检索** | 无任何 agent 在动手前检索 HIG/成熟范式（检索能力存在——WebSearch/WebFetch 工具都有，但从没人把这个职责指派出去） | 每个功能从零发明轮子，而不是站在"被做到烂"的范式肩膀上 |
| G3 **无规格化验收** | qa-frontend 视觉回归只对照 visual-style **6 条铁律**（硬规则：毛玻璃/无 overlay/字重/阴影激活态），不对照 per-task 规格 | 功能正确但"不是该有的样子"——铁律覆盖不到的交互/动效/状态设计无人把关 |
| G4 **无范式沉淀** | visual-style 沉淀的是用户**裁定**（硬规则，替换式）；"微信搜索怎么做的"这类**参考范式**无沉淀处 | 每次设计重新检索（甚至不检索）；用户打回的教训只留在会话里，不复用 |

**一句话**：功能质量有 qa-frontend 关卡，设计质量没有关卡。质量路线图（昨日）解决的是"前端会报错"（正确性），本方案解决"前端不好看不好用"（设计质量）——**两条线互补，共用 C2 Playwright 基建**。

---

## 2. 方案总览 — 四道防线

![四道防线](assets/uiux-defenses.svg)

- **防线 1 · 设计先行（上游）**：design-engineer 检索标准/参考 → 产出设计规格书（含参考链接、状态机、可断言点）
- **防线 2 · 规格化实现（中游）**：Frontend 按规格书实现，禁止自由发挥（规格书没写到的 → 回问设计工程师，不猜）
- **防线 3 · 双重验收（下游）**：qa-frontend 功能验收（自动化：冒烟/契约/资源 + 规格书断言）+ design-engineer 视觉评审（vision 对照规格书，合并关卡）
- **防线 4 · 教训沉淀（反馈环）**：用户打回 → [DESIGN-LESSON] 归因 → design-system 案例库 → 下次设计必读

与现有机制的关系：**全部复用，零新架构**。agent 机制（standalone agent）、skill 机制（nebflow/visual-style 同款）、关卡机制（qa-frontend 同款）、纠偏闭环（[USER-RULING] 同构）都已存在——本方案只是把这些机制组合成一条"设计质量"链，并补上缺失的职责点（设计者）与沉淀层（案例库）。

---

## 3. 角色设计 — design-engineer（HCI/UI/UX 设计工程师）

### 3.1 放置决策：standalone 全局 agent（推荐）

| 选项 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. standalone 全局 agent**（推荐） | 设计是横切能力：Nebflow 客户端（nebflow-project）、官网（nebflow-website）、slideblocks、语音识别项目都有前端 UI；设计标准不该被锁在一个团队 | 无团队 memory/rules，需要 skills 承载规范 | **选 A**：规范全部走 skill（visual-style + design-system），不依赖团队上下文 |
| B. nebflow-project 新成员 | 有团队上下文（worktree/qa 惯例） | 只服务一个团队；与其他项目 UI 无协同 | 备选 |
| C. 升级复用 qa-frontend | 零新增 | qa-frontend 已双职责（Scala 回归 + Rust 契约），再加设计评审会过载；且"设计者"与"验证者"应分离——qa 管自动化断言，design-engineer 管设计判断 | 不选 |

### 3.2 agent.json 要点（可执行）

```json
{
  "name": "design-engineer",
  "category": "standalone",
  "displayName": "Design Engineer",
  "preset": "Vision",
  "description": "HCI/UI/UX 设计工程师 — 前端 UI 任务的设计先行者与视觉评审者。动手实现前检索平台规范（Apple HIG/Material 3）与成熟产品范式（微信/Apple 系统应用/Linear 等），产出结构化设计规格书（布局/交互状态机/动效/边界/无障碍/可断言验收点/参考链接）；实现后截图 + vision 对照规格书做视觉评审（PASS/FAIL，附证据）。只设计不写产品代码。",
  "skills": ["nebflow/visual-style", "nebflow/design-system"],
  "tools": ["Read", "Write", "Edit", "Bash", "Grep", "Glob", "WebSearch", "WebFetch", "Pop", "Screenshot", "AskUserQuestion"],
  "useWhen": "前端 UI 任务需要设计先行（新组件/复杂交互/成熟范式功能如登录、搜索）或需要视觉评审（实现后对照设计规格书验收）。Manager 在阈值判定为'设计先行'时调用产出规格书；在实现完成后调用做视觉评审。"
}
```

要点说明：
- **preset Vision**：模型原生看图（与 Frontend/qa-frontend 同款），Screenshot 截图直接看，不依赖 MCP 中转。MCP analyze_image（zai/glm-4.5v）作为补充——当需要把截图解析成**精确像素级文字规格**（如间距数值、颜色值）供无 vision 的 Coder 实施时（User.md FACT 已验证链路）。**vision 对"是否已修复"的精细判断会误报**（User.md 教训）——design-engineer 的评审结论要与二进制取证（Playwright DOM 断言/截图路径）交叉验证，不能只看图说 PASS。
- **skills 订阅**：`nebflow/visual-style`（既有 6 条铁律，强制遵守）+ `nebflow/design-system`（P2 新建案例库，检索协议 + 参考范式）。
- **tools**：WebSearch/WebFetch（参考检索）、Screenshot（看实现结果）、Pop（向 Manager/用户展示规格书与评审报告）、Write（写规格书/评审报告，落 `~/.nebflow/plan/<folder>/design-specs/`）、AskUserQuestion（规格书歧义时向用户澄清）。
- **不写产品代码**：与 qa-frontend 的"只验收不修改"同理，写规格书/评审报告用 Write，禁改 `web/` 源码。

### 3.3 职责分工表（谁做什么）

| 环节 | design-engineer（新） | Frontend | qa-frontend | Designer（官网） |
|---|---|---|---|---|
| 设计规格书 | **负责**：检索 + 产出规格书 | — | — | 官网任务可复用 |
| 实现 | — | **负责**：按规格书实现 | — | 官网实现（Next.js） |
| 功能验收（冒烟/契约/资源） | — | — | **负责**（既有 C2 + 资源契约） | — |
| 规格书断言（自动化视觉回归） | 规格书里定义"可断言点" | — | **负责**：转 Playwright 断言进 CI | — |
| 视觉评审（对照规格书，合并关卡） | **负责**：截图 + vision 出 PASS/FAIL | — | 不重复做 | — |
| 教训沉淀 | **负责**：用户打回归因 → 案例库 | 汇报所见问题 | 汇报所见问题 | 汇报 |

**衔接规则**：
- 上游 → 下游：规格书是 Frontend 实现的唯一依据；规格书 §8 可断言点是 qa-frontend 断言的来源
- 下游 → 上游：视觉评审 FAIL 打回 Frontend（附截图证据）；用户打回 → [DESIGN-LESSON] 归因
- **设计者-评审者不分离是故意的**：规格书是 design-engineer 写的，评审也是它做——因为它要对"设计意图是否被忠实实现"负责。qa-frontend 是独立的第二道眼（功能正确性），两道眼不重叠
- **与 Designer 的关系**：Designer 是官网（nebflow-website）专属实现者（会写代码）；design-engineer 是横切的设计规格+评审。官网 UI 任务同样可走"design-engineer 出规格 → Designer 实现 → design-engineer 评审"，验证跨团队服务能力（P2 试点）

---

## 4. 工作流设计 — 前端 UI 任务标准流程

![标准工作流](assets/uiux-workflow.svg)

### 4.1 阈值判定表（Manager 派单依据，写入 nebflow-project rules.md）

| 任务类型 | 判定 | 理由 |
|---|---|---|
| **新组件 / 新页面**（聊天输入框、设置面板、文件预览器……） | **设计先行** | 没有"已有的样子"可抄，必须定义规格 |
| **复杂交互**（多状态/多分支：搜索、向导、拖拽、多选） | **设计先行** | 状态机不先定义，实现必然走样 |
| **成熟范式功能**（登录、搜索、表单、设置——用户点名"被做到烂"的） | **设计先行** | 有优秀参考，不检索就是浪费 |
| **涉及动效/动画**（新增过渡、入场动画、交互动效） | **设计先行** | 动效是设计欠火候的重灾区，必须规格化（时长/缓动/触发） |
| **视觉风格变更**（配色/字体/玻璃质感参数调整） | **设计先行**（涉及 visual-style 铁律时须先确认用户裁定） | 风格是全局的，草率改造成本高 |
| bug 修复（纯逻辑/样式单点损坏） | 直接实现 | 已有规格或既有组件，无需重新设计 |
| 单属性小调整（改色值/间距/字号） | 直接实现 | 可复用既有 design token |
| 文案/内容修改 | 直接实现 | 非设计 |

**默认倾向**：拿不准 → 设计先行。设计先行多花的时间（规格书 0.5-1 轮）远小于"实现后被打回返工"。

### 4.2 设计先行路径（详细步骤）

1. **派单**：Manager Mail design-engineer，附需求原文 + 相关代码路径 + 阈值判定理由
2. **检索**（0.5-1 轮）：design-engineer 按检索源清单（§6）命中 1-2 个权威范式 → WebSearch/WebFetch 拿原文 → 摘录关键规则；同时必读 visual-style skill（铁律）与 design-system 案例库（若 P2 已建）
3. **产出规格书**：按 §5 模板 → 写入 `~/.nebflow/plan/<folder>/design-specs/<date>_<feature>.md`
4. **确认**：Pop 展示规格书 → 有歧义处 AskUserQuestion 问用户（如视觉方向、参考取舍）→ 用户/Manager 确认后冻结
5. **实现**：Manager Mail Frontend"按规格书实现"（附规格书路径）；**规格书未定义之处，Frontend 回问 design-engineer，不自由发挥**
6. **功能验收**：qa-frontend 跑冒烟/契约/资源 + 规格书 §8 断言（P1 起）
7. **视觉评审**：design-engineer 对隔离实例截图（亮/暗/响应式/关键状态）→ vision 对照规格书逐项核对 → 输出 PASS/FAIL 报告（附截图证据，与 DOM 断言交叉验证）
8. **合入**：PASS → Manager 合并；FAIL → 打回 Frontend（附证据清单）→ 回到 5
9. **沉淀**：用户对交付物打回 → [DESIGN-LESSON]（§7）

### 4.3 直接实现路径

需求 → Manager Mail Frontend 直接实现 → qa-frontend 功能验收（含 visual-style 铁律断言）→ 合入。与现状一致，只是明确"小改动不走设计流程"，避免流程过重。

---

## 5. 设计规格书模板（可直接用）

保存为 `~/.nebflow/skills/nebflow/design-system/spec-template.md`（P0 落地），design-engineer 每次产出规格书按此模板填。

```markdown
# 设计规格书：<功能名>

> 版本：v1 · 日期：<YYYY-MM-DD> · 设计：design-engineer · 关联需求：<任务/需求号>
> 状态：草案 → 已确认（Manager/用户）→ 已冻结（开始实现）

## 0. 一句话目标
<用一句用户能听懂的话说明这个功能解决什么问题>

## 1. 参考与依据（检索产物——每个引用必须带链接）
### 1.1 平台规范（命中的 HIG/Material/Fluent 章节 + 关键规则摘录）
### 1.2 行业范式（成熟产品案例：功能类型 → 范式要点 3-8 条 → 截图/链接）
### 1.3 既有裁定与案例（visual-style 铁律命中条目 + design-system 案例库命中条目）

## 2. 布局与信息架构
<组件清单 + 层级关系 + 尺寸/间距/对齐——引用既有 design token（sapphire.css 变量），禁止引入新 token 除非本规格书显式新增并说明理由>

## 3. 交互设计（状态机表）
| 状态 | 触发条件 | 表现（视觉/行为） | 备注 |
|---|---|---|---|
| 默认 | | | |
| hover | | | |
| focus | | | 含键盘 focus ring |
| active / pressed | | | |
| disabled | | | |
| loading | | | 禁止空白屏 |
| error | | | 可操作的错误提示 |
| empty | | | 引导用户行动的空态 |

## 4. 动效规范
<每个动效一行：触发条件 / 时长 ms / 缓动曲线 / 位移或形变 / 是否尊重 prefers-reduced-motion>

## 5. 视觉规格
<字体/字重/字号、颜色（token 引用）、圆角、边框、阴影、玻璃质感参数——全部引用既有设计系统>

## 6. 边界与异常
<空态 / 错误态 / 超长文本截断 / 窄视口（768/375）行为 / 多语言（若适用）>

## 7. 无障碍
<键盘导航顺序 / focus ring / ARIA 角色与标签 / 对比度 ≥4.5:1 / 非鼠标可达>

## 8. 可断言验收点（qa-frontend 转 Playwright 断言 + design-engineer 评审核对）
- [ ] A1：<可自动化检查项 + 判断标准>（截图：是/否）
- [ ] A2：<…>
（每条格式：编号 + 检查项 + 二值判断标准 + 是否需要截图证据。≥5 条。）

## 9. 参考链接
<全部检索来源 URL>
```

---

## 6. 参考检索怎么做 — 检索源清单 + 结构化方式

### 6.1 检索源清单（写入 design-engineer system.md 或 design-system skill）

| 层级 | 来源 | 用途 | 入口 |
|---|---|---|---|
| **平台 HIG**（权威规范） | Apple HIG | 弹窗/表单/设置/登录/动效的官方范式 | developer.apple.com/design/human-interface-guidelines |
| | Google Material 3 | 组件状态/动效/布局/无障碍 | m3.material.io |
| | Microsoft Fluent 2 | 生产力工具范式 | fluent2.microsoft.design |
| **成熟产品范式**（用户点名"被做到烂"的功能） | 微信 | 消息搜索、聊天界面、设置、扫一扫 | 客户端/网页版截图 |
| | Telegram / Discord | 消息搜索、侧栏、消息交互 | 客户端截图 |
| | Apple 系统应用 | 设置（iOS/macOS）、邮件、备忘录、Spotlight 搜索 | 系统截图 |
| | Linear / Notion / Slack | 快捷键、命令面板、空态、批量操作 | 产品官网/文档 |
| | VS Code | 面板布局、命令面板、设置界面（Nebflow 直接 vendor monaco，最近参考） | code.visualstudio.com/docs |
| **组件库模式**（实现层参考） | antd | 组件交互与状态模式（中文文档） | ant.design/components |
| | Radix UI / shadcn/ui | 无头组件交互 + 无障碍模式 | radix-ui.com/primitives / ui.shadcn.com |
| | WAI-ARIA APG | 无障碍交互模式权威 | w3.org/WAI/ARIA/apg |
| **动效参考** | Material Motion | 动效时长/缓动系统 | m3.material.io/styles/motion |
| | Lottie | 动效案例库 | lottiefiles.com |
| | cubic-bezier | 缓动曲线调试 | cubic-bezier.com |
| **Nebflow 内部** | visual-style skill | 用户裁定铁律（最高优先级） | ~/.nebflow/skills/nebflow/visual-style |
| | design-system 案例库 | 沉淀的参考范式（P2 起） | ~/.nebflow/skills/nebflow/design-system/cases/ |
| | sapphire.css / 组件清单 | 既有 design token 与组件 | src/main/resources/web/css/ |

### 6.2 检索协议（怎么写进规格书）

1. **按功能类型命中**：检索不追求全，追求"命中该功能类型最相关的 1-2 个权威范式"。如消息搜索 → 微信 + Telegram；登录 → Apple HIG Sign-in 章节 + 一个成熟登录页
2. **摘录可操作规则**：不是贴大段原文，是提炼 3-8 条**可执行规则**（如"搜索结果按时间倒序、每条显示会话名+消息摘要+时间，点击跳转原文并高亮"）
3. **标注冲突取舍**：范式之间冲突时（如微信的强反馈 vs Apple 的低调克制），**以 visual-style 铁律和用户 taste（低调克制专业感）为准**，并在规格书里写明取舍理由
4. **全部带链接/截图**：规格书 §9 留痕，评审与沉淀可追溯

---

## 7. 规范沉淀机制 — design-system 案例库 + [DESIGN-LESSON] 闭环

### 7.1 与 visual-style 的分层（关键设计决策）

| 层 | 内容 | 变更方式 | 优先级 |
|---|---|---|---|
| **visual-style**（既有，硬规则） | 用户**裁定**：弹窗毛玻璃/无 overlay/字重/阴影激活态 | [USER-RULING] 替换式（用户改主意 = 最高删除信号） | 最高，冲突即遵守 |
| **design-system**（新建，软参考） | **参考范式**：微信搜索怎么做、Apple 登录怎么做 + Nebflow 适配规则 | [DESIGN-LESSON] 增量式（新案例补充，不替换） | 参考，与铁律冲突时让位 |

两者**同构**（都是"用户反馈 → 归因 → 沉淀 → 下次必读"的纠偏闭环），区别是硬规则 vs 软参考。新建的原因：用户裁定（"弹窗要毛玻璃"）和参考范式（"搜索列表应该按时间倒序"）是两类不同的知识，混在一个 skill 里会导致铁律被稀释。

### 7.2 skill 结构

```
~/.nebflow/skills/nebflow/design-system/
  SKILL.md                        # 总纲：检索协议 + 案例库使用方式 + [DESIGN-LESSON] 流程
  spec-template.md                # §5 规格书模板（P0 落地，独立文件便于 design-engineer 引用）
  cases/
    search/                       # 功能类型目录
      wechat-message-search.md    # 范式条目
      telegram-search.md
    login/
      apple-hig-signin.md
      wechat-login-page.md
    modal/
      macos-panel.md
      vscode-command-palette.md
  rules/
    motion-timing.md              # 跨案例通用规则（如微交互 150-300ms 表）
```

### 7.3 案例条目结构（与规格书 §1.2 同构）

```markdown
# <范式名>（功能类型：<search/login/modal/…>）
## 参考来源（链接/截图，可追溯）
## 成熟范式的关键规则（3-8 条，可操作，不贴原文）
## Nebflow 适配规则（受 visual-style 铁律与 design token 约束的本地化）
## 适用场景（何时用 / 何时不用）
## 来源教训（若由 [DESIGN-LESSON] 沉淀：用户原话 + 打回日期）
```

### 7.4 [DESIGN-LESSON] 闭环（与 [USER-RULING] 同构）

- **触发**：用户对已交付 UI 打回修改
- **归因四类**（design-engineer 分析，写进闭环）：
  1. **裁定冲突** → 用户新裁定 → 升级 [USER-RULING] → visual-style 铁律替换
  2. **缺参考** → 设计时没检索到范式 → 检索协议补条 + 新增/更新案例
  3. **规格缺失** → 状态/边界/动效没写进规格书 → 规格书模板补项
  4. **实现走样** → 按规格做了但做错 → 不是规范问题，是评审关卡漏检 → 评审 checklist 补项
- **记录**：案例条目 `## 来源教训` 段 + 同步 Manager 更新团队 rules.md（若适用）

---

## 8. 与质量路线的衔接 — 视觉断言层接 C2 Playwright 基建

**结论：可行，且是低成本增量。** C2 Playwright 基建（真实实例 smoke.spec.mjs + 截图 + 双主题 + 响应式 + 404 监听）昨日已落地并进 CI；qa-frontend 已在做 visual-style 铁律断言；双主题截图方法论（colorScheme 模拟）已验证。新增的只是"把规格书 §8 可断言点参数化"。

### 8.1 三层视觉验收

| 层 | 执行者 | 内容 | 时机 | 拦截目标 |
|---|---|---|---|---|
| **L1 自动化断言** | qa-frontend | 规格书 §8 可断言点 → Playwright 断言（如"弹窗面板 backdrop-filter 存在""激活态 box-shadow 而非 border-left""hover 色值变化"）+ visual-style 铁律断言 | CI 必跑（frontend-smoke job） | 视觉**回归**（改坏已有设计） |
| **L2 人工视觉评审** | design-engineer | 隔离实例截图（亮/暗/响应式/关键状态）→ vision 对照规格书逐项核对 → PASS/FAIL | 合并关卡（Manager 检查清单含此项） | 设计**质量**（新功能是否"是该有的样子"） |
| **L3 用户验收** | 用户 | 打回 → [DESIGN-LESSON] 归因 | 交付后 | 纠偏闭环（规范持续进化） |

### 8.2 落地要点

- **断言来源**：规格书 §8 是断言清单的唯一来源（qa-frontend 不自己发明断言项，避免"验了没用的"）；规格书冻结后断言清单即确定
- **视觉回归基线**：P1 起对关键页面（聊天主界面/设置/搜索弹窗）建截图基线，首次人工确认，之后 CI 对比（qa-frontend 双主题截图方法论已验证，直接复用）
- **与现有关卡不冲突**：C2 冒烟（运行时错误/404/契约）管"能不能跑"，L1 断言管"样式对不对"，L2 评审管"设计好不好"——三道闸门互不重叠

---

## 9. 分期与验收

### P0 — 最小可行（0.5-1 天）：角色 + 模板 + 检索流程 + 试点走通

| 项 | 内容 |
|---|---|
| P0-1 | design-engineer 角色落地（§3.2 agent.json + system.md：职责/阈值判定/检索协议/规格书模板引用） |
| P0-2 | 规格书模板落盘（design-system/spec-template.md，§5） |
| P0-3 | 试点：客户端消息搜索走通全流程（检索 → 规格书 → 确认 → 实现 → 功能验收 → 视觉评审 → 合入） |

**验收（二值可自动化）**：
- [ ] `~/.nebflow/agents/design-engineer/agent.json` 存在，Manager 可 Mail 调用（preset Vision；Screenshot/WebSearch/WebFetch 可用；skills 含 nebflow/visual-style）
- [ ] 试点产出完整规格书（含 ≥1 个参考链接、状态机表 ≥4 状态、可断言点 ≥5 条）
- [ ] design-engineer 视觉评审输出 PASS/FAIL 报告（含截图证据 + DOM 断言交叉验证）
- [ ] 试点功能用户零介入修改（或 ≤1 处微调且已走 [DESIGN-LESSON] 归因）

### P1 — 评审关卡 + 视觉断言（1-2 天）：制度化

| 项 | 内容 |
|---|---|
| P1-1 | 阈值判定表写入 nebflow-project rules.md（§4.1）；Manager 派单按表执行 |
| P1-2 | qa-frontend 增加"规格书驱动断言"职责（system.md 更新）：规格书 §8 → Playwright 断言 |
| P1-3 | 关键页面视觉回归基线 + CI 对比（frontend-smoke job 扩展） |
| P1-4 | 合并检查清单加"视觉评审 PASS"项（Manager 惯例） |

**验收**：
- [ ] rules.md 含阈值判定表，5 个历史任务类型可无歧义分类（抽查）
- [ ] 试点规格书可断言点 ≥80% 已转 Playwright 断言且进 CI（push 触发）
- [ ] 人为注入视觉回归（如删除弹窗 backdrop-filter / 改回竖线激活态）→ CI 红
- [ ] 设计先行任务的合并日志含 design-engineer 视觉评审 PASS 记录

### P2 — 案例库沉淀（0.5-1 天启动，持续）：范式库 + 闭环

| 项 | 内容 |
|---|---|
| P2-1 | nebflow/design-system skill 落地（SKILL.md + cases/ 结构 + 检索协议 + spec-template.md 迁移） |
| P2-2 | [DESIGN-LESSON] 闭环协议写入（§7.4）；design-engineer/Frontend/qa-frontend/Designer 订阅 skill |
| P2-3 | 首批案例入库（搜索/登录/弹窗各 ≥1，含试点沉淀） |
| P2-4 | 第二试点：客户端登录或官网登录（验证跨团队服务能力） |

**验收**：
- [ ] skill 存在且 4 个角色（design-engineer/Frontend/qa-frontend/Designer）订阅
- [ ] 案例库 ≥3 条可检索案例，每条含参考来源 + 适配规则 + 适用场景
- [ ] 用户 1 次打回 → 48h 内归因 + 入库（闭环可验证）
- [ ] 第二试点走通且首次规格书引用案例库命中 ≥1 条（证明沉淀复用）

---

## 10. 试点建议

| 候选 | 团队 | 范式来源 | 推荐度 | 理由 |
|---|---|---|---|---|
| **客户端消息搜索** | nebflow-project | 微信/Telegram 搜索 | ★★★ 首选 | 用户点名；边界清晰（搜索框+结果列表+跳转高亮）；交互状态多（输入/空/无结果/加载/跳转）——最能验证规格书价值；全在 nebflow-project 内，流程改动最小 |
| 客户端登录 | nebflow-project | Apple HIG Sign-in + 成熟登录页 | ★★ | 用户点名"被做到烂"；但登录涉及后端 token/认证，依赖项多 |
| 官网登录/注册 | nebflow-website | 同上 + 官网风格 | ★★ | 验证 design-engineer 跨团队服务（Designer 实现）；但与 nebflow-project 流程不同步 |
| 客户端设置面板 | nebflow-project | Apple 设置 / VS Code 设置 | ★ | 已有实现，适合 P1 视觉回归基线试点，不适合"设计先行"首发 |

**建议**：P0 只做**消息搜索**；P1 复用消息搜索做断言/基线；P2 加登录（客户端或官网）。理由：一个试点走通全流程验证体系本身，比同时开多个试点更能收敛问题（每个试点失败都可能是流程问题而非功能问题）。

---

## 11. 需要你拍板的决策点

1. **角色放置**：standalone 全局 agent（推荐）vs nebflow-project 团队成员
2. **职责范围**：规格书 + 视觉评审（推荐，闭环）vs 仅规格书 vs 仅评审
3. **试点对象**：客户端消息搜索（推荐）vs 客户端登录 vs 官网登录
4. **推进节奏**：P0 先行试点确认后进 P1/P2（推荐）vs 一次性全上

> 说明：本方案只分析与写文档。P0 落地（agent.json/system.md/skill 模板）需走 entity-creator 流程（design-engineer 属于新 standalone agent 分支）；rules.md 更新由 nebflow-project Manager 执行；规格书模板落盘由 P0 实施时执行。

---

## 附录 — 依据清单

| 依据 | 位置 |
|---|---|
| 用户原话（痛点与思路） | 2026-08-17 09:14 会话 |
| visual-style 6 条铁律 | ~/.nebflow/skills/nebflow/visual-style/SKILL.md |
| Frontend 定位（实现者 + 视觉自查） | ~/.nebflow/teams/nebflow-project/agents/Frontend/{system.md, agent.json} |
| qa-frontend 定位（验证者 + 铁律断言） | ~/.nebflow/teams/nebflow-project/agents/qa-frontend/{system.md, agent.json} |
| 质量路线（C1/C2/合并关卡，昨日落地） | /tmp/frontend-quality-roadmap.md + tests/smoke.spec.mjs + scripts/verify-web-assets.mjs |
| 视觉能力教训（vision 必须配视觉 agent；vision 精细判断误报需交叉验证） | User.md（"UI 视觉调优必须配视觉能力"、"analyze_image 链路已验证"）+ Frontend memory |
| 用户 taste（低调克制专业感） | User.md + visual-style skill |
| 双主题/响应式截图方法论（已验证） | Frontend memory（"双主题视觉验证方法论"、"worktree 视觉验证新模式"） |
| agent 定义格式（standalone/team + preset Vision + skills + tools） | ~/.nebflow/agents/Coder/agent.json、teams/nebflow-project/agents/Frontend/agent.json |
