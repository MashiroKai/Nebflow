# UI 实测巡检（重启后 · 真实浏览器）__20261107

> 巡检对象：新实例 pid 45825 · http://localhost:6138 在线态（引擎刚完成重启）
> 巡检方式：Playwright 驱动真实 Chromium 打开真实引擎（ticket 兑换登录），亮/暗 × 桌面 1440×900 / 手机 375×812 共 4 个变体逐面操作取证；**无 mock、无假数据注入、未跑全量 sbt**
> 证据根目录：`reports-evidence/ui-audit-post-restart/`（131 张 PNG + `_data/` 机器可读明细）
> 时间口径：2026-11-07 上午（本地），由 HTTP `Date` 头与工作区文件 mtime 推算

---

## 0. 结论（先看这一段）

**①「浏览器还在用旧缓存」这条假设，已被实测否证。** 四条独立证据链全部指向「服务端与客户端字节一致」：

| 链 | 检查 | 结果 |
|---|---|---|
| A | 线上 `index.html` 引用的全部 54 个 `css/js` 静态资源的 `?v=<16hex>` 是否等于该文件真实 sha-256 前 16 位 | **54/54 全部相符**，0 个缺版本、0 个不符、0 个非 200（`_data/cache-check.json` → `assets`） |
| B | Service Worker 记录的索引哈希 vs 线上索引哈希 | **完全相同**（`e9ff64ce8323756992246911ceec29d4`）；SW 缓存 `nfsw-v1` 290 条，导航请求网络优先（`_data/cache-check.json` → `indexHash`） |
| C | 冷 context（CDP `Network.setCacheDisabled` + 反注册 SW + 全新存储）与温 context（持久 profile、SW 已接管、第二次访问）同页渲染指纹 | 关键面 computed style **逐项相同**（body/活动栏/侧栏/会话/输入条/顶栏）；资源条目数同为 250；差异仅在动态内容（时间戳、耗时徽标）（`_data/cache-check.json` → `compare`、`00-cache/*.png`） |
| D | 与代码台账对照 | 静态路由按 `?v=` 给 `immutable`、裸请求 `no-cache`（`src/main/scala/nebflow/gateway/StaticRoutes.scala:604-610`），SW 有索引哈希对账 + 硬刷新/`?nfsw=0` 逃生门（`sw.js` 文件头） |

⇒ **「很多 UI 问题」不是陈旧缓存造成的。** 下面的清单是当前代码在真实渲染下的可复现问题。

**② 本次实测确认的真缺陷 7 条**（4 条明显、3 条瑕疵），另有 **6 条自动化误报/设计如此已逐条排除**（见 §4，这部分同样重要——它说明清单里剩下的每一条都过过验证）。
其中手机层的两条（待办不可达、消息操作按钮 13×13）与顶部浮层压字，是本次唯一集中的成簇问题；桌面端的设置/插件/画布/项目面整体是干净的。

---

## 1. 缺陷总表（按严重度排序）

> 严重度：**阻断**＝功能不可用；**明显**＝可感知的错误状态/遮挡/难操作；**瑕疵**＝影响观感或可读性但不挡事
> 判定：**真缺陷**＝客观违背设计语言/可读性/遮挡/状态正确性；**设计如此**；**需作者裁定**＝依赖产品意图

