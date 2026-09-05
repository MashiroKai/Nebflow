<!-- merge-node-rules:start -->

## 合并节点接线（批次产物落地收口，merge-node 批 20260905）

凡产生分支/worktree 产物的批次（步骤 4 评估为「多节点写同一批文件→各自建 worktree」）：**每个任务节点的 out 多对一接到同一个合并节点**（本批落地收口）——由它把全部上游分支 `--no-ff` 合并进 main，并清理本批 worktree/分支。纯调查/零产物批次（纯读取、无 worktree、无分支产出）可不接。零产物批次不需要合并节点，产物滞留审计口径（completed ⇒ 分支领先≥1且净 / commit-ready 申报 / 零改动）对任务节点照常生效。

### 合并节点创建模板（NodeEdit create）

- `agent`: `general`；**不配 `worktree`**（硬约束，NodeEdit 会拒绝 merge+worktree 组合）——合并节点沙箱根 = workspace 本体，主仓 `.git` 在根内可写；配了 worktree 沙箱根变成 worktree 目录，主仓 `.git` 在根外，git 变更一律 EPERM。
- `merge`: `true`（触发语义引擎侧保证：全部上游 completed 才启动；上游 failed → 合并节点自动转 blocked（category=upstream-incomplete，不合并不悬挂）；上游 blocked → 走既有 blocked 重入协议处置该上游，合并节点原地等待）。
- `out`: `Nebula`（落地完成回报）。
- `task` 必含三要素：**上游清单**（分支名 ↔ worktree 名一一对应）、**落地命令全集**（commit-ready 代执行语义：上游节点只需申报 commit-ready，落地由合并节点代做）、**复核命令 + 完成标准**。模板：

```text
合并落地：把上游分支逐支 --no-ff 合并进 main 并清理本批 worktree/分支。
上游清单：feat/xxx (worktree xxx)；feat/yyy (worktree yyy)
CMD:
git merge --no-ff feat/xxx -m "merge: xxx" &&
git merge --no-ff feat/yyy -m "merge: yyy" &&
git worktree remove .nebflow/worktrees/xxx &&
git worktree remove .nebflow/worktrees/yyy &&
git branch -d feat/xxx && git branch -d feat/yyy &&
git worktree list && git for-each-ref refs/heads
END
```

### 完成标准（防污染闭环，残留即不算完成）

合并节点 completed ⇔ 全部满足，否则必须以 BLOCKED 开头申报残留明细（category=external-dependency 或 other）：
1. 全部上游分支已合并进 main（`git log main` 可见各支合并提交）；
2. `git worktree list` 无本批 worktree 残留；
3. `git for-each-ref refs/heads` 无本批分支残留。

### blocked 后续（上游失败/被阻断时）

合并节点 blocked（上游 failed）→ 处置失败上游（NodeEdit 重激活它）→ 再 NodeEdit 重激活合并节点（改 task/agent 任一即触发重激活）→ 已完成上游结果自动重投、barrier 自动补齐，合并重跑。**不删除重建节点**（拓扑与结果留痕）。

<!-- merge-node-rules:end -->
