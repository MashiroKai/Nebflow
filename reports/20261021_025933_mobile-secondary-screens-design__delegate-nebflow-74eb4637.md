# 移动端 375px 样张 v3.5 · 二级界面全量补齐 — 设计报告

- 样张：`reports/20261021_025933_mobile-mockup-375-v3.5__delegate-nebflow-74eb4637.html`（同目录，浏览器直开，`#scr=s2&r12&t=light` 深链直达）
- 基线：v3 已批样张 `reports/20261020_214500_mobile-mockup-375-v3__delegate-nebflow-74eb4637.html`（作者已批；落地 commit a8f7c1234，登录卡补令 ea74165e8）
- 性质：**零源码改动，纯设计**。v3 已批五屏不重做，索引页（P1）深链直达。

## §0 一句话目标

作者令：「把没有展示的界面也呈现给我看」——把聊天/任务/登录卡/附件/头像之外的全部二级界面补出设计样张：左列五键（任务面板已批做样）同语言出**圆钮形变面板**，右列六键出**全屏推送页**，每屏带「开放点」清单供逐条批。

## §1 参考与依据

1. **项目视觉铁律（最高优先）**：v3 已批语言四件套——①悬浮顶栏（无通栏底面）②双圆钮液态下拉 ③二级液态形变（圆钮 40→308px 原地加宽，spring 过冲回稳）④glass-control token（blur10 · saturate1.2 · 顶亮/底暗 inset · 辉光投影，nf-findbar/.glass-control 同族）。v3.5 全量沿用，零偏离。
2. **现网数据结构**（内容镜像真实字段，file:line 为仓内检索所得）：
   - 搜索：`src/main/resources/web/js/chatSearch.js:1607-1680`（key=sessionId:ord / ts / kind=user|agent|ai|tool / sessionName / text；徽标 用户/助手/AI/工具·ToolName；mark 居中截 180 字；同日 `14:32`、跨日全格式；筛选五档 `zh-CN.js:1716-1720`）
   - 定时任务：`scheduled-task.js:396-421,546-552`（content/triggerAt/repeat 单次|每小时|每天|每周/enabled/lastTriggered；`formatTriggerTime`:78-85「今天 14:30 / 明天 09:00 / 3月5日 08:00」；超时=已超时 tag；触发气泡「定时任务已触发」）
   - 心跳进程：`daemons.js:277-278,374-423`（name/port/portOpen/status running|starting|stopped|error|crashed/autoStart；5s 轮询 + WS daemonStatus；「掉线约 10 秒自动拉起」现网文案）
   - 记忆：`memory.js:86-99`（scope=agent→memory.md / user→User.md，双 tab，单 markdown 文件无条目字段）
   - 运行状态：`main.js:2718-2799 renderBgDropdown`（taskId/description/status 六档/idle>2min、stuck>10min/origin nebula|dispatcher|node/本地·远程/outputLines/uptime；**现网无 CPU/内存/网络字段**）
   - 画板：`canvas.js:48,553,615`（tab record：title/type markdown|code|image|pdf|epub|xlsx|docx|url/pinned；移动=文档卡层 `mobile/navController.js:279-286`；空态「画板暂无文档卡片」；**无持久「最近打开」列表**）
   - 项目：`projectTab.js:975-1042`（name/description/workspace/origin{device,path}/运行中 agent 数/flow-map 摘要{running,brief,notMounted,total}；远端徽标+离线琥珀点；空态「还没有项目 / 让 {agent} 创建项目」）
   - 图谱：`wikiGraph.js:4-7,1130-1175`（`/api/wiki/graph` projects/nodes{title,rel,project,kind,status}/edges；状态行「n 个项目 · n 篇文档 · n 条链接」；灰阶·度数深浅/附件方块/未解析虚线/已完成绿环；缩放三钮）
   - 插件：`plugins.js:5-7,377-457`（frozen contract C6：name/version/author/skills/mcpServers/toolsExtension/blocked/contentChanged；行=版本·作者+描述+组成 chips「n 个技能/MCP/内建工具 +n」；三态 pill；分发开关=任务分发器可见性）
   - 用量：`usageDashboard.js:14-17,105-130`（`/api/usage/aggregate?dim=day|hour|provider|model|agent`；input/output/cacheRead/cacheWrite/count；总消耗+计费等效/今日+较昨日/缓存命中率目标 96%+；token 万/亿缩写；52 周热力图）
   - 设置：`sidebar.js:773-946 renderSettings`（账号→运行时→外观→LLM 服务商→密钥→高级→关于）+ **已实施** `agentProfile.js:126-207`（头像预览/上传头像/恢复默认/名称行内回车存/同源文案 `zh-CN.js:2315-2322`）
