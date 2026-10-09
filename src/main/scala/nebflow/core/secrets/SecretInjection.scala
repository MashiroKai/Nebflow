package nebflow.core.secrets

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * The C-case injection allowlist (secrets-consumption batch, 2026-10-01).
 *
 * BY PATH is the universal baseline: a consumer interpolates
 * `$(cat <location>)` at command time and the value exists only inside that
 * sub-shell. The allowlist is the OPT-IN exception on top of it — a named
 * secret is injected by the gateway as an environment variable into the Bash
 * commands of the agents it was named for, so a high-frequency credential can
 * be used as `$VARIABLE` instead of an interpolation ritual.
 *
 * 🔴 The default is EMPTY, and an empty allowlist is byte-for-byte the old
 * behaviour: nothing is read, nothing is added to any child environment. The
 * exposure that injection buys is per-key and always an explicit naming
 * decision, made by the operator in `nebflow.json`.
 *
 * What an allowlisted key costs, stated plainly (design card §C — the same
 * trade every env-injection mechanism makes): the value is present in EVERY
 * command environment of the named agents, not just the ones that use it, so
 * a debugging command that dumps the environment (`env`, `printenv`, `set`)
 * reveals every injected value at once, and every descendant process inherits
 * them. That is why the allowlist starts empty and is not a per-project
 * default.
 *
 * Config face (`nebflow.json` — a NAME-ONLY list; no value ever lands in the
 * config file):
 *
 * {{{
 * { "secrets": { "inject": {
 *     "default": [ { "variable": "VERCEL_TOKEN", "secret": "vercel-token" } ],
 *     "agents":  { "Nebula": [ { "variable": "OPENAI_API_KEY", "secret": "openai-key" } ] }
 * } } }
 * }}}
 *
 * A malformed section degrades to "no injection" rather than to a partial
 * list: guessing which half of a broken allowlist the operator meant would be
 * strictly worse than injecting nothing.
 */
object SecretInjection:

  /**
   * One allowlist entry: the environment variable name to expose, and the
   * secret whose value backs it. Both sides are identifiers — the value is
   * never part of this type.
   */
  final case class Entry(variable: String, secret: String)

  /** The parsed allowlist: the blanket entries plus the per-agent additions. */
  final case class Config(defaults: List[Entry], byAgent: Map[String, List[Entry]])

  val empty: Config = Config(Nil, Map.empty)

  /**
   * Largest value this face will inject. Anything larger is skipped: an
   * environment block has a hard OS size limit, and a credential is never
   * legitimately that big (same 64 KiB sense as the store's write cap).
   */
  val MaxInjectBytes: Int = 64 * 1024

  /**
   * Load the allowlist from `nebflow.json`. Fail-safe: an absent file, an
   * unparseable file, or a malformed section all read as [[empty]] — "no
   * injection".
   */
  def load(dataRoot: os.Path): Config =
    val p = nebflow.shared.PathUtil.configJsonWritePath(dataRoot)
    if !Files.exists(p.toNIO) then empty
    else
      try
        io.circe.parser.parse(os.read(p)) match
          case Right(json) =>
            val node = json.hcursor.downField("secrets").downField("inject")
            val defaults = node.downField("default").focus.flatMap(entriesOf).getOrElse(Nil)
            val agents = node.downField("agents").focus.flatMap(_.asObject).map(_.toMap).getOrElse(Map.empty)
            Config(defaults, agents.map { case (k, v) => k -> entriesOf(v).getOrElse(Nil) })
          case Left(_) => empty
      catch case _: Throwable => empty

  /** Decode one entry array, dropping entries that are not name->name pairs. */
  private def entriesOf(json: io.circe.Json): Option[List[Entry]] =
    json.asArray.map(_.toList.flatMap { e =>
      val h = e.hcursor
      for
        v <- h.downField("variable").as[String].toOption.filter(_.nonEmpty)
        s <- h.downField("secret").as[String].toOption.filter(_.nonEmpty)
      yield Entry(v, s)
    })

  /**
   * The effective list for one agent: the blanket entries plus that agent's
   * own, where an agent entry REPLACES a blanket entry with the same variable
   * name (so a per-agent block can both add a key and repoint one). Order is
   * stable: blanket entries first in configured order, then the agent's own
   * additions.
   */
  def resolve(config: Config, agentName: Option[String]): List[Entry] =
    val own = agentName.flatMap(config.byAgent.get).getOrElse(Nil)
    val overridden = own.map(_.variable).toSet
    config.defaults.filterNot(e => overridden.contains(e.variable)) ++ own

  /**
   * The environment additions for a resolved allowlist. Reads each
   * allowlisted secret file — this is the ONLY place in the batch that opens
   * a credential for its content, and it opens nothing at all when the
   * allowlist is empty.
   *
   * Fail-safe per entry: a name that does not exist, an unreadable file or an
   * oversized value is SKIPPED rather than raised — a broken allowlist entry
   * must not take down every Bash command of the agent it was configured for.
   * The skip is silent here and audible at the call site, which logs the
   * variable names that did not resolve (never a value).
   *
   * At most one trailing newline is removed, matching what `$(cat <file>)`
   * yields: a file written by the panel or by a shell redirect commonly ends
   * with one, and an environment variable carrying it would silently break
   * `Authorization` headers and token comparisons.
   */
  def materialize(secretsDir: os.Path, entries: List[Entry]): Map[String, String] =
    if entries.isEmpty then Map.empty
    else
      entries.flatMap { e =>
        try
          val f = secretsDir / e.secret
          if !Files.isRegularFile(f.toNIO) then None
          else
            val bytes = Files.readAllBytes(f.toNIO)
            if bytes.length > MaxInjectBytes then None
            else
              val raw = new String(bytes, StandardCharsets.UTF_8)
              val value =
                if raw.endsWith("\r\n") then raw.dropRight(2)
                else if raw.endsWith("\n") then raw.dropRight(1)
                else raw
              if value.isEmpty then None else Some(e.variable -> value)
        catch case _: Throwable => None
      }.toMap

  /**
   * The SECRET NAMES that did not resolve into an environment variable — the
   * audit line's payload. Names only: this is what may be logged, never a
   * value, and never even the resolved count's counterpart.
   */
  def unresolved(entries: List[Entry], env: Map[String, String]): List[String] =
    entries.filterNot(e => env.contains(e.variable)).map(_.secret).distinct

end SecretInjection
