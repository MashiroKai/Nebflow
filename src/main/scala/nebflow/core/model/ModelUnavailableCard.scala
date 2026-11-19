package nebflow.core.model

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.apache.pekko.actor.typed.ActorRef

import nebflow.actor.{InteractionHubCommand, InteractionKind, InteractionReply, InteractionRequest}
import nebflow.core.{AtomicJson, ModelRoles, SchemePolicy}
import nebflow.shared.{
  AgentModelConfig,
  ModelUnavailableAction,
  ModelUnavailableAnswer,
  NebflowLogger,
  PathUtil
}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * ModelUnavailableCard —— **统一的「模型不可用」卡片**（单实现，全槽共用）。
 *
 * == 作者裁定（2026-11-18 14:37，逐字）==
 * 「不使用多候选 而是设计模型fallback卡片 比如小模型 以及各种 kernel node等
 *  模型不可用了 通过统一的卡片进行汇报 由用户手动切换 并且切换卡片 也要有测试
 *  llm 功能」——外加 14:26/14:27 的「5 分钟超时才回报」要求，本件合一落位：
 *   - **不做多候选**：本件不含任何第二跳候选链（`small-llm-chain-depth` 已销号）；
 *   - **统一卡片**：任何槽（`small-llm` / `kernel` / `node` / `dispatcher` /
 *     `subagent`）模型不可用都走 [[withUnavailableCard]] 这一条实现——槽名是
 *     **载荷字段**（`cardPayload.slot`），不是一个槽一套卡；
 *   - **用户手动切换**：卡片给出 `switch` 动作 + 候选清单；答复即**就地写回该槽的
 *     `model.preferred`**（[[writeSlotModel]]，与设置面板 / `PUT
 *     /api/agents/:name/model` 同一落盘形态），随后**重试原请求**（原请求继续完成，
 *     不让用户重发）；
 *   - **卡片内测试 LLM**：`test` 动作在本件内经 [[Probe]] 缝发**一次最小真实调用**，
 *     回报成功/失败 + 延迟 + 失败分类，写进重发的卡片（`state=tested`）——「切完才
 *     知道是坏的」在机制上被排除；
 *   - **5 分钟超时口径**：[[configuredTimeoutSec]] 默认 **300s**（理由：与其他交互
 *     卡的人工响应窗口同量级，且远长于一次真实探活；可配置
 *     `model.unavailableCardTimeoutSec`，钳制 [30, 3600]）；
 *     **超时才回报根会话**（[[Outcome.TimedOut]] → 一张 `reportOnly` 卡进 root
 *     会话），写明原因与已尝试动作。**绝不静默**；不改
 *     `plugins.failOpenOnAllocationError`（保持 fail-closed）。
 *
 * == 与既有机制的关系（零新建机制）==
 * 卡片走既有 [[InteractionHub]] 单点（[[InteractionKind.ModelUnavailable]] 分支）；
 * 待答槽位、重连重放、跨端镜像、审计行全部复用既有链路。装配面见 [[Runtime]]：
 * 未装配（测试/early boot/未接线部署）⇒ [[Outcome.Unwired]]，调用侧保持既有行为。
 */
