# 阶段 2e 迁移收官批·总报告（phase2e-finale）

- 日期：2026-09-05（任务窗口 09-04 深夜～09-05 凌晨）｜ 节点：收官批合并与验收（barrier，收口）
- 上游：n-344d4661（退役-Issue机制收尾）、n-0c0466d8（实施-AGENTS.md注入与双轨移除）经 in 注入；n-01fcb885（蒸馏两 plugin）、n-25063feb（F.3 归档命令清单）经 deps 放行
- 结论先行：**两分支已合并进 main（零冲突）+ 全量 sbt 2334 总/30 红与基线逐条同构归因（零语义新增红）+ 隔离实例五项取证全 PASS + 四节点交付全部落地（宿主侧提交已在案）**

---

## §1 合并执行（主仓 main，基线 6dc4b398 实测一致）

### 1.1 Commit hash 清单

| commit | 内容 |
|---|---|
| `6f68a9de` | fin-issue-retire 分支单笔落地（上游 commit-ready 申报由本节点代执行；5 文件 +49/−41，与申报清单逐文件一致） |
| `cdd49cca` | fin-agents-md 分支单笔落地（9 文件 +493/−41 含新 spec AgentsMdInjectionSpec.scala，与申报一致） |
| `474d2fe9` | **Merge fin-issue-retire → main**（--no-ff，ort 策略） |
| `d2abd1b2` | **Merge fin-agents-md → main**（--no-ff，AgentCore.scala 文本自动合并） |

合并时点 main HEAD = `d2abd1b2`。合并前 `git status --porcelain` = 0（无他人脏文件）。未 push、未碰 origin。

**并行合并事件（如实记录）**：收口进行中，并行分支 proc-residue-governance 于 01:10:25 合并出 `533a2a0a`（现 main HEAD），落在本节点全量测试窗口（00:26–01:19）内。影响评估：sbt 于测试启动时一次性编译，运行中 JVM 不热重载，**全量数字仍对应 d2abd1b2（本批合并）语义**；子集复跑发生在换树重编译后仍 161/161 绿。`d2abd1b2`/`6f68a9de`/`cdd49cca` 均为现 main 祖先（merge-base 实测）。

### 1.2 冲突清单

**零冲突。** 预判的两分支文件域（AgentCore+工具面 spec vs ContextRefresher/PromptSections/RestApiRoutes/ProjectStore）唯一交集 AgentCore.scala 的改动位于不同 hunk（fin-issue-retire 改 fixedToolsFor/NebulaExclusiveTools/注释区 L2011-2137；fin-agents-md 仅 +1 行 `agentsMd = turnCtx.agentsMd` 于 PromptContext 组装 L575），git ort 自动合并。合并后实测两改动共存：L575 agentsMd 透传在位、L2136-2137 fixedToolsFor Nebula 分支为 `AgentCore.NebulaOrchestrationTools`（无 + "Issue"）、`NebulaExclusiveTools = {Schedule, Delegate, AgentControl, MemoryEdit}` 四件零 Issue。

### 1.3 Worktree 清理

- `git worktree remove .nebflow/worktrees/fin-issue-retire` + `fin-agents-md` ✓
- 软链 `.nebflow/fin-issue-retire`、`.nebflow/fin-agents-md` 已删 ✓
- `git branch -d` 两分支成功（曾指向 6f68a9de / cdd49cca，均已并入）✓
- phase2c-agent-convergence、qc-2d-refactor 两留存 worktree 未触碰 ✓

---

## §2 全量 sbt test 基线对照

测试对象 = d2abd1b2 树（见 §1.2 并行合并事件说明）。跑法：主仓前台一次跑完（sbt 1.10.10 / temurin-23 / fork=false；`-Dsbt.boot.directory=/tmp/sbt-boot -Dsbt.global.base=/tmp/sbt-home/.sbt/1.0 -Dsbt.ivy.home=/tmp/sbt-home/.ivy2 -Dsbt.coursierhome=/tmp/sbt-home/Library/Caches/Coursier`；**user.home 保持真实 /Users/dev** 以复现基线的 `~/EPERM` 环境性条件；`-Dsbt.server.forcestart=true` 防蹭宿主 sbt server）。Total time 3184s（53:04）。

| 指标 | 基线（2d 收口） | 本轮（2e 合并后） | 判定 |
|---|---|---|---|
| 总用例 | 2314 | **2334**（+20 = 2e 新增：AgentsMdInjectionSpec 18 + 存量 spec 断言重构净增 2） | ✓ |
| 红 | 30 | **30** | ✓ 等量 |
| 绿 | — | 2304 | ✓ |
| Ignored | — | 7 | ✓ |

