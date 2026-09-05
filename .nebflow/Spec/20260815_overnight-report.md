# 整晚实施进度报告（启动时间修正：**00:00 开跑**，2026-08-15 19:43 用户拍板）

> Manager 维护，Nebula 明早 09:00 取。阻塞超 20 分钟换下一子项，阻塞记录在「阻塞清单」段。
> 运行实例未重启（FlowTrigger 修复未生效）→ 今晚全部合并 = Manager 审合备案，明早重启后下一批回归 flow。
> 19:42 任务书已预发 Frontend/Backend 两线（含暂停指令）；tool-engineer/prompt-engineer 任务书 00:00 随开跑指令发出。

## 战场布局

| 线 | 谁 | 工作区 | 任务序 |
|---|---|---|---|
| 前端 | Frontend | /tmp/nb-onight-frontend (feat/onight-frontend @7408a4b4) | F1 串台 → F2 toggle UI → 文件多选 UI → Onboarding 前端 → Canvas 12 内置迁移 |
| 后端 | Backend | /tmp/nb-onight-backend (feat/onight-backend @7408a4b4) | F1-3 过滤器 → autostart 服务 → deletePaths → Onboarding 后端(探活门禁) → FileTypeRegistry → Hook P0 测试 |
| 发布 | tool-engineer | /tmp/nb-onight-release (feat/onight-release @7408a4b4) + 官网仓 feat/onight-website | F4 P0 dmg → CI 三平台 → 官网 Hero/installation |
| entities | prompt-engineer | ~/.nebflow (main) | release-stable coder 日期版本规则 |
| C-Core | tool-engineer（第一段，06:00 硬时限） | /tmp/nb-toolopt-core (feat/toolopt-core @91117499) | C1 MultiEdit → C4 速赢三项 → 完成后转 B4 发布线 |
| C-Task后端 | Backend（B 线六任务后接续） | /tmp/nb-toolopt-task (feat/toolopt-task @91117499) | C2 TaskModel/completedAt/useDefaults/TaskQuery/归档端点/_index.json |
| C-Task前端 | Frontend（B 线五任务后接续） | /tmp/nb-toolopt-frontend (feat/toolopt-frontend @91117499) | C3 今日完成折叠条 + 档案视图（按契约可先行） |
| QA | qa-backend | 用例先行 → /tmp/qa-toolopt-acceptance.md | C 线验收用例先行产出 → 交付物逐项源 worktree 验收 |

## 用户修正（最高优先级，验收硬条件）

1. **Onboarding 固定式**：引导流程+问题集=固定代码/固定文案（AskUserQuestion 仅渲染载体）；**探活硬门禁**——真实 LLM 调用成功才发引导消息，配置没生效绝不发
2. **桌面版严格区分系统**：dmg（macOS）/msi（Windows）独立产物，官网按平台给下载按钮
3. 其余技术决策已授权（F2 六风险按建议 / F4 四决策按方案 / F3 分期按方案）

## 进度

（Manager 随收随记）

### 启动时间线
- 23:58 B 线开跑信号（Nebula）→ 00:0x 五 Mail 齐发：Backend/Frontend（B 线开跑 + C 线接续指令）、tool-engineer（C1+C4 先做——06:00 硬时限约束段，B4 第二段）、qa-backend（验收用例先行）、prompt-engineer（日期版本规则，依据 F4 方案 §146）
- 23:59 C 线开跑信号 → 三个 worktree 已从新基线 91117499（含删工具）创建：nb-toolopt-core / nb-toolopt-task / nb-toolopt-frontend
- 基线核实：nb-onight-backend 顶端是 ba489c1f（FlowTrigger 执行侧修复，按合并顺序先合）；frontend/release 在 7408a4b4；主仓 archive/scala @ 91117499
- 官网仓 main 干净（5 个 probe-*.mjs 未跟踪，无视）
- **C 线降级序（06:00 硬时限）**：先砍事件流 → notes → 档案视图 → TaskQuery；保 MultiEdit + completedAt + 今日完成条 + 速赢三项
- **C 线内部合并顺序**：C1 先合（registry.scala），C2 后合 rebase；C3 独立分支无冲突
- W4-1 文案行（CompactService ManagerSaveMemoryReminder 第 5 项）确切口径未能从历史恢复（entities log / design 文档均无命中）→ **降级明早单独派发**，不阻塞今晚批次

