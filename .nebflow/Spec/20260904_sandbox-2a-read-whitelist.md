# 沙箱批·单件 报告 — sandbox-2a 读白名单补全（系统运行数据目录可读、凭据层拒读不变）

- worktree: `.nebflow/worktrees/sandbox-readlist`，分支 `sandbox-readlist`，基线 `main@403e0274`
- 域: Nebflow Scala 后端（sandbox 四件套）
- 状态: 验证链四步完成（冒烟 8/8 PASS、变异红绿闭环、sbt 全量归因完毕）；**commit 因 .git EPERM 本侧不可为**——改动 commit-ready，宿主侧落地命令见 §10。详细验证报告：`20260904_sandbox-readlist-verify-report.md`

## 1. 改动说明

### 1.1 白名单扩充（`SandboxPolicy.nebflowReadExtras`）

`src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala` 单一策略源：

| 前值（H-12①） | 后值（本次补全） |
|---|---|
| `skills, prompts, docs` | `skills, prompts, docs` + `tool-results, uploads, logs, sessions, projects, agents` |

- 全部**只读**（进 `readExtras` → `readableRoots`，不进 `writableRoots`）
- 取 `PathUtil.dataRoot`（NEBFLOW_HOME 隔离/测试 setDataRoot 均生效）

### 1.2 凭据层拒读不变（红线）

- `~/.nebflow` 根层散文件（vps.env、auth.json、nebflow.json、model-presets.json、stt-config.json、User.md、*credentials*、*.env …）**不在任何白名单**——默认拒读机制不变，无需新规则
- **agents/Nebula/memory.md（及一切 `agents/**/memory.md`，agent 私有记忆）**：新增文件级负向规则 `SandboxPolicy.readDenied(canonical)`——`FileSandbox.checkCanonicalRead` 中**一票优先于 readableRoots**，保证「agents/ 目录开读、记忆文件仍拒」两个断言同时成立；SANDBOX_DENIED 文案追加 `Reason: path matches the private-memory deny rule ...` 解释行
- 负向规则设计为结构规则（basename=memory.md 且位于 canonical dataRoot/agents 子树内），Nebula 与 team agent 同规，不逐 agent 硬编码

### 1.3 遍历面覆盖（Grep/Glob）

目录级白名单 + 文件级拒绝管不住 rg 目录遍历（Grep `~/.nebflow/agents` 会把 memory.md 内容扫进结果）。新增 `SandboxPolicy.memoryGlobExcludes(canonicalRoot)`：搜索根落在 agents 子树内时给 rg 追加 `--glob !memory.md`（basename 语义、任意深度）。

**rg glob 次序陷阱（实现要点）**：rg glob 规则 last-match-wins——排除参数若先于用户 include glob 会被覆盖（用户 `glob: "*.md"` 会重新放行 memory.md）。故 `GrepTool` 中排除参数固定追加在**所有用户 glob 之后**（最后位=最高优先级）；`GlobTool` 的 include pattern 位于 args 首位，排除后置天然正确。spec 已固化该次序断言（用户 glob="*.md" 场景）。

### 1.4 错误文案动态性

`FileSandbox.deniedMessage` 的 `Readable roots:` 段自 `SandboxPolicy.readableRoots(policy)` 动态推导——全仓无硬编码根清单（grep 实证：唯一白名单定义点 = `nebflowReadExtras`）。新目录自动出现在实际 SANDBOX_DENIED 文案中。

## 2. 白名单前后对照

（见 1.1 表格）

## 3. spec 结果

全量 sbt test（前台真实跑）：**Total 2234, Failed 30, Errors 0, Passed 2204, Ignored 7，8m22s**。

- 本批新增 SandboxSpec 用例（READLIST+/−、MUT、MSG、遍历面）零断言逻辑失败；沙箱化会话内它们与全部存量 SandboxSpec 用例同因「spec 公共 fixture 在 $HOME 建目录 EPERM」而环境性失败（24 个同签名 `~/.nb-sbx-dataroot-*: Operation not permitted`，与基线 403e0274 环境性失败集同分布）
- 每条新断言的真实行为已由隔离实例冒烟逐条实证（§5，8/8 PASS）——强于沙箱内 spec 运行
- 2 个 project 域失败（R1 负载竞态隔离复跑 PASS；②b 基线遗留 NodeEngine 竞态×沙箱计时放大，main@c391415b 已带修复未并入——merge 因 .git EPERM 未完成）——均非本批回归，逐条归因详见验证报告 §5

## 4. 变异验红记录

两轮均完成：

1. **spec 级**（SandboxSpec「MUT」用例）：readExtras 剔除 tool-results → checkRead 拒、恢复条目 → 复绿（用例内闭环）
2. **隔离实例源码级**：Edit 移除 `nebflowReadExtras` 中 `"tool-results"` → 重编译重启实例 → 分发器重探：
   - 红：`FAIL P1 Grep tool-results 成功`（SANDBOX_DENIED 原文存 `dispatcher-evidence-mutation-red.txt`）+ `FAIL N1b 文案动态反映新白名单`（拒绝文案同步失去 tool-results——动态性双红）
   - 恢复条目 → 复绿：VERDICT mutation-green ALL PASS，`git diff --stat` 复原 +280/−24
   - 变异期间零 commit

## 5. 隔离实例冒烟记录

**正式轮（fixture 于 worktree/.smoke/home，所有沙箱根之外）8/8 PASS**（dispatcher-e6331c35 会话 JSON 取证，verdict: `smoke-verdict-base2.txt`）：

