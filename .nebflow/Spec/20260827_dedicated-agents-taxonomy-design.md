# Flow/Team Agent 专用化分类基线设计

> 版本日志：v1.0（2026-08-27，Explorer 初稿，待用户裁定）
> 背景：用户 2026-08-27 11:53 裁定——flow 与 team 的 agent 不应基于全局 standalone agent 复用，应专门定义。
> 方法：只读探索 `~/.nebflow/{agents,flows,teams,sessions}`，未改任何代码或定义。
> 结论速览见 §0；证据原文摘录见附录。

## 0 结论速览

1. **问题不在预定义 flows**——9 个 flow 全部有本地 agents 目录、零同名 fallback（程序化交叉验证，见 A2）。真正缺口在两处：
2. **动态 flow 是全局 standalone 的裸引用通道**：今日 deck-v6 视觉生产 flow 的 10 个节点直接引用 Explorer×5 / Coder×4 / design-engineer×2，qa/复审节点拿到的是带 Pop+Screenshot 人设的设计工程师（证据：附录 E-3）。
3. **GUIDE.md D3 明文鼓励该行为**：「no flow-local dir → loads the GLOBAL agent…Prefer this over copying」——机制根源，需改写。
4. **人设复制残留已在预定义 flows 出现**：release-stable/coder 与 memory-consolidation/consolidator 第一行均为逐字复制的「You are Coder, a deep coding specialist running inside Nebflow」（后者连名字都谎报）。
5. 方案方向：三层分类（§B）+ 工具护栏与白名单（§C）+ deck-v6 家族专属定义（§D），分阶段验收（§E），运行中流程禁改红线优先（§F）。

## A 现状盘点

### A1 全局 standalone 清单 × 用户面向能力

25 个全局 agent，逐个读 `agent.json` 的 `tools` 与 `system.md` 人设语句。**显式声明用户面向工具的只有 3 个**：

| Agent | 声明的用户向工具 | system.md 人设关键词（面向用户的证据） |
|---|---|---|
| **Explorer** | `Pop`, `AskUserQuestion` | 「你是一个单次调用的 standalone agent…展示（结构化汇报）」「只有以下情况可以结束轮次：1. 用户已批准方案」「Pop 展示方案」——全程以「人读得懂」为交付标准 |
| **design-engineer** | `Pop`, `Screenshot`, `AskUserQuestion` | 「Nebflow 前端设计质量关卡（standalone 全局 agent，跨团队服务）…产出设计规格书 + 视觉评审」——规格书/评审报告均预设人类读者 |
| **Coder** | （无 Pop 但有）`AskUserQuestion`, `TransferFile` | 深度编码 specialist、通用交付习惯；本轮它作为 deck-v6 worker 也暴露了受众误判（附录 E-4「Manager 可 Pop 交付」判词） |

其余 22 个（Backend/Frontend/Nebula/Mail/content-planner/czt-* 等）`agent.json` tools 为空数组或不含 Pop 类工具——但注意：**空数组≠剥离**，引擎默认工具集行为需 engine 侧确认（本文不假设）。

两类身份条款横切特征（复制传播源头）：
- Explorer/design-engineer 属「能力特化 + 用户受众」复合体：既携带可复用的方法论（切块规划、视觉评审框架），又捆绑面向人的交付仪式（Pop、AskUserQuestion、终稿等待批准）。被流水线复用时会整包带入。

### A2 Flows 引用现状（预定义 + 动态）

**预定义 flows（9 个）——程序化交叉验证结果：每个节点的 agent 名都在 `flows/<name>/agents/<agent>/` 有本地目录，零同名 fallback。**

| Flow | 节点数 | 本地定义覆盖 | 全局人设残留 |
|---|---|---|---|
| code-review | 3 (scanner/reviewer/fixer) | ✓ | 无 |
| entity-creator | 7 | ✓ | architect 内有「Explorer (global)…Copy-template for research/analysis roles」字样（把复制实践写进了文档，system.md:60） |
| git-merge | 3 | ✓ | 无 |
| memory-consolidation | 2 (scanner/consolidator) | ✓ | **consolidator 第一行逐字复制 Coder 人设**「You are Coder, a deep coding specialist running inside Nebflow」（名字都错位） |
| nebflow-review-merge | 3 | ✓ | 无 |
| presentation-prep | 5 + r1-r4 researcher | ✓ | visual-designer 写「像 design-engineer 出规格」——方法论引用而非复制，问题轻；但其「落盘 deck-plan」路径正确区分了写文件/展示 |
| release-stable | 3 (reviewer/coder/packager) | ✓ | **coder 第一行逐字复制全局 Coder 人设**（同名复制，受众条款缺失） |
| research | 4 | ✓ | 无 |
| weekly-summary | 2 | ✓ | 无 |