| # | 面 + 主题 + 尺寸 | 现象（一句） | 复现步骤 | 截图 | 严重度 | 判定 | 建议修法（一句） |
|---|---|---|---|---|---|---|---|
| D1 | ⑥ 待办入口 · 亮/暗 · 手机 375×812 | 手机层**没有任何可见的待办入口**：`#task-list` 在手机层 `display:none`（0×0），唯一含「待办」字样的 `#top-overlays` 仅 8px 高、内容不可见；「工作台」页也没有待办行 | 手机视口登录 → 消息页找待办（无）→ 工作台（只有 项目/插件/token 用量/定时任务/心跳/后台任务/设置） | `06-todo/light-mobile__1-todo-open.png`、`10-mobile-faces/light-mobile__workbench.png`、`_data/mobile-todo-entry.json` | 明显 | 真缺陷（若手机端本就不做待办 ⇒ 降为设计如此） | 在手机 dock/工作台补一行「待办」，或把回合头里的「N 待办」chip 在手机层渲染出来 |
| D2 | ① 主会话 · 消息操作 · 亮/暗 · 手机 | 消息操作按钮（复制/引用）命中盒 **13×13 px**、常态 `opacity:0.35`；手机无 hover ⇒ 既不显眼也很难点中（每变体 58 处） | 手机视口 → 任一消息行下方元信息行 → 点「复制/引用」 | `10-mobile-faces/light-mobile__overlap-clip.png`、`01-chat/light-desktop__5-row-actions.png`、`_data/findings.json`（kind=touch） | 明显 | 真缺陷 | 手机层把命中盒提到 ≥40×40（图标视觉尺寸不必变），常态 opacity 提到 ≥0.6 |
| D3 | ⑦ 浮动层 · 顶部 chrome · 亮/暗 · 手机 | 顶部圆形菜单钮（48×48）在滚动到某些位置时**压住消息元信息行的时间戳**（13 次/变体），时间戳被遮且不可点 | 手机视口 → 会话滚动使某条消息的时间戳进入顶部 64px 区 | `10-mobile-faces/light-mobile__overlap-clip.png`（「10:07」被圆钮压住）、`_data/findings.json`（kind=occluded） | 明显 | 真缺陷（遮挡） | 顶部 chrome 加一条不透明条或给消息列补 `padding-top`＝chrome 高度，避免浮钮与内容同层 |
| D4 | ① 主会话 · 会话内浮标 · 亮/暗 · 手机 | 代理名浮标「Spider」半透明压在同一位置的消息正文上，正文被遮掉半行字 | 同 D3 的滚动位置（会话顶部第 1–2 条气泡） | `10-mobile-faces/light-mobile__overlap-clip.png`、`06-todo/light-mobile__1-todo-open.png` | 明显 | 需作者裁定（可能是滚动过渡的固定头设计） | 浮标改为滚动时淡出，或移到气泡上方留白处，不与正文同层 |
| D5 | ④ 委托回执徽标 · 会话内状态字 · 亮/暗 · 桌面 | 回执徽标状态词对比度不足：`INTERNAL` **2.66:1**、`FAILED` **3.55:1**（11px，AA 需 4.5:1）——而这是最该被读清的状态字 | 会话内任一 `DELEGATE · INTERNAL · FAILED` 回执行 | `01-chat/light-desktop__1-base.png`、`_data/findings.json`（kind=contrast, `nf-rcpt-seg`/`nf-rcpt-badge`） | 瑕疵 | 真缺陷（可读性） | 段词前景改实色（`rgba(91,127,191,.85)`→`#3f66a8`），`FAILED` 红加深到 `#b3352f` |
| D6 | ② 文件树 / ③ 项目卡 · 次级灰文本 · 亮/暗 · 桌面 | 次级文本稳定落在 3.17–3.4:1（`#8b8e96` on `#fbfbfc`；暗色 4.07:1），涉及文件树标签、项目字段值/名称、会话摘要（12–13px，AA 需 4.5:1） | 打开文件树/项目面板，读 `AGENTS.md`、工作区路径、会话摘要等次级行 | `04-agents/light-desktop__2-agents-panel.png`、`09-tasks/light-desktop__1-projects.png`、`_data/findings.json`（kind=contrast） | 瑕疵 | 需作者裁定（是否属设计语言的 muted gray 档） | 若要过 AA：`--muted` 提一档（`#8b8e96`→`#6f737c`），或把这些行升到 4.5:1 的下限 |
| D7 | 全局控制台 · 失败请求 · 全尺寸 | 每次打开控制台都有固定几条失败请求：`404 /api/portal/status`、`404 /api/projects/<x>/flow-map`（2 个项目）、`502 /api/avatars/<id>`；**无可见破图**（已兜底），只在 console 里报错 | 打开控制台 → DevTools console / network | `_data/findings.json`（`report[*].netErrors`）、`_data/verify.json`（`brokenImgs: []`） | 瑕疵 | 真缺陷（低危：状态被静默） | 无 relay 时 `/api/portal/status` 回 204 或前端静默；flow-map 404 归「无图」空态；avatar 502 落失败兜底缓存 |

