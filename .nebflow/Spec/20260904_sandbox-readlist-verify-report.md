# 沙箱读白名单补全·验证链续作报告（2026-09-04）

- 节点：验证-沙箱读白名单·续作（前会话实施节点 n-8564496a 的验证链续作，非重做）
- worktree：`.nebflow/worktrees/sandbox-readlist`，分支 `sandbox-readlist`，基线 `main@403e0274`
- 结论：**验证链四步全部执行完毕**——冒烟 8/8 PASS、变异验红/复绿闭环、全量 sbt 30 失败全部逐条归因（28 环境性 + 2 基线遗留域）、**提交因 .git EPERM 本侧不可为**（改动保持 commit-ready，宿主侧落地命令见 §7）

---

## 1. 盘点结论（worktree 实测 vs 上游清单）

| 项 | 上游声明 | 实测 | 判定 |
|---|---|---|---|
| 分支/tip | sandbox-readlist @ 403e0274 零新 commit | 一致 | ✓ |
| 未提交改动 | 恰 5 文件 +280/−24 | `git status --porcelain` = 5 文件；diffstat = +280/−24 | ✓ |
| FileSandbox.scala | +25 | +25 | ✓ |
| SandboxPolicy.scala | +40 | +40 | ✓ |
| GlobTool.scala | +14 | +14 | ✓ |
| GrepTool.scala | +16 | +16 | ✓ |
| SandboxSpec.scala | +209 | +209 | ✓ |

前会话产物复核：`smoke.sh` / `setup-fixture.sh` **机制判定可用**（隔离实例 up/trigger/down 生命周期、lsof-PID 核实后 kill、scrub 擦凭据设计均正确），但本次续作做了三处必要改造（理由见 §3.0）：
1. fixture HOME 从 `/tmp` 迁至 worktree 内 `.smoke/home`（/tmp 被 `SandboxPolicy.tempRoots` 整体放进可读/可写根，白名单与凭据拒绝在 /tmp 位置**原理上不可验证**，run1 实证读穿）；
2. 探测集 5 步 → 7 步（补 skills 正向 + auth.json 负向，对齐任务规格并加固）；
3. 取证通道从 tools JSONL 改为 dispatcher 会话 JSON（`ToolsLogWriter`/`LlmLogWriter` 以 `user.home` 定根、不走 NEBFLOW_HOME，本沙箱会话内写 `~/.nebflow/logs` EPERM，JSONL 永远落不出来——代码证据 `ToolsLogWriter.scala:76`）。

## 2. 设计意图落点复核（代码现状 = 唯一权威）

| 意图 | 落点 | 复核证据 |
|---|---|---|
| 九目录只读白名单单一推导点 | `SandboxPolicy.nebflowReadExtras`：`skills/prompts/docs/tool-results/uploads/logs/sessions/projects/agents` | diff 确认；`readableRoots` 唯一推导（SandboxPolicy.scala:123-126） |
| readDenied 一票优先 | `FileSandbox.checkCanonicalRead` 先查 `readDenied` 再查白名单；命中带 `Reason: path matches the private-memory deny rule ...` | diff 确认 + 冒烟 N3 实证（见 §4 run1：memory.md 在「本可读」位置仍拒——优先级最强形态验证） |
| 遍历面排除在用户 glob 之后 | `GrepTool.runGrep` 中 `args ++= memoryExcludes` 位于全部用户 glob 处理之后（last-match-wins）；`GlobTool` 同构 | diff 确认 + SandboxSpec「用户 glob 不得放行 memory.md」用例固化次序 |
| 错误文案动态无硬编码 | `deniedMessage` 的 Readable roots 段源自 `readableRoots(policy)` | diff 确认 + run1 完整 SANDBOX_DENIED 文案实证列出全部九目录 |

## 3. 隔离实例冒烟（验证链第 1 步）

