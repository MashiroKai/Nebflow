package nebflow.pilogic

import cats.Applicative
import cats.effect.Sync
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json

/** 事件与出口（设计 §Ⅱ.3/§Ⅱ.4）：LoopEvent 密封层级 + EventSink[F] 推送面 + 崩溃合成助手。
  * L-2 采纳推荐档 (c)：自建 EventSink 接口——push 对位 scala 现状双腿、隔离单点化、不把 fs2 焊进事件面。
  */

/** 循环消息模型（本轮最小垂直切片占位：role + content JSON；完整消息模型桥接属接线期）。 */
enum LoopMessageRole:
  case System
  case User
  case Assistant
  case ToolResult

final case class LoopMessage(role: LoopMessageRole, content: Json)

/** 中止原因（RunOutcome.Aborted 载荷）。 */
sealed trait AbortCause extends Product with Serializable
object AbortCause:
  case object UserRequested extends AbortCause
  final case class External(reason: String) extends AbortCause

/** 循环错误（RunOutcome.Failed 载荷；BudgetExhausted=预算停路径）。
  * 兼作 F 错误通道的 Throwable（HookRunFatal 经 dispatch FailRun 档 raiseError）——类型化错误，不抛裸串。 */
sealed abstract class LoopError(msg: String) extends Throwable(msg) with Product with Serializable
object LoopError:
  final case class ProviderFailed(message: String) extends LoopError(message)
  final case class HookRunFatal(point: String, hookId: String, cause: String) extends LoopError(s"$point: $cause")
  final case class InvalidContinuation(message: String) extends LoopError(message)
  /** RunGate（#42 before_drive 对位）Veto 的落点：门拒了整趟，run 未跑任何轮。 */
  final case class RunGated(reason: String) extends LoopError(s"run gated: $reason")
  case object BudgetExhausted extends LoopError("budget exhausted")

/** 收尾四态（设计 §Ⅱ.1；穷举，编译期封闭）。 */
enum RunOutcome:
  /** 自然/显式结束；newMessages 与 RunEnded 事件载荷同源（pi 关键不变量）。 */
  case Completed(newMessages: Vector[LoopMessage])
  /** 外部中断（provider 契约把中断编码为 stopReason=Aborted，不抛异常）。 */
  case Aborted(cause: AbortCause)
  /** error 路径（含 BudgetExhausted）。 */
  case Failed(cause: LoopError)
  /** postRun.Settle 强制续轮后的簿记复入态：continuations=续轮次数。
    * newMessages 为增量轮补载荷（对方案 §Ⅱ.1 纸面形的偏离，理由：方案 §Ⅱ.1 自己钉死的
    * 不变量「newMessages 既是返回值又是收尾事件载荷」须对一切自然收尾路径成立——
    * settle 强制续轮后的收尾若不带消息即违反该不变量）。 */
  case SettledExtra(continuations: Int, newMessages: Vector[LoopMessage])

/** 单轮收尾局（TurnEnded 载荷）。 */
enum TurnEndOutcome:
  case Natural
  case ExplicitEnd
  case Error(message: String)
  case Aborted
  case Truncated

/** 循环事件（设计 §Ⅱ.3，穷举 12——对位 pi AgentEvent 10 + HookErrored + BudgetReported）。
  * 分支器密封穷举：每个分支的产出含事件——不发事件的分支类型不成立（病灶 #3 的编译期免疫）。 */
enum LoopEvent:
  case RunStarted
  case TurnStarted(turn: TurnNumber)
  case MessageStarted(seq: MessageSeq)
  case MessageDelta(seq: MessageSeq, chunk: Json)
  case MessageEnded(seq: MessageSeq, message: LoopMessage)
  case ToolExecutionStarted(id: String, name: String)
  case ToolExecutionDelta(id: String, partial: Json)
  case ToolExecutionEnded(id: String, result: Json, isError: Boolean)
  case TurnEnded(
    turn: TurnNumber,
    message: Option[LoopMessage],
    toolResults: Vector[LoopMessage],
    outcome: TurnEndOutcome,
  )
  case RunEnded(outcome: RunOutcome)
  case HookErrored(point: String, hookId: String, cause: String)
  case BudgetReported(snapshot: CostSnapshot)

/** 事件出口（L-2 自建）：循环推事件给 sink；sink 是接口，实现自由（WS 帧/审计行/合批下沉为接线期适配层）。
  * emit 是总函数——类型上没有「返回值被忽略」的口子，实现抛错由 sink 适配层隔离。 */
trait EventSink[F[_]]:
  def emit(e: LoopEvent): F[Unit]

object EventSink:

  /** 缺省 Noop。 */
  def noop[F[_]: Applicative]: EventSink[F] = _ => Applicative[F].unit

  /** 测试/断言用收集 sink（验收 A-5 载体）。 */
  def collecting[F[_]: Sync]: F[(EventSink[F], F[Vector[LoopEvent]])] =
    Ref.of[F, Vector[LoopEvent]](Vector.empty).map { ref =>
      val sink = new EventSink[F]:
        def emit(e: LoopEvent): F[Unit] = ref.update(_ :+ e)
      (sink, ref.get)
    }

  /** 拉模型桥（设计 §Ⅱ.3 EventSink companion 第三件）：要拉模型的消费者经 Queue 适配——
    * 用 cats-effect-std Queue 而非 fs2（§Ⅱ.3(c)「不把 fs2 类型焊进事件面」；fs2 消费者
    * 自行以 fs2.Stream.fromQueueUnterminated 适配，属接线层裁量）。16 签名增量轮落地。 */
  def toChannel[F[_]](q: cats.effect.std.Queue[F, LoopEvent]): EventSink[F] =
    e => q.offer(e)

  /** 崩溃兜底助手（§Ⅱ.4(4)；对位 pi runWithLifecycle → handleRunFailure：
    * 循环崩溃对下游仍呈现为一次正常收尾——合成 TurnEnded(空)+RunEnded 终态对）。 */
  def synthesizeTruncatedEvents(
      activeTurn: Option[TurnNumber],
      aborted: Boolean,
  ): Vector[LoopEvent] =
    val outcome: RunOutcome =
      if aborted then RunOutcome.Aborted(AbortCause.External("truncated"))
      else RunOutcome.Failed(LoopError.ProviderFailed("loop truncated"))
    activeTurn match
      case Some(t) =>
        Vector(
          LoopEvent.TurnEnded(
            t,
            None,
            Vector.empty,
            if aborted then TurnEndOutcome.Aborted else TurnEndOutcome.Error("loop truncated"),
          ),
          LoopEvent.RunEnded(outcome),
        )
      case None => Vector(LoopEvent.RunEnded(outcome))

end EventSink
