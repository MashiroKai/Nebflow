# 数据根迁移 · 段二外部 agent 提示词（#535 ~/.nebflow → ~/.stand）

给 Kai 的用法：下次重启窗**亲手停引擎**后，把下方两条分隔线之间的提示词正文**整段**复制粘贴给外部 CLI agent（claude-code/codex 类）。正文自包含，无需附带任何上下文。命令与判据均依据主仓 `scripts/data-root-migration/` 脚本实文与 `evidence/` 实测日志（锚点见附录 A）。

--- ↓↓↓ 提示词正文（从此行下方整段复制） ↓↓↓ ---

# 任务：Nebflow 数据根迁移段二执行与核验（~/.nebflow → ~/.stand）

## 0. 角色与硬边界（先读，违者立即停手）

你是**文件层执行与校验 agent**。引擎的停止与启动一律由 Kai 亲手动完成，你**零服务操作**：

- 禁止 `launchctl`（bootout / bootstrap / load / unload 等任何子命令）；
- 禁止 `kill` / `pkill` / `killall` 任何进程；禁止 `sudo`；
- 禁止启动、重启引擎（含 `sbt run`）；
- 你只做：跑仓内迁移脚本、文件层校验（`ls` / `lsof` / `df` / `sqlite3` 只读 PRAGMA）、读数收集与报告。

三条绝对禁区：

1. **8090 稳定线零触碰**：`/Users/kaiyu/.nebflow-stable` 目录、其 `bin/nebflow` 启动器、任何 8090 相关进程/文件——无论是否仍在运行，一个字节都不改。finalize.sh 对此有内建守卫（源/目标路径含 `nebflow-stable` 即拒绝执行），不要试图绕过。
2. **8787 微信 sidecar 服务零触碰**：不许对 `com.nebflow.weixin-sidecar` 做任何 launchctl 操作或 reload。finalize.sh 只**补丁其 plist 文件**（`NEBFLOW_HOME` 值改写、原值留在 `.bak-<ts>`）、**绝不 reload**——这是设计内且经作者授权的行为，你无需也不得做额外的服务操作。
3. **旧根零删除**：`/Users/kaiyu/.nebflow` 迁移 = 只改名保底为 `/Users/kaiyu/.nebflow-migrated-<时间戳>`，你与脚本都**永远不删除它**；未来是否删除由 Kai 亲决。

**失败即停规则**：任何一条命令 FATAL / 非预期非零退出 → 停止后续所有步骤，收集日志原文与退出码，原样报告，等待 Kai。绝不自行修脚本、绝不重试花活、绝不绕过守卫（环境变量 `NEBFLOW_MIGRATE_ENGINE_GUARD=0` 仅 /tmp dryrun 沙箱合法，真实运行严禁设置；本次全部用脚本默认值，不设任何 `NEBFLOW_MIGRATE_*` 变量）。

注意：`/Users/kaiyu/Claude code/Stand` 路径含空格，命令中必须带引号。

## 1. 已知事实（校准预期，勿误判）

- **量级**：源侧实测约 331 万文件 / 212G；finalize 阶段增量差量约 **24.7 万件 / 27.4GB**（依据 `scripts/data-root-migration/evidence/rsync-delta-20261019-093703.log`，只读 dry-run 差量测得单轮 8m21s）。
- **耗时**：finalize 实测 **4-7 分钟，不是秒级**（runbook 里"秒切"仅指改名一步；300 万级文件的 rsync 扫描占大头）。命令数分钟无输出属正常，**禁止中断、禁止判定为卡死**。
- **段一收敛**：段一核验收敛 99.93%，剩 **111 个活文件**（引擎运行中热写入所致），由 finalize 的 `--delete` 权威增量收口——finalize 成功后这些差异应消失。

## 2. 前置检查（逐条跑；任一失败 → 停手报告，不进第 3 节）