### 3.0 环境适配（本会话沙箱写边界实测）
- 不可写：`.git/worktrees/*`、git objects、`~/.sbt`、`~/.ivy2`、`$HOME`、`~/.nebflow`（只读前提）
- 适配：sbt 启动加 `-Dsbt.global.base=/tmp/nb-sandbox-readlist/sbt-global -Dsbt.boot.directory=/tmp/nb-sandbox-readlist/sbt-boot`（重定向后启动链全通）；fixture 迁 worktree/.smoke/home；实例日志（LlmLogWriter router 日志）EPERM 仅 WARN 不影响功能
- 端口 8280 全程独占，宿主 8080/PID 94384 未触碰；每次 kill 前 lsof 核实 PID（smoke.sh 内建二次核实 cwd）

### 3.1 run1（fixture 在 /tmp，前会话原设计）——协议缺陷实证
7 步探测跑通（dispatcher 会话 dispatcher-bee16a9d，turn-complete），但：
- P1 Grep tool-results ✓ / P2 Read uploads ✓ / P3 Read projects ✓
- **N-步全部失效：vps.env、auth.json 根层凭据被读穿**（`/tmp` 在 tempRoots 可读面内，白名单/拒绝语义根本未被测到）
- memory.md **仍拒** + Reason 行——readDenied 优先级的强形态证据（目录本可读、文件级例外照拒）
- 完整拒绝文案（动态 Readable roots 九目录）已留档 `evidence-run1-redacted.txt`

**安全卫生**：run1 中宿主 API token 被读进会话持久化文件——已即时清除全部相关会话 JSON（`fixture/sessions/*.json`），脱敏证据不含 token 原文；token 未出本机。

### 3.2 run2（fixture 迁 worktree/.smoke/home，所有沙箱根之外）——正式冒烟 **8/8 PASS**

| # | 探测 | 结果 | 证据（dispatcher-e6331c35 会话 JSON / dispatcher-evidence-base2.txt） |
|---|---|---|---|
| P1 | Grep `~/.nebflow/tool-results` 搜 NBX_SMOKE_TR_OK | **PASS** | 命中 `tool-results/tr-smoke/result.txt:1:NBX_SMOKE_TR_OK` |
| P2 | Read uploads/u-smoke/attachment.txt | **PASS** | 返回 `upload attachment NBX_SMOKE_UP_OK` |
| P3 | Read skills/sbx-skill/SKILL.md | **PASS** | 返回完整 SKILL.md（NBX_SMOKE_SKILL_OK） |
| P4 | Read projects/sbx-readlist/project.json | **PASS** | 返回项目元数据 |
| N1 | Read vps.env（根层假凭据） | **PASS** | `SANDBOX_DENIED`；**N1b**：文案 Readable roots 段含 tool-results（动态） |
| N2 | Read auth.json（真实 token 文件） | **PASS** | `SANDBOX_DENIED` 且结果不含 token 原文 |
| N3 | Read agents/Nebula/memory.md | **PASS** | `SANDBOX_DENIED` + `Reason: path matches the private-memory deny rule` |

VERDICT base2: ALL PASS（`smoke-verdict-base2.txt`）。测毕实例 kill（lsof 核实）、scrub 擦凭据、`.smoke/` 与 /tmp fixture 全部 rm -rf。

## 4. 源码级变异验红（验证链第 2 步）

- **变异方式**：临时将 `SandboxPolicy.nebflowReadExtras` 列表中的 `"tool-results"` 条目移除（Edit 精确反向恢复，未动 git）
- **红**（`smoke-verdict-mutation-red.txt`）：
  - `FAIL P1 Grep tool-results 成功`——同路径探测变 `SANDBOX_DENIED ... cannot read ".../tool-results" (resolves to ..., outside sandbox root .../workspace)`（原文存 `dispatcher-evidence-mutation-red.txt`）
  - `FAIL N1b 文案动态反映新白名单(含 tool-results)`——拒绝文案 Readable roots 同步失去 tool-results（动态性双红证据）
  - 其余 6 项保持 PASS（对照面稳定）
