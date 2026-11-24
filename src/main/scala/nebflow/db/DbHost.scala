package nebflow.db

import cats.effect.Fiber
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.slf4j.LoggerFactory

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主侧共享访问点（接线第一阶，设计 §3.9「一个数据根一个句柄」的进程内落地）：
 * 按数据根 memoize 的 [[DbHandle]] 注册表。
 *
 * 为什么必须共享：[[InstanceLock]]（`INSTANCE.lock` tryLock）⇒ 同一数据根在同一
 * 进程内**恰一个句柄**——六个宿主（DispatchNotify/PendingAskLedger/ScheduledTaskStore/
 * HistoryArchiver/SessionStore/FlowMapEventLog）散布在 core/agent/llm 三包，各自
 * `Db.open` 会互相撞锁（同 JVM 双通道同文件 ⇒ OverlappingFileLockException）。
 *
 * 接线纪律（第一阶总原则①）：**best-effort**——open 失败（他进程持锁/根不可写）⇒
 * WARN + None，调用方回落现状行为，绝不阻断宿主主链。失败**不 memoize**（下次
 * 调用重试；宿主多在 boot 期构造，重试成本可忽略）。
 *
 * 本文件属 db 包但**不含任何数据面逻辑**（纯注册表）；卫生判据（V7/R20：
 * db 树 `import nebflow.` == 0）保持——仅依赖 cats-effect unsafe 运行时与 JDK。
 * dataRoot 一律由调用方传入（宿主侧用 `PathUtil.dataRoot.toNIO`）。
 */
object DbHost:

  private val log = LoggerFactory.getLogger("nebflow.db")

  private val handles = new ConcurrentHashMap[Path, DbHandle]()

  /** 遗忘刀 sweep fiber 面（D-9 接线：开根 arm / release 取消；见 [[RetentionSweeper]]）。 */
  private val sweepers = new ConcurrentHashMap[Path, Fiber[IO, Throwable, Unit]]()

  @volatile private var shutdownHookInstalled: Boolean = false

  /**
   * best-effort 打开（或复用）数据根的共享句柄。成功 ⇒ memoize + 挂遗忘刀 sweep
   * （避让门内嵌：sweep 先等 recoverPendingD8——门不开永不跑）。
   * 失败 ⇒ WARN + None（不 memoize，下次重试）。
   */
  def openBestEffort(dataRoot: Path): Option[DbHandle] =
    try
      val existing = handles.get(dataRoot)
      if existing != null then Some(existing)
      else
        // open 是低频 boot 期动作（每根一次）——全局同步足够，无需细粒度锁。
        val opened = synchronized(
          Option(handles.get(dataRoot)).getOrElse {
            val h = Db.open(DbConfig(dataRoot = dataRoot)).unsafeRunSync()
            handles.put(dataRoot, h)
            sweepers.put(dataRoot, RetentionSweeper.arm(h))
            installShutdownHook()
            log.info("db handle opened for data root {} (generation {})", dataRoot, h.instanceLockGeneration)
            h
          }
        )
        Some(opened)
    catch
      case e: Throwable =>
        log.warn(
          "db handle open failed for data root {} — host wiring degrades to legacy " +
            "in-memory/file behavior (best-effort): {}",
          dataRoot,
          e.toString
        )
        None

  /** 该根是否已持有句柄（测试/诊断面）。 */
  def isOpen(dataRoot: Path): Boolean = handles.containsKey(dataRoot)

  /**
   * 只查不开：已 memoize 的句柄（None = 本进程尚未有人开过该根）。
   * 供**无实例态的审计面**（如 FlowMapEventLog——object、装配点不在解禁面）
   * 接线用：写穿影子仅在生产宿主（SessionStore/PendingAskLedger/DispatchNotify）
   * boot 期已注册句柄后激活 ⇒ 测试 JVM 永不因静态 lookup 打开真实数据根。
   */
  def peek(dataRoot: Path): Option[DbHandle] = Option(handles.get(dataRoot))

  /** 关闭并注销指定根的句柄（测试面；生产由 shutdown hook 收口）。sweep fiber 随手取消。 */
  def release(dataRoot: Path): Unit = synchronized {
    val f = sweepers.get(dataRoot)
    if f != null then
      f.cancel.unsafeRunSync()
      sweepers.remove(dataRoot, f)
    val h = handles.get(dataRoot)
    if h != null then
      // E2E 红测注入点（E2E_INJECT_DB_CLOSE_FAILURE=1）：在 close 点抛错——注销位于
      // close 成功之后，故句柄保持登记（isOpen 仍真），退出清点如实非零、runner FAIL。
      if sys.env.get("E2E_INJECT_DB_CLOSE_FAILURE").contains("1") then
        throw new java.io.IOException("injected db close failure (E2E red test)")
      h.close()
      handles.remove(dataRoot, h)
  }

  /** 关闭全部（JVM shutdown hook 单点；幂等）。 */
  def closeAll(): Unit =
    val ks = java.util.List.copyOf(handles.keySet)
    ks.forEach(root =>
      try release(root)
      catch case scala.util.control.NonFatal(e) => log.error(s"DB shutdown failed for $root", e)
    )

  private def installShutdownHook(): Unit =
    synchronized:
      if !shutdownHookInstalled then
        shutdownHookInstalled = true
        Runtime.getRuntime.addShutdownHook(new Thread(() => closeAll(), "nebflow-db-host-close"))

end DbHost
