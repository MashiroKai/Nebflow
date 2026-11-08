AUDIT: FAIL（2 项失败：#1 保全提交标题格式 38/94 不合模板；#14 第六次基座移动未落笔）

# 脏工作树保全二轮 —— 机械复核报告（audit-round2）

审计者：r2-audit 节点。**只判不修**：本节点未 checkout / reset / clean / stash / commit / merge / remove 任何树，未删分支、未动 main、未 kill 进程、未读写 `/private/tmp/x1367-*`、未写 vault 文档。
被审对象 = `reports-evidence/worktree-salvage__20261104/`（worktree `delegate-nebflow-750143b4` @ `7991150f8`，`status --porcelain` = 0 行）下的 `inventory-round2.md`、`shards/*.tsv`、`shards/recon.md`、`shards/force-discard-round2.tsv`。

## 0 审计基线（现算，不写死）

| 项 | 读数 | 命令 |
|---|---|---|
| 现算 main tip | **`d989779a8`**（2026-11-04 19:45 后现算） | `git -C "/Users/kaiyu/Claude code/Stand" rev-parse main` |
| 被审报告自记 判词基线 | `0fffa26b5` | inventory-round2.md L7 |
| 被审报告自记 重验基线 | `a0597efdd` | inventory-round2.md L8 |
| main 首父链（自 `0fffa26b5`） | `d1d669aab → 9be1797fd → f3bc0a7be → 2996e6fbb → a0597efdd → d989779a8` = **6 动** | `git log --first-parent --oneline 0fffa26b5..main` |
| 主仓工作区 | `status --porcelain` = **0 行** | 同上 |
| 分支数 | **400** | `git branch \| wc -l` |
| 工作树登记 | **25** 条 | `git worktree list \| wc -l` |

> 因 100 棵树已删，第 12 项一律用 **分支 ref** 复算（`git -C <main> ... <branch>`）。等价性已验证：94 个 salvage 提交的父提交 == tsv 的 `pre_head`（94/94），且 98 个分支名在 `git branch` 中 0 缺。

---

## 1 第1项 · 保全提交真实存在且是真保全 —— **FAIL**（子项 1b 失败；1a/1c 通过）

**1a 存在性 PASS**：tsv 中 `commit_sha != "-"` 的 94 行，`git cat-file -e <sha>^{commit}` **94/94 成功**，去重后 **94 个不同提交**，0 个非 commit 对象。

**1b 标题格式 FAIL**：按模板 `salvage(<树名>): 未提交改动保全 — …（原批次 …，末次活动 …）` 逐条匹配 `git show -s --format='%s'`：

| 类别 | 条数 | 现象 |
|---|---|---|
| 完全合规 | **56** | 树名 + 中文标题 + `（原批次 <id>，末次活动 <YYYY-MM-DD>）` 齐全 |
| A · 中段 JSON 转义（全在 `dirty-1` 分片） | **19** | 提交对象里存的是字面 `\u672a\u63d0\u4ea4\u6539\u52a8\u4fdd\u5168 \u2014 …`（`git log --oneline` 看到的是转义串，不是中文）。树名与尾部 `（原批次 …，末次活动 …）` 正常 |
| B · 尾部元数据丢失（全在 `dirty-2` 分片） | **19** | 尾部被截断成 `（原批次 ¼，末次活动 ¼）`（U+FF0C / U+FF09 的 `0xEF` 首字节丢失后再被 latin-1→UTF-8 二次编码），**原批次 id 与末次活动日期两个值在标题里完全不存在** |

证据（字节级，非终端编码问题）：

```
$ git -C <main> log -1 --format='%s' 5268a9fd1 | xxd | head -3
00000000: 7361 6c76 6167 6528 ... 3632 293a 205c 7562  salvage(delegate-nebflow-00381b66): \u...
00000020: 3629 3a20 5c75 3637 3261 5c75 3633 6430       6): \u672a\u63d0\u
$ git -C <main> log -1 --format='%s' f5927cd64 | xxd
... 5c 78 28? → 实为 0xc2 0xbc 0xc2 0x8c …（“（原批次 ¼，末次活动 ¼）”）
```

