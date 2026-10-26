# 共享分支积压合并放行（#945）

日期：2026-10-26 16:41（本机 KAI / Windows）
授权：人工放行（#943 体检坐实共享分支终态不进合并队列）

## 1. 结论

`delegate/delegate-nebflow-c2e6b6d0` 的 9 个 commit 已全部并入 `main`，无冲突、无强推、无远端推送、无 jar 部署、未碰引擎进程、未改主工作树检出与未提交改动。

## 2. 合并前后 main tip

| | commit | 说明 |
|---|---|---|
| 合并前 | `85a79ff74` | Merge branch 'delegate/delegate-nebflow-c2e6b6d0'（#943 记录写于 14:02） |
| 主代码合并后 | `095f06461` | Merge branch 'delegate/delegate-nebflow-c2e6b6d0' into main (backlog release #945) |
| 报告入册后 | `eaf5ac78c` | Merge … (backlog release #945 report) —— 仅新增本 REPORT.md，无一代码改动 |
| 终态 | `ab0e904b9` | Merge … (backlog release #945 report final) —— 仅本报告文字补正 |

报告文件本身的入册：`6e1bed5d7` + `6e5f8e6a5`（delegate 分支，均为报告文字）→ 并入 main `eaf5ac78c`、`ab0e904b9`。
上表合并 commit 均以 `--no-ff` 产生；若之后有别批落地，main tip 会继续前移，以 `git log --oneline -1 main` 为准。

终态读数（`85a79ff74..main`）：总 14 个 commit，其中非 merge **11** 个 = 9 个交付 commit + 2 个本报告 commit。
基线代码核对仍以 `85a79ff74..095f06461` 的 9 个交付 commit 为准（见 §3）。

策略：`git merge --no-ff`，实测 `Merge made by the 'ort' strategy.`，零冲突。
合并基：`456e9d18f4bdf6b9ca1bf669f5f2d1b64c76058b`。

## 3. 入册 commit 清单（9 个，`85a79ff74..095f06461`）

```
ed3c8e707 fix(engine): #943 合并队列回纯排队+通知
90a9d7b42 feat(agent): #939 Mail 注入时机提前到工具调用边界
d000ed686 ui-copy: #934 删设置页思考档位说明行
abd67aad7 report(#924): 广面回归读数补正
142b3e9ed feat(login): #924 统一登录两路径 —— 无头密码登录 + 设备码流退役
1dbbb14ff fix(infra): #916 task <Command> 改 powershell 全路径
2a6ba3a36 report(#922): 上滑加载慢+触发重连批报告
7d773b928 fix(web): #922 上滑补页分帧构建+预取/停顶续拉+心跳渲染遮蔽门
a35e6599f fix(infra): #916 task XML 编码改 UTF-16LE+BOM
```

计数核对：
- `git rev-list --count 85a79ff74..main` = **10**（9 个交付 commit + 1 个合并 commit）
- `git rev-list --count --no-merges 85a79ff74..main` = **9**
- `git merge-base --is-ancestor delegate/delegate-nebflow-c2e6b6d0 main` → 通过（分支已完全被 main 包含）

覆盖台账：#916 / #921 / #922 / #924 / #934 / #939 / #943 —— 任务书所列五批（#921/#922/#924/#934/#939）与 #943 引擎修复全数入册。

## 4. 合并 diffstat（读数，不重跑全量测试）

`git diff --stat 85a79ff74..main -- src/main/scala | tail -5`：

```
 .../scala/nebflow/neblink/LogtoTransport.scala     | 187 +++++++++
 src/main/scala/nebflow/neblink/NeblinkClient.scala |   2 +-
 src/main/scala/nebflow/neblink/NeblinkModel.scala  |   2 +-
 src/main/scala/nebflow/neblink/Protocol.scala      |  21 +-
 25 files changed, 1587 insertions(+), 730 deletions(-)
```

合并整体：58 files changed, 3910 insertions(+), 1450 deletions(-)（含 4 个新增报告、1 个删除的 `LogtoDeviceFlow.scala` 及其 spec、4 个新增 spec）。

轻量静态检查（不重跑测试）：
- 变更文件面 `git status --porcelain` 在合并后 worktree 为空 → 无遗留冲突标记、无未跟踪残留。
- 变更构成与各批报告自述一致（web/js、neblink 登录两路径、MergeQueue/MergeArtifactCare、FlowMapStore、inline specs）。

## 5. worktree 清理确认

- 创建：`git worktree add C:/Users/Kai/stand-merge-tmp main`（临时 linked worktree；共两轮：第一轮 main @ 85a79ff74 合代码，第二轮 main @ 095f06461 合本报告）
- 每轮删除前确认：`git status --porcelain | wc -l` = **0**（干净）
- 每轮删除：`git worktree remove C:/Users/Kai/stand-merge-tmp`（exit=0，目录已不存在）
- 删除后 `git worktree list` 仅剩：`C:/Users/Kai/stand`（delegate 分支）、`C:/Users/Kai/stand-929`、`C:/Users/Kai/tmp936/main1223`（均为他人/他批既有 worktree，未动）

## 6. 主工作树未受影响（红线核对）

| 项 | 合并前 | 合并后 |
|---|---|---|
| 检出分支 | `delegate/delegate-nebflow-c2e6b6d0` | 不变 |
| HEAD | `ed3c8e707` | → `6e1bed5d7`（仅新增本 REPORT.md 一条 commit，无代码面变更） |
| 未提交改动 | 18 项 | 18 项（全程未动；新增的仅本报告目录，已提交，未跟踪计数回到基线 18） |

未执行任何 reset / checkout / clean / stash / branch -D；未改动 git config（合并身份用一次性 `-c user.name/-c user.email` 传入，与仓库既有 author `MashiroKai <Kaimashiro@qq.com>` 一致，未写盘任何配置）。

## 7. 未做 / 遗留

- **未 push 远端**（按任务红线）。
- **未部署 jar**：修复生效走既有部署节拍，等作者。当前在飞 jar 仍为旧码。
- 未重跑全量测试（各批已各自验证；本批按任务书仅做读数核对）。
- `main` 的推进现在仅在本地仓库；下次有推送动作时需作者确认。
- 观测项：`main` 已含 #943 的 MergeQueue 改动，但**该改动要生效仍需重启/重新部署引擎**；在此之前合并队列行为仍是旧 jar 的形态。
