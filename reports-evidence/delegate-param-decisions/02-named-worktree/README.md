# 02 · 填 `worktree=<name>` ⇒ 按该 name 建树

判据（作者令）：「填了 ⇒ 内容就是 worktree 的 name」——`<worktreeRoot>/<name>`，分支 `delegate/<name>`。

## 命令原文

同 `01-` 的 sbt 命令（全量 selector，输出落 `../after-test.log`）。

## 原始输出（`raw.log`）

```
nebflow.core.delegate.DelegateWorkspaceWorktreeLegSpec:
  + W1 零分配：worktree 参数缺省 ⇒ 不建树，新建目录清单前后逐项相同 + brief/回执都写明未分配 0.128s
  + W2 按名建树：worktree="<name>" ⇒ <worktreeRoot>/<name> 且分支 delegate/<name>，回执写『精确 name/新建』 0.154s
  + W3 同名复用：同一 name 再派一次 ⇒ 复用既有树，不产生第二个目录 0.328s
  + W4 零模型调用：装计数判官面，跑完零分配腿 + 按名建树腿 + 模式解析后计数仍为 0 0.334s
  + W5 非 git 工作区：就地腿 = None（旧语义不回归） 0.017s
```

## 机械断言（W2）

- 目录路径**等于**参数名：`<ws>/.nebflow/worktrees/liquid-glass-batch` 存在；
- 分支名 `delegate/liquid-glass-batch`；
- `seatSource == Created`，回执行含 `hit rule ③ created for name '…'`（命中哪条规则必须可审计）。
- `GitWorktrees.createNamedWorktree` 固定 `<ws>/.nebflow/worktrees/<name>`，**不再**派生候选名
  （旧口径的 `-2`/`-3` 后缀派生随判定机一起删掉），建后做四查（注册可见 / 目录可进 / 分支正确 / 起点一致）。

改前读数：`worktree` 参数 = **复用**语义（value 是 delegate id / 目录名 / 绝对路径）；
改后：value = name，未命中即按名新建。
