package nebflow.core.project

import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import nebflow.core.NebflowLogger
import nebflow.llm.LlmInterface
import nebflow.shared.Defaults

/**
 * 优雅关机「排水中」标志（中断恢复语义批 2026-09-13，spec
 * `.nebflow/Spec/20260908_interrupt-recovery-semantics.md` §2.3）。
 *
 * 语义 = 「进程正在优雅关机」这一瞬态事实的进程内单点。置位后：
 *   - `NodeEngine.failNode` / `NodeEngine.autoFailDeadRunning` 头部守卫拒绝写 failed
 *     + 拒绝 `deliverFailed`（abort 钩子诱发的失败链全部撞上守卫——spec §1.2 逐跳噪音
 *     链的拦断面）；
 *   - 唯一置位点 = [[GracefulInterruptHook]]（关机钩子线程），**且必须先于**在飞 LLM
 *     abort，否则「abort 先跑、失败链先落 failed」的窗口重新出现；
 *   - 回滚开关 `Defaults.ShutdownInterruptEnabled=false` ⇒ 钩子不置位 ⇒ 标志恒 false
 *     （守卫结构性失效 = 现行为逐字节）。
 *
 * 不落 ProjectDef/配置：行为开关走 `Defaults`，生命周期事实走本对象（单进程、单
 * 钩子线程，无持久化语义——进程蒸发即归零）。
 */
object ShutdownState:

  private val drainingFlag = new AtomicBoolean(false)

  /** 排水中？——守卫只读本值。 */
  def draining: Boolean = drainingFlag.get()

  /** 置位（幂等）：首次置位返回 true。 */
  def beginDraining(): Boolean = drainingFlag.compareAndSet(false, true)

  /** 测试复位（生产无调用点——进程生命周期内只置不清）。 */
  private[project] def resetForTest(): Unit = drainingFlag.set(false)

/**
 * 优雅关机单钩子（spec §2.3-2）：**两处注册点合并为一个**
 * （`Main.bootGateway` / `Main.succeedGateway` 原各自的 `cancelAllInflightSync` 钩子）。
 *
 * 线程内**严格有序**（顺序即正确性——draining 先置，abort 诱发的全部失败链才会撞上
 * 守卫；不存在「abort 先跑、失败链先落 failed」的窗口）：
 *
 *   1. `ShutdownState.beginDraining()`
 *   2. 遍历 `ProjectRuntimeRegistry` 全部已挂载项目：快照 → `filter(Running)` →
 *      逐节点 CAS `Running → Interrupted`（+ `bgWait=None`，G4 同语义：等待集随进程
 *      蒸发，resume prompt 既有死亡告知自动生效）→ WS `nodeUpdated` + `FlowMapEventLog`
 *      "interrupted" 审计留痕；带整体 deadline（超时放弃剩余翻转）
 *   3. `LlmInterface.cancelAllInflightSync()`（2026-08-19 P0 token 燃烧防线**原样保留、
 *      顺序在后**）
 *
 * 回滚（`Defaults.ShutdownInterruptEnabled=false`）：只执行第 3 步——与本批前逐字节一致。
 *
 * 执行形态：钩子线程上 `unsafeRunSync`（`LlmInterface.cancelAllInflightSync` 同款既有
 * 先例）。异常一律吞掉——钩子内的异常绝不能阻断 JVM 关机（catch Throwable 后仍执行
 * abort 腿）。
 */
