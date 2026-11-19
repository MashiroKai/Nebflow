package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.PathUtil

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

/**
 * 密钥 CLI（密钥能力三件套批 A 件，作者令 2026-11-01：`stand secrets list` =
 * 键名 + 用途 + 写入时间的元数据清单，**任何形态都不显示值**；bare
 * `stand secrets` 输出用法提示）。
 *
 * 可查询批（作者令 2026-11-18 21:31「secret 要加上 cli 查询功能，secret 要加上
 * 说明和关键词 tag，方便被查询使用」）：the inventory grows the two query axes
 * and the per-secret drill-down —
 *
 *   - `stand secrets list [--keyword <kw>] [--tag <a,b>]` — the SAME metadata
 *     table, narrowed by the shared [[nebflow.core.secrets.SecretsQuery]]
 *     terms (`keyword` = case-insensitive substring over name / variable /
 *     description / tags / referrers; `--tag` = ALL named tags must be
 *     carried). The CLI, `GET /api/secrets?q=&tag=` and the panel search box
 *     therefore answer from one predicate — there is no second matcher.
 *   - `stand secrets show <name>` — one secret's full METADATA record
 *     (description, tags, variable name, updated, path, consumers).
 *   - `stand secrets meta <name> [--description <text>] [--tag <a,b>]
 *     [--variable <NAME>]` — the CLI write entry for the non-value catalogue
 *     fields (the panel's PATCH and the AskUser sink are the other two; all
 *     three land on `SecretsStore.setMetadata`).
 *
 * 落位：stand CLI 现有命令体系（`CommandRegistry` 注册 + `CliRouter` 分发），
 * 与 `token` / `sessions`（phase 3b console-credential 面）同族——密钥域的
 * CLI 读面。
 *
 * 🔴 零值纪律（`SecretsStore` 红线原样继承）：本命令只走
 * `SecretsStore.list()` / `show()` / `setMetadata()`——元数据面，结构上不存在
 * 值字段可读；输出文本、错误、`--json` 载荷都不携带任何存储值。`list`/`show`
 * 只 stat 不 read：没有一行代码打开值文件的内容。
 *
 * 离线归类（`CliRouter.OfflineSubcommands`）：三个子命令都是**本机元数据面**
 * （与 `health`/`logs` 同款判据——网关不在时恰恰更该可用），直接实例化本
 * 数据根的 `SecretsStore` 读盘/写侧车；与网关 REST 的 `GET /api/secrets` 是同一
 * `list()` 单点，两端读数一致。`list`/`show` 只读：刻意不刷新目录（读不写，
 * store 契约），sidecar/catalog 不动；`meta` 只写侧车（值面零触碰）。
 */
object SecretsCommand extends CliCommand:
  def name = "secrets"
  def description = "Secrets store: name, description, tags, updated — values are NEVER displayed"
  def subcommands = List(SecretsUsage, SecretsList, SecretsShow, SecretsMeta)
  def examples = List(
    "stand secrets",
    "stand secrets list",
    "stand secrets list --tag deploy,ci --json",
    "stand secrets list --keyword aliyun",
    "stand secrets show aliyun-oss",
    "stand secrets meta aliyun-oss --description \"OSS access key\" --tag cloud,oss"
  )
  // Bare `stand secrets` = usage (author ruling: 无参输出用法提示 — it must
  // NOT silently run the list; the usage face is the default subcommand).
  override def defaultSubcommand = Some("usage")

/** `secrets usage` — the bare-command face: what this command can do, zero reads. */
private[cli] object SecretsUsage extends CliSubcommand:
  def name = "usage"
  def description = "Show how to inspect the secrets store (bare `stand secrets` lands here)"
  def params = Nil

  def run(ctx: CliContext): IO[CliResult] =
    val storePath = s"${PathUtil.dataRoot}/secrets"
    if ctx.json then
      IO.pure(
        CliResult.Json(
          Json.obj(
            "usage" -> Json.arr(
              "stand secrets                        — show this usage".asJson,
              "stand secrets list                   — list secrets (name, tags, updated, description)".asJson,
              "stand secrets list --keyword <kw>     — substring over name/description/tags/consumers".asJson,
              "stand secrets list --tag <a,b>        — only secrets carrying ALL named tags".asJson,
              "stand secrets show <name>            — one secret's metadata record".asJson,
              "stand secrets meta <name> [--description <t>] [--tag <a,b>] [--variable <NAME>]".asJson
            ),
            "store" -> storePath.asJson,
            "valueDiscipline" -> "values are never displayed by this command (owner-only files)".asJson
          )
        )
      )
    else
      IO.pure(
        CliResult.Text(
          List(
            "secrets — unified credential store (metadata face; values are NEVER displayed)",
            "",
            "Usage:",
            "  stand secrets                          Show this usage",
            "  stand secrets list                     List secrets: name, tags, updated, description",
            "  stand secrets list --keyword <kw>      Narrow by a substring over name/description/tags/consumers",
            "  stand secrets list --tag <a,b>         Narrow to secrets carrying ALL of the named tags",
            "  stand secrets list --json              Machine-readable inventory",
            "  stand secrets show <name>              One secret's metadata record (path + consumers included)",
            "  stand secrets show <name> --json       Same, machine-readable",
            "  stand secrets meta <name>              Edit a secret's metadata (never its value)",
            "      --description <text>               What this credential is for",
            "      --tag <a,b>                        Keyword tags (comma separated; replaces the whole list)",
            "      --variable <NAME>                  Variable-name override (shell identifier)",
            "",
            s"The store: $storePath — one owner-only (0600) file per secret.",
            "Create: settings · secrets panel, or an AskUserQuestion secret question.",
            "Consume: \"$(cat <location>)\" inside a sub-shell, or $VARIABLE when allowlisted (stand.json secrets.inject).",
            "This command never prints a value."
          )
        )
      )

