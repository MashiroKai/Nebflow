package nebflow.pilogic

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.Json

/** 执行面（设计 §Ⅲ.3/§Ⅲ.4/§Ⅲ.5）：Interceptor/Observer 单点分派 + 执行序/短路/否决传播 + 隔离包装。
  *
  * 隔离性做进 dispatch 单点（不是靠各调用点自觉）：任何 hook 异常 ⇒ HookErrored 事件 +
  * 按位点 HookErrorPolicy 裁决——一个 hook 抛异常不得整轮失败（验收 A-6）。
  * 双臂 recover 先例（亲读引用）：`core/hooks/HookRunner.scala:40-52`——
  * `continueOnError=true` ⇒ WARN + `[Hook error: …]` 上下文继续；`false` ⇒ `HookResult.block`。
  * 本 dispatch 把该双臂策略化为位点级三档 HookErrorPolicy（Continue/Block/FailRun）。
  */

/** 分派结果：观察族=Observed（无返值通道）；拦截族=Decided（聚合后最终效果，引擎必读）。 */
enum DispatchResult:
  case Observed
  case Decided(effect: HookEffect)

object HookDispatch:

  /** 单点分派（§Ⅲ.1：Interceptor 返回必被消费的唯一 match 点）。
    * 位点聚合档决定执行序/短路；位点错误档决定异常裁决；快照语义=本调用不可见期间注册。 */
  def dispatch[F[_]: MonadThrow](
      point: HookPoint,
      input: Json,
      snapshot: HookSnapshot[F],
      sink: EventSink[F],
  ): F[DispatchResult] =
    val handlers = snapshot.orderedAt(point)
    point.aggregate match
      case Aggregate.AllRun =>
        handlers
          .collect { case o: ObserverEntry[F] => o }
          .traverse_ { o =>
            o.observe(input).attempt.flatMap {
              case Right(_)  => MonadThrow[F].unit
              case Left(err) => onHookError[F](point, o.id, err, HookErrorPolicy.Continue, sink).void
            }
          }
          .as(DispatchResult.Observed)
      case _ =>
        val interceptors = handlers.collect { case i: InterceptorEntry[F] => i }
        aggregateInterceptors[F](point, input, interceptors, sink)
          .map(DispatchResult.Decided(_))

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

  /** 聚合执行（§Ⅲ.3）：
    *   - Chain     逐个链式，Modify 换入参继续，决定性效果（Veto/Block/Inject/ContinueRun/EndRun）短路
    *   - FirstWins 首个非弃权效果即胜短路
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
            case Aggregate.LastWins  => F.pure(lastDecisive.getOrElse(HookEffect.Proceed))
            case Aggregate.AllRun    => F.pure(HookEffect.Proceed)
        case Some(e) =>
          e.handle(cur).attempt.flatMap {
            case Right(effect) =>
              effect match
                case HookEffect.Proceed => loop(rest.tail, cur, lastDecisive, modified)
                case m @ HookEffect.Modify(v) =>
                  agg match
                    case Aggregate.Chain     => loop(rest.tail, v, lastDecisive, modified = true)
                    case Aggregate.LastWins  => loop(rest.tail, v, Some(m), modified = true)
                    case Aggregate.FirstWins => F.pure(m)
                    case Aggregate.AllRun    => F.pure(m)
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