A 类 19 棵（`dirty-1`）：`00381b66→5268a9fd1`、`0cda5510→cb7a940e8`、`189e295a→4256fb5b8`、`22c29d3d→9a76f9861`、`35eeaf1e→1429de0c3`、`491a5f27→414cb4875`、`5e45698c→3687269e4`、`6cbd7d08→4aceaa489`、`7961c23f→95602846e`、`7fa8a6bb→86d0e2b3c`、`89580610→dc4d05632`、`9b828eae→ef91dca17`、`ae1c2d58→bc27158c3`、`bf049776→22cf27045`、`cd833b36→d23bc58f1`、`d8f43803→be70d031e`、`ea997ea1→3fbc368a6`、`f6a21b25→6bf401929`、`fbe4618c→b0da1fca1`
B 类 19 棵（`dirty-2`）：`03e396b4→36f9f65ae`、`147dadf8→f5927cd64`、`18d8fe69→184d0f521`、`2637ab1d→4dda2ede6`、`432df36a→15ec4f369`、`5017517a→d910003fa`、`5fa55cde→faed9ca5f`、`6e4185d6→f83095e9a`、`7966cb44→7a2c4141c`、`824276f8→d474e8473`、`8b2f50d3→ca51e555f`、`9fa60def→31914f5b5`、`b21e2edf→2bd2f2d9e`、`bf078e5e→a3685eaf9`、`ce004aa9→bd61454df`、`dcc13390→036aa33a1`、`eed3ad45→eb472e46c`、`f6d3906f→c6b0cb2f5`、`fbf9b18e→4f34355cb`

**1c 文件数 PASS（差额已解释）**：`git show --name-only --format='' <sha> | wc -l` 与 tsv `kept_files`：**86 行精确相等**，8 行不等，且差额合计 **+110**（7+1+7+20+21+12+36+6），与 §1 自述「28,907（分片）/ 29,017（git 展开），差额 110 = dirty-2 按 porcelain 行计（121 行 → 231 文件）」**完全吻合**。这 8 行的 tsv notes 各自都写了「dir 展开后 N 文件」，N 与实测一致。`git show --stat` 末行数字与 `--name-only` 计数同值（8 行同为 8/2/28/35/43/14/38/7）。

**建议修法（本节点不执行）**：38 个提交均非 main 祖先、父提交 = `pre_head`，可安全 `git commit --amend`：把提交信息先写进 UTF-8 文件再用 `git commit -F <file>`（A 类重新生成中文正文；B 类补回 `原批次 <树名短 id>，末次活动 <YYYY-MM-DD>`），随后同步回写 `shards/dirty-1.tsv`/`dirty-2.tsv` 的 `commit_sha`、§2 主表对应 38 行与 §2b 的 sha。若判为超范围，至少须在 §6 边界自查里把「38/94 标题不合模板」登记为已知缺陷，不能默认「94 条标题全部合规」。

---

## 2 第2项 · 没把垃圾提交进去 —— **PASS**

- 对 94 个提交逐个 `git show --name-only --format='' `，合计 **29,017 条路径**，按 `node_modules/ target/ .bloop/ .metals/ .bsp/ dist/ build/ out/ coverage/ .cache/ .DS_Store *.class *.log *.tmp *.pyc *.swp` 全模式匹配：**命中 0 条**。
- 全量 blob 体积（`git cat-file --batch-check`，29,017 个 (commit,path)，解析 28,701，未解析 316 全为含非 ASCII 的空格引号路径——审计脚本同类名解析限制，非仓库问题）：**最大 blob = 2,061,736 B = 1.97 MiB**（`reports/20261023_195528_delegate-fullflow-audit.html`），**> 5 MiB 的 blob = 0 个**（抽查升级为全扫）。
- 抽 3 个提交的 `cat-file -s`（`f643598da`、`ef91dca17`、`208a1d228`）与其最大文件一致，结论同上。