object GracefulInterruptHook:

  private val logger = NebflowLogger.forName("nebflow.project.shutdown")

  /** 翻态报告（可断言形态：测试经 [[interruptRunningNodes]] 取用）。 */
  final case class Report(
    /** 已成功翻转（Running → Interrupted）的节点 id。 */
    flipped: List[String],
    /** 因整体 deadline 到达而未处置、仍停留 Running 的节点 id（残余 ⇒ kill -9 同款 sweep 兜底）。 */
    remaining: List[String],
    /** deadline 是否触发（remaining.nonEmpty 的必要非充分条件——翻转失败也可能是 CAS 输给并发终态化）。 */
    timedOut: Boolean
  ):
    def flippedCount: Int = flipped.size

  /** 钩子执行体（JVM 关机钩子线程内同步跑）。`timeoutMs` 默认取
    * `Defaults.ShutdownInterruptTimeoutMs`（唯一取值点）；测试传注入值验证降级路径。 */
  def run(timeoutMs: Long = Defaults.ShutdownInterruptTimeoutMs): Unit =
    if !Defaults.ShutdownInterruptEnabled then
      // 回滚形态：无翻态、无守卫、无 draining——只有本批前既有的 abort 在飞 LLM。
      LlmInterface.cancelAllInflightSync()
    else
      try
        ShutdownState.beginDraining()
        interruptRunningNodes(timeoutMs).unsafeRunSync()(using global) match
          case Report(flipped, remaining, timedOut) =>
            if flipped.nonEmpty then
              logger.warn(
                s"graceful shutdown: ${flipped.size} running node(s) marked interrupted (non-terminal — boot recovery will resume them): ${flipped.mkString(",")}")
            if timedOut then
              logger.warn(
                s"graceful shutdown: interrupt sweep timed out after ${timeoutMs}ms — ${remaining.size} node(s) left Running " +
                  s"(they fall back to the kill -9 style boot sweep path): ${remaining.mkString(",")}")
      catch
        case e: Throwable =>
          logger.warn(s"graceful shutdown: interrupt sweep failed: ${Option(e.getMessage).getOrElse(e.toString)}")
      finally
        // token 燃烧防线（2026-08-19 P0）：原样保留，顺序恒在翻态之后。
        LlmInterface.cancelAllInflightSync()

  /** 翻态主体（IO 形态，可测）：遍历全部已挂载项目的 Running 节点逐个 CAS。
    *
    * deadline 语义：`clock()` ≥ deadline 即放弃剩余节点（记入 `remaining` 并置
    * `timedOut`）——**降级路径**（残余 Running 由 boot sweep 认领，行为安全降级）。
    * `timeoutMs = 0` ⇒ 全部节点直接降级（测试的确定性入口）。
    *
    * 逐节点 fresh 守卫（R2 纪律）：`Running → Interrupted` 单事务 CAS；节点已终态 /
    * 已翻转 / 消失 ⇒ 拒写（不覆盖并发终态化，也不重复发事件）。本批不改任何其他字段：
    * `sessionRef`（恢复依据）与 `deliveredTo`（in-barrier 已收投递）原样保留，`result`
    * 不写（无失败事实），TTL 不写（无 TTL 写点 ⇒ 永不自动归档）。 */
  def interruptRunningNodes(
    timeoutMs: Long,
    clock: () => Long = () => System.currentTimeMillis()
  ): IO[Report] =
    val deadline = clock() + math.max(0L, timeoutMs)
    ProjectRuntimeRegistry.all.flatMap { runtimes =>
      runtimes.foldLeft(IO.pure(Report(Nil, Nil, false))) { (acc, rt) =>
        acc.flatMap { rep =>
          rt.store.snapshot.flatMap { snap =>
            val running = snap.nodes.values.filter(_.status == NodeLifecycle.Running).toList.sortBy(_.id)
            running.foldLeft(IO.pure(rep)) { (acc2, node) =>
              acc2.flatMap { r =>
                if clock() >= deadline then
                  IO.pure(r.copy(remaining = r.remaining :+ node.id, timedOut = true))
                else interruptNode(rt, node.id).map {
                  case true  => r.copy(flipped = r.flipped :+ node.id)
                  case false => r // CAS 输给并发状态变更（已终态化/已翻转）——既非翻转也非残余
                }
              }
            }
          }
        }
      }
    }

  /** 单节点翻态：`Running → Interrupted`（+ bgWait 清空）+ WS 事件 + 审计留痕。 */
  private def interruptNode(rt: ProjectRuntime, nodeId: String): IO[Boolean] =
    IO(System.currentTimeMillis()).flatMap { now =>
      rt.store.mutateWithResult { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            (st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Interrupted,
              // G4 同语义：等待集随进程蒸发（resume prompt 既有死亡告知自动生效）。
              bgWait = None))), true)
          case _ => (st, false)
      }.flatMap { case (s, changed) =>
        if !changed then IO.pure(false)
        else
          s.nodes.get(nodeId).traverse_ { n =>
            rt.engine.emitUpdated(n) *>
              FlowMapEventLog.append(rt.project.workspace, rt.project.name, nodeId,
                NodeEngine.InterruptedEventType,
                "graceful shutdown: host is draining — node marked interrupted (non-terminal; " +
                  s"crash recovery will resume it from its checkpoint; startedAt=${n.startedAt.getOrElse(0L)}, " +
                  s"sessionRef=${n.sessionRef.getOrElse("-")}; cause: SIGINT/SIGTERM on the host)") *>
              logger.warn(
                s"Node '${n.name}' ($nodeId) marked interrupted by graceful shutdown (host draining, status now ${NodeLifecycle.Interrupted})")
          }.as(true)
      }
    }

end GracefulInterruptHook
