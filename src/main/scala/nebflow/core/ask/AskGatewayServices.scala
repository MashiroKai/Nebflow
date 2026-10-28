package nebflow.core.ask

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.core.NeblinkServicePort
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * ask 装配层服务定位（gateway 装配、core 消费——WikiService.current 先例）。
 *
 * AskPromptCache：组装好的系统提示缓存（sid → prompt）。写点 = WsAskHandlers
 * 的 askOpen/askQuestion 组装腿；读点 = WebSocketRoutes.resolveAgentDef 的 ask
 * 分支（spawn 时注入 AgentDef.systemPrompt）。缓存缺席（进程重启后未组装）时
 * 读点会现场组装一次——冷却状态随之落账，语义收敛。
 * ═══════════════════════════════════════════════════════════════════════════ */

final class AskPromptCache:
  private val m = new TrieMap[String, (String, Long)]()

  def get(sessionId: String): Option[String] = m.get(sessionId).map(_._1)

  def put(sessionId: String, prompt: String, now: Long): Unit = m.update(sessionId, (prompt, now))
end AskPromptCache

final case class AskServices(
  registry: AskSessionRegistry,
  sources: AskContextSources,
  prompts: AskPromptCache
):
  /**
   * spawn 面：ask 会话的运行时 AgentDef（name/category 仅为身份占位——工具面
   * 由 buildAllowedToolSet 的 askSession 早退集机制固定，不读声明）。
   */
  def agentDefFor(sessionId: String): IO[nebflow.actor.AgentDef] =
    registry.byId(sessionId).flatMap {
      case None =>
        IO.raiseError(new RuntimeException(s"ask session $sessionId has no file binding"))
      case Some(binding) =>
        cachedPrompt(sessionId, binding).map { prompt =>
          nebflow.actor.AgentDef(
            name = "ask",
            description = s"Canvas 文件问答分身 · ${binding.fileName}",
            tools = Nil,
            systemPrompt = prompt,
            category = "standalone"
          )
        }
    }

  /** 提示词：缓存优先；缺席现场组装一次（幂等，冷却状态随之落账）。 */
  def cachedPrompt(sessionId: String, binding: AskBinding): IO[String] =
    prompts.get(sessionId) match
      case Some(p) => IO.pure(p)
      case None => assembleNow(sessionId, binding)
  end cachedPrompt

  private def assembleNow(sessionId: String, binding: AskBinding): IO[String] =
    val now = System.currentTimeMillis()
    AskContextAssembler.assemble(sources, binding.file, binding.fileName, now).flatMap { ctx =>
      val prompt = AskContextAssembler.promptFor(binding.fileName, ctx.block)
      prompts.put(sessionId, prompt, now)
      registry.setAssemblyState(sessionId, AskCooldown.afterAssemble(now, ctx.fingerprint)) *>
        IO.pure(prompt)
    }
  end assembleNow

  /**
   * 交互前组装腿（askOpen / askQuestion 前置，§3.3 冷却语义唯一入口）：
   * 返回本块来源标签（askState 帧消费；Hold = None 表示无需重组）。
   */
  def assembleIfDue(sessionId: String, binding: AskBinding): IO[Option[List[String]]] =
    val now = System.currentTimeMillis()
    for
      st <- registry.assemblyState(sessionId)
      recallMtime <- sources.recallDbMtime.handleError(_ => 0L)
      graphVer <- sources.graphVersion.handleError(_ => "")
      currentFp = st.fingerprint.copy(recallDbMtime = recallMtime, graphVersion = graphVer)
      // 文件面读数实时取（触发源②：文件版本变化 = mtime+size）。
      fileFp <- sources.fileStat(binding.file).handleError(_ => None)
      fpNow = fileFp.map(f => currentFp.copy(fileMtime = f._1, fileSize = f._2)).getOrElse(currentFp)
      plan = AskCooldown.plan(now, st, fpNow)
      res <- plan match
        case AskCooldown.Plan.Hold => IO.pure(None)
        case AskCooldown.Plan.Assemble | AskCooldown.Plan.Reassemble =>
          AskContextAssembler.assemble(sources, binding.file, binding.fileName, now).flatMap { ctx =>
            val prompt = AskContextAssembler.promptFor(binding.fileName, ctx.block)
            prompts.put(sessionId, prompt, now)
            registry.setAssemblyState(sessionId, AskCooldown.afterAssemble(now, ctx.fingerprint)) *>
              IO.pure(Some(ctx.sources))
          }
    yield res
  end assembleIfDue
end AskServices

object AskGatewayServices:
  @volatile var instance: Option[AskServices] = None

  def install(s: AskServices): Unit = instance = Some(s)

  def uninstall(): Unit = instance = None
end AskGatewayServices

/* ═══════════════════════════════════════════════════════════════════════════
 * 活体检索源（§3.1 三路的进程内实现；gateway 装配注入）。
 *   ① 文件全文：os 直读（只读，16k 预算截断在拼装层）；
 *   ② 记忆检索：tools/stand-recall --json（三候选定位复用 MemoryRecallDaemon）；
 *   ③ 图谱邻接：WikiService.current(neblink).graph()——按文件名/rel 匹配节点，
 *     1-2 跳邻接标题 + summary（§3.1 的「锚段全文」以 summary + 首节名为代理，
 *     段落级快照走既有 GET /api/wiki/paragraph——实施假设，机制稿 §9 收敛）。
 * 触发源读数：memory-db 目录 mtime（①记忆增量）+ 文件 stat（②文件变化）；
 * 图谱版本读数退化并入文件指纹（图谱边派生自文档自身，文档变化即图谱变化
 * ——实施假设，登记机制稿 §9）。
 * ═══════════════════════════════════════════════════════════════════════════ */
