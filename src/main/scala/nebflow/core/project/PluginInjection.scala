/* jev-plugins 批（#1226）：delegate / 链步会话的插件分配＋注入等价路径。
 * 分配面（PluginAllocator 三态）与解析面（原 NodeStarter.prepareNodePlugins 的
 * 解析块逐字迁入——行为保持，NodeStarter 改为委托本件）。 */
package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.core.delegate.ChainStore
import nebflow.core.jev.{
  JevAllocation,
  JevDeclarationDuty,
  JevFallback,
  JevFallbackKind,
  PluginAllocator,
  PluginCatalog
}
import nebflow.core.plugin.{PluginRegistry, PluginsConfig}
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * PluginInjection —— **分配→注入等价路径**（jev-plugins 批 #1226 增补 §3.1/§3.2）.
 *
 * == 本件是什么 ==
 * 原文条款「分配经 prepareNodePlugins 等价路径注入（plugins 块＋pluginMcp.acquire/
 * release 随会话生命周期）」的落位：
 *   - [[resolvePreparation]] = 原 `NodeStarter.prepareNodePlugins` 的解析块
 *     **逐字迁入**（行为保持重构——NodeStarter 改为委托，节点声明路径零漂移）；
 *   - [[allocatePreparation]] = 分配面全链：PluginsConfig 总闸 → [[PluginCatalog]]
 *     目录 → [[PluginAllocator]]（Jev → LLM 兜底 → fail-open 三态）→ 解析注入，
 *     外加 `JevDeclarationDuty.evaluate` 记账（INFO 单行——evaluate 判定单点消费）；
 *   - [[forNode]] = S9 步级分支单点：声明集在场 ⇒ 声明权威（旧行为逐字）；
 *     空声明 ∧ delegate 作用域引擎 ⇒ 分配面；空声明 ∧ per-project 引擎 ⇒ 空注入
 *     （**旧行为逐字节不变**——分配触发点只有 delegate spawn ＋ flow/链每步 spawn，
 *     分发器项目轨不在其列）。
 *
 * == 三态模式词（审计/结构帧 `allocation` 字段值域）==
 * `jev` / `llm-fallback`（两态 = 分配成功，集合可能为空）／`fail-open`（两臂全败，
 * 空集注入派发照走）／`disabled`（PluginsConfig 总闸关）／`empty-catalog`（无可分配
 * 包——合法「无能力面」态，零模型调用）／`off`（分配端口未注册——未装配环境）／
 * `auto-off`（#523 补齐批：`plugins.autoAllocate=false`——声明集照常注入，空声明
 * 不再问模型；惰性态，与 disabled/off 同族）。
 *
 * 失败语义分层（与节点路径同款，2026-09-13 无审批批口径）：**分配**失败 = fail-open
 * （空集，派发绝不阻塞）；**解析/注入**失败（选中包被封禁/技能文件不可读）= `Left`
 * （调用方 failNode / 显式拒 spawn——分配失败是节点级失败，不静默降级）。
 */
