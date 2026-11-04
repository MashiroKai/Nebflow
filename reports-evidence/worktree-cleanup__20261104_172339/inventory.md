# Stand 仓库 worktree 清理 —— 盘点与执行

## 0. 口径与基线

- 任务书写「`git worktree list` 当前 281 条」。实测：任务下发时 main tip `6eb8d3f3e`，盘点时 worktree 登记 **284** 条（主仓 + 283 个树）——差额来自任务下发期间新起的会话（活的会持续造树）。
- 合并判定一律以 main tip `d2fc65c82`（执行时的 tip）为准；执行结束后 main 又前进到 `1bfe6892d`。main 只前进不回退，「已合」判定方向安全（合过的永远合过）。
- 「干净」= `git status --porcelain` 输出 0 行；「已合」= `git rev-list --count main..<branch>` = 0。
- 活会话判定：任务书要求查 `AgentControl list`，本节点无该工具。改用两条硬证据：① `~/.stand/delegates/*.json` 中未归档、`reportStatus` 非 finish/merge、最后活动 ≤27h 的 delegate（覆盖 standby 挂起会话）；② 全进程 `lsof` 扫描（cwd 或打开文件落在树内）。两者取并集。

## 1. 分类表（任务书要求的「先盘」，163 + 91 + 2 + 10 + 1 + 16 = 283 自洽）

| 类 | 含义 | 条数 |
|---|---|---|
| `clean_merged` | 可删：干净（porcelain 0 行）且分支已全部并入 main | **163** |
| `dirty` | 保留：工作树有未提交改动 | **91** |
| `unmerged` | 保留：工作树干净但有未合 main 的提交 | **2** |
| `detached` | 单列：detached HEAD | **10** |
| `orphan` | 孤儿：目录已不存在 | **1** |
| `live` | 保留：仍有活会话 | **16** |
| `probe_failed` | 异常：git 探测失败 | **0** |
| | **合计** | **283** |

自洽校验：163 + 91 + 2 + 10 + 1 + 16 + 0 = **283** = 待判定 worktree 数 283 ✅

## 2. 执行结果

| 步骤 | 结果 |
|---|---|
| `git worktree prune`（第 1 次） | 清掉 **2** 条元数据：`cc-pre`、`cs1030-pristine`（目录/gitdir 已消失） |
| `git worktree remove`（Stand，无 `--force`） | 成功 **163** 条 |
| 被 git 拒绝转保留 | **0** 条 |
| `git worktree prune`（收尾） | 0 条 |
| Stand worktree 登记数 | **284 → 120**（−164） |
| `.nebflow/worktrees` 占用 | **106G → 47G**（−59G） |

### 每条删除都做了「删除前即时复核」

删除清单来自盘点快照，但**每次 remove 之前都重新算一遍** `status --porcelain` 与 `rev-list main..<branch>`，并做一次实时活进程扫描（`/tmp/wt_live_fresh.txt`）。任一不满足即跳过并记原因。这是为了防「盘点后、删除前」有会话往某棵树里落了改动。实际执行：163 条 0 跳过、0 拒绝。

## 3. 磁盘回收

| 指标 | 改前 | 改后 | 回收 |
|---|---|---|---|
| `df -h` 可用 | **3.3Gi**（Used 898Gi，100%） | **73Gi**（Used 828Gi，92%） | **≈ +70Gi 可用** |
| `du -sh Stand/.nebflow/worktrees` | 106G | 47G | −59G |
| `du -sh /private/tmp` | 20G | 13G | −8.25G（整数 8.0GiB） |
| stand-website `.nebflow/worktrees` | 8.5G | 5.7G | −2.8G |

> 改前 `df` 读数在盘点期间从 4.5Gi 掉到 3.3Gi —— 同期其他批在跑 sbt 测试吃盘，说明这次清仓是止血而非优化。

## 4. 保留清单（给作者看：这些树里还躺着可能需要的工作）

### 4.1 活会话（16 条，一律不动）

