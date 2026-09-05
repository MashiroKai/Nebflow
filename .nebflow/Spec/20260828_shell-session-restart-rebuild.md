# Shell 会话 restart 后重建修复（2026-08-28）

**分支**：`fix/restart-shell-session-rebuild` @ `562c827a`（worktree `~/Claude code/.nb-worktrees/nb-shell-restore`，基线 main `50f16ac4`）
**状态**：已交 qa-backend，未合并（勿自并）

## 症状

Team member restart（AgentControl restart / Stop 链路）恢复后，member 的 Bash 工具调用持续抛 `"Session has been destroyed"`，永不自愈——slideblocks Frontend restart 后无法跑任何命令、收尾卡死的根因。重启恢复家族第三案例（loadMessages ✅ / flow 重放 △ / shell ❌）。

## 根因

`killSessionProcesses`（`shell.scala` ~1093，#391 机制 E：restart/Stop 时杀会话全部 OS 进程树）只做两件事：`s.killActiveProcesses() *> s.kill()`——**杀进程 + `isAlive.set(false)`，但会话滞留 `ShellSession` 注册表 map**（只有 `destroySession` 才移除）。

恢复后的首个 Bash 调用链：`BashTool → ShellSession.forSession(sid) → doGetOrCreate` 命中 `case Some(死)` → `isStale=false`（TTL 30min 未到）→ `s.touch.as(s)` 原样返回死会话 → `execute` 内 `checkAlive` 抛 `IllegalStateException("Session has been destroyed")`。死会话永远占位，无法自愈。

## 修复（双层，同 commit）

- **A（根因）**：`killSessionProcesses` 改 `sessions.modify` 在杀进程的同时把会话移出注册表（与 `destroySession` 对称）——恢复路径走 get-or-create 的 `None` 分支惰性重建新会话。
- **B（防御）**：`doGetOrCreate` 命中滞留死会话时替换重建（`replace(old)` helper + `isDead` 探针）——覆盖任意路径（不只 restart）留下的死会话。`isDead` 为 `private[tools]`，只暴露给同包注册表路径。

两层纵深重叠：只回退 A 时 rs-a 仍被 B 救活（变异验红 M2 证明：A+B 同时回退（原始 bug 形态）rs-a+rs-b 红）。

## 调试过程教训（重要，可复用）

spec 首轮 4/5 红且观测「反直觉」：fresh create 读 `isDead=true`、kill 后读 `false`——疑似「新建=死、kill=活」的极性反转。排查走弯路至增量编译/影子类/类加载假设（javap 三处字节码全部正确），**真相是一行自伤 bug**：`isDead` 初版实现 `isAlive.get` 忘了 `.map(!_)`，返回的是「活」而非「死」。该假设下全部观测 100% 自洽，且 rs-c（正常复用回归）抓到了真实危害——编译态 fix B 会把活会话当死会话替换。

教训：**观测「不可能」时，先怀疑自己新写的 helper 的语义极性，再怀疑环境**。DIAG 数据（fresh=true/killed=false 的完美确定性）从一开始就在指向极性错误，而非随机性环境问题。探针方法论（loaded-from + execute-after-isDead 对比）有效——`isDead=true` 与 `execute` 成功同对象共存，直接锁死「isDead 与 checkAlive 语义不一致」。

## 验证

- **Spec**：`ShellSessionRestartSpec` 5 用例（每用例唯一 sid 防 object 单例跨测试污染）：restart 恢复主链路（killSessionProcesses → forSession 新会话 → 真实 Bash `echo restart-ok`）/ `None`+未知 id 幂等 / 滞留死会话自愈（`kill()` 不出 map）/ 正常复用回归（同实例）/ `destroySession` 语义回归。干净态 5/5 绿。
- **变异验红**（`/tmp/nb-shell-mutation.log`）：
  - M1 只回退 B（保 A）→ rs-b 红、其余绿——B 对滞留路径独立载重；
  - M2 回退 A+B（=原始 bug 形态）→ rs-a+rs-b 红、rs-c/rs-d 绿——spec 抓得住原始根因。
- **全量**：`sbt test` 定稿态（见交付 Mail 数字）。