**动态 flows——真正缺口。** 动态 flow 由 Mail 发起方在 `FlowExecute` 里即时写节点定义，agent 名只能填全局名。今日实例：deck-v6 视觉生产 flow（Manager 会话 28820ea6 msg210），10 个节点全部裸引用全局 standalone：

```
planner=Explorer, restrct=Coder, worker-fanout=Coder(×N), join=Explorer,
qa-fanout=design-engineer, judge1=Explorer, redo-launch=Explorer,
redo-fanout=Coder(×N), redo-review=design-engineer, integrate=Coder
```

qa 与复审节点拿到的是带 `Pop/Screenshot` 的设计工程师人设——本轮它向流水线内部 Pop 了可视化 HTML 报告（见 F1 红线说明与附录 E-1/E-2 取证约束）。

### A3 Teams 引用现状

10 个 team、33 个成员定义全部独立成文（无任何 `extends` 字段），总体比 flows 干净。逐个 grep 后的残留问题分三档：

| 档位 | 成员 | 问题 |
|---|---|---|
| 合法 | html-deck-studio/Manager | Pop 用于交付用户——Manager 本就是团队对用户接口，受众正确 |
| **同类错位（需裁定）** | nebflow-website/Frontend、nebflow-website/Designer | system.md 写「Use Pop to display the page …and visually verify」——把用户画布当自己的验证镜子，Pop 的实际受者是终端用户；正确姿势是 headless 截图自验 + Mail 报告 |
| 轻微 | nebflow-project/tool-engineer | 提及「Some tools are Nebula-exclusive (Pop, AskUserQuestion, Schedule)」——意识正确，仅提醒语，无需改 |

**团队模式的关键区别**：team 成员最后一轮输出由 Team Lead 经 Mail 消费（与 flow 经 FlowReport 消费同构），成员间可经 Mail 直触协作（如 QA 直触模式）。因此 team member 与 flow worker 的受众约束本质相同，差异在通信通道（§B3）。

### A4 根因定位

三层叠加导致今日事故：

1. **机制层**：GUIDE.md D3 把「无本地目录 → 加载全局同名 agent」定为推荐路径，动态 flow 天然只能走这条路。qa-fanout 引用 `design-engineer` 不是一个失误，而是当前规则下的标准动作。
2. **人设层**：全局 standalone 的身份条款写死「你的输出用户可见」（Explorer:「Pop 展示方案…AskUserQuestion 阻塞等待用户回答」），注入流水线后无任何受众校正——节点把下游节点当成了读者。
3. **工具层**：Pop 对节点始终可达（无引擎级剥离），LLM 在「幻觉的观众」面前选择表演性交付（可视化 HTML 报告、终轮长叙述）是概率必然。

故修复必须三刀齐下：改 D3 规则（§D4）、换人设基线（§B）、剥展示类工具（§C）——只做其一都会被另外两层漂移回来。

## B 分类基线设计

### B1 三层分类

| 层 | 受众 | 通信通道 | 展示类工具 | 身份条款核心 |
|---|---|---|---|---|
| **T0 全局 standalone**（Explorer/Coder/design-engineer…） | 终端用户 | 对话轮次 + Pop/Card | 允许 | 「用户可见、可交互、可等待批准」 |
| **T1 flow worker**（每个 flow 的节点专属定义） | 编排器 + 下游节点 | FlowReport（outputs/slots + verdict） | 禁止 | 「机器消费、落盘交付、限字」 |
| **T2 team member**（团队域专属定义） | Team Lead（收口者）→ 才可能达用户 | Mail + TeamTask | 默认禁止，白名单例外 | 「Lead 消费、直触协作有契约」 |

