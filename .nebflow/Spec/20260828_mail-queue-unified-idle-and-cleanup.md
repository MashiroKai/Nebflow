# Mail queue 空闲判定统一 + 卡住/僵尸邮件清理（2026-08-28 01:00 裁定）

- 分支：`feat/mail-queue-unified-idle` @ 402e43b4 + ea9efadf（基线 bcaedcab，worktree `~/Claude code/.nb-worktrees/nb-mailqueue-unified`）——待审合并，不部署（攒重启包）
- 定义层：~/.nebflow @ 1aa9c15（20260825_mail-queue-idle-delivery.md 修订）

## ① 空闲判定统一（裁定实施）

**裁定**：废除 08-25「按发送者区分」（Nebula→Manager 等全 team 停 vs team 内等目标单独停），统一为**无论发送者是谁，投递时机 = 目标 agent 自身+子树全空闲**。

**改动**：
- `MailIdleGate.isTeamTreeIdle` 删除（连同其存在理由——sender 区分）
- `AgentActor.fullyIdle` 签名去 `senderSession`，删 senderIsRoot/teamOfSession/sessionIdsOf 分支，统一 `isAgentTreeIdle && internalIdle`
- Q3（RunningFlow.sessionId 关联）保留不动；turn-end drain 的 skipSelfStatus 语义不变
- 设计文档 20260825_mail-queue-idle-delivery.md 头部加 08-28 统一裁定注记（~/.nebflow @ 1aa9c15）

**Spec**：
- MailIdleGateSpec 15（删 AC-5 段 6 用例随函数退役，AC-4 矩阵完整保留）
- MailIdleGateWiringSpec 9：**AC-6 翻转**——「root→Manager queue mail delivers by Manager's own subtree — sibling member busy no longer blocks」（member 子树忙时 Nebula→Manager 照常投递、队列清空）
- 变异验红：fullyIdle 无条件附加全 team 检查（team-wide 语义重现）→ **AC-6/AC-7b 恰好红**（20.5s waitUntil 超时=兄弟忙拦截重现），AC-7（target 自身忙拦截）不受影响仍绿——变异精确钉住被移除的语义；恢复后 24/24 绿
- 全量：见下

**过程瑕疵坦白**：变异脚本两次异常中断（锚点子串 count / 漏注释行），python 非原子写入致 MailIdleGate.scala 残留函数定义混入首 commit（402e43b4），以修正 commit ea9efadf 清除。教训：变异脚本对多文件应先整体写临时副本或 finally 恢复；commit 前对「应删函数」跑 grep 终检。

## ② 卡住邮件根因（73527280「好友功能就绪度评估」）

**根因一句话**：Manager 子树含正在执行 Nebula 派发任务的成员（Backend/Frontend 常态活跃），目标子树永不空闲 → `mail-queued-deferred ... subtree-busy, queue retained` 于 01:01–01:31 五次循环（宿主日志实证），内容过时（好友评估已人工完成）作废。

**日志证据**：`subagent-Manager nebflow-project/Manager/73527280 event=mail-queued-deferred detail=head=mail-q-ab8ae201 sender=5cc7590a subtree-busy, queue retained`（01:01:48 / 01:02:24 / 01:12:37 / 01:25:08 / 01:31:10）。

**结构性观察（供后续裁定参考）**：08-28 统一裁定收窄了「全 team 停」→「目标自身+子树」，但 Manager 的子树≈其成员集合，Nebula 常态并行派任务给成员 → Nebula→Manager 的 queue 邮件在「目标=Manager」时仍可能长期 deferred。这不是回归而是 Manager 角色的天然属性；长期解法方向 = 队列内容的时效性管理（TTL/过期作废），本例按裁定直接作废清理。

## ③ 僵尸队列清理（771d76fc ×3 封）

**确认**：771d76fc 在宿主日志零活跃痕迹（仅有的 5 条命中全是本次排查命令本身）；目录无 session.json，只有 flows.json（9 个 team 挂载索引）——是**旧 Nebula root 会话目录**，实例重启/会话更迭后 Nebula sessionId 变化，投递目标失联 → 3 封 Manager RESULT（slideblocks 试点收尾 / SiPM 批次重发 / deck v6.2 第三批）永不可投。

**清理方式安全性核实**：MailQueueStore 为**纯磁盘读写**（load 每次 os.read+decode，无内存缓存/Ref）——直接改盘不存在内存态重写覆盖；且目标会话无 actor，零并发竞态。

**执行**：备份至 `~/.nebflow/backups/mail-queue-cleanup-20260828/`（771d76fc.mail-queue.json 9114B + 73527280.mail-queue.json 1118B）→ 删除两个 mail-queue.json。收件内容本就经文件通道/项目 git 到达，非丢消息。

**产品观察（同供后续）**：queue 目标会话 id 与 agent 生命周期解耦（agent 换会话 id 后旧队列失联成僵尸）——「queue 条目带 agent 名 + 失联检测/迁移」是潜在改进项，未立项。

## 验证汇总

| 项 | 结果 |
|---|---|
| MailIdleGateSpec + MailIdleGateWiringSpec | 24/24（修复态） |
| 变异验红 | team-wide 重现 → AC-6/AC-7b 红（正交），恢复绿 |
| 全量 sbt test | 定稿态数字见交付回报（SBT_OPTS=-Xmx3g） |
| ② 根因 | 日志五次 deferred 循环实证 |
| ③ 清理 | 3+1 封全清（备份留存），僵尸条目清零 |