/** Shared read/write plumbing for the metadata subcommands. */
private[cli] object SecretsCli:
  import nebflow.core.secrets.{SecretsCatalog, SecretsStore}

  /** THIS data root's store — the same construction the gateway's per-request
    *  face uses (`SecretsRoutes.secretsStore`), so the CLI inventory and the
    *  panel list can never disagree about what exists. */
  def store: SecretsStore = new SecretsStore(PathUtil.dataRoot / "secrets")

  /** Failure → one-line diagnostic (name/code family only — never a value). */
  def describe(err: SecretsStore.Failure): String =
    import SecretsStore.Failure
    err match
      case Failure.Io(reason)       => s"io: $reason"
      case Failure.SecretMode(n, r) => s"secret_mode: $n ($r)"
      case Failure.UnknownSecret(n) => s"unknown_secret: $n"
      case other                    => s"${other.getClass.getSimpleName.toLowerCase}"

  /** Local-time stamp, same face as the rendered catalogue (yyyy-MM-dd HH:mm). */
  def stamp(millis: Long): String =
    if millis <= 0 then "-"
    else
      DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))

  /** One-line text cell: descriptions are user-authored free text. */
  def cell(s: String): String =
    val flat = s.replaceAll("[\\r\\n]+", " ").trim
    if flat.isEmpty then "-" else flat

  /** Tags as one text cell (`#a #b`), `-` when none. */
  def tagsCell(tags: List[String]): String = if tags.isEmpty then "-" else tags.map(t => s"#$t").mkString(" ")

  /** The consumption path a user would query — a PATH, never a value. */
  def locationOf(name: String): String =
    SecretsCatalog.renderedLocation(PathUtil.dataRoot, store.secretsDir, name)

  /** The JSON shape of one entry — metadata only, no value key exists. */
  def entryJson(e: SecretsStore.Entry): Json =
    Json.obj(
      "name" -> e.name.asJson,
      "variableName" -> e.variableName.asJson,
      "description" -> e.description.asJson,
      "tags" -> e.tags.asJson,
      "size" -> e.size.asJson,
      "mtime" -> e.mtime.asJson,
      "updated" -> stamp(e.mtime).asJson,
      "mode" -> e.mode.asJson,
      "refs" -> e.refs.asJson,
      "path" -> locationOf(e.name).asJson,
      "stored" -> e.stored.asJson
    )

/** `secrets list` — the queryable metadata inventory. */
private[cli] object SecretsList extends CliSubcommand:
  import nebflow.core.secrets.{SecretsQuery, SecretsStore}

  def name = "list"
  def description = "List secrets: name, tags, updated, description — metadata only, never a value"
  def params = List(
    CliParam(
      name = "keyword",
      short = Some('k'),
      description = "Only secrets whose name/variable/description/tags/consumers contain this text"
    ),
    CliParam(
      name = "tag",
      short = Some('t'),
      description = "Only secrets carrying ALL of these tags (comma separated, e.g. cloud,deploy)"
    )
  )

  def run(ctx: CliContext): IO[CliResult] =
    val terms = SecretsQuery.parse(ctx.args.get("keyword"), ctx.args.get("tag"))
    SecretsCli.store.list().map {
      case Left(err) =>
        CliResult.error(s"secrets store unreadable: ${SecretsCli.describe(err)}")
      case Right(all) =>
        val entries = SecretsQuery.filter(all, terms)
        if ctx.json then CliResult.Json(inventoryJson(all.size, entries, terms))
        else CliResult.Text(inventoryText(entries, terms))
    }

  private def inventoryJson(total: Int, entries: List[SecretsStore.Entry], terms: SecretsQuery.Terms): Json =
    Json.obj(
      "count" -> entries.size.asJson,
      "total" -> total.asJson,
      "store" -> (PathUtil.dataRoot / "secrets").toString.asJson,
      "query" -> Json.obj(
        "keyword" -> terms.keyword.asJson,
        "tags" -> terms.tags.asJson
      ),
      "secrets" -> entries.map(SecretsCli.entryJson).asJson
    )

  private def inventoryText(entries: List[SecretsStore.Entry], terms: SecretsQuery.Terms): List[String] =
    val storePath = s"${PathUtil.dataRoot}/secrets"
    if entries.isEmpty then
      if terms.isEmpty then
        List(
          "No secrets stored.",
          s"The store $storePath is empty (archive/rollback backups are not listed).",
          "Create one in the settings · secrets panel, or via an AskUserQuestion secret question."
        )
      else
        val q =
          terms.keyword.fold("")(k => s" keyword=\"$k\"") +
            (if terms.tags.nonEmpty then s" tags=${terms.tags.mkString(",")}" else "")
        List(
          "No secrets match this query.",
          s"Query:$q",
          "List everything with `stand secrets list` (no filters)."
        )
    else
      val cells = entries.map(e => (e, SecretsCli.tagsCell(e.tags), SecretsCli.stamp(e.mtime), SecretsCli.cell(e.description)))
      val nameW = cells.foldLeft(4)((w, c) => math.max(w, c._1.name.length))
      val tagW = cells.foldLeft(4)((w, c) => math.max(w, c._2.length))
      val header =
        s"${"NAME".padTo(nameW, ' ')}  ${"TAGS".padTo(tagW, ' ')}  ${"UPDATED".padTo(16, ' ')}  DESCRIPTION"
      val body = cells.flatMap { case (e, tags, updated, desc) =>
        List(
          s"${e.name.padTo(nameW, ' ')}  ${tags.padTo(tagW, ' ')}  ${updated.padTo(16, ' ')}  $desc",
          s"  path:    ${SecretsCli.locationOf(e.name)}",
          s"  used by: ${if e.refs.isEmpty then "-" else e.refs.mkString(", ")}"
        )
      }
      val footer = List(
        "",
        s"${entries.size} secret(s) · store: $storePath",
        "Values are never displayed — one owner-only (0600) file per name; consume via \"$(cat <path>)\" or an allowlisted $VARIABLE."
      )
      header :: body ::: footer

  end inventoryText

