package nebflow.core.secrets

/**
 * The ONE matcher behind every secrets query face（可查询批，作者令
 * 2026-11-18 21:31「secret 要加上说明和关键词 tag，方便被查询使用」）:
 * `stand secrets list --keyword … --tag …`, `GET /api/secrets?q=…&tag=…`
 * and the panel's search box all answer with the same predicate over the
 * same three non-value fields. A second matcher is exactly how the CLI and
 * the panel would drift apart, so there is deliberately none.
 *
 * 🔴 The matcher reads only metadata: name, variable name, description,
 * tags and the referrer ids. It never opens a secret file — its input is
 * [[SecretsStore.Entry]], which structurally has no value field.
 *
 * Terms（判据，写死在文档里，三个面共享）:
 *   - `keyword` — ONE free-text term, matched case-insensitively as a
 *     substring against name / variableName / description / tags / refs.
 *     Empty or blank ⇒ no keyword constraint.
 *   - `tags` — zero or more tag names; an entry must carry ALL of them
 *     (intersection, not union): tags are a narrowing facet, and a union
 *     would make `--tag a --tag b` identical to two separate calls.
 *     Tag comparison is exact after [[SecretsCatalog.normalizeTag]] folding,
 *     so `--tag Deploy` and `--tag deploy` are the same query.
 */
object SecretsQuery:

  /** A parsed query: an optional keyword plus zero-or-more required tags. */
  final case class Terms(keyword: Option[String], tags: List[String]):
    def isEmpty: Boolean = keyword.isEmpty && tags.isEmpty

  val Empty: Terms = Terms(None, Nil)

  /**
   * Build terms from raw user input. `tags` arrives as one raw expression
   * (`"cloud,deploy"`) — the CLI flag and the panel's search box both speak
   * the comma form, so the parsing happens here rather than at each entry.
   */
  def parse(keyword: Option[String], tags: Option[String]): Terms =
    Terms(
      keyword.map(_.trim).filter(_.nonEmpty),
      tags.map(SecretsCatalog.parseTags).getOrElse(Nil)
    )

  /**
   * Same, from an already-split tag list (the REST face's query params). Each
   * element is folded through the comma form as well, so `?tag=a,b` and
   * `?tag=a&tag=b` are the same query — the CLI's `--tag a,b` speaks the
   * former, a machine caller may speak either, and neither may mean something
   * different from the panel's chips.
   */
  def of(keyword: Option[String], tags: Seq[String]): Terms =
    Terms(
      keyword.map(_.trim).filter(_.nonEmpty),
      SecretsCatalog.normalizeTags(tags.flatMap(t => SecretsCatalog.parseTags(t)))
    )

  /**
   * The haystack of one entry: exactly the metadata fields a user may search
   * by, lowercased once. No value ever enters it — there is no value to
   * enter ([[SecretsStore.Entry]] has no such field).
   */
  private def haystack(e: SecretsStore.Entry): String =
    (e.name :: e.variableName :: e.description :: e.tags ::: e.refs).mkString(" ").toLowerCase

  /** Does one entry satisfy the terms? */
  def matches(e: SecretsStore.Entry, terms: Terms): Boolean =
    val keywordOk = terms.keyword match
      case None       => true
      case Some(kw)   => haystack(e).contains(kw.toLowerCase)
    val tagsOk = terms.tags.forall(want => e.tags.exists(t => SecretsCatalog.normalizeTag(t) == want))
    keywordOk && tagsOk

  /** Filter a listing, order preserved. */
  def filter(entries: List[SecretsStore.Entry], terms: Terms): List[SecretsStore.Entry] =
    if terms.isEmpty then entries else entries.filter(e => matches(e, terms))

end SecretsQuery