---

## 3 第3项 · 内容等价自证成立 —— **PASS**

- 结构式「保全 + 判废 == 提交前 porcelain 行数」：`dirty-1` 19 行逐行以 `核账 OK: 保全X+判废Y=Z == 提交前基线Z行` 明写，X/Y 与 kept/junk 相等、且 X+Y==Z==Z（**19/19 自洽**）。其余分片用等价但不同措辞（`porcelain 2 行=展开 1501，kept+junk=1501`、`展开 981 文件后 952+29=981 成立`、`MISMATCH(porcelain 5 vs kept+junk 28)… 按 -uall 展开 28=28+0 成立`、`等式成立 3=3+0`），逐行抽验「声明的合计」与 kept+junk：**除 dirty-2 的 8 行（即第1项 1c 的 +110，已解释）外无一处不等**。
- 目录折叠的展开已按 `-uall` 口径处理（例：`d71b0ea4` porcelain 15 → 展开 19；`g2-base-check` porcelain 24 = 23 M + 1 个 `??` 目录 = 30 文件，notes 写明）。
- 因 94 个提交的父提交 == `pre_head`（1a 已验证），`git show --name-only` 恒等于 `git diff --stat <pre_head> <commit_sha>` 的文件清单 ⇒ 抽样 10 棵的要求被**全量 94 棵**覆盖，未发现未解释 MISMATCH。

---

## 4 第4项 · 主表完备 —— **PASS**

- §2 主表数据行 **103 行**（`NF==11` 严格判定，未混入其他表格的 `| 1 |` 行），编号 `1..103` **无缺号、无重号**；`shards/recon.md` §3 待处理 = **103 棵** ⇒ 相等。
- 每行九单元格（`# / 路径 / 分支 / 保全提交 / 保全 / 判废 / 合并判定 / 处置 / 判词依据`）**无一格为空**。
- 判词取值仅 `被取代 46 / 已并入 30 / 需合并 17 / 不确定 10`（合计 103），全部在允许词表内；§1 的分布表与主表**逐值相等**（46/30/17/10）。归一化落地正确：`92944aba` → 已并入、`98f9ca81` → 需合并，且两行 evidence 都保留了 `原文判词：…` 原样。
- 17 条「需合并」行 evidence 长度 **304~528 字符**，均含 cherry 计数 + own delta 文件数/新增行/命中率 + main 缺件举例，**无「只写判词没证据」的行**。
- 交叉核对我复算的 own-delta 代码文件数：`6cbd7d08 10 / f6a21b25 8 / 03e396b4 7 / 2637ab1d 1 / 432df36a 1 / 5017517a 6 / 5fa55cde 11 / 7966cb44 1 / 064972f7 7 / bfad8ba9 7 / fe4f891e 3 / 298b4ddb 1 / 87290cd9 2 / f23846aa 16 / 183b016d 3 / 98f9ca81 31` —— 与主表逐行一致（17/17）。

---

## 5 第5项 · 报告必备节 —— **PASS**

- 首行 = `VERDICT: PASS`（L1）。
- §1 摘要含：删除 **100** / 保留 **3** / 保全提交 **94** / 保全文件 **28,907·29,017·29,015** / 判废丢弃 **716 文件 · 1,856.18 MiB** / `du` 51,709,828→7,573,048 KB（42.1 GiB）/ `df` 可用 69,156,064→115,586,256 KB；处置列统计与之一致（`--force` 19 + 免 force 81 = 已删 100，保留 3）。
- §3「需合并清单」**恰好 5 条**（98f9ca81 / 6cbd7d08 / f6a21b25 / 87290cd9 / 183b016d），每条含「改了什么 + 产品价值 + 为什么没自动合」；另 12 条在 §3b，合计 17 与主表相等。
- §5「我不确定的三类」单列成节，三张表都是 `路径 | 疑点 | 建议处置` 三列，逐条齐备（垃圾还是工作 5 条、是否已并入 6 条、该留该删 6 条）。**未发现替作者拍板的语句**：全篇 grep `已决定|拍板|我决定|就此定|按我的决定|直接采用` = 0 命中；§4 给 C1/C2 两条候选而不选，§5③ 措辞为「若作者确认…下一批可直接删」。

