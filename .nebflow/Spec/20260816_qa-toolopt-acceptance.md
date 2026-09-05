# C 线工具优化 — 可执行验收清单

> 验收人：qa-backend ｜ 创建：2026-08-16 00:20 ｜ 基线：主仓 archive/scala @ 91117499（注意：已比任务书分析的 7408a4b4 前进 1 个提交）
> 任务书：/tmp/tool-opt-edit.md（C1）、/tmp/tool-opt-task-inventory.md（C2/C3/C4）
> 执行规则：在各源 worktree 内验收（/tmp/nb-toolopt-core、/tmp/nb-toolopt-task、/tmp/nb-toolopt-frontend）；今晚零 push；禁 sbt run / kill；sbt 锁冲突时错峰重试。

## 基线偏差提示（影响验收语义，产出者必读）

1. **SaveWorkspaceItem / RemoveUnnecessary 已在 91117499 删除**，BashTool description 中的 `Card` 引用已同步清理（grep 验证：src/main/scala 无任何 `SaveWorkspaceItem`/`Card` description 引用残留；`sanitizeCardOutput` 是内部函数名，不是工具引用）。→ C4-Q3 的验收从「改名对齐」**改为「回归断言：无幽灵工具名」**（见 Q3）。**基线确认（Manager 00:10）：C 线三个 worktree（core/task/frontend）全部从 91117499 拉取，无需 rebase**；验收时仍以 `git log` 实证为准。
2. 任务书分析基线 7408a4b4 与主仓现 HEAD 91117499 的 TaskModel/TaskStore/EditTool/StringMatcher/DiffUtil/ScheduleTool 逐文件核对无差异（TaskStore 行号 133-139/209-217/246-274 均吻合）。
3. renderForPrompt byte-identical 属 Task 域红线，归入 C2-R5（Manager 指令中列在 C1 项下，按实际归属调整）。
4. 今晚**无隔离运行环境**：任务书 E1 验收 1 的 `sbt run + curl` 冒烟、E2 的真实 Playwright 冒烟 **deferred**——降级为单测层 + 静态检查（各用例已给出替代步骤），真实冒烟待 Manager 提供隔离实例或明早窗口补跑。

---

## C1 MultiEdit（产出者：tool-engineer ｜ worktree：/tmp/nb-toolopt-core）

前置：worktree 内 `sbt compile` 零错误。所有 T* 用例除注明外均指 MultiEditToolSpec（新增）；T9 为既有 EditToolSpec 回归。

