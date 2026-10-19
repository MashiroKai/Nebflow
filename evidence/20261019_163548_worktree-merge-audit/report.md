# 审计 · worktree 分配/自动合并链（只读 · 零改动 · 零 push）

审计对象：`DelegateWorkspace`（分配）→ `DelegateTerminal`（终态）→ `DelegateAutoMerge`（引擎自动合并）/ `MergeQueue`（delegate 自执行合并轮）→ `PostMergeCleanup`（回收）。
证据基线：本仓 main@c9f6cd18f（含 f707cfdc4，DelegateAutoMerge 落地提交）；运行数据根 `~/.nebflow/`（delegates/ 173 记录、chains/ 150+ 账本文件、logs/stand.log）。
读数快照另见同目录 `raw-readings.md`。

---

## 问题清单（按严重度）

### P1-1 续聊会话零 git 锚 → 自动合并/合并队列双盲（今日 #557/#558 直接触发）

**根因（认领键断裂）**：自动合并的认领键 = **完成会话自己的 registry 记录里的 `branch`**（`DelegateAutoMerge.onCompletedHook`，DelegateAutoMerge.scala:155 `rec.filter(_.branch.nonEmpty)`；记录键 = 会话 id）。分支命名 = `delegate/<spawn时会话id>`（DelegateWorkspace.scala:98），锚只落在**原会话**的记录上。

而 Mail 续聊复活腿（`MailTool.continueDelegate`，MailTool.scala:1313-1348）spawn 新会话时：
- `workspace = record.cwd`（= **原会话的 worktree**——座位继承 ✓）
- 但 `spawnBackground` 未传 `judgeWorkspace`（缺省 **false**，DelegateTool.scala:581）→ 判定机腿整体跳过（DelegateTool.scala:672-679 `if judgeWorkspace … else IO.pure(None)`）→ 新记录 **branch/worktree/baseBranch/projectWorkspace 全 None**（对照 DelegateTool.scala:761-764 的落账位）。

代码自证这是「有意但错」：新鲜派发腿注释明写「Mail continue 复活腿（不经此处）保持旧形态」（DelegateTool.scala:358-359）。

**完成时的连锁失效**（全部因为认领键查不到分支）：
1. 自申报 merge → 判词 ReadyToMerge，但 `branch.isEmpty` ⇒ **降级 completed**（DelegateTerminal.scala:185）——合并队列永不入队；
2. auto-merge 钩子 `rec.filter(_.branch.nonEmpty)` = None ⇒ **静默 None**（DelegateAutoMerge.scala:155-156）——零合并、**零审计行**；
3. P2-3 unmerged-work 注记也不触发（aheadOfBase 依赖 branch，DelegateTerminal.scala:165-175）——零注记。

交付提交躺在 `delegate/<原会话id>` 上，引擎任何机制都不再认领（原会话已终态，无重评面）。

**同型缺陷腿（本轮一并修）**：
- `MergeQueueBootSweep.internalRevival`（MergeQueueBootSweep.scala:76-100）：重启恢复的合并轮续起会话**同样不传 judgeWorkspace**，座位=基工作区（合理）但锚不传 → 新记录无锚 → 终态时机械验收闸 `MergeAcceptance.verifyRecord`（MergeAcceptance.scala:42-47，锚不全 = 必 false）⇒ **恢复腿的合并轮即使 merge 成功落地也必判 blocked**（潜伏，今日未触发——今日无重启恢复轮）。
- `DeviceDelegate.spawnLocalDelegate`（DeviceDelegate.scala:408）：设备受托腿同样零绑定。

**修法**：续聊/恢复/设备三腿显式继承原记录的 git 锚——`spawnBackground` 增 `inheritBinding`（或 continuation 专用参数），把原记录的 `branch/worktree/baseBranch/projectWorkspace`（四键，cwd 不动）透传进新 Record；DelegateAutoMerge/MergeQueue/MergeAcceptance/PostMergeCleanup 认领键零改动即全部复明。配套 spec：续聊会话带提交完成 → auto-merge 评原分支。
**建议批次**：小（MailTool 1 处 + DelegateTool 参数 1 个 + BootSweep/DeviceDelegate 2 个调用点 + DelegateAutoMergeSpec 增 2 判红锚），约半天。

