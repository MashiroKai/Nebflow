# raw 读数快照（2026-10-19 16:00-16:40 采集 · 只读）

## 1. 代码锚点（本仓 main@c9f6cd18f）

| 断言 | file:line |
|---|---|
| 认领键 = 完成会话记录的 branch；无锚静默 None（不落行） | src/main/scala/nebflow/core/delegate/DelegateAutoMerge.scala:155-157 |
| attempt 内 skip 路径都落 skipped 行（对照） | 同上 :188, :196, :201 |
| ReadyToMerge × branch.isEmpty ⇒ 降级 completed | src/main/scala/nebflow/core/delegate/DelegateTerminal.scala:185 |
| aheadOfBase 依赖 branch 锚 | 同上 :165-175 |
| Mail 续聊腿 spawnBackground 不传 judgeWorkspace，workspace=原记录 cwd | src/main/scala/nebflow/core/tools/MailTool.scala:1327-1348 |
| judgeWorkspace 缺省 false | src/main/scala/nebflow/core/tools/DelegateTool.scala:581 |
| 判定机腿整体闸在 judgeWorkspace | 同上 :672-679 |
| 新鲜派发腿显式 judgeWorkspace=true（注释自证续聊腿「保持旧形态」） | 同上 :358-359 |
| Record 落账 branch/worktree/baseBranch/projectWorkspace 四键位 | 同上 :761-764 |
| BootSweep 恢复腿不传 judgeWorkspace（座位=baseWs） | src/main/scala/nebflow/agent/MergeQueueBootSweep.scala:76-100 |
| DeviceDelegate 腿不传 judgeWorkspace | src/main/scala/nebflow/core/delegate/DeviceDelegate.scala:408 |
| MergeAcceptance.verifyRecord 锚不全必 false | src/main/scala/nebflow/core/delegate/MergeAcceptance.scala:42-47 |
| 外部腿同走 prepare 拿锚 | src/main/scala/nebflow/core/executor/ExternalSessionRunner.scala:131-140 |
| 外部腿 settle 挂 auto-merge（有锚才评） | 同上 :553 |
| 外部腿无锚显式 skip 尾注（与内部腿不对称） | 同上 :556 |
| 外部续聊 = 同 id resume turn（锚不丢） | 同上 :288-328 |
| auto-merge merged 分支无 PostMergeCleanup | DelegateAutoMerge.scala:235-257 |
| 清理面仅三消费点 | DelegateTerminal.scala:254 / ExternalSessionRunner.scala:531 / MergeQueueBootSweep.scala:171 |
| MergeQueue 轮锁外注入、delegate 自执行 git -C baseWs merge | src/main/scala/nebflow/core/delegate/MergeQueue.scala:298-307, 370-378 |
| refused 落「git refused (no conflict markers)」假案面 | DelegateAutoMerge.scala:281-284 |
| 冲突挂起三承载面（尾注/台账/ChainStore 行） | 同上 :263-280 |
| freeName check-then-act | src/main/scala/nebflow/core/GitWorktrees.scala:86-89, :129 |
| 分支名 = delegate/<spawn id> | src/main/scala/nebflow/core/delegate/DelegateWorkspace.scala:98 |
| 两机制互不相干自述 | DelegateAutoMerge.scala:44-46 |
| legacy NodeEditTool worktree 腿已退役 | src/main/scala/nebflow/core/tools/NodeEditTool.scala:922 |

## 2. 今日续聊形态记录（branch=None 且 cwd 在他人 worktree）

| id | task | reportStatus | 状态 | 座位（他人 worktree） |
|---|---|---|---|---|
| delegate-nebflow-dcdbba65 | 537 | **merge** | archived 10:44* | delegate-nebflow-75b0ba04（案例②续聊） |
| delegate-nebflow-3ed38d01 | 552 | **merge** | archived 10:44* | delegate-nebflow-017653fb（案例③续聊） |
| delegate-nebflow-7f79b63d | 519 | finish | archived | delegate-nebflow-1fdd686d |
| delegate-nebflow-1d46f0cd | 517 | finish | archived | delegate-nebflow-6f7ec175 |
| delegate-nebflow-01308080 | 574 | None | **live（进行中）** | delegate-nebflow-e0b069db |

*archivedAt 1792374588042/1792378135669 ≈ 10:44/10:49。01308080 为在飞同型会话——**下个复现点**。
全部记录 branch/worktree/baseBranch/projectWorkspace = None（delegate-registry 逐文件读数）。

## 3. 今日合并提交归属（main merge commits × ChainStore merge-queue 行）