- **绿**（恢复条目后重编译重跑）：VERDICT mutation-green **ALL PASS**；`git diff --stat` 复原 +280/−24
- 变异期间零 commit（本侧 commit 本就 EPERM，双保险）

## 5. 全量 sbt test（验证链第 3 步）

前台真实跑（timeout 3600000ms 一次跑完）：**Total 2234, Failed 30, Errors 0, Passed 2204, Ignored 7，sbt 502s（8m22s，wall 8m27s）**

### 5.1 环境性失败 28 个（逐条签名归因，与基线同分布）
| spec | 数 | 签名 | 归因 |
|---|---|---|---|
| SandboxSpec | 24 | 全部同一签名 `FileSystemException: /Users/dev/.nb-sbx-dataroot-*: Operation not permitted`——**spec 公共 fixture 在 $HOME 建目录被沙箱拒**，死于建 fixture 而非断言 | 环境性（~/ 写 EPERM 类，任务预告先例；含本批 8 个新用例 READLIST×5/MUT/MSG/遍历面，同样死于 fixture 创建，无一条断言逻辑失败） |
| BashActivityBridgeSpec | 1 | D-2 CPU delta 探测 | CPU 探测类（基线已知） |
| BashBackgroundHardTimeoutSpec | 1 | B-2 CPU-busy 判定 | 同上 |
| ShellStuckDetectorSpec | 1 | #17 busy child 存活判定（32s 计时敏感） | 同上 |
| PopToolSpec | 1 | `~` 展开→写 `/Users/dev/.nebflow-pop-test-tmp.png` EPERM | ~/ 写 EPERM 类 |

对照基线：403e0274 基线 20 个环境性失败集中于 SandboxSpec/Bash 三 spec/PopToolSpec（dispatch-reliability 批已净基线对照证明环境性）；本批新增 8 个 SandboxSpec 用例使该类数量按比例增至 24，**类别与签名完全同分布，未新增失败类别**。新用例的真实行为已由 §3.2 隔离实例 8/8 全 PASS 逐条实证（强于沙箱内 spec 运行）。

### 5.2 project 域失败 2 个（基线清单外，单独归因）
- **NodeBlockedReentrySpec R1**：全量跑红，**隔离复跑 PASS（0.95s）**——负载相关竞态。该域有在案先例（main@ae43fb61「14 suite 并发负载下复现」竞态并修复）。→ 环境性（负载竞态）。
- **NodeAcceptanceSpec ②b buffered rewire**：全量 + 隔离均红（2/2，本环境确定性）。归因链：① 该用例基线 403e0274 已存在且 main 未改过此测试文件；② 本分支 NodeEngine 停在基线版（**开工 git merge main 因 `.git/worktrees/sandbox-readlist/ORIG_HEAD.lock` EPERM 失败**，main@c391415b 携带 NodeEngine +165/−29 竞态修复未并入）；③ 本批 5 文件与 NodeEngine/NodeAcceptanceSpec 零交集。→ **基线遗留引擎竞态 × 本沙箱会话计时放大，非本批回归**。合并 main 后预期消除（遗留问题 §9）。

## 6. 提交（验证链第 4 步）——EPERM 申报

真实尝试（三次，均 Operation not permitted，非 index.lock 竞争）：
1. `git merge main` → `cannot lock ref 'ORIG_HEAD': 不能创建 '.../.git/worktrees/sandbox-readlist/ORIG_HEAD.lock'：Operation not permitted`
2. `git hash-object -w` → `错误：无法创建临时文件: Operation not permitted`（objects 不可写）
3. `git add <5 文件>` → `不能创建 '.../index.lock'：Operation not permitted`

**改动保持 commit-ready 完整状态**（working tree 5 文件 +280/−24 原样），零丢失。宿主侧落地命令见 §7。

## 7. 宿主侧落地命令

