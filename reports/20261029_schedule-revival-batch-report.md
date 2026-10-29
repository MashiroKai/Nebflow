# Schedule 双面化+主动性六条落地批 REPORT（#822 复活批，2026-10-29）

批：作者令 2026-10-29 12:00「搜一下历史 检查一下我们对定时工具的优化要求。判断现状和计划的差距，然后派发实施落实」。
权威原令 = 作者 2026-10-24 15:5x（双面化 15:49 + 主动性六条 15:5x，**本次考古自会话存档逐字找回全文**，见 §2）。
分支：`delegate/delegate-nebflow-cd4d977f`。考古残稿来源 = 原三连会话（bd7a0f1c→57cb9529→447d7317）停机落盘 commit `494d59611`（main 与其父提交在这 8 个文件上零漂移，cherry-pick 干净捡回）。

## 1. 交付清单

| 层 | 内容 |
|---|---|
| 待办面（双面化） | `kind` 字段（task/todo）；待办引擎侧同款存储（抗压缩/重启）；每日 09:00 汇总 digest 注入一次（ExternalEvent 通道：忙时排队闲时直投）；`done` 销账 / `cancel` 销账后永不复浮；list 双节段；system reminder 待办栏；upsert 按 kind 圈定互不覆盖 |
| 错过补偿 | 重复任务：补射恰一次（带「补跑：错过 N 个完整周期」标注）+ next 快进未来槽位（消灭宕机多日连环补发风暴）；一次性任务：`triggerAt < bootAt` ⇒「错过后补跑」标注；正常晚点零变化（回归 spec 钉死） |
| 调度丢失 bug | 根因见 §3；修复 = store 全库 Mutex 串行（所有读写过锁，upsert 清理半步+追加同 permit 原子）；spec 钉「创建后可见、重启后仍在」+ 三组并发用例 |
| 说法加宽 | am/pm、noon/midnight、星期（中英）、中文日期时段（「明天下午3点」「晚上11点半」「大后天」等）；解析失败报错带可接受写法示例 |
| 例程自检降频 | 连续 3 次触发窗口内会话零工具动作 ⇒ repeat 阶梯降级（hourly→daily→weekly，weekly 到底只挂标）+ 挂「待审查」（list 可见，粘性）；有活动清零；无证据（重启冷启动）不奖不罚；同名 re-arm 恢复（upsert 换新归零） |
| 条件触发 | 评估器接口 `TaskConditionEvaluator`（前缀分发）+ 三实现：`git-tip:` 有状态哨兵（建基线→变化才叫，基线随任务持久化）、`task-terminal:` 流程图终态哨兵（读 flow-map.json，NodeLifecycle.Terminal 四值）、裸 `sh -c` 探针兜底（60s 超时=未满足）；未满足静默顺延（一次性 30min 复查 24h 观察窗转 PAUSED）；端到端 spec 两条（git-tip 三拍、task-terminal 两拍） |
| ⑤触发链上下文 | 例行任务再次触发自动附「上一轮结果摘要」（会话窗口内第一条 Ai 行掐头 160 字，取不到零打扰）——**按考古原文逐字落地** |
| ⑥同窗合并注入 | 同一窗口同会话多触发合并为一次 ExternalEvent（eventType=trigger-batch）；单任务组逐字走旧路径；逐任务落账/删除/面板广播不合并——**按考古原文逐字落地** |

## 2. 考古结论（六条原令逐字找回 + 残稿判定）

- **原文找回**：作者 2026-10-24 15:5x 六条原令全文存于 `.stand/sessions/delegate-nebflow-bd7a0f1c.ui.json`（15:5x 追加需求帧）。六条 = ①条件触发 ②错过补偿 ③例程自检降频 ④自然语言解析增强 ⑤触发链上下文 ⑥同窗合并注入；红线 = 空闲安静；优先级 1>2>4>3>5>6。本 brief 所称「第 4-6 条原文未完整留存」已被考古推翻——**⑤⑥原文完整，按原文落地**（无自拟项）。
- **残稿判定（494d59611，10-24 停机落盘，从未跑绿）**：
  - **可复用（捡回）**：双面化全套（model/store/actor/tool/reminders/gateway 五文件 + TodoScheduleSpec 473 行 + ScheduleInitiativeSpec 335 行）、错过补偿（重复任务面）、条件探针（sh -c 形态）、说法加宽全套、待办 digest。捡回后修三处：
    - **spec 笔误 ×2**（残稿从未执行过：`take(5)` 对 3 字符恒红；第二次 `mkCtx()` 清空广播缓冲导致销账广播断言恒红）——勘误注记留在 spec 内；
    - **codec 坑**（见 §4，捡回即红，本批实修）。
  - **需重做/新增（残稿没有）**：丢失 bug 修复、条件评估器接口+两类哨兵实现、一次性错过标注、例程自检降频（残稿 spec 头明写「③自检降频…本批不做」）、⑤⑥（残稿明写「本批不做」）、`SessionToolActivity` 活动时钟。
