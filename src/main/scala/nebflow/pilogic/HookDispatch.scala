package nebflow.pilogic

import cats.MonadThrow
import cats.effect.Concurrent
import cats.effect.kernel.Ref
import cats.syntax.all.*
import io.circe.Json

/** 执行面（设计 §Ⅲ.3/§Ⅲ.4/§Ⅲ.5）：Interceptor/Observer 单点分派 + 执行序/短路/否决传播 + 隔离包装。
  *
  * 隔离性做进 dispatch 单点（不是靠各调用点自觉）：任何 hook 异常 ⇒ HookErrored 事件 +
  * 按位点 HookErrorPolicy 裁决——一个 hook 抛异常不得整轮失败（验收 A-6）。
  * 双臂 recover 先例（亲读引用）：`core/hooks/HookRunner.scala:40-52`——
  * `continueOnError=true` ⇒ WARN + `[Hook error: …]` 上下文继续；`false` ⇒ `HookResult.block`。
  * 本 dispatch 把该双臂策略化为位点级三档 HookErrorPolicy（Continue/Block/FailRun）。
  *
  * L-11（增量轮落地）：Dropping 位观察件经 run-scoped 单槽忙门非阻塞投递——门忙 ⇒ 该帧
  * 丢弃（声明的有损通道，非错误、不发 HookErrored）；生产者永不因观察者阻塞（对位 pi
  * provider_stream_event 顺序 await 拖慢流的自认病，不照抄）。
  */

/** 分派结果：观察族=Observed（无返值通道）；拦截族=Decided（聚合后最终效果，引擎必读）。
  * endRequested=turn.Ended 位的 EndRun/Veto 读取辅助（循环收尾档判定）。 */
enum DispatchResult:
  case Observed
  case Decided(effect: HookEffect)
  def endRequested: Boolean = this match
    case DispatchResult.Decided(HookEffect.EndRun | HookEffect.Veto(_)) => true
    case _                                                             => false

/** L-11 丢弃档的 run-scoped 单槽忙门集：run 开始时对快照内 Dropping 位观察件逐个构建
  * （Ref.of 安全构造——模块内禁 Ref.unsafe，A-4 grep 门），随 run 生灭；按 HookId 键控。
  * 缺省 none：无门时 Dropping 位退化为顺序投递（独立调用 dispatch 的测试/接线层缺省面）。 */
final class DropGates[F[_]] private[pilogic] (private[pilogic] val map: Map[HookId, Ref[F, Boolean]])

object DropGates:
  def none[F[_]]: DropGates[F] = new DropGates(Map.empty)
  /** 循环 run 开始时调用：为快照内一切 Dropping 位观察件建门（Ref.of 经 Ref.Make
    * 从 Concurrent 派生——安全构造，模块内禁 Ref.unsafe，A-4 grep 门保持）。 */
  def of[F[_]: Concurrent](snapshot: HookSnapshot[F]): F[DropGates[F]] =
    val ids = snapshot.entries.collect {
      case o: ObserverEntry[F] if o.point.backpressure == Backpressure.Dropping => o.id
    }
    ids.traverse(id => Ref.of[F, Boolean](false).map(id -> _)).map(es => new DropGates(es.toMap))

