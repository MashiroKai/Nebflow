package nebflow.shared

/** A snapshot of active failures; retries never increase the task count. */
object ProviderFailureGroups:
  final case class Task(id: String, detail: String)
  final case class Group(provider: String, reason: String, taskIds: List[String]):
    def count: Int = taskIds.size

  private val attempt = "(?m)^\\s*\\[([^]\\r\\n]+)\\]\\s+([^/\\s]+)/[^: \\r\\n]+:\\s*([^\\r\\n]+)".r
  private val requestId = "(?i)(request[_ -]?id\\s*[:=]\\s*)[A-Za-z0-9_-]+".r

  def snapshot(tasks: List[Task]): List[Group] =
    val rows = tasks.flatMap { task =>
      val parsed = attempt.findAllMatchIn(task.detail).map { m =>
        val detail = requestId.replaceAllIn(ProviderFailureDetail.sanitize(m.group(3)), "$1[request]")
        (m.group(2), s"${m.group(1)}: $detail", task.id)
      }.toList
      if parsed.nonEmpty then parsed
      else List(("unknown", ProviderFailureDetail.sanitize(task.detail), task.id))
    }
    rows.groupBy { case (provider, reason, _) => (provider, reason) }
      .toList.map { case ((provider, reason), items) =>
        Group(provider, reason, items.map(_._3).distinct.sorted)
      }.sortBy(g => (g.provider, g.reason))
