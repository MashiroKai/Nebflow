---
type: report
status: done
related: "[[20261101_174600_delegate-status-audit__kernel]]"
---

# Nebula 退役批（显示层身份收口）

> 批令（作者令 2026-11-01 逐字）：「Nebula 退役了」——界面里不该再有 Nebula 的身影。凡**用户可见面**出现 Nebula 的地方都要改：主 agent（根会话）创建的东西标**用户给它起的名字**（显示名，当前是 Spider，从名字权威处读，不许硬编码）；后台 agent 标**它们自己的身份**（delegate 短名 / 设备名 / type，沿用既有徽标契约）。
> 图谱：[[index]] · 词表 [[glossary]]。纪律：只改显示层，不动中枢地址与协议标识。

## 0. 结论先行

| 面 | 改前 | 改后 |
|---|---|---|
| i18n 用户可见值里的 Nebula（zh+en） | 8 处（4 键 × 双语） | **0** |
| 显示层取名字的出口 | 5 处各自读 `displayName \|\| name`（根 agent 读到机制名） | **1 处**（`js/rootName.js#rootDisplayName` / `#agentDisplayName`） |
| 后台任务面板来源 chip / 详情卡来源行 | 后端 `originLabel` 直显 = `Nebula` | 显示名（Spider） |
| AskUser 卡片归属行 | 帧 `agentName` 直显 = `Nebula` | 显示名 |
| 跨端注入行 header 的 PROJECT 段 | `MAIL · NEBULA · …` | `MAIL · SPIDER · …` |
| 机制名在前端字面量 | 76 行（散落 10 文件） | 49 行，其中**唯一定义点** `ROOT_AGENT_KEY`（`js/rootName.js:26`），其余全是判定/协议键 |

一句话：**名字只有一个出口，机制名一个定义点，用户可见面零残留。**

## 1. 全量扫（A/B/C 分类表）

扫描命令（前后同一条，可在任一 checkout 复跑）：

```bash
# ① i18n 用户可见值（注释外的字面值）
grep -oE "'[^']*':\s*'[^']*Nebula[^']*'" src/main/resources/web/js/locales/{zh-CN,en}.js | wc -l
# ② 前端非注释行（含判定键、协议键、字面量）
grep -rn "Nebula" src/main/resources/web/js --include=*.js | grep -vE ":[0-9]+: *(//|\*|/\*)" | wc -l
```

读数：① **8 → 0**；② **76 → 49**（49 行的归属见 §1.C / §1.B，逐行复核无显示面）。

### A 类 — 用户可见，必须改（本批已改）

| 面 | 文件:行（改前） | 改法 |
|---|---|---|
| 后台任务面板·行内来源 chip | `js/main.js:3174 bgTaskOrigin`（`label = task.originLabel` = 后端 `RootAgentIdentity.Name`） | `cat==='nebula'` 整体改读 `rootDisplayName()` |
| 后台任务面板·详情卡来源行 | `js/bgTaskOutputPopup.js:752 originText` | `task.origin==='nebula'` ⇒ `rootDisplayName()` |
| AskUser 卡片归属行 | `js/askPending.js:56 askSourceLabel`（回 `source.agentName`） | 根 asker ⇒ `rootDisplayName()`；后台 asker ⇒ `agentDisplayName()` |
| AskUser 引用身份 / 权限卡来源 badge | `js/chat.js:3125`、`js/chat.js:3545 perm-source-badge` | `agentDisplayName()` |
| 跨端注入行 header（PROJECT 段 = 引擎 `NotificationHeader.RootProject`） | `js/chat/injectedRows.js:468 renderedLabel` | 新 `displayIdentitySegment()`：只把机制名段换显示名，其余段逐字 |
| 注入行 sender 段 | `js/chat/injectedRows.js:233` | 同上映射 |
| 左侧 nav agent 列表 / 会话分组头 / 会话头标题 | `js/sidebar.js:216/236/2013` | `agentDisplayName()` / `rootDisplayName()` |
| 会话头显示名（agentProfile 镜像） | `js/agentProfile.js:206 headerLabel` | 同上 |
| Agent 管理卡片 / 详情头 | `js/agentManager.js:76/275` | `agentDisplayName()` |
| Flow 子会话浮窗标题 | `js/flowAgentPopup.js:475` | `agentDisplayName()`（模型徽标 fetch 仍走机制键 = 协议面不动） |
| i18n 内容字典 4 键 × 双语 | `content.agent.kernel.desc`、`content.plugin.visual-report.desc`、`content.project.general.desc`、`content.skill.visual-report/visual-report.desc` | 去名（中性表述「主 Agent / the main agent」）——`contentText()` 不做占位符插值，故不塞 `{agent}` |
| 语音球预设英文名 | `js/orbPresets.js:38`（`nebula` 预设 `en: 'Nebula'`） | `en: 'Starcloud'`（`zh` 本是「星云」；预设 id 是存储键，不动） |

### B 类 — 内部机制，不改（含「会漏到 UI 的已筛掉」结论）