object PluginInjection:

  private val logger = NebflowLogger.forName("nebflow.core.plugin.injection")

  /** 分配产出（记账/结构帧承载）：集合 + 三态模式词。 */
  final case class AllocatedSet(names: List[String], mode: String):
    /** 分配动作是否真实发生（disabled/off = 未发生——零审计行、注册面不记集合）。 */
    def actionRan: Boolean = !InertModes.contains(mode)

  /** 分配面未发生动作的模式词（不写分配审计、不进结构帧行）。 */
  private val InertModes: Set[String] = Set("disabled", "off", "auto-off")

  /**
   * delegate spawn（内置执行器腿，含 Mail 续聊复活腿——同点复用）的分配＋解析全链。
   * `Left` 仅出自**解析/注入**失败（spawn 显式拒）；分配三态全数落到 `Right`。
   *
   * #523 补齐批：`plugins.autoAllocate=false` ⇒ 自动分配专用闸短路（声明集路径不受
   * 影响——本函数本就只服务空声明臂）：零模型调用、惰性态 auto-off（INFO 单行留痕，
   * 不写分配审计/结构帧行——与 disabled 同族语义）。
   */
  def allocatePreparation(
    subject: String,
    taskText: String
  ): IO[Either[String, (NodeEngine.PluginPreparation, AllocatedSet)]] =
    PluginsConfig.enabled.flatMap {
      case false => IO.pure(Right(NodeEngine.PluginPreparation.empty -> AllocatedSet(Nil, "disabled")))
      case true =>
        PluginsConfig.autoAllocate.flatMap {
          case false =>
            logger
              .info(s"plugin allocation '$subject': skipped (plugins.autoAllocate=false)")
              .as(Right(NodeEngine.PluginPreparation.empty -> AllocatedSet(Nil, "auto-off")))
          case true =>
            PluginCatalog.entries.flatMap { catalog =>
              if catalog.isEmpty then
                // 空目录 = 合法「无能力面安装」态（JevAllocator 同款短路口）：零模型调用，
                // 空集分配成功（duty = Allocated(empty)）。recordSuccess 落一条 allocated 行。
                JevFallback
                  .recordSuccess(subject, Nil)
                  .as(
                    Right(NodeEngine.PluginPreparation.empty -> AllocatedSet(Nil, "empty-catalog"))
                  )
              else
                PluginAllocator.allocatePlugins(subject, taskText, catalog).flatMap { result =>
                  val (mode, names) = result.allocation match
                    case JevAllocation.Allocated(ns) =>
                      result.via.map(_.wire).getOrElse("jev") -> ns
                    case JevAllocation.FellBack(JevFallbackKind.Off, _) => ("off", Nil)
                    case JevAllocation.FellBack(_, _) => ("fail-open", Nil)
                  logDuty(subject, result)
                  if InertModes.contains(mode) then
                    IO.pure(Right(NodeEngine.PluginPreparation.empty -> AllocatedSet(Nil, mode)))
                  else if names.isEmpty then
                    IO.pure(Right(NodeEngine.PluginPreparation.empty -> AllocatedSet(Nil, mode)))
                  else resolvePreparation(names).map(_.map(prep => (prep, AllocatedSet(names, mode))))
                }
            }
        }
    }

  /**
   * S9 步级分支单点（NodeStarter.prepareNodePlugins 委托本件）：声明集权威；空声明
   * 只在 delegate 作用域引擎走分配面；per-project 引擎空声明 = 旧行为（空注入）。
   *
   * `scopeKey` = 引擎作用域键（delegate 作用域引擎 rootSessionId = delegate 会话 id），
   * 供步级结构帧行（`step-plugins`）挂台账：键 = 台账号回落 scopeKey
   * （ChainStore.keyFor 既有归一口径，零新建存储⑤A）。
   */
  def forNode(
    node: NodeDef,
    delegateScope: Boolean,
    scopeKey: String
  ): IO[Either[String, NodeEngine.PluginPreparation]] =
    if node.plugins.nonEmpty then resolvePreparation(node.plugins)
    else if !delegateScope then IO.pure(Right(NodeEngine.PluginPreparation.empty))
    else
      // 步级分配输入 = 节点任务文（NodeDef.task 可缺省——缺省回落节点名，分配面
      // 输入永不为空串；12k 封顶在分配器内单点）。
      val stepTask = node.task.map(_.trim).filter(_.nonEmpty).getOrElse(node.name)
      allocatePreparation(subject = s"node:${node.id}", taskText = stepTask).flatMap {
        case Left(err) => IO.pure(Left(err))
        case Right((prep, set)) =>
          // 结构帧行（plugins 摘要可查——沿既有 ChainStore 行形态，零新建存储⑤A）：
          // 仅分配动作真实发生时落行（disabled/off/auto-off 无行可查）。
          if InertModes.contains(set.mode) then IO.pure(Right(prep))
          else
            // appendSync 的 Either 形态（同步原子落盘；落盘失败只 WARN——结构帧行
            // 是审计面，绝不阻断步会话 spawn）。
            ChainStore
              .append(
                ChainStore.keyFor(None, scopeKey),
                "step-plugins",
                io.circe.Json.obj(
                  "nodeId" -> node.id.asJson,
                  "plugins" -> set.names.asJson,
                  "allocation" -> set.mode.asJson
                )
              ) match
              case Right(_) => IO.pure(Right(prep))
              case Left(e) =>
                logger.warn(s"step-plugins chain line failed (${node.id}): ${e.getMessage}").as(Right(prep))
      }

  /**
   * 声明集（或分配集）→ 注入物。原 `NodeStarter.prepareNodePlugins` 解析块逐字迁入：
   * 逐包 `PluginRegistry.resolve`（内容面：不存在/被封禁 = Left 列明原因）；逐包读
   * 包根 Plugin.md 全文（[[injectedPluginBlock]]，必要文件缺失/不可读 = Left 不静默
   * 降级）；组装 `<injected-plugins>` 块 + MCP 插件清单 + builtin 工具授予。
   */
  def resolvePreparation(names: List[String]): IO[Either[String, NodeEngine.PluginPreparation]] =
    PluginsConfig.enabled.flatMap {
      case false => IO.pure(Right(NodeEngine.PluginPreparation.empty))
      case true =>
        if names.isEmpty then IO.pure(Right(NodeEngine.PluginPreparation.empty))
        else
          names.traverse(PluginRegistry.resolve).flatMap { resolved =>
            val errors = resolved.collect { case Left(e) => e }
            if errors.nonEmpty then IO.pure(Left(errors.mkString(" | ")))
            else
              val defs = resolved.collect { case Right(d) => d }
              defs.traverse(injectedPluginBlock).map { blocks =>
                // 必要文件缺失/不可读 = Left 上浮（旧链在 injectedPluginBlock 内聚合
                // Left；机制切到 Plugin.md 后单块 Left 在此单点收集——语义不变：
                // 节点级失败，绝不静默降级成空注入）。
                blocks.collectFirst { case Left(e) => e } match
                  case Some(err) => Left(err)
                  case None =>
                    val inner = blocks.collect { case Right(b) => b }
                    Right(
                      NodeEngine.PluginPreparation(
                        injectedBlock =
                          if inner.isEmpty then ""
                          else s"<injected-plugins>\n${inner.mkString("\n\n")}\n</injected-plugins>",
                        mcpPlugins = defs.filter(_.mcpServers.nonEmpty),
                        builtinTools = defs.flatMap(_.toolsExtension).distinct
                      )
                    )
              }
            end if
          }
    }

  /**
   * 插件必要文件注入（作者令 2026-10-21「plugin.md = 插件必要文件机制」）：分配
   * 注入的提示词 = **包根 Plugin.md 的内容**（该插件的功能描述单源）。旧链 =
   * 逐 skill 读 SKILL.md 全文拼 `<plugin name skill>` 块——随本批退役（SKILL.md
   * 回归「按需查阅」件，面板胶囊深链打开，不再随分配整文注入）。
   *
   * 文件名 = 包根 `Plugin.md`（既有包生态的既有命名，零迁移）；小写 `plugin.md`
   * 同认（回退一查——同一文件机制，覆盖手写小写名的包）。`{{data_root}}` 照旧
   * 渲染为实例数据根（PathUtil.substituteDataRoot）。
   *
   * 缺失/不可读 → Left（节点级失败，显式列路径——与旧 SKILL.md 不可读同款
   * no-silent-degradation；Plugin.md 自本批起是插件必要文件，存量包已随本批
   * 全部补齐）。
   */
  private def injectedPluginBlock(defn: PluginRegistry.PluginDef): IO[Either[String, String]] =
    val root = os.Path(defn.dir)
    val readPrimary = IO.blocking(os.read(root / "Plugin.md")).attempt
    val readLowercase = IO.blocking(os.read(root / "plugin.md")).attempt
    readPrimary
      .orElse(readLowercase)
      .flatMap {
        case Right(text) =>
          IO.pure(
            Right(s"""<plugin name="${defn.name}">\n${PathUtil.substituteDataRoot(text)}\n</plugin>""")
          )
        case Left(_) =>
          IO.pure(
            Left(
              s"Plugin '${defn.name}' required file Plugin.md missing/unreadable at ${root / "Plugin.md"} — " +
                "allocation refused (no silent degradation)"
            )
          )
      }

  end injectedPluginBlock

  /**
   * duty 记账（INFO 单行；判定 = [[JevDeclarationDuty.evaluate]] 单点）：分配动作
   * 发生与否、产出集合、回落原因 → duty 三态。审计事件本体已由 JevFallback 落
   * `jev-allocation.jsonl`，本行把 duty 判定显式化（观察者不必重放事件推 duty）。
   */
  private def logDuty(subject: String, result: PluginAllocator.PluginAllocationResult): Unit =
    val (allocatedRan, names, fallbackReason) = result.allocation match
      case JevAllocation.Allocated(ns) => (true, ns, None)
      case JevAllocation.FellBack(JevFallbackKind.Off, reason) => (false, Nil, Some(reason))
      case JevAllocation.FellBack(_, reason) => (true, Nil, Some(reason))
    val duty = JevDeclarationDuty.evaluate(allocatedRan, names, fallbackReason)
    val via = result.via.map(_.wire).getOrElse("-")
    logger.infoSync(
      s"plugin allocation '$subject': via=$via plugins=[${names.mkString(",")}] duty=$duty"
    )

end PluginInjection