原则：**允许 T1/T2 复用 T0 的方法论段落，禁止复用其身份条款与交付仪式。** 方法论（切块规划、三铁律、验收基准）属知识，身份（谁读我的输出）属上下文——上下文必须按层重写。

### B2 flow worker 最小通用基线

**工具集基线**（T1 默认全部具备，按节点角色增删）：

- 必备：`Read` `Glob` `Grep` `Bash`（只读 + 进程操作）
- 角色性：写者节点加 `Write`/`Edit`；构建类加 `Bash` 高权限；检索型加 `WebSearch`/`WebFetch`
- 契约必备：`FlowReport`（引擎注入）
- **默认剥离**：`Pop` `Card` `AskUserQuestion`（理由与白名单见 §C）

**标准身份条款**（每份 T1 system.md 头部原样注入，engine 可在 harness 层兜底追加）：

```markdown
## 身份与受众（不可协商）
- 你是流水线节点。你的受众是编排器与下游节点——它们通过 $x.output 与
  slots 字段机器消费你的产出；终端用户不直接阅读你的任何文本。
- 禁止面向用户的展示类动作：不调用 Pop/Card，不制作「给人看」的
  可视化包装页、汇总美化稿。证据用截图落盘文件 + 路径引用代替。
- 中间产物一律 Write 落盘并在 FlowReport 给出绝对路径；
  「写文件给下游」永远优于「渲染给人看」。
- 禁止调用 AskUserQuestion：你没有对话对象。需求歧义时在 outputs
  中标注 assumption 字段并继续，由编排器路由裁决。
- 最终轮输出 ≤ <N> tokens：只写结论、状态与下游模板需要的字段，
  不写背景叙述、不写给用户看的总结语。
```

其中 `<N>` 按节点类别取值（§B4）。条款为一段固定文本，避免每份 system.md 手写漂移。

### B3 team member 变体与差异

T2 = T1 基线 + 三处差异：

1. **通道**：FlowReport 换成 Mail——「最终轮输出 ≤ N tokens」改为「Mail 回 Lead 的 [RESULT] ≤ N tokens」；跨成员直触协作（QA 直触模式等用户已裁定范式）保留，但直触消息同样受字数约束且须抄送面收口。
2. **歧义路由**：无 FlowReport verdict，故「assumption 标注并继续」的落点是 Mail 中显式 `[ASSUMPTION]` 行，Lead 负责裁决或升级 Nebula。
3. **工具白名单更宽一点但默认仍禁 Pop/Card**：现网已有反向案例（nebflow-website 用 Pop 自验，A3 表）——统一改为 headless 截图自验；仅当某团队有用户裁定的合法交付需求（如 design 预览稿需用户直接看）时，才在该成员定义中显式声明 Pop 并写明触发条件。

T2 身份条款在 T1 文本上替换两处：受众从「编排器与下游节点」→「Team Lead（它汇总后才会见用户）」，禁令中追加「你不与用户对话——用户通过 Lead 与你交互」。

### B4 最终轮输出预算参考值

以 deck-v6 实际用到的预算为锚点（说明现网已自发形成合理量级，只需标准化）：

| 节点类别 | 典型节点 | 预算 | 依据 |
|---|---|---|---|
| router/launcher | redo-launch | ≤100 tokens | 「输出一句话确认启动」已是现网要求 |
| judge/QA/report | qa-fanout、judge1、redo-review、join | ≤300–500 tokens | 现网各节点自带 ≤300/≤500 要求 |
| planner 切块 | deck-planner | ≤800 tokens（+blocks 数组不计入） | 现 planner 文本指南 ≤800 已实测可行 |
| worker 修改摘要 | worker-fanout、redo-fanout | ≤300 tokens/块 | 现 redo 要求 ≤300 |
| integrate 收尾 | deck-integrate | ≤500 tokens | 只报构建结果与路径 |

**baseline 值 = 类别默认，flow.json 可按节点覆盖但只能收紧不能放宽**；harness 断言只查「有预算条款 + 执行时不超」，不查具体数值。

## C 工具层护栏提案

### C1 leaf worker 默认剥离与保留