| merge commit | 时间 | 归属 |
|---|---|---|
| 573dfe9d2 | 08:24 | 队列轮 35b31668（#539，enq 08:22/deq 08:25） |
| d8a3d2aa6 | 08:34 | 队列轮 f7679358（#542，08:26-08:35） |
| ec7259ba5 | 10:12 | 队列轮 7933af88（#555，10:12-10:13） |
| e86d464a2 | 10:42 | **人工批 #557**（00ef554a，零 merge-queue 行） |
| 9821bb5a5 | 10:50 | **人工批 #558**（c830b62d，零 merge-queue 行） |
| 80fbde315 | 13:19 | 队列轮 9ecb332f（#560） |
| 5f973f36d | 15:16 | 队列轮 external-zcode-26afe2a0（#563，外部 channel 注入，15:17 cleanup removed） |
| 72af8b8d0 | 15:50 | 队列轮 e0b069db（#574） |
| 617c56e27 | 16:01 | 队列轮 d8bdb31f（#572） |
| f8c9baf65 | 16:06 | 队列轮 e33b815f（#578） |
| c9f6cd18f | 16:20 | 队列轮 540b9857（#579） |
| 81a3be27a | 10-18 23:51 | （昨日轮） |

全部为 `Merge branch '…'` 缺省 message = MergeQueue 自执行轮形态；**零条** auto-merge 专属 message（`auto-merge: delegate … engine-native FIFO, #524`）。

## 4. 行种普查（~/.nebflow/chains/*.json，150 文件）

```
terminal-verdict 198 / delegate-spawn 171 / merge-queue 88 / worktree-cleanup 69
/ step-plugins 17 / unmerged-work 1 / auto-merge 0  ←零
```
nebflow.json 无 delegateAutoMerge 键 ⇒ Config.read = Default(enabled=true)。DelegateAutoMerge 落 main = f707cfdc4（10-18 23:41）；DelegateAutoMerge.class 编译产物 10-19 08:39；引擎进程（sbt run，stand.log 连续 00:35→16:30）自 10-18 ~22:00 未重启 ⇒ 今日终态全在旧字节码上。

## 5. worktree 盘面（/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/，31 目录 6.8G）

28 条 delegate/external 分支全部 ahead=0（已并入 main）。逐目录：

| worktree | branch | ahead | dirty | size |
|---|---|---|---|---|
| 017653fb | delegate/delegate-nebflow-017653fb | 0 | 0 | 584M |
| f7679358 | delegate/delegate-nebflow-f7679358 | 0 | 0 | 483M |
| 189e295a | delegate/delegate-nebflow-189e295a | 0 | 12 | 483M |
| cd833b36 | delegate/delegate-nebflow-cd833b36 | 0 | 32 | 477M |
| aab5503d | delegate/delegate-nebflow-aab5503d | 0 | 190 | 427M |
| 35b31668 | delegate/delegate-nebflow-35b31668 | 0 | 0 | 421M |
| 其余 21 个 delegate-* / 2 个 external-codex-* | ahead=0 | — | 0-10 | 164-166M |
| e0b069db | **残壳 20K**（git worktree list 无此条目；目录内 rev-parse 落穿主仓库误报 main） | — | 0 | 20K |
| e0b069db-c | delegate/delegate-nebflow-e0b069db-c（非 freeName 命名法产物） | 0 | 2 | 166M |

可回收 ≈ 5.3G；6 个脏工作树（189e295a/4f58672f/6141e47e/aab5503d/cd833b36/b9a0937f±）非 force remove 必拒。`~/.nebflow/worktrees`（全局）= 0。

## 6. 今日判词退化观测

stand.log「terminal verdict unavailable on both legs」11 条（00:35 0b4e0ac6 / 02:13 683136df / 09:12 1f38f5d0 / 10:44 **00ef554a** / 10:51 **c830b62d** / 13:26 410e9899 / 13:34 2a8c6ab9 / 15:17 external-zcode / 15:20 44b09f05 / 16:06-07 e33b815f×2）。

## 7. 原会话终态读数（案例②③前置）

- delegate-nebflow-75b0ba04（#537 迁移工具箱）：spawn 07:44 → terminal-verdict **blocked** 08:11；archivedAt=None；分支留 2 提交（fcc93f22d/f02240585）。
- delegate-nebflow-017653fb（#552 AskUser 批）：spawn 09:09 → terminal-verdict **blocked** 09:56；archivedAt=None；分支留 9 提交。
- 续聊两记录 reportStatus=merge 且 reportDetail 明列待并提交清单——自申报到达引擎，认领键查不到锚。
