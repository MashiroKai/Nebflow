# provider-concurrency · 01 只读侦察 + 实现契约

- 座位：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/provider-concurrency`（分支 `delegate/provider-concurrency`）
- 性质：**只读侦察 + 契约**。本步零产品代码改动（唯一写文件 = 本 md）。未提交、未 push、未 reset、未 kill 任何进程。
- 本文件是下游节点的唯一依据：签名逐字可照抄。

---

## 0. 结论先行

1. **现状**：模型链在**每次 LLM 请求**现算一次（`interface.scala:627` 非流 / `:818` 流），单一解析点 `registry.scala:460` `getCandidatesForAgentUnfiltered(agentModel)`；链首/顺序来自 `llm.fallback`（`FallbackChain.SectionKey`）或 agent 自己的 `agent.json` `model` 键；链尾由 `reserveTier` 追加"储备层"。**没有任何"按 actor 固定"的层**。
2. **不造第二套并发控制**：候选构造全仓只有 `registry.scala` 的 4 处（:188 / :217 / :415 / :513）；额外改序的只有 `SearchProviderResolver`（搜索注入排序，不构造候选）与 `LlmAllocator`（钉 utilityModel，不作用于 agent 请求）。**新机制 = 在既有链上钉一个"链首下标"，不走新选择路径、不数请求**（`AdaptiveThrottle` 数的是"已获票的**HTTP 请求**"，本批数的是"**actor 占坑**"，键、语义、闸位全不同）。
3. **API 与作者草案的偏差（重要，下游按本文件执行）**：草案的
   `ProviderConcurrency.assignFallbackChain(actorId: String, agentModel: Option[AgentModelConfig]): IO[PinnedChain]`
   **不可编译可用**——`SharedResources.spawnAgentActor` 拿不到 `ProviderRegistry` 级链解析：`SpawnParams.resources` 的类型是 `AgentRuntimePort`（`core/node/NodeRunner.scala:37`），该 trait 没有 `providerRegistry`（只有 `SharedResources` 具体类有，`SharedResources.scala:52`；测试里常传 `providerRegistry = null`，`ToolResultTtlSpec.scala:321`）。**本文件 §3b 给出修正版**，签名同样逐字可用。
4. **落地载体比"config 快照"更关键**：宿主的 `LlmRequest.agentModel` 是**每 turn 现读**——`freshDef = turnCtx.agentDef`（`AgentSessionExecution.scala:614`）→ `agentModel = freshDef.model`（`:1071`），`freshDef` 来自每 turn 的 `ContextRefresher.refreshTurn`（`:613`）→ `AgentLibrary.loadFromDir` → `SchemePolicy.resolveModel`（`AgentLibrary.scala:150`）。**所以把快照写回 `/model`（`agent.json`）会被每 turn 现读，等于每 turn 重算——直接违反『派发那一刻算一次、之后不变』**。必须走**每 actor 内存钉定**（§3c）。

---

## 1. 现状选择机制清单（谁在何时算链）

| # | 位置 | 何时 | 作用 |
|---|---|---|---|
| 1 | `src/main/scala/nebflow/shared/FallbackChain.scala:29`（`SectionKey` 在 `:32`） | 每请求（读内存 config） | 全局链单源 = `stand.json` 的 `llm.fallback`；`refs()` `:37` 把 `{preferred, fallbacks}` 摊平成有序 ref 列表 |
| 2 | `src/main/scala/nebflow/llm/registry.scala:460` `getCandidatesForAgentUnfiltered(agentModel)` | **每次请求** | 唯一候选解析点：`agentModel` 为 `None`/空 ⇒ 全局链（`:462`）；否则 `[preferred, ...fallbacks]` 逐 ref 解析（`:466-471`）；全解不出 ⇒ 回落全局链（`:470`）；末尾 `reserveTier`（`:475`） |
| 3 | `registry.scala:493` `reserveTier` | 每次请求 | 配置里存在但链未引用的 provider/model，按 providerId→modelId 字典序**追加**链尾（`:526`）；`disabled` 一律不进（`:503`） |
| 4 | `registry.scala:154` `getCandidatesUnfiltered` | 每次请求（agentModel 为空时） | 全局链：`llm.fallback` 有 ref 用它（`:165-166`），否则 Nebula 侧车链（`:168-170`），否则首 provider 首模型（`:210-229`） |
| 5 | `src/main/scala/nebflow/llm/interface.scala:627`（非流）/ `:818`（流） | **每次请求** | 请求路径唯一入口：`registry.getCandidatesForAgentUnfiltered(req.agentModel)`；随后会话 override 前置（`:628-631`/`:819-822`）→ `filterKnownProvidersDetailed`（`:635`/`:825`）→ 时段闸（`:639`/`:830`）→ 健康闸（`:644`）→ `Fallback.tryProviderWithFallback`（`:658`） |
| 6 | `src/main/scala/nebflow/core/SchemePolicy.scala:131` `resolveModel` | 每次 agent-def 装载（**每 turn 一次**） | `own-chain`（仅 settable 名：`:48`）> `nebula-chain` > `seed`；`seedChain` `:101` 读 `llm.fallback`（`:109-118`） |
| 7 | `src/main/scala/nebflow/agent/AgentLibrary.scala:150` | 每 turn 经 `ContextRefresher.refreshTurn` | `AgentDef.model = Some(resolvedModel)`（`:161`）——**这就是下发给 `LlmRequest.agentModel` 的东西** |
| 8 | `src/main/scala/nebflow/agent/AgentSessionExecution.scala:614` / `:1071` | 每 turn | `freshDef = turnCtx.agentDef` → `agentModel = freshDef.model` |
| 9 | `src/main/scala/nebflow/shared/protocol.scala:110` | 载体 | `LlmRequest.agentModel: Option[AgentModelConfig]`（唯一模型链下行通道） |

**其它请求构造点（不进 actor 链、本批不接）**：`OnboardingService.scala:248`（引导探测，一次性）、`AgentPingpong.scala:315`（pingpong shadowModel 钉死单模型）、`OpenAiChatShim.scala:118`（OpenAI 兼容 shim 点名单模型）、`SearchProviderResolver.scala:499`（搜索分类子请求转发同一 agentModel）。

**运行期链序稳定性现状**：同配置下可复现（`reserveTier` 显式排序，`registry.scala:502`），但**链首会随配置/占用变化**——今天没有任何"按 actor 钉死"的东西。

---

## 2. 第二套 / 绕过路径审计

### 2.1 候选构造点（全仓 `ModelCandidate(` 只有 4 处 + 类型定义）

`registry.scala:24`（定义）、`:188`（全局链腿）、`:217`（seed 兜底腿）、`:415`（`getCandidateForRef` 单 ref 腿，`disabled` 守卫在 `:407`）、`:513`（`reserveTier` 储备层腿）。**结论：无第二套构造点。**

### 2.2 改序 / 旁路选择的位置

| 位置 | 干了什么 | 判定 |
|---|---|---|
| `src/main/scala/nebflow/llm/SearchProviderResolver.scala:217-226` `modelChainRefs`、`:236` `chainHeadProviderId`、`:255-262` `searchOrderedModel` | 只从 `agentModel` **读**链首、为搜索注入**重排**（返回新 `AgentModelConfig`，`:261`），不构造候选 | **不算第二套**；它读同一个 `req.agentModel`，快照下发后自动一致 |
| `src/main/scala/nebflow/llm/decision/LlmAllocator.scala:179-208` `candidatesFrom` | 钉 `judge.utilityModel` 首位，其后接 `registry.getCandidates()`（全局链） | **编队域**（插件分配），不服务 agent 请求；本批不接，边界写明 |
| `src/main/scala/nebflow/llm/decision/DelegateJudgeMachine.scala:176-192` | 同上，`getCandidates()` 整链遍历 | 同上，不接 |
| `src/main/scala/nebflow/llm/imagegen/*`（`ImageGenAdapters.scala:65/93/120`、`ImageGenService/Jobs/Probe`） | 按 `ImageGenRequest` 点名 model，自建 HTTP body | **独立端点/凭据域，不经候选链**；不产生 actor。不接 |
| `src/main/scala/nebflow/gateway/OpenAiChatShim.scala:118` | 外部 OpenAI 兼容请求点名单模型 | 不产生 actor，不接 |
| `src/main/scala/nebflow/core/OnboardingService.scala:248` | 引导探测一次性请求 | 不产生 actor，不接 |

**结论：候选维度无第二套；编队/图片/外部 shim/引导四类旁路均不产生 agent actor、不动 `LlmRequest.agentModel` 的链首，本批一律不接并在回执写明。新增的 `ProviderConcurrency` 只读既有链（复用 §1#2 的解析）。收编清单 = 空。**

### 2.3 既有并发机制的边界（作者铁律：不许造第二套并发控制）

`src/main/scala/nebflow/llm/AdaptiveThrottle.scala`
- 键 = `provider.baseUrl`（`registry.scala:49-55` 注释；`hostOf` `:423`）→ 账户级；
- 计数对象 = **已获票的 HTTP 请求**（`inFlight`，`AdaptiveThrottle.scala:214-218` 注释与实现），`acquire`（`:218`）/`release`（`:247`）是**请求级**许可，闸位 = `GatedAdapter`（`registry.scala:101-105`），每次 `sendMessage` 都要过；
- 触发 = **429 反应式**，不是配置上限；总闸 `llm.adaptiveThrottle.enabled` 缺省 false。

**边界判定（写进回执）**：本批**不接** `AdaptiveThrottle`，也不让它数 actor；新增的 `ProviderConcurrency` 只做"**actor 派发时刻的链首选择**"，不做排队、不做拒发、不改在飞请求的并发度——**不是第二套并发控制，而是"派发时刻的编队"**。两者在计数对象（actor vs 请求）、键（providerId vs baseUrl）、生命周期（actor 终生 vs 单次请求）三处正交。

---

## 3. 设计契约（逐字可抄）

### 3a. 配置字段

```scala
// src/main/scala/nebflow/shared/config.scala —— ProviderConfig（当前 :120-179；建议插在 disabled(:158) 之后、headers(:178) 之前）
  /**
   * 该 provider 可同时「占坑」的 actor 上限（provider-concurrency 批）。None = 不限（缺省）。
   * 语义 = 派发时刻（spawn）该 actor 的链首落在此 provider 的 actor 数上限；超限 ⇒ 链首顺延到
   * 链上的下一个 provider（见 nebflow.llm.ProviderConcurrency）。
   * 🔴 形状纪律：Option 形（deriveDecoder 不认 case class 默认值，同 unavailableWindows/disabled）。
   * 🔴 深合并存活面：provider 对象上的键，provider 编辑弹窗保存须显式携带（见 §3h 前端）；
   *    不携带时 `ConfigService.mergeConfig`（ConfigService.scala:873-900）以旧值为基底保留——安全但不生效。
   */
  maxConcurrentActors: Option[Int] = None,
```

- **默认值依据**：`None` = 不限 ⇒ 现有部署行为**逐字节不变**（与 `unavailableWindows`/`disabled` 的"Option 缺省 = 零行为变化"范式同款，`config.scala:110-113/142/154`）。**不主张数字默认值**：任何非 `None` 缺省都会改变既有部署的派发链首，且作者没给数字。
- `<= 0`：**非法 ⇒ 当作 `None` 并 WARN 一次**（不要静默当 0 = 全拒，那会让链首永远落二档）。
- 配置面**只读内存 `configRef`**（热读），不在分配点读盘。

### 3b. 分配单点 API（修正版，逐字可抄）

新文件 `src/main/scala/nebflow/llm/ProviderConcurrency.scala`：

```scala
package nebflow.llm

import cats.effect.IO
import cats.effect.kernel.Ref
import nebflow.shared.{NebflowServiceConfig, ProviderConfig}

/** 派发时刻钉定的链（快照）。refs 之外不带任何可变量。 */
final case class PinnedChain(
  /** 链首 ref，`provider/model`；分配时被选中的那一根。 */
  headRef: String,
  /** 链首之后的全部 ref，原链相对顺序（自 head 之后；储备层已在调用方入参内）。 */
  orderedRefs: List[String],
  /** 每根 ref 的 provider/model（诊断面/可观测面用）。 */
  refs: List[(String, String)],
  /** 占坑的 provider（= headRef 的 provider id）。 */
  pinnedProviderId: String
)

object ProviderConcurrency:

  /** 计数表：providerId -> 该 provider 上占坑的 actorId 集合。 */
  final case class Counts(byProvider: Map[String, Set[String]])

  val empty: Counts = Counts(Map.empty)

  /** 配置解析（非法/缺省 ⇒ None = 不限）。 */
  private[llm] def capOf(p: ProviderConfig): Option[Int] = p.maxConcurrentActors.filter(_ > 0)

  /**
   * 分配：取「若该 actor 现在派发，会在什么链首上跑」，并**原子地把坑占上**。
   * 幂等：同 actorId 已在某 provider 上占坑 ⇒ 返回同一快照、不重算、不加计数、不迁移。
   *
   * @param orderedChain 全序候选 [(providerId, model)]——由调用方用
   *        `ProviderRegistry.getCandidatesForAgentUnfiltered(agentDef.model)` 的产物映射而来
   *        （已含储备层、已剔 disabled）。
   * @return None = 无可用 provider（空链／全部超限）⇒ 不钉，回落既有全局行为。
   */
  def assign(
    counts: Ref[IO, Counts],
    config: NebflowServiceConfig,
    actorId: String,
    orderedChain: List[(String, String)]
  ): IO[Option[PinnedChain]] =
    counts.modify { c =>
      val existing = c.byProvider.collectFirst { case (pid, ids) if ids.contains(actorId) => pid }
      existing match
        case Some(_) => (c, existing) // 幂等：永不迁移
        case None =>
          val chosen = orderedChain.zipWithIndex.find { case ((pid, _), _) =>
            val cap = config.llm.providers.get(pid).flatMap(capOf)
            cap.forall(limit => c.byProvider.getOrElse(pid, Set.empty).size < limit)
          }
          chosen match
            case Some((pair, _)) =>
              val pid = pair._1
              val upd = c.byProvider.updated(pid, c.byProvider.getOrElse(pid, Set.empty) + actorId)
              (Counts(upd), Some(pid))
            case None => (c, None)
    }.map { chosenPid =>
      chosenPid.flatMap { pid =>
        val idx = orderedChain.indexWhere(_._1 == pid)
        if idx < 0 then None else Some(pinAt(orderedChain, idx))
      }
    }

  /** 尾链策略 = 仅取被选中的下标之后（不回绕被跳过的前段）；见 §3d。 */
  private[llm] def pinAt(chain: List[(String, String)], idx: Int): PinnedChain =
    val (before, after) = chain.splitAt(idx)
    val head = before.last
    PinnedChain(
      headRef = s"${head._1}/${head._2}",
      orderedRefs = after.map((p, m) => s"$p/$m"),
      refs = head :: after,
      pinnedProviderId = head._1
    )

  /** 释放（幂等：坑不在就 no-op；永不报错、永不 decrement 别人的坑、计数永不为负）。 */
  def release(counts: Ref[IO, Counts], actorId: String): IO[Unit] =
    counts.update { c =>
      Counts(c.byProvider.view.mapValues(_ - actorId).filter(_._2.nonEmpty).toMap)
    }

  /** 启动自愈：计数表投影为「原表 ∩ 活 actor 集」（活 ID 集来自 AgentRegistry）。 */
  def reconcile(counts: Ref[IO, Counts], liveActorIds: Set[String]): IO[Unit] =
    counts.update { c =>
      Counts(c.byProvider.view.mapValues(_ intersect liveActorIds).filter(_._2.nonEmpty).toMap)
    }

  /** 只读投影（/api/models/candidates 与 /api/health 共用；空表 ⇒ 空 providers）。 */
  def snapshot(counts: Ref[IO, Counts], config: NebflowServiceConfig): IO[io.circe.Json] = ???
```

**壳方法（`SharedResources` 侧，spawn 唯一调用面）**：

```scala
// src/main/scala/nebflow/agent/SharedResources.scala（新增方法，private[agent] ⇒ 编译期禁止其它调用方）
  /**
   * provider 并发上限 · 派发时刻分配（provider-concurrency 批）。**唯一调用点 = spawnAgentActor**。
   * 返回 None = 不钉（无上限／全 provider 满／链空／registry 未装配 ⇒ 零行为变化）。
   */
  private[agent] def assignFallbackChain(actorId: String, agentDef: AgentDef): IO[Option[nebflow.llm.PinnedChain]] =
    if providerRegistry == null then IO.pure(None)
    else
      for
        cfg <- providerRegistry.currentConfig
        chain <- providerRegistry.getCandidatesForAgentUnfiltered(agentDef.model)
        pinned <- nebflow.llm.ProviderConcurrency.assign(
          providerConcurrency,
          cfg,
          actorId,
          chain.map(c => c.providerId -> c.model)
        )
      yield pinned
```

> 实现节点必读：
> 1. **配置源单点**：`ProviderConcurrency.assign` 需要 `NebflowServiceConfig`（判 `disabled` 与 cap）。**只准读 `providerRegistry` 的 `configRef` 那一份**（热读、与请求路径同一份）——在 `ProviderRegistry` 上新增
>    `private[llm] def currentConfig: IO[NebflowServiceConfig] = configRef.get`。**禁止**在 `SharedResources` 里另开 `Config.loadServiceConfig()`（那会成为第二份配置真相）。
> 2. **链源单点**：`getCandidatesForAgentUnfiltered(agentDef.model)`——与请求路径同一个函数，不新建解析。
> 3. **计数表注入**：`SharedResources` 参数表末尾追加
>    `providerConcurrency: Ref[IO, nebflow.llm.ProviderConcurrency.Counts] = Ref.unsafe[IO, nebflow.llm.ProviderConcurrency.Counts](nebflow.llm.ProviderConcurrency.empty)`
>    （`Ref.unsafe` + 默认值先例：`SharedResources.scala:94/104/111/119`，存量测试构造零改动）。

**调用点约束（硬）**：`assignFallbackChain` 只在 `SharedResources.spawnAgentActor`（`SharedResources.scala:304-364`）**`system.spawn` 之前**调用一次（插在 `:323` 之后、`:324` 之前；快照写进 `AgentActor(...)` 新参数，见 §3c）。**禁止**在 `NodeRunner.scala:152` 的委托、9 个业务调用方（`DelegateTool:1512`、`SubTaskTool:284`、`BlockingSubagent:110`、`FlowDagExecutor:1221`、`NodeLoopRunner:100`、`ProjectActor:2189`、`NodeStarter:718`、`DelegateRevival:410`、`SessionResumption:265`）以及 `MailTool.scala:2318` 的 `agentActorBehavior` 直连 spawn 上加。

### 3c. 快照存放与下发

**载体**：新增参数（`agent/AgentActor.scala` 的 `AgentActor(...)` 参数表末尾 + `actor/AgentState.scala` 的 `AgentState(...)` 大参数表 + `SharedResources.agentActorBehavior` 工厂签名 `SharedResources.scala:409-445`，三处同追，默认值保零改动）：

```scala
  /** 派发时刻钉定的模型链（provider-concurrency 批）。None = 不钉（既有行为）。 */
  providerChainPin: Option[nebflow.llm.PinnedChain] = None,
```

**下发（零新选择路径）**：`AgentSessionExecution.scala:1071` 改为

```scala
            agentModel = agentModelWithPin(freshDef.model, stateForLlm.providerChainPin),
```

同文件私有纯函数：

```scala
  /** 未传 pin 的既有构造/测试字面量 ⇒ None ⇒ 既有行为逐字节不变。 */
  private def agentModelWithPin(
    base: Option[nebflow.shared.AgentModelConfig],
    pin: Option[nebflow.llm.PinnedChain]
  ): Option[nebflow.shared.AgentModelConfig] =
    pin.fold(base)(p =>
      Some(nebflow.shared.AgentModelConfig(preferred = Some(p.headRef), fallbacks = p.orderedRefs))
    )
```

语义（逐条写进回执）：
- **快照 = 派发时刻的链首**。之后每个请求都把 `AgentModelConfig(preferred = headRef, fallbacks = orderedRefs)` 交给 `registry.scala:460`，走**同一条**既有解析 ⇒ 该 actor 的链首不再受 `llm.fallback`/`agent.json` 后续变化影响（作者要的"链首不变"）。
- **`reserveTier` 可接受**：它只**追加**到 `:475` 的链尾（`registry.scala:493-527`，`:526` 明文"只追加"），不会改变 `preferred` = headRef ⇒ **运行期零重排保证 = 链首由快照 `preferred` 钉死；运行期追加只在 tail**。`orderedRefs` 已把储备层收进去了，所以请求期 reserveTier 只会与「已引用 ref」去重，不新增前插。
- **总闸关闭（无 cap 配置）时零行为**：`providerChainPin = None` ⇒ 返回 `freshDef.model` 原值 ⇒ 逐字节既有行为。
- **`agentModel` 为空的路径兜住**：`getCandidatesForAgentUnfiltered(None)`（`registry.scala:462`）走全局链；**只要 pin 存在，`agentModel` 就非空**。pin = None（全满/无上限/装配缺失）⇒ 完全交给既有路径，并打一行 WARN（§3h）。
- **禁止**：把快照写回 `agent.json` 的 `model` 键——`AgentLibrary.loadFromDir:150` 每 turn 现读，会把快照变成"每 turn 重算"（§0.4）。**本批最容易踩的坑**。

### 3d. 尾链策略（选定）

**选定：仅取被选中的下标之后（不回绕被跳过的前段）**，储备层已在 `orderedChain` 内随之后段同行。

理由（写进回执）：
1. **作者原话「其后按原 fallback 顺序接续」**：字面就是"之后"；回绕（`i` 之后 + 前段 `0..i-1`）是"越过链尾再回到已满的链首"，不是"接续"。
2. **回绕破坏"链首是唯一闸"**：链首 provider 满了，回绕等于又在 tail 里打它——"先拒绝再尝试"，与上限意图矛盾。
3. **回绕会与储备层交错**：`reserveTier` 已在 `:475` 拼在链尾，回绕会把被跳过的前段塞进 reserve 之后，产生与配置意图不符的顺序。
4. **储备层兜底不丢**：reserveTier 在请求期仍追加，链深结构不变（与 `ProviderChainDepthSpec` 的"有效链深 ≥ 1"纪律一致：`preferred` 永远在）。

**反例申报（诚实）**：链只有 2 根且第 1 根满 ⇒ 第 2 个 actor 的 tail = 空 + 储备层，失去"回绕到第 1 根"的能力——这是**故意**的（第 1 根满时回绕无意义）。若作者后要回绕，改动点只有 `pinAt` 一处（形状已留）。

### 3e. 计数单点

```scala
ProviderConcurrency.assign(counts, config, actorId, orderedChain): IO[Option[PinnedChain]]
ProviderConcurrency.release(counts, actorId): IO[Unit]
ProviderConcurrency.reconcile(counts, liveActorIds: Set[String]): IO[Unit]
ProviderConcurrency.snapshot(counts, config): IO[io.circe.Json]
```

- **计数对象 = 链首 provider 占坑，actor 终生不迁移**。
  - 表形：`Map[providerId, Set[actorId]]`；`assign` 的幂等分支保证一个 actor 最多占 1 坑。
  - **运行期 failover 不迁移占坑**（明确理由）：① 作者语义 = "**并发按 actor 计、在派发那一刻算一次**"；② 运行期请求在 fallback 里换 provider 是**单次请求**粒度（`interface.scala:658` `tryProviderWithFallback`），迁移 = 每次失败都改全表 = 在请求热路径上写并发闸（作者铁律「不许造第二套」的反面）；③ 迁移破坏 T2「同 actor 连续 N 次 provider 序列全同」。
  - **结论：占坑 = 快照的 `pinnedProviderId`，生命周期 = actor 生命周期，永不迁移。**
- **幂等键 = `actorId`**：`assign` 命中已在坑 ⇒ 原样返回同一快照（不重算、不加计数）——覆盖 BackoffSupervisor 重 spawn / Mail 复活 / SessionResumption 同 id；`release` 命中不在 ⇒ no-op。
- **并发安全**：**`Ref[IO, Counts].modify` 单点原子 CAS**（cats-effect `Ref.modify` 原子；先例 `AdaptiveThrottle.gates: Ref[IO, Map[...]]`，`AdaptiveThrottle.scala:94`）。**不得**新增 `java.util.concurrent` 锁 / `synchronized`（与作者 actor 化裁定对齐）。不回退为 actor 消息：本操作是 spawn 路径上一次读-改-写，`modify` 已线性化，actor 往返只增加异步边界、无收益。
- **性能**：`modify` 内只有 `Set` 的 O(1) 操作 + ≤ `providers.size` 次 cap 判断；spawn 频率 ≪ 请求频率。

### 3f. 释放出口清单

| # | 出口 | 挂钩点（file:line） | 方式 |
|---|---|---|---|
| 1 | **主出口：actor 停止**（正常完成/失败终态/取消/崩溃/`system.stop`/`stopAll`） | `src/main/scala/nebflow/actor/ActorSystem.scala:340-352` `loop.guarantee { registry.update(_ - path) (:338) *> cancelCurrentTurn (:343) *> onStop (:346-348) *> notifyWatchers (:350) }` | 在 `onStop` 之后追加 `providerSlotRelease(path.name)`。这是 **actor 生命周期的唯一收尸点**（`:335-339` 注释：所有退出路径都走 `guarantee`；`LocalActorRef.stop = fiberPromise.get.flatMap(_.cancel)` 在 `LocalActorRef.scala:66` ⇒ 也会走 `guarantee`）。**`ActorSystem` 不持有 `SharedResources`** ⇒ 在 `LocalActorSystem` 上暴露 `private[nebflow] var providerSlotRelease: String => IO[Unit] = _ => IO.unit`，由 `GatewayMain` 装配时赋值；未设置 ⇒ no-op（测试/极早期装配零改动）。**禁止**在 `ActorSystem` 里 new 第二份计数表 |
| 2 | **兜底 1：spawn 失败回滚** | `SharedResources.scala:316-360` for 体 | `assign(...).flatMap(pin => system.spawn(...).onError(e => release(actorId) *> IO.raiseError(e)))` |
| 3 | **兜底 2：启动 reconcile** | `GatewayMain` boot（参考既有 boot reconcile：`GatewayMain.scala:885-930` / `:1077-1090`） | 清残：`reconcile(counts, agentRegistry.get.map(_.keySet))`；不新增定时器/fiber |
| 4 | **兜底 3：释放幂等 + 计数下界** | `release` 实现（§3b） | `_ - actorId` 对不在的 key 无副作用；`filter(_._2.nonEmpty)` |
| 5 | **兜底 4：可观测自证** | `/api/models/candidates` + `/api/health`（§3h） | 重启前后 `inUse` 可读，作者能自查坑是否停在旧值 |

**逐条"为什么兜底够"**：
- **正常完成**：actor 不销毁（回 Idle），**坑不释放——这是对的**（并发按 **actor** 计、不按 turn；Idle actor 下次派发仍占同一坑 = 链首不变）。
- **失败终态 / 冻结（Frozen）/ Error**：actor 仍活（`AgentFrozen.scala:176` `touchRegistryActivity(... AgentStatus.Frozen)`），**不释放**（同一条理由）。
- **取消（用户 cancel / watcher giveUp / NodeCancel）**：两条腿都归同一点——`AgentCommand.Stop` 消息 ⇒ behavior 回 `Behaviors.stopped` ⇒ loop 正常退出；外部 `ref.stop` ⇒ `LocalActorRef.stop`（`LocalActorRef.scala:66`）⇒ fiber.cancel。**两者都走** `loop.guarantee`（`ActorSystem.scala:340-352`）⇒ 出口 1。
- **崩溃**：fiber 异常退出 ⇒ `loop.guarantee` ⇒ 出口 1。
- **热重启**：`HotRestart.quiesceReport`（`HotRestart.scala:105-132`）五域清空 ⇒ JVM 退出；新进程 = 空计数表 ⇒ 出口 3 重建。旧进程 release 是否跑完无关（新表从零起）。
- **`system.stopAll`**（`:179-184`）：`traverse_(_.fiber.cancel)` ⇒ 每个 fiber 走自己的 `guarantee` ⇒ 出口 1。
- **Mail 复活 / BackoffSupervisor 重 spawn / SessionResumption 同 id**：`assign` 幂等 ⇒ 不二次计数、不换 provider（T2 的第二条保证线）。

### 3g. 启动 reconcile（存活 actor 集）

- **真源 = `SharedResources.agentRegistry: Ref[IO, Map[String, AgentRecord]]`**（`SharedResources.scala:104`，注释 `:96-103`："本表自此是 agent 记录的**唯一**权威"）。**不是** `ActorSystem.registry`（fiber 表，含 bridge/非 agent actor）。
- **实现**：`reconcile(counts, liveActorIds = agentRegistry.get.map(_.keySet))`——`Counts` 本身同时表达"谁占哪个 provider"，**无需**改 `AgentRecord` / `NodeRunner.registerAgent` 签名（省掉 9 个调用方改动）。
- **boot 时序兼容**：boot 时 `agentRegistry` 为空 ⇒ 清空表；恢复路径（`SessionResumption`/`DelegateRevival`）用**原 id** spawn ⇒ `assign` 按当时配置重新选一次链首（**显式申报**：重启后是"新的派发"，与作者"派发那一刻算一次"一致）。
- 装配点：`GatewayMain` boot，`agentRegistry`/actorSystem 装配之后、接消息之前；不新增定时器、不新增 fiber。

### 3h. 可观测面

**（1）`GET /api/models/candidates`**（已有面：路由 `gateway/RegistryRoutes.scala:41-44`，实现 `registry.scala:715-769`）——**追加** `providerConcurrency` 键（既有键零改）：

```json
"providerConcurrency": {
  "assignable": [
    { "providerId": "openai",   "inUse": 10, "cap": 10,   "full": true  },
    { "providerId": "deepseek", "inUse": 3,  "cap": 5,    "full": false },
    { "providerId": "zhipu",    "inUse": 0,  "cap": null, "full": false }
  ],
  "totalActors": 13
}
```
`cap: null` = 不限；`full = cap != null && inUse >= cap`；`totalActors` = 占坑 actor 数。实现在 handler：`candidatePlan.flatMap(plan => sharedResources.providerConcurrencyJson.map(j => plan.deepMerge(Json.obj("providerConcurrency" -> j))))`。

**（2）`GET /api/health`**（`gateway/HealthRoutes.scala:72-76`）——追加键 `providerConcurrency`（"有状态才出现"纪律同 `throttle`：空表 ⇒ 载荷逐字节不变）：
```json
"providerConcurrency": { "providers": [ { "providerId": "openai", "inUse": 10, "cap": 10 } ], "totalActors": 13 }
```

**（3）分配/释放日志行（单点：`NebflowLogger.forName("nebflow.llm.provider-concurrency")`）**
```
[provider-concurrency] assign actor=<actorId> agent=<agentName> head=<provider/model> pinned=<providerId> inUse=<n>/<cap|unlimited> chainHead=<configured head ref> index=<i>
[provider-concurrency] no-pin actor=<actorId> agent=<agentName> reason=<no-cap-configured|all-full|empty-chain|registry-absent> chainHead=<ref>        (WARN)
[provider-concurrency] release actor=<actorId> provider=<providerId> inUse=<n>/<cap>                                                                  (DEBUG)
```

**（4）设置面板字段落点（前端）**
- `src/main/resources/web/js/sidebar/providersUi.js`
  - 字段表 `:335-355`（`showProviderModal` 的 `fields:`）——在 `unavailableWindows` 字段（`:348-354`）之后追加
    `{ key: 'maxConcurrentActors', label: t('provider.maxConcurrentActors'), type: 'text', value: p.maxConcurrentActors ?? '', placeholder: t('provider.maxConcurrentActorsPh') }`;
  - 值收集 `:661-664` + 保存 payload `:424-430`（`onSave(name, {...})`）——增补 `maxConcurrentActors: cap`（`cap === undefined` ⇒ toast 拒存；`null` ⇒ 显式删键 = 不限）；
  - 新 helper：`function parseActorsCap(raw) { const s = String(raw ?? '').trim(); if (!s) return null; const n = Number(s); return Number.isInteger(n) && n > 0 ? n : undefined; }`。
- 文案：`src/main/resources/web/js/locales/zh-CN.js`（`provider.unavailableWindowsHint` 在 `:1468`）与 `en.js`（`:1518`）各加 `provider.maxConcurrentActors` / `provider.maxConcurrentActorsPh`。
  - **自解释铁律**：zh 标语 `同时派发到该服务商的 agent 上限`；占位 `留空 = 不限`。禁说明书句式。
- 组件契约：该弹窗是既有 `showModal` 表单（非 `<nf-*>` 组件），不触发 `check-component-contract.mjs`；但须过 `make frontend-gates`（`Makefile:108-110`，含 `check-design-tokens.mjs`）——本批不加样式，预期零命中（仍需实测）。
- **`contracts/*`**：全仓只有 `contracts/ptc/v1/`（PTC 协议 schema），**不含 provider/设置面契约** ⇒ **本批 `contracts/*` 无落点（N/A，回执申报）**。

---

## 4. 测试可断言性（T1–T4 的公共缝 / 改前为什么红）

**缝 1（纯分配器最快）**：`ProviderConcurrency.assign/release/reconcile` + `Ref.of[IO, Counts]` + 手工 `NebflowServiceConfig(llm = ServiceLlmConfig(providers = ...))`（`config.scala:563-594`；先例 `FallbackChainCandidateSpec.scala:35-39`）。
**缝 2（链解析）**：`new ProviderRegistry(Ref.of[IO, NebflowServiceConfig](cfg), NullBackend)`（先例 `FallbackChainCandidateSpec.scala:38`）——`getCandidatesForAgentUnfiltered(Some(pin 物化的 AgentModelConfig))` == 期望尾链。
**缝 3（端到端下发）**：`LlmInterface.createLlm` + 本地 `HttpServer` 假 provider（先例 `FallbackSeamSpec.scala:87-126`；`:126-136` 组装 `LlmRequest(agentModel = ...)`）；或 `CaptureLlm extends LlmHandle`（先例 `ToolResultTtlSpec.scala:278-284`）+ `SharedResources(...)`（先例 `:302-325`，`providerRegistry = null` 可保留，新参数有默认值）。
**缝 4（actor 生命周期）**：`ActorSystem.apply("test")` + `system.spawn` + `system.stop(ref)`/`fiber.cancel` + 断言 `counts.get` 归零。

### T1 —— 10 占满 ⇒ 第 11 个落二档；A 释放到 3 后链首不变
- **构造**：缝 1/2（主）+ 缝 3（下发）。config：`openai/m1` cap=10、`deepseek/m1` cap=None、全局 `llm.fallback=[openai/m1, deepseek/m1]`；`orderedChain = [(openai,m1),(deepseek,m1),(zhipu,...reserve)]`。循环 `assign(a_i)` i=1..10 ⇒ 全 pin openai；第 11 个 ⇒ `headRef == "deepseek/m1"`。
- **第二段（作者原话）**：释放 a1..a7（openai 回到 3）后，再对 a11 `assign` ⇒ 幂等返回 `deepseek/m1`；对存活 a8..a10 再 `assign` ⇒ 仍 `openai/m1`；释放后新派发的 a12 ⇒ 落回 `openai/m1`（上限恢复）。
- **改前为什么红**：`ProviderConcurrency` 不存在 ⇒ spec 不编译（显式红，非静默跳过）；若只加壳不接线 `spawnAgentActor`，缝 3 下 `req.agentModel` 恒等于 `freshDef.model`（`AgentSessionExecution.scala:1071`），第 11 个 actor 的链首仍是 `openai/m1` ⇒ 断言 `deepseek` 红。

### T2 —— 同 actor 连续 N≥5 次调用 provider 序列全同
- **构造**：缝 3（`FallbackSeamSpec` 双 mock，`:38-40` 端口 `a`/`b`）+ `AgentState.providerChainPin = Some(PinnedChain("deepseek/m1", List(...), ...))`；发 N=5 次 `sendStream`；中途把 openai 占坑释放到 0、并发一次配置热更。
- **断言**：5 次请求的 `req.agentModel.preferred` 全 == `deepseek/m1`；**mock 命中序列全同**（`b.hits == 5 && a.hits == 0`）。
- **改前为什么红**：`AgentState.providerChainPin` 不存在（编译红）；且**错误实现**（快照写回 `agent.json`）会因 `AgentLibrary.loadFromDir:150` 每 turn 现读而在第 2 次 turn 回退链首 ⇒ 断言红。**这条同时是错误实现的护栏。**

### T3 —— 终态各路径减计数、N 轮回 0、release 幂等
- **构造**：缝 4。每轮：spawn → 触发 ① `system.stop(ref)`、② behavior 抛错退出、③ `cancelCurrentTurn`、④ `system.stopAll`；断言 `counts.get.byProvider` 全空；再对同 `actorId` 重复 `release` 2 次 ⇒ 不报错、计数不为负。
- **改前为什么红**：`release` 不存在（编译红）；`LocalActorSystem.actorLoop`（`ActorSystem.scala:340-352`）无槽释放钩子 ⇒ 即使有 `release` 不接线，`inUse` 恒不归零 ⇒ 断言红（**接线完整性护栏**）。

### T4 —— 重启 reconcile 自愈
- **构造**：缝 1 直接造残留 `Counts(openai -> Set(a1..a10), deepseek -> Set(a11..a13))`，`liveActorIds = Set("a1","a2","a11")`；`reconcile` ⇒ `openai -> {a1,a2}`、`deepseek -> {a11}`。
- **T4-b（端到端）**：`SharedResources` + `agentRegistry` 仅含 a1/a2 两条 `AgentRecord` ⇒ boot reconcile 后 `providerConcurrencyJson` 里 `openai.inUse == 2`。
- **改前为什么红**：`reconcile` / `providerConcurrency` 键不存在（编译/字段红）；只加表不接 reconcile ⇒ `inUse` 停在旧值 ⇒ 断言红。

### 附：门禁（非 T 编号，必跑）
`sbt compile` / `sbt test`（相关 suite）；`make check`（含 `frontend-gates` `Makefile:108-110`、`component-contract` `Makefile:61`）；运行期不碰 6138（隔离实例纪律）。

---

## 5. 实现顺序（给下游）

1. `shared/config.scala`：`ProviderConfig.maxConcurrentActors: Option[Int] = None`（§3a）。
2. `llm/ProviderConcurrency.scala`：新文件（§3b）。
3. `llm/registry.scala`：`private[llm] def currentConfig: IO[NebflowServiceConfig] = configRef.get`。
4. `agent/SharedResources.scala`：+参数 `providerConcurrency`（末尾，默认值）；+`assignFallbackChain`（private[agent]）；`spawnAgentActor:304-364` 在 `:324` 前调用一次，pin 传入 `AgentActor(... providerChainPin = pin)`，并 `onError(release)`。
5. `agent/AgentActor.scala` + `agent/SharedResources.agentActorBehavior`（`:409-445`）+ `actor/AgentState.scala`：+`providerChainPin: Option[PinnedChain] = None`，state 落位。
6. `agent/AgentSessionExecution.scala:1071`：`agentModel = agentModelWithPin(freshDef.model, stateForLlm.providerChainPin)`（§3c）。
7. `actor/ActorSystem.scala`：`LocalActorSystem` 暴露 `providerSlotRelease`（默认 no-op）；`loop.guarantee`（`:340-352`）`onStop` 后调用。`gateway/GatewayMain.scala` 装配赋值 + boot reconcile。
8. `gateway/RegistryRoutes.scala:41`、`gateway/HealthRoutes.scala:72`：可观测面（§3h）。
9. 前端 `sidebar/providersUi.js` + `locales/{zh-CN,en}.js`（§3h）。
10. 测试 T1–T4（§4）+ 门禁。

---

## 6. 未决项 / 风险（显式申报）

1. **`SharedResources.providerRegistry` 是必填参数，测试传 `null`**（`SharedResources.scala:52`；先例 `src/test/scala/nebflow/core/compact/ToolResultTtlSpec.scala:316`，另 `:498`）⇒ `assignFallbackChain` 必须 `if providerRegistry == null then None`（§3b 已写）。检查 `null` 是唯一手段（不改类型）。
2. **`ActorSystem` 是 object 单例**（`ActorSystem.apply` `:84`），每进程一份；槽释放钩子必须**可赋值且默认 no-op**，否则测试隔离被污染。
3. **快照写回 `agent.json` = 错误实现**（§3c）、**在请求路径上迁移占坑 = 错误实现**（§3e）。
4. **ref 解析空**：`assign` 返回 None ⇒ 既有全局链 = 既有失败形态（不新增失败模式）。
5. 本文件不改产品代码、不提交 git、不 kill 进程、不 push/reset。

---

## 7. 下游可直接抄的验收断言摘要

- cap=10 已用 10 时，第 11 个 actor 的 `headRef` = 链上第 2 根；
- 释放到 3/10 后，**同一个 actor** 再询问 `headRef` 不变（幂等，`Counts` 不变）；
- 同 actor 的 `AgentModelConfig = (Some(headRef), orderedRefs)`，**每 N 次请求全同**（`LlmRequest.agentModel` + mock `hits` 双断言）；
- 每条终态路径后 `Counts` 归零；`release` 重复调用 no-op；
- `reconcile(残留计数, 活ID集)` 后 `inUse == |活ID集 ∩ provider|`；
- `/api/models/candidates` 与 `/api/health` 的 `providerConcurrency` 键形状见 §3h；provider 弹窗新增「同时派发到该服务商的 agent 上限」。

---

## 8. 重跑复核记录（2026-11-06 12:4x CST，第二轮）

上一轮会话因引擎 provider 链全灭（Deepseek 402 / cmdcode 429 周配额 / zhipu 软回避）中断，非侦察质量问题；本轮为**只读复核重跑**，对上文全部承重锚点逐条在 `delegate/provider-concurrency` 工作树（HEAD `1d92528a8`，main tip `2de196528` 在祖先链内）重新打开验证。**契约本体零改动**；仅修正三处行号/表述漂移：

1. §4 缝 1：`config.scala:564-594` → **`:563-594`**（`ServiceLlmConfig` case class 在 `:563`）；先例 `FallbackChainCandidateSpec.scala:39-45` → **`:35-39`**（`cfg` `:35`、`mkRegistry`+`NullBackend` `:38-39`）。
2. §6#1：`ToolResultTtlSpec.scala:321` → **`src/test/scala/nebflow/core/compact/ToolResultTtlSpec.scala:316`**（`providerRegistry = null` 首现 `:316`，另一构造点 `:498`；`CaptureLlm` `:278-284`、`SharedResources(...)` `:302-325` 均已复核）。
3. §3f 取消行：补全双腿——`AgentCommand.Stop` 消息腿（behavior `Behaviors.stopped` → loop 正常退出）与外部 `ref.stop` 腿（`LocalActorRef.scala:66` fiber.cancel）**都落** `loop.guarantee`（`ActorSystem.scala:340-352`）；原表述把两腿并写成一条直线。

复核确认（抽样清单，全部 file:line 亲验）：`FallbackChain.scala:29/:32`；`registry.scala:154-234/:250-275/:460-477/:493-527`（含 4 处 `ModelCandidate(` 构造 :188/:217/:415/:513）；`interface.scala:620-791`（非流，:627 取链）/`:793+`（流，:818 取链）/`sessionOverrides :442`/override 前置 `:628-631`/`:819-822`/`applyScheduleGateDetailed :94`；`AdaptiveThrottle.scala`（键=baseUrl `:39`、请求级 acquire/release `:218/:247`、`GatedAdapter :544-556`、缺省关 `:53-55`）；`config.scala:120-179`（ProviderConfig 全 Option 形纪律 :110-113/:142/:154）；`ServiceLlmConfig :563-594`（`fallback :580`、`adaptiveThrottle :593`）；`SharedResources.scala:51/:52/:104/:214/:304-364/:409+`；`NodeRunner.scala:37`（SpawnParams.resources: AgentRuntimePort）/:149-153 委托/:238-264 registerAgent；`AgentRuntimePort` 窄视图**无 providerRegistry**（仅 `healthMonitor: ProviderHealthPort :88-89`）；九调用方 + `MailTool.scala:2318` agentActorBehavior 直连；`ContextRefresher.scala:451-477/:494-502/:504-591`（每 turn 现读 def——§0.4 禁写回 agent.json 的依据）；`AgentLibrary.scala:131 loadFromDir`/:150 resolveModel/:161 `model = Some(resolvedModel)`；`AgentSessionExecution.scala:613-614/:1055-1076`（`agentModel = freshDef.model :1071`）；`AgentPingpong.scala:289-321`（shadowModel `:303/:315`）；`AgentActor.scala:1084-1124`（初始 AgentState）；`AgentState.scala:778-820`（**从不持久化** :790-797 注释）；`ActorSystem.scala:170-174 stop`/:179-184 stopAll/:340-352 guarantee 收尸；`LocalActorRef.scala:66`；`BackoffSupervisor.scala:36/:57/:1805`（终态三清含 registry 摘除）；`BlockingSubagent.scala:255-283`（reap :271-273）；`FlowDagExecutor.scala:1285`；`AgentControlTool.scala:1125/:1183`；`WebSocketRoutes.scala:279/:499`；`SessionChildCascade.scala:61`；`AgentFrozen.scala:176`（Frozen 仍活）；`SchemePolicy.scala:48/:101/:131`；`SearchProviderResolver.scala:217-226/:236/:255-262`；`decision/LlmAllocator.scala:168-207`、`DelegateJudgeMachine.scala:156-192`；`OnboardingService.scala:248`；`OpenAiChatShim.scala:118`；`HealthRoutes.scala:72-76`（throttle 键先例）；`RegistryRoutes.scala:36-43`；`contracts/` 仅 `ptc/v1`（§3h N/A 成立）；`providersUi.js:335-356/:424-431/:658-666`；`Makefile:60-61/:108-110`；`GatewayMain.scala:885-893/:1077-1082`（boot reconcile 先例）。**结论：本文件 §0-§7 为本轮生效版本，下游照此执行。**