3. **范式参考**：iOS 全屏 push 导航（右列页面动效基准）· 微信/Telegram 面板内容密度 · Linear 空态 CTA 文案式。冲突处以项目铁律与作者 taste 为准。

## §2 布局与信息架构（P1-P12）

- **P1 索引**（全屏页）：新 11 屏（待批）+ v3 已批五屏深链（已批/已实施标注，跳 v3 文件 `#scr=p1..p6`）。
- **左列 · 圆钮形变面板组**（= v3⑤ 任务面板同语言：scrim + 面板 40→308×560，进场即放形变动画）：
  - P2 搜索聊天记录：查询框（含光标）→ 范围 seg（当前会话/全部会话）→ 计数行 → 结果行（会话名+类型徽标+时间 / 摘要 mark 高亮 / 卡片行可内嵌展开）→ 底部五档筛选。
  - P3 定时任务：即将触发/已超时/已停用三分组；行=空心圈+内容+重复徽标（单次不显）；下次触发主行+上次触发副行；头部 + 创建钮。
  - P4 心跳进程：进程行（状态点 run/start/err + 名 + :port + 命令 + autoStart 小开关）→ 近次心跳时间线（时点+色点+事件）→ 脚注「每 5 秒轮询 · 掉线约 10 秒自动拉起」。
  - P5 记忆：scope seg（智能体·memory.md / 用户·User.md）→ 条目卡（标题+摘录+来源·更新脚注）→ 头部「编辑」钮（唤双栏 markdown 编辑器）。
  - P6 运行状态：在飞任务行（状态点+标题+时长 + chips：状态词/origin/本地·远程/行数）→ 资源占用（CPU/内存/网络条，**新增提案**）。
- **右列 · 全屏推送页组**（作者令：不是下拉；页=悬浮页头[返回圆钮+标题胶囊+动作钮] + 滚动内容 + homebar；进场右推 320ms）：
  - P7 画板：新建/打开双钮 → 最近打开（**新增提案**）七类文档卡（钉住置顶标识）。
  - P8 项目：项目卡（名+远端徽标 → 摘要行[点色+brief/空闲/未挂载] → 字段行[运行中 agent/workspace 或来源/说明]）；空态 CTA 行。
  - P9 图谱：状态行 → 迷你图谱预览（SVG + 图例四态）→ facet seg → 项目行（轮换色点）→ 最近文档行（附件图标/未解析降透明）→ 缩放三钮。
  - P10 插件：插件卡（名+pill / 描述 / chips：版本·作者·技能·MCP·内建工具 / 行尾分发开关）；已封禁整卡降透明；智能体分组（内置徽标）。
  - P11 token 用量：维度 seg（总计/按模型/按 agent）→ 四卡摘要（总消耗+计费等效/今日+较昨日/缓存命中率+目标/本周环比）→ 近 14 天条带（52 周热力图留桌面）→ 最耗模型条。
  - P12 设置：顶部 **profile 区与已实施 agentProfile.js 一一对应**（头像预览+角标 / 上传头像[主键] / 恢复默认 / 名称行+回车光标 / 同源文案）→ 七分组沿用桌面 renderSettings 次序。

## §3 交互状态机

| 对象 | 状态机 |
|---|---|
| 左列面板 | 圆钮点按 →（六键收回 260ms）→ tpOpen 形变 680ms spring → 内容 240ms 后 320ms 淡入；X/点空白 → 逆向收回。样张内：进场自动放形变，X/返回回 P1 |
| 右列页面 | 右列键点按 → 页面自右推入 320ms ease（translateX 64px→0 + 淡入）；返回圆钮 → 回 P1 |
| 索引 | 行点按切屏（新屏）/ 原生跳转（v3 深链）；控制台 seg 12 键独立可切；← → 键巡屏 |
| 主题 | 暗（主）/ 亮（切）双档，`.phone.dark` 类切换，全组件 token 化自动适配 |

## §4 动效规范

