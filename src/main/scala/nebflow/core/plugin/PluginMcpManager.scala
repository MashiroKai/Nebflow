package nebflow.core.plugin

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.core.mcp.McpManager
import nebflow.llm.McpServerConfig

import scala.concurrent.duration.*

/**
 * PluginMcpManager —— plugin 级 MCP 生命周期（阶段 2b §B.5）。
 *
 * - **引用计数**：同一 plugin 被多个并发 node 分配 → server 只起一份；最后一个
 *   会话 release 后关闭（stdio 进程 kill / http 连接断开）。工具注册名
 *   `mcp__plugin_<plugin>_<server>__<tool>`（serverId = `plugin_<plugin>_<server>`，
 *   复用 agent 级前缀先例；McpManager.stopServer 的 unregisterToolsByPrefix
 *   按 `mcp__<serverId>__` 前缀回收，命名自洽）。
 * - **启动失败语义**（§B.5）：MCP server 起不来 → acquire 返回 Left → NodeEngine
 *   failNode（不静默跳过——分配了 MCP 却没有 = 节点能力残缺）。
 * - **独立管理器**：不复用全局 McpManager 实例与 enable/disable 面（避免互相
 *   污染开关状态，§B.5）；本类持有自己的 McpManager 实例。
 * - **信任运行时联动**（§B.5）：digest 失效 → refcount>0 的运行中 server 立即
 *   停用 + 受影响 running 会话收到系统提醒（trust 是运行时属性，不只是装载时
 *   属性）。提醒经 notify 回调投递（NodeEngine 接 agentRegistry 的
 *   ImmediateInput——排 pendingUserInputs、turn 边界消费，不打断进行中 turn）。
 */