| 面 | 出处 | 判定 |
|---|---|---|
| 根 agent 身份键（判定 / 会话 meta / API 路径 / 探针 URL） | `js/rootName.js:26 ROOT_AGENT_KEY`、`js/socialPanel.js:420`、`js/taskList.js:60`、`js/flowMapTab.js:691/804/869/1117/1295`、`js/main.js:2000/2602` | 键值判定，非显示 |
| `/model` 角色枚举与链读写键 | `js/modelPanel.js:142/440/670` 等 | 角色键；显示走 `t('model.roleNebula', {agent})` = 显示名 |
| i18n **键名** `model.followsNebula` / `model.roleNebula` | 两份 locale | 键名不是文案；值为 `{agent}` 占位，已吃显示名 |
| 引擎侧协议/提示词字面（`NotificationHeader.RootProject`、`MailTool` 地址面、`BgTaskRegistry.originFor`、`NodeEditTool` out 目标 `"Nebula"`、tool 描述与工具错误） | `src/main/scala/**`（868 处） | 协议与 LLM 面；显示层已做归一（header PROJECT 段、originLabel、sender），故不落到 UI |
| 引擎 seed 项目描述 `"...Nebula dispatches, nodes execute."` | `core/seed/SeedService.scala:389` | 数据面信号（`contentI18n` 的 i18n 键优先于服务端值），显示面由 A 类 i18n 键接管；不改 seed 行为 |
| `nebula-busy` 状态 id、`nebula-chain` resolvedFrom、`nebula` origin 类别 | 各处 | 类别 id，非文案 |
| 英文思考气泡的天文意象（"Traversing the nebula…" 等 4 条） | `locales/en.js:1630/1649/1654/1658` | 小写天文通用名词，不是 agent 身份；按「不顺手改无关文案」保留（如需一并清是另一批） |

### C 类 — 历史文档 / 测试夹具

- 测试**断言**钉死旧名的两处已按新契约改写：`tests/bg-device-origin-chip.spec.mjs`（T3 chip 由 `Nebula` → `Spider` + 新增「退役名零残留」断言）、`tests/bg-device-name-chip-shots.mjs`（plain 行 chips → `[设备名, Spider]`）。
- 夹具里的 `agentName: 'Nebula'` / `originLabel: 'Nebula'` / `header: 'DELEGATE · NEBULA · …'` **保留**：那是帧数据形态（与真实引擎一致），显示层必须能吃下它并换名字——正是本批的验收对象。
- 存量历史行（磁盘上已落 `senderName: 'Nebula'` 的旧引用/旧台账）按 C 类不迁移：新渲染路径不再产生这类值。

## 2. 单一权威（收口）

`js/rootName.js`（叶模块：只 import `state.js` + `i18n.js`）现在是**唯一取名字出口**：

```
rootDisplayName()     根 agent → onboarding 覆盖 → agent.json 探针 → agentList 行的真名 → t('agent.rootGeneric')
agentDisplayName(key) 任意 agent 键 → 根 = rootDisplayName()；其余 = 自身 displayName，缺省回落键本身
ROOT_AGENT_KEY        机制名唯一字面点（判定 / API / 协议面用）
```

三条硬约束（写进模块头注）：机制名**永不**作为显示回落；`agentList` 行的 fallback 毒化值（服务端把未命名折叠成机制名）在出口处被过滤；未命名根 agent 读通用称呼 `agent.rootGeneric`（zh「主 Agent」/ en "Main agent"）。

## 3. 实拍与读数（evidence）

测试 = `tests/nebula-retired-faces.spec.mjs`（真实链：WS 帧 → main.js onMessage → 各渲染面；零伪造 state / 零伪造 DOM）。**ALL GREEN**，零 console/pageerror。

产物 `evidence/20261101_nebula-retired__delegate-nebflow-cd7a9fbd/shots/`（深/浅 × 桌面 1440 / 手机 375 各一套）：

| 面 | 文件 | 读数 |
|---|---|---|
| 后台任务面板（本机行 + 远程行） | `bg-panel-{desktop,narrow}-{dark,light}.png` | 本机行 chips = `[Mashiros-MacBook-Pro, Spider]`；远程行 = `[DESKTOP-IJBBEOT]` |
| 后台任务详情卡来源行 | `bg-detail-*.png` | subtitle = `job-local · Spider` |
| AskUser 卡片归属行 | `ask-badge-*.png` | badge = `Spider` |
| delegate 完工卡 | `delegate-receipt-*.png` | 胶囊 = `DELEGATE · INTERNAL · COMPLETED` |
| 跨端注入行 header | 断言（同 spec） | `MAIL · SPIDER · MANAGER · RESULT`（机制名段已换，其余逐字） |
| 未命名腿 | 断言（同 spec） | `rootDisplayName()` = `主 Agent` ≠ 机制键 |

## 4. 回归

- 改后跑绿：`bg-device-origin-chip`(3)、`bg-device-name-chip-shots`（16 断言 ×4 形态）、`bg-merged-panel`、`bg-mode-badge`、`askuser-bubblename`、`askuser-badge-conditional`、`askuser-badge-mirror`、`askuser-dup-card`、`askuser-expandfix`、`delegate-receipt-card`、`onboarding-name-header`、`agent-identity-sync`、`content-i18n`、`model-chain-slim`、`sidebar-plugins`。
- **存量红（与本批无关，基线对照已证）**：
  - `bg-task-output-viewer`（4 例）：选择器仍是 `#bg-dropdown > .bg-dropdown-list`，分区批 2026-10-29 ③ 后任务行落 `.bg-section-tasks` ⇒ 0 行。取 HEAD 的 web 树（`git archive HEAD`）跑同一 spec，4 例同样红。
  - `modelpanel-roles-hiddenfilter` M2b：断言 `js/plugins.js` 含 `const PANEL_HIDDEN_AGENTS = [...]`，该常量在 HEAD 已不存在（现实装于 `modelPanel.js` 的 `PANEL_HIDDEN_ROLES`）。