### T1 互不重叠多编辑全应用
1. 构造 30 行临时文件（含 3 个互不重叠的锚点串 A/B/C）
2. MultiEdit edits=[{A→A'}, {B→B'}, {C→C'}]
3. 期望：返回 OK，`A'/B'/C'` 全部落盘，其余行不变；返回结果含统一 diff（基于 original vs 最终 buffer 一次生成，**不得**出现中间态 hunk）
- 证据：测试名 + 断言的文件终态全文

### T2 原子性（红线）：单条失败整批不落盘
1. 记录文件初始字节（测试内 `os.read.bytes`）与 mtime
2. edits=[{A→A'（会成功）}, {X→Y（文件中不存在）}, {C→C'}]
3. 期望：调用返回 Left；**文件字节级相同**（逐字节比较，非语义比较）；mtime 不变
4. 失败消息含 `edits[1]` 序号定位 +「未写盘/文件未修改」类声明 + 最近候选行号提示（候选位于第 N 行附近 + 相似度）+ 建议的 Read(offset,limit) 动作
- 证据：测试名 + 失败消息全文断言（含序号与「未写盘」关键词）

### T3 顺序语义：前序 edit 改变后续匹配区域
1. 文件含 `val x = 1`；edits=[{`val x = 1`→`val x = 2`}, {`val x = 2`→`val x = 3`}]
2. 期望：第二条在第一条应用后的 buffer 上匹配成功，终态 `val x = 3`（证明 edits[i] 在 edits[0..i-1] 结果上匹配，非原始快照）

### T4 非唯一匹配拒绝
1. 文件中串 M 出现 2 次；edits=[{M→M'}] 且未设 replace_all
2. 期望：失败，消息含匹配次数（Found 2 matches 类）与「扩大上下文或设 replace_all」提示；文件不变
3. 补充：replace_all=true 时两处全替换成功

### T5 四级模糊匹配复用 + 引号保持
1. 精确匹配失败但缩进变体命中（old_string 用 2 空格、文件为 4 空格）→ 成功且落盘为文件原缩进风格
2. 弯引号变体：文件含 curly `“”`，old_string 用直引号 `"` → 命中，且 new_string 中的直引号被 preserveQuoteStyle 转为弯引号
- 证据：两个子用例分别断言终态内容

### T6 外部并发修改检测
1. 锁内构造：edits 全部匹配成功后、写盘前，篡改磁盘内容与 mtime（单测可用注入 hook 或将检查逻辑抽为可测函数——接受产出者的等价可测设计）
2. 期望：报 `File was modified externally. Please re-read and retry.`，不落盘

### T7 边界输入
1. edits=[] → 明确错误消息
2. edits 数 = 51 → 明确错误消息（上限 50）
3. 全部 edits 应用后 buffer == original → 「没有产生任何变更」类错误，不写盘
4. maxItems=50 边界值恰好 50 条 → 正常执行

### T8 行分隔符保持
1. 写 CRLF 文件（`\r\n`）；MultiEdit 修改后读回：行分隔符仍为 `\r\n`（不得混入裸 `\n`）

### T9 EditToolSpec 零回归（重构守恒）
1. EditTool 抽取 `applyEditToBuffer` 纯函数重构后：既有 EditToolSpec 全部用例不改一行仍绿
- 证据：`sbt test` 输出中 EditToolSpec 计数（用例数与改造前一致，全过）

### T10 与 Edit 锁互斥（单测层等价验证）
1. 同一文件并发发 1 MultiEdit + 1 Edit（cats-effect `IO.both` / parSequence，同 fileLockManager 实例）
2. 期望：无撕裂写（读回内容 ∈ {MultiEdit-then-Edit 合法终态, Edit-then-MultiEdit 合法终态}）；两调用均返回（无死锁）
3. 代码审查项：MultiEdit 的**完整 read→loop→write 链路必须在单次 `withWriteLock` 内**（对照 EditTool.scala:137-139 语义），禁止锁外读锁内写

### T11 注册表
1. registry.scala 含 `"MultiEdit" -> MultiEditTool`；`ToolRegistry.builtinToolNames` 含 MultiEdit
2. ALL_TOOLS 导出的 schema 与任务书 §4.2 一致（file_path + edits[]，edits items 含 old_string/new_string/replace_all，minItems 1 / maxItems 50）

### C1 通过判据
T1-T11 全 ✅；`sbt test` 全绿（现有 0 回归 + 新增 ≥ 9 用例）；T2 原子性断言为**字节级**。任务书 §5.3 的并发冒烟/E2E/自愈验证为 deferred 项（需隔离实例），单测层以 T2/T10 等价覆盖，真实冒烟明早补。

---

## C2 Task 后端（产出者：Backend ｜ worktree：/tmp/nb-toolopt-task）

### 真实旧档 fixture 样本（已从主仓 ~/.nebflow/tasks/ 取样，共 134 session / 798 文件 / 535+ completed）

产出者应将以下 JSON **内嵌进测试代码或 test/resources**（勿在运行时读 ~/.nebflow——环境依赖），注明来源路径：

**S1 现代格式·completed（来源 ~/.nebflow/tasks/003021f7-bca2-4a5d-b02d-b743d10e59c8/6.json，任务书点名的 fixture）：**
```json
{"id":"6","subject":"P0: remote-exec 鉴权加固 — 引入 device secret","description":"为 P2P 通信引入 device-level secret。DeviceIdentity 增加一个 deviceSecret 字段，peer 端点验证 userId + deviceSecret，而非仅 userId。","activeForm":"修复 P0: remote-exec 鉴权过弱","status":"completed","blocks":[],"blockedBy":[],"createdAt":"2026-06-09T13:44:41.596134Z","updatedAt":"2026-06-09T13:48:46.579475Z"}
```

**S2 老格式 v1·缺 createdAt/updatedAt + 未知字段 metadata（来源 ~/.nebflow/tasks/165061b2-23eb-48a8-8a06-2836c34e64fc/1.json——比任务书预想更极端的样本）：**
```json
{"id":"1","subject":"演示任务系统功能","description":"创建一个示例任务来展示任务管理系统的基本功能，包括创建、更新、查询等操作","activeForm":"演示任务系统功能中","status":"completed","blocks":[],"blockedBy":[],"metadata":null}
```

**S3 dismissed（来源 ~/.nebflow/tasks/5cc7590a-6dcb-4dba-8979-0ce5eb0a14fe/1.json）：**
```json
{"id":"1","subject":"Create baseline branch with current uncommitted changes","description":"Create temp/flow-baseline worktree from main, apply uncommitted changes, commit. This is the base for all flow fix branches.","activeForm":"Creating baseline branch","status":"dismissed","parentId":null,"blocks":[],"blockedBy":[],"createdAt":"2026-07-23T12:59:12.353180Z","updatedAt":"2026-08-05T05:49:47.474550Z"}
```

**S4 failed + 依赖（来源 ~/.nebflow/tasks/c42fe49b-df07-40ba-b2ec-05c7612277ad/8.json）：**
```json
{"id":"8","subject":"前端交互界面 v1 (已废弃，需重写)","description":"前端: Three.js 3D 探测器（可点击设置交互位置）+ 参数面板（能量、角度、探测器尺寸、偏压、迁移率、粒子类型）+ 实时波形图（Chart.js，显示总信号/电子/空穴分量）+ 能谱图（支持 log/linear 切换）。WebSocket 实时更新。","activeForm":"构建前端交互界面","status":"failed","blocks":[],"blockedBy":["5","6"],"createdAt":"2026-06-07T13:24:25.717562Z","updatedAt":"2026-06-07T14:01:10.711460Z"}
```

**S5 delegate 目录·parentId 显式 null（来源 ~/.nebflow/tasks/delegate-Nebula-299ce368/1.json）：**
```json
{"id":"1","subject":"探索 Scala 版上下文压缩现有实现","description":"探索 archive/scala 分支的 ContextRefresher, compaction, DreamMode, AgentCore 等源文件，理解现有架构","activeForm":"探索现有上下文压缩实现","status":"completed","parentId":null,"blocks":[],"blockedBy":[],"createdAt":"2026-08-10T00:39:06.920775Z","updatedAt":"2026-08-10T00:40:33.955765Z"}
```

### R1（红线）旧档 decode 兼容
1. S1-S5 逐一 `decode[Task]` → 全部 Right
2. 旧字段**逐一断言值保留**（id/subject/description/activeForm/status/parentId/blocks/blockedBy/createdAt/updatedAt 与源 JSON 相等——尤其 S4 的 `blockedBy=["5","6"]`、S5 的 `parentId=null`、S2 的 `activeForm="演示任务系统功能中"`）
3. 新字段 = 默认值：`completedAt == None`、`notes == Nil`、`events == Nil`
4. S2 的未知字段 `metadata` 被忽略（不得开 strictDeserialization）
- 原理备注（验收校验点）：semiauto `deriveCodec` 对 `Option` 字段缺失返回 None 不报错，**真正会炸的是带默认值的 List 字段（notes/events）缺失**——若产出者只加 Option 字段不加 useDefaults，R1 第 3 步会暴露；若根本没加字段，R1 过但 R3/R4 不过。测试必须真实走 `decode[Task]`，禁止只测 `.toString`。

### R2（红线）list() 不丢档
1. 将 S1-S5 放入临时 tasks/<sessionId>/ 目录 → `list(sessionId)` 返回 **5 条**（一条不少）
2. 回归：现有 FileTaskStore 相关既有测试全绿（若有 TaskStoreSpec）
- 证据：测试名 + 返回条数断言

### R3 completedAt 自动写入
1. create → update(status=in_progress) → update(status=completed)：磁盘 JSON 含 `completedAt`，值 ≈ now（非空断言 + 与 updatedAt 同秒级）
2. update(status=failed) 同样写 completedAt（任务书 B1：「进入 completed/failed 的时刻」）
3. pending→pending（no-op update）不写 completedAt
4. 磁盘写回后再读：completedAt round-trip 保留

### R4 事件流
1. create → events 含 `kind=created`
2. update description → events 追加 `kind=description`，detail 含旧值摘要（前 120 字）
3. update status → `kind=status`，detail 形如 `in_progress→completed`
4. 依赖变更（addBlockedBy）→ `kind=dependency`
5. 连续 60 次变更 → events 长度 = 50（最旧被截）
- 证据：各 kind 的断言测试名

### R5（红线）renderForPrompt byte-identical
1. 构造 session：#1 completed、#2 in_progress(activeForm=Some)、#3 pending
2. `renderForPrompt` 输出必须与下方基准**逐字节相等**（含尾部换行、双空格缩进、em-dash）：
```
## Current Tasks

Your task list is below. Work through tasks in order. Mark each as completed when fully done.

#2 [in_progress] <subject2> — <activeForm2>
#3 [pending] <subject3>
```
3. 反向断言：#1（completed）的 subject 串不出现在输出任何位置；输出中不出现 notes/events/completedAt 字段
4. 全 completed 的 session → 输出空串 `""`（现状 249 行行为）
- 基准依据：主仓 TaskStore.scala:246-274 现行实现（纯字符串拼接，确定性输出）。产出者另需在 worktree 做一次「改造前 vs 改造后」双跑对照（git stash / checkout 改造前代码跑同一断言）作为自证。

### R6 TaskQuery 工具
1. scope=recent&since=7d → 只返回 7 天内 completedAt/createdAt 命中的任务，倒序
2. scope=project&project=<folderName> → 只返回该 folder 的任务（走索引）；孤儿 session 归「未分类」且 folderId=null 时可查
3. scope=session → 当前 session 任务（不依赖索引器）
4. status=completed/failed 过滤；keyword 对 subject+description 模糊匹配；limit 默认 30 生效
5. since 词法：today / yesterday / 7d / ISO-date 四种
6. 子代理（无索引器访问）降级为仅 session 查询——降级不抛异常
- 证据：tool 层真实调用测试（任务书 E1-6：不 mock store）

### R7 /api/nf-tasks 归档端点
1. http4s 路由单测（RestApiRoutesSpec）：`GET /api/nf-tasks?since=7d` → 200 + JSON 数组，元素含 folderName/group 字段
2. 参数组合：folderId=&since=&status=&keyword=&limit= 全部生效（与 R6 语义一致）
3. 空结果 → 200 + `[]`（非 404/500）
4. curl 实例冒烟 **deferred**（今晚禁 sbt run）——产出者在 worktree 跑通单测即可，curl 步骤明早隔离实例补

### R8 _index.json 索引器
1. 构建：多 session（含 S1-S5 样本 session）→ 生成 `~/.nebflow/tasks/_index.json`，entries 含 sessionId/folderId/folderName/taskId/subject/status/createdAt/completedAt/noteCount/hasLinks
2. folderId join：session 存在于 SessionStore → 正确 folderId/folderName；session 已删 → folderId=null（未分类）
3. 增量：TaskCreate/Update/Dismiss 后该 session 段刷新（挂 TaskToolHelper 管道），无需全量重建
4. 磁盘约束：tasks/ 下**只新增** _index.json 一个文件，不改目录结构
- 注：单测用临时 dataRoot（PathUtil 可注入或系统属性），勿写真实 ~/.nebflow

### R9 taskListUpdate 向后兼容
1. WS 消息结构断言：`tasks` 数组元素在既有 11 字段基础上**只增不改**（新增 completedAt/notes/events 字段名与类型）
2. TaskToolHelper.emitTaskListUpdate 仍走 `store.list`（全量）——旧前端（忽略未知字段）行为不变的代码级确认

### R10 TaskUpdate note 参数（若本期交付含 B5）
1. update(note="x", noteLinks=["/tmp/a.md","abc123"], status=completed) 一步走 → notes 追加 1 条（content/links/at/by），status 变更与 note 同调用生效
2. notes append-only：第二次 note 不覆盖第一条
3. description 文案鼓励 completed 带 note（diff 检查 TaskUpdateTool.scala）

### C2 通过判据
R1/R2/R5 三条红线任一 FAIL 即整体 FAIL（数据安全优先于功能）。其余 R3/R4/R6-R10 按实际交付范围逐条二值判定（今晚若只交 completedAt 部分，R6-R8/R10 标 NOT-DELIVERED 不算 FAIL）。

---

## C3 Task 前端（产出者：Frontend ｜ worktree：/tmp/nb-toolopt-frontend）

> Playwright 完整冒烟需 dev server。今晚降级：F1-F3 的 node/jsdom 级验证 + 静态检查；有环境则补完整 Playwright。所有 DOM 断言必须查 **textContent 实际内容**，禁止只断言元素存在。

### F1 「今日完成 N」折叠条渲染
1. 注入 fixture：taskListUpdate 消息含 3 active + 2 completed(completedAt=今天) + 1 failed(completedAt=今天) + 1 dismissed(completedAt=今天)
2. 断言折叠条 textContent 含 i18n 文案（中=「今日完成」）+ 数字
3. **口径已裁定（Manager 00:10）**：「今日完成 N」的 N = **status==completed 且 completedAt 存在且日期为今天（本地时区）** 的任务数。failed **不计入**（即使有 completedAt——避免"完成 5 但 3 个失败"误导；failed 在档案视图照常展示带失败标注，信息不丢）；dismissed 不计入。折叠条展开列表与 N 同口径（只含 completed）。依据此裁定，F1 fixture 的期望 N = 2
4. 时区：「今天」按**用户本地时区**解析 completedAt（UTC ISO 串），跨时区边界任务（本地 23:30 完成）不串日
5. 无今日完成时折叠条隐藏（不留空壳 DOM）

### F2 折叠条展开
1. 点击折叠条 → 展开最近 10 条（**只含 completed，与 N 同口径**）：textContent 含各条 subject + completedAt（人类可读格式）+ 首条 note.content
2. 11 条今日完成 → 展示 10 条 + 「查看全部」入口
3. 再次点击收起

### F3 档案视图
1. 「查看全部」打开 taskArchive 视图：项目分组（folderName）→ 组内时间线倒序
2. 条目详情含 events 时间线 + notes；notes.links 的**文件路径**点击走 popFile 链路（Canvas 打开对应文件）、URL 点击打开 iframe
3. 数据源 = GET /api/nf-tasks（与 TaskQuery 工具同源）——网络请求断言（fetch/WS mock 层面确认 URL 与参数）
4. 窄屏（<768px）折叠为单栏（左项目栏隐藏或抽屉化）

### F4 双主题截图对比
1. prefers-color-scheme: light / dark 各截一张（折叠条 + 档案视图）
2. 断言：无对比度问题（人工审图 + 折叠条文字与背景对比可辨）；遵守 CSS 变量（无硬编码色值——grep taskList.css/taskArchive.css 新增规则中的 #hex/rgb( 除 var() 回退外）
3. 无 accent bar、无 emoji（团队 UI 惯例）

### F5 响应式
1. 1280×800 / 768×1024 / 375×667 三视口截图，档案视图无横向溢出（document.documentElement.scrollWidth <= viewport.width）

### F6 活跃任务卡回归
1. 现有 entering/flipping/leaving 动画行为与改造前一致（改造前后各录一段/截对比帧；最低限度：现有 taskList.js 渲染路径的既有行为不被破坏——active 卡片渲染断言）

### F7 i18n
1. `task.completedToday` 等 key 中英双语均存在（i18n*.js grep）；无硬编码中文串散落组件

### F8 dismissed 隔离 + failed 定位
1. dismissed 任务不出现在折叠条计数、展开列表、档案视图的默认过滤中（档案视图 status=dismissed 显式查询除外——与后端 R8「档案中仍可见 dismissed」的口径对齐：默认视图不含，显式查询含）
2. failed 任务：不出现在折叠条计数与展开列表（裁定口径），但在档案视图**默认可见**且带失败标注（x icon / failed 样式）——textContent 断言其 subject 出现在档案视图

### C3 通过判据
F1/F2/F3/F7/F8 的 jsdom/静态级 ✅ 为今晚硬性；F4/F5/F6 截图级在 dev server 可用时执行，否则 deferred 并在报告中标注。textContent 断言缺失任何一条 → 该项 FAIL。

---

## C4 速赢（产出者：tool-engineer ｜ worktree：/tmp/nb-toolopt-core）

### Q1 Write 孤立 \r 清洗（DiffUtil.scala:40-43）
1. 单测：content 含孤立 `\r`（非 `\r\n`，如 `"a\rb\n"`）→ 落盘字节无孤立 `\r`
2. `\r\n` 文件经 writeFile(lineSep="\r\n") → 行分隔符保持 `\r\n`（既有行为不回归）
3. `\r\n` 归一为 `\n`（lineSep="\n"）→ 落盘无 `\r` 任何形态
4. 混合：`\r\n` 与孤立 `\r` 并存 → `\r\n` 语义保留、孤立 `\r` 清除
5. 回归：既有 DiffUtil 测试全绿
- 注意：清洗应在归一化之后作用于 normalized 串，避免破坏 \r\n 转换顺序

### Q2 Schedule ISO/自然语言时间（ScheduleTool triggerAt，现为 integer epoch ms）
1. ISO 8601 带时区（`2026-08-17T09:00:00+08:00`）→ 正确 epoch
2. ISO 本地日期/日期时间（无时区后缀）→ 按服务器本地时区解析（与用户时区机制对齐）
3. `"tomorrow 09:00"` → 明天本地 09:00
4. epoch 毫秒数字（旧行为）→ 仍兼容，不断裂
5. 非法串（"next week sometime"）→ 明确错误消息（含支持的格式列表）
6. 过去时间 → 报错（既有语义保留）
7. schema：triggerAt 类型改 string 或 [integer,string] anyOf，description 更新列明支持格式
- 证据：三格式 + 兼容 + 非法的测试名

### Q3 幽灵工具名回归断言（语义已更新，见基线偏差提示 1）
1. **静态回归**：`grep -rn "SaveWorkspaceItem\|RemoveUnnecessary" src/main/scala` 零命中（保持 91117499 清理态）
2. **新增守门单测**（防未来再犯）：遍历 `ToolRegistry.builtinToolNames` 对应 tool.description，提取其中引用的候选工具名（大写开头的词与注册名/已知删除名比对），断言无引用已删除或未注册的工具名。最低验收：产出者提供一条单测或脚本，能对全部 20 个 builtin description 跑出「引用 × 注册表」diff 报告，全部引用 ∈ 注册表
- 若产出者判断单测不可行（描述文体多样），可用检查脚本 + 本次 grep 输出作证据，Manager 裁定是否豁免

---

## 执行流程备忘（qa-backend 自用）

1. 交付通知到达 → `cd /tmp/nb-<worktree>` → `git log --oneline -5`（确认基线含 91117499 或已 rebase）→ `sbt compile` → `sbt test`
2. sbt 锁冲突（并行产出者同时构建）→ 等 60s 错峰重试 ≤3 次，仍失败如实报 BLOCKED（不判 FAIL）
3. 代码质量三查：错误路径显式（IO raiseError/handleErrorWith）/ Actor-IO 分层（TaskArchiveIndexer 属 gateway 层，勿让 core 依赖 gateway——见任务书 B3「folderId 在索引器解析」）/ 资源释放
4. 每验一项 → Mail Manager [RESULT] PASS/FAIL + 证据（测试名/输出摘录/文件:行）
5. FAIL → Mail 产出者（C1/C4→tool-engineer；C2→Backend；C3→Frontend）附逐条问题，抄送 Manager
6. 报告归档：/tmp/qa-backend-reports/toolopt-<域>-<日期>.md
