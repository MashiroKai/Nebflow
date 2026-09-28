package nebflow.core.daemon

import cats.effect.IO
import io.circe.Json
import io.circe.parser.decode
import nebflow.core.{NebflowLogger, PathUtil}

import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Storage for daemon config-panel VALUES (daemonpanel Phase A).
  *
  * Discipline: **declaration inline, values external**. The declaration lives
  * in `daemons.json` (`DaemonConfig.configPanel`), while values live in a
  * separate file so a value change never rewrites the declaration:
  *
  *   - non-secret values -> `~/.nebflow/daemon-config/<id>.json` (mode 0600)
  *   - secret values     -> `~/.nebflow/secrets/<name>` (existing namespace:
  *     dir 0700 / value 0600 — this is NOT a new mechanism, see the design
  *     evidence D-1)
  *
  * Secrets are never written into `daemons.json`, and never read back into the
  * UI: the read view masks every `type:"secret"` field as `"***"`, a written
  * `"***"` keeps the stored value, and an empty string means "do not modify".
  *
  * Permission handling is FAIL-CLOSED and local: `CredentialFileAcl.restrict`
  * is warn-not-abort (fail-open), so it is deliberately not relied upon — this
  * store checks modes itself and refuses to read/write a credential whose
  * permissions are too wide.
  */
