# 06 · 回归读数（改前 / 改后同一条命令）

改前与改后用**同一条 sbt 命令**（见 `01-`），日志：`../before-test.log` / `../after-test.log`。

## 读数

| | Total | Failed | Passed | Skipped | 红的是谁 |
|---|---|---|---|---|---|
| 改前（基线，工作区改动前的同一 selector） | 423 | 3 | 419 | 1 | `DelegateToolSpec` schema 存量红 + `PluginCatalogLayerSpec` T4a/T4b 存量红 |
| 改后 | 407 | 2 | 404 | 1 | 只剩 `PluginCatalogLayerSpec` T4a/T4b（**与本批无关的存量红**，见下） |

- Total 423 → 407 = **−16**：整文件删除的 `DelegateModeMachineSpec`（模式分配器 spec）+ `DualModeGateSpec`
  （总闸 spec）+ `DelegateJudgeMachineSpec` 里 `parseWorktree` 的用例，随被删代码一起消失（不是跳过）；
- `DelegateToolSpec` 的 schema 存量红**被本批修掉**（schema/描述本来就该改：enum 去掉 `auto`、
  `worktree` 描述改语义），所以 Failed 3 → 2。

## 存量红（未碰，不在本批范围）

```
==> X nebflow.llm.decision.PluginCatalogLayerSpec.T4a C17: description>200 chars lands a WARN (never a FAIL); ≤200 stays PASS  munit.FailException: src/test/scala/nebflow/llm/decision/PluginCatalogLayerSpec.scala:67 the soft WARN must appear:
==> X nebflow.llm.decision.PluginCatalogLayerSpec.T4b C18: a broken SKILL.md reference in Plugin.md lands a WARN; live refs and no-Plugin.md pass  munit.FailException: src/test/scala/nebflow/llm/decision/PluginCatalogLayerSpec.scala:83
```

这两条在 `before-test.log` 里逐字相同（第 685 / 691 行），与 worktree/mode 参数无关（Plugin.md /
SKILL 引用校验），本批未改也没有修它们——**红线与基线一致，零新增红**。

## 原始输出（`raw.log`，选取两条本批改动的关键 spec）

```
nebflow.core.tools.DelegateToolSpec:
  …（schema 用例通过：props = task/name/project/executor/worktree/force/mode；mode enum = kernel|dispatcher）

nebflow.core.delegate.DeviceWorktreeResolutionSpec:
  + 态① 设备项目的座位腿不再问设备 git：本机不建任何树（判定机与设备建树腿均已删除） 0.01s
  + 态② 设备项目 + worktree 参数 = 显式拒（文案含 DELEGATE_WORKTREE_REMOTE 与出路） 0.002s
  + spawn 行契约：workspaceSeat 键 additive——传则落键，缺省则键缺席（旧行字节零漂移） 0.001s
  + spawn 行契约：座位来源键 seat / seatName（本批新增——命中哪条规则可审计） 0.0s
```