- 面板形变：680ms `cubic-bezier(.3,1.36,.45,1)`（spring 过冲），40×40 r20 → 318×574（62%）→ 308×560 r16 回稳；内容延迟 240ms 淡入 320ms。
- 页面推入：320ms `cubic-bezier(.32,.72,0,1)`，translateX 64px→0 + opacity 0→1（轻推入，非全宽滑动——保留与聊天层的空间连续感）。
- 心跳运行点：1.6s 脉冲光环；自启开关 200ms ease；查询光标 1.1s steps 闪烁。
- **全部动效尊重 `prefers-reduced-motion`**（样张已内建 `animation:none!important` 兜底）。

## §5 视觉规格

- 全部取自既有 token：base.css/sapphire.css 镜像值 + v3 glass-control 族（--gcb/--gcbh/--gcbd/--gchl/--gcue/--gcglow/--gcsh/--gcblur/--gcpanel）；圆角 20（钮）→16（面板/页卡）→12/11（行卡）；状态色 #07c160/#4caf50/#98a2b3/#d64545/#f44336 沿用 v3。
- **唯一新增**：`--amber:#f59e0b`（亮/暗同值），仅用于进程「启动中」点、插件「内容已变更」pill、资源内存条——现网 daemons.js 琥珀态语义，显式声明非静默引入。
- 「新增提案」标签 = 琥珀描边小签（样张批注层，非产品 token）；开放点卡 = gcpanel 材质 + blur10（与面板同族）。

## §6 边界与异常

- 空态：定时「暂无定时任务 · 点击空白处或按 + 创建」/ 画板「画板暂无文档卡片」/ 项目「还没有项目 · 让 笨笨 创建项目」/ 心跳三行提示（现网文案）——样张以脚注或 CTA 行示意。
- 超长文本：会话名/插件名/文档标题全部 ellipsis；workspace/来源路径 ellipsis + tabular-nums；搜索摘要两行截断。
- 面板内容超高 → tp-bd 内滚动（隐藏滚动条，与 v3 同）；右列页面 pgbd 内滚动。
- 窄视口：375 单档（作者令基线）；面板宽 308 = 375−2×6−55 恒等式沿用 v3。

## §7 无障碍

- 全部可点目标 ≥40×40（圆钮/页头钮）、行卡 ≥36 高；开关 38×22（触区由行承担）。
- 对比度：暗/亮双档均 ≥4.5:1 正文（亮档已修 selected 态：gcue 填充 + ctrl-b 描边，不再白上白）。
- 焦点：真机实施批需 focus-visible 环（样张为静态示意，规格锁定：2px sapphire 外环）。
- 动效：prefers-reduced-motion 全关（§4）。

## §8 可断言验收点（供实施批转 Playwright）

1. 左列任一面板展开后，面板盒模型 = left:6px/top:54px(相对 app)/width:308px/height:560px，border-radius:16px —— 二值，需截图。
2. 面板头图标 = 来源圆钮图标（搜索 i-search / 定时 i-clock / 心跳 i-server / 记忆 i-brain / 状态 i-activity）—— 二值，无需截图。
3. 搜索结果行含 `<mark>` 且 mark 背景 = rgba(127,156,245,.30) —— 二值，无需截图（DOM 断言 computed style）。
4. 定时任务重复徽标四档枚举 {每小时,每天,每周}，单次行**无**徽标节点 —— 二值，无需截图。
5. 右列页返回钮点按后回到 P1（data-s="ix" 的 seg 键获得 .on）—— 二值，无需截图。
6. P12 profile 区首子行文案 = 「头像本地优先保存（按智能体各自记忆），登录后可随账号同步；名称回车即存。」（与 agentProfile.js 同源 i18n key）—— 二值，无需截图。
7. 亮档 `.seg2 button.on` 背景色 ≠ 亮档 `.seg2` 背景色（回归本次修复的对比度缺陷）—— 二值，需截图。
8. `prefers-reduced-motion: reduce` 下 `.tpanel.open` 无 animation（computed animation-name = none）—— 二值，无需截图。
9. 12 屏遍历点击零 console error、零横向溢出（phone 视口内）—— 二值，无需截图（本次自检已过，实施批复跑）。

## §9 参考链接