| 工具 | T1 leaf worker 默认 | 理由 |
|---|---|---|
| `Pop` | **剥离** | 唯一作用是打开用户 Canvas——对节点是纯错位交付 |
| `Card` | **剥离** | 同上（卡片也是用户面向 UI） |
| `AskUserQuestion` | **剥离** | 节点无对话对象；调用即死等。歧义走 assumption 字段 |
| `Screenshot` | **保留** | QA 类节点的取证动作（headless 截图落盘），产物给下游/Lead 消费，不是展示 |
| `Write`/`Edit`/`Bash` | 保留按角色 | 落盘交付是唯一合法交付形态 |

执行位置两层：engine 在装载 T1/T2 定义时从工具白名单扣掉默认禁用项（即使 agent.json 写了也不生效），harness 注入身份条款兜底话术。T0 与 Manager 类角色不受影响。

注意：**strategy 是「引擎剥离」而非「提示词求它别用」**——今日案例证明提示词层约束在错位人设下会被推翻。

### C2 白名单机制（flow 显式要回）

flow.json 节点级显式声明，engine 只在此标记下恢复工具：

```json
"qa-fanout": {
  "agent": "deck-qa",
  "userFacing": true,          // ← 显式要回：该节点可有 Pop/Card
  "input": "…"
}
```

配套约束：
- `userFacing: true` 时该节点的身份条款自动切换为「受众=用户 + 下游」（用户终审型节点确实两头都要喂），最终轮限字相应放宽。
- GUIDE.md 清单加一项：**凡 `userFacing: true` 必须在 flow 描述中写明「哪个环节天然面向用户」**；entity-creator 的 reviewer 检查此对应关系。
- T2 team member 不加 JSON 字段（成员定义本身即专属文件），白名单直接写在成员 system.md 工具声明里并注明触发条件。

### C3 判定表：「写文件给下游」≠「Pop 给用户」

| 动作 | 形态 | 受众 | 判定 |
|---|---|---|---|
| `Write(/path/deck-plan.md)` 后在 FlowReport 报路径 | 落盘文件 | 下游节点 Read | ✅ 合法且**首选** |
| 产 HTML 汇报页落盘 /tmp/xxx.html + 报路径 | 落盘文件 | 下游节点/Lead 决定是否 Pop | ✅ 合法（presentation-prep 视觉稿类） |
| 工作中调 `Pop(/tmp/report.html)` | 展示动作 | 终端用户画布 | ❌ 违规——「写出来」≠「推给用户」，Pop 的时机属于 Lead/Nebula |
| 最终轮文本堆砌叙述、写给「读者」的总结 | 输出风格 | 幻觉的用户 | ❌ 违规——超预算条款直接判 fail |
| headless 截图 → 存文件 → 报路径 | 取证 | 下游验收节点 | ✅ 合法（Screenshot 保留的原因） |

一句话判据：**节点的每一份产出都应有下游消费者名字；说不出消费者的产出，就是错位交付。**

### C4 合法场景推演（presentation-prep 类）

以 presentation-prep 现网定义推演（它是唯一「视觉产物密集」的 flow，最容易被误伤）：

- `visual-designer` 逐页 spec + 落盘 deck-plan md → 无需任何展示工具，基线全覆盖 ✓
- `researcher` ×4 网络检索 → 基线 + WebSearch/WebFetch ✓
- `verifier` 校验素材真实 → 基线（截图取证不需要）✓
- **不需要 Pop 的环节全部被基线覆盖**；最终 deck 的 Pop 由上游 html-deck-studio Manager 完成——职责本就属于 Lead 而非流水线节点。

若未来确有「flow 自动交付给用户」需求（如 release-stable 发布后自动 Pop 安装包链接），走 §C2 白名单显式声明，且实体审查时质询「为什么这个交付不能由 Lead 收口」。默认答案应是「能收口」，白名单是例外不是便利。

## D 落地清单

### D1 预定义 flows 人设句修正（低风险，随时可做）

三处小改，不依赖 engine 改造：

| 文件 | 现状 | 改动要点 |
|---|---|---|
| `flows/release-stable/agents/coder/system.md` | 第一行逐字复制全局 Coder 人设 | 首行改为「You are the coder node of release-stable」+ 注入 T1 身份条款（受众=reviewer 节点） |
| `flows/memory-consolidation/agents/consolidator/system.md` | 同上，且名字谎报为 Coder | 同上；名字改为 consolidator 本名 |
| `flows/entity-creator/agents/architect/system.md:60` | 「Explorer (global)…Copy-template for research/analysis roles」 | 该行删除或改写——复制模板实践随本方案废止，改为指向 §B 基线条款 |

