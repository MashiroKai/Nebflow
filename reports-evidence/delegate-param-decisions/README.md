# delegate-param-decisions 批 · 证据目录（2026-11-04）

把 `Delegate` 的两个「小模型决策点」改成**编排方直接填的参数**：删掉 worktree 判定机与 mode 分配器
（零模型调用是硬判据，不留开关），`worktree` / `mode` 成为唯一决定面，并对「零分配却写盘」加事后标红护栏。

链：`delegate-nebflow-dcdeaa46` · 分支 `delegate/delegate-nebflow-dcdeaa46`

## 目录

| 目录 | 对应判据 |
|---|---|
| `01-zero-allocation/` | 不填 `worktree` ⇒ 零分配（就地只读通道），目录清单前后逐项相同 |
| `02-named-worktree/` | 填 `worktree=<name>` ⇒ `<ws>/.nebflow/worktrees/<name>` + 分支 `delegate/<name>` |
| `03-same-name-reuse/` | 同名再派 ⇒ 复用同一棵树，绝不产生第二个目录 |
| `04-zero-model-calls/` | 零模型调用：计数判官面 = 0 + 纯函数签名 + 符号级 grep |
| `05-mode-auto-reject/` | 不填 `mode` ⇒ 确定性 kernel；显式 `auto` ⇒ 明确报错（原文） |
| `06-regression/` | 改前 423/3/419/1 → 改后 407/2/404/1（零新增红，只剩存量红） |
| `07-in-place-write-guard/` | 补充令：写文件必标红（原始回执原文）+ 只读不误报（`None`）+ 提交也算 |
| `08-deletion-points/` | 删干净的证明：逐符号 `grep -rn` + 整文件删除 + diff |

顶层文件：

- `before-test.log` / `after-test.log`：同一条 sbt 命令的改前/改后全量输出
- `tool-description.diff`：`Delegate` 工具描述与 schema 的**逐字**改前/改后（含被删掉的旧假话）
- `v1-test.log` / `v2-test.log`：中途两轮的读数（保留过程）

## 一句话读数

`after-test.log` 末尾：`Failed: Total 407, Failed 2, Errors 0, Passed 404, Skipped 1`，两条红均为
`PluginCatalogLayerSpec` T4a/T4b 的**存量红**（改前就在，逐字相同）。
