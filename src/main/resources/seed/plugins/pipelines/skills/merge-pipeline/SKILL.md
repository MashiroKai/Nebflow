---
name: merge-pipeline
description: Git 合并流水线纪律（由 git-merge flow 蒸馏）——分支扫描（commit/文件/冲突/测试四事实）→ 就绪评估（go/no-go 三判据）→ 合并执行（简单冲突自解/复杂冲突上报/分支清理）→ 失败回滚；全程不 push 除非明确指示。Use when 将 feature 分支合并进目标分支（main 等）需要质量闸门；worktree=None 即主仓语义。
when_to_use: 合并 feature 分支前的就绪评估与执行；多分支批量合并的逐支闸门；合并后清理（分支/worktree）。冲突融合、子集复跑等深度场景与 LoopNode 打回循环由分发器按任务选型。
language: zh
status: active
last_verified: 2026-09-03
---

# Merge Pipeline — Git 合并纪律

## 第一步：扫描（事实收集）

对目标合并（feature → target）收集四类事实，产出事实报告：

- `git status`（工作区状态）、`git branch`（分支存在性）、`git log <target>..<feature> --oneline`（feature 独有提交）、`git diff <target>...<feature> --stat`（变更面）
- 报告：commit 清单、变更文件清单、**检测到的冲突文件**（`git merge-tree` 或试合并探知；未测则标 null）、测试状态（可验证才填 boolean）

事实要全面——下游的 go/no-go 完全依赖这份报告。

## 第二步：评估（go / no-go）

三个判据全过才可合并：

| 判据 | 通过条件 |
|---|---|
| 测试 | 相关测试通过（或本轮合并闸门认可的等价验证） |
| 冲突 | 无未解决冲突 |
| 审查 | 变更已经过审查（评审 PASS/验收通过） |

- **go**：附一句话理由。**no-go（skip）**：附一句话理由（冲突/测试挂/未审查/其他未就绪）——合并被跳过不是失败，是闸门起作用。
- 有问题的合并**不硬修**：blocked 项如实上报（需要人工干预的冲突、历史问题）。

## 第三步：执行合并

1. 切到目标分支，确认工作区干净。
2. `git merge <feature> --no-ff -m "merge: <feature> into <target> ..."`——保留合并语义（`--no-ff` 无条件强制）。
3. **冲突处理分级**：简单冲突（同义小改）自行融合解决，解完必须核对两侧语义都保留（整文件 ours/theirs 一刀切禁止——类名/内容对账防静默吞块）；复杂冲突（架构性分歧/大面积重叠）→ 中止合并（`git merge --abort`），上报人工裁决。
4. 合并后跑受影响面的复验（编译/测试子集，按项目验收口径）。
5. 清理：删除已合并的 feature 分支（`git branch -d`）；worktree 场景 `git worktree remove <路径>`。

## 第四步：报告

- merged 状态、feature→target、merge commit hash、未解决冲突清单（空则 []）、分支是否已清理、一句话摘要。
- 合并失败/中止时：merged=false + 中止原因 + 当前现场（干净与否）。

## 红线

- **不 push**——除非触发方明确指示；推送与发布永远显式授权。
- **不强推、不改已发布的合并历史**。
- 复杂改动（多文件/架构变更）合并前应已过审查（review 关卡前置，不在合并时补审）。