### tool-engineer（C 线 core + B4 发布）
- 00:10 C1 Commit A `4717d307`：EditTool.applyEditToBuffer 纯函数抽取（行为不变，Edit 错误消息/prompt 渲染零变化）+ EditToolSpec 13 用例（基线本无 Edit 测试）全绿
- 00:14 C1 Commit B `025e59ef`：MultiEdit 工具（~200 行，原子顺序应用+失败行号提示+closestLineHint）+ registry 注册 + 14 用例全绿；硬红线守住（EditTool description/schema 未动一字节）
- 00:15 C4-1 Commit C `84831a28`：DiffUtil.writeFile 孤立 \r 清洗（\r\n→\n 先行，防 CRLF 双断行）+ DiffUtilSpec 7 用例，文件工具族 52/52 回归绿
- 00:15 C4-3 `0fc119fa`：基线已连带清掉 Bash 引用（全仓 grep 零命中确认），按 qa 建议加 DeletedToolGuardSpec 3 用例守门
- 00:21 C4-2 Commit D `73e9d6b3`：Schedule triggerAt 支持 ISO 8601/自然语言（epoch-ms 向后兼容，纯函数 parseTriggerAt + 17 用例；解析失败错误嵌当前时间自愈）
- 00:20 全量回归：sbt test **747 passed / 0 failed / 8 ignored（既有）**——第一段 5 commits 收官，feat/toolopt-core 顶端 `73e9d6b3`，转 B4 发布线
- 00:27 B4-P0 `ff2d5a71`：macOS dmg 本地跑通——jdeps 推导模块集+jlink 裁剪（80M，≤90M 验收线内；默认 java.se 全集 112M 超标）；app-image 隔离 home/端口冒烟 PASS（health 200/WS 认证路径/退出释放端口，主实例无恙）；干净机安装+LLM 端到端留人工
- 00:29 B4-CI `b5b3209f`：auto-release.yml 三平台矩阵（build-jar→macos arm64/x64 dmg+windows msi→release 四资产+COS 全量镜像）；JDK21+WiX3.14 绑定（研究 §4 拍板）；**未 push**（push 即触发，等用户指令）
- 00:35 B4-官网 `3d8ac4c`（feat/onight-website）：Hero 平台 tab（macOS/Windows/Linux·CLI）+ dmg/msi 主按钮 + Intel/国内镜像次链 + GH releases/latest 资产探测（无桌面资产时优雅降级 pending 提示）+ Gatekeeper/SmartScreen 首开引导；Beta 入口移除；CTA 桌面锚点；installation.mdx 虚构内容全换真实双渠道（Docker 段因镜像不存在已删）。localhost:3000 验证：双语言 SSR + Playwright 三 tab 交互断言全 PASS，截图留 /tmp/website-hero-zh*.png。**未 push 未部署**
- 00:36 C 线已被 Manager ff 合并 archive/scala@73e9d6b3 + worktree/分支清理确认
- 遗留移交：①B4 release worktree（feat/onight-release@b5b3209f）待审合 ②官网 feat/onight-website 待用户 localhost:3000 预览后指示 push ③dmg 干净机安装+LLM 端到端人工 QA ④文档一致性发现：docs/behavior-parity/README.md:14 与 docs/reviews/parity/01-tool-execution.md 仍列 RemoveUnnecessary/SaveWorkspaceItem（Rust parity 域，未越权改）
- 00:40 Manager 审合 B4 → merge `182dff3c`（worktree/分支已清，dmg 产物抢救到主仓 build/dist/）。T10 并发锁互斥用例（qa 挂账）当轮顺手补：test/filelock-mutex @ `6345eced`，FileLockMutexSpec 三用例（8 fiber 同文件互斥 / per-file 粒度不死锁 / 并发 MultiEdit 无撕裂双成功）全绿——**00:41 已合并**（archive/scala @ 6345eced，ff，worktree/分支清毕）。tool-engineer 今晚账目全清

