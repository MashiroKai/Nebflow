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
 * 落位：stand CLI 现有命令体系（`CommandRegistry` 注册 + `CliRouter` 分发），
 * 与 `token` / `sessions`（phase 3b console-credential 面）同族——密钥域的
 * CLI 读面。
 *
 * 🔴 零值纪律（`SecretsStore` 红线原样继承）：本命令只走
 * `SecretsStore.list()`——元数据唯一读面，结构上不存在值字段可读；输出文本、
 * 错误、`--json` 载荷都不携带任何存储值。
 *
 * 离线归类（`CliRouter.OfflineSubcommands`）：本命令是**本机元数据读面**
 * （与 `health`/`logs` 同款判据——网关不在时恰恰更该可用），直接实例化本
 * 数据根的 `SecretsStore` 读盘；与网关 REST 的 `GET /api/secrets` 是同一
 * `list()` 单点，两端读数一致。只读：`list` 刻意不刷新目录（读不写，store
 * 契约），sidecar/catalog 不动。
 */
object SecretsCommand extends CliCommand:
  def name = "secrets"
  def description = "Secrets store inventory: name, purpose, updated time — values are NEVER displayed"
  def subcommands = List(SecretsUsage, SecretsList)
  def examples = List("stand secrets", "stand secrets list", "stand secrets list --json")
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
              "stand secrets       — show this usage".asJson,
              "stand secrets list  — list secrets (name, variable, updated, purpose; values never shown)".asJson
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
            "  stand secrets            Show this usage",
            "  stand secrets list       List secrets: name, variable name, updated time, purpose (metadata only)",
            "  stand secrets list --json  Machine-readable inventory",
            "",
            s"The store: $storePath — one owner-only (0600) file per secret.",
            "Create: settings · secrets panel, or an AskUserQuestion secret question.",
            "Consume: \"$(cat <location>)\" inside a sub-shell, or $VARIABLE when allowlisted (nebflow.json secrets.inject).",
            "This command never prints a value."
          )
        )
      )

/** `secrets list` — the metadata inventory (the A-item face: 键名 + 用途 + 写入时间). */
private[cli] object SecretsList extends CliSubcommand:
  def name = "list"
  def description = "List all secrets: name, variable name, updated time, purpose — metadata only, never a value"
  def params = Nil

  def run(ctx: CliContext): IO[CliResult] =
    // Same store construction as the gateway's per-request face
    // (SecretsRoutes.secretsStore): THIS instance's data root — so the CLI
    // inventory and the panel list can never disagree about what exists.
    val store = new nebflow.core.secrets.SecretsStore(PathUtil.dataRoot / "secrets")
    store.list().flatMap {
      case Left(err) =>
        IO.pure(CliResult.error(s"secrets store unreadable: ${describe(err)}"))
      case Right(entries) =>
        if ctx.json then IO.pure(CliResult.Json(inventoryJson(store, entries)))
        else IO.pure(CliResult.Text(inventoryText(store, entries)))
    }

  /** Failure → one-line diagnostic (name/code family only — never a value). */
  private def describe(err: nebflow.core.secrets.SecretsStore.Failure): String =
    import nebflow.core.secrets.SecretsStore.Failure
    err match
      case Failure.Io(reason)       => s"io: $reason"
      case Failure.SecretMode(n, r) => s"secret_mode: $n ($r)"
      case other                    => s"${other.getClass.getSimpleName.toLowerCase}"

  /** Local-time stamp, same face as the rendered catalogue (yyyy-MM-dd HH:mm). */
  private def stamp(millis: Long): String =
    if millis <= 0 then "-"
    else
      DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))

  /** Purpose cell: the catalogue description, `-` when none was ever set. */
  private def purposeOf(description: String): String =
    if description.isEmpty then "-" else description.replaceAll("[\\r\\n]+", " ")

  private def inventoryJson(store: nebflow.core.secrets.SecretsStore, entries: List[store.Entry]): Json =
    Json.obj(
      "count" -> entries.size.asJson,
      "store" -> (PathUtil.dataRoot / "secrets").toString.asJson,
      "secrets" -> entries.map { e =>
        Json.obj(
          "name" -> e.name.asJson,
          "variableName" -> e.variableName.asJson,
          "description" -> e.description.asJson,
          "size" -> e.size.asJson,
          "updated" -> stamp(e.mtime).asJson,
          "mode" -> e.mode.asJson,
          "refs" -> e.refs.asJson
        )
      }.asJson
    )

  private def inventoryText(store: nebflow.core.secrets.SecretsStore, entries: List[store.Entry]): List[String] =
    val storePath = s"${PathUtil.dataRoot}/secrets"
    if entries.isEmpty then
      List(
        "No secrets stored.",
        s"The store $storePath is empty (archive/rollback backups are not listed).",
        "Create one in the settings · secrets panel, or via an AskUserQuestion secret question."
      )
    else
      val nameW = entries.foldLeft(4)((w, e) => math.max(w, e.name.length))
      val varW = entries.foldLeft(8)((w, e) => math.max(w, e.variableName.length))
      val header = s"${"NAME".padTo(nameW, ' ')}  ${"VARIABLE".padTo(varW, ' ')}  ${"UPDATED".padTo(16, ' ')}  PURPOSE"
      val rows = entries.map { e =>
        s"${e.name.padTo(nameW, ' ')}  ${e.variableName.padTo(varW, ' ')}  ${stamp(e.mtime).padTo(16, ' ')}  ${purposeOf(e.description)}"
      }
      val footer = List(
        "",
        s"${entries.size} secret(s) · store: $storePath",
        "Values are never displayed — one owner-only (0600) file per name; consume via \"$(cat <path>)\" or an allowlisted $VARIABLE."
      )
      header :: rows ::: footer

  end inventoryText

end SecretsList