顺带统一：presentation-prep/visual-designer 的「像 design-engineer 出规格」改为「按三铁律产出可断言 spec」（去人名化，方法论内联）。

### D2 动态 flow agent 解析缺口（engine 前置需求）

动态 flow 的 agent 名目前只能解析到全局层。本方案实施需要 engine 提供其一（本文只列接口要求，不涉实现）：

1. **首选**：FlowExecute 支持 `agentPack` 字段——引用某个预定义目录的专属 agents 集（如 `teams/html-deck-studio/agents/` 下的 deck 家族）；或
2. FlowExecute 的 node 定义支持 inline `system`（节点即定义），engine 对缺省 system 的节点注入 T1 身份条款并剥离展示工具；或
3. 维持现状但将 D3 fallback 从「加载全局」改为「加载全局但强制套 T1 基线」（最弱，仅兜底）。

无论哪条路，验收断言相同：任何 flow 节点的运行时 system prompt 都必须含受众条款且工具清单不含 Pop/Card/AskUserQuestion（见 §E2）。

### D3 deck-v6 家族专属 agent 定义骨架

按 deck-v6 现网 10 节点 1:1 映射，落地位置建议 `teams/html-deck-studio/agents/`（团队域专属，Manager FlowExecute 直接引用）。每份骨架 = 目录名 + tools + 一句职责 + T1 条款自动注入。结构如下（不写全文）：

| 目录 | 源自 | tools 基线 | 职责一句话 + 输出契约要点 |
|---|---|---|---|
| `deck-planner/` | Explorer | Read/Glob/Grep/Bash/FlowReport | 读 slide-structure-v2.md 切块 → outputs.blocks 数组；文本指南 ≤800 tok |
| `deck-restrct/` | Coder | Read/Glob/Grep/Bash/**Write**/Edit/FlowReport | 物理重组唯一写者，迁移旧 parts → build-parts/BXX.md；摘要 ≤300 |
| `deck-worker/` | Coder | 同 restrct | 单块页面精修（Slidev HTML）；修改摘要 ≤300；落盘即交付 |
| `deck-join/` | Explorer | Read/Bash/FlowReport | 聚合 worker 输出成总览 ≤500 tok；原样回传 blocks 数组 |
| `deck-qa/` | design-engineer | Read/Glob/Grep/Bash/Screenshot/FlowReport | 文件级逐页审查（三铁律/重叠/字号…）→ 首行 VERDICT + 问题块清单；**Screenshot 保留、Pop 剥离** |
| `deck-judge/` | Explorer | Read/Bash/FlowReport | 统计全部 qa 结果 → verdict=pass/fail + failBlocks 数组 ≤300 |
| `deck-relaunch/` | Explorer | Read/FlowReport | 确认 fail 块、回传 failBlocks 一字不改 ≤100 |
| `deck-redo-worker/` | = deck-worker 复用或软链 | 同 deck-worker | 重做单块；不复述 QA 全文只引用路径 |
| `deck-review/` | design-engineer | 同 deck-qa | 独立复审 fail 块（非修改者本人）→ remainingFail 数组 ≤300 |
| `deck-integrate/` | Coder | Read/Bash/**Write**/Edit/FlowReport | 构建 + 自验 + 交付物落盘 ppt/index.html + pdf；报 commit 与端口 |

条款注记：全部节点注入 §B2 身份条款；qa/review 的「截图落盘」属取证（C3 表合法行）；integrate 的 Pop 需求不存在——它只写文件，交付展示由 Manager 收口。

### D4 GUIDE.md D3 条款修订要点

GUIDE.md D3 现文鼓励「无本地目录 → 加载全局」。修订方向：

1. 主推路径反转为「每个 flow 的每个节点必须有本地定义（或 §D2 的 agentPack）」，同名 fallback 降级为「迁移期兼容 + 强制 T1 基线套壳」；
2. 保留其反对「逐字复制全局 system.md」的立场——本方案同样禁止复制，主张的是**重写身份、复用方法论**；
3. 清单新增两条：C7「节点 agent 无本地定义即 load-time reject（或告警 + 基线注入）」；C8「`userFacing: true` 必须配对 flow 描述中的用户面向环节说明」（§C2）。