---

## 2. 逐面实测结果（9 个面 × 4 变体）

| 面 | 桌面 @1440×900 | 手机 @375×812 | 结论 |
|---|---|---|---|
| ① 主会话/输入区（含输入编辑态、附件气泡、队列） | 输入区正常；长文本换行/发送键位正确（`01-chat/light-desktop__2-typing.png`）；附件走 `#attach-btn` → filechooser 可正常挂载并出附件条（364×28，图片经 blob 正常解码：`01-chat/light-desktop__3-attach.png`）；**消息无「编辑已发送」入口**，本报告编辑态＝输入态 | 见 D2/D3/D4；手机点附件**不发起 filechooser**（走相册/拍照菜单），手机附件气泡本轮未取证 | 输入区本身无布局缺陷；手机消息操作与顶部浮层是问题点 |
| ② 左侧栏与折叠 | 折叠/展开/树滚动正常（`02-sidebar/*`） | 手机层侧栏由 dock 承担，正常 | 仅 D6 对比度 |
| ③ 设置面板全部分区 | 账号/设备/运行时（思考模式/LLM 日志/冻结时间/STT/旧工具清理）/语言…全分区可滚动到底（`03-settings/*sec-01..03`，内容高 2075px、可滚到底） | 手机层为整页设置（375×738，内容 2234px），分区可达（`10-mobile-faces/light-mobile__settings-1..4.png`） | 无功能缺陷 |
| ④ 后台智能体 / agentflow / 插件面板 | 插件面板 50 技能 + 2 MCP 全部渲染，摘要 2 行裁切＝设计（`04-agents/light-desktop__2-agents-panel.png`） | 同（`04-agents/*mobile*`） | 无缺陷；面板标题「插件」重复一次（瑕疵级观感，未计入表） |
| ⑤ 画布 / 卡窗 / 独立窗口 | 画布面板启动即在位（878,16,552,874），可打开文件预览（`05-canvas/light-desktop__2-file-preview.png` 显示 glossary.md 渲染） | 画布可用 | 无缺陷（层叠已核实无冲突，见 §4-F2） |
| ⑥ 待办面板与手机待办入口 | 桌面：回合头「0 待办」chip + `#todo-island` 弹出面板、`+` 新建（`06-todo/light-desktop__1-todo-open.png`） | **见 D1：入口缺失** | 桌面正常 / 手机不可达 |
| ⑦ 通知横幅与浮层 | 断连横幅本体正常（握手被拒时实拍：`07-notify/desktop__reconnect-banner-real.png`，「连接断开 · 正在重连」30px 条）；接管弹窗（`#takeover-box`）正常渲染并可「退出/重新接管」 | 同横幅（`07-notify/mobile__reconnect-banner-real.png`）；接管弹窗满屏（`10-mobile-faces/light-mobile__nav-kanban.png` 即该态） | 横幅与浮层均为设计内行为；已登录态的真实断连未能在本轮触发，见 §4-F5 |
| ⑧ 文件浏览器与 diff 视图 | 文件树/预览正常；「变更文件」面板当前为「尚未打开 diff」空态（`08-explorer-diff/*`） | 同 | 无缺陷；**diff 面未取到真实 diff（空态）**，见 §5 |
| ⑨ 任务/合并队列面板 | `#task-list`「0 待办」条 + 项目面板（`09-tasks/light-desktop__1-projects.png`） | 手机层 `#task-list` 隐藏（同 D1） | **合并队列当前无在队项**，只见空态，见 §5 |

---

## 3. 证据与复现

所有脚本都在本目录，可直接重跑（只读，不写引擎状态）：

