# nebflow-qa — 插件使用指导（Plugin.md）

> 本文件是 nebflow-qa 插件的**包根使用指导件**（Plugin.md）：装载器不注入本文件、不参与选配；维护者与按需 Read 的节点/用户以此为包内地图（用户＋模型可见的按需 Read 面）。选配判定只看 plugin.json 的 description；作业指令在各 `skills/<skill>/SKILL.md`（spawn 时全文注入）。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`，该替换仅发生在 SKILL.md 注入文本）。

## 1. 速览

nebflow-qa 是验证域知识包——前后端交付验证关卡与验证方法论深化：两个关卡 skill 管「验什么」（五维审核/三步验证），三个方法论 skill 管「怎么验」（自验 harness/隔离冒烟/验证严谨性）。内含 5 个 skill：

- `skills/nebflow-qa-backend/`——Scala 后端验证关卡：五维审核（编译/测试/验收条件逐项/代码质量三维/回归）、✅/❌ 审核报告格式、运行安全红线、进程清理纪律；**只审不改**。
- `skills/nebflow-qa-frontend/`——前端验证关卡：隔离真实例三步验证（隔离实例/Playwright 冒烟套件/资源可达性实测）、禁静态服务器替身规则、FAIL 判定三条款、双端契约静态审核四维；**只验不改**。
- `skills/frontend-verification/`——前端自验与验证方法论：实现侧无后端 smoke harness、wire-probe 侦查法、契约夹具、分层验证顺序、Playwright 高频坑、结构化盲点补验。
- `skills/isolated-smoke-verification/`——隔离实例冒烟验证法：临时 home＋独占端口＋mock 路由起隔离实例、断言锚定持久化产物而非易失 stdout、沙盒泄漏三信号三角定位、kill 前 PID 验身。
- `skills/verification-rigor/`——验证严谨性纪律：验红实证、变异安全、spec 载力（防巧合绿）、sbt/异步排障、日志断言韧性、字段改名四面验证。

**包纪律一句话**：验证结论必须有独立证据链——绿测试不证明防线有效，只证明这次测到了；本包只验/只审不改码；宿主进程与宿主网关端口是两道绝对防线；质量评分审查不在本包（另配 `nebflow-pipelines` 的 review）。

## 2. 安装与启用

- **安装**：本包属 Nebflow seed 官方分发，随实例 seed mirror 自动下发，无需手工安装。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（后端/前端交付验证、合并前独立验收、冒烟验证、验证方法论、验红实证等），派发时对应 SKILL.md 全文注入节点。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/nebflow-qa/revoke` / `nebflow plugin revoke nebflow-qa`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载）。

## 3. 目录结构

```
nebflow-qa/                                  （包根）
  plugin.json
  Plugin.md                                  ← 本文件（包根使用指导件：速览 / 安装启用 / 验证导览 / 总目录 / 决策树）
  skills/
    nebflow-qa-backend/SKILL.md              ← Scala 后端验证关卡（注入面）
    nebflow-qa-frontend/SKILL.md             ← 前端验证关卡（注入面）
    frontend-verification/SKILL.md           ← 前端自验与验证方法论（注入面）
    isolated-smoke-verification/SKILL.md     ← 隔离实例冒烟验证法（注入面）
    verification-rigor/SKILL.md              ← 验证严谨性纪律（注入面）
```

纯知识包：无 references/、assets/、scripts/，无 mcp.json——每个 skill 的全部口径都在其 SKILL.md 单文件内（各 skill 尾部 Evidence 节为实战先例沉淀）。

## 4. 包级核心用法（关卡与方法论怎么配合）

> 全量口径以注入的五份 SKILL.md 为准；本节是验证分层速查。

| 层 | 用哪个 skill | 关键纪律（速查） |
|---|---|---|
| 后端交付关卡 | nebflow-qa-backend | 五维每次全覆盖；结论附实际命令输出；sbt 锁冲突错峰重试、仍挂如实报阻塞（不是 FAIL）；Write 仅用于写审核报告 |
| 前端交付关卡 | nebflow-qa-frontend | 三步缺一不可，全打隔离真实例（禁替身：静态服务器对任意深度路径都 200，跑在其上的「全绿」无效）；资源可达性含动态 import 链实测；FAIL 附逐条证据 |
| 实现侧自验 | frontend-verification | mock 只验证你对帧形状的假设——自验 harness 用 `data-*` 断言契约与视觉文案解耦；分层验证顺序防「E2E 调试实际是语法错误」 |
| 跨 agent/重启/fallback 冒烟 | isolated-smoke-verification | 断言锚定持久化产物（会话 JSONL/任务库终态），不依赖易失 stdout；kill 前 `lsof` 定位＋cwd 验身，防误杀并发实例 |
| 证明「测的真」 | verification-rigor | 修复后必做验红实证（注回 bug 验红→恢复验绿）；变异意外全绿先疑编译时序假象；字段改名四面验证缺一即留盲区 |

