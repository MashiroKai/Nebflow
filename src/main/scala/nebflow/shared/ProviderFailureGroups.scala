package nebflow.shared

/** A snapshot of active failures; retries never increase the task count. */
object ProviderFailureGroups:
  final case class Task(id: String, detail: String)

  /** 失败族（FallbackExhaustedError 每行前置的 `[family]` 标签，2026-11-21 作者裁定）：
    * 面板文案必须按族呈现——`format` 是「请求/响应形状不合规」（我们的请求与上游契约
    * 不符），不是「服务商不可用」；后者只留给可用性一族（server/network/conn-reset/
    * timeout/overload/provider）。未知标签一律归 `unknown`，沿用可用性文案，不臆测。 */
  final case class Group(provider: String, family: String, reason: String, taskIds: List[String]):
    def count: Int = taskIds.size
    /** 呈现分组：availability（服务商不可用）/ shape（请求/响应格式不合规）/ config（鉴权·配额·模型）。 */
    def presentation: String = family match
      case "format" | "image-capability" => "shape"
      case "auth" | "model" | "quota" => "config"
      case _ => "availability"

  private val KnownFamilies: Set[String] =
    Set("quota", "overload", "auth", "model", "format", "timeout", "conn-reset",
      "network", "server", "empty", "provider", "image-capability")

  private val attempt = "(?m)^\\s*\\[([^]\\r\\n]+)\\]\\s+([^/\\s]+)/[^: \\r\\n]+:\\s*([^\\r\\n]+)".r
  private val SummaryFamilies = "(?i)families:\\s*([a-z0-9,\\- ]+)".r
  private val requestId = "(?i)(request[_ -]?id\\s*[:=]\\s*)[A-Za-z0-9_-]+".r

  def snapshot(tasks: List[Task]): List[Group] =
    val rows = tasks.flatMap { task =>
      val parsed = attempt
        .findAllMatchIn(task.detail)
        .map { m =>
          val family = if KnownFamilies.contains(m.group(1)) then m.group(1) else "unknown"
          val detail = requestId.replaceAllIn(ProviderFailureDetail.sanitize(m.group(3)), "$1[request]")
          (m.group(2), family, s"${m.group(1)}: $detail", task.id)
        }
        .toList
      if parsed.nonEmpty then parsed
      else
        // 无逐行 `[family] provider/model:` 明细时（error-freeze 的 compact 详情只带
        // 「All providers failed (N attempt(s); families: X)」摘要），族仍可从摘要读出——
        // 否则面板一律显示 unknown，族区分文案失效（2026-11-21 复核发现）。
        val detail = ProviderFailureDetail.sanitize(task.detail)
        val family = SummaryFamilies
          .findFirstMatchIn(detail)
          .flatMap(m => m.group(1).split("[,\\s]+").find(KnownFamilies.contains))
          .getOrElse("unknown")
        List(("unknown", family, detail, task.id))
    }
    rows
      .groupBy { case (provider, family, reason, _) => (provider, family, reason) }
      .toList
      .map { case ((provider, family, reason), items) =>
        Group(provider, family, reason, items.map(_._4).distinct.sorted)
      }
      .sortBy(g => (g.provider, g.family, g.reason))
  end snapshot
end ProviderFailureGroups
