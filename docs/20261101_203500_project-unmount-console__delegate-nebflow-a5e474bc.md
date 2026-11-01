---
type: batch
status: final
date: 2026-11-01
topic: 项目面板「取消加载」（unmount）+ 编排侧 remount 面——控制台与 Project 工具收口同一引擎通路；卸载态可见去处 + 审计 + 真实例冒烟
---

# 项目面板「取消加载」+ 编排侧 remount 面批（unmount-console 批）

- **日期**：2026-11-01
- **链**：delegate-nebflow-a5e474bc
- **作者令**：控制台「项目」面板加**取消加载**能力 + 编排侧 Project 工具面增强；语义与引擎既有 unmount 语义严格一致（卸载 = 从运行中 stand 实例摘挂载面：上下文/列表消失、不能再派活、**项目本身零触碰**）。

## 1. 需求拆解（作者令原话 → 实施面）

| 作者令条目 | 实施落点 |
|---|---|
| 项目卡「取消加载」按钮 + 确认卡（写清后果） | `projectTab.js` 卡头 `.project-unmount-btn` → `openUnmountConfirm`（既有 `#delete-box`，tone neutral）→ 确认才 `POST /api/projects/<name>/unmount` |
| 确认卡如实展示在飞工作（几条链/几个节点） | 新增 `GET /api/projects/<name>/inflight`（只读；计数判据与 `ProjectUnmount.stopInFlight` 取消靶**同源单点** `ProjectUnmount.inFlight`） |
| 卸载后的项目有**可见去处**（已卸载分组/灰态行）+「重新加载」 | `GET /api/projects` additive `unmounted` 数组 → 面板尾部 `.project-unmounted-group`（名字 + 工作区 + 重新加载钮）；`POST /api/projects/<name>/remount` |
| 卸载态跨重启保持（启动自动挂载跳过） | 既有单点（`ProjectStore.markUnmounted` → `list()` 源头过滤 → `GatewayMain.startupMount` 同源跳过）——本批只加 **remount 摘键恢复面**，不动既有序 |
| 编排侧补 **remount** 动作 + list 卸载态可见性 | `ProjectTool` `action="remount"`；`listFace` 尾部 Unmounted 节 |
| 控制台按钮与工具面**同一条引擎通路** | unmount = `ProjectUnmount.unmount`（单点双面：REST 腿 actor="console"、工具腿 actor=<sessionId>）；remount = 新单点 `ProjectRemount.remount`（REST 腿 / 工具腿同壳） |
| 卸载/重挂进审计日志（谁/何时/哪个项目） | `flow-map-events.jsonl` 新事件类型 `project-unmounted` / `project-remounted`（actor 进 summary） |
| 排水 deadline 超时即中止且不摘面 | 既有 `pollUntil` 行为保持（spec "refuses to unmount while a session survives" 继续钉住） |

## 2. 引擎改动（backend）

- `ProjectStore.clearUnmounted(name)`：手术式摘 `unmounted`/`unmountedAt` 两键（JsonObject.remove 同 updateDescription 清空腿；幂等：无标记 = Right(None) 零写）。
- `ProjectUnmount`：
  - `unmount(..., actor: String = "tool")`——审计动作方参数（控制台 REST 腿 "console"，工具腿调用会话 id）；
  - `removeMountFace` 成功腿追加 `project-unmounted` 审计行（`<brainWorkspace>/.nebflow/flow-map-events.jsonl`；零删除红线不涉——append 是 spec 头注认可的合法副作用）；
  - 新增 `InFlight` 读数 + `inFlight(name, registry)`（只读计数：链谓词 / Running 过滤 / dispatcher+delegate 会话过滤与 stopInFlight 同源）。
- `ProjectRemount`（新对象，remount 引擎单点）：校验盘面定义（listAll 大小写不敏感解析；archived ⇒ Left 不复活）→ 摘键（clearUnmounted）→ `ProjectRuntimeRegistry.mount`（幂等；本机预检/设备通道/大脑根锚全部复用挂载面既有铁律，零新造预检）→ `projectRemounted` 帧（`ProjectActor.projectRemountedFrame` 单点，row 与 GET /projects 行同构）→ `project-remounted` 审计行。幂等腿（已挂载）= no-op（零写/零帧/零审计）。
- `ProjectActor.projectRemountedFrame`：帧形单点（type/project/row{...}/remountedAt）。
- `ProjectsRoutes`：
  - `GET /projects` additive `unmounted` 数组（行形 = mounted 行 + unmountedAt；归档不出列）；
  - `POST /projects/<name>/unmount`（actor="console"；Left → 404 可行动文案；Right → 报告 JSON）；
  - `POST /projects/<name>/remount`（rootSessionId = `ensureActiveAgentSession(RootAgentIdentity.Name)`——与 startupMount **同一唯一真源**，禁自造占位根）；
  - `GET /projects/<name>/inflight`（未挂载 → 404）。
