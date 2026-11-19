package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.Ref

/**
 * 会话工具活动时钟（2026-10-29 例程自检降频批）：记录每个会话最近一次工具
 * 调用成功的墙钟——例程空转判据的数据源。由 AgentSessionExecution 的工具成功
 * 落点单点写入；ScheduledTaskService 在例行任务触发时查询窗口
 * [lastFiredAt, now) 内该会话有无活动。
 *
 * 判空转口径（三态，诚实启发式，宁松勿冤）：
 *   - Some(true)：有记录且早于 sinceMs ⇒ 空转（整个周期窗口内该会话零工具动作）；
 *   - Some(false)：窗口内有工具动作 ⇒ 有生命（例程自检清零）；
 *   - None：本进程启动后该会话从无工具调用 ⇒ 无证据不定罪（重启冷启动的第一个
 *     周期不冤枉；调用方保持原 streak 不增不清）。
 *
 * 仅内存态：重启即清零（重启错过的周期由错过补偿面负责，不归本面）。
 */
object SessionToolActivity:

  private val lastActivity: Ref[IO, Map[String, Long]] = Ref.unsafe[IO, Map[String, Long]](Map.empty)

  /** 工具成功落点调用（每次工具调用 OK 记一次；sessionId 缺省/空则忽略）。 */
  def record(sessionId: Option[String]): IO[Unit] =
    sessionId match
      case Some(sid) if sid.trim.nonEmpty =>
        recordAt(sid, System.currentTimeMillis())
      case _ => IO.unit

  /** 定时刻记录（record 的时间可注入形态——spec 钉窗口判据用）。 */
  def recordAt(sessionId: String, tsMs: Long): IO[Unit] =
    lastActivity.update(_.updated(sessionId, tsMs))

  /** 会话自 sinceMs 以来是否空转（三态判据，见类注）：Some(true)=空转 /
    * Some(false)=有活动 / None=无证据（不定罪）。 */
  def idleSince(sessionId: String, sinceMs: Long): IO[Option[Boolean]] =
    lastActivity.get.map {
      _.get(sessionId) match
        case Some(ts) => Some(ts < sinceMs)
        case None => None // 无证据 ≠ 空转
    }

  /** spec 专用：清零（模拟新进程）。 */
  def resetForSpec(): IO[Unit] = lastActivity.set(Map.empty)

end SessionToolActivity