- v3 已批样张（本样张基线）：`reports/20261020_214500_mobile-mockup-375-v3__delegate-nebflow-74eb4637.html`
- v3 落地 commit：a8f7c1234（mobile v3）· ea74165e8（登录卡绿键退役）
- 检索范式：Apple HIG Modality/Presentation（developer.apple.com/design/human-interface-guidelines）· Material Motion easing（m3.material.io/styles/motion）· WAI-ARIA APG disclosure（w3.org/WAI/ARIA/apg）
- 数据结构源：见 §2 各 file:line（src/main/resources/web/js/**）

## 开放点汇总（46 条 · 候作者逐条批；屏内右下角有同份）

- **P2 搜索（5）**：①mark 高亮底=sapphire 30% ②「工具 · WebSearch」徽标措辞 ③卡片内嵌展开与跳转并存、默认展开否 ④范围默认=全部会话 ⑤五档筛选沿用
- **P3 定时（5）**：①下次触发主行/上次副行 ②重复徽标四档、单次不显 ③超时 err 红独立分组 ④创建入口=头部 + 钮 vs 空态引导 ⑤已停用降透明垫底
- **P4 心跳（4）**：①进程行+近次时间线的合成形态 ②延迟毫秒展示与否【新增提案】 ③「掉线约 10 秒自动拉起」文案沿用 ④行内自启开关与桌面双向同步
- **P5 记忆（4）**：①条目卡按 ## 分节切【新增提案·切分规则】 ②双 scope tab 措辞 ③「编辑」唤双栏编辑器、不做移动编辑器 ④卡脚更新时间需后端透出【新增提案】
- **P6 状态（4）**：①与桌面两下拉的归并（移动单面板）、子智能体并入否 ②状态词六档+卡死阈值沿用 ③资源占用立项与否【新增提案·后端无字段】 ④远程 chip 标识
- **P7 画板（4）**：①「最近打开」tab 记录落库【新增提案】 ②七类类型图标 ③移动定位=只读预览+跳桌面 vs 移动编辑 ④桌面面板 tab 归并入口
- **P8 项目（4）**：①卡三段字段密度 ②远端徽标+离线琥珀点 ③flow-map 摘要进卡 vs 点开 ④归档项目折叠
- **P9 图谱（4）**：①预览+列表 vs 全画布漫游 ②文档行无更新时间、以链接数替代 ③项目轮换色点+灰阶度数沿用 ④缩放三钮保留、facet 收一段
- **P10 插件（4）**：①三态 pill、正常态补「任务分发中」灰 pill 去留 ②分发开关行内常驻 vs 收进详情 ③拒载列表上移动端否 ④无安装入口维持否
- **P11 用量（4）**：①费用=「计费等效」口径保留否 ②52 周热力图→近 14 天条带（热力图留桌面） ③维度 seg 置顶 ④万/亿缩写与峰值文案沿用
- **P12 设置（4）**：①profile 区与 agentProfile.js 一一对应（已对齐，仅排版置顶） ②分组次序沿用桌面 ③「重启服务」红字+二次确认【新增提案】 ④桌面项（开机自启动/状态配色/编辑原始 JSON）移动端去留

## 自检记录（设计方自查，非 QA 结论）

- Playwright headless（chromium，dsf=2）：12 屏逐一 click 遍历 ×2 轮 —— **0 console error / 0 pageerror / 0 横向溢出**。
- 自检中发现并已修复：①索引行/画板行标题-副题内联塌行（.it/.dt 改纵向 flex）②亮档 seg2/ftab 选中态白上白（改 gcue 填充+ctrl-b 描边）③pill/badge/chip 亮档描边弱化（--gcbd→--ctrl-b）④开放点卡透出红色停止钮（--gcb→--gcpanel）。修复后暗/亮双主题复核通过。
- 交叉验证边界：本报告为**设计样张交付**，无产品实现可验；§8 验收点留给实施批 + QA（视觉评审须以 Playwright DOM 断言 + 截图交叉验证，不裸看图下结论）。

## 交付清单

1. `reports/20261021_025933_mobile-mockup-375-v3.5__delegate-nebflow-74eb4637.html` — 样张（P1-P12 · 暗亮双主题 · 深链）
2. `reports/20261021_025933_mobile-secondary-screens-design__delegate-nebflow-74eb4637.md` — 本报告
3. 源码改动：**无**（纯设计）

## 交付状态说明

两件交付以**未跟踪态**落在主仓 `reports/`（与本族 v2/v3「Mail 续聊批直写主仓 reports/」同例）；本续聊批无独立 worktree/分支，按规程不自行建分支、不直提 main —— 建议调度方照 0f2f2ee0a 先例归档（`chore(design): 归档移动端 375 样张 v3.5`），避免未跟踪态再次触发自动合并 fail-closed 误拒。