- `ProjectTool`：`action="remount"` 面（身份守卫镜像 mount 腿）；`listFace` 尾部 Unmounted 节；`showFace` 卸载/归档行改指 remount；create 面对卸载态的拒挂文案改指 remount 通路（拒挂语义本体沿用 [[20261030_100600_projcreate-device-flat-three-fixes__delegate-nebflow-a97665b1]] 时代的 create 面守卫，单程语义不变）。
- `ws.js`：GLOBAL_MSG_TYPES += `projectRemounted`。

## 3. 前端改动（console）

- `projectTab.js`：
  - 卡头摘要右侧「取消加载」钮（unplug 图标；sapphire Pattern A 中性玻璃，非 danger 红——卸载可逆有 remount）；
  - 确认卡：先拉 `/inflight` → `#delete-box`（neutral）展示「当前在飞工作：N 条链、N 个节点、N 个会话」+ 四要点后果（在飞停止/列表与上下文消失/不能派活/本地文件零删除）→ 确认才 POST；取消零请求；
  - `projectUnmounted` 帧处理扩展：定点退场 + 防抖重拉落「已卸载」分组；
  - `projectRemounted` 帧处理：按名定点恢复卡片 + 摘灰态行 + 播报（幂等：卡已在只摘行）；
  - 「已卸载」分组（列表尾部虚线分隔 + 标题/提示 + 灰态行：名字 + 工作区 + 重新加载钮；行按名定点增删，组头节点身份恒保持）；`clearPlaceholders`/`renderEmptyState` 对分组节点**原样保留**（摘下再挂回）；
  - 取数改 `fetchProjectsRaw`（additive unmounted 数组；旧后端无键 ⇒ [] 零分组）。
- `nodeData.js`：`projectUnmount/projectRemount/projectInflight` 端点常量 + `unmountProject/remountProject/fetchProjectInflight` 三态助手。
- locale（zh-CN + en）：`project.unmountBtn/unmountTitle/unmountInflight/unmountConfirm/unmountConfirmGo/unmountFail/unmountedGroup/unmountedGroupHint/unmountedRowTitle/remountBtn/remounting/remountFail/remountedAnnounce` 13 键双语。
- CSS：`projectPanel.css` `.project-unmount-btn`（原 `.project-archive-btn` 面位回归、类名随语义更替）+ `.project-unmounted-group/-unm-head/-unm-title/-unm-hint/-unm-row/-unm-name/-unm-ws/-unm-remount`（全 `var(--color-*)`/`--glass-control-*` token，零裸 hex，深浅主题自适应）；`sapphire.css` Pattern A 两处注册改名。

## 4. 测试与验收证据

- **引擎级 spec**（`ProjectUnmountSpec` 扩展 6 case，全绿）：
  - remount 往返（卸载 → remount 摘两键 → 复挂 → list/registry 恢复 → startupMount 幂等零新增 + 帧断言 + 审计两行 actor=console）；
  - remount 拒绝 archived / 未知名（零写回归）；
  - remount 幂等（已挂载 no-op：零写/零帧）；
  - clearUnmounted 手术式（恰两键消失、其余字节稳定、摘后可再打标）；
  - unmount 审计行（actor=tool 缺省腿，恰一行）；
  - inFlight 只读计数（链/Running/会话 + 快照与登记零变化 + 未挂载 None；`runningNodes` 排除已计入 chains 的链成员——链归链、孤立/单成员链 Running 归节点，同一件工作不双计，total 断言钉住口径）；
  - create 面拒挂 archived 语义不变（回归锚）。
  - 既有 5 case（三步序停飞顺序、零删逐字节、跨重启跳过自动挂载、排水超时中止不摘面、幂等重卸 + create 拒挂 unmounted）原样全绿。
- **控制台实拍**（`tests/project-unmount-panel.spec.mjs` 3 case 全绿；截图落 `docs/evidence/20261102_project-unmount/`）：
  - 确认卡（含在飞计数 1 链/2 节点/2 会话 + 四要点后果、neutral 确认钮非红）、深浅双主题；
  - 卸载后卡片退场 → 「已卸载」灰态分组（名字+工作区+重新加载），零 reload 断言；
  - 重新加载 → 卡片恢复、分组消失，零 reload 断言。