### Backend（B 线六任务 + C2 Task 后端）
- 00:33 ✅ **B 线六任务全部完成**，feat/onight-backend 五连 commit（ba489c1f 之上）：`51905762` 任务1/F1-3 activeAgents 补 Team ｜ `71de3a70` 任务2 autostart 服务抽取+WS toggle（.app bundle 形态检测+sbt run 硬禁用）+ 任务4 onboarding 状态机+probeLlm 硬门禁（FallbackExhaustedError 逐 provider 归因）｜ `d8b0293f` 任务3 deletePaths 批删（companion 纯函数可测+os.remove.all）｜ `9f3e2c79` 任务5 FileTypeRegistry 收敛三处重复（行为冻结迁移，BinaryExtensions 删除，grep 锚点过）｜ `5ced3d3c` 任务6 Hook P0 测试 31 case（Matcher/Result/ConfigLoader/Runner 真进程/AgentCore 端到端 3 条）+ W4-1 文案入库 + docs/hooks-and-callbacks.md 首次入库（-f，3 处最小修正）
- 全套 **749 passed / 0 failed / 8 ignored**（基线 694 + 新增 55）——零回归；Crossref 活网 flaky 本轮通过
- 00:34 起 转 C2（/tmp/nb-toolopt-task，rebase 73e9d6b3 后开工）
- 00:55 ✅ **C2 Task 后端完成**：feat/toolopt-task `cffa714b`（rebase 73e9d6b3 零冲突，registry MultiEdit 行与 TaskQuery 行并存）。TaskModel +completedAt/notes/events（**R1 红线守住**：ConfiguredCodec+withDefaults 真做在 codec 上——qa S1-S5 五个真实生产旧档 decode 全 Right、List 字段缺失填默认、metadata 未知键忽略）；TaskStore completedAt 进 completed/failed 盖章+事件流五 kind（status detail 带 wire-format 下划线名，50 条截断）+notes append-only+root val→def（dataRoot 注入）；TaskArchive _index.json 索引（rebuild+增量 refreshSession，孤儿 session folderId=null，tasks/ 下唯一新文件）；TaskToolHelper 挂增量刷新；TaskQuery 工具（session/recent/project 三 scope+since 四词法+索引缺失降级 session 不抛异常）；GET /api/nf-tasks 归档端点（group=folderName||未分类，空结果 200+[]）；TaskUpdate note/noteLinks 参数+文案鼓励 completed 带 note。**770 passed / 0 failed**（C 线基线 747+23 新）。降级序未动用——事件流/notes 全交付
- 01:03 ❌→01:06 ✅ **qa 打回 wire-name 四处漏网已修**（`5f69db16`）：TaskStatus.wireName 提升单源（TaskModel，与 codec 并列），修 qa 点名 4 处（TaskArchive IndexEntry/TaskQuery session 过滤/降级过滤/toEntries）+ 自查同坑 2 处（TaskUpdateTool 单条+批量结果消息，qa 未点名但同病）；+2 测试（in_progress 过滤 session+recent 各一，IndexEntry 断言 "in_progress"——首轮漏测原因：只测了无下划线的 completed）。**772 passed / 0 failed**。注意：cffa714b 写出的 _index.json 含 "inprogress" 脏值，需一次重建（增量刷新自愈或 rebuildIndex 一发）——无数据丢失
- 02:13 ✅ **C3 契约断裂修复**（fix/note-wire-field `c1a0523a`，worktree /tmp/nb-notefield @8a609292 主线顶）：TaskNote wire 字段 text→content 对齐设计文档 B1（C2 实现偏离文档未声明是根因，Manager 裁定文档=契约源）；3 文件纯改名零迁移（生产零 notes 数据）；文档 by 字段不加（P1 勘误待议，commit message 已注明）；全仓残留 grep 干净（前端 taskArchive.js 本就按 n.content 消费）。**836 passed / 0 failed / 8 ignored**（主线新基线含 C3 前端）
- 00:56 ✅ **probeOkAt 硬门禁下沉完成**（qa 追加任务，onight-backend `af6cf771`）：OnboardingService 重写 StoredState(state, probeOkAt)+全路径 read-modify-write（setState/writeState/recordProbeOk 均保留已有 probeOkAt，qa 红线"setState 不擦 probeOkAt"有专门用例）；probeLlm 成功路径单边记录 probeOkAt=now（前端零改动）；setOnboardingState(done) 无 probe 记录**硬拒**（WS 回 error code=probe_required，前端可区分"需先探活"）；state 值非法降级 Pending 但保留可解析的 probeOkAt。OnboardingServiceSpec 13/13（含 gate 6 用例），全套 **754 passed / 0 failed** 零回归