---

## 6 第6项 · 禁则未破 —— **PASS**

- **分支只增不减**：现算 `git branch | wc -l` = **400**；103 棵树涉及的 **98 个分支名 0 缺失**（`comm -23` 空）⇒ 无删除分支。报告 §6 记「开工 400 / 收工 400（recon 基线 399，他批 +1）」。*观察项*：`shards/recon.md` 全文没有分支数读数，399 这个基线在被审批自己的工件里查不到出处（见 §15 观察 1）——但 400 ≥ 任何 399 级基线，且「98 个分支全在」是更强的机械证据。
- **salvage 提交无一成为 main 祖先**：94/94 `git merge-base --is-ancestor <sha> main` 为假，0 例为真。
- **x1367 两个 tarball 在且非 0**：`/private/tmp/x1367-restored/restored-3dirs.tar` **464,652,288 B**（11-02 19:39）、`/private/tmp/x1367-backup/tracked-3dirs.tar` **464,652,288 B**（11-02 19:32）。
- **19 次 `--force` 全部留了清单 + 体积**：`shards/force-discard-round2.tsv` **716 行**（表头 `tree/path/bytes/status/reason`），按树汇总 **19 棵**、每棵件数与体积均与 §2b 表逐行相等（例 `f6a21b25` 85 件 111.82 MiB、`5ca27781` 63 件 111.55 MiB、`e0b069db-c` 1 件 88.88 MiB、`76aa2f3c` 33 件 0.28 MiB），合计 **716 件 / 1,856.18 MiB / 1,946,343,613 B**；`status` 列 716 行全为 `??`（未跟踪残留）。
- 主仓 `status --porcelain` = 0 行，未 merge、未 cherry-pick。

---

## 7 第7项 · 没自删 —— **PASS**

`/Users/kaiyu/Claude code/Stand` 在（`.git` 正常、`rev-parse main` = `d989779a8`）；`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-750143b4` 在（本节点 cwd，HEAD `7991150f8`，porcelain 0 行）。

---

## 8 第8项 · 剩余树终态 —— **PASS**

- 标「保留」3 棵（`git -C <tree> status --porcelain -uall`）：
  - `183b016d` = **0 行**（clean）；
  - `98f9ca81` = **0 行**（clean）；
  - `e7c1a868` = **6 行**，逐条 = tsv 登记的 6 个 `? reports-evidence/20261104_isolated-instance-kill/green-*.log` 判废产物，**无未登记文件**。
- 标「已删」100 棵：`git worktree list --porcelain` 中 **0 条残留**，磁盘上 `-d` 命中 **0/100**。

---

## 9 第9项 · 命名新制落实 —— **PASS**

- 新目录 `reports-evidence/worktree-salvage__20261104/` 存在且已入库（`7991150f8` 树里 35 个文件，含 `inventory-round2.md` 与 `shards/*`）；`git ls-tree -r 7991150f8 | grep 'reports-evidence/20261104_worktree-salvage'` = **0**。旧名目录在 worktree 与 main 树里均不存在。
- `reports-evidence/20261104_worktree-cleanup/`：`git -C <main> status --porcelain -- reports-evidence/20261104_worktree-cleanup` = **0 行**；其 `inventory.md` 未被改写 —— main 侧该目录已被**别的批次**（`ff7089b98`，doc-naming-migration）纯改名成 `worktree-cleanup__20261104_172339/inventory.md`，`git show -M ff7089b98` 记为 **`R100`（相似度 100%、0 insertions / 0 deletions）**，正文零改动。按分发器补充 7，这属命名迁移批的动作、不计本批违规；**本批对它的内容零改动**这一点成立。
- 本批未对 vault 做写操作：`~/.stand/projects/*/.stand/docs/` 下含 `worktree-salvage|保全二轮|salvage(` 的文档 **0 个**（236 个 17:30–20:00 窗口内改动的 vault 文件全部属其他主题，如 legacy-domain-paving / 旧域 callback / prompt-carbon 等）；15 个 `docs/` 孤儿文档以原样放在本批 `rescued-docs/`（未改名、未迁入 vault）。
- 其他批次的 `20261104_*` 旧名目录未计为本批违规（分发器补充 7 第 1 条）。

