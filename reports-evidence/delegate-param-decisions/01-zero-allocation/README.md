# 01 · 不填 `worktree` ⇒ 零分配（就地只读通道）

判据（作者令）：「不填 worktree ⇒ 零分配（就地跑）」——缺省**一棵树都不建**，座位就是项目工作区本身。

## 命令原文

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-dcdeaa46"
sbt -batch "testOnly nebflow.core.delegate.* nebflow.core.tools.DelegateToolSpec \
  nebflow.core.tools.DualModeDispatcherScopeSpec nebflow.agent.DualModeGrayReleaseSpec \
  nebflow.agent.DualModeToolFaceSpec nebflow.llm.decision.* nebflow.core.executor.ExternalLegPrepareSpec" \
  > reports-evidence/delegate-param-decisions/after-test.log 2>&1
```

## 原始输出（`raw.log`）

```
nebflow.core.delegate.DelegateWorkspaceWorktreeLegSpec:
  + W1 零分配：worktree 参数缺省 ⇒ 不建树，新建目录清单前后逐项相同 + brief/回执都写明未分配 0.128s
  + W2 按名建树：worktree="<name>" ⇒ <worktreeRoot>/<name> 且分支 delegate/<name>，回执写『精确 name/新建』 0.154s
  + W3 同名复用：同一 name 再派一次 ⇒ 复用既有树，不产生第二个目录 0.328s
  + W4 零模型调用：装计数判官面，跑完零分配腿 + 按名建树腿 + 模式解析后计数仍为 0 0.334s
  + W5 非 git 工作区：就地腿 = None（旧语义不回归） 0.017s
```

## 机械断言（W1，`DelegateWorkspaceWorktreeLegSpec`）

1. `<基工作区>/.nebflow/worktrees/` 的目录清单**前后逐项相同**，且**连根目录都不创建**
   （`assert(!os.exists(repo / ".nebflow" / "worktrees"))`）；
2. `binding.worktree == None`、`binding.cwd == 项目工作区`、`seatSource == InPlace`；
3. 共享 HEAD 仍停基分支 `main`，但 `delegate/<id>` 分支照建（回执可续用）；
4. 快照落地：`snapshotSha == HEAD`、`snapshotDirty == Some(Nil)`（收尾护栏的基线，见 `07-`）；
5. brief 行含 `NO worktree was allocated` / `READ-ONLY channel` / `GIT_INDEX_FILE`；
   回执行含 `NONE ALLOCATED` + `read-only channel`。

改前读数：缺省派发**必建树**（判定机判是/判否都建）；改后：0 个目录、0 次建树调用。