### Frontend（B 线）
- 00:47 ✅ **F1 串台修复+全条目可点**：`49233fad`（main.js/bgAgentPopup.js/flowAgentPopup.js，+44/-17）。agentStart 存 nodeSessionId（原存 parent sid=串台根因）+ team 去壳 + dag/bare 分流 flowAgentPopup（消双 view）+ popup view 注册 chatViews（team 弹窗实时更新）+ flow 弹窗重开必重拉。Playwright 全 mock 验证 20/20 PASS，截图确认零串台。F1-3（activeAgents 补 Team）在 Backend 任务 1。
- 01:04 ✅ **F2 自启 toggle UI**：`36286637`（sidebar.js/state.js/sidebar.css，+55）。运行时 section 加「开机自启动」行，服务端权威交互（toggle 只由 autostartStatusResult 落定，失败回弹+reason toast），sbt run 形态 disabled+灰字。Playwright 14/14 PASS 含失败回弹/unsupported 断流，亮暗截图过。locales 键已写未 commit（等多选 SubTask 释放 locale 文件）。多选+Canvas 拆分两个 SubTask 并行进行中。
- 01:10 ✅ **文件多选**（SubTask 实施+我验收 commit）：`d0abb7de`（explorer.js/split.css/locales×2，+329/-9）。cmd/shift 多选+浮动操作条+单条 deletePaths+失败汇总 toast，排除 rename 按裁定。验收：独立重跑 worker harness 37/37 PASS + 暗色截图目验。worker 发现并上报：deleteNode 单删存在**既有双发 bug**（__showConfirm 存在时立即+确认后双发），非本次引入，建议另行修。Canvas 拆分 SubTask 进行中（fileViewers.js 已 916→瘦身中）。
- 01:14 ✅ **Canvas 12 内置迁移**（SubTask 实施+我验收 commit）：`07528f67`（fileViewers.js 906→56 行 + viewers/ 12 模块，+1052/-883）。协议对象 {name,label,extensions,binary,priority,render}，itemType 零变化，canvas.js 零改动。验收：独立重跑 27/27 PASS + 与迁移前基线逐项一致 + 截图目检。Onboarding 前端我自做进行中（onboarding.js 已落盘，待 main.js 接线+CSS+locales+验证）。
- 01:24 ✅ **Onboarding 前端**（固定式）：`bef513db`（onboarding.js 新 + main.js/sidebar.js/modal.css/locales×2，+345/-19）。欢迎→provider 复用→**probeLlm 探活硬门禁**→完成→固定 /onboarding 问候（injectUserMessage 透明可见）；老用户轻量一次性提示（探活同样门禁）；高级 section 重跑按钮；overlay 透明无暗化+面板入共享玻璃组。Playwright 7 场景 29/29 PASS，亮暗截图目检。**B 线五任务全部完成**，转 C3（/tmp/nb-toolopt-frontend）。
- 02:05 ✅ **C3 折叠条+任务档案**（/tmp/nb-toolopt-frontend，feat/toolopt-frontend）：`1ff3c641`（taskList.js/taskArchive.js 新/taskList.css/taskArchive.css 新/index.html/locales×2，+718/-6）。折叠条口径=completed 且 completedAt 今天（failed/dismissed 不计入），倒序 10 条+「查看全部」，0 活跃独立展示（.has-today 放行折叠容器）；档案=Canvas panel tab（可持久化恢复），项目分组时间线，failed 删除线标注、dismissed 防御过滤，links 双路点击（url tab/popFile），notes/events 缺席防御（待 C2 后端联调）。Playwright 全 mock 35/35 PASS（textContent 实断言）+双主题截图+三视口（375px 溢出为 daemon panel 既有 baseline，C3 组件零额外溢出）+i18n 中英键齐。
- 02:08 ℹ️ **闲时请缨 G4/G6 核查结果=全部已完成，零代码产出**（防撞车对账记录）：Manager 02:05 授权 G4/G6 实现+G3/G5 spec，核查 archive/scala @1cdf2e07 发现**均已被 03:00 闲时定时任务完成并合并**——G4 `a0e17023`（LlmLogWriter 日志 base64→`<base64 omitted, N bytes>` 占位，media_type 保留）、G6 `8405dc77`（ReadTool 长边>1920px 或 >2MB 降采样 JPEG 0.8，GIF 豁免保动图，PNG 透明压白底，>5MB 报错）、**VisionErrorPatterns 中文盲区 `4d3a8e10` 也已做**（此前担心未进定时任务内容，实际已覆盖），三项经 `ff6063a8` merge 进主线；G3 `8a39e34b`/G5 `2ffb99ab` spec+实施均完成（G5 经 `6937ede6` 合并）。未建 worktree、未写代码，抽查 diff 与审计 §4 建议逐条相符。