---

## 10 第10项 · vault 文档没被判废 —— **PASS**

103 行的 `junk_detail` 列（第 7 列）全量扫描：含 `.stand/` 的 **0 行**，含 `docs/` 字样的 **0 行**（比分发器要求的更严格：连证据性提及都没有）。`force-discard-round2.tsv` 的 716 条 `discarded_path` 亦无 `.stand/docs` 路径（全为 `evidence/ reports/ scratch/ *.log *.bundle` 类）。

---

## 11 第11项 · 基线 tip 与 §8 —— **PASS**

- 报告顶部 L7/L8 明写 **分片判词基线 `0fffa26b5`** 与 **重验基线 `a0597efdd`**（并注明「本节点开工时为 `2996e6fbb`」），不写死 sha 的口径写进了正文。
- §8 存在，逐次给出移动面与交集，且**我逐条复算全部命中**：

| 次 | 区间 | 我复算的移动面 | 报告 | 交集主张 | 我复算 |
|---|---|---|---|---|---|
| 1 | `0fffa26b5→d1d669aab` | 99 文件（`src/main` **75** + `reports-evidence` **24**） | 99/75/24 ✅ | 与 5 棵树相交 | `0cda5510:1, 6cbd7d08:3, 164aec7e:2, f23846aa:3, f30e15f7:1` **全部命中** |
| 2 | `d1d669aab→9be1797fd` | 1 文件 | 1 ✅ | 空 | 空 ✅ |
| 3 | `9be1797fd→f3bc0a7be` | 2 文件（`PresenceCandidateProbe.scala` + Spec） | 2 ✅ | 空 | 空 ✅ |
| 4 | `f3bc0a7be→2996e6fbb` | 368 文件 | 368 ✅ | 路径层空、但改写 98f9ca81 证据 1 件 | 见到 `b1-dataroot__20261104_110844/red/red-scala.log`（同 blob）✅ |
| 5 | `2996e6fbb→a0597efdd` | 3 文件（doc-naming 收口） | 3 ✅ | 空 | 空 ✅ |

- 「判词被改写 1 行」= `6e4185d6` 需合并→被取代；我独立复算该主张：5/5 文件在 main，非空行新增 **175** 行中 **170** 行在 main（strip 归一后逐行命中）**= 97.1%**，与报告数字**逐位相同** ⇒ 改写有据。
- 复核基线与现算 tip 不同（`a0597efdd` vs `d989779a8`）本身不判 FAIL（分发器补充 4/5 口径）；但第 6 次移动的落笔义务见第 14 项。

---

## 12 第12项 · 需合并清单逐条用当前 main 复算 —— **PASS**

对 §3（5 条）+ §3b（12 条）= **17 条全部**，用现算 tip `d989779a8` 跑 `git cherry main <branch>`、`merge-base`、own delta（`merge-base..HEAD`）与 `main:<path>` 存在性：