```bash
# 2.1 磁盘余量 ≥ 8GiB（< 8.0 GiB → 停手，标黄报告 Kai）
df -k /Users/kaiyu | awk 'NR==2{printf "%.1f GiB\n", $4/1048576}'

# 2.2 段一预拷贝在位（应看到文件，非空目录）
ls -l /Users/kaiyu/.stand/tasks-v2.json

# 2.3 旧根在位
ls -d /Users/kaiyu/.nebflow

# 2.4 脚本工具箱在位（用主仓绝对路径，不是任何 worktree）
ls "/Users/kaiyu/Claude code/Stand/scripts/data-root-migration/"
#   应列出: dryrun.sh finalize.sh plist-patch.py precopy.sh rollback.sh runbook.md verify.sh evidence
```

- 2.1 输出 < 8.0 GiB → **停手标黄**：报告 Kai 磁盘不足，不继续任何写操作。
- 2.3 若 `/Users/kaiyu/.nebflow` 已不存在且存在 `/Users/kaiyu/.nebflow-migrated-*` → 已切换过（幂等分支）：跳过 3.A/3.B 的重跑确认，直接从 3.C verify 开始，并在报告中注明。
- 2.4 缺文件 → 停手报告，勿从 worktree 或他处拷贝替代。

## 3. 主流程（顺序执行；每步先看成功判据，失败即停）

### 3.A 确认引擎已停（Kai 亲手停，你只做只读确认）

```bash
lsof -iTCP:8080 -sTCP:LISTEN
```

- 成功判据：**无任何输出**（8080 已释放）。
- 有输出 → 停手，回报「8080 仍在监听，请 Kai 处理」。你绝不 kill、绝不等待循环重试（Kai 处理后再跑一次本条即可）。
- finalize.sh 自带同一守卫：8080 在监听会直接 FATAL 拒绝执行（这是防线，不是故障）。

### 3.B 跑 finalize（权威增量 + 零删除改名 + plist 补丁不 reload）

```bash
cd "/Users/kaiyu/Claude code/Stand" && bash scripts/data-root-migration/finalize.sh
```

脚本按序做六件事（全部内建，你无需干预）：

1. 守卫：8080 必须无监听（否则 FATAL → 回 3.A）；源/目标永不触碰 `~/.nebflow-stable`；
2. `rsync -aH[AX] --delete` 增量收口（`--delete` 只作用于目标 `~/.stand` 侧，源侧只读）；
3. `mv /Users/kaiyu/.nebflow /Users/kaiyu/.nebflow-migrated-<ts>`（**零删除：只改名保底**）；
4. 三个 launchd plist 补丁（`com.nebflow.weixin-sidecar` / `com.nebflow.gateway` / `com.nebflow.memsample`，先 `.bak-<ts>` 再改，**只写盘、绝不 reload**）；其余含旧路径的 `com.nebflow.*` plist 一并补丁，非 nebflow 的 plist 只报告不改；
5. 数据根内引用面路径改写（逐个 `.bak-<ts>`）：`daemons.json` / `install-manifest.json` / `bin/nebflow-autostart.sh` / `bin/memsample.sh`；`bin/nebflow`（8090 稳定线启动器）**明确不动**；
6. 残留旧路径引用只报告不修改（`tasks-v2.json` 等历史记录里的旧路径是历史，保持原样）。

- 预期时长 **4-7 分钟**；全程日志：`/tmp/stand-finalize-<ts>.log`。
- **成功判据**：输出末行 `FINALIZE DONE.`，且以下三条全真：

```bash
ls -d /Users/kaiyu/.nebflow 2>/dev/null            # 无输出（已改名）
ls -d /Users/kaiyu/.nebflow-migrated-*             # 有输出（保底备份在）
ls -l /Users/kaiyu/.stand/tasks-v2.json            # 有输出
```

