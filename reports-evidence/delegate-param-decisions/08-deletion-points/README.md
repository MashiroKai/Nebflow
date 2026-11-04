# 08 · 删除点证明（判定机 / 分配器无残留）

判据：「零模型调用是硬判据，不许留开关」——所以每个被删的符号都要有 `grep -rn` 的**空结果或
仅注释/测试列举**，并且整文件删除要在 `git status` 里看到 `D`。

## 命令原文

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-dcdeaa46"
for pat in decideWorktree decideWorktreeDetailed DelegateModeAllocator DelegateModeMachine \
           WorktreeJudgement FileChangeQuestion jevWorktree smallJudgeWorktree parseWorktree \
           prepareDevice "dualMode.enabled" failopenMode; do
  echo "### grep -rn \"$pat\" src/ --include=*.scala"
  grep -rn "$pat" src/ --include=*.scala || echo "(no hits at all)"
done
git status --porcelain | grep -E "^D |^ D"
git log -1 --format=%H
```

## 原始输出

见同目录 `deletion-grep.txt`（逐符号）。

## 读数（逐符号）

| 符号 | 结果 | 说明 |
|---|---|---|
| `WorktreeJudgement` | **0 命中** | 类型整体消失（端口方法 + spec 桩全部清除） |
| `decideWorktree` / `decideWorktreeDetailed` | 仅 2 处**注释** | `DelegateJudgeMachine.scala:55`、`DelegateJudge.scala:21` 的「已删除」说明文字；无任何代码引用 |
| `FileChangeQuestion` / `jevWorktree` / `smallJudgeWorktree` / `parseWorktree` | 仅 1 处注释 | `DelegateJudgeMachine.scala:55-56` 的删除说明 |
| `DelegateModeAllocator` | 仅 2 处注释 | `DualMode.scala:11`、`ModeParamSpec.scala:12` 的删除说明 |
| `DelegateModeMachine` | 3 处历史注释（`llm/registry.scala:93`、`AdaptiveThrottle.scala:47` 是**别的批**留下的枚举文案；`DualMode.scala:11` 是本批说明） | 无代码引用；整文件 `src/main/scala/nebflow/llm/decision/DelegateModeMachine.scala` 已 `git rm` |
| `prepareDevice` | 1 处**负断言** | `DeviceWorktreeResolutionSpec.scala:68` 的 `!src.contains("prepareDevice")`；`DeviceGitPort.scala` 注释已改为「本端口零生产消费点」 |
| `dualMode.enabled` / `failopenMode` | 仅注释 + `ModeParamSpec` P4 的**配置写法** | 解析面不再读配置（P4 反向证明：写进 nebflow.json 也不生效） |
| `worktreeJudged` | 仅 Record 字段的解码/兼容位与测试 | spawn 行**不再写入**（`DelegateWorkspace.spawnPayload` 停写，`DeviceWorktreeResolutionSpec` 机械断言 `judgeKeys absent`） |

## 整文件删除

```
D  src/main/scala/nebflow/llm/decision/DelegateModeMachine.scala                          （代码 + 执行体）
D  src/test/scala/nebflow/llm/decision/DelegateModeMachineSpec.scala                      （它的 spec）
D  src/test/scala/nebflow/core/delegate/DualModeGateSpec.scala                            （总闸 spec）
```

## 附带清理（本批一并做掉，避免留下「零消费者的假面」）

- `PromptCatalog.Classifiers.ModeStatePreamble`：mode 分配器的提示前导，删掉分配器后**零消费者** ⇒ 移除；
  分区四注释从「2 份」改为「1 份」（`PromptCatalog.scala`）。
- `DelegateWorkspace.scala` 三腿重写（499 行改动，见 `08-deletion-points/diffstat.txt` 与各 `.diff`）。