object AskLiveSources:

  private val logger = NebflowLogger.forName("nebflow.ask")

  def make(neblink: Option[NeblinkServicePort], dataRoot: os.Path): AskContextSources =
    new AskContextSources:
      private val recallDb: os.Path = dataRoot / "memory-db"

      override def fileStat(absPath: String): IO[Option[(Long, Long)]] =
        IO.blocking {
          try
            val p = os.Path(absPath, os.pwd)
            if os.isFile(p) then Some((os.mtime(p), os.size(p))) else None
          catch { case NonFatal(_) => None }
        }

      override def fileText(absPath: String): IO[Option[(String, Long, Long)]] =
        IO.blocking {
          try
            val p = os.Path(absPath, os.pwd)
            if os.isFile(p) then Some((os.read(p), os.mtime(p), os.size(p))) else None
          catch { case NonFatal(e) => logger.warn(s"ask file read failed: ${e.getMessage}"); None }
        }

      override def graphNeighbors(absPath: String): IO[List[AskNeighbor]] =
        graphJson.flatMap(json => IO.blocking(extractNeighbors(json, absPath))).handleErrorWith(e =>
          logger.warn(s"ask graph leg failed (skip): ${e.getMessage}") *> IO.pure(Nil)
        )

      override def recallHits(query: String): IO[List[AskRecallHit]] =
        IO.blocking {
          nebflow.core.MemoryRecallDaemon.resolveToolPath(dataRoot) match
            case None => Nil
            case Some(cli) =>
              val res = os.proc(
                "python3", cli.toString, "--json", "-n", AskBudgets.RecallMax.toString, "--context", "1", query
              ).call(cwd = os.pwd, timeout = 30L, check = false, mergeErrIntoOut = true)
              if res.exitCode != 0 then Nil else parseRecall(res.out.text())
        }.handleErrorWith(e => logger.warn(s"ask recall leg failed (skip): ${e.getMessage}") *> IO.pure(Nil))

      override def recallDbMtime: IO[Long] =
        IO.blocking(if os.exists(recallDb) then os.mtime(recallDb) else 0L).handleError(_ => 0L)

      override def graphVersion: IO[String] = IO.pure("")

      // ── 图谱邻接抽取（best-effort；Json 形状 = WikiIndex.nodeJson/edgeJson）──
      private def graphJson: IO[Json] =
        nebflow.core.wiki.WikiService.current(neblink).graph(None, None, force = false)

      private def extractNeighbors(graph: Json, absPath: String): List[AskNeighbor] =
        try
          val nodes = graph.hcursor.downField("nodes").as[List[Json]].toOption.getOrElse(Nil)
          val edges = graph.hcursor.downField("edges").as[List[Json]].toOption.getOrElse(Nil)
          val base = absPath.replace('\\', '/').takeRight(math.max(1, absPath.split('/').lastOption.map(_.length).getOrElse(1) + 48))
          case class N(id: String, title: String, rel: String, summary: String)
          val parsed = nodes.flatMap { n =>
            for
              id <- n.hcursor.downField("id").as[String].toOption
              title = n.hcursor.downField("title").as[String].toOption.getOrElse("")
              rel = n.hcursor.downField("rel").as[String].toOption.getOrElse("")
              summary = n.hcursor.downField("summary").as[String].toOption.getOrElse("")
            yield N(id, title, rel, summary)
          }
          val me = parsed.find(n =>
            absPath.replace('\\', '/').endsWith(n.rel) || n.rel.endsWith(base)
          )
          me match
            case None => Nil
            case Some(self) =>
              val direct = edges.flatMap { e =>
                for
                  from <- e.hcursor.downField("from").as[String].toOption
                  to <- e.hcursor.downField("to").as[String].toOption
                yield if from == self.id then to else if to == self.id then from else ""
              }.filter(_.nonEmpty).distinct
              val secondHop = edges.flatMap { e =>
                for
                  from <- e.hcursor.downField("from").as[String].toOption
                  to <- e.hcursor.downField("to").as[String].toOption
                  a <- direct.find(d => d == from || d == to)
                  other = if from == a then to else from
                yield if direct.contains(other) || other == self.id then "" else other
              }.filter(_.nonEmpty)
              val ids = (direct ++ secondHop).distinct.take(AskBudgets.NeighborMax)
              parsed.filter(n => ids.contains(n.id)).map(n => AskNeighbor(n.title, n.rel, n.summary))
        catch
          case NonFatal(_) => Nil
      end extractNeighbors

      // stand-recall --json 输出解析（宽松：尽力取 session/ts/excerpt 字段）。
      private def parseRecall(out: String): List[AskRecallHit] =
        try
          io.circe.parser.parse(out).toOption match
            case None => Nil
            case Some(json) =>
              val arr = json.hcursor.downField("hits").as[List[Json]].toOption
                .orElse(json.as[List[Json]].toOption).getOrElse(Nil)
              arr.flatMap { h =>
                for
                  excerpt <- h.hcursor.downField("text").as[String].toOption
                    .orElse(h.hcursor.downField("excerpt").as[String].toOption)
                  session = h.hcursor.downField("session").as[String].toOption.getOrElse("")
                  ts = h.hcursor.downField("ts").as[String].toOption
                    .orElse(h.hcursor.downField("time").as[String].toOption).getOrElse("")
                yield AskRecallHit(session, ts, excerpt)
            }
        catch
          case NonFatal(_) => Nil
  end make
end AskLiveSources