### P1-2 auto-merge 钩子「无锚跳过」零审计、两腿不对称

DelegateAutoMerge.scala:155-157：记录缺失/branch 空 ⇒ 返回 None，**不落任何 ChainStore 行**（对比 attempt 内所有 skip 路径都落 `skipped` 行：188、196、201）。外部腿 settle 反而有显式尾注「[auto-merge] skipped (no branch anchors on record)」（ExternalSessionRunner.scala:556）。结果：P1-1 形态的失效在生产上完全不可观测（今日 chains 里 auto-merge 行总数 = 0，无从区分「特性没生效」与「被静默跳过」——本审计靠逐文件考古才定案）。
**修法**：钩子对「有记录但无锚」也落 `skipped` 行（reason=continuation-without-binding 之类），内部腿补与外部腿同款的可见尾注。
**建议批次**：极小（DelegateAutoMerge 单文件 + spec 1 锚），1-2 小时。

### P1-3 引擎进程生命周期吞掉新特性（部署面，非代码缺陷）

DelegateAutoMerge 落 main 于 **10-18 23:41**（f707cfdc4）；运行中引擎进程 10-18 ~22:00 启动（sbt run，stand.log 连续覆盖今日 00:35→16:30）**未重启** → 今日全部终态（00:35-16:21）跑在旧字节码上。实证：
- `~/.nebflow/chains/` 150+ 文件行种普查：terminal-verdict 198、delegate-spawn 171、merge-queue 88、worktree-cleanup 69、unmerged-work 1、**auto-merge 0**；
- stand.log/nebflow.log 零条 `nebflow.core.delegate.auto-merge` 日志；
- 而今日至少 2 个「带锚 × completed 判词 × 非合并轮」的完美探针会话（#557 00ef554a 10:44、#558 c830b62d 10:51，report=finish）本应各产至少一行 auto-merge 审计行——零行。
- DelegateAutoMerge.class 已于今日 08:39 编译进 target（sbt compile/test 跑过），但运行进程从未加载。

**修法**：特性落地后重启引擎再放量（作者动作：今晚 22:00 后的进程疑似已带新码——**待首个带锚终态会话落一行 auto-merge 行验证**）；中期在健康面暴露「运行构建 git head」。
**建议批次**：运维动作 1 次 + 观测点 1 个。

### P2-1 引擎原生 auto-merge 落地后零清理 → worktree/分支只增不减

清理面消费点仅三处：MergeQueue 轮 completed（DelegateTerminal.scala:254）、外部轮（ExternalSessionRunner.scala:531）、boot sweep（MergeQueueBootSweep.scala:171）。**DelegateAutoMerge.runMerge 的 merged 分支（DelegateAutoMerge.scala:235-257）不调 PostMergeCleanup** —— 每次引擎代并都新增一个「分支已并、worktree 永留」的残留。

**现状量级（/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/，今日 16:30 读数）**：
- 31 目录共 **6.8G**：28 个 `delegate-*` + 2 个 `external-codex-*` + brand-switch-console（手工）+ evidence；
- **28 条 delegate/external 分支全部 ahead=0（工作已全数进 main）**，26 个 worktree 100% 可回收（约 5.3G）；最大单目录 584M（017653fb）；
- 6 个脏工作树（189e295a 12 个脏文件、aab5503d 190 个、cd833b36 32 个…）——PostMergeCleanup 的非 force `git worktree remove` 对这些天然拒收，需要单独的挂起-人工面；
- 既有 69 条 worktree-cleanup 行 vs 88 条 merge-queue 行——清理覆盖率本就不全（非队列完成永不清理 + 脏拒收 + 债前存量）。

**修法**：① merged 验收通过后挂 `PostMergeCleanup.run(record)`（守卫已内置零领先+绝不 force，直接复用）；② boot 时加一条比 MergeQueueBootSweep 更宽的 reconciliation：扫「branch ahead=0 ∧ 记录非 live」的残留（覆盖非队列路径与债前存量），逐条走同守卫清理。
**建议批次**：① 小（1 文件 + spec）；② 中（新 sweep + dry-run 面），分两批。

### P2-2 双合并机制对同一基工作区并发无互斥