## E 分阶段验收标准

### 阶段 1：本文档定稿可审（binary 检查）

全部 binary 判定，逐条可跑：

| # | 断言 | 命令 |
|---|---|---|
| E1.1 | 文档存在且 ≤400 行 | `wc -l <本文件>` ≤ 400 |
| A–F | 六章齐全非空 | `grep -c "<![-]- SEC" 本文件` = 0（骨架占位全部被替换） |
| E1.2 | 证据可复查 | 附录每条引用的 session 文件存在且 msg 序号能定位原文 |

用户裁定通过即阶段 1 收口，进入实施排期。

### 阶段 2：实施后断言

**静态断言（CI 可跑，二值）**

| # | 断言 | 实现要点 |
|---|---|---|
| E2.1 | 预定义 flows 零人设复制句 | `grep -rn "You are Coder, a deep coding specialist" ~/.nebflow/flows/ teams/*/agents/` = 空 |
| E2.2 | 每个节点有本地定义 | 解析 `flows/*/flow.json` 每个 node.agent → `flows/<n>/agents/<a>/` 目录存在（本轮已程序化验证过一遍，脚本固化进 CI） |
| E2.3 | T1/T2 定义均含身份条款 | `grep -rLn "受众" flows/*/agents/*/{system.md}` = 空（条款关键字命中） |
| E2.4 | 动态 flow 节点 prompt 含条款 + 工具剥离 | harness 抓包一次最小测试 flow 的 LLM 请求：system 里含「禁止面向用户的展示类动作」且 tools 数组不含 Pop/Card/AskUserQuestion |

**运行断言（冒烟优先——真实启动，非 oneshot/mock）**

| # | 断言 | 实现 |
|---|---|---|
| E2.5 | 流程引擎服务可跑通完整 flow | 真实启动 Nebflow → 触发最小现网 flow（weekly-summary 或 memory-consolidation，无副作用副作用面小）→ 全节点 FlowReport 正常回传 → 流程结束。首次启动日志核对全部初始化步骤 |
| E2.6 | 注入后最后一轮字数达标 | E2.5 的各节点最终轮文本按 §B4 类别预算断言 token/字符上限；超限即 fail（harness 记录到测试报告） |
| E2.7 | 端到端含落盘产物 | E2.5 中至少一个节点的 outputs 指向真实存在的文件路径，下游节点 Read 成功（证明「写文件给下游」链路替代了「Pop 给用户」） |

E2.5 不通过则其余运行断言无效（先保证活，再验对）。

## F 迁移风险与顺序

### F1 红线：deck-v6 运行中禁止中途改定义

deck-v6 视觉生产 flow 正在运行（redo-fanout 于 11:53 起在跑，integrate 未执行）。其节点提示词在 flow 启动时已固化——**本次实施开始前及进行中，禁止对 `agents/`、`flows/` 下任何被其引用的定义做增删改**（含全局 Explorer/Coder/design-engineer 与 html-deck-studio 团队定义），否则 fanout 子节点与 retry 可能拿到新旧混搭人设。

安全信号 = Manager 会话 28820ea6 出现该 flow 的终态（FlowReport pass 返回 / 「用户真人 PASS」记录）。在此之前本方案只读、只评审。

### F2 风险表

| 风险 | 概率 | 缓解 |
|---|---|---|
| 字数上限过紧 → QA/评审节点丢证据 | 中 | 截图/报告落盘路径引用代替正文（§C3）；预算按类别放宽参数并允许 flow 覆盖收紧 |
| 剥 AskUserQuestion 后节点歧义无处表达 | 中 | assumption 字段 + 编排器路由（§B2）；T2 走 [ASSUMPTION] 行 |
| D3 改写破坏现有动态 flow 习惯用法 | 中 | 迁移期保留 fallback 但套基线（§D2 路线 3 兜底），新 flow 才强制 |
| 引擎工具剥离误伤合法角色 | 低 | 默认只影响 T1/T2；T0 与 Manager 不动；白名单可逆 |
| 一次性迁移九个 flow 引入回归 | 低 | 预定义 flows 本就全部本地化，实际只有 3 处人设句小改（§D1）+ 动态通道收口，爆炸半径小 |