- **运行安全红线（全包通用）**：环境表里的宿主 PID 绝对禁杀；禁止裸 `sbt run`（默认 home＋默认端口会抢宿主）；对宿主网关端口监听进程绝对禁发信号；kill 前 PID 验身（`lsof` 定位＋cwd 确认）。
- **进程清理纪律（全包通用）**：验证结束必须清理自己 spawn 的全部进程（隔离实例/mock server/静态服务/裸 playwright browser）；收尾三步＝杀（＋`pgrep -f` 兜底）→ `wait` 收尸 → `lsof` 复查端口释放；脚本包 `trap 'cleanup' EXIT INT TERM`。
- **关卡与方法论的分工**：关卡 skill 给「查什么、出什么格式的报告」；方法论 skill 给「怎么构造可信证据」——前端关卡的方法论内嵌引用 frontend-verification，冒烟法与严谨性纪律按需 Read。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| nebflow-qa-backend/SKILL.md | Scala 后端验证关卡：五维审核、运行安全、进程清理、✅/❌ 报告格式、只审不改 |
| nebflow-qa-frontend/SKILL.md | 前端验证关卡：隔离真实例三步验证、禁替身规则、FAIL 三条款、契约静态审核四维、只验不改 |
| frontend-verification/SKILL.md | 前端自验方法论：smoke harness、wire 探针、契约夹具、分层验证、Playwright 坑、盲点补验 |
| isolated-smoke-verification/SKILL.md | 隔离冒烟验证法：隔离实例搭建、持久化产物锚定、三信号三角定位、PID 验身、mock LLM 驱动 |
| verification-rigor/SKILL.md | 验证严谨性：验红实证、变异安全、spec 载力、sbt/异步排障、日志断言韧性、改名四面验证 |

## 6. 选 skill 决策树

1. **后端交付要验证**（编译测试、验收核对、后端质量三维）→ `nebflow-qa-backend`。
2. **前端交付要合并前独立验收**（冒烟、资源可达性、WS/REST 契约）→ `nebflow-qa-frontend`。
3. **前端改动要自验/交付 qa**，或调查「功能没接通」类 bug → `frontend-verification`。
4. **验证多 agent 交互/重启恢复/fallback**，或怀疑冒烟污染真实数据根 → `isolated-smoke-verification`。
5. **修复后要证明 spec 能抓回归**、跑变异验红、排障 sbt 挂起 → `verification-rigor`。
6. **质量评分审查**（三维评分 check-fix 循环）→ 不在本包：另配 `nebflow-pipelines` 的 review。
7. **修复代码** → 不在本包：本包只验/只审不改，发现问题只报告；修复交实现侧。
8. 常连用：一次交付＝实现侧自验（frontend-verification）→ 关卡验收（qa-backend/qa-frontend）→ 结论存疑时按方法论 skill 深化取证。

## 7. 共享件专节

本包无 assets/、scripts/、references/ 共享件——全部内容承载于五个 SKILL.md；包级资产面为空是本包的合法形态（skills 非空即满足协议）。各 skill 尾部 Evidence 节沉淀实战先例，是方法论正文的佐证面：新增先例追加到对应 skill 的 Evidence 节，不改写正文结论。

## 8. 扩展位（未来候选）

| 候选项 | 前置条件 | 备注 |
|---|---|---|
| 新域验证关卡 skill（如契约回归关卡、性能验证关卡） | 该域验证口径在生产中稳定跑通并沉淀出可复现清单 | 先入本表，落地后迁出并入 §5 |

> 只登记候选与前置条件；候选落地后迁出本表、入 §5 总目录。

## 9. 维护约定

- **红线不放宽**：宿主 PID 禁杀、宿主网关端口禁信号、禁替身、kill 前验身——四条是本包安全基线；skill 演进不得稀释，且五个 skill 的红线表述保持一致（同一条款改则五处同步）。
- **Evidence 沉淀**：新实战先例入对应 skill 的 Evidence 节（一行一条、不推翻正文）；若先例推翻既有正文结论，先改正文再补先例。
- **跨包口径**：与 `nebflow-pipelines` review 的「交付验证 vs 质量评分审查」分工边界、与 `nebflow-frontend-dev` visual-style 的视觉硬规则指涉，若调整两侧同步。
- **本文件定位**：Plugin.md 是按需 Read 面——装载器不注入；SKILL.md 是注入面权威，两者不一致时以 SKILL.md 为准并回改本文。