- 残稿分支其余 WIP commit（7aba8a2da 等）属 BgTaskLedger/RemoteExecutor 另一批，与本批无关，未触碰。

## 3. 调度丢失 bug 根因（f2ef6ff1，2026-10-29 11:49 实测）

- **现场证据链**：`stand.log` 11:50:21.073 **同一毫秒**两条 `Schedule(create)` 落同一会话文件（`d7c3cb54….json`）：term-batch3-gate（线程 io-compute-blocker-18，即 f2ef6ff1）与 term-batch2-gate（线程 io-compute-blocker-138）。事后文件里 batch2 在、f2ef6ff1 无（仅存于 batch2 的重挂文案里）；Nebula 11:58 同名重挂的自证文案「原 f2ef6ff1 创建回执成功但 list 消失」。
- **根因**：`ScheduledTaskStore` 所有操作都是**无锁读-改-写整文件覆盖**。同会话文件上的两个并发 create：后写者持**旧读数**写回 ⇒ 先落任务被静默抹掉（经典 last-writer-wins）。读-写竞争（半截 JSON）还会造成瞬时漏触发。
- **修复**：单一 `cats.effect.std.Mutex` 进程内全库串行；单会话读-改-写走 `modifySession`；跨文件扫描同 permit；`upsertTaskByName` 的清理半步+追加同 permit 原子。Mutex 不可重入 ⇒ 公开方法只调 `*Unlocked` 内部件（类注钉死禁令）。
- **spec 钉死**：24 路并发 create 全存活；并发 create+markTriggered+reschedule 零丢失；同名并发 upsert 收敛单份；「创建后可见、重启后仍在」双断言。

## 4. 捡回即红的 codec 坑（本批实修，防复发）

- 残稿给 `ScheduledTask` 新增非 Option 字段但沿用 `deriveCodec`（generic.semiauto）：circe 只对 **Option 字段** 容忍缺键，非 Option 缺键 = 解码硬失败 ⇒ **整个会话任务文件解码为 Nil ⇒ 存量任务静默全部消失**（与丢失 bug 同症状、更致命）。spec 实测红：`DecodingFailure at [0].idleStreak: Missing required field`。
- 修复 = `Configuration.default.withDefaults` + `ConfiguredCodec.derived`（库内先例：TaskListEntry/NotifyConfig）——缺键一律回构造器默认，老文件零迁移；legacy 无新字段 spec 钉死。
- ⚠️ 后续批给本模型加**任何**字段都无需再操心缺键（withDefaults 全字段兜底），但**禁止换回 deriveCodec**（模型 companion 注释已钉）。

## 5. spec 读数

| 面 | 读数 |
|---|---|
| 调度族（ScheduleRevivalSpec 新增 17 + TodoScheduleSpec 14 + ScheduleInitiativeSpec 13 + 既有族回归） | **46/46 绿**（`Test/testOnly nebflow.core.scheduler.*`） |
| 既有调度工具族回归（ScheduleToolSpec 17 + ScheduleActionsSpec 19 + FreezeScheduleSpec 59 + ScheduleV2Spec 12 + SystemRemindersSpec 11） | **118/118 绿** |
| 合计 | **164/164 绿** |

新增断言覆盖：并发语义×4、一次性错过标注×2（正反例）、评估器分发+git-tip 端到端（建基线→不触发→tip 变化→触发+基线推进）、task-terminal 端到端（running 等待→completed 触发）、降频×5（阈值触发/活动清零/无证据保持/粘性/weekly 到底）、legacy 缺键解码、⑤摘要（取窗/掐头/无回复零打扰）、⑥合并（同会话合并×1、跨会话不合并）。

## 6. 重启后生效面（在产零触碰）

本批为纯代码改动，在产实例（旧 jar）行为零变化；作者下次重启后一次性吃到：

1. **立即修复面**：调度创建不再互吞（store 互斥）；重启跨期不再连环补发（快进+补跑标注）；存量任务文件零迁移照读（withDefaults）。
2. **新能力面**：待办（`kind:"todo"` + 每日 09:00 digest + `done` 销账）；条件触发三种写法（`git-tip:` / `task-terminal:` / 裸探针）；说法加宽全套；例程空转自动降频+「待审查」（计数自部署起积累，3 个空转周期后才触发）；⑤上一轮结果摘要；⑥同窗合并。
3. **工具描述文本**已同步（工具面新 schema/文案随新 jar 加载）。

## 7. 遗留与边界（如实回贴）

- 条件触发本批落了骨架+两类真实哨兵+探针兜底（含端到端 spec）；更多条件类型（如目录新文件监视、CI 专用探针库）未做，评估器接口已为其留位。
- 降频判据是**诚实启发式**（会话窗口内零工具动作=空转）：纯文本回复不算活动（宁松勿冤——漏判比误判代价低）；「待审查」粘性设计保证误判可见可恢复。
- ⑤摘要取「窗口内第一条 Ai 行」：若作者在两次触发间手聊过天，摘要可能取到的是手聊回复——启发式边界，spec 注明。
- 原六条之外的 #822 遗留：无（本批已清空该批全部六条）。