### F3 安全窗口与迁移顺序

| 步骤 | 内容 | 前置 | 可回滚 |
|---|---|---|---|
| S1（今天） | 本文档评审定稿；deck-v6 全程只读不动 | — | 纯文档 |
| S2（deck-v6 终态后） | §D1 三处人设句修正 + GUIDE C7/C8 条款补入 | F1 安全信号 | git revert 单文件 |
| S3（S2 后） | deck 家族 10 份专属定义落盘 `teams/html-deck-studio/agents/`（§D3 骨架填全文），不接线 dry-run | — | 目录整体删除 |
| S4（S3 后） | engine：T1/T2 工具剥离 + 身份条款 harness 注入 + §D2 agentPack/inline system | 引擎排期 | 功能开关 |
| S5（S4 后） | E2.1–E2.7 全量断言跑绿 → 下一次 deck 类任务改用 agentPack 实战验证 | E2.5 冒烟先绿 | 切回 FlowExecute 内联定义 |
| S6（观察一周后） | D3 主推路径反转成文，移除迁移期兼容 | S5 无回归 | 不建议回退（回退=问题复发） |

每个步骤独立 commit（`~/.nebflow` repo 与引擎 repo 分开），可按步独立 revert。

### F4 回滚方案

- 定义层：各步骤独立 git commit，`git revert` 即回。
- 引擎层：工具剥离与条款注入挂功能开关，关闭即恢复现网行为；不迁移数据、无状态残留。
- 唯一不可单点回滚的是「D3 主推路径反转后的行为差异」（S6）——它通过保留 fallback 兜底路径天然可回。

## 附录 证据摘录（原话引用）

**E-1 · 动态 flow 节点定义裸引用全局名**（`sessions/28820ea6-….json` msg210，html-deck-studio Manager 的 FlowExecute）：

> planner=Explorer, restrct=Coder, worker-fanout=Coder, join=Explorer, qa-fanout=**design-engineer**, judge1=Explorer, redo-launch=Explorer, redo-fanout=Coder, redo-review=**design-engineer**, integrate=Cod­er

qa-fanout 原文摘录：「你的职责（Read 本块 build-parts/BXX.md，逐页文件级审查）：①第一人称合规…⑩slideblocks-page/data-layout 结构完整 | 输出首行 VERDICT」——职责正确，但载体人设是「产出设计规格书 + 视觉评审、Pop 展示」的 standalone。

**E-2 · 用户面向工具清单对比**：Explorer tools 含 `Pop,AskUserQuestion`；design-engineer 含 `Pop,Screenshot,AskUserQuestion`（各自 agent.json）。今日全量会话扫描：Pop 工具调用仅 2 例，均为 Manager/用户合法交付（28820ea6 msg74 质量报告、5cc7590a msg130 论文 PDF）；deck-v6 节点会话（dag-deck-v6-*，尚存 6 份 worker 会话）无 Pop 调用——主流程 qa 节点会话文件已随 ephemeral 清理不可考，F1 因此按最保守假设禁止运行中改定义。

**E-3 · 机制根源原文**（`flows/GUIDE.md` D3）：

> R4's … a node's `agent` without a flow-local dir under `flows/<name>/agents/<agent>/` loads the GLOBAL agent of that name (`loadFlowAgent` fallback). **Prefer this over copying a global system.md into the flow**

**E-4 · 人设复制实证**：

- `flows/release-stable/agents/coder/system.md:1`：「You are Coder, a deep coding specialist running inside Nebflow.」
- `flows/memory-consolidation/agents/consolidator/system.md:1`：同句逐字复制（节点名为 consolidator）
- 受众误判语例（`dag-deck-v6-视觉-redo-fanout#8-254875.json` 节点判词）：「契约验收 PASS。**Manager 可 Pop 交付**（ppt/index.html + ai-fpga-deck.pdf），豁免三项随交付说明供用户终审。」——Coder 人设的 worker 在替上游考虑面向用户的交付仪式。

**E-5 · 团队域同类错位例**（`teams/nebflow-website/agents/Frontend/system.md:7`）：「You are not blind. Use Pop to display your work…」——成员把用户画布当自验镜子。
