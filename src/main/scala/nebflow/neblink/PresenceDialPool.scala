package nebflow.neblink

import nebflow.neblink.relay.DialBudget

import cats.effect.IO

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{
  CompletableFuture,
  SynchronousQueue,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit
}

/**
 * presence 拨号专用阻塞池（presence-dial-overhaul 批 2026-11-04，作者 13:05 批「可以派」）。
 *
 * == 病灶（KAI 批实测，本批 P2 的直接依据） ==
 *
 * 改前 `dialCandidates` 用 `IO.blocking(openConnection(...))` 拨候选。**`IO.blocking` 在
 * cats-effect 3.6 的 WSTP 上并不换线程**——`WorkerThread.canExecuteBlockingCodeOn(pool)`
 * 对「本 worker 属于本池」恒 `true`（cats-effect 3.6.1 字节码实测：该方法只比
 * `pool eq 自己的池`），于是阻塞体**就地跑在 `io-compute-N` 工作线程上**，只把该 worker
 * 标成 blocking（`prepareForBlocking`）并让 `io-compute-blocker` 接手它的队列。
 *
 * 后果（KAI 场景实测）：一次 presence 拨号轮 = 两个候选串行各付满预算 = **4500ms**，
 * 期间**一条 compute 工作线程整段被占**；多设备并发拨号时 blocker 池被占满，
 * 12:52 那条 **11.3s 冷开离群**正是它造成的（`20261104_113647_kai-probe-tax.md` §5）。
 *
 * == 本文件的两条硬规矩 ==
 *
 *  1. **拨号/连接类阻塞调用一律跑在这里**（`presence-dial-*` 专用池），
 *     **绝不占用 `io-compute-*`**；
 *  2. **线程占用断言**（[[requireDialThread]]）：任何在这条腿里跑到的拨号体，若当前线程
 *     不是 `presence-dial-*`，立刻抛 [[PresenceDialThreadViolation]]——把「有人把它改回
 *     `IO.blocking`」从「性能悄悄退化」变成「当场炸红」。
 *
 * == 池形态（有界，不是无界并发） ==
 *
 * `core=8 / max=32 / SynchronousQueue / 30s keepAlive / daemon`：
 *  - `SynchronousQueue` 是**必须**的：`ThreadPoolExecutor` 在「有界队列」形态下会
 *    「先排队、不扩线程」（经典坑）；SynchronousQueue 才是「有活就开线程，到 max 才拒绝」。
 *  - 上限 32 = 同时能有多少条拨号体在飞。每轮每设备最多 `maxConcurrentPerRound`(4) 条，
 *    32 覆盖 8 台设备同时拨号；超出 → `RejectedExecutionException`（`dialOne` 记为一次
 *    失败并留痕，**不会**回落到调用方线程跑），绝不无限开线程。
 */
