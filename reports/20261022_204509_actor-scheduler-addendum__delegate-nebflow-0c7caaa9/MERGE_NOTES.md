# 并入建议（MERGE NOTES）· Actor 模型与调度器实现补充批

> 本目录 = `ADDITION.md`（拟并入章节全文，编号 §7）+ `topology-actor.dot/svg`（监督层级图）+ 本文件（并入操作单）。
> 目标稿：`reports/20261022_194019_multi-device-compute-design__delegate-nebflow-1c1590ea/` 下的 `MULTI_DEVICE_ARCH_SPEC.md`（下称 SPEC）与 `MULTI_DEVICE_ARCH_SUMMARY.md`（下称 SUMMARY），已合入 main（merge f7105c7f2 前身 284cd6f5 线）。

---

## 1. 改动总览（5 处编辑 + 0 处重编号）

| # | 文件 | 位置（并入时以该稿实际行号为准） | 操作 |
|---|---|---|---|
| 1 | SPEC | §6 之后（现稿止于 §6.2 真实命令示例） | **追加** `ADDITION.md` 全文为新 §7「Actor 模型与调度器实现」——现稿 §1-§6 编号零改动 |
| 2 | SPEC | §3.3 代码块内 `trait ComputeScheduler {` 上方 | 增一行注释：`// 实现形态 = 网关进程内 actor 拓扑（每设备一枚 DeviceActor，见 §7）；trait 签名即对外门面，不因实现形态改动` |
| 3 | SPEC | §4 目录结构行 `src/main/scala/nebflow/compute/      # C2：Scheduler.scala / Capability.scala / Lease.scala / Queue.scala` | 改为 `# C2：Scheduler.scala / DeviceActor.scala / Capability.scala / Lease.scala / Queue.scala`（§7.5） |
| 4 | SPEC | §5-C2 清单（「`ComputeScheduler`（§3.3 全部接口）…」条后） | 增一条：`- [ ] DeviceActor 拓扑落地（§7.5 类型草图 + 实施纪律三条：不装 BackoffSupervisor 本体 / forkTurn 错误自投 JobLost / 名字空间 compute-device-<id> 住既有 ActorSystem）` |
| 5 | SUMMARY | §9 边界表末尾 | 增一行：`| **nebflow.actor 自建 actor 框架（nebflow/actor/，12 文件）** | **复用（调度器实现基底）** | actor 树只在网关进程内；设备是腿不是 actor 节点；不打通 ActorSystem.resolve 远程路径（ ActorSystem.scala:94-96 现为显式 NotImplementedError），不引 pekko/akka 依赖（Dependencies.scala:48-50 为死配置） |` |

可选（不强制，最小 diff 优先）：
- SUMMARY §3 现状盘点表可增第 10 行原语「进程内 actor 框架（`nebflow.actor`：Queue+Fiber+Ref 信箱/死亡观察/TTL，`ActorSystem.scala:18-33`）」，锚点列填 `src/main/scala/nebflow/actor/*`——若增，§9 行与 §7 章呼应更顺。
- SUMMARY §1 末尾架构图句后可补一句「调度器实现形态的监督层级图：actor 补充批 `topology-actor.svg`」。

## 2. 图件

- `topology-actor.dot` / `topology-actor.svg`：两级监督层级图（第一级=进程内 crash→退避重建；第二级=租约 TTL 判死→重派）。并入时**建议随 §7 一并拷入设计稿目录**（与既有 `topology.dot/svg` 命名并列为 `topology-actor.*`），或保留路径引用本报告目录，二选一由并入库执行时定。
- ADDITION.md §7.3.2 内嵌 mermaid 图与 svg 同构，mermaid 供面板渲染、svg 为定稿图。

## 3. 明确不并入（负清单）

| 不并入 | 理由 |
|---|---|
| 任何 pekko/akka/ActorFoundry 依赖 | §7.1.2 证据链：死配置+迁出史+自建框架在产；引依赖逆仓库轨迹且违反禁双实现（upd:86） |
| `ActorSystem.resolve` 远程化 / ActorPath device 段消费 | 属新建跨设备消息通道，违反 ADR-1（复用 `device:` 双腿）；登记为「不做」，不预留接缝 |
| 信箱改有界（O3） | C2 结构上不需要（任务体不进信箱）；框架扩展仅登记 |
| 通用监督器提取（O4） | 跨批重构；C2 只复用 BackoffSupervisor 习语（数十行 ComputeSupervisor） |
| 改 SPEC §3.3 trait 签名 / §3.4 安全闸 | DeviceActor 拓扑是实现载体；门面与安全闸零改动 |

## 4. 与上游批次的呼应关系（供评审对照）

| 本稿论断 | 上游依据 |
|---|---|
| 用既有原语不引框架（§7.0/7.2） | triple-star REPORT §4-不采纳#3「主线 ActorSystem 留在进程内…无需新范式」（REPORT.md:174）；本批新增仓内证据 §7.1.1-7.1.2 |
| 租约 TTL=180s=3×心跳 与 K 拍判死互注（§7.3.2） | triple-star REPORT §A-3（REPORT.md:78）；主线自有先例 NeblinkPresenceService 45s 拍 3 拍剔除（:163） |
| claimEligible 纯谓词+准入预演（§7.5 trait 关系段） | triple-star REPORT §A-1 govd 模式（REPORT.md:76），SPEC §3.3/§3.4 既有 |
| 设备=腿非 actor 节点（§7.2-4） | DeviceDelegate 对端建自己的本地会话先例（`DeviceDelegate.scala:33-40`）；ADR-1 |
| 两级监督与「死/卡死」双机制（§7.3.2） | BackoffSupervisor（Terminated 轴）+TaskStuckWatcher（无进展轴）既有互补结构；ADR-6 零超时纪律 |

## 5. 证据基线

- 全部 `src/main/scala` 锚点为本批亲验（Grep/Read 直读行号，worktree `delegate-nebflow-0c7caaa9`，基线 = main merge f7105c7f2）。行号漂移时以符号名检索：`trait ActorSystem` / `Queue.unbounded[IO, Msg]` / `Remote actor resolution not yet implemented` / `Mirrors Pekko's BackoffSupervisor` / `IO-based sync loop replaces the old Pekko actor` / `spawnAgentActor` / `BackoffSupervisor 只在 Terminated`。
- 零代码改动、零构建跑动（graphviz 仅用于渲染本报告图件）。