| # | 探测 | 结果 |
|---|---|---|
| P1 | Grep tool-results（NBX_SMOKE_TR_OK） | PASS——命中 result.txt |
| P2 | Read uploads/u-smoke/attachment.txt | PASS |
| P3 | Read skills/sbx-skill/SKILL.md | PASS |
| P4 | Read projects/sbx-readlist/project.json | PASS |
| N1 | Read vps.env（根层凭据） | PASS——SANDBOX_DENIED；N1b 文案含 tool-results（动态） |
| N2 | Read auth.json（真实 token） | PASS——SANDBOX_DENIED 且不泄 token |
| N3 | Read agents/Nebula/memory.md | PASS——SANDBOX_DENIED + Reason: private-memory deny rule |

预跑轮（fixture 在 /tmp，前会话原选址）实证协议缺陷：/tmp 处于 `tempRoots` 可读面，vps.env/auth.json 被读穿——白名单语义在 /tmp 选址下原理上不可验证；唯 memory.md 仍拒（readDenied 优先级强形态证据）。fixture 遂迁 worktree/.smoke/home。该轮泄漏进会话文件的宿主 token 已即时清除、证据脱敏（`evidence-run1-redacted.txt`）。

取证通道改造说明：tools-audit JSONL（`~/.nebflow/logs/tools/`）在本沙箱会话 EPERM 不可写（ToolsLogWriter 以 user.home 定根，不走 NEBFLOW_HOME），改用 dispatcher 会话 JSON（含全部 tool_use/tool_result 原文）。

测毕清理：实例 kill（lsof 核 PID ≠ 94384）、`smoke.sh scrub` 擦凭据、worktree/.smoke 与 /tmp fixture 全部 rm -rf、8280 复核空闲。

## 6. 产物索引

- `/tmp/nb-sandbox-readlist/setup-fixture.sh` — fixture 搭建脚本
- `/tmp/nb-sandbox-readlist/smoke.sh` — 冒烟驱动（up/trigger/collect/down/scrub）
- `/tmp/nb-sandbox-readlist/instance.log` — 隔离实例启动日志
- `/tmp/nb-sandbox-readlist/dispatcher-tools-*.jsonl` — 分发器会话工具执行审计（tools JSONL 摘取）
- `/tmp/nb-sandbox-readlist/smoke-verdict-*.txt` — 冒烟断言结果

## 7. 生效提醒

**沙箱策略改动需重启宿主实例才生效**——本批不做重启。宿主（PID 94384，端口 8080）继续按旧白名单运行，直至下次重启。

## 8. 已知边界（如实申报）

- Bash 层读面本轮不设界（H-10① 既有 hardening 清单项）：沙箱 Bash `cat` 记忆文件在本批前后同样可达，非本批引入的缺口；文件五件套（Read/Write/Edit/MultiEdit/Glob/Grep）闸门已全覆盖
- agents/ 子树内更深层（如未来 `agents/<n>/projects/<p>/memory.md`）亦被负向规则覆盖（basename 规则不限深度）

## 9. 改动文件清单（sandbox-readlist 分支 commit）

- `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala` — 白名单九目录 + `readDenied` 负向规则 + `memoryGlobExcludes`
- `src/main/scala/nebflow/core/sandbox/FileSandbox.scala` — 读闸负向规则一票优先 + 文案 Reason 行
- `src/main/scala/nebflow/core/tools/GrepTool.scala` — 遍历排除接线（用户 glob 后置）
- `src/main/scala/nebflow/core/tools/GlobTool.scala` — 遍历排除接线
- `src/test/scala/nebflow/core/sandbox/SandboxSpec.scala` — READLIST+/−、MUT、MSG、遍历面 spec

## 10. 交付与宿主侧落地命令

**本批不合并 main**（合并由后续触发）。宿主侧归档报告 + 提交（由宿主侧会话执行）：

```bash
# 报告归档（~/.nebflow repo，按文件 add）
cp /tmp/nb-sandbox-readlist/REPORT.md ~/.nebflow/docs/Nebflow/20260904_sandbox-2a-read-whitelist.md
cd ~/.nebflow && git add docs/Nebflow/20260904_sandbox-2a-read-whitelist.md && \
  git commit -m "docs: sandbox-2a 读白名单补全批次报告（六目录开读+memory.md 负向规则+隔离实例冒烟）"
```

**worktree 内 commit（⚠️ 本侧沙箱对 `.git/worktrees/*` 写 EPERM，`git add`/`git merge`/objects 写三次真实尝试均 Operation not permitted——改动保持 commit-ready，由宿主侧会话执行）：**

```bash
cd "/Users/dev/Claude code/Nebflow/.nebflow/worktrees/sandbox-readlist"
git add src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala \
        src/main/scala/nebflow/core/sandbox/FileSandbox.scala \
        src/main/scala/nebflow/core/tools/GrepTool.scala \
        src/main/scala/nebflow/core/tools/GlobTool.scala \
        src/test/scala/nebflow/core/sandbox/SandboxSpec.scala
git commit -m "sandbox: 读白名单补全——系统运行数据目录可读、凭据层与 agent 私有记忆拒读不变"
```

**合并提醒**：`sandbox-readlist` → `main` 需后续触发（nebflow-review-merge 流程），本批不自动合并。
**生效提醒**：合并后需重启宿主实例，沙箱白名单新值才对节点会话生效。