| 脚本 | 作用 |
|---|---|
| `cache-check.mjs` | 缓存陈旧四链取证（资产指纹 / SW 索引哈希 / 冷暖 context 渲染指纹对比） |
| `harness.mjs` | 主巡检：4 变体 × 9 面，逐面截图 + 自动检测（对比度/遮挡/裁切/横向溢出/小字号/触点数/失败请求）。支持 `THEMES= SIZES= ONLY=` 过滤 |
| `mobile-faces.mjs` / `mobile-todo-entry.mjs` | 手机层专项：dock 各面、设置入口、待办入口定位 |
| `modal-check.mjs` / `verify.mjs` / `verify2.mjs` / `verify3.mjs` | 逐条证实/否证候选缺陷（层叠、遮挡、破图、裁切、断网时间线） |
| `final-probe.mjs` | 编辑态入口、断连横幅、遮挡特写 |

机器可读明细：`_data/findings.json`（2818 条自动检测原始记录）、`_data/cache-check.json`、`_data/verify.json`、`_data/stacking2.json`、`_data/mobile-todo-entry.json`、`_data/final-probe.json`。

---

## 4. 已排除的假阳性（同批自动检测命中、但核实后不报）

| # | 候选 | 否证依据 |
|---|---|---|
| F1 | 「新建待办」浮动按钮被消息行压住、点不到 | 该按钮 `pointer-events:none`，且祖先 `#tfl-panel` 为 `opacity:0` —— 面板未展开时的隐藏态，不是层叠缺陷（`_data/verify.json` → `fab.chain`；特写 `11-verify/*todo-fab-hovered.png`） |
| F2 | 设置模态被画布面板/输入条压住 | 命中测试 4 点全部落在设置内容上；`#settings-overlay` 为 `z=310 fixed` 全屏，画布面板只是紧邻（`_data/stacking2.json` → `hits`；特写 `12-stacking/C-settings-right-edge.png`） |
| F3 | 插件摘要文本被裁切 | `-webkit-line-clamp:2` 的**设计裁切**（`_data/verify.json` → `pluginClamp`） |
| F4 | 头像 `502` 导致破图 | 页面内 `naturalWidth===0` 的 `<img>` 数为 **0**（已兜底渲染），无可见破图（`_data/verify.json` → `brokenImgs`） |
| F5 | 已登录后断网 200s 未见重连横幅 | 断连判定的代码口径是 30s 心跳 × 连续 3 次 miss（`js/ws.js:1149-1152,1204`），但实测 20s→200s 全程 `#conn-banner` 仍为 `hidden`（`_data/banner-long.json`）——**原因是 CDP 的 `offline` 模拟不会砍掉已建立的 WebSocket**（心跳仍走原连接），该路径根本没机会触发断连判定。横幅本体已在「握手被拒」路径实测正常（`07-notify/desktop__reconnect-banner-real.png`、`mobile__reconnect-banner-real.png`）。**不作为缺陷上报**，登记为「需真实断网（停实例/拔网）复测」 |
| F6 | 正文呈「深字压深底」1.04:1 | 底色回溯在渐变/玻璃面上取色失败导致的误报；肉眼与截图均为深字浅底（`findings.json` 中 `rgb(17,17,17)` 族） |

另外两条**已知存量红**未重复上报：手机 G7 mask 断言、`js/ws.js` 颜色基线上限（`scripts/design-token-baseline.json`）。

---

## 5. 未覆盖 / 未触发的项（诚实边界）