- **17/17 的 `git cherry` 都有 `+`（未入 main 的提交）** ⇒「main 仍零等价/未合」成立，0 条被 `d989779a8` 取代。
- 关键零等价主张逐条验证为真（`git cat-file -e main:<path>` 全部 ABSENT）：`inputHistory.js`、`web/css/mobile-todo-apple.css`、`PresetChainRegistry.scala`、`PresetChainMigrationSpec.scala`、`.probe/probe-history.mjs`、`tests/_spec-floor-sweep.sh`、`tests/flat-button-shots.mjs`、`MergeWindowCare.scala`、`R1RespawnRefusalSpec.scala`、`attFileGlyph.js`。
- 命中率复算（**发现的记账约定**：分母只算「main 里存在」的文件，main 缺的文件单列）——复算与报告逐位一致：
  - `bfad8ba9`：`MsgTokenDeliveryPointsSpec.scala` 600 行 main 不存在；其余 7 文件 **104 新增行 / 38 命中 = 36.5%**（报告 36.5% ✅，§3b「600 行 main 不存在」✅）
  - `87290cd9`：`R1RespawnRefusalSpec.scala` 304 行 main 不存在；`BackoffSupervisor` **87 / 50 = 57.5%**（报告 ✅）
  - `5017517a`：**631 / 217 = 34.4%**（报告 ✅）
  - `6e4185d6`：**175 / 170 = 97.1%**（报告 ✅，即被改判的那一行）
  - `98f9ca81`：own delta **31 代码文件 / 2001 行 / 1186 命中 = 59.3%**（报告 ✅）；`git cherry main` = `----++`（4 已合 + **2 未合**）= 报告的 ahead 6 /「前 4 提交已并入」✅
- **98f9ca81 的判词不是建立在 `diff main..HEAD` 总量上**：全文检索 `664`/`136015`，只在 §8 的「方法论警告」里出现一次（`98f9ca81 上更是 664 文件 / 巨大负差 … 本报告一律不用这个总量作判据`），§3/§4 与 tsv 证据全走 `git cherry` + 己方 delta + 逐文件 `main:<path>` + 逐行命中率 ⇒ 分发器补充 7 第 2 条的禁则**未被触发**。
- §4 的「19 接线面零等价」复算：main `src/` 上 `ownerGate`=0、`writeActiveAccountPointer`=0、`InstanceScope.machineRoot`=0、`LoginCommand.checkLoginAllowed`=0；`OwnerGuard` 仅 `Guards.scala`（定义 2 命中）+ 测试 spec（7 命中，均为 `OwnerGuard.check` 断言，无生产调用点）⇒「账号层未接线」结论成立（报告写「1 文件 2 命中（全是定义）」在 `src/main` 口径下准确，措辞未提 test 文件属轻微不完整）。
- §4 证据归属：`red/red-scala.log` 同 blob 已由命名迁移批以新名入库（已并入）、另 2 件 `green/*` blob 在 main 全库 0 命中 —— 结论与机械证据齐备（PASS）。
- 需合并清单里的 3 棵「撞上第 6 次移动」的树（`bfad8ba9`/`87290cd9`/`5017517a`）用 `d989779a8` 复算后命中率**一字未变**，无一条因此翻判（细节见第 14 项）。

---

## 13 第13项 · 保全量可核对性 —— **PASS**

- §1.1 给了按顶层前缀的分解 + 占比（`evidence/** 23,905 = 82.4%`、`reports/** 4,731 = 16.3%`、`src/** 351 = 1.2%`、`tests/** 18`、`docs/.probe/scripts 6`、根散件 5），另给前 10 个二级目录与体积 ⇒ 不是「只有总数」。
- **我独立复算**（94 个提交逐个 `git show --name-only` 去空行）合计 **29,017** 文件，与报告的「git 展开实测 29,017」**完全相同**；分片口径 **28,907** 亦与我按 tsv 求和一致（差额 110 见第 1c 项）。按顶层前缀复算：`evidence 23,710 + 195 非 ASCII 未解析 = 23,905`、`reports 4,612 + 119 = 4,731`、`src 351`、`tests 18`、`root 4`、`docs 2 / scripts 2 / .probe 2` ⇒ 与报告**逐项对得上**（我的未解析 316 件=含空格非 ASCII 路径，属审计脚本的引号解析限制；报告自称 2 件未解析，其口径更严）。
- 单目录最大贡献：`evidence/20261019_145000_avatar-sharp` = **1,602** 文件（与我复算一致），**无 > 2,000 文件的目录**（我全量统计 0 个）。
- 体积前 10（我全量 `cat-file --batch-check` 的前 10）：1.97 MiB `delegate-fullflow-audit.html`、1.04 / 1.04 / 0.99 / 0.95 / 0.92 MiB 的 `term-citation-batch2/live/*.png`、0.86 MiB `wiki-graph-multivault/shots/2-stand-light.png`、0.86 / 0.85 / 0.82 MiB 的 `beta2-verify/shots/*.png` —— 与报告前 10 表**完全一致**；`> 5 MiB` blob = **0**，最大 1.97 MiB。保全集去重前总字节我算 **2,420,836,271 B ≈ 2.31 GiB**（报告 2,424,159,611 B ≈ 2.26 GiB，差 0.4% 属 316 件未解析与去重口径），量级与结论一致。