### 交付记录（随收随记）
- 00:06 ✅ **prompt-engineer 日期版本规则**：entities `bb098f2`（release-stable coder system.md，+17/-16，零 push）。Manager diff 审查 PASS：beta 规则二分穷尽（同日 beta.N→N+1；其它一切→当日 beta.1）/ `<today>` 记号 step 2 先定义防照抄 / 透传安心句防 coder 误改 CI / stable 显式取发布当日含跨日示例 / git 骨架未动。热加载生效。**今晚首个交付**。已知边界（不返工，明早知会用户用精确版）：触发链 = **同日 beta.1 → stable → 同日再发 beta**——第三次 bump 时 VERSION 是当日 suffix-less stable，命中"→<today>-beta.1"分支数回 beta.1，撞当天早上已存在的 `v<today>-beta.1` tag；单纯 stable 后次日再 beta **不触发**（日期前进自然归 1）。若用户要堵：suffix-less 分支半句改动（查当日已有 v<today>-beta.N tag 最大值续数），prompt-engineer 待命可出一行 diff。
- 00:09 ✅ **qa-backend 验收用例先行交付**：/tmp/qa-toolopt-acceptance.md（C1×11 + C2×10 + C3×8 + C4×3）。亮点：主仓 tasks/ 真实取样 134 session/798 文件/535+ completed，5 个形态各异旧档全文内嵌（含比任务书预想更极端的缺 createdAt/updatedAt + metadata:null 老档）；**红线机理校准**——Option 缺失返回 None 不炸、真炸点=带默认值的 List 字段（notes/events），R1 可区分"真 useDefaults"vs"只加 Option"。三项处理：①C4-3 缩水（91117499 已连带清理 Bash description 引用，tool-engineer 已改发"验证+可选守门测试"口径；C 线 worktree 全在 91117499 无 rebase 问题）②**N 口径 Manager 裁定：「今日完成 N」= completedAt 存在且日期=今天，failed/dismissed 不计入**（failed 在档案视图照常展示带标注）——已同步 qa+Frontend ③sbt run/Playwright 完整冒烟 defer 明早隔离窗口（今晚禁令），单测/jsdom 级替代已入用例。renderForPrompt byte-identical 归 C2-R5（Task 域）认可。00:10 用例按 N 裁定修正完毕（F1 计数/F2 同口径/F8 failed 定位拆分）。
- 00:27 ✅ **qa 验收 C1+C4 PASS**：/tmp/qa-backend-reports/toolopt-core-20260816.md。亲证 747/0 与产出方对数 + 5 新 Spec 定向 54/0。要点：T2 原子性字节级+mtime 双断言；T9 判据按基线实况修正（91117499 无 EditToolSpec，转为错误消息逐字断言+doEdit 等价审查）；**Q1 实现优于任务书**（孤立 \r 转 \n 而非删除——保持断行语义，7 用例含 double-break 防护）；Q2 含"已过时刻推明天"+epoch 兼容+错误消息嵌当前 epoch。**Warning（低）**：T10 并发锁互斥无用例（测试 ctx 锁全 None 零覆盖），代码审查与 Edit 同构判 PASS，follow-up 30 行并发用例不阻塞。
- 00:28 ✅ **今晚首个主仓合并**：feat/toolopt-core → archive/scala，91117499 → **73e9d6b3** fast-forward（与 qa 验收 commit 同 hash），worktree/分支已即清。C2 rebase 提醒已发 Backend（冲突面=registry.scala 单行，解法=TaskQuery 行与 MultiEdit 行都保留）。follow-up 待办：T10 并发锁用例（30 行，tool-engineer B4 空隙或明早）。
- 00:35 🔵 **Backend B 线六任务交付**：feat/onight-backend @ 5ced3d3c（ba489c1f 上 5 commits）：51905762 F1-3（activeAgents 补 Team kind）/ 71de3a70 autostart（jar in .app→plist 指向 bundle executable；sbt run 误报修复=resolveRunJar 过滤 scala-library）+ Onboarding（三态状态机+probeLlm 硬门禁 maxTokens=8+15s timeout+逐 provider 归因）/ d8b0293f deletePaths（os.remove.all+deleted/failed 聚合）/ 9f3e2c79 FileTypeRegistry 单源收敛（行为冻结，两表预核等价）/ 5ced3d3c Hook P0 31 case+W4-1+docs 首入库（-f 过 gitignore，特殊路径防误删）。**749/0**（基线 694+55）。**W4-1 第 5 项 Manager 审查接受**：PERIOD SELF-CHECK（[USER-RULING] 计数+一句话摘要，不记全文）与生态设计裁定计数语义吻合——来源之谜（Backend 称"00:00 附件已含"，实际那封无附件，疑 19:43 预发任务书已含）不影响内容正确性。
- 00:42 ✅ **qa 验收 B 线六点全过 PASS**（/tmp/qa-backend-reports/onight-backend-20260816.md，749/0 复跑对数）：autostart 双分支纯函数化/F1-3 五 kind 齐/deletePaths 含 symlink 出根拒绝（额外收获）/FileTypeRegistry 逐项对照+机械断言双证实/探活副作用面干净（LlmHandle 零会话污染，单次 8-token）。**中等发现→Manager 裁定**：setOnboardingState 直写无 probe 前置校验（WS 可绕过前端向导），用户"硬门禁"语义未后端闭环 → **采纳 qa 建议 probeOkAt 下沉**：probe 成功路径单边写 probeOkAt + setDone 无值硬拒绝 + 3 用例，已派 Backend **C2 完成后追加**（估 15-20 分钟）。**B 线合并推迟至此补完**（onight-backend worktree/分支保留=今晚唯一例外，挂账未清；今晚零 push 故无实质影响）。qa 届时复验此点。
- 00:36 🔵→00:38 ✅ **tool-engineer B4 三部分交付+审合合并**：①P0 dmg 本地跑通 ff2d5a71（jdeps→jlink 裁剪 80M<90M 验收线，app-image 隔离 home/端口冒烟 health 200 全 PASS）②CI 三平台矩阵 b5b3209f（build-jar 单次→macos arm64+x64 矩阵+windows msi，fail-fast false，触发仍 push release——合并安全）③官网 feat/onight-website @ 3d8ac4c（Hero 平台 tab+桌面主按钮+国内镜像+Gatekeeper/SmartScreen 引导，Beta 入口移除，Playwright 全 PASS，dev server 3000 常驻明早预览，**未 push 未部署**）。Manager 审合亮点：**app-version.sh 天然兼容日期制**（2026.08.15-beta.N→2026.8.15 去前导零防 MSI 拒收——与 entities 版本规则线独立咬合正确）/ jlink jdk.crypto.ec 安全增量+质量门注释 / jar 单次构建防三平台漂移。**合并 182dff3c**，dmg 产物 79MB 已存主仓 build/dist/（worktree 删除前抢救），worktree/分支已清。移交清单处置：③dmg 干净机 QA=明早用户 ④parity 文档两处已删工具引用→挂明早派 Docs（Rust 团队暂停不派）⑤VERSION 日期制=entities bb098f2 已闭环。T10 并发锁 follow-up 挂明早。tool-engineer 今晚全部完结待命。
- 00:40 ✅ **T10 follow-up 当轮清零**（tool-engineer 主动不等明早）：test/filelock-mutex @ 6345eced → 合并 **archive/scala @ 6345eced**（fast-forward，+91 行纯测试，worktree/分支已清）。三用例：8 fiber 同路径临界区深度恒 1（真并发互斥）/ per-file 粒度不死锁对照 / IO.both 双 MultiEdit 竞争同文件零撕裂实证。qa 唯一 Warning 关闭，C1+C4 全绿收官。prompt-engineer 今晚任务完结待命。