- 幂等性：可安全重跑（源已改名时自动跳过 rsync+rename，只补未完成的 patch）。若在步骤 4/5 阶段报错且源已改名：障碍解除后**直接重跑同一命令**即可，无需回滚。
- rsync / mv / plist patch 任一 FATAL → 失败即停，收集 `/tmp/stand-finalize-<ts>.log` 原文报告。

### 3.C 严核 verify --strict

```bash
cd "/Users/kaiyu/Claude code/Stand" && bash scripts/data-root-migration/verify.sh --strict 2>&1 | tee "scripts/data-root-migration/evidence/segment2-verify-$(date +%Y%m%d-%H%M%S).log"
```

- 脚本行为：30 文件抽样 sha256 对账（对照侧自动取最新 `~/.nebflow-migrated-*`）+ SQLite `PRAGMA integrity_check` + 任务台账条数读数；finalize 有意改写过的文件（daemons.json 等 4 个）按「路径归一化后比对」判定（记 `PASS*`）。
- **成功判据**：命令 exit 0，summary 行 `fail=0`（形如 `files sampled=… pass=… pass*(patched-as-intended)=… fail=0 skip=…`）。
- **失败判据**：末行 `STRICT RESULT: FAIL (N mismatches)` 且 exit 1 → 失败即停，**不要自行修复任何文件**，携全部读数按第 4 节与 Kai 评估是否回滚。
- 顺带记录读数：`SQLITE-OK` 各行、`LEDGER … target_entries=… ref_entries=… bytes=…`、`target file count=…`、`volume free=… GiB`。

### 3.D 汇报（读数 + 残余）

写一份简报落盘到 `"/Users/kaiyu/Claude code/Stand/scripts/data-root-migration/evidence/segment2-report-<ts>.md"` 并在对话里全文输出：

- finalize 起止时间、日志路径、是否触发幂等分支；
- verify 全部读数：sampled / pass / pass* / fail / skip、SQLITE 各行、LEDGER target vs ref 条数、target file count、volume free；
- 残余旧路径引用：finalize 输出中 `record keeps old path:` 各行（`tasks-v2.json` 等历史记录保留旧路径**属预期**，只报告不改）；
- 磁盘余量读数；异常清单（无则写「无」）；
- 明确声明：未做任何服务操作、未删除任何文件、未触碰 `~/.nebflow-stable` 与 8787 sidecar。

**之后停在这里**。引擎启动（带 `NEBFLOW_HOME=/Users/kaiyu/.stand`）由 Kai 亲手执行，你不得代劳。

## 4. 回滚（何时用 + 确切命令）

**何时用**：verify --strict FAIL、或 Kai 判断 `~/.stand` 上引擎起不来 / 台账读数不对 / 数据异常，**经 Kai 明确决定退回旧根**时。不要自行发起回滚。

**前置**：引擎必须处于停止状态（Kai 亲手停，用 3.A 同款 `lsof -iTCP:8080 -sTCP:LISTEN` 无输出确认）。注意 rollback.sh 本身**没有** 8080 守卫，这条纪律靠执行前确认。

```bash
cd "/Users/kaiyu/Claude code/Stand" && bash scripts/data-root-migration/rollback.sh --full
```

- 行为：把最新的 `/Users/kaiyu/.nebflow-migrated-<ts>` 改名回 `/Users/kaiyu/.nebflow`；`--full` 额外把 finalize 补过的 3 个 plist 与 4 个数据根内配置从各自最新 `.bak-*` 恢复（当前文件先另存 `.pre-rollback-<ts>`，不丢现场）。
- `~/.stand` 整个保留**不删除**（零删除纪律）。
- **成功判据**：末行 `ROLLBACK DONE.`，且 `ls -d /Users/kaiyu/.nebflow` 恢复存在。
- 可选：`rollback.sh --ts <YYYYmmdd-HHMMSS>` 指定用哪个迁移备份（默认自动取最新）。
- 之后引擎由 Kai 按原方式启动（**不带** NEBFLOW_HOME）。你只报告，不重启引擎。