| 路径 | 判定依据 |
|---|---|
| `delegate-nebflow-183b016d` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-1d300d2e` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-4280aa54` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-485bb4f4` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-4d537ce5` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-6f8eab41` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-70e8d8ab` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-79768ef5` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-79bb6698` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-835d17e9` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-af261318` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-b1d91721` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-b3bdf8d3` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-be138470` | delegate 未收尾 ≤27h / 进程占用 |
| `delegate-nebflow-fbe4618c` | delegate 未收尾 ≤27h / 进程占用 |
| `cc-before` | delegate 未收尾 ≤27h / 进程占用 |

### 4.2 有未提交改动（91 条）——「一律保留」

| 路径 | 分支 | 未提交 | 领先 main | 末次提交 | 末次提交标题 |
|---|---|---|---|---|---|
| `…/delegate-nebflow-00381b66` | `delegate/delegate-nebflow-00381b66` | 7 | 0 | 2026-10-30 09:16 | Merge branch 'delegate/delegate-nebflow-74e637c4' |
| `…/delegate-nebflow-03e396b4` | `delegate/delegate-nebflow-03e396b4` | 7 | 0 | 2026-10-30 02:59 | Merge branch 'delegate/delegate-nebflow-fbbd4ee3': main 尖既有测试红归因修复批（me |
| `…/delegate-nebflow-064972f7` | `delegate/delegate-nebflow-064972f7` | 7 | 0 | 2026-10-30 11:08 | Merge branch 'delegate/delegate-nebflow-acc72f03': preset→model-chain  |
| `…/delegate-nebflow-09fffbda` | `delegate/delegate-nebflow-09fffbda` | 5 | 0 | 2026-10-31 17:27 | feat(mobile): 悬浮钮统一 48px g2-fab 基准 + 全钮 spring 按压 + 消息/联系人页展开式搜索（作者令 1 |
| `…/delegate-nebflow-0c50686f` | `delegate/delegate-nebflow-0c50686f` | 2 | 2 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-0cda5510` | `delegate/delegate-nebflow-0cda5510` | 2 | 0 | 2026-11-01 22:30 | chore(merge-hygiene): 合并轮证据刷新——6438f440b 合并树上四套复测（muse-restore-smartcr |
| `…/delegate-nebflow-147dadf8` | `delegate/delegate-nebflow-147dadf8` | 1 | 0 | 2026-10-31 12:17 | feat(web): @ // 候选浮层换装墨族+液态玻璃设计语言（作者令 2026-10-31） |
| `…/delegate-nebflow-164aec7e` | `delegate/delegate-nebflow-164aec7e` | 24 | 0 | 2026-10-31 11:31 | Merge branch 'delegate/delegate-nebflow-10b6f701' |
| `…/delegate-nebflow-1818bb74` | `delegate/delegate-nebflow-1818bb74` | 8 | 0 | 2026-11-02 13:45 | fix(quote): 文字引用牌图标改用 composer footer 引用入口键同一枚 SVG（作者令 2026-11-02 12:5 |
| `…/delegate-nebflow-189e295a` | `delegate/delegate-nebflow-189e295a` | 189 | 0 | 2026-10-19 17:06 | docs(evidence): resend-dedup #569 复跑读数更新 + 并行批踩踏事故申报 |
| `…/delegate-nebflow-18d8fe69` | `delegate/delegate-nebflow-18d8fe69` | 1 | 0 | 2026-11-01 17:19 | Merge branch 'delegate/delegate-nebflow-9fcc3f05' |
| `…/delegate-nebflow-1ac734ef` | `delegate/delegate-nebflow-1ac734ef` | 2 | 0 | 2026-10-29 08:17 | docs(ask): 设计稿 §2/§8 可见性口径注记——入口只在文件型标签页挂载显示（作者缺陷报 2026-10-29） |
| `…/delegate-nebflow-1cb7b55c` | `delegate/delegate-nebflow-1cb7b55c` | 2 | 2 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-1f4dbedf` | `delegate/delegate-nebflow-1f4dbedf` | 3 | 0 | 2026-11-01 03:53 | Merge branch 'delegate/delegate-nebflow-67391c31' |
| `…/delegate-nebflow-22c29d3d` | `delegate/delegate-nebflow-22c29d3d` | 2 | 0 | 2026-10-29 00:59 | fix(gateway)+test(e2e): 孤儿 IO 日志双修（兑换/判词行从未发射）+ 隔离引擎真浏览器 e2e 全绿 + 指纹 s |
| `…/delegate-nebflow-2637ab1d` | `delegate/delegate-nebflow-2637ab1d` | 21 | 0 | 2026-10-23 23:05 | docs(spec-hygiene): REPORT 回填 social-panel 换代（§2b #20/§4/§5）+ pw 终态 |
| `…/delegate-nebflow-296aafdb` | `delegate/delegate-nebflow-296aafdb` | 2 | 3 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-298b4ddb` | `delegate/delegate-nebflow-298b4ddb` | 3 | 0 | 2026-11-02 08:27 | Merge branch 'delegate/delegate-nebflow-43af6929' |
| `…/delegate-nebflow-2be86ba4` | `delegate/delegate-nebflow-2be86ba4` | 1 | 0 | 2026-10-31 11:31 | Merge branch 'delegate/delegate-nebflow-10b6f701' |
| `…/delegate-nebflow-35eeaf1e` | `delegate/delegate-nebflow-35eeaf1e` | 2 | 0 | 2026-11-02 13:47 | test(todo): legacy chain 三套件终跑账（24 passed / 1 failed = 既有红，73c305f81 同 |
| `…/delegate-nebflow-432df36a` | `delegate/delegate-nebflow-432df36a` | 9 | 0 | 2026-11-01 03:53 | Merge branch 'delegate/delegate-nebflow-67391c31' |
| `…/delegate-nebflow-4398a05f` | `delegate/delegate-nebflow-4398a05f` | 2 | 2 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-43af6929` | `delegate/delegate-nebflow-43af6929` | 1 | 0 | 2026-11-02 08:26 | mobile: 消息/联系人两 tab 常驻顶部搜索条，右上搜索浮钮退役（作者令 2026-11-02 07:13） |
| `…/delegate-nebflow-48dfd701` | `delegate/delegate-nebflow-48dfd701` | 9 | 0 | 2026-10-29 22:22 | settings-panel-jev-imagegen 批②③件：JeV 与生图的「测试可用性」钮（作者令 2026-10-29 17:53 |
| `…/delegate-nebflow-491a5f27` | `delegate/delegate-nebflow-491a5f27` | 1 | 0 | 2026-11-02 08:39 | chore(merge-hygiene): absorb untracked generated artifacts (evidence/, |
| `…/delegate-nebflow-5017517a` | `delegate/delegate-nebflow-5017517a` | 6 | 0 | 2026-10-29 18:17 | Merge branch 'delegate/delegate-nebflow-3021b8e1' |
| `…/delegate-nebflow-5856419d` | `delegate/delegate-nebflow-5856419d` | 1 | 0 | 2026-10-31 23:44 | 撤除手机端联系人页顶「手机接入」入口（作者 10-31 22:55 令，仅手机端） |
| `…/delegate-nebflow-5ca27781` | `delegate/delegate-nebflow-5ca27781` | 2 | 3 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-5d5aa2be` | `delegate/delegate-nebflow-5d5aa2be` | 3 | 0 | 2026-11-02 12:01 | Merge branch 'delegate/delegate-nebflow-8c0b5ca5' |
| `…/delegate-nebflow-5e45698c` | `delegate/delegate-nebflow-5e45698c` | 4 | 0 | 2026-10-28 20:58 | fix(web): 思考中计时改读阶段锚 phaseStartAt——内部重试/重连不重置，阶段切换才归零（thinkingtimer-fi |
| `…/delegate-nebflow-5fa55cde` | `delegate/delegate-nebflow-5fa55cde` | 11 | 0 | 2026-10-24 09:00 | Merge branch 'delegate/delegate-nebflow-0e1c9e82' |
| `…/delegate-nebflow-5fb4d5e4` | `delegate/delegate-nebflow-5fb4d5e4` | 4 | 0 | 2026-10-24 16:45 | fix(web): 排队消息逐条投递语义收口——投递未决闸（作者报障 2026-10-24 15:45 逐字「排队的消息一次性发送…应该是你 |
| `…/delegate-nebflow-6aae2fd2` | `delegate/delegate-nebflow-6aae2fd2` | 5 | 0 | 2026-10-22 20:09 | docs(report): 子智能体面板行显示名修复批收口报告 |
| `…/delegate-nebflow-6c055b42` | `delegate/delegate-nebflow-6c055b42` | 4 | 0 | 2026-10-23 15:31 | fix(web): 删除批——对话框上方「活动」面板整链移除（作者令 2026-10-23 15:13 逐字「这个活动面板我不是让删掉吗，就 |
| `…/delegate-nebflow-6cbd7d08` | `delegate/delegate-nebflow-6cbd7d08` | 2 | 3 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-6e4185d6` | `delegate/delegate-nebflow-6e4185d6` | 5 | 0 | 2026-10-22 18:40 | fix(web): 排队呈现回退批（作者回访令 2026-10-22「怎么还出现了发送了的消息还在队列里的问题。我觉得这个可能需要回退，已收 |
| `…/delegate-nebflow-7297e18b` | `delegate/delegate-nebflow-7297e18b` | 2 | 0 | 2026-11-02 12:42 | feat(quote): 引用入口移入 footer + 引用牌类型图标化（作者令 2026-11-02 11:00） |
| `…/delegate-nebflow-76727b4a` | `delegate/delegate-nebflow-76727b4a` | 2 | 0 | 2026-10-29 14:30 | style(console): 错误态/冻结态对话框极简化（作者令 2026-10-29 13:51「往简约的方向走 结合stand的风格  |
| `…/delegate-nebflow-76aa2f3c` | `delegate/delegate-nebflow-76aa2f3c` | 1 | 0 | 2026-10-28 14:36 | reports: dbm 首个 MCP 插件挂载试点——validate 过闸+离线装载+stdio 全链路往返实证 |
| `…/delegate-nebflow-7961c23f` | `delegate/delegate-nebflow-7961c23f` | 7 | 0 | 2026-10-22 16:39 | Merge diff 摘要 chip 右对齐微调批（作者回访令 2026-10-22「把diff胶囊靠右对齐」——.nf-diff-chip |
| `…/delegate-nebflow-7966cb44` | `delegate/delegate-nebflow-7966cb44` | 1 | 0 | 2026-11-01 17:26 | Merge branch 'delegate/delegate-nebflow-27f4c58b' |
| `…/delegate-nebflow-7c249f3f` | `delegate/delegate-nebflow-7c249f3f` | 4 | 0 | 2026-10-30 02:31 | sw.js navigate 腿 /api 零接触次序修复 + INDEX_CACHE 只记真索引导航（console-load-perf  |
| `…/delegate-nebflow-7ceafc0d` | `delegate/delegate-nebflow-7ceafc0d` | 1 | 0 | 2026-10-30 21:28 | fix(冷启动调试批①-⑧,2026-10-30 作者实证): 图片查看器默认非拖拽(抓手钮/Space门)+控件hover完整材质+毛玻璃 |
| `…/delegate-nebflow-7e5196ed` | `delegate/delegate-nebflow-7e5196ed` | 3 | 0 | 2026-10-28 17:08 | fix(canvas): 三件 UI/UX 修（作者令 2026-10-28 16:0x）——①卡窗胶囊行对齐面板同款+水平居中（合并钮改  |
| `…/delegate-nebflow-7fa8a6bb` | `delegate/delegate-nebflow-7fa8a6bb` | 2 | 0 | 2026-11-01 21:54 | feat(mobile): dock 悬浮胶囊批——通栏底条换 Apple 风玻璃胶囊 + 输入卡呼吸缝（作者令 2026-11-01 21 |
| `…/delegate-nebflow-824276f8` | `delegate/delegate-nebflow-824276f8` | 15 | 0 | 2026-10-31 11:31 | Merge branch 'delegate/delegate-nebflow-10b6f701' |
| `…/delegate-nebflow-865a936b` | `delegate/delegate-nebflow-865a936b` | 2 | 4 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-87290cd9` | `delegate/delegate-nebflow-87290cd9` | 2 | 0 | 2026-10-29 18:17 | Merge branch 'delegate/delegate-nebflow-3021b8e1' |
| `…/delegate-nebflow-87cbdee0` | `delegate/delegate-nebflow-87cbdee0` | 1 | 0 | 2026-10-31 22:09 | chore(merge-hygiene): absorb spec-regenerated evidence shots（capsule-s |
| `…/delegate-nebflow-89580610` | `delegate/delegate-nebflow-89580610` | 25 | 0 | 2026-11-02 21:27 | Merge branch 'delegate/delegate-nebflow-f4d3e0f8' |
| `…/delegate-nebflow-8b2f50d3` | `delegate/delegate-nebflow-8b2f50d3` | 22 | 0 | 2026-10-31 10:46 | test(web): 刷新 checkJs type-baseline — reader/msgQuote 批欠账（非本批引入） |
| `…/delegate-nebflow-94fe78c9` | `delegate/delegate-nebflow-94fe78c9` | 3 | 7 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-991f7f0f` | `delegate/delegate-nebflow-991f7f0f` | 1 | 0 | 2026-11-02 11:20 | Merge branch 'delegate/delegate-nebflow-ea997ea1' (批文档补 vault 校验读数) |
| `…/delegate-nebflow-9a522ebe` | `delegate/delegate-nebflow-9a522ebe` | 5 | 0 | 2026-10-25 13:36 | 胶囊固定尺寸批：顶栏胶囊全脸固定 180×36（token 单源）+ 超长 ellipsis 收口 |
| `…/delegate-nebflow-9b828eae` | `delegate/delegate-nebflow-9b828eae` | 2 | 2 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-9fa60def` | `delegate/delegate-nebflow-9fa60def` | 2 | 0 | 2026-10-31 22:07 | fix(mobile): 字号放大批收口手机端（作者 21:36 追加令）——早前 12:50『联系人/对话字体放大（微信对标）』批确认口径 |
| `…/delegate-nebflow-a26da039` | `delegate/delegate-nebflow-a26da039` | 17 | 0 | 2026-10-22 06:59 | Merge branch 'delegate/delegate-nebflow-c00e0fd9' |
| `…/delegate-nebflow-a77a4e54` | `delegate/delegate-nebflow-a77a4e54` | 1 | 0 | 2026-10-31 17:34 | fix(web): 桌面边距还原批（作者令 2026-10-31 17:15「电脑端边距不需要调整，还原成之前靠边的形态」）——考古定位 5 |
| `…/delegate-nebflow-add4c91e` | `delegate/delegate-nebflow-add4c91e` | 14 | 0 | 2026-10-31 22:40 | Merge branch 'delegate/delegate-nebflow-b1991fb6' |
| `…/delegate-nebflow-ae1c2d58` | `delegate/delegate-nebflow-ae1c2d58` | 2 | 2 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-b21e2edf` | `delegate/delegate-nebflow-b21e2edf` | 4 | 0 | 2026-10-30 20:41 | 阅读器 P1 统一内核批（锚 设计稿20261030_181655 §2.2/§2.3/§3.1/§4.1）：底栏重排（滑杆取代纯文本+TO |
| `…/delegate-nebflow-bd39ddf7` | `delegate/delegate-nebflow-bd39ddf7` | 8 | 0 | 2026-10-25 18:51 | feat(canvas): 文件页签右下刷新钮 + 查看源码提速（canvas 文件刷新批，作者令 2026-10-25） |
| `…/delegate-nebflow-bd54b0ee` | `delegate/delegate-nebflow-bd54b0ee` | 2 | 0 | 2026-10-31 16:01 | feat(settings): 档参数覆盖人话文案批 + 接线审计（作者令 10-31「我还是不懂这些设置会对你的行为造成什么影响」） |
| `…/delegate-nebflow-bd7a0f1c` | `delegate/delegate-nebflow-bd7a0f1c` | 2 | 2 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-bf049776` | `delegate/delegate-nebflow-bf049776` | 5 | 0 | 2026-10-31 16:29 | fix(mobile): 手机端 AskUser 白屏批（作者 10-31 15:38 截图令）——chat-top-clip 垃圾锚点 + |
| `…/delegate-nebflow-bf078e5e` | `delegate/delegate-nebflow-bf078e5e` | 5 | 0 | 2026-11-01 21:22 | docs(recorder): 批报告补邻接回归读数与存量红归属实验（7 失败=基线存量，非本批） |
| `…/delegate-nebflow-bfad8ba9` | `delegate/delegate-nebflow-bfad8ba9` | 7 | 0 | 2026-11-01 09:13 | Merge branch 'delegate/delegate-nebflow-4a58b3db' |
| `…/delegate-nebflow-c1da3caa` | `delegate/delegate-nebflow-c1da3caa` | 2 | 2 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-c6ce9f87` | `delegate/delegate-nebflow-c6ce9f87` | 2 | 2 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-cd833b36` | `delegate/delegate-nebflow-cd833b36` | 32 | 0 | 2026-10-19 13:19 | Merge branch 'delegate/delegate-nebflow-9ecb332f' |
| `…/delegate-nebflow-ce004aa9` | `delegate/delegate-nebflow-ce004aa9` | 3 | 0 | 2026-11-01 11:11 | feat(takeover+entry): 作者令批（11-01 10:07 三件）——接管必达/卡片同族/入场加载覆盖 |
| `…/delegate-nebflow-d2fc050f` | `delegate/delegate-nebflow-d2fc050f` | 4 | 0 | 2026-10-31 13:14 | feat(web): Canvas 改造批十条（作者令 2026-10-31 09:25，液体玻璃 v2 风格闸门下）——①canvas 控 |
| `…/delegate-nebflow-d71b0ea4` | `delegate/delegate-nebflow-d71b0ea4` | 15 | 0 | 2026-11-01 03:53 | Merge branch 'delegate/delegate-nebflow-67391c31' |
| `…/delegate-nebflow-d78d3e3f` | `delegate/delegate-nebflow-d78d3e3f` | 1 | 0 | 2026-10-28 18:18 | Merge branch 'delegate/delegate-nebflow-96cbcbf8' |
| `…/delegate-nebflow-d8f43803` | `delegate/delegate-nebflow-d8f43803` | 2 | 8 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-dcc13390` | `delegate/delegate-nebflow-dcc13390` | 1 | 0 | 2026-10-30 09:07 | reports: 杂项运维小批三件批报告（cmdcode 摘除/flowmap KEEP 归档 2 留 35/design-language |
| `…/delegate-nebflow-e0b069db-c` | `delegate/delegate-nebflow-e0b069db-c` | 2 | 2 | 2026-11-02 20:14 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-e7c1a868` | `delegate/delegate-nebflow-e7c1a868` | 6 | 0 | 2026-11-04 10:51 | merge: main@56720568e → delegate/delegate-nebflow-e7c1a868 |
| `…/delegate-nebflow-e90a4202` | `delegate/delegate-nebflow-e90a4202` | 4 | 0 | 2026-10-23 16:08 | fix(web): 排队消息空闲不自动投递修复批（作者报障 2026-10-23 15:10 逐字「我发的消息进入排队队列之后，有时候，你完 |
| `…/delegate-nebflow-ea997ea1` | `delegate/delegate-nebflow-ea997ea1` | 2 | 0 | 2026-11-02 11:20 | docs(mobile): 标签环对齐修正批文档补 vault 校验读数（152 篇零违规 + 未入库 orphan 说明） |
| `…/delegate-nebflow-eed3ad45` | `delegate/delegate-nebflow-eed3ad45` | 2 | 0 | 2026-11-01 18:00 | docs(wiki): 文档库校验清零——17:25 面板标题 B 批文档补「样张」词条链 + 两条正文引用（R3 首现未释 / orpha |
| `…/delegate-nebflow-f1fc2f78` | `delegate/delegate-nebflow-f1fc2f78` | 2 | 0 | 2026-11-02 10:45 | docs(mobile): 插件面板让位+一行化批 —— 合入 main 后复跑账本 + 主工作区孤儿文档核查口径 |
| `…/delegate-nebflow-f23846aa` | `delegate/delegate-nebflow-f23846aa` | 17 | 0 | 2026-10-29 17:41 | Merge branch 'delegate/delegate-nebflow-f52794b2' |
| `…/delegate-nebflow-f30e15f7` | `delegate/delegate-nebflow-f30e15f7` | 2 | 2 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-f6a21b25` | `delegate/delegate-nebflow-f6a21b25` | 2 | 6 | 2026-11-02 20:16 | chore: untrack process artifacts (stop-tracking ruling 5ba295d2a) |
| `…/delegate-nebflow-f6d3906f` | `delegate/delegate-nebflow-f6d3906f` | 4 | 0 | 2026-10-31 09:37 | mobile long-press msg menu + quote attachment thumbs (author 10-31) |
| `…/delegate-nebflow-f7c28d49` | `delegate/delegate-nebflow-f7c28d49` | 1 | 0 | 2026-10-31 23:59 | feat(mobile): dock 收形批六件——消息流头像+agent 名恢复 / 中键去文字 / stand logo 回落 / 边缘 |
| `…/delegate-nebflow-f8815d4c` | `delegate/delegate-nebflow-f8815d4c` | 2 | 0 | 2026-11-02 07:52 | feat(mobile): 手机端交互标准批——token 单源 + 18 面全量套用 + 取消加载图标语义修正 |
| `…/delegate-nebflow-f91c3ca6` | `delegate/delegate-nebflow-f91c3ca6` | 12 | 0 | 2026-11-01 19:33 | feat(web): ask 输入行为包批——文字输入逻辑统一包（IME 回车统一 + ask interrupt 状态） |
| `…/delegate-nebflow-fbf9b18e` | `delegate/delegate-nebflow-fbf9b18e` | 1 | 0 | 2026-10-31 13:29 | feat(friends): 联系人/消息列表条目字号放大批——对标微信档（作者 10-31 12:50 令） |
| `…/delegate-nebflow-fe4f891e` | `delegate/delegate-nebflow-fe4f891e` | 4 | 0 | 2026-10-20 17:49 | 本地加速M1引擎侧: 同机近路四件套 (local-ticket会话腿绑定防localhost任意读/健康探测面环回deviceId+门户C |

### 4.3 有未合 main 的提交、工作树干净（2 条）

| 路径 | 分支 | 未提交 | 领先 main | 末次提交 | 末次提交标题 |
|---|---|---|---|---|---|
| `…/delegate-nebflow-92944aba` | `delegate/delegate-nebflow-92944aba` | 0 | 1 | 2026-11-03 15:07 | wip: 续跑批已停（作者 15:06 裁定由原 dispatcher 续命接管）——留存中断时的工作区改动，供原批参考 |
| `…/delegate-nebflow-98f9ca81` | `delegate/delegate-nebflow-98f9ca81` | 0 | 6 | 2026-11-04 12:23 | B1 第三腿：转绿证据 —— V1/V2/V3/V5(+V4/V6) spec 全绿 21/21、回归 shared 178 / cli 2 |

### 4.4 detached HEAD（10 条）

| 路径 | 分支 | 未提交 | 领先 main | 末次提交 | 末次提交标题 |
|---|---|---|---|---|---|
| `…/delegate-nebflow-6110565f` | `-` | 0 | 0 | 2026-10-30 14:29 | Merge branch 'delegate/delegate-nebflow-6110565f' |
| `…/tmp-bisect-63be` | `-` | 0 | 0 | 2026-10-31 23:54 | Merge branch 'delegate/delegate-nebflow-b452f5c5' |
| `/private/tmp/bashoverhaul-baseline-aade799e` | `-` | 0 | 0 | 2026-11-04 13:59 | Merge branch 'delegate/delegate-nebflow-42b0596b' |
| `/private/tmp/capsule-before` | `-` | 0 | 0 | 2026-11-02 11:35 | Merge branch 'delegate/delegate-nebflow-f003855e' (面板头胶囊高度 48 + canvas |
| `/private/tmp/cs1030-pristine` | `-` | 0 | 0 | - |  |
| `/private/tmp/g2-base-check` | `-` | 24 | 0 | 2026-10-31 09:41 | Merge branch 'delegate/delegate-nebflow-002f6988' |
| `/private/tmp/lg-baseline` | `-` | 0 | 0 | 2026-10-31 09:41 | Merge branch 'delegate/delegate-nebflow-002f6988' |
| `/private/tmp/lg-current` | `-` | 24 | 0 | 2026-10-31 12:01 | Merge branch 'delegate/delegate-nebflow-99507049' |
| `/private/tmp/np-baseline` | `-` | 0 | 0 | 2026-11-04 11:45 | 近路批·FINDINGS 读数订正：闸链耗时按最终全矩阵实测值（Chromium 命中 723/741ms、回环轴 527/649ms） |
| `/private/tmp/verify-main-baseline` | `-` | 12 | 0 | 2026-11-01 22:57 | Merge branch 'delegate/delegate-nebflow-9770a0a7' |

> 其中 `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-6110565f` 与 `tmp-bisect-63be` 是仓库内残留的临时树，HEAD 都在 main 历史里（领先 0），内容没有风险，但名字不是 delegate 批次，未擅自删。

### 4.5 孤儿（1 条）

- `/private/tmp/cc-pre` —— 收尾 prune 已随第 1 次 prune 清掉。

## 5. ⚠️ 单独高亮：分支已合但树里有未提交改动

任务书要求把这类矛盾情形单独拎出来 —— **说明有工作没落进 main**。

- 「分支已全部并入 main、但工作树有未提交改动」：**74 条**
- 「有未合提交、同时还有未提交改动」（更危险，两份没落地的工作）：**17 条**

前者是这次清理能保住的最大价值：这些树的分支已经合干净了，看上去「无事可做」，但树里还压着没提交的改动，一旦按「已合」误删就永久丢失。全部原样保留。

| 路径 | 分支 | 未提交 | 领先 main | 末次提交 | 末次提交标题 |
|---|---|---|---|---|---|
| `…/delegate-nebflow-00381b66` | `delegate/delegate-nebflow-00381b66` | 7 | 0 | 2026-10-30 09:16 | Merge branch 'delegate/delegate-nebflow-74e637c4' |
| `…/delegate-nebflow-03e396b4` | `delegate/delegate-nebflow-03e396b4` | 7 | 0 | 2026-10-30 02:59 | Merge branch 'delegate/delegate-nebflow-fbbd4ee3': main 尖既有测试红归因修复批（me |
| `…/delegate-nebflow-064972f7` | `delegate/delegate-nebflow-064972f7` | 7 | 0 | 2026-10-30 11:08 | Merge branch 'delegate/delegate-nebflow-acc72f03': preset→model-chain  |
| `…/delegate-nebflow-09fffbda` | `delegate/delegate-nebflow-09fffbda` | 5 | 0 | 2026-10-31 17:27 | feat(mobile): 悬浮钮统一 48px g2-fab 基准 + 全钮 spring 按压 + 消息/联系人页展开式搜索（作者令 1 |
| `…/delegate-nebflow-0cda5510` | `delegate/delegate-nebflow-0cda5510` | 2 | 0 | 2026-11-01 22:30 | chore(merge-hygiene): 合并轮证据刷新——6438f440b 合并树上四套复测（muse-restore-smartcr |
| `…/delegate-nebflow-147dadf8` | `delegate/delegate-nebflow-147dadf8` | 1 | 0 | 2026-10-31 12:17 | feat(web): @ // 候选浮层换装墨族+液态玻璃设计语言（作者令 2026-10-31） |
| `…/delegate-nebflow-164aec7e` | `delegate/delegate-nebflow-164aec7e` | 24 | 0 | 2026-10-31 11:31 | Merge branch 'delegate/delegate-nebflow-10b6f701' |
| `…/delegate-nebflow-1818bb74` | `delegate/delegate-nebflow-1818bb74` | 8 | 0 | 2026-11-02 13:45 | fix(quote): 文字引用牌图标改用 composer footer 引用入口键同一枚 SVG（作者令 2026-11-02 12:5 |
| `…/delegate-nebflow-189e295a` | `delegate/delegate-nebflow-189e295a` | 189 | 0 | 2026-10-19 17:06 | docs(evidence): resend-dedup #569 复跑读数更新 + 并行批踩踏事故申报 |
| `…/delegate-nebflow-18d8fe69` | `delegate/delegate-nebflow-18d8fe69` | 1 | 0 | 2026-11-01 17:19 | Merge branch 'delegate/delegate-nebflow-9fcc3f05' |
| `…/delegate-nebflow-1ac734ef` | `delegate/delegate-nebflow-1ac734ef` | 2 | 0 | 2026-10-29 08:17 | docs(ask): 设计稿 §2/§8 可见性口径注记——入口只在文件型标签页挂载显示（作者缺陷报 2026-10-29） |
| `…/delegate-nebflow-1f4dbedf` | `delegate/delegate-nebflow-1f4dbedf` | 3 | 0 | 2026-11-01 03:53 | Merge branch 'delegate/delegate-nebflow-67391c31' |
| `…/delegate-nebflow-22c29d3d` | `delegate/delegate-nebflow-22c29d3d` | 2 | 0 | 2026-10-29 00:59 | fix(gateway)+test(e2e): 孤儿 IO 日志双修（兑换/判词行从未发射）+ 隔离引擎真浏览器 e2e 全绿 + 指纹 s |
| `…/delegate-nebflow-2637ab1d` | `delegate/delegate-nebflow-2637ab1d` | 21 | 0 | 2026-10-23 23:05 | docs(spec-hygiene): REPORT 回填 social-panel 换代（§2b #20/§4/§5）+ pw 终态 |
| `…/delegate-nebflow-298b4ddb` | `delegate/delegate-nebflow-298b4ddb` | 3 | 0 | 2026-11-02 08:27 | Merge branch 'delegate/delegate-nebflow-43af6929' |
| `…/delegate-nebflow-2be86ba4` | `delegate/delegate-nebflow-2be86ba4` | 1 | 0 | 2026-10-31 11:31 | Merge branch 'delegate/delegate-nebflow-10b6f701' |
| `…/delegate-nebflow-35eeaf1e` | `delegate/delegate-nebflow-35eeaf1e` | 2 | 0 | 2026-11-02 13:47 | test(todo): legacy chain 三套件终跑账（24 passed / 1 failed = 既有红，73c305f81 同 |
| `…/delegate-nebflow-432df36a` | `delegate/delegate-nebflow-432df36a` | 9 | 0 | 2026-11-01 03:53 | Merge branch 'delegate/delegate-nebflow-67391c31' |
| `…/delegate-nebflow-43af6929` | `delegate/delegate-nebflow-43af6929` | 1 | 0 | 2026-11-02 08:26 | mobile: 消息/联系人两 tab 常驻顶部搜索条，右上搜索浮钮退役（作者令 2026-11-02 07:13） |
| `…/delegate-nebflow-48dfd701` | `delegate/delegate-nebflow-48dfd701` | 9 | 0 | 2026-10-29 22:22 | settings-panel-jev-imagegen 批②③件：JeV 与生图的「测试可用性」钮（作者令 2026-10-29 17:53 |
| `…/delegate-nebflow-491a5f27` | `delegate/delegate-nebflow-491a5f27` | 1 | 0 | 2026-11-02 08:39 | chore(merge-hygiene): absorb untracked generated artifacts (evidence/, |
| `…/delegate-nebflow-5017517a` | `delegate/delegate-nebflow-5017517a` | 6 | 0 | 2026-10-29 18:17 | Merge branch 'delegate/delegate-nebflow-3021b8e1' |
| `…/delegate-nebflow-5856419d` | `delegate/delegate-nebflow-5856419d` | 1 | 0 | 2026-10-31 23:44 | 撤除手机端联系人页顶「手机接入」入口（作者 10-31 22:55 令，仅手机端） |
| `…/delegate-nebflow-5d5aa2be` | `delegate/delegate-nebflow-5d5aa2be` | 3 | 0 | 2026-11-02 12:01 | Merge branch 'delegate/delegate-nebflow-8c0b5ca5' |
| `…/delegate-nebflow-5e45698c` | `delegate/delegate-nebflow-5e45698c` | 4 | 0 | 2026-10-28 20:58 | fix(web): 思考中计时改读阶段锚 phaseStartAt——内部重试/重连不重置，阶段切换才归零（thinkingtimer-fi |
| `…/delegate-nebflow-5fa55cde` | `delegate/delegate-nebflow-5fa55cde` | 11 | 0 | 2026-10-24 09:00 | Merge branch 'delegate/delegate-nebflow-0e1c9e82' |
| `…/delegate-nebflow-5fb4d5e4` | `delegate/delegate-nebflow-5fb4d5e4` | 4 | 0 | 2026-10-24 16:45 | fix(web): 排队消息逐条投递语义收口——投递未决闸（作者报障 2026-10-24 15:45 逐字「排队的消息一次性发送…应该是你 |
| `…/delegate-nebflow-6aae2fd2` | `delegate/delegate-nebflow-6aae2fd2` | 5 | 0 | 2026-10-22 20:09 | docs(report): 子智能体面板行显示名修复批收口报告 |
| `…/delegate-nebflow-6c055b42` | `delegate/delegate-nebflow-6c055b42` | 4 | 0 | 2026-10-23 15:31 | fix(web): 删除批——对话框上方「活动」面板整链移除（作者令 2026-10-23 15:13 逐字「这个活动面板我不是让删掉吗，就 |
| `…/delegate-nebflow-6e4185d6` | `delegate/delegate-nebflow-6e4185d6` | 5 | 0 | 2026-10-22 18:40 | fix(web): 排队呈现回退批（作者回访令 2026-10-22「怎么还出现了发送了的消息还在队列里的问题。我觉得这个可能需要回退，已收 |
| `…/delegate-nebflow-7297e18b` | `delegate/delegate-nebflow-7297e18b` | 2 | 0 | 2026-11-02 12:42 | feat(quote): 引用入口移入 footer + 引用牌类型图标化（作者令 2026-11-02 11:00） |
| `…/delegate-nebflow-76727b4a` | `delegate/delegate-nebflow-76727b4a` | 2 | 0 | 2026-10-29 14:30 | style(console): 错误态/冻结态对话框极简化（作者令 2026-10-29 13:51「往简约的方向走 结合stand的风格  |
| `…/delegate-nebflow-76aa2f3c` | `delegate/delegate-nebflow-76aa2f3c` | 1 | 0 | 2026-10-28 14:36 | reports: dbm 首个 MCP 插件挂载试点——validate 过闸+离线装载+stdio 全链路往返实证 |
| `…/delegate-nebflow-7961c23f` | `delegate/delegate-nebflow-7961c23f` | 7 | 0 | 2026-10-22 16:39 | Merge diff 摘要 chip 右对齐微调批（作者回访令 2026-10-22「把diff胶囊靠右对齐」——.nf-diff-chip |
| `…/delegate-nebflow-7966cb44` | `delegate/delegate-nebflow-7966cb44` | 1 | 0 | 2026-11-01 17:26 | Merge branch 'delegate/delegate-nebflow-27f4c58b' |
| `…/delegate-nebflow-7c249f3f` | `delegate/delegate-nebflow-7c249f3f` | 4 | 0 | 2026-10-30 02:31 | sw.js navigate 腿 /api 零接触次序修复 + INDEX_CACHE 只记真索引导航（console-load-perf  |
| `…/delegate-nebflow-7ceafc0d` | `delegate/delegate-nebflow-7ceafc0d` | 1 | 0 | 2026-10-30 21:28 | fix(冷启动调试批①-⑧,2026-10-30 作者实证): 图片查看器默认非拖拽(抓手钮/Space门)+控件hover完整材质+毛玻璃 |
| `…/delegate-nebflow-7e5196ed` | `delegate/delegate-nebflow-7e5196ed` | 3 | 0 | 2026-10-28 17:08 | fix(canvas): 三件 UI/UX 修（作者令 2026-10-28 16:0x）——①卡窗胶囊行对齐面板同款+水平居中（合并钮改  |
| `…/delegate-nebflow-7fa8a6bb` | `delegate/delegate-nebflow-7fa8a6bb` | 2 | 0 | 2026-11-01 21:54 | feat(mobile): dock 悬浮胶囊批——通栏底条换 Apple 风玻璃胶囊 + 输入卡呼吸缝（作者令 2026-11-01 21 |
| `…/delegate-nebflow-824276f8` | `delegate/delegate-nebflow-824276f8` | 15 | 0 | 2026-10-31 11:31 | Merge branch 'delegate/delegate-nebflow-10b6f701' |
| `…/delegate-nebflow-87290cd9` | `delegate/delegate-nebflow-87290cd9` | 2 | 0 | 2026-10-29 18:17 | Merge branch 'delegate/delegate-nebflow-3021b8e1' |
| `…/delegate-nebflow-87cbdee0` | `delegate/delegate-nebflow-87cbdee0` | 1 | 0 | 2026-10-31 22:09 | chore(merge-hygiene): absorb spec-regenerated evidence shots（capsule-s |
| `…/delegate-nebflow-89580610` | `delegate/delegate-nebflow-89580610` | 25 | 0 | 2026-11-02 21:27 | Merge branch 'delegate/delegate-nebflow-f4d3e0f8' |
| `…/delegate-nebflow-8b2f50d3` | `delegate/delegate-nebflow-8b2f50d3` | 22 | 0 | 2026-10-31 10:46 | test(web): 刷新 checkJs type-baseline — reader/msgQuote 批欠账（非本批引入） |
| `…/delegate-nebflow-991f7f0f` | `delegate/delegate-nebflow-991f7f0f` | 1 | 0 | 2026-11-02 11:20 | Merge branch 'delegate/delegate-nebflow-ea997ea1' (批文档补 vault 校验读数) |
| `…/delegate-nebflow-9a522ebe` | `delegate/delegate-nebflow-9a522ebe` | 5 | 0 | 2026-10-25 13:36 | 胶囊固定尺寸批：顶栏胶囊全脸固定 180×36（token 单源）+ 超长 ellipsis 收口 |
| `…/delegate-nebflow-9fa60def` | `delegate/delegate-nebflow-9fa60def` | 2 | 0 | 2026-10-31 22:07 | fix(mobile): 字号放大批收口手机端（作者 21:36 追加令）——早前 12:50『联系人/对话字体放大（微信对标）』批确认口径 |
| `…/delegate-nebflow-a26da039` | `delegate/delegate-nebflow-a26da039` | 17 | 0 | 2026-10-22 06:59 | Merge branch 'delegate/delegate-nebflow-c00e0fd9' |
| `…/delegate-nebflow-a77a4e54` | `delegate/delegate-nebflow-a77a4e54` | 1 | 0 | 2026-10-31 17:34 | fix(web): 桌面边距还原批（作者令 2026-10-31 17:15「电脑端边距不需要调整，还原成之前靠边的形态」）——考古定位 5 |
| `…/delegate-nebflow-add4c91e` | `delegate/delegate-nebflow-add4c91e` | 14 | 0 | 2026-10-31 22:40 | Merge branch 'delegate/delegate-nebflow-b1991fb6' |
| `…/delegate-nebflow-b21e2edf` | `delegate/delegate-nebflow-b21e2edf` | 4 | 0 | 2026-10-30 20:41 | 阅读器 P1 统一内核批（锚 设计稿20261030_181655 §2.2/§2.3/§3.1/§4.1）：底栏重排（滑杆取代纯文本+TO |
| `…/delegate-nebflow-bd39ddf7` | `delegate/delegate-nebflow-bd39ddf7` | 8 | 0 | 2026-10-25 18:51 | feat(canvas): 文件页签右下刷新钮 + 查看源码提速（canvas 文件刷新批，作者令 2026-10-25） |
| `…/delegate-nebflow-bd54b0ee` | `delegate/delegate-nebflow-bd54b0ee` | 2 | 0 | 2026-10-31 16:01 | feat(settings): 档参数覆盖人话文案批 + 接线审计（作者令 10-31「我还是不懂这些设置会对你的行为造成什么影响」） |
| `…/delegate-nebflow-bf049776` | `delegate/delegate-nebflow-bf049776` | 5 | 0 | 2026-10-31 16:29 | fix(mobile): 手机端 AskUser 白屏批（作者 10-31 15:38 截图令）——chat-top-clip 垃圾锚点 + |
| `…/delegate-nebflow-bf078e5e` | `delegate/delegate-nebflow-bf078e5e` | 5 | 0 | 2026-11-01 21:22 | docs(recorder): 批报告补邻接回归读数与存量红归属实验（7 失败=基线存量，非本批） |
| `…/delegate-nebflow-bfad8ba9` | `delegate/delegate-nebflow-bfad8ba9` | 7 | 0 | 2026-11-01 09:13 | Merge branch 'delegate/delegate-nebflow-4a58b3db' |
| `…/delegate-nebflow-cd833b36` | `delegate/delegate-nebflow-cd833b36` | 32 | 0 | 2026-10-19 13:19 | Merge branch 'delegate/delegate-nebflow-9ecb332f' |
| `…/delegate-nebflow-ce004aa9` | `delegate/delegate-nebflow-ce004aa9` | 3 | 0 | 2026-11-01 11:11 | feat(takeover+entry): 作者令批（11-01 10:07 三件）——接管必达/卡片同族/入场加载覆盖 |
| `…/delegate-nebflow-d2fc050f` | `delegate/delegate-nebflow-d2fc050f` | 4 | 0 | 2026-10-31 13:14 | feat(web): Canvas 改造批十条（作者令 2026-10-31 09:25，液体玻璃 v2 风格闸门下）——①canvas 控 |
| `…/delegate-nebflow-d71b0ea4` | `delegate/delegate-nebflow-d71b0ea4` | 15 | 0 | 2026-11-01 03:53 | Merge branch 'delegate/delegate-nebflow-67391c31' |
| `…/delegate-nebflow-d78d3e3f` | `delegate/delegate-nebflow-d78d3e3f` | 1 | 0 | 2026-10-28 18:18 | Merge branch 'delegate/delegate-nebflow-96cbcbf8' |
| `…/delegate-nebflow-dcc13390` | `delegate/delegate-nebflow-dcc13390` | 1 | 0 | 2026-10-30 09:07 | reports: 杂项运维小批三件批报告（cmdcode 摘除/flowmap KEEP 归档 2 留 35/design-language |
| `…/delegate-nebflow-e7c1a868` | `delegate/delegate-nebflow-e7c1a868` | 6 | 0 | 2026-11-04 10:51 | merge: main@56720568e → delegate/delegate-nebflow-e7c1a868 |
| `…/delegate-nebflow-e90a4202` | `delegate/delegate-nebflow-e90a4202` | 4 | 0 | 2026-10-23 16:08 | fix(web): 排队消息空闲不自动投递修复批（作者报障 2026-10-23 15:10 逐字「我发的消息进入排队队列之后，有时候，你完 |
| `…/delegate-nebflow-ea997ea1` | `delegate/delegate-nebflow-ea997ea1` | 2 | 0 | 2026-11-02 11:20 | docs(mobile): 标签环对齐修正批文档补 vault 校验读数（152 篇零违规 + 未入库 orphan 说明） |
| `…/delegate-nebflow-eed3ad45` | `delegate/delegate-nebflow-eed3ad45` | 2 | 0 | 2026-11-01 18:00 | docs(wiki): 文档库校验清零——17:25 面板标题 B 批文档补「样张」词条链 + 两条正文引用（R3 首现未释 / orpha |
| `…/delegate-nebflow-f1fc2f78` | `delegate/delegate-nebflow-f1fc2f78` | 2 | 0 | 2026-11-02 10:45 | docs(mobile): 插件面板让位+一行化批 —— 合入 main 后复跑账本 + 主工作区孤儿文档核查口径 |
| `…/delegate-nebflow-f23846aa` | `delegate/delegate-nebflow-f23846aa` | 17 | 0 | 2026-10-29 17:41 | Merge branch 'delegate/delegate-nebflow-f52794b2' |
| `…/delegate-nebflow-f6d3906f` | `delegate/delegate-nebflow-f6d3906f` | 4 | 0 | 2026-10-31 09:37 | mobile long-press msg menu + quote attachment thumbs (author 10-31) |
| `…/delegate-nebflow-f7c28d49` | `delegate/delegate-nebflow-f7c28d49` | 1 | 0 | 2026-10-31 23:59 | feat(mobile): dock 收形批六件——消息流头像+agent 名恢复 / 中键去文字 / stand logo 回落 / 边缘 |
| `…/delegate-nebflow-f8815d4c` | `delegate/delegate-nebflow-f8815d4c` | 2 | 0 | 2026-11-02 07:52 | feat(mobile): 手机端交互标准批——token 单源 + 18 面全量套用 + 取消加载图标语义修正 |
| `…/delegate-nebflow-f91c3ca6` | `delegate/delegate-nebflow-f91c3ca6` | 12 | 0 | 2026-11-01 19:33 | feat(web): ask 输入行为包批——文字输入逻辑统一包（IME 回车统一 + ask interrupt 状态） |
| `…/delegate-nebflow-fbf9b18e` | `delegate/delegate-nebflow-fbf9b18e` | 1 | 0 | 2026-10-31 13:29 | feat(friends): 联系人/消息列表条目字号放大批——对标微信档（作者 10-31 12:50 令） |
| `…/delegate-nebflow-fe4f891e` | `delegate/delegate-nebflow-fe4f891e` | 4 | 0 | 2026-10-20 17:49 | 本地加速M1引擎侧: 同机近路四件套 (local-ticket会话腿绑定防localhost任意读/健康探测面环回deviceId+门户C |

## 6. `/private/tmp` 之外的遗留

清掉 **22** 个目录，共 **8254MB**。判定口径：mtime < 2026-11-04（不是今天在飞的批次）、无活进程占用、顶层无 `.git`（纯拷贝，内容可由 git 复原）。

| 目录 | 大小 |
|---|---|
| `/private/tmp/merge-eed3ad45` | 1258MB |
| `/private/tmp/basechk2` | 567MB |
| `/private/tmp/basechk` | 566MB |
| `/private/tmp/qi-before` | 552MB |
| `/private/tmp/baseline-prebatch` | 516MB |
| `/private/tmp/nb-head-tree` | 515MB |
| `/private/tmp/qa-fleet-probe5` | 471MB |
| `/private/tmp/x1367-cmp` | 439MB |
| `/private/tmp/x1367-cmp2` | 439MB |
| `/private/tmp/p1-iso2` | 414MB |
| `/private/tmp/base-tree` | 407MB |
| `/private/tmp/kb-batch-head` | 395MB |
| `/private/tmp/qa-fleet-probe3` | 350MB |
| `/private/tmp/zq-recon` | 261MB |
| `/private/tmp/mob-bisect` | 243MB |
| `/private/tmp/p1-verify-4b756df9` | 146MB |
| `/private/tmp/qa-fleet-smoke2` | 127MB |
| `/private/tmp/qa-fleet-probe4` | 123MB |
| `/private/tmp/stand-coldstart-console-profile-69971` | 117MB |
| `/private/tmp/qa-fleet-probe` | 116MB |
| `/private/tmp/qa-fleet-smoke` | 116MB |
| `/private/tmp/qa-fleet-patch` | 116MB |

**明确保留**：
- `/private/tmp/x1367-restored`、`/private/tmp/x1367-backup` —— 内含 `restored-3dirs.tar` / `tracked-3dirs.tar`，是备份性质的唯一副本，删了没法从 git 复原，不碰。
- `/private/tmp/cc-before`、`sbtbench-1/2/3` —— 有活进程 cwd 在里面。
- `/private/tmp` 下今天（2026-11-04）产生的目录 —— 属在飞批次。
- 10 个登记为 Stand worktree 的 `/private/tmp/*` 树（capsule-before、verify-main-baseline、g2-base-check、lg-current、lg-baseline、np-baseline、bashoverhaul-baseline-aade799e 等）—— 是 detached HEAD 快照，脏文件只有 `evidence/` 下重跑出来的 PNG 截图，`git worktree remove` 无 `--force` 会被拒；按「不用 --force」的约束原样留下，见 §4.4。

## 7. stand-website（一并盘）

仓库：`/Users/kaiyu/.stand/projects/stand-website`，main tip `3208de7`。

| 类 | 条数 | 处理 |
|---|---|---|
| 干净且已合 main | 7 | **已删** |
| 活会话（15a9cf94 standby 0.2h、c102e6d0 standby 1.7h） | 2 | 保留 |
| 有未提交改动 | 3 | 保留 |
| 孤儿 `stand-cloud-login-logo` | 1 | prune 清掉 |
| **合计** | **13** | 登记数 14 → 6 |

保留的 3 条有未提交改动：`delegate-nebflow-175edbf8`(1)、`staging-lane`(3)、`delegate-nebflow-8bb9284a`(1)。

> 项目本体 `/Users/kaiyu/.stand/projects/*` 一律没动。stand-website 主工作树仅有一处 `?? .stand/`（注册目录未忽略），属正常。

## 8. 边界自查

| 约束 | 结果 |
|---|---|
| 主仓工作树全程干净 | `git status --porcelain` = **0 行** ✅ |
| 不动 main 分支 | 未切分支、未 commit 到 main、**未 push** ✅ |
| 不删分支 | 只删 worktree 目录与元数据；`git branch` 一条未删 ✅ |
| 不用 `--force` | 全程无 `--force`；凡 git 拒绝即转保留 ✅ |
| 不删 `~/.stand/projects/*` 项目本体 | 未动 ✅ |
| 不重启/不 kill 进程 | 只做 `ps`/`lsof` 读操作 ✅ |

## 9. 剩余 119 棵树的三类计数

| 类 | 条数 |
|---|---|
| `clean_merged` | 0 |
| `dirty` | 91 |
| `unmerged` | 2 |
| `detached` | 10 |
| `orphan` | 0 |
| `live` | 16 |
| `probe_failed` | 0 |
| **合计** | **119** |