## 合并备案（Manager 审合，未走 flow——实例未重启）

（逐批记录 commit + 审查要点）

- 00:43 ⚠️ **qa 复验预判转告**：writeState 现行 os.write.over 整文件覆写只写 state——probeOkAt 同存 onboarding.json 会被任何 setOnboardingState 擦掉，Backend 必须改 read-modify-write。已补发 Backend（+建议加 setState 不擦 probeOkAt 用例）。qa 复验登记 3 条（error 可区分/坏 JSON 不丢/逐场景不擦）。非阻塞建议 1 条（配置变更后旧 probeOkAt 失效语义）待记录，P1。

- 00:57 🔵 **Backend C2 + probeOkAt 双交付待验**：①C2 Task 后端 feat/toolopt-task @ cffa714b（rebase 零冲突）：R1 红线守住（ConfiguredCodec+withDefaults 真 codec 层，qa 五真实旧档全 Right）/ TaskStore completedAt 双终态盖章+事件流五 kind（wire-format 名踩坑已修）+50 条截断 / TaskArchive _index.json rebuild+refresh / TaskQuery 三 scope+since 四词法+降级不抛 / /api/nf-tasks 全参 / 23 测试全真实 store 无 mock。**770/0**（747+23），降级序未动用全交付。②probeOkAt af6cf771（onight-backend 第 6 commit）：三条 Mail 全条落实+超配（writeState 三路径 read-modify-write / 坏 JSON 两层语义 / 6 gate 用例含 setState 不擦红线），754/0。00:59 qa 先复验 probeOkAt（PASS 即合 B 线 6 commits）再验 C2（R1-R10）。Backend 三段收官待命。

- 01:00 ✅ **qa probeOkAt 复验 PASS**（三证据：error code=probe_required 独立可判别 / 坏 JSON 两层语义含三断言用例 / 三路径 read-modify-write+逐转移不擦用例）→ **B 线合并执行 899e4057**（feat/onight-backend 全分支 7 commits=ba489c1f FlowTrigger 修复+5 任务+af6cf771 挂账，自动合并干净 +1959/-295，含 docs -f 特殊路径正常入库）。组合态（C1+B4+T10+B线）全量 sbt test 后台验证中（job c140da00）——按 G5 教训合并验收必须全量，任何一方都没跑过这个组合。PASS 后清 worktree/分支。qa 已转 C2 验收（wire-name 坑修正确认先报）。

- 01:03 ❌→🔵 **qa C2 验收 FAIL 已打回**（/tmp/qa-backend-reports/toolopt-task-20260816.md，直邮 Backend 附修复方案）：wire-name 坑只修 TaskStore 一处（修对了），**TaskArchive:93 + TaskQueryTool:106/152/167 四处 toString.toLowerCase 漏网**——最硬的是 TaskQuery schema enum 承诺 "in_progress" 但过滤必空（契约自相矛盾）+ _index.json 与任务文件同数据双格式。测试全绿因 Spec 恰好只测 completed。**边界：三条数据红线（R1/R2/R5）全过无数据风险，770/0 亲证**；修复面小（共享 wireName+四处替换+两测试+index 重建），qa 预计一轮过。修复到达→qa 复验（四处+in_progress 两测试+重建后 index 值）→ PASS 合并。涌现协作按设计运转：qa 直邮打回无需 Manager 中转。

- 01:04 ✅ **B 线全链闭环**：组合态全量 **811/0/8**（186s，G5 教训执行——组合态任何一方没跑过的全量验证），worktree nb-onight-backend + feat/onight-backend 分支已清。**archive/scala @ 899e4057**：今晚已含 C1+C4+B4+T10+B 线七 commits（FlowTrigger 修复+F1-3+autostart+deletePaths+Onboarding 探活+FileTypeRegistry+Hook 31+W4-1+probeOkAt 硬门禁）。剩余战场：onight-frontend @49233fad（Frontend B 线产出中）/ toolopt-task @cffa714b（Backend 修复 C2 中）/ toolopt-frontend（等接续）。

- 01:07 🔵 **C2 修复到达转复验**：5f69db16（772/0）——TaskStatus.wireName 单源（TaskModel object 级权威）+ qa 点名 4 处全替换 + **自查扩面 2 处**（TaskUpdateTool :164/:183 agent 可见字符串同病）+ 2 新测试含索引值先断言强化 + _index.json 脏值生产无迁移论证（今晚零部署，真实 tasks/ 尚无索引，首建即净）。打回→修复 4 分钟。qa 复验中。