class PluginMcpManager private (
  mcp: McpManager,
  serverRefs: Ref[IO, Map[String, PluginMcpManager.ServerEntry]],
  sessionRefs: Ref[IO, Map[String, Set[String]]]
):
  private val logger = NebflowLogger.forName("nebflow.plugin.mcp")

  /** acquire：为会话注册一组 plugin 的 MCP servers（调用方已过信任门解析；
    * 此处以 acquire 时点 digest 记账，供 revalidate 对比）。
    *
    * 返回 Right(grant)：serverIds（allowedSet 前缀来源，AgentCore 扩展消费）。
    * 任一 server 启动失败 → 本批已启动者立即回滚停用 + Left（failNode 语义，§B.5）。
    */
  def acquire(sessionId: String, plugins: List[PluginRegistry.PluginDef]): IO[Either[String, PluginMcpManager.Grant]] =
    val wanted: Map[String, (String, String, McpServerConfig)] =
      plugins.flatMap(p => p.mcpServers.map { case (s, cfg) =>
        PluginMcpManager.serverIdFor(p.name, s) -> (p.name, p.digest, cfg)
      }).toMap
    if wanted.isEmpty then IO.pure(Right(PluginMcpManager.Grant(Nil)))
    else
      for
        existing <- serverRefs.get
        toStart = wanted.filter { case (sid, _) => !existing.contains(sid) }
        started <- toStart.toList.traverse { case (sid, (_, _, cfg)) =>
          mcp.startServer(sid, cfg)
            .timeout(15.seconds)
            .attempt
            .map(sid -> _)
        }
        failures = started.collect { case (sid, Left(err)) => sid -> err }
        result <-
          if failures.nonEmpty then
            // 回滚本批已成功启动的（引用未建立，直接停），失败细节进错误消息
            val okStarted = started.collect { case (sid, Right(())) => sid }
            okStarted.traverse_(sid => mcp.stopServer(sid).handleError(_ => ())) *>
              IO.pure(Left(
                s"Plugin MCP server failed to start — node cannot run without its allocated capability " +
                  "(no silent skip, §B.5). Detail: " +
                  failures.map { case (sid, err) => s"$sid: ${Option(err.getMessage).getOrElse(err.toString)}" }.mkString("; ") +
                  " (PLUGIN_MCP_START_FAILED)"))
          else
            // 引用记账：server 条目（含 digest 记账）+ session 持有集并入
            serverRefs.update { m =>
              wanted.foldLeft(m) { (acc, entry) =>
                val (sid, (pname, digest, _)) = entry
                acc.get(sid) match
                  case Some(e) => acc.updated(sid, e.copy(holders = e.holders + sessionId))
                  case None    => acc.updated(sid, PluginMcpManager.ServerEntry(pname, Set(sessionId), digest))
              }
            } *> sessionRefs.update(m => m + (sessionId -> (m.getOrElse(sessionId, Set.empty) ++ wanted.keys.toSet))) *>
              IO.pure(Right(PluginMcpManager.Grant(wanted.keys.toList)))
      yield result

  /** release：会话终态回收（completed/failed/cancelled/blocked 全终态统一调用，
    * §B.4 第 5 步）。该会话持有的全部 server 计数 -1；归零 → 停 server
    * （McpManager.stopServer 内含 unregisterToolsByPrefix + 连接关闭）。幂等：
    * 会话无持有集 / server 已不在表 → no-op（允许兜底路径二次调用）。 */
  def release(sessionId: String): IO[Unit] =
    for
      held <- sessionRefs.get.map(_.getOrElse(sessionId, Set.empty))
      _ <- sessionRefs.update(m => m - sessionId)
      _ <- held.toList.traverse_ { sid =>
        serverRefs.modify { m =>
          m.get(sid) match
            case Some(e) =>
              val rest = e.holders - sessionId
              if rest.isEmpty then (m - sid, Some(sid))
              else (m.updated(sid, e.copy(holders = rest)), None)
            case None => (m, None)
        }.flatMap {
          case Some(sid) =>
            mcp.stopServer(sid).handleError(e => logger.warn(s"stopServer '$sid' after release failed: ${e.getMessage}"))
          case None => IO.unit
        }
      }
    yield ()

  /** 信任运行时重验（§B.5 信任联动，周期驱动）：重扫注册表 → 运行中 server 对应
    * plugin 的当前 trusted digest ≠ acquire 时 digest（或已 untrusted / 被移除）
    * → 立即停 server + 对持有会话发系统提醒。返回受影响 plugin 名列表。
    * 无运行中 server → 不扫描直接返回 Nil。 */
  def revalidate(
    rescan: IO[List[PluginRegistry.PluginDef]],
    notify: (String, String) => IO[Unit]
  ): IO[List[String]] =
    serverRefs.get.flatMap { running =>
      if running.isEmpty then IO.pure(Nil)
      else
        rescan.flatMap { defs =>
          val current = defs.map(d => d.name -> d).toMap
          val stale = running.toList.flatMap { case (sid, entry) =>
            current.get(entry.pluginName) match
              case Some(d) if d.trust.trusted && d.digest == entry.digestAtAcquire => Nil
              case other =>
                val why = other match
                  case Some(d) if !d.trust.trusted => "plugin fell back to untrusted"
                  case Some(d) => "plugin directory digest changed since this server started"
                  case None => "plugin removed from registry"
                List((sid, entry, why))
          }
          stale.traverse { case (sid, entry, why) =>
            for
              _ <- mcp.stopServer(sid).handleError(e => logger.warn(s"trust revalidation stopServer '$sid' failed: ${e.getMessage}"))
              _ <- serverRefs.update(m => m - sid)
              holders <- sessionRefs.get.map(_.filter { case (_, set) => set(sid) }.keySet)
              _ <- holders.toList.traverse_ { s =>
                notify(s, s"[plugin-trust] Plugin '${entry.pluginName}' failed trust revalidation ($why) — " +
                  "its MCP server has been stopped (trust is a runtime property, §B.5). Re-approve the plugin if intended; " +
                  "this session's tool calls to it will now fail.")
              }
              _ <- logger.warn(s"Plugin '${entry.pluginName}' MCP '$sid' stopped by trust revalidation: $why")
            yield entry.pluginName
          }
        }
    }

  /** 运行中 server 快照（spec 断言用）：serverId → 持有会话数。 */
  def runningServers: IO[Map[String, Int]] =
    serverRefs.get.map(_.map { case (k, v) => k -> v.holders.size })

  /** 会话持有快照（spec 断言用）。 */
  def sessionHolds: IO[Map[String, Set[String]]] = sessionRefs.get
end PluginMcpManager

object PluginMcpManager:

  final case class Grant(
    /** 允许集前缀来源：plugin MCP serverId 列表（AgentCore 扩展一个前缀来源消费，
      * §B.4 第 4 步）。 */
    serverIds: List[String]
  )

  private final case class ServerEntry(
    pluginName: String,
    holders: Set[String],
    digestAtAcquire: String
  )

  /** 运行时 serverId：`plugin_<plugin>_<server>` → 工具名
    * `mcp__plugin_<plugin>_<server>__<tool>`（§B.4 命名）。 */
  def serverIdFor(pluginName: String, serverName: String): String =
    s"plugin_${pluginName}_$serverName"

  /** 创建（GatewayMain / SharedResources 默认实例共用）。 */
  def create: IO[PluginMcpManager] =
    for
      mcp <- McpManager.create
      serverRefs <- Ref.of[IO, Map[String, ServerEntry]](Map.empty)
      sessionRefs <- Ref.of[IO, Map[String, Set[String]]](Map.empty)
    yield new PluginMcpManager(mcp, serverRefs, sessionRefs)

  /** 同步构造（SharedResources 字段默认值用——boot 单线程期 / 测试构造）。
    * Ref.unsafe 与既有 SharedResources 字段先例同款。 */
  def unsafe(): PluginMcpManager =
    new PluginMcpManager(
      McpManager.create.unsafeRunSync(),
      Ref.unsafe[IO, Map[String, ServerEntry]](Map.empty),
      Ref.unsafe[IO, Map[String, Set[String]]](Map.empty)
    )
end PluginMcpManager