---

## 14 第14项 · 第六次基座移动（`d989779a8`）的落笔 —— **FAIL**

**现象**：main 第 6 动 `a0597efdd → d989779a8`（`Merge branch 'delegate/delegate-nebflow-b3bdf8d3'`，**动真源码** 4 + spec 2）：

```
src/main/scala/nebflow/agent/BackoffSupervisor.scala
src/main/scala/nebflow/core/delegate/MergeQueue.scala
src/main/scala/nebflow/core/project/ChainsReadFace.scala
src/main/scala/nebflow/shared/Defaults.scala
src/test/scala/nebflow/agent/DelegateBgIdleCapSpec.scala
src/test/scala/nebflow/core/delegate/MergeQueueHeadSkipFactCheckSpec.scala
```

但被审产出（`inventory-round2.md` 77,421 B，mtime 19:32）**完全没有这次移动**：
- 全目录 `grep -rn "d989779a8"` = **0 命中**；
- 重验基线仍写 `a0597efdd`；
- §8 只有 **5 行**移动记录（`awk` 计数 = 5），**缺第六条**；
- 6 棵撞上的树（`bfad8ba9`/`87290cd9`/`5017517a`/`164aec7e`/`a26da039`/`189e295a`）的 evidence 里**没有「已对 `d989779a8` 复核」的痕迹**。

**我替它算了一遍（供修法直接引用；本节点不改文件）**：

| 树 | 判词 | cherry | 与第 6 动交集 | 对 `d989779a8` 复算 |
|---|---|---|---|---|
| `bfad8ba9` | 需合并 | `+1` | `shared/Defaults.scala` (1) | 104 新增行 / 38 命中 = **36.5%**（不变） |
| `87290cd9` | 需合并 | `+1` | `agent/BackoffSupervisor.scala` (1) | 87 / 50 = **57.5%**（不变） |
| `5017517a` | 需合并 | `+1` | `core/delegate/MergeQueue.scala` (1) | 631 / 217 = **34.4%**（不变） |
| `164aec7e` | 不确定 | `+1` | `BackoffSupervisor` + `Defaults` (2) | 1103 新增行 / 790 命中 = **71.6%**（不变；`DelegateStandbyLifecycleSpec.scala` 252 行仍 main 不存在） |
| `a26da039` | 已并入 | `+1` | `BackoffSupervisor` + `Defaults` (2) | 675 / **644** = 95.4%（报告 636/675=94%，+8 行由第 6 次移动带进来），仍「已并入」 |
| `189e295a` | 被取代 | `+1` | `BackoffSupervisor`+`MergeQueue`+`ChainsReadFace` (3) | 4448 / 2544 = 57.2%，仍「被取代」 |

⇒ **实质结论不会翻**（3 条需合并仍零等价、cherry 仍 +1、main 缺件仍缺；唯一有数值变化的是 `a26da039` 的命中 636→644，方向仍「已并入」），失败的是「未重验就照抄」的**落笔义务**，不是结论本身。

**建议修法**：§8 补第 6 行（移动面 6 文件 + 上表逐棵交集），重验基线改为现算 tip（写作时当前 = `d989779a8`），并在 6 行 evidence 前缀补「对 `d989779a8` 复核：…」+ 上表读数；§1 的「期间 main 移动 5 次」改为 6 次。不要重跑其他 97 棵（分发器补充 5 口径：移动次数增加不要求全批重跑）。

