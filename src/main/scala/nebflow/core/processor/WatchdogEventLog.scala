package nebflow.core.processor

import cats.effect.IO
import io.circe.Json
import nebflow.core.{NebflowLogger, PathUtil}

import java.nio.file.Path
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * R8 方向①（看门狗自身监测，2026-09-10 设计 §2.3）：卡死看门狗**自身**每次开火的
 * 结构化事件流。
 *
 * 缺口（设计 §1.3）：一次开火在系统里留下的是 3–9 行自由文本 WARN（`nebflow.log`，
 * logback 50MB×7 天轮转）+ 一条不落盘的 WS 帧——**不可聚合、不可计数、不可 join**。
 * 查询当天 L3 开火次数要翻 14 万行日志 + 手写正则（设计 §2.1 的反面教材）。
 *
 * 本对象只做一件事：把「一次开火」写成一行的 JSON。
 *
 *   - 文件：`<dataRoot>/logs/watchdog/<YYYY-MM-DD>_events.jsonl`
 *     （`PathUtil.dataRoot` 语义 = CLI `--home` / `NEBFLOW_HOME` / 默认 `~/.nebflow`；
 *      隔离实例的看门狗事件留在自己的 home 内，不泄漏进生产数据根）；
 *     **全局单文件**，非 per-project——watcher 是全局单例，且 root/Team 会话
 *     无项目归属（设计 §2.8-1 落位推荐）。
 *   - append-only、零迁移、grep 友好（与 `FlowMapEventLog.append` 同款纪律）。
 *   - **fire-per-fire 无条件写**：不做单发去重——单发语义会吃掉「同一段被 L1/L2/L3
 *     连打三拍」这一关键事实（设计 §2.6）；去重留在查询期做。
 *   - 写入 **best-effort**：任何异常只 WARN，绝不拖垮扫描/恢复链（审计面不许成为
 *     新的故障点）。
 *
 * 红线（设计 §2.3 末 + 任务书）：
 *   - **不得**写入进程 CPU / `processActivityMs` 或任何 `assess` 判据未使用的信号
 *     （防判据漂移；R6-4 红线）。
 *   - 事件内**不嵌** progress 快照（`BashTool` 输出行数等，设计 §2.2-C）——v2 再议。
 *
 * 测试缝：`setLogDirForTest` / `resetLogDirForTest`，与 `LlmLogWriter.setLogDirForTest`
 * 同款（spec / 隔离实例断言本文件而不触碰真实 `~/.nebflow/logs/watchdog`）。
 */
object WatchdogEventLog:

  private val logger = NebflowLogger.forName("nebflow.core.processor.stuck.watchdog")

  /** 事件类型（本文件专用词；设计 §2.3 的 `type` 取值域）。 */
  val StuckFireType: String = "stuck-fire"
  val L3IneffectiveType: String = "l3-ineffective"

  private val logDirOverride = AtomicReference[Option[Path]](None)

  private[nebflow] def setLogDirForTest(p: Path): Unit = logDirOverride.set(Some(p))
  private[nebflow] def resetLogDirForTest(): Unit = logDirOverride.set(None)

  /** Package-visible probe：spec 钉住「目录跟随 dataRoot / 注入优先」契约。 */
  private[nebflow] def logDirForTest: Path = logDir

  private def logDir: Path =
    logDirOverride.get().getOrElse((PathUtil.dataRoot / "logs" / "watchdog").toNIO)

  private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  private def fileFor(now: Long): os.Path =
    os.Path(logDir.toString) /
      s"${dayFormat.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))}_events.jsonl"

  /** 追加一行事件（调用方负责给全设计 §2.3 的字段，含 `ts`）。永不抛：写失败只 WARN。 */
  def append(event: Json): IO[Unit] =
    IO.blocking {
      // 单行 append（与 FlowMapEventLog 同款；写入方 = 扫描循环单线程顺序调用）。
      os.write.append(fileFor(System.currentTimeMillis()), event.noSpaces + "\n", createFolders = true)
    }.void.handleErrorWith(e =>
      logger.warn(s"watchdog event append failed (audit-only, scan unaffected): ${Option(e.getMessage).getOrElse(e.toString)}"))

end WatchdogEventLog
