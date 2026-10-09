# 分支清算留痕清单（作者六裁清算令 · 2026-10-06）

执行者：清算执行批节点（机械执行 + 全程留痕）。本文件与执行节点回执同内容。

## 0. 执行环境与红线遵守

- 仓：/Users/kaiyu/Claude code/Nebflow，全程检出分支 executor-registry（未切换）。
- 零新建 commit、零 push、零 git config 改动；唯一元数据清理 = 末尾一次 `git worktree prune`。
- 名单外零触碰：stable/*（含 /Users/kaiyu/.nebflow-stable/ 下 5 个 stable worktree）、其余全部分支/worktree；chatmsgops-impl（分支 + worktree）全程未动。
- 名单口径：作者令标题「删除 13 支」、红线「18 支」与实际列举（15 删 + 1 bundle 后删 + 1 保留）不一致，按指示一律以列举名单为准逐支执行。实际删除 16 支，保留 1 支。

## 1. 删除支清单（16 支，含类别 + 删除前 HEAD sha）

### A 类（8 支）
| 分支 | 删除前 HEAD sha | 形态 |
|---|---|---|
| bug/chainledger-boot-oom | 22427230be74a6331318bdb8ef0524da5c8b0768 | 纯分支 |
| discofix-impl | 651759121f3d026f6b21f9213e9bac63b8359979 | worktree（脏，快照后 --force 删） |
| discofix-impl2 | dc6c853d7f8ef2d6b409c09dfbc0f1c9367da780 | worktree（脏，快照后 --force 删） |
| llmstallfix-impl | dc6c853d7f8ef2d6b409c09dfbc0f1c9367da780 | worktree（脏，快照后 --force 删） |
| explorerrt-impl | 7c064266b20f5a5f9ff5471d9da137af26b9af87 | worktree（脏，快照后 --force 删） |
| b2sea-impl | 76297722a90f5eb0384f80ac98109a189b3f24a0 | 纯分支 |
| b64-replay-impl | 34314d299a888a7190c447cde817a300f2a85ef5 | 纯分支 |
| fe-perf-render-opt | c02cf2f392bfaf651423782f40ce9e826462c8a3 | worktree（干净） |

### B 类（5 支）
| 分支 | 删除前 HEAD sha | 形态 |
|---|---|---|
| askuser-prompt-impl | 6ad5be7f95f9b91d683f19f627fa3c8d4b501956 | worktree（干净） |
| engdef-deadgate-impl | 7df6f55a0131fb4a1b20d72d1f75f8cdf5ee30fd | 纯分支 |
| feiscanbind-impl.bak-pre-refresh | 38e26479897f607495ae230d60cb6151900be24a | 纯分支 |
| llmstallfix-impl2 | 2971bc69304d53c2cffb2f4d490c5bc63c959586 | worktree（干净） |
| feat/ci-desktop-verify | b7c26ec1531a9bb96787473220d1cbb37bbf6644 | 纯分支 |

### C 类（2 支）
| 分支 | 删除前 HEAD sha | 形态 |
|---|---|---|
| canvas100m-impl | 4e9e4989ea2c4984c24c6f0e972f5c2c371a4f19 | worktree（干净） |
| logofavicon-impl | 5cb5c7e4da9ee454737e6c3b7f385f73857f8e76 | worktree（干净） |

### bundle 后删（1 支）
| 分支 | 删除前 HEAD sha | 形态 |
|---|---|---|
| backup/local-main-e718625e | e718625e52beb3b31537846fde85b5f666b8eeb4 | 纯分支 |

删除顺序：4 个脏 worktree（discofix-impl → discofix-impl2 → llmstallfix-impl → explorerrt-impl，每支 worktree 删后随即 branch -D）→ 5 个干净 worktree（askuser-prompt-impl、canvas100m-impl、fe-perf-render-opt、llmstallfix-impl2、logofavicon-impl）→ 6 支纯分支 → backup/local-main-e718625e（bundle verify 通过后）→ git worktree prune。每支删后 git 报文「曾为 <短sha>」均与删除前 rev-parse 记录一致。

## 2. 快照（阶段 1，先于一切删除动作）

- 落位根：/Users/kaiyu/Claude code/Nebflow/evidence/20261006_branch-cleanup-6verdicts/
- 快照时间：2026-10-06 16:28 CST
- 范围 = 将删 worktree 内全部未提交修改 + 未跟踪文件；盘点确认仅 4 个脏 worktree（与任务书已知线索一致），其余 5 个 worktree `git status --porcelain` 全空，无额外脏件。

### 快照目录逐条
1. /Users/kaiyu/Claude code/Nebflow/evidence/20261006_branch-cleanup-6verdicts/discofix-impl+discofix-impl2/ —— 5 文件 + README.md（双署名）
2. /Users/kaiyu/Claude code/Nebflow/evidence/20261006_branch-cleanup-6verdicts/llmstallfix-impl/ —— 12 文件 + README.md（9 M + 3 ??，即 338 行旧中间体）
3. /Users/kaiyu/Claude code/Nebflow/evidence/20261006_branch-cleanup-6verdicts/explorerrt-impl/ —— 4 文件 + README.md（2 M + 2 ?? 草稿）

### discofix 对同份证实
两 worktree 脏文件路径一致（5 个 M）；逐文件 `diff` 比对字节级相同 ⇒ 一份快照、README 双署名（目录名 `discofix-impl+discofix-impl2`）。快照复制自 discofix-impl worktree，核对时同时对照两 worktree 源文件（10 组 cmp 全 OK）。附注：impl2 worktree 独有的 AttachmentPreviewContract.scala 属其 HEAD（dc6c853d7）已提交内容（blob e733701c…），非脏件，不入快照。

### 快照核对记录
- 21 个快照文件逐文件 `cmp` + `stat` 字节数双端比对：21/21 全 OK，零差异（逐文件字节数见各目录 README.md 表格）。
- 核对通过后才进入阶段 2/3，满足红线「核对不过 ⇒ 停」。

## 3. bundle 存档（阶段 2）

- 绝对路径：/Users/kaiyu/Claude code/Nebflow/evidence/20261006_branch-cleanup-6verdicts/backup-local-main-e718625e.bundle
- 大小：93,199,372 字节（≈ 90 MB）
- `git bundle verify` 结论：通过 —— 含 refs/heads/backup/local-main-e718625e = e718625e52beb3b31537846fde85b5f666b8eeb4，记录完整历史，哈希算法 sha1。verify 通过后方删该支。

## 4. 与名单的差异

- 名单 17 支全部存在，无缺支、无中途消失，无需记录跳过项。
- 盘点与分发器预读一致：15 支全存在；9 支挂 worktree（.nebflow/worktrees/<同名>）；6 支纯分支。
- 线索核对：discofix-impl 与 impl2 脏件同份（已证实）、llmstallfix-impl 338 行旧中间体（12 文件）、explorerrt-impl 2 个未跟踪草稿 —— 全部吻合。
- 观察项（不影响执行）：llmstallfix-impl 与 discofix-impl2 删除前 HEAD 同为 dc6c853d7f8ef2d6b409c09dfbc0f1c9367da780。

## 5. 终态佐证

- `git branch --list`：名单内 17 支仅剩 chatmsgops-impl；其余 16 支全部 GONE。
- `git worktree list`（删后）：9 个名单内 worktree 全部移除；chatmsgops-impl worktree（5e5fbe757）原样保留；stable/* 与名单外 worktree 全部原样。
- 主仓检出分支仍为 executor-registry，HEAD 0adfef219 未动。

## 6. 本目录文件清单

- MANIFEST.md（本文件）
- backup-local-main-e718625e.bundle
- discofix-impl+discofix-impl2/（README.md + 5 快照件）
- llmstallfix-impl/（README.md + 12 快照件）
- explorerrt-impl/（README.md + 4 快照件）
- 共 25 文件，约 90 MB。
