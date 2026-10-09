# main 追平晋升批回执：refs/heads/main ff → executor-registry tip

- 时刻：2026-10-06 16:29:57 +0800
- 手段：纯本地 `git update-ref refs/heads/main <tip>`（等价 ff）
- 红线遵守：零 push / 零 remote 写 / 未 checkout·switch / 未新建 commit / 未动 git config / 未 force

## 全量读数

| 项 | 值 |
|---|---|
| before main | `a5b716d55c76173d253e78f7bd3609b2b4f6f3ab` |
| executor-registry（移动时点现读） | `0adfef219914002ba166d4648d371c7459f2eadb` |
| ancestry 判定 | `git merge-base --is-ancestor main executor-registry` → 成立（main 为祖先，ff-able） |
| 动作 | `git update-ref refs/heads/main 0adfef219914002ba166d4648d371c7459f2eadb`（exit 0） |
| after main | `0adfef219914002ba166d4648d371c7459f2eadb` |
| executor-registry 复核 | `0adfef219914002ba166d4648d371c7459f2eadb`（未变） |
| after merge-base(main, executor-registry) | `0adfef219914002ba166d4648d371c7459f2eadb`（两支同 tip，双向祖先成立） |

## porcelain 前后差集（应空 → 空）

- before（7 条）：`?? .zcodeignore` `?? bing1.html` `?? bing2.html` `?? demo/` `?? design/` `?? evidence/` `?? t.bin`
- after（7 条）：与 before 完全相同
- 差集：**∅**（update-ref 动作自身零扰动）
- 注：`evidence/` 为并行 branchcleanup-6verdicts-x1 批的授权在途产物（派位基线 6 条之外的第 7 条），非本批扰动；本批自身口径差集为空。

## reflog 证据（refs/heads/main）

```
0adfef219 refs/heads/main@{2026-10-06 16:29:57 +0800}:   ← 本次 update-ref（a5b716d55 → 0adfef219）
a5b716d55 refs/heads/main@{2026-10-03 12:04:51 +0800}: commit: feat(builtin): BuiltinAgents code-defined four agents; …
```

## worktree 核验

- `git worktree list`：refs/heads/main 无任何 worktree 检出；主区 HEAD 保持在 executor-registry（未动）。

## 声明

- **零 push / 零 remote 写动作**：origin/main 未动，纯本地晋升。
- 未触碰：executor-registry 引用本身、neblink-server 仓、stable/*、分支清算批 17 支名单、chatmsgops-impl。