object PresenceDialPool:

  /** 拨号线程名前缀（读数面 / 断言 / 日志都用这一个常量）。 */
  val ThreadNamePrefix: String = "presence-dial-"

  /** 并发拨号体上限（见类注释：不是无界）。 */
  val MaxThreads: Int = 32

  /** 空闲线程存活期。 */
  val KeepAliveSec: Long = 30L

  private val seq = new AtomicLong(0L)

  private val lastThreadName = new AtomicReference[String]("")

  private val factory: ThreadFactory = (r: Runnable) =>
    val t = new Thread(r, s"$ThreadNamePrefix${seq.incrementAndGet()}")
    t.setDaemon(true)
    t

  private lazy val executor: ThreadPoolExecutor =
    new ThreadPoolExecutor(8, MaxThreads, KeepAliveSec, TimeUnit.SECONDS, new SynchronousQueue[Runnable](), factory)

  /** 最近一次真正执行拨号体的线程名（读数面；spec/夹具据此断言「没占 compute」）。 */
  def lastDialThreadName: String = lastThreadName.get()

  /** 是否是拨号专用池线程。 */
  def isDialThreadName(name: String): Boolean =
    name != null && name.startsWith(ThreadNamePrefix)

  /** 是否是**计算线程**（`io-compute-*`，含 `io-compute-blocker`）——拨号绝不允许落在这些上面。 */
  def isComputeThreadName(name: String): Boolean =
    name != null && name.startsWith("io-compute")

  /**
   * 线程占用断言（P2 的护栏）：拨号体**必须**跑在专用池线程上。
   *
   * 为什么是「抛」而不是「打一行警告」：这条规矩的唯一失效方式是「有人把调用点改回
   * `IO.blocking`」——那种退化在日志里是看不见的（性能退化没有错误），只有让它在**第一次
   * 拨号**就炸红（并被 `dialOne` 记成 `DialFailure(other)` + WARN 行），才拦得住复发。
   */
  def requireDialThread(where: String): Unit =
    val name = Thread.currentThread().getName
    lastThreadName.set(name)
    if !isDialThreadName(name) then throw new PresenceDialThreadViolation(where, name)

  /**
   * 把一段**阻塞**拨号体提交到专用池；返回值语义与「直接调用」完全一致
   * （`Either` 由调用方解释，异常原样传出）。
   *
   * 池满 ⇒ `RejectedExecutionException` 从 `IO` 里抛给调用方（`dialOne` 记失败），
   * **绝不**在调用方线程（compute）上兜底跑——兜底跑就是本批要消灭的形态。
   */
  def run[A](where: String)(body: => A): IO[A] =
    IO.defer {
      val supplier: java.util.function.Supplier[A] = () =>
        requireDialThread(where)
        body
      IO.fromCompletableFuture(IO(CompletableFuture.supplyAsync(supplier, executor)))
    }

  /** 读数面：`(活跃线程数, 池内线程数)`（报告/夹具取数用）。 */
  def stats: (Int, Int) = (executor.getActiveCount, executor.getPoolSize)

/** 拨号体跑错线程（占用 `io-compute-*`）——见 [[PresenceDialPool.requireDialThread]]。 */
final class PresenceDialThreadViolation(where: String, actualThread: String)
    extends IllegalStateException(
      s"presence dial body [$where] is running on '$actualThread' — 拨号/连接类阻塞调用必须跑在专用阻塞池 " +
        s"(${PresenceDialPool.ThreadNamePrefix}*)，绝不允许占用 io-compute-* 线程"
    )

/**
 * 拨号调度参数（presence-dial-overhaul 批的采集面 + 生产口径）。
 *
 * 🔴 生产值只有 [[PresenceDialSchedule.Production]] 一个（`GatewayMain` 不传这个参数）。
 * [[LegacySerial]] **只为实测对照存在**：改前/改后跑**同一个方法**（`connect`）、同一批
 * 端点、同一台机器，只翻这两个开关——否则比出来的不是调度收益，是两套实现的差异。
 */
final case class PresenceDialSchedule(
  /** P1：一轮内多候选**并发**拨号、首胜即放行（改前 = `false`，串行挨个拨）。 */
  concurrent: Boolean = true,
  /** P1 的并发度上限：一轮最多同时拨几个候选（其余留到下一拍，`truncated` 留痕）。 */
  maxConcurrentPerRound: Int = 4,
  /** P2：拨号体提交到 [[PresenceDialPool]]（改前 = `false`，即 `IO.blocking` 就地占 compute 线程）。 */
  dedicatedPool: Boolean = true
):

  /** 单轮时长上界：并发口径 = **单批内最大的单候选预算**（不是 Σ）；串行口径退化为 Σ。 */
  def roundUpperBoundMs(candidates: List[String], budget: DialBudget): Long =
    if !concurrent then budget.roundUpperBoundMs(candidates)
    else
      val batch = candidates.take(math.max(1, maxConcurrentPerRound))
      math.min(budget.roundBudgetMs, if batch.isEmpty then 0L else batch.map(budget.budgetMsFor).max)

object PresenceDialSchedule:

  /** 生产口径（唯一在用的值）。 */
  val Production: PresenceDialSchedule = PresenceDialSchedule()

  /** 改前口径（串行 + `IO.blocking`）——只给实测对照用，生产绝不装配。 */
  val LegacySerial: PresenceDialSchedule =
    PresenceDialSchedule(concurrent = false, maxConcurrentPerRound = 1, dedicatedPool = false)