DelegateAutoMerge 的 per-projectKey FIFO（DelegateAutoMerge.scala:339-400）与 MergeQueue 单飞行窗**各自串行、彼此零引用**（DelegateAutoMerge.scala:44-46 自述）。MergeQueue 轮由 delegate 在**锁外**执行 `git -C <baseWs> merge`（MergeQueue.scala:298-307、370-378），与 auto-merge 的 evaluate（dirty 检查 + `git merge`，DelegateAutoMerge.scala:208-223、235）可同时写同一基工作区 → git index.lock 撞车 → 输的一方走「git refused the merge (no conflict markers)」**误挂起 refused**（DelegateAutoMerge.scala:281-284），候裁面从此多一条假案。
**修法**：两机制共用同一把 per-projectKey 互斥（auto-merge 的 projectKey 已与 MergeQueue.Entry.projectKey 同源 `GitWorktrees.projectKeyOf`；最小改法 = auto-merge evaluate 前探 MergeQueue 在飞窗，在飞 ⇒ refused-skip 带原因「merge round in flight」并**不落挂起**）。
**建议批次**：小（1 文件 + spec 1 锚）。

### P2-3 冲突挂起/拒并挂起零再驱动、三承载面无聚合查询（#524 遗留候裁项）

conflict-pending（DelegateAutoMerge.scala:258-280）与 refused（207-212、281-284）只写三承载面：终态 payload 尾注 + TaskLedger note + ChainStore `auto-merge` 行。delegate 已终态，**没有任何机制在基工作区变干净/冲突解除后重评**；「候人/候智能裁决」的消费面不存在，发现挂起只能全扫 chains/*.json（无索引无查询 API）。今日 #550 合并窗小批本质就是在人肉补这个查询面（见附录案例①）。
另注：队列/挂起运行态为进程内（重启即消亡，DelegateAutoMerge.scala:46-47 自述）——重启后挂起状态只剩三承载面的静态行，无重放。
**修法**：最小 = 聚合只读面（`GET /api/auto-merge/pending`：conflict-pending/refused 行的索引视图）；进阶 = boot sweep 对挂起行做重评（dry-run 默认开）。
**建议批次**：中（读面 + REST + 面板卡），挂起重评可后置独立批。

### P3-1 worktree 分配 check-then-act 竞态（观察项）

`GitWorktrees.freeName`（GitWorktrees.scala:86-89，os.exists 探测）与 `createWorktree` 的 `git worktree add`（:129）之间无锁；并发同 base 名双 spawn 会同选一个候选，一方 add 失败 → DelegateWorkspace.prepare 回落 inPlaceBinding（DelegateWorkspace.scala:136-141，无 worktree 座位的降级形态）。delegate 腿 dirBase = `delegate-<agent>-<8hex>`（DelegateTool.scala:592），UUID-8 撞名概率可忽略；40 字符截断也不可及（25 字符）；legacy NodeEditTool 腿已退役（NodeEditTool.scala:922）。**结论：现网风险≈0，登记观察，不建议单开批次。**

### P3-2 盘面异常两例（卫生项）

- `.nebflow/worktrees/delegate-nebflow-e0b069db`：20K 残壳——`git worktree list` 已无此条目（admin 记录被清）但目录残留，目录内 `git -C` 落穿主仓库、`rev-parse --abbrev-ref` **误报 main**（任何以 currentBranch(cwd) 为据的读数在此类残壳上都会说谎——P1-1 的修法若选「终态回退读 cwd 分支」方案必须先排掉这类残壳）；
- `.nebflow/worktrees/delegate-nebflow-e0b069db-c`：非 freeName 命名法产物（`-2..-99` 之外的后缀），手工或 delegate 自建的旁路 worktree。
**修法**：PostMergeCleanup 移除成功后校验目录消失并清扫残壳；审计面列出非引擎命名的 worktree。
**建议批次**：极小，随 P2-1② 搭车。

### P3-3 判词腿大面积不可用放大本链故障面（关联项，修法不在本链）

今日 stand.log 11 条「terminal verdict unavailable on both legs」（00:35-16:07，含 #557/#558 终态时刻）——判词退化到自申报/引擎缺省。auto-merge 的触发判词（completed/ready-to-merge）在判词缺失时全靠自申报兜底，P1-1 的「自申报 merge 被降级」正是兜底路径上的断裂。判词腿两层的可用性另行立项。

---

## 案例附录 · 今日三例人工补并

**共同时间线背景**：引擎进程 10-18 ~22:00 启动、未再重启；DelegateAutoMerge 10-18 23:41 才落 main ⇒ 今日终态全部跑在无 auto-merge 的旧字节码上（P1-3）。但 **#557/#558 的交付滞留与二进制新鲜度无关**——续聊会话零锚（P1-1）在最新代码下同样静默丢失。

### 案例① #550 · delegate-nebflow-a064e802（08:36 spawn → 08:44 archived）
【合并窗小批 · 两支 FIFO 并入 main，零 push】。裁决读数：`delegate-nebflow-5dbbf668`（#544）= **幽灵分支**——分支尖 81a3be27a 即 main 历史旧点位，reflog 仅 "Created from HEAD" 从未前移，所称两笔商标调研提交在全仓 refs 中不存在；`delegate-nebflow-f7679358`（#542）= 已被在飞队列轮并入（d8a3d2aa6，双亲 573dfe9d2+70beba3a7）。**根因面**：合并状态无聚合查询面（P2-3），候裁只能人肉 spawn 小批核实；幽灵分支另暴露「ready-to-merge 判定对零提交分支的防空合并闸（DelegateTerminal.scala:185 ahead==0 降级）早于本分支终态」的既有保护未覆盖的账面混乱。

### 案例② #557 · delegate-nebflow-00ef554a（10:30 前后 spawn → 10:44 archived）· 迁移工具箱
- 原会话 `delegate-nebflow-75b0ba04`（#537，07:44 spawn，worktree+branch `delegate/delegate-nebflow-75b0ba04`）→ 08:11 终态 **blocked**（中断，交付 2 提交留分支）；
- Mail 续聊 `delegate-nebflow-dcdbba65`（title "continuation: …"）→ **branch/worktree/baseBranch/projectWorkspace 全 None**，cwd = 75b0ba04 的 worktree；完成 2 提交后**自申报 reportStatus=merge**（reportDetail 明列 fcc93f22d/f02240585 待并）→ 判词 ReadyToMerge 因 branch.isEmpty 降级 completed → 零并零注记零审计行；
- 作者人肉 spawn 合并小批 #557（00ef554a）执行 `git merge --no-ff delegate/delegate-nebflow-75b0ba04` → e86d464a2（10:42）。

### 案例③ #558 · delegate-nebflow-c830b62d（10:51 archived）· AskUser 附件实施批
- 原会话 `delegate-nebflow-017653fb`（#552，09:09 spawn）→ 09:56 终态 **blocked**（中断，9 提交留分支）；
- Mail 续聊 `delegate-nebflow-3ed38d01`（branch=None，cwd = 017653fb 的 worktree）→ 自申报 reportStatus=merge（明列 427e0ed1b/4b1a3933e/db1c17e96/6383cb… 9 提交待并）→ 同案例②降级链 → 零并；
- 人肉合并小批 #558 → 9821bb5a5（10:50）。

**对照（健康路径）**：今日其余 8 次合并全部走 MergeQueue 轮健康落地（35b31668 08:22-25、f7679358 08:26-35、7933af88 10:12-13、9ecb332f 13:19-20、external-zcode-26afe2a0 15:16-17〔外部腿经 channel 注入，同 id 续聊零锚问题〕、e0b069db 15:50-51、d8bdb31f 16:01-03、e33b815f 16:06-07、540b9857 16:19-21）——外部执行器腿**在** auto-merge 射程内（ExternalSessionRunner.scala:136 同走 prepare 拿锚；settle:553 同钩子；外部续聊为同 id resume turn、记录锚不丢），锚缺失时还有显式 skip 尾注（:556）。

---

## 修复顺序建议

1. **P1-1 + P1-2 同批**（锚继承 + 跳过审计化）——根治今日两例，且让失效从此可观测；spec 判红锚：续聊会话携提交完成 ⇒ auto-merge 评原分支并落行。
2. **P1-3**：重启引擎，用首个带锚终态验证 auto-merge 首行落账。
3. **P2-2 同批搭 P2-1①**（互斥 + merged 后清理）。
4. **P2-1② + P3-2**（reconciliation sweep + 残壳清扫，清 6.8G 债）。
5. **P2-3**（挂起聚合读面 + 重评）独立批。
