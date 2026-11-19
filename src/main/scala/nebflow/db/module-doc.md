# db

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

嵌入式数据层：五步写 / 三态读 / 分片 / 保留策略。

**入口建议**：先读 [`DbCore.scala`](DbCore.scala)（本模块最大文件）。

## 文件（12）

| 文件 | 说明 |
|---|---|
| [`DbCore.scala`](DbCore.scala) | db 门面（设计 §3.1/§3.2，D-2/D-3 采纳）：**进程内嵌入式库**——一个 Scala 包 + 数据根 下文件族；对外 API 全走 trait（[[DbWriteFace]]/[[DbReadFace |
| [`DbError.scala`](DbError.scala) | db 错误面（结构化错误码；IO 的 error channel 用 —— 调用方按码处置，不解析文案）。 与三态读的分工：ReadResult.Corrupt 是**读面的可表示态**；这里是被拒动作的**错误**。 |
| [`DbHost.scala`](DbHost.scala) | 宿主侧共享访问点（接线第一阶，设计 §3.9「一个数据根一个句柄」的进程内落地）： 按数据根 memoize 的 [[DbHandle]] 注册表。 |
| [`Locking.scala`](Locking.scala) | 跨进程实例锁 + 世代感知（设计 §3.9 第 3 层，D-7 采纳）。 - `INSTANCE.lock`：`FileChannel.tryLock` 独占；拿不到 ⇒ 拒启（第二实例/他进程持锁）。 |
| [`Quarantine.scala`](Quarantine.scala) | 损坏隔离（设计 §3.10）：`.corrupt-<ts>` 就地改名，全字节保留，**绝不删除**。 收编本仓 3 处隔离先例（SessionStore `.corrupted.<ts>`、TaskLedgerStor |
| [`ReadFace.scala`](ReadFace.scala) | 三态读面（设计 §3.7，D-5 采纳）：Absent / Corrupt / Present —— 「坏 ≠ 不存在」。 这是全仓 9 处静默空视图（设计 v1 §1.3：`getOrElse`-空值族把 Corrup |
| [`Recovery.scala`](Recovery.scala) | 恢复面（设计 §3.11，D-8/D-10 采纳）——纯函数规划腿（执行腿在 [[nebflow.db.DbHandle]]）。 boot 重水合时序（表步 1–6 的库侧职责；步 7 回执闸 = 现状已有面，接线期对象 |
| [`Retention.scala`](Retention.scala) | 阵隔与遗忘（设计 §4，D-9 采纳）——**纯函数面**（零 I/O、零时钟、零随机，可对拍）。 - **双轨**：逻辑标记先行（遗忘 = tombstone，可回滚）+ 物化删除另立一等显式动词 |
| [`RetentionSweeper.scala`](RetentionSweeper.scala) | 遗忘刀最小内嵌调度（D-9 接线期宿主定时器——轮 B 落位说明）： - **谁触发**：[[DbHost]].openBestEffort 成功开根 ⇒ arm（fiber 挂 cats 全局运行时）； |
| [`SeqCounter.scala`](SeqCounter.scala) | 全局单调计数器（设计 §3.6，D-6 采纳）：seq = 计数器文件，**绝不数行**。 - cap `agentd:4502-4506` 数行 ⇒ 并发重号/截断重号（R34）的反面：发号只出自计数器区间。 |
| [`Shards.scala`](Shards.scala) | 分片/轮转/索引（设计 §3.8）：`<base>.<n>.jsonl`（n 从 1）按字节切档； `events.idx` 记每片首尾 seq——**派生面**（坏 ⇒ 删了重建，三标签③）。 |
| [`WriteFace.scala`](WriteFace.scala) | 写面（设计 §3.5，D-4 采纳）：五步写序 + 追加腿 + 撕裂尾前置检 + orphan tmp 启动清扫。 自包含 durable 原语（零 nebflow 内部依赖；供体 triple `archive.rs: |

## 子模块（1）

- [`format/`](format/module-doc.md) — 数据层记录格式（编解码）。

## 依赖的兄弟模块

[`db/format/`](format/module-doc.md)