- 01:09 ✅ **qa C2 复验 PASS**（4 处点名✓/自查 2 处 diff 实录✓/全局残留 grep 唯一命中无关 HTTP status/2 新测试含索引值先断言/脏值 ls 404 实证/772=770+2 对数）→ **C2 合并 4690c11d**（+999/-14，WebSocketRoutes 双方改动 ort 自动合并干净）。组合态（B线+C2）全量后台验证中。qa 侧今晚验收全链闭环（用例→C1/C4 验→B 线验+门禁发现→C2 打回复验），剩 C3 前端 F 组待验。

- 01:12 ✅ **C2 全链闭环**：组合态（B线+C2）**836/0/8**（811+25 对数精确），worktree nb-toolopt-task + 分支已清。**archive/scala @ 4690c11d**。C 线后端全收口（C1+C4+C2），唯一剩 Frontend：B 线（onight-frontend @d0abb7de 产出中，五任务）→ C3 接续（toolopt-frontend）。

- 01:26 🔵 **Frontend B 线五任务交付**（feat/onight-frontend，127 项 mock Playwright 全过）：49233fad F1 串台（根因=msg.sessionId vs nodeSessionId，20/20）/ 36286637 F2 toggle（14/14 含失败回弹）/ d0abb7de 文件多选（37/37，选中态纯背景合规）/ 07528f67 Canvas 12 内置迁移（27/27 与基线逐项一致，+1052/-883）/ bef513db Onboarding 固定式（29/29，探活不过绝不发问候+injectUserMessage 原生链路）。SubTask 并行+逐一独立重跑纪律好。**worker 上报既有 bug**：explorer.js deleteNode 双发 deletePath——已派 Frontend C3 前先修。
- 01:31 🔵 **B 线前端一次成型**：8cb2ac72 explorer 双发修复（+6/-3，if/else 与 deleteSelected 同款，42/42，确认前 0/确认后 1 断言入 harness）——feat/onight-frontend 6 commits 齐（49233fad→36286637→d0abb7de→07528f67→bef513db→8cb2ac72），Frontend 转 C3。**验收要点已转 qa**：mock configData 必须带 onboarding 字段（缺字段=老用户一次性提示 overlay 拦截点击，预期产品行为非 bug——真实环境后端 getConfig 三态供值）。01:28 qa 开验 B 线（重点：Onboarding 硬编码合规+探活失败零问候/多选无 rename/弹窗禁令/Canvas 抽验）。

- 01:32 🔵 **qa B 线前端验收进行中**（/tmp/qa-backend-reports/onight-frontend-20260816.md）：explorer 8cb2ac72 复验 PASS（双发根因实锤+typeof 判定更稳+回归断言入 harness 亲证 42/42）；五重点面全过；**两打回点挂起**（①ob-no done→skipped 一行修+probe_required 防回归场景 ②F1 脚本 mock 补 onboarding 字段重跑）——qa 直邮 Frontend 流转中，修复到达复验后出终判。B 线合并等终判。

- 01:35 🔵 **两打回修复完成 641d7633**（B 线第 7 commit）：ob-no 改发 'skipped'（主动 decline 豁免探活门禁——与 Backend probe_required 硬拒绝闭环，原发 done 会被拒成死循环）+ 回归双断言，onboarding 30/30；F1/f2/f3 verify mock 补 onboarding 字段，20/20。**B 线分支 7 commits 齐 @641d7633**，qa 终判复验中，PASS 即合并。Frontend 转 C3。

- 01:36 ✅ **B 线前端终判 PASS 全链闭环**：合并 **1cdf2e07**（7 commits，+1834/-931，locales 自动合并干净，viewers/ 12 内置+onboarding.js 入库），worktree/分支已清。qa 终判证据：ob-no done→skipped diff 实录（greet 路径 done 不变的区分正确）+E 场景三断言 30/30 亲证；F1 20/20 亲证。**今晚主仓七批合并全部落定 @1cdf2e07**（删工具基线→C1/C4→B4→T10→B 线后端→C2→B 线前端），qa 战绩四分支验收零挂账。唯一剩 C3（Frontend 编码中，qa F 组就绪 N 口径已修正）。

