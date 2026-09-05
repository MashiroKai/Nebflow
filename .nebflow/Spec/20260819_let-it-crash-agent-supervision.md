# Agent 监管架构：Let It Crash（#22 排查的架构指导思想）

> 用户裁定（2026-08-19 21:10）：「要用 actor 的 let it crash 思想来管理 agent」
> 状态：架构方向 frozen · 待 #22 排查吸收落地
> 背景：今天 8 次同款 turn 挂起静默死亡（Frontend×3 / Manager×2 / Backend×1 / 夜间×2）

---

## 1. 现状的根本矛盾

今天的循环：**turn 挂起 → agent 半死不活（不崩溃也不工作）→ 手动唤醒（immediate 排队进不去，因为 turn 永不结束）→ 偶尔救活又卡 → 用户重启**。

三重救援死角（日志实证）：
1. TaskStuckWatcher 检测到 team agent stuck（601s）但**只发 read-only notice**——政策「Team agents are never auto-stopped」
2. watcher 提示「user/Nebula can restart via AgentControl」——但 **AgentControl 对 team 恰好只读**，指的路走不通
3. immediate Mail 是 turn 边界注入——**turn 挂着就永远到不了边界**（20:53:28 日志：`immediate-input-queued` 排队后再无消费）

结论：出错的 turn 被系统性地「保全」着，而保全一个挂起的 turn 比崩溃重启代价更高——它锁死了 agent、锁死了救援通道、烧掉了用户等待时间。

## 2. Let It Crash 思想的应用

Actor 模型（Erlang/Akka 传统）的监督哲学：
- **不修复脏状态，替换它**：出错的组件崩溃，supervisor 按策略重启，从干净状态恢复
- **关键状态外置**：状态持久化在 actor 之外，崩溃不丢
- **崩溃是常态**：快速崩溃 + 快速恢复 > 慢性挂起

映射到 Nebflow：
| Actor 传统 | Nebflow 现状 | 缺口 |
|---|---|---|
| 挂起检测 → 崩溃 | watcher 检测到但只通知 | **政策改：team agent stuck 超时 → 主动 restart** |
| supervisor 重启 | BackoffSupervisor + AgentControl.restart 已有（#317 loadMessages 断点续跑） | **watcher 与 restart 的联动**——检测到 stuck 直接触发，不等人工 |
| 状态外置 | 会话持久化（sessions/*.json）、任务持久化（tasks/）、AtomicJson 已全 | 无缺口 |
| 重启退避 | BackoffSupervisor 已有退避 | 复用 |

## 3. 落地改造清单（#22 P4 吸收）

1. **watcher → restart 联动**：`TaskStuckWatcher` 检测 team agent `Processing` 态 > 阈值（10min）时，从「read-only notice」升级为「触发 AgentControl.restart 语义」——kill 挂起的 actor → BackoffSupervisor 用 loadMessages 断点恢复（"continue from where you left off"）
2. **重启保护**：连续重启退避（已有 BackoffSupervisor 机制）；同一 agent 1 小时内 restart ≥3 次 → 停止自动重启、升级 Nebula/用户（避免重启风暴）
3. **turn 挂起检测补强**：今天 8 次卡死的精确症状是「工具结果返回 → 下一轮 LLM 未发起」——turn 的 lastActivityMs 在工具结果后停止更新但 agent 仍 Processing。watcher 的 10min 阈值已能覆盖，关键是第 1 条的处置动作
4. **测试 fixture 隔离**（取证发现）：Backend 跑 TaskStuckWatcherSpec 时测试假 agent（loop-stuck 2s / root-stuck / team-long 3000s）泄漏进生产 watcher 观测面——测试 registry/watcher 必须用隔离实例，不污染生产 ActorSystem 状态（20:36 日志实证）
5. **immediate 队列的 turn 挂起旁路**：immediate-input-queued 在 turn 挂起时永不消费——restart 后 loadMessages 恢复时把 pendingImmediateInputs 保留（已有语义）或转投新 turn
6. **AgentControl team 只读政策放宽**：watcher 联动 restart 后，AgentControl 对 team 的手动 restart 也可开放给 Nebula（保留 Manager 仲裁：Manager 正在用该成员时除外）

## 4. 今天的取证证据链（供排查复现）

| 证据 | 日志位置 | 说明 |
|---|---|---|
| Frontend turn 挂起 | 会话 aac1c33a 12:33 后零写入 | 工具结果返回→LLM 下一轮未发起 |
| Manager 卡死×2 | 会话 73527280 14:42 / 20:59 后 | 同款 |
| Backend 卡死 | 会话 676780c5 20:35 后 | 在写 #22 测试时自己卡死 |
| immediate 排队不消费 | nebflow.log 20:53:28 `immediate-input-queued textLen=1169` | turn 挂着 immediate 进不去 |
| watcher 只通知不处置 | 20:35:41 `read-only notice (Team agents are never auto-stopped)` | 政策死角 |
| AgentControl 提示矛盾 | 同上日志「can restart via AgentControl」 | AgentControl team 只读 |
| 测试假 agent 泄漏 | 20:36:35 `loop-stuck stuck for 2s` ×8、`team-long 3000s` | TaskStuckWatcherSpec fixture 进生产 watcher |

## 5. 验收方向

- 注入 turn 挂起（模拟工具结果后不续跑）→ 10min 内 watcher 触发 restart → agent 从断点恢复继续任务 → 会话无丢失
- 重启风暴保护：3 次/小时上限
- 测试运行后生产 watcher 观测面零假 agent
- immediate 在目标 restart 后被消费（不丢）