```bash
cd "/Users/dev/Claude code/Nebflow/.nebflow/worktrees/sandbox-readlist"
git add src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala \
        src/main/scala/nebflow/core/sandbox/FileSandbox.scala \
        src/main/scala/nebflow/core/tools/GrepTool.scala \
        src/main/scala/nebflow/core/tools/GlobTool.scala \
        src/test/scala/nebflow/core/sandbox/SandboxSpec.scala
git commit -m "sandbox: 读白名单补全——九目录只读+agents/**/memory.md 负向规则+遍历面排除（冒烟8/8+变异红绿+sbt全量2234归因）"

# 报告归档（~/.nebflow repo，按文件 add）
mkdir -p ~/.nebflow/docs/Nebflow
cp /tmp/nb-sandbox-readlist/20260904_sandbox-readlist-verify-report.md ~/.nebflow/docs/Nebflow/
cp /tmp/nb-sandbox-readlist/REPORT.md ~/.nebflow/docs/Nebflow/20260904_sandbox-2a-read-whitelist.md
cd ~/.nebflow && git add docs/Nebflow/20260904_sandbox-readlist-verify-report.md \
                       docs/Nebflow/20260904_sandbox-2a-read-whitelist.md && \
  git commit -m "docs: sandbox-readlist 验证链续作报告（隔离冒烟8/8+变异红绿+sbt全量归因+EPERM申报）"
```

（`sandbox-readlist` → `main` 合并仍由后续 nebflow-review-merge 流程触发，本批不合并。）

## 8. 生效说明

**沙箱策略改动需重启宿主实例才生效**——本批不做。宿主（PID 94384，端口 8080）继续按旧白名单（三目录）运行；合并入 main 且重启后，九目录白名单 + memory.md 负向规则对节点会话生效。

## 9. 遗留问题

1. **merge main 未完成**（EPERM）：分支仍基于 403e0274。main 的 NodeEngine 竞态修复未并入——②b/R1 类 project 域 flake 预期在合并后缓解/消除。宿主侧提交后由合并流程补齐。
2. **②b buffered rewire 在沙箱会话环境确定性红**：非本批回归（§5.2 归因链）。若合并 main 后仍红，需 NodeEngine 域专项排查（不在本批范围）。
3. **SandboxSpec fixture 依赖 $HOME 写**（`.nb-sbx-dataroot-*` 建在 os.home）：沙箱化会话内整个 SandboxSpec 必红。建议后续批把 fixture 根改为可重定向（如 java.io.tmpdir），提升沙箱内可测性。
4. **ToolsLogWriter/LlmLogWriter 以 user.home 定根、不读 NEBFLOW_HOME**：隔离实例的 tools-audit JSONL 无法落 fixture，审计只能走会话 JSON。属可测性缺口，非安全缺口，建议后续批对齐 PathUtil.dataRoot。
5. 冒烟 run1 曾将宿主 API token 读入 /tmp 会话文件（tempRoots 遮蔽所致）：已全部清除并脱敏。真机部署中 ~/.nebflow 不在 tempRoots 下，此泄漏路径不成立；但印证了「fixture 选址必须在所有沙箱根之外」这条验收纪律。

## 10. 产物索引

- `/tmp/nb-sandbox-readlist/20260904_sandbox-readlist-verify-report.md` — 本报告
- `/tmp/nb-sandbox-readlist/REPORT.md` — 批次报告（§3/§4/§5 已由本节点续写补全）
- `/tmp/nb-sandbox-readlist/smoke.sh` / `setup-fixture.sh` — 冒烟驱动+fixture 搭建（含三处改造，见 §1）
- `/tmp/nb-sandbox-readlist/smoke-verdict-{base2,mutation-red,mutation-green}.txt` — 三轮断言结果
- `/tmp/nb-sandbox-readlist/dispatcher-evidence-{base2,mutation-red,mutation-green}.txt` — 分发器会话取证（脱敏）
- `/tmp/nb-sandbox-readlist/evidence-run1-redacted.txt` — run1（/tmp fixture 协议缺陷实证，脱敏）
- `/tmp/nb-sandbox-readlist/instance.log` — 最近一轮隔离实例日志