- 02:05 🔵 **C3 交付**：feat/toolopt-frontend @ 1ff3c641（+718/-6）：taskList.js 今日完成折叠条（N 口径精确执行/前 10 条 completedAt 倒序/expanded 跨重渲染保留/0 活跃独立展示——**抓到 .has-today 真 bug**：#task-list 无 .has-tasks 时 max-height:0 裁掉 bar）+ taskArchive.js/css 档案视图（Canvas tab persistTabs/项目栏/分组时间线/failed 删除线红 x 默认可见/dismissed 前端防御/notes.links 双路/events 折叠）。**35/35 Playwright 全 textContent 实断言 + 4 截图**。两点声明：①375px 溢出=既有 baseline（daemon panel 400px，零 C3 代码 boot 实测 scrollWidth=650）→ daemon panel 移动端适配挂明早待办 ②notes/events 按 C2 文档消费+缺席防御。闲时请缨 G4/G6 经 git log 核查为 08-15 03:00 批次存量（见 02:08 行），撤回授权零重复劳动。
- 02:09 ❌→🔵 **qa C3 验收 FAIL 一处（契约断裂）+ 裁定**：TaskNote wire 字段 Backend 实现 `text` 偏离设计文档 `content` 未声明，C3 前端按文档消费 `content`，mock 同错互相印证掩盖断裂（35/35 绿但真实链路 notes 空）。**裁定方案 a：实现向文档对齐**（契约权威，不树"前端迁就实现"先例；生产零数据零迁移；by 字段不加 P1 勘误）。执行：C3 前端零改动独立正确**先合并**，Backend 修复走独立小分支 /tmp/nb-notefield（fix/note-wire-field）。qa 复验方案就绪（diff/C2 用例/真实链路对齐/前端零重跑）。另：qa 自省 C2 验收漏字段名逐字对照，记入报告教训段。
- 02:10 ✅ **C3 合并 8a609292**（+718/-6，locales 与 B 线重叠**自动合并干净**），组合态验证：双文件 node --check 过 + 406 键中英对称 + task 新键在场。worktree/分支已清。**archive/scala @8a609292=今晚八批合并**。唯一剩单：Backend 字段修复（nb-notefield 已建 @8a609292）→ qa 复验 → 终合并。

- 02:08 ✅ **G4/G6 闲时授权撤回（防撞车拦截）**：Frontend 拉 worktree 前 git log 核查发现 G4（a0e17023）/G6（8405dc77）/G3（8a39e34b）/G5（2ffb99ab）/VisionErrorPatterns 中文盲区（4d3a8e10）**全部已在主线**——实为 08-15 03:00 定时任务（bedc4702）存量产出（我 memory 白天波合并链有记录），非今晚产出（Frontend 初判"03:14 定时任务产出"为昨天时间戳，已修正）。零重复劳动，未建 worktree。**Manager 教训沉淀：派工闲时/批量任务前必核 memory 完成记录+主线 log**。

- 02:13 🔵 **字段契约对齐修复到达**：fix/note-wire-field @ c1a0523a（nb-notefield @主线顶 8a609292）：TaskNote text→content 三文件纯改名（deriveCodec 自动跟随/零迁移/by 不加已注明）+全仓残留 grep 干净，836/0。转 qa 终验（今晚最后一单）。

- 02:15 ✅ **终合并 c1a0523a（fast-forward，与 qa 终验同 hash）——今晚批次全收口**。qa 终记分：6 单验收 + 4 次 FAIL 判定全部打回-修复-复验闭环，零挂账零数据风险。**今晚九批合并链**：91117499（删工具基线）→73e9d6b3（C1+C4）→182dff3c（B4）→6345eced（T10）→899e4057（B 线后端）→4690c11d（C2）→1cdf2e07（B 线前端）→8a609292（C3）→c1a0523a（字段契约对齐）。**最终态 836 passed / 0 failed**。worktree/分支全清（主仓仅余 NebLink 测试遗留 2 个未跟踪文件）。02:15 收官，富余 06:00 时限 3h45m。

## 阻塞清单

（超 20 分钟的阻塞项 + 处置）

## 明早待办（用户重启后）

- [ ] 重启实例（激活 FlowTrigger 修复 7408a4b4 + 今晚全部批次）
- [ ] W2 锚点 1/2/3/5 补验（research 并行/Barrier/verdict/断裂 flow）+ W3 E2E 三锚点
- [ ] flow 合并回归启用（下一批合并起）
- [ ] 官网预览确认（今晚官网改动未 push 未部署）
- [ ] F4 CI push 后首个三平台产物验证
- [x] W4-1 文案行（ManagerSaveMemoryReminder 第 5 项）——✅ 已随 B 线 5ced3d3c 落地（PERIOD SELF-CHECK），无需再派
- [ ] daemon panel 移动端适配（375px 溢出 scrollWidth=650，元凶固定 400px 宽——C3 声明 1 证实为既有 baseline，低优先）
- [ ] TaskNote `by` 字段 P1 勘误（设计文档 B1 有、实现无——c1a0523a commit message 已注明待议）
- [ ] parity 文档两处已删工具引用（docs/behavior-parity/README.md:14、docs/reviews/parity/01-tool-execution.md）——派 Docs（Rust 团队暂停不动其域）
- [ ] probeOkAt 强校验 P1（配置变更后旧 probeOkAt 失效语义——qa 00:43 建议）
- [ ] team rules.md「版本与发布管理」段仍是 semver 规则（PATCH/MINOR 递增），与 F4 日期制方案（YYYY.MM.DD-beta.N）不一致——**需用户裁定**（手定规则段，修改须用户确认；改后同步 README/docs 映射说明，Docs 段活）