object HookDispatch:

  /** 单点分派（§Ⅲ.1：Interceptor 返回必被消费的唯一 match 点）。
    * 位点聚合档决定执行序/短路；位点错误档决定异常裁决；快照语义=本调用不可见期间注册；
    * 位点背压档（L-11）决定观察腿投递方式：Sequential=顺序 await（缺省），Dropping=忙门
    * 非阻塞（gates 有门时），无门退化为顺序（独立调用面）。 */
  def dispatch[F[_]: Concurrent](
      point: HookPoint,
      input: Json,
      snapshot: HookSnapshot[F],
      sink: EventSink[F],
      gates: DropGates[F] = DropGates.none[F],
  ): F[DispatchResult] =
    val handlers = snapshot.orderedAt(point)
    point.aggregate match
      case Aggregate.AllRun =>
        handlers
          .collect { case o: ObserverEntry[F] => o }
          .traverse_ { o =>
            point.backpressure match
              case Backpressure.Sequential => observeSequential[F](point, o, input, sink)
              case Backpressure.Dropping   => observeDropping[F](point, o, input, sink, gates)
          }
          .as(DispatchResult.Observed)
      case _ =>
        val interceptors = handlers.collect { case i: InterceptorEntry[F] => i }
        aggregateInterceptors[F](point, input, interceptors, sink)
          .map(DispatchResult.Decided(_))

  /** 顺序档观察腿：await 完成再返回；异常 ⇒ HookErrored + 继续（隔离 Continue 档）。 */
  private def observeSequential[F[_]: MonadThrow](
      point: HookPoint,
      o: ObserverEntry[F],
      input: Json,
      sink: EventSink[F],
  ): F[Unit] =
    o.observe(input).attempt.flatMap {
      case Right(_)  => MonadThrow[F].unit
      case Left(err) => onHookError[F](point, o.id, err, HookErrorPolicy.Continue, sink).void
    }

  /** 丢弃档观察腿（L-11）：忙门空闲 ⇒ 后台投递（不 await，生产者即刻返回）；门忙 ⇒ 丢帧。
    * 投递 fiber 自带隔离与释门（attempt 后必释）——异常照发 HookErrored（错误通道不丢），
    * 只有帧可丢；这是「声明的有损通道」，丢帧非错误故无事件（设计 §Ⅵ L-11 推荐档）。 */
  private def observeDropping[F[_]: Concurrent](
      point: HookPoint,
      o: ObserverEntry[F],
      input: Json,
      sink: EventSink[F],
      gates: DropGates[F],
  ): F[Unit] =
    val F = Concurrent[F]
    gates.map.get(o.id) match
      case None => observeSequential[F](point, o, input, sink) // 无门退化：独立调用面
      case Some(gate) =>
        gate.modify(busy => (true, !busy)).flatMap { wasFree =>
          if !wasFree then F.unit // 丢帧：上一帧仍在投递中
          else
            F.start(
              observeSequential[F](point, o, input, sink).attempt.flatMap(_ => gate.set(false))
            ).void
        }

  /** 隔离单点：hook 异常 → HookErrored 事件 + 策略裁决。
    * 错误文案提取对位 HookRunner.scala:41 同款（Option(getMessage).getOrElse(类简单名)）。 */
  def onHookError[F[_]: MonadThrow](
      point: HookPoint,
      hookId: HookId,
      err: Throwable,
      policy: HookErrorPolicy,
      sink: EventSink[F],
  ): F[Option[HookEffect]] =
    val F = MonadThrow[F]
    val cause = Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
    val event = LoopEvent.HookErrored(point.display, hookId.value, cause)
    policy match
      case HookErrorPolicy.Continue =>
        // 双臂的「继续」臂：跳过该 hook，run 继续（HookRunner:42-48 同形）
        sink.emit(event).as(Option.empty[HookEffect])
      case HookErrorPolicy.Block =>
        // 双臂的「阻断」臂：fail-closed 不回落（HookRunner:49-51 同形）
        sink.emit(event).as(Some(HookEffect.Block(s"hook failed: $cause", terminate = false)))
      case HookErrorPolicy.FailRun =>
        sink.emit(event) *> F.raiseError(LoopError.HookRunFatal(point.display, hookId.value, cause))

  /** 聚合执行（§Ⅲ.3 + #45 patch/replace 显式分档）：
    *   - Chain     逐个链式：Modify=整块替换、Patch=deepMerge 增量；决定性效果短路
    *   - FirstWins 首个非弃权效果即胜短路（Patch 折叠为 Modify(合并值)）
    *   - LastWins  全跑，最后一个非弃权效果胜（Proceed=弃权）
    *   - AllRun    不可达腿（外层走观察腿），保持穷举 */
  private def aggregateInterceptors[F[_]: MonadThrow](
      point: HookPoint,
      input: Json,
      entries: Vector[InterceptorEntry[F]],
      sink: EventSink[F],
  ): F[HookEffect] =
    val F = MonadThrow[F]
    val agg = point.aggregate

    def loop(
        rest: Vector[InterceptorEntry[F]],
        cur: Json,
        lastDecisive: Option[HookEffect],
        modified: Boolean,
    ): F[HookEffect] =
      rest.headOption match
        case None =>
          agg match
            case Aggregate.Chain     => F.pure(if modified then HookEffect.Modify(cur) else HookEffect.Proceed)
            case Aggregate.FirstWins => F.pure(lastDecisive.getOrElse(HookEffect.Proceed))
            case Aggregate.LastWins  => F.pure(lastDecisive.getOrElse(if modified then HookEffect.Modify(cur) else HookEffect.Proceed))
            case Aggregate.AllRun    => F.pure(HookEffect.Proceed)
        case Some(e) =>
          e.handle(cur).attempt.flatMap {
            case Right(effect) =>
              effect match
                case HookEffect.Proceed => loop(rest.tail, cur, lastDecisive, modified)
                case HookEffect.Modify(v) =>
                  agg match
                    case Aggregate.Chain     => loop(rest.tail, v, lastDecisive, modified = true)
                    case Aggregate.LastWins  => loop(rest.tail, v, Some(HookEffect.Modify(v)), modified = true)
                    case Aggregate.FirstWins => F.pure(HookEffect.Modify(v))
                    case Aggregate.AllRun    => F.pure(HookEffect.Modify(v))
                case HookEffect.Patch(delta) =>
                  val merged = cur.deepMerge(delta)
                  agg match
                    case Aggregate.Chain     => loop(rest.tail, merged, lastDecisive, modified = true)
                    case Aggregate.LastWins  => loop(rest.tail, merged, Some(HookEffect.Modify(merged)), modified = true)
                    case Aggregate.FirstWins => F.pure(HookEffect.Modify(merged))
                    case Aggregate.AllRun    => F.pure(HookEffect.Modify(merged))
                case other =>
                  agg match
                    case Aggregate.LastWins => loop(rest.tail, cur, Some(other), modified)
                    case _                  => F.pure(other)
            case Left(err) =>
              onHookError[F](point, e.id, err, e.errorPolicy, sink).flatMap {
                case Some(effect) =>
                  effect match
                    case b @ (_: HookEffect.Block) =>
                      agg match
                        case Aggregate.LastWins => loop(rest.tail, cur, Some(b), modified)
                        case _                  => F.pure(b) // fail-closed 短路
                    case other => F.pure(other)
                case None => loop(rest.tail, cur, lastDecisive, modified) // Continue：跳过该 hook
              }
          }

    loop(entries, input, None, modified = false)

end HookDispatch