### 30 红逐条签名归因（与基线同构）

| 类别 | 数量 | 本轮签名 | 基线归因 | 同构 |
|---|---|---|---|---|
| SandboxSpec | 24 | 全部 `java.nio.file.FileSystemException: /Users/dev/`（A.2×3/§A.4×3/A.8×6/G.1×2/H-12①/MSG/MUT/READLIST±×6，含 e511a614 READLIST 新增组） | 24 ~/EPERM | ✓ |
| CPU 探测 | 3 | BashActivityBridgeSpec.D-2 / BashBackgroundHardTimeoutSpec.B-2 / ShellStuckDetectorSpec.#17（32.4s 超时） | 3 | ✓ |
| 网络 | 1 | AcademicSearchSpec.Crossref（api.crossref.org 请求异常，10.1s） | 1 | ✓ |
| ~/EPERM | 1 | PopToolSpec「~ expansion」（`/Users/dev/.nebflow-pop-test-tmp` EPERM） | 1 | ✓ |
| flake | 1 | **NodePluginChainSpec.§B.4**（mcp plugin refcount 断言，8.99s；**单跑/子集复跑绿**） | NodeSessionDeathFinalize（flake 单跑绿） | 等量置换 ✓ |

**语义新增红 = 0。** flake 名单差异：基线的 NodeSessionDeathFinalize 本轮绿；本轮 NodePluginChainSpec 在 53 分钟满载 JVM 下红、子集复跑（161/161）绿——同为负载敏感型环境 flake，归因同构允许。

---

## §3 受影响 spec 子集复跑（合并后）

`testOnly` 一炮全跑：**161/161 全绿**。

| 来源 | spec | 用例 |
|---|---|---|
| fin-issue-retire（申报 84） | Phase2dToolRefactorSpec + AgentConvergenceSpec + AllowedToolSetSpec | 84 |
| fin-agents-md（申报 65） | AgentsMdInjectionSpec(18) + ProjectAgentFileRoutesSpec(11) + PromptSectionsSpec(25) + ProjectStoreSpec(7) + Phase2dSkillCatalogSpec + HeadlessModeSpec | 65 |
| 本轮新增验证对象 | NodePluginChainSpec（flake 单跑裁定） | 12 |

---

## §4 隔离实例集中取证（五项全 PASS）

实例：assembly jar `nebflow-assembly-1.4.1-beta.54.jar`（sha1 fa470404520d95b9e7150a9b58c260f440c1eda2，合并后 main 构建）｜`--home /tmp/nb-finale-home --port 8295 --no-browser` + `-Duser.home` 同指临时 HOME（LlmLogWriter 按 user.home 落 `logs/router/`，保证取证日志入隔离盘）｜LLM 配置拷自宿主 nebflow.json（真实模型调用，kimi/kimi-k3 实通）｜用毕 `lsof` 验 PID 41799 ≠ 宿主 94384 → kill → 端口释放 → `rm -rf` 临时 HOME 与工作区 ✓

### a. Nebula 恰十四件零 Issue — **PASS**
REST `GET /api/agents/Nebula` → `fixedTools` = Task/ProjectCreate/NodeList/AgentControl/Mail/SendFriendMessage/Delegate/FlowTrigger/FlowExecute/AskUserQuestion/Pop/Schedule/TransferFile/MemoryEdit，**恰 14 件**；`Issue` ∉、`CheckIssues` ∉（程序化比对 True）。**【2026-09-06 增补】FlowTrigger/FlowExecute 现已全局退役**（工具面裁撤批，Nebula 面本批前就含此二件、fin 批后移除）——Nebula fixedTools 现为恰 12 件；本行取证反映 2026-09-04 时点快照。**【2026-09-06 00:48 再增补·计数纠偏】上句「恰 12 件」与代码实况不符**：23:34 裁定后 NebulaOrchestrationTools 恰 14 件（读三件/Card 在，spec size==14 绿为证）；00:48 裁定摘 NodeList 后**现恰 13 件**

### b. AGENTS.md 注入三态 — **PASS**
试点 project（workspace=/tmp/nb-finale-ws，根 AGENTS.md 含标记串 `NB2E-FINALE-AGENTSMD-MARKER-8f3a1c`）→ Nebula 会话 turn「Task(project=pilot)」驱动分发器建 probe 节点（agent=general）：