---

## 15 失败项清单 + 观察项

### 失败项（2）

| # | 路径 | 现象 | 建议修法 |
|---|---|---|---|
| F1 | `shards/dirty-1.tsv`（19 行）、`shards/dirty-2.tsv`（19 行）对应的 38 个 salvage 提交 | 标题不合模板：19 条中段是字面 `\uXXXX` JSON 转义（`git log --oneline` 不可读）；19 条尾部 `（原批次 ¼，末次活动 ¼）` 把**原批次 id 与末次活动日期整段丢失** | 38 个提交非 main 祖先、父提交 = pre_head ⇒ 可 `git commit --amend -F <utf8-file>` 重写信息（B 类按 tsv 的 `pre_head` 日期与树名补回两个值），再同步 tsv 的 `commit_sha`、§2 主表、§2b。若判超范围，至少在 §6 登记该缺陷 |
| F2 | `inventory-round2.md` §8 / 顶部基线行 / 6 棵树的 evidence | 第 6 次基座移动 `a0597efdd→d989779a8`（真源码 4 文件 + 2 spec）零落笔：全文 0 命中、§8 仅 5 行、重验基线未更新 | 按第 14 项表格补 §8 第 6 行 + 6 行 evidence 的「已对 d989779a8 复核」痕迹 + 更新基线与「移动 5 次→6 次」 |

### 观察项（不判 FAIL，建议下一批顺手修）

1. `shards/recon.md` 全文无「分支数」读数，报告 §6 的「recon 基线 399」在被审批自己的工件里**查不到出处**；我改用更强证据（98 个分支 0 缺失 + 总数 400）判第 6 项 PASS。建议 recon 补一行 `git branch | wc -l` 基线。
2. §1.1 顶层前缀表自身相加 = **29,016**，而正文与他处口径为 **29,017**（差 1 件，属表内算术漏计）；我复算恰好 29,017。
3. 主表第 77 行（`183b016d`）evidence 模板句「main 文件都在但新增内容只有部分命中」与同行「0 行已在 main（0.0%）」自相矛盾（**数字是对的**，措辞是模板串），建议改为「0 命中 ⇒ 疑与 main 独立实现重叠」。
4. §1 的 `df` 开工读数 `69,156,064 KB` 与 recon §7 的 `72,133,084 KB` 不同（相隔约 4h，他批并发写入）；报告已在 §1 注明「df 含并发批次增减，以 du 为准」，属已披露口径差。
5. §3b 对 `298b4ddb` 的一句话写「1 文件」，其 own delta 实为 57 文件（1 个 `tests/` 代码件 + evidence 目录）；tsv 该行证据完整，仅 §3b 概述过窄。

---

## 16 总账

- 保全提交：**94**（`cat-file -e` 94/94 通过；父提交 == pre_head 94/94；标题合规 **56/94**）。
- 保全文件：**28,907（分片口径）/ 29,017（git 展开，我独立复算同值）**；判废 **722**（其中 716 随 19 次 `--force` 丢弃，1,856.18 MiB / 1,946,343,613 B；另 6 件在保留树 `e7c1a868` 内）。
- `--force`：**19 次**，每次均有逐文件清单 + 体积（716 行台账）。
- 保留树：**3**（`183b016d`、`98f9ca81`、`e7c1a868`，均 `reportStatus=None` 活树规则）；删除树：**100**（worktree list 与磁盘 0 残留）。
- 分支 **400 → 400**（0 删除）；main 祖先性 0 命中；`x1367` 双 tarball 464,652,288 B ×2 完好；主仓 porcelain 0。
- 主表 **103 行 == recon 待处理 103 棵**；判词 46 被取代 / 30 已并入 / 17 需合并 / 10 不确定；17 条需合并全部用**现算 main `d989779a8`** 复核为「仍零等价」。
- **AUDIT: FAIL —— 失败项 = F1（38/94 提交标题不合模板）与 F2（第 6 次基座移动未落笔）；其余 12 项 PASS。**