- **隔离真实例冒烟**：见 §5（真实 gateway + 真实 REST/WS + 真实盘面断言；**终轮跑在交付 jar `953ae4f63bf6` 上**——inFlight 口径修复后的 assembly，:8292 实例）。
- **静态门**：checkJs 0 errors（gate PASS）；build-web orphans 0。

## 5. 隔离真实例冒烟记录（脚本内联执行，过程留痕）

- 隔离 home：`mktemp -d`（`STAND_HOME`/`--home` 双保险，EnrollGuard 自动隔离，绝不触生产 :6138）；沙箱端口 8291（非 6138/8080）。
- 盘面预置：`projects/p-demo/project.json` + 工作区两文件（挂载前 sha256 基线）。
- `stand-assembly.jar start --port 8291 --home H --no-browser` → `/api/health` 200（startupMount 自动挂载 p-demo）。
- 控制台（Playwright 挂真实 token）：确认卡（真实 /inflight 读数）→ 确认卸载 → **断言**：卡片退场、「已卸载」分组出现、`project.json` 两键落盘、`GET /api/projects` unmounted 数组携带、工作区 sha 逐字节不变、审计行 `project-unmounted by console`；→ 重新加载 → 卡片恢复、两键摘除、审计行 `project-remounted by console`。
- 深浅双主题实拍落 `docs/evidence/20261102_project-unmount/`。
- teardown：**实例留盘待作者清理**（引擎无 shutdown 端点；安全纪律禁向 java 进程发信号）——gateway 仍在 :8291 服务，home `/tmp/nf-unmount-smoke-home`（`boot.log` 在其中），重置盘面已用 `POST /api/projects/p-demo/remount` 摘键复挂，p-demo 处于挂载态。
- **终轮复跑（口径修复后）**：inFlight 修口径（runningNodes 排除链成员，spec 13/13 绿）后重打 assembly（`953ae4f63bf6`），预置同法第二个隔离 home `/tmp/nf-unmount-smoke-home2` → :8292 实例重跑全部断言——证据 6 图 + `real-smoke-result.txt` **全部由交付 jar 产出覆盖落盘（21:07）**。两个冒烟实例（:8291 旧 jar / :8292 交付 jar）均留盘候作者清理。
- **排障留痕（harness 而非产品）**：中途三轮「卡不退场/双卡」假象 = runner 用 `import('/js/projectTab.js')` 源模块开面板，与 index.html 实际加载的 bundle 副本形成**双实例**（两 pane 抢同一帧处理器，`projectsListScroll()` 只认首个 active scroll）。改为**真实用户路径**（点击 `#projects-btn`）后一次全绿——WS 传输层 TAP 实证 `projectUnmounted`/`projectRemounted` 帧均到页、盘面/审计/sha 断言全过。产品面零改动；该坑只存在于测试驱动方式。

## 6. 判据声明（实施批自裁，非作者逐字裁定）

1. **GET /projects 的 unmounted 行**排除已归档定义（归档保持单程，remount 也不复活——与 remount 的 archived Left 分支闭环）。
2. **remount 对「定义在盘但无卸载标记、也未挂载」的重启残留态**：跳过摘键直接挂载（挂载面幂等语义的自然延伸；非「卸载态专属」收窄——面板只对卸载行出钮，工具面对该态调用同样可用）。
3. **REST Left 一律 404**（本文件链控三端点族惯例；文案自带可行动语义与「重试安全」声明），不引入 409 新状态面。
4. **inflight 只算「点了确认就会真被停的东西」**（与 stopInFlight 取消靶同源；外部执行器 delegate 无进程内会话，不在计数面——与 ProjectUnmount 头注的归属性边界一致）。
5. **stale spec 替换**：`project-panel-archive.spec.mjs`（锁定 2026-10-06 已移除的 `.project-archive-btn`，两 case 已实跑全红——本次替换批实证）→ `project-unmount-panel.spec.mjs`（同面位新语义回归 + 截图面）。sapphire/projectPanel 的 `.project-archive-btn` 死样式随语义更替改名 `.project-unmount-btn`。

## 7. 红线自检

- **零工作区删除**：`ProjectUnmount`/`ProjectRemount`/REST/前端**零** `os.remove`/`os.move`/清理逻辑（grep 自证见交付报告）；唯一写盘面 = project.json 手术式插/摘键 + `.nebflow/` 内审计 append。
- 在飞批工作区（待办复刻/@弹窗高亮/邮箱根治/output块提示词面/recorder F1F2/LLM 兼容）零触碰。
- 不 push；`--no-ff` 合并 main。