| 会话形态 | system 对象证据（logs/router/objects） | 判定 |
|---|---|---|
| node 会话（agent=general，sid node-380…） | 对象 `4e0e2ccb…`：含小节头 + 标记串（8301B）；**节点结果自证**引用小节标题行与标记行原文 | 注入 ✓ |
| 分发器会话（project-dispatcher，sid dispatch…） | 对象 `1a2e2636…`：含小节头 + 标记串（8586B，3 次请求一致） | 注入 ✓ |
| Nebula 独立会话（sid a5b56613…） | 对象 `ff27cc23…`（3 次请求）：header/marker **零命中** | 不注入 ✓ |

双轨 team/flow 会话：按任务书以 spec 层断言替代——AgentsMdInjectionSpec「双轨不注入+空串护栏」用例在 §3 子集复跑绿（161/161），注明。

### c. F.3 装载失效模拟 — **PASS**
临时 HOME agents/ = 三 keeper（Nebula/project-dispatcher/general，agent.json 在位）+ Explorer/（仅 `agent.json.archived`，取宿主真实归档件）→ REST `GET /api/agents` 恰返回三 keeper，**无 Explorer** ✓。
**重要实证**：F.3 归档审计 §二 预告的 seedDefaults 复活向量（Explorer/Coder 每次重启复活）**已在当前 main 根治**——`AgentLibrary.scala` `Seeds.all = List(Nebula)`（注释「F.3 convergence, 2026-09-05……Archived agent dirs must NOT be resurrected by seeding」），隔离实例启动零复活，与设计预期一致。

### d. plugin 装载校验 — **PASS**（/tmp/nb-2f-plugins/plugins/ 已就绪，未 SKIP）
拷入 explorer-toolkit + design-spec → 未审批态：两 plugin 均装载为 `untrusted / never approved (default-deny)`，各带 digest（explorer-toolkit `94d2a0471f35763b…e5`、design-spec `3ed636408e7820b7…f8`），`GET /api/plugins/catalog` 为空（默认拒生效）→ REST approve 成功（digest 入账，持久化于 nebflow.json `plugins.trust`）→ catalog 出现两 plugin 条目及 skills 清单（`[skills: exploration-method, solution-planning | mcp: -]`、`[skills: design-spec | mcp: -]`）。

### e. 截断护栏实例层抽查 — **PASS**（可选件，已做）
AGENTS.md 放大至 17058 字节（16KB 内含标记、16KB 外置哨兵串）→ probe2 节点报告：尾注 `[AGENTS.md truncated]` 出现（切点 filler 00216 行 `paddi` 处）、哨兵串未出现、16KB 内标记串可见——与 ContextRefresher.scala:159-164 实现（16384 字节截断 + 尾注）一致。

---

## §5 收官批四节点交付摘要

1. **n-344d4661 退役-Issue机制收尾**（fin-issue-retire→`6f68a9de`）：Issue/CheckIssues 机制层退役——fixedToolsFor 删 `+ "Issue"` parity carry、NebulaExclusiveTools 删 Issue 条目（现四件）、Guardrails 注释去退役工具引用；spec 三件（Phase2dToolRefactor/AgentConvergence/AllowedToolSet）改恰十四件零 Issue 断言含负向断言；src/main grep 零机制残留（仅终裁注释与无关同名）；变异验红 4/84→恢复绿 84/84。
2. **n-0c0466d8 实施-AGENTS.md注入与双轨移除**（fin-agents-md→`cdd49cca`）：§E 注入管线九点打通（ContextRefresher resolveAgentsMd + 16KB 护栏 + gating=sandboxEnabled∧projectRoot；PromptSections order 895；protocol/AgentActor/AgentCore 透传）；E.3 REST 只读写根 + ProjectStore.load 迁移三分支；gating 取舍实证留档（两案否决理由）；新 spec 18 + REST spec 11 用例；变异验红 2 红→18 绿。
3. **n-01fcb885 蒸馏-explorer与design两plugin**（产物 /tmp/nb-2f-plugins/，报告 REPORT.md）：explorer-toolkit（exploration-method 50 行 + solution-planning 77 行）+ design-spec（85 行，无 mcp.json 按裁定 12 合法）+ visual-report 增强（220→245 行，新增 HTML 报告触发条件 + SVG 暗色适配节 + 修悬挂代码块）；静态自检四项全 PASS；宿主落地命令在其报告 §6。
4. **n-25063feb 归档-agents层F3命令清单**（/tmp/nb-f3-archive/20260904_f3-agents-archive-commands.md）：自适应幂等归档循环组（keeper 例外 Nebula/project-dispatcher/general）+ zip 清理 + commit 编排 + 核验/回滚/flows 引用审计 + 复活向量预告（后经实证已被 Seeds.all 收敛根治）。

---

## §6 生效说明（机制层 vs 定义层）