## 5. 禁区清单（重申，执行中随时对照）

| 禁区 | 界线 |
|---|---|
| 8090 稳定线（已退役） | `~/.nebflow-stable`、其 `bin/nebflow` 启动器、任何 8090 进程/端口/文件：零触碰 |
| 8787 微信 sidecar | 服务零触碰（无 launchctl / 无 reload / 无 kill）；其 plist 文件补丁仅由 finalize.sh 内建完成 |
| 旧根 `~/.nebflow` | 零删除，只改名保留为 `~/.nebflow-migrated-<ts>`；何时删由 Kai 亲决 |
| 服务操作 | launchctl / kill / pkill / killall / sudo / 启停引擎：一律禁止，全部归 Kai |
| 环境变量覆盖 | `NEBFLOW_MIGRATE_*` 全部不设（仅 /tmp dryrun 沙箱合法） |

--- ↑↑↑ 提示词正文（到此行上方为止） ↑↑↑ ---

## 附录 A：依据锚点（正文每条命令的脚本实文出处）

| 正文内容 | 锚点（Stand 仓内） |
|---|---|
| 8080 守卫、stable 守卫、rsync --delete、零删除改名、plist 补丁不 reload、幂等重跑 | `scripts/data-root-migration/finalize.sh:20-26,37-49,51-76,81-109,133-134` |
| `FINALIZE DONE.` / 日志路径 `/tmp/stand-finalize-<ts>.log` | `finalize.sh:26,133` |
| verify 报告/严核双模式、30 文件抽样、PASS* 归一化比对、SQLite integrity、台账读数、fail>0 → exit 1 | `scripts/data-root-migration/verify.sh:4-5,13-25,53-78,82-92,94-106,113-115` |
| `rollback.sh --full` / `--ts`、改名恢复 + .bak 还原、`.stand` 保留 | `scripts/data-root-migration/rollback.sh:4-8,24-37,40-61,63-65` |
| 前置自检 `ls ~/.stand/tasks-v2.json`、Kai 三步操作卡（停引擎/启动/自查） | `scripts/data-root-migration/runbook.md:8-35` |
| launchd reload 属 Kai 可选项（agent 禁做） | `runbook.md:52-60` |
| 24.7 万件 / 27.4GB、单轮只读差量 8m21s | `scripts/data-root-migration/evidence/rsync-delta-20261019-093703.log:3-9,18` |
| 331 万文件 / 212G 量级、df 余量 | `scripts/data-root-migration/evidence/reconcile-20261019-093703.log` |
| `~/.nebflow-stable` 永不动、原目录只改名零删除铁律 | `runbook.md:4,48` + `finalize.sh:37-38,71-75` |
| 段一核验 99.93% 收敛剩 111 活文件 | Kai 提供的段一结论（本仓 evidence 内无对应日志，按作者给定事实写入） |
| finalize 实测 4-7 分钟 | Kai 提供的实测结论；与 rsync-delta 日志的分钟级读数相互印证 |

## 附录 B：Kai 手动部分备忘（不属于 agent 提示词正文）

- 启动引擎（亲手，runbook §⭐③）：`cd "/Users/kaiyu/Claude code/Stand" && NEBFLOW_HOME=/Users/kaiyu/.stand GATEWAY_CONN_GUARD_TUNNEL_PER_IP_CAP=4096 <平时 env 原样> sbt run`；起来后自查 8080 监听、台账条数、`~/.stand/logs/` 出新日志。
- launchd reload（可选，Kai 自决时机，agent 永不代做）：见 `runbook.md:52-60`（sidecar bootout/bootstrap 命令；gateway/memsample 下次注销登录自然生效）。
- `~/.nebflow-migrated-<ts>` 删除时机：Kai 亲决，建议稳定运行数日后再定；脚本永远不会删它。