1. **合并队列（面⑨）**：当前无 `mergeQueue` 在队项，只能取到空态；行内排队位次（`mergeQueuePos`）需要有 delegate 处于「排队中/合并中」才能取证。
2. **diff 视图（面⑧）**：变更文件面板为「尚未打开 diff」空态（提示需点委托交付消息上的变更摘要），本轮会话里没有可点的变更摘要，未取到真实 diff 渲染。
3. **队列中消息**：主会话空闲，`#queue-bar` kids=0，未触发「排队中消息」形态（`01-chat` 的 1-base 即空队列态）。
4. **「编辑态」**：实测消息行悬停动作面只有 时间戳/复制/引用 三枚图标，**没有「编辑已发送消息」入口**（`js/` 内消息气泡无 dblclick 绑定，仅 `todoFace.js`/`sidebar.js` 等处有）。本报告的「编辑态」= 输入条输入态（`01-chat/*__2-typing.png`）。若作者所说的编辑态指「改已发送的消息」，那是一条**功能缺口**而非样式缺陷。
5. **手机附件气泡**：手机层点 `#attach-btn` 不发起 `filechooser`（走相册/拍照菜单），本轮未取到手机附件气泡态（桌面态已取证）。
5. **手机层部分面板**：巡检途中多次出现接管弹窗（`#takeover-box`，多客户端同实例的真实行为）遮挡全站，已用强制收起 + 补拍（`10-mobile-faces/light-mobile__settings-1..4.png`）覆盖设置面；`04/05/09` 的手机截图存在部分为接管态的可能，已在 §2 表格中标注可用图。

---

## 6. 截图清单（131 张，按面归档）

**严重度「明显」全部取证图（建议优先看）**

```
01-chat/light-desktop__1-base.png                              面① 桌面亮：会话全貌（含 DELEGATE 回执行）
01-chat/dark-desktop__1-base.png                               面① 桌面暗：同上
01-chat/light-mobile__1-base.png                               面① 手机亮：D2/D3/D4 现场
01-chat/dark-mobile__1-base.png                                面① 手机暗：同上
10-mobile-faces/light-mobile__overlap-clip.png                 D3/D4 特写：浮钮压时间戳、代理名浮标压正文
10-mobile-faces/light-mobile__workbench.png                    D1 反证：工作台无「待办」行
06-todo/light-mobile__1-todo-open.png                          D1：手机点了待办位也打不开
01-chat/light-desktop__3-attach.png                            面① 附件气泡（桌面，正常）
01-chat/light-desktop__5-row-actions.png                       D2：桌面消息操作面（13×13、opacity .35）
01-chat/light-desktop__1-base.png                              D5：委托回执徽标对比度
04-agents/light-desktop__2-agents-panel.png                    D6：次级灰文本
```

**分面归档**

| 面 | 目录 | 张数 |
|---|---|---|
| 00 缓存假设 | `00-cache/`（冷/温×2 同页对比） | 3 |
| ① 主会话/输入区 | `01-chat/` | 19 |
| ② 左侧栏与折叠 | `02-sidebar/` | 10 |
| ③ 设置面板 | `03-settings/` | 6 |
| ④ 后台智能体/插件 | `04-agents/` | 12 |
| ⑤ 画布与卡窗 | `05-canvas/` | 6 |
| ⑥ 待办 | `06-todo/` | 12 |
| ⑦ 通知与浮层 | `07-notify/` | 17 |
| ⑧ 文件浏览器/diff | `08-explorer-diff/` | 8 |
| ⑨ 任务/项目面 | `09-tasks/` | 4 |
| 手机专项 | `10-mobile-faces/` | 21 |
| 假阳性否证 | `11-verify/` | 2 |
| 层叠核实 | `12-stacking/` | 10 |

完整清单：`find . -name '*.png' | sort`。

---

## 7. 一句话建议

优先修手机层那一簇（D1 入口缺失 + D2 触点 + D3 顶部浮层遮挡，三处同源：手机层把桌面元素「隐藏而不替代」），桌面端只剩两处可读性档位（D5 回执状态字、D6 次级灰）与一条 console 噪音（D7）；其余面实测无功能缺陷。

---

## 8. 落位说明（需知会）

按任务书要求，本次产物落在仓库工作区的 `reports-evidence/ui-audit-post-restart/`（证据目录）。若需并入知识图谱口径，应另存一份到注册目录
`~/.stand/projects/<project>/.stand/docs/`（YAML frontmatter + `[[wikilink]]` + `check_wiki_docs.py` 校验）；本节点未获知具体 project 名，故未擅自落库。