- **机制层（需宿主重启，本批未重启）**：Issue 退役（fixedToolsFor/NebulaExclusiveTools）、AGENTS.md 注入管线（order 895/gating/16KB 护栏/REST/ProjectStore 迁移）——均随合并代码进入 main，宿主进程（PID 94384）仍运行旧代码，**重启后生效**。
- **定义层（已生效，无需重启的部分）**：F.3 agents 层归档由宿主执行完毕（实测 Explorer/Coder/design-engineer 已 `agent.json.archived`，三 keeper LIVE，zip 已清理），提交 `af849c3`；catalog 收窄在宿主重启后由 EntityLoader 重扫即现（现行 live 进程内目录为启动时快照）。
- **plugins**：宿主 `~/.nebflow/plugins/` 两 plugin 树已落地且已提交（`9c2af33`），但宿主 nebflow.json 尚无 `plugins.trust` 审批记录 → 当前 untrusted 默认拒；审批后 catalog 随下次装载出现（重启或热路径即时生效，见 approve 响应「next spawn/allocation takes effect immediately」）。
- **种子收敛**：`Seeds.all = List(Nebula)` 随 main 代码走，重启后归档目录不再被复活。

---

## §7 宿主侧落地命令汇总

**已由宿主执行完毕（本节点只读核实，无需重跑）**：
- [x] node3 plugin 树落地 + visual-report 覆盖 + 三笔 commit（`9c2af33` plugins、`56cdf00` visual-report；命令全文见 `/tmp/nb-2f-plugins/REPORT.md` §6）
- [x] node4 F.3 归档循环组 + zip 清理 + 单笔 commit（`af849c3`，方案 A；命令全文见 `/tmp/nb-f3-archive/20260904_f3-agents-archive-commands.md`）

**待宿主执行（唯一剩余项）——plugins 信任门审批（二选一）**：
```bash
# 方式 A：REST 审批（宿主自身会话执行；digest 与本报告 §4d 一致）
curl -s -X POST -H "Authorization: Bearer $(python3 -c "import json;print(json.load(open('$HOME/.nebflow/auth.json')))")" \
  -H "Content-Type: application/json" -d '{}' http://localhost:8080/api/plugins/explorer-toolkit/approve
curl -s -X POST -H "Authorization: Bearer $(python3 -c "import json;print(json.load(open('$HOME/.nebflow/auth.json')))")" \
  -H "Content-Type: application/json" -d '{}' http://localhost:8080/api/plugins/design-spec/approve

# 方式 B：手编 ~/.nebflow/nebflow.json 增 plugins.trust（digest 必须逐字节一致）
# "plugins": {"trust": {"explorer-toolkit": {"sha256": "94d2a0471f35763b56e48eee7a26855fe2e553d7097008cf5a1d0c7e1d7e9be5", "approvedAt": <unix>, "scope": "all"}, "design-spec": {"sha256": "3ed636408e7820b7fb8bee69b1181657d82b56d35e2d7b197b8d2cdf999301f8", "approvedAt": <unix>, "scope": "all"}}}
```

**本报告归档（宿主执行）**：
```bash
cp /tmp/nb-finale/20260904_phase2e-finale-report.md ~/.nebflow/docs/Nebflow/ && \
cd ~/.nebflow && git add docs/Nebflow/20260904_phase2e-finale-report.md && \
git commit -m "docs: 阶段2e 迁移收官批总报告（两分支合并 474d2fe9/d2abd1b2 + 全量 2334/30 同构归因 + 隔离实例五项取证 PASS）"
```

---

## §8 遗留问题

1. **NodePluginChainSpec 满载 flake**：全量跑 §B.4 refcount 断言偶红（本轮 1 次，子集/单跑绿）；与基线 NodeSessionDeathFinalize 同类。建议后续加宽该断言 await 窗口或登记为已知 flake（不阻塞本批）。
2. **宿主重启前机制层不生效**：Issue 退役与 AGENTS.md 注入的运行时行为切换依赖宿主重启（现网 Nebula 仍有 Issue 工具可见性取决于旧代码的 fixedTools——旧代码含 parity carry，重启后自然消失）。
3. **双轨 team/flow 会话注入仅 spec 层断言**（AgentsMdInjectionSpec 用例绿），实例级取证按任务书豁免。
4. **主仓基线非 format-clean**（2e 节点教训在案）：任何节点禁跑 `scalafmtAll`。
5. **sbt 沙箱配方沉淀**：`-Dsbt.coursierhome` 是 sbt 内嵌 coursier 的缓存开关（`-Dcoursier.cache` 无效）；user.home 必须保持真实以保 SandboxSpec 基线同构。