class DaemonPanelStore(dataRoot: os.Path = PathUtil.dataRoot):

  private val logger = NebflowLogger.forName("nebflow.daemon.panelstore")

  /** Mask literal for secret fields (same discipline as ConfigService). */
  val Mask = "***"

  def configDir: os.Path = dataRoot / "daemon-config"
  def secretsDir: os.Path = dataRoot / "secrets"
  def valuesPath(id: String): os.Path = configDir / s"${safeName(id)}.json"

  /** Backing secret file name. Deterministic and namespaced so it cannot
    * collide with an unrelated credential, inside the EXISTING `secrets/` dir.
    */
  def secretName(id: String, key: String): String = s"daemon-${safeName(id)}-${safeName(key)}"

  def secretPath(id: String, key: String): os.Path = secretsDir / secretName(id, key)

  private def safeName(s: String): String =
    s.map(c => if c.isLetterOrDigit || c == '-' || c == '_' || c == '.' then c else '_')

  // ── Read ───────────────────────────────────────────────────────────────

  /** Panel read view: field key -> value, with every secret masked.
    *
    * A declared field with no stored value falls back to its declared default,
    * so the UI always shows something meaningful for an unconfigured daemon.
    */
  def readMasked(decl: DaemonPanelSchema.Declaration, id: String): IO[Map[String, Json]] =
    loadValues(id).map { stored =>
      decl.fields.map { f =>
        if f.isSecret then f.key -> Json.fromString(Mask)
        else f.key -> stored.getOrElse(f.key, f.default.getOrElse(Json.Null))
      }.toMap
    }

  /** Stored values as-is (secrets resolved to their file content). Only for
    * internal use / the credential probe — never for a UI response.
    */
  def loadValues(id: String): IO[Map[String, Json]] =
    IO.blocking {
      val p = valuesPath(id)
      if !os.exists(p) then Map.empty[String, Json]
      else
        decode[Map[String, Json]](os.read(p)) match
          case Right(m) => m
          case Left(err) =>
            logger.warnSync(s"Failed to parse daemon panel values for '$id': ${err.getMessage}")
            Map.empty[String, Json]
    }.handleErrorWith { e =>
      logger.warn(s"Failed to load daemon panel values for '$id': ${e.getMessage}").as(Map.empty[String, Json])
    }

  // ── Write ──────────────────────────────────────────────────────────────

  /** Apply an incoming value map.
    *
    * Every key must be declared (`unknown field`), every value must match its
    * declared type (`invalid value`). Secret semantics: `"***"` keeps the
    * stored value, `""` does not modify it, anything else replaces it.
    *
    * When the resulting non-secret value map is byte-identical to what is
    * already stored, the file is NOT rewritten — that is what makes the
    * F4 round-trip (`cmp` equal) hold exactly rather than approximately.
    */
  def writeValues(
    decl: DaemonPanelSchema.Declaration,
    id: String,
    incoming: Map[String, Json]
  ): IO[Either[String, Unit]] =
    val byKey = decl.fieldByKey
    val unknown = incoming.keys.filterNot(byKey.contains).toList.sorted
    if unknown.nonEmpty then IO.pure(Left(s"unknown field: ${unknown.mkString(", ")}"))
    else
      // Validate every non-secret value before touching any file.
      val invalid = incoming.toList.flatMap { case (k, v) =>
        val f = byKey(k)
        if f.isSecret then Nil
        else validateValue(f, v).left.toOption.map(e => s"invalid value for '$k': $e")
      }
      invalid.headOption match
        case Some(err) => IO.pure(Left(err))
        case None =>
          for
            before <- loadValues(id)
            secretEdits = incoming.toList.collect { case (k, v) if byKey(k).isSecret => (byKey(k), v) }
            written <- secretEdits.foldLeft(IO.pure(Right(()): Either[String, Unit])) { (acc, pair) =>
              acc.flatMap {
                case Left(e)  => IO.pure(Left(e))
                case Right(_) => writeSecret(id, pair._1.key, pair._2)
              }
            }
            result <- written match
              case Left(e) => IO.pure(Left(e))
              case Right(_) =>
                // Normalize before persisting, so that echoing the read view
                // back is a genuine no-op (F4):
                //   - JSON null means "unset"            -> drop the key
                //   - a value equal to its declared      -> drop the key; the
                //     default                               default is supplied
                //                                           at read time either way
                // Both cases are observationally identical to the user, and
                // dropping them keeps the file free of materialized defaults.
                var next = before
                for (f, v) <- incoming.toList.collect { case (k, v) if !byKey(k).isSecret => (byKey(k), v) } do
                  if v.isNull || f.default.contains(v) then next = next - f.key
                  else next = next + (f.key -> v)
                if next == before then IO.pure(Right(())) // byte-stable: no rewrite
                else saveValues(id, next).as(Right(()))
          yield result

  private def validateValue(f: DaemonPanelSchema.PanelField, v: Json): Either[String, Unit] =
    if v.isNull then
      if f.required then Left("required field cannot be empty") else Right(())
    else
      f.ftype match
        case "string" | "text" => if v.isString then Right(()) else Left("expected a string")
        case "secret" =>
          // "***" = keep, "" = do not modify; any other string replaces.
          if v.isString then Right(()) else Left("expected a string")
        case "number" =>
          v.asNumber.map(_.toDouble) match
            case None => Left("expected a number")
            case Some(d) =>
              f.min match
                case Some(lo) if d < lo => Left(s"must be >= $lo")
                case _ =>
                  f.max match
                    case Some(hi) if d > hi => Left(s"must be <= $hi")
                    case _                  => Right(())
        case "boolean" => if v.isBoolean then Right(()) else Left("expected a boolean")
        case "enum" =>
          v.asString match
            case Some(s) if f.options.contains(s) => Right(())
            case Some(s)                          => Left(s"'$s' is not one of ${f.options.mkString(", ")}")
            case None                             => Left("expected one of the declared options")
        case other => Left(s"unsupported type '$other'")

  private def saveValues(id: String, values: Map[String, Json]): IO[Unit] =
    IO.blocking {
      os.makeDir.all(configDir)
      restrictDir(configDir)
      val tmp = configDir / s".${safeName(id)}.json.tmp"
      // Sorted keys: deterministic bytes make the F4 `cmp` round-trip exact.
      os.write.over(tmp, Json.fromFields(values.toList.sortBy(_._1)).noSpaces)
      restrictFile(tmp)
      os.move.over(tmp, valuesPath(id))
      restrictFile(valuesPath(id))
    }

  private def writeSecret(id: String, key: String, value: Json): IO[Either[String, Unit]] =
    value.asString match
      case None => IO.pure(Left(s"invalid value for '$key': expected a string"))
      case Some(Mask) => IO.pure(Right(())) // keep the stored value untouched
      case Some("") => IO.pure(Right(())) // empty = do not modify
      case Some(plain) =>
        IO.blocking {
          // Fail-closed FIRST: if the namespace already exists and is too
          // permissive, refuse before creating or tightening anything. (The
          // tighten below is best-effort only — the decision is `dirIsTight`.)
          if os.exists(secretsDir) && !dirIsTight(secretsDir) then
            Left(s"secrets directory permissions are too permissive; refusing to write '$key'")
          else
            os.makeDir.all(secretsDir)
            restrictDir(secretsDir)
            val p = secretPath(id, key)
            val tmp = secretsDir / s".${secretName(id, key)}.tmp"
            os.write.over(tmp, plain)
            restrictFile(tmp)
            os.move.over(tmp, p)
            restrictFile(p)
            if !fileIsTight(p) then
              os.remove(p)
              Left(s"could not restrict permissions on secret '$key'; refusing to store it")
            else Right(())
        }.handleErrorWith(e => IO.pure(Left(s"failed to store secret '$key': ${e.getMessage}")))

  // ── Credential three-state (Phase B readiness, read-only) ─────────────

  /** Three-state probe per declared secret field:
    *   - `missing`    no backing file
    *   - `permissive` file or dir permissions are wider than 0600 / 0700
    *   - `ok`         file exists, mode 0600, dir 0700
    */
  def credentialStates(
    decl: DaemonPanelSchema.Declaration,
    id: String
  ): IO[List[DaemonPanelSchema.CredentialState]] =
    IO.blocking {
      decl.fields.filter(_.isSecret).map { f =>
        val name = secretName(id, f.key)
        val p = secretsDir / name
        if !os.exists(p) then DaemonPanelSchema.CredentialState(f.key, name, DaemonPanelSchema.CredentialStates.Missing, None)
        else
          val mode = fileMode(p)
          val tight = fileIsTight(p) && dirIsTight(secretsDir)
          val state =
            if tight then DaemonPanelSchema.CredentialStates.Ok else DaemonPanelSchema.CredentialStates.Permissive
          DaemonPanelSchema.CredentialState(f.key, name, state, mode)
      }
    }.handleErrorWith { e =>
      logger.warn(s"credential state probe failed for '$id': ${e.getMessage}").as(Nil)
    }

  // ── Permission helpers (fail-closed, local) ───────────────────────────

  private def permsOf(p: os.Path): Option[Set[PosixFilePermission]] =
    try
      val jp: Path = Paths.get(p.toString)
      if Files.exists(jp) then Some(Files.getPosixFilePermissions(jp).asScala.toSet) else None
    catch case _: Throwable => None

  /** Owner-only for a file: exactly rw------- (0600). */
  private def fileIsTight(p: os.Path): Boolean =
    permsOf(p).contains(Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))

  /** Owner-only for a directory: exactly rwx------ (0700). */
  private def dirIsTight(p: os.Path): Boolean =
    permsOf(p).contains(
      Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    )

  private def fileMode(p: os.Path): Option[String] =
    permsOf(p).map { perms =>
      def bit(set: Set[PosixFilePermission], ps: PosixFilePermission*): Int =
        if ps.forall(set.contains) then 1 else 0
      val o = bit(perms, PosixFilePermission.OWNER_READ) * 4 + bit(perms, PosixFilePermission.OWNER_WRITE) * 2 +
        bit(perms, PosixFilePermission.OWNER_EXECUTE)
      val g = bit(perms, PosixFilePermission.GROUP_READ) * 4 + bit(perms, PosixFilePermission.GROUP_WRITE) * 2 +
        bit(perms, PosixFilePermission.GROUP_EXECUTE)
      val w = bit(perms, PosixFilePermission.OTHERS_READ) * 4 + bit(perms, PosixFilePermission.OTHERS_WRITE) * 2 +
        bit(perms, PosixFilePermission.OTHERS_EXECUTE)
      s"$o$g$w"
    }

  /** Best-effort tightening; the fail-closed decision is `dirIsTight` above,
    * never this call (mirrors the CredentialFileAcl warn-not-abort caveat).
    */
  private def restrictDir(p: os.Path): Unit =
    try
      os.perms.set(p, "rwx------")
      ()
    catch case _: Throwable => ()

  private def restrictFile(p: os.Path): Unit =
    try
      os.perms.set(p, "rw-------")
      ()
    catch case _: Throwable => ()

end DaemonPanelStore