end SecretsList

/** `secrets show <name>` — one secret's metadata record. */
private[cli] object SecretsShow extends CliSubcommand:
  def name = "show"
  def description = "Show one secret's metadata (description, tags, updated, path, consumers) — never a value"
  def params = List(
    CliParam(name = "name", description = "Secret name", required = true)
  )

  def run(ctx: CliContext): IO[CliResult] =
    val name = ctx.args.get("name").orElse(ctx.positionalArgs.headOption).getOrElse("")
    if name.isEmpty then IO.pure(CliResult.error("Missing required params: name"))
    else
      SecretsCli.store.show(name).map {
        case Left(err) => CliResult.error(s"secrets: ${SecretsCli.describe(err)}")
        case Right(e) =>
          if ctx.json then CliResult.Json(SecretsCli.entryJson(e))
          else
            CliResult.Text(
              List(
                s"name:         ${e.name}",
                s"description:  ${SecretsCli.cell(e.description)}",
                s"tags:         ${SecretsCli.tagsCell(e.tags)}",
                s"variable:     ${e.variableName}",
                s"updated:      ${SecretsCli.stamp(e.mtime)}",
                s"path:         ${SecretsCli.locationOf(e.name)}",
                s"used by:      ${if e.refs.isEmpty then "-" else e.refs.mkString(", ")}",
                s"stored:       ${if e.stored then "yes" else "metadata only (bytes live in a managed namespace)"}",
                "",
                "The value is never displayed by this command."
              )
            )
      }

/** `secrets meta <name>` — the CLI write entry for the non-value fields. */
private[cli] object SecretsMeta extends CliSubcommand:
  import nebflow.core.secrets.SecretsCatalog

  def name = "meta"
  def description = "Edit a secret's description/tags/variable name (metadata only — never its value)"
  def params = List(
    CliParam(name = "name", description = "Secret name", required = true),
    CliParam(name = "description", description = "What this credential is for (replaces the description)"),
    CliParam(name = "tag", description = "Keyword tags, comma separated (replaces the whole tag list)"),
    CliParam(name = "variable", description = "Variable-name override; empty clears it")
  )

  def run(ctx: CliContext): IO[CliResult] =
    val name = ctx.args.get("name").orElse(ctx.positionalArgs.headOption).getOrElse("")
    if name.isEmpty then IO.pure(CliResult.error("Missing required params: name"))
    else
      val description = ctx.args.get("description")
      val tags = ctx.args.get("tag").map(SecretsCatalog.parseTags).getOrElse(Nil)
      val variable = ctx.args.get("variable").filter(_.nonEmpty)
      SecretsCli.store.setMetadata(name, variable, description, tags).map {
        case Left(err) => CliResult.error(s"secrets: ${SecretsCli.describe(err)}")
        case Right(_) =>
          if ctx.json then
            CliResult.Json(
              Json.obj(
                "ok" -> true.asJson,
                "name" -> name.asJson,
                "description" -> description.getOrElse("").asJson,
                "tags" -> tags.asJson,
                "path" -> SecretsCli.locationOf(name).asJson
              )
            )
          else
            CliResult.Text(
              List(
                s"Updated metadata for ${name} (description: ${SecretsCli.cell(description.getOrElse(""))}, tags: ${SecretsCli.tagsCell(tags)}).",
                "The stored value is untouched."
              )
            )
      }

end SecretsMeta