object ModelUnavailableCard:

  private val logger = NebflowLogger.forName("nebflow.core.model.unavailable-card")

  /** 卡片等用户的最长时长（默认值 + 理由见文件头注）。 */
  val DefaultTimeoutSec: Int = 300

  /** 超时配置钳制区间（防呆：<30s 会误杀正常作答；>1h 等于无界）。 */
  private val MinTimeoutSec = 30
  private val MaxTimeoutSec = 3600

  /**
   * 热读 `model.unavailableCardTimeoutSec`（缺省 [[DefaultTimeoutSec]]）。
   * absent / 非法 / 越界 → 钳制或缺省（同类热读先例：`PluginsConfig.readBool`）。
   */
  def configuredTimeoutSec(): IO[Int] =
    IO.blocking {
      val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      val parsed: Option[Int] =
        if !os.exists(configPath) then None
        else
          io.circe.parser
            .parse(os.read(configPath))
            .toOption
            .flatMap(
              _.hcursor.downField("model").downField("unavailableCardTimeoutSec").as[Option[Int]].toOption
            )
            .flatten
      parsed.map(v => math.max(MinTimeoutSec, math.min(MaxTimeoutSec, v))).getOrElse(DefaultTimeoutSec)
    }

  // ── 失败分类（HTTP 状态码 / 供应商错误码 / 超时 / 传输 / 空回文） ──────────────

  /** 失败分类（卡片必须写清「失败分类（HTTP 状态码/错误码/超时）」）。 */
  enum FailureClass:
    case HttpStatus(code: Int)
    case ProviderCode(code: String)
    case Timeout
    case Transport
    case EmptyReply
    case Unknown

  object FailureClass:

    /** 卡片字段值（前端/审计同一拼写）。 */
    def wire(fc: FailureClass): String = fc match
      case HttpStatus(_)    => "http-status"
      case ProviderCode(_)  => "provider-code"
      case Timeout          => "timeout"
      case Transport        => "transport"
      case EmptyReply       => "empty-reply"
      case Unknown          => "unknown"

    /** 分类里的「码」（HTTP 状态码 / 供应商错误码）；无码 = None。 */
    def code(fc: FailureClass): Option[String] = fc match
      case HttpStatus(c)   => Some(c.toString)
      case ProviderCode(c) => Some(c)
      case _               => None

    /** 人类可读一句。 */
    def label(fc: FailureClass): String = fc match
      case HttpStatus(c)   => s"HTTP $c"
      case ProviderCode(c) => s"provider error code $c"
      case Timeout         => "timeout"
      case Transport       => "transport/connection error"
      case EmptyReply      => "empty reply (HTTP 200, empty body)"
      case Unknown         => "unknown failure"

    private val HttpRe = """(?i)\bHTTP\s+(\d{3})\b""".r
    private val CodeRe = """\[(\d{2,5})\]""".r

    /**
     * 从「详情字符串」判类（供应商错误体形态，例如 zhipu 的
     * `HTTP 429: [1308] 已达到 5 小时的使用上限` ⇒ ProviderCode("1308") 压过
     * HttpStatus(429)——错误码更可归因）。纯函数，spec 判红锚。
     */
    def fromDetail(detail: String): FailureClass =
      val d = Option(detail).getOrElse("")
      val low = d.toLowerCase
      // 序：供应商错误码 → 显式关键字（超时/空回文，压过内嵌的 HTTP 状态码——
      // `empty reply (HTTP 200, empty body)` 的真因是空回文，不是「HTTP 200 正常」）
      // → HTTP 状态码 → 传输/未知。
      CodeRe.findFirstMatchIn(d).map(m => ProviderCode(m.group(1))) match
        case Some(pc) => pc
        case None =>
          if low.contains("timeout") || low.contains("timed out") then Timeout
          else if low.contains("empty reply") || low.contains("empty body") then EmptyReply
          else
            HttpRe.findFirstMatchIn(d).map(m => HttpStatus(m.group(1).toInt)) match
              case Some(hs) => hs
              case None     => if d.trim.isEmpty then Unknown else Transport

    /** 异常 → (分类, 一行详情)。沿 cause 链查传输/超时类（sttp 常把
     * `HttpTimeoutException` 包在 `IOException` 里——与 `LlmAllocator.isTimeoutOrTransport`
     * 同口径）。 */
    def classify(e: Throwable): (FailureClass, String) =
      def chain(t: Throwable, depth: Int): List[Throwable] =
        if t == null || depth > 8 then Nil
        else t :: Option(t.getCause).map(c => chain(c, depth + 1)).getOrElse(Nil)
      val detail = Option(e).map(x => s"${x.getClass.getSimpleName}: ${Option(x.getMessage).getOrElse("")}").getOrElse("")
      val hit = chain(e, 0).find { t =>
        val cls = t.getClass.getName.toLowerCase
        val msg = Option(t.getMessage).getOrElse("").toLowerCase
        cls.contains("timeout") || msg.contains("timed out") || msg.contains("timeout")
      }
      val fc =
        hit match
          case Some(_) => Timeout
          case None    => fromDetail(detail)
      (fc, detail)

  // ── 汇报体 + 卡片载荷 ─────────────────────────────────────────────────────

  /**
   * 一次「某槽模型不可用」的汇报体——**所有槽共用**（槽名/标签是字段，不是型别）。
   *
   * @param slot      槽（`ModelRoles` 的 wire 名：small-llm / kernel / node / dispatcher / subagent）
   * @param slotLabel 人类可读槽标签（「通用小 LLM」等）
   * @param agentName 归因徽标名（= 槽的规范目录名）
   * @param providerId 供应商 id
   * @param model     模型 id
   * @param failure   失败分类
   * @param detail    原始详情（已由调用侧脱敏——本件零凭据面）
   */
  final case class Report(
    slot: String,
    slotLabel: String,
    agentName: String,
    providerId: String,
    model: String,
    failure: FailureClass,
    detail: String
  ):
    def providerRef: String = s"$providerId/$model"

  /** 卡片内的「测试 LLM」结果（成功/失败 + 延迟 + 失败分类）。 */
  enum TestResult:
    case Ok(latencyMs: Long, preview: String)
    case Failed(failure: FailureClass, detail: String, latencyMs: Long)

  object TestResult:
    def wire(r: TestResult): Json = r match
      case Ok(latencyMs, preview) =>
        Json.obj(
          "ok" -> true.asJson,
          "latencyMs" -> latencyMs.asJson,
          "reply" -> preview.asJson
        )
      case Failed(fc, detail, latencyMs) =>
        Json.obj(
          "ok" -> false.asJson,
          "latencyMs" -> latencyMs.asJson,
          "failureClass" -> FailureClass.wire(fc).asJson,
          "failureCode" -> FailureClass.code(fc).fold(Json.Null)(_.asJson),
          "failureLabel" -> FailureClass.label(fc).asJson,
          "detail" -> detail.asJson
        )

  /** 最小真实调用缝（生产 = 既有 `ProviderModelProbe` 的 1-token 探活；
   * spec = canned）。 */
  type Probe = (String, String) => IO[TestResult]

  /** 未接线探针（装配缺席 ⇒ 测试动作如实报「未接线」，绝不假装成功）。 */
  val ProbeNotWired: Probe = (p, m) =>
    IO.pure(TestResult.Failed(FailureClass.Unknown, s"probe not wired for $p/$m", 0L))

  /**
   * 统一卡片载荷（**唯一拼帧点**：任何槽、任何状态都过这里）。字段对照作者
   * 要求：哪个槽（`slot`/`slotLabel`）、哪个 provider/model（`providerId`/`model`/
   * `modelRef`）、失败分类（`failureClass`/`failureCode`/`detail`）、可采取的动作
   * （`actions`）。
   */
  def cardPayload(
    r: Report,
    candidates: List[String],
    state: String = "open",
    test: Option[TestResult] = None,
    reportOnly: Boolean = false,
    note: Option[String] = None
  ): Json =
    val actions = Json.arr(
      Json.obj(
        "id" -> ModelUnavailableAction.wire(ModelUnavailableAction.Test).asJson,
        "label" -> "测试 LLM（一次最小真实调用）".asJson
      ),
      Json.obj(
        "id" -> ModelUnavailableAction.wire(ModelUnavailableAction.Switch).asJson,
        "label" -> "切换该槽模型".asJson,
        "options" -> candidates.asJson
      ),
      Json.obj(
        "id" -> ModelUnavailableAction.wire(ModelUnavailableAction.Dismiss).asJson,
        "label" -> "保持现状".asJson
      )
    )
    Json
      .obj(
        "type" -> "modelUnavailable".asJson,
        "slot" -> r.slot.asJson,
        "slotLabel" -> r.slotLabel.asJson,
        "agentName" -> r.agentName.asJson,
        "providerId" -> r.providerId.asJson,
        "model" -> r.model.asJson,
        "modelRef" -> r.providerRef.asJson,
        "failureClass" -> FailureClass.wire(r.failure).asJson,
        "failureCode" -> FailureClass.code(r.failure).fold(Json.Null)(_.asJson),
        "failureLabel" -> FailureClass.label(r.failure).asJson,
        "detail" -> r.detail.asJson,
        "actions" -> actions,
        "state" -> state.asJson,
        "reportOnly" -> reportOnly.asJson
      )
      .deepMerge(test.fold(Json.obj())(t => Json.obj("test" -> TestResult.wire(t))))
      .deepMerge(note.fold(Json.obj())(n => Json.obj("note" -> n.asJson)))

  // ── 装配面（单一注册点；未注册 = 既有行为） ──────────────────────────────────

  /**
   * 生产装配（root 会话可答 + 探针 + 候选清单）。**惰性单点**：未注册 ⇒ 卡片路径
   * 整体旁路（[[Outcome.Unwired]]），调用侧保持既有行为——测试/early boot/未接线
   * 部署零影响。
   */
  final case class Runtime(
    hub: () => IO[Option[ActorRef[InteractionHubCommand]]],
    rootSessionId: () => IO[String],
    probe: Probe,
    candidates: () => IO[List[String]]
  )

  private val runtimeRef = new AtomicReference[Option[Runtime]](None)

  def registerRuntime(rt: Runtime): Unit = runtimeRef.set(Some(rt))

  def currentRuntime: Option[Runtime] = runtimeRef.get()

  def clearRuntime(): Unit = runtimeRef.set(None)

  // ── 卡片交互（唯一实现） ───────────────────────────────────────────────────

  /** 卡片交互的结果（调用侧据此决定「重试原请求」还是「保持失败」）。 */
  enum Outcome:
    /** 用户选定了模型并已写回该槽 ⇒ 调用侧重试原请求（原请求继续完成）。 */
    case Switched(modelRef: String)
    /** 用户选择保持现状。 */
    case Dismissed
    /** 300s 内无人作答 ⇒ 已回报根会话。 */
    case TimedOut
    /** 卡片链路未装配（无 hub / 无 root / 未注册 runtime）⇒ 未弹卡。 */
    case Unwired

  /**
   * 一次「模型不可用」卡片的完整交互（**唯一实现，全槽共用**）：
   * 弹卡 → 等用户动作（上限 `timeoutSec`）→ `test` 就地在卡内跑真实探活并重发
   * 卡片 → `switch` 返回 [[Outcome.Switched]] → `dismiss` 返回 [[Outcome.Dismissed]]
   * → 超时回报根会话并返回 [[Outcome.TimedOut]]。
   */
  def raiseAndAwait(
    hub: Option[ActorRef[InteractionHubCommand]],
    rootSessionId: String,
    report: Report,
    candidates: List[String],
    probe: Probe,
    sourceAgent: String,
    sourceSession: String,
    timeoutSec: Int = DefaultTimeoutSec
  ): IO[Outcome] =
    hub match
      case None => IO.pure(Outcome.Unwired)
      case Some(h) =>
        def send(payload: Json): IO[Deferred[IO, ModelUnavailableAnswer]] =
          for
            d <- Deferred[IO, ModelUnavailableAnswer]
            rid = s"model-unavailable-${java.util.UUID.randomUUID().toString.take(12)}"
            _ <- IO(
              h ! InteractionHubCommand.Request(
                InteractionRequest(
                  requestId = rid,
                  kind = InteractionKind.ModelUnavailable,
                  payload = payload.asObject.map(o => o.add("requestId", rid.asJson)).map(Json.fromJsonObject).getOrElse(payload),
                  reply = InteractionReply.ModelUnavailableReply(d),
                  rootSessionId = rootSessionId,
                  sourceAgent = sourceAgent,
                  sourceSession = sourceSession
                )
              )
            )
            _ <- logger.info(
              s"model-unavailable card raised slot=${report.slot} ref=${report.providerRef} " +
                s"failure=${FailureClass.wire(report.failure)} state=${payload.hcursor.downField("state").as[String].getOrElse("open")}"
            )
          yield d

        def reportTimeout(): IO[Unit] =
          val note =
            s"无人在 ${timeoutSec}s 内作答——保持该槽现状（fail-closed，未改动 model.preferred）。" +
              s"已尝试动作：报告统一卡片 + 等待用户手动切换/测试。槽=${report.slot} 目标=${report.providerRef}"
          val payload = cardPayload(report, candidates, state = "timeout", reportOnly = true, note = Some(note))
          IO(
            h ! InteractionHubCommand.Request(
              InteractionRequest(
                requestId = s"model-unavailable-timeout-${java.util.UUID.randomUUID().toString.take(12)}",
                kind = InteractionKind.ModelUnavailable,
                payload = payload,
                reply = InteractionReply.ModelUnavailableReply(
                  Deferred.unsafe[IO, ModelUnavailableAnswer]
                ),
                rootSessionId = rootSessionId,
                sourceAgent = sourceAgent,
                sourceSession = sourceSession
              )
            )
          ) *> logger.warn(
            s"model-unavailable card TIMED OUT after ${timeoutSec}s slot=${report.slot} ref=${report.providerRef} — reported to root session $rootSessionId (no silent loss)"
          )

        def await(d: Deferred[IO, ModelUnavailableAnswer], sentPayload: Json): IO[Outcome] =
          IO.race(d.get, IO.sleep(timeoutSec.seconds)).flatMap {
            case Right(()) => reportTimeout().as(Outcome.TimedOut)
            case Left(ans) =>
              ans.action match
                case ModelUnavailableAction.Dismiss => IO.pure(Outcome.Dismissed)
                case ModelUnavailableAction.Switch =>
                  ans.modelRef match
                    case Some(ref) => IO.pure(Outcome.Switched(ref))
                    case None      => IO.pure(Outcome.Dismissed)
                case ModelUnavailableAction.Test =>
                  val target = ans.modelRef.getOrElse(report.providerRef)
                  val (tp, tm) = target.split("/").toList match
                    case p :: rest if rest.nonEmpty => (p, rest.mkString("/"))
                    case _                          => (report.providerId, report.model)
                  probe(tp, tm)
                    .handleError(e => TestResult.Failed(FailureClass.classify(e)._1, FailureClass.classify(e)._2, 0L))
                    .flatMap { tr =>
                      val next = cardPayload(
                        report,
                        candidates,
                        state = "tested",
                        test = Some(tr),
                        note = Some(s"已对 $target 发一次最小真实调用")
                      )
                      send(next).flatMap(d2 => await(d2, next))
                    }
          }

        send(cardPayload(report, candidates, state = "open")).flatMap(d => await(d, Json.obj()))

  // ── 切换落盘（与设置面板同一形态） ─────────────────────────────────────────

  /**
   * 就地写回该槽的 `model.preferred`（与 `PUT /api/agents/:name/model` 的落盘块
   * **同一形态**：槽名归一 → agent.json 去 `model` 键 → deepMerge 新链 → 原子写；
   * sidecar 缺失就地建最小 `{"name": ...}`）。绝不改 `fallbacks`（该段已退役）。
   */
  def writeSlotModel(slot: String, modelRef: String): IO[Either[String, Unit]] =
    IO.blocking {
      val slotName = ModelRoles.canonicalSlotName(slot)
      if !SchemePolicy.isSettable(slotName) then Left(s"slot '$slot' is not settable (no model chain write)")
      else
        val ref = modelRef.trim
        if ref.isEmpty then Left("empty modelRef")
        else
          val dir = PathUtil.dataRoot / "agents" / slotName
          os.makeDir.all(dir)
          val jsonPath = dir / "agent.json"
          if !os.exists(jsonPath) then
            AtomicJson.writeSync(jsonPath, Json.obj("name" -> slotName.asJson).noSpaces)
          io.circe.parser.parse(os.read(jsonPath)) match
            case Left(e) => Left(s"agent.json unparseable: ${e.message}")
            case Right(parsedJson) =>
              val stripped = parsedJson.asObject
                .map(obj => Json.fromFields(obj.toMap.removed("model")))
                .getOrElse(parsedJson)
              val chain = AgentModelConfig(Some(ref), Nil)
              AtomicJson.writeSync(jsonPath, stripped.deepMerge(Json.obj("model" -> chain.asJson)).noSpaces)
              Right(())
    }

  // ── 单点组合子：任何槽的失败路径都过这里 ────────────────────────────────────

  /**
   * **统一的「模型不可用 ⇒ 卡片 ⇒ 手动切换 ⇒ 原请求继续」组合子**（单实现）。
   *
   * 跑 `attempt`；若其失败结果被判为「该槽模型不可用」（`failureOf`），则弹**同一张**
   * 卡片等用户动作：
   *   - `switch` ⇒ [[writeSlotModel]] 写回该槽，然后**重试 `attempt` 一次**（原请求
   *     继续完成，用户不必重发）；
   *   - `test`   ⇒ 在卡片内跑真实探活后仍等下一次动作（回到等待）；
   *   - `dismiss`/超时 ⇒ 返回原失败结果（上游 fail-closed 语义不变，且超时已回报根会话）；
   *   - 未装配 ⇒ 返回原失败结果（既有行为逐字）。
   */
  def withUnavailableCard[A](
    attempt: IO[A],
    failureOf: A => Option[Report],
    runtime: Option[Runtime] = currentRuntime
  ): IO[A] =
    attempt.flatMap { a =>
      failureOf(a) match
        case None => IO.pure(a)
        case Some(report) =>
          runtime match
            case None => IO.pure(a)
            case Some(rt) =>
              for
                hub <- rt.hub()
                root <- rt.rootSessionId()
                cands <- rt.candidates()
                tsec <- configuredTimeoutSec()
                out <- raiseAndAwait(
                  hub = hub,
                  rootSessionId = root,
                  report = report,
                  candidates = cands,
                  probe = rt.probe,
                  sourceAgent = report.agentName,
                  sourceSession = report.agentName,
                  timeoutSec = tsec
                )
                result <- out match
                  case Outcome.Switched(ref) =>
                    writeSlotModel(report.slot, ref).flatMap {
                      case Right(_) =>
                        logger.info(
                          s"model-unavailable card: switched slot=${report.slot} to $ref — retrying the original request"
                        ) *> attempt
                      case Left(err) =>
                        logger.warn(s"model-unavailable card: switch write failed ($err) — original failure stands") *> IO.pure(a)
                    }
                  case _ => IO.pure(a)
              yield result
    }

  /** 便捷汇报体构造（槽 → 标签/规范槽名；`ModelRoles.Slots` 是槽名单点）。 */
  def reportFor(slot: String, providerId: String, model: String, failure: FailureClass, detail: String): Report =
    val label = ModelRoles.Slots.find { case (wire, _, dir) => wire == slot || dir == slot }.map(_._2).getOrElse(slot)
    Report(
      slot = slot,
      slotLabel = label,
      agentName = ModelRoles.canonicalSlotName(slot),
      providerId = providerId,
      model = model,
      failure = failure,
      detail = detail
    )

end ModelUnavailableCard
