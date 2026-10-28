package nebflow.llm.imagegen

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * 生图 job 台账（imagegen 双层批 2026-10-24，腿①非阻塞生命周期的状态面）。
 *
 * 非阻塞语义：`generate_image` 工具调用**立即返回 job 句柄**（回合不阻塞），
 * 生成在后台 fiber 跑；完成经 WS `imagegenUpdate` 帧推送回填前端占位卡片
 * （前端原位替换为真实图片）。本台账是 job 状态的权威读面（REST 查询腿 +
 * 完成回调写点），内存态 + PNG 已落盘（`<dataRoot>/imagegen/`），重启后台账
 * 清空、磁盘文件仍在（nf-file 票据展示链不依赖本台账）。
 */
object ImageGenJobs:

  enum Status(val wire: String):
    case Running extends Status("running")
    case Completed extends Status("completed")
    case Failed extends Status("failed")

  final case class Job(
    jobId: String,
    sessionId: Option[String],
    prompt: String,
    status: Status,
    /** completed 时的落盘 PNG 绝对路径。 */
    path: Option[String] = None,
    /** failed 时的可行动错误文案（凭据零出现）。 */
    error: Option[String] = None,
    createdAtMs: Long,
    updatedAtMs: Long
  )

  /** 内存台账上限（LRU 驱逐最旧——实验面的有界性纪律）。 */
  val MaxRetained: Int = 100

  private val jobs = new ConcurrentHashMap[String, Job]()

  private def now: Long = System.currentTimeMillis()

  def register(jobId: String, sessionId: Option[String], prompt: String): Job =
    val job = Job(jobId, sessionId, prompt, Status.Running, createdAtMs = now, updatedAtMs = now)
    jobs.put(jobId, job)
    evictIfNeeded()
    job

  def complete(jobId: String, path: String): Option[Job] =
    update(jobId)(_.copy(status = Status.Completed, path = Some(path)))

  def fail(jobId: String, error: String): Option[Job] =
    update(jobId)(_.copy(status = Status.Failed, error = Some(error)))

  private def update(jobId: String)(f: Job => Job): Option[Job] =
    Option(jobs.get(jobId)).map { existing =>
      val next = f(existing).copy(updatedAtMs = now)
      jobs.put(jobId, next)
      next
    }

  def get(jobId: String): Option[Job] = Option(jobs.get(jobId))

  def snapshot: List[Job] = jobs.values.asScala.toList.sortBy(_.createdAtMs)(Ordering[Long].reverse)

  private def evictIfNeeded(): Unit =
    if jobs.size() > MaxRetained then
      val excess = jobs.size() - MaxRetained
      jobs.values.asScala.toList.sortBy(_.createdAtMs).take(excess).foreach(j => jobs.remove(j.jobId, j))
end ImageGenJobs
