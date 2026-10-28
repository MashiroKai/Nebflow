package nebflow.llm.decision

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

/** Health state of the decision face (mirrors `SearchApiHealth`). */
enum DecisionHealth:
  case Unconfigured
  case Up
  case Down(reason: String, since: Long)

/**
 * Narrow health port of the decision face (Face A / P1-A1 adaptation layer).
 *
 * Deliberately NARROW and self-contained rather than another expansion of
 * `core.ProviderHealthPort`: the decision channel must be independent of the
 * model health face (card §A.2.8), and keeping it off the shared port leaves
 * the existing `/api/health` payload byte-identical — which is exactly what
 * the P2 golden-diff assertions on the REST payload face require. The two
 * member signatures mirror `recordSearchSuccess` / `recordSearchFailure`
 * (`core/ports.scala:188-190`) so a later batch can lift this onto the shared
 * port mechanically if the payload face is ever opened for it.
 */
trait DecisionHealthPort:
  /** Record a successful decision call. */
  def recordDecisionSuccess(): IO[Unit]

  /** Record a failed decision call (reason = classified diagnostic). */
  def recordDecisionFailure(reason: String): IO[Unit]

  /** Current decision-face health. */
  def getDecisionHealth: IO[DecisionHealth]

  /**
   * 三改③（jev-panel-threefix 批，2026-10-09 用户反馈③）：一次**复合分配调用**
   * 的分腿记账——`legs` 每键 = 一条实际开跑的腿的终态（`"jev"` / `"llm"`），
   * `effective` = 通道整体终态（与 `recordDecisionSuccess/Failure` 同义法）。
   *
   * 蛋白点：回退发生时通道整体可能是 Up（llm 兜底腿产出集合）而 jev 腿是
   * Down——单一 tri-state 会把「Jev 不可用 → 已回退 llm」这个故事抹平（旧
   * 行为：jev 失败 Down 被 llm 成功 Up 覆盖）。分腿键让读面（REST
   * `toJson` 的 `legs` 块 + 设置页健康徽标）把两腿状态**分开如实显示**。
   *
   * 默认空实现：唯一语义是「不记账」——既有直写腿（`DecisionHttpTransport`
   * 持 `Option[DecisionHealthPort]` 的 success/failure 对）不感知本方法，
   * trait 侧零源迁移。
   */
  def recordAllocationOutcome(legs: Map[String, DecisionHealth], effective: DecisionHealth): IO[Unit] =
    IO.unit

end DecisionHealthPort

/**
 * In-memory decision-health monitor.
 *
 * Shape precedent = `ProviderHealthMonitor`'s search ref (`HealthMonitor.scala:67-83`):
 * a single `Ref` starting `Unconfigured`, set to `Up`/`Down` by the call site.
 * Nothing here reads a stored credential or a request body — only the
 * classified diagnostic string ever lands in the state.
 */
final class DecisionHealthMonitor extends DecisionHealthPort:

  private val ref: Ref[IO, DecisionHealth] = Ref.unsafe(DecisionHealth.Unconfigured)

  /**
   * 分腿终态（三改③）：键 = 腿名（`"jev"` / `"llm"`，与
   * `PluginAllocatorMachine` 的写腿对齐），值 = 该腿最近一次**实际开跑**的
   * 终态。未开跑的腿**不造键**（gate off / 腿未装配 = 键缺席——显示面据此
   * 区分「没跑」与「跑了挂了」，绝不虚构状态）。
   */
  private val legsRef: Ref[IO, Map[String, DecisionHealth]] = Ref.unsafe(Map.empty)

  def recordDecisionSuccess(): IO[Unit] =
    ref.set(DecisionHealth.Up)

  def recordDecisionFailure(reason: String): IO[Unit] =
    ref.set(DecisionHealth.Down(reason, System.currentTimeMillis()))

  def getDecisionHealth: IO[DecisionHealth] =
    ref.get

  /** Per-leg terminal states of the last composite allocation call (三改③). */
  def legs: IO[Map[String, DecisionHealth]] = legsRef.get

  override def recordAllocationOutcome(
    legs: Map[String, DecisionHealth],
    effective: DecisionHealth
  ): IO[Unit] =
    legsRef.set(legs) *> ref.set(effective)

  /** JSON view for diagnostics (same key spelling as the search channel). */
  def toJson: IO[Json] =
    (ref.get, legsRef.get).mapN { (base, legs) =>
      val baseJson = base match
        case DecisionHealth.Unconfigured => Json.obj("status" -> "unconfigured".asJson)
        case DecisionHealth.Up => Json.obj("status" -> "up".asJson)
        case DecisionHealth.Down(reason, since) =>
          Json.obj("status" -> "down".asJson, "reason" -> reason.asJson, "since" -> since.asJson)
      // legs 键是**加法**扩展：无分腿记账（legacy 直写路径）时载荷与旧形
      // byte-identical（P2 golden-diff 纪律）；有记账才多出 `legs` 块。
      if legs.isEmpty then baseJson
      else
        baseJson.deepMerge(Json.obj("legs" -> Json.fromFields(legs.toList.sortBy(_._1).map { case (k, v) =>
          k -> legJson(v)
        })))
    }

  private def legJson(h: DecisionHealth): Json = h match
    case DecisionHealth.Unconfigured => Json.obj("status" -> "unconfigured".asJson)
    case DecisionHealth.Up => Json.obj("status" -> "up".asJson)
    case DecisionHealth.Down(reason, since) =>
      Json.obj("status" -> "down".asJson, "reason" -> reason.asJson, "since" -> since.asJson)

end DecisionHealthMonitor

object DecisionHealthMonitor:
  def create: IO[DecisionHealthMonitor] = IO(new DecisionHealthMonitor)
