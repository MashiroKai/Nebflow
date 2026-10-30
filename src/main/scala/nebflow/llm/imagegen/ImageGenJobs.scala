package nebflow.llm.imagegen

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * 生图 job 台账（imagegen 双层批 2026-10-24；交互修正批 2026-10-30 语义刷新）。
 *
 * 阻塞语义：`generate_image` 工具调用**挂起至生成完成**（回合不并发推进），
 * 台账随之退为簿记/读面——REST `/api/imagegen` 查询腿与 sidebar 状态投影仍消费
 * 它（register → complete/fail 翻转照旧），但完成回填**不再走 WS imagegenUpdate
 * 帧**（该腿已整腿退役：结果载荷走 toolEnd 的 Pop 面原位直出）。内存态 +
 * 文件已落盘（`<dataRoot>/imagegen/`），重启后台账清空、磁盘文件仍在（nf-file
 * 票据展示链不依赖本台账）。
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

  def complete(jobId: String, path: String): Option[Job] = update(jobId)(_.copy(status = Status.Completed, path = Some(path)))

  def fail(jobId: String, error: String): Option[Job] = update(jobId)(_.copy(status = Status.Failed, error = Some(error)))

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
