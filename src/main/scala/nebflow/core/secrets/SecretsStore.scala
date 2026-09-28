package nebflow.core.secrets

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.CredentialFileAcl
import nebflow.shared.{Branding, PathUtil}

import nebflow.social.SocialChannels

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/** Unified secrets store (secrets-panel backend leg, 2026-09-27).
  *
  * One management face over the EXISTING `~/.nebflow/secrets/` namespace —
  * same directory, same discipline, zero storage change for the two existing
  * writers (`SocialChannels.writeSecret`, `DaemonPanelStore.writeSecret`).
  *
  * 🔴 Two hard rules, both pinned by `SecretsStoreSpec`:
  *
  * ① Zero value exposure. Nothing in this file ever reads a stored value back:
  * the read face answers metadata only (`list`), the write face takes a
  * plaintext and never echoes it, and NO diagnostic (log line, error message,
  * failure payload) may carry a value — name + exception class + path only
  * (same credential protocol as `SocialChannels`/`CredentialFileAcl`).
  *
  * ② The write discipline IS the F1 fix order (`SocialChannels.writeSecret`,
  * `SocialChannels.scala:304-333`): ① a target that does not exist is created
  * `rw-------` from its very first byte (mode from the creation attribute, no
  * "write, then narrow" window); ② a target that already exists is NARROWED
  * BEFORE any new plaintext can land in it; ③ the plaintext is written IN
  * PLACE (`TRUNCATE_EXISTING` — the inode is preserved, which is what
  * `NfPathPolicy.credentialInodes` keys on; a rename would hand the credential
  * a fresh inode); ④ `CredentialFileAcl.restrict` is the authoritative
  * narrowing step. No private copy of the ACL logic — everything goes through
  * `CredentialFileAcl`.
  *
  * Fail-closed doubles (`DaemonPanelStore.scala:189-206` shape): before any
  * write — an existing secrets directory that is NOT 0700 refuses the write;
  * after the write — a file that did not end up 0600 is deleted and the write
  * refused. A failure removes whatever THIS call created or wrote; a
  * pre-existing file that was never written into is left exactly as found. A
  * secret is never reported as stored when it is not.
  *
  * Naming (`^[a-z0-9][a-z0-9._-]{0,63}$`): lowercase letter/digit first,
  * charset `[a-z0-9._-]`, length 1-64. Two hard rejections on top: a name
  * starting with `.` and a name ending with `.tmp` — `.名字.tmp` inside the
  * secrets dir is the daemon panel's temp-file namespace
  * (`DaemonPanelStore.scala:198`). The `social-`/`daemon-` prefixes are MANAGED
  * namespaces: visible in the list, every write refused
  * (`403 managed_namespace`) — they belong to their own panels.
  *
  * No actor face (backend dev rule): every effect sits on `IO.blocking` and
  * every failure on an explicit `Either[Failure, *]` / `IO` channel.
  */
class SecretsStore(
  val secretsDir: os.Path,
  /** Data root of the install this store serves. The channel-reference scan
    * reads `<root>/nebflow.json` (`socialChannels.*`) only — never a secret
    * value, only stored path strings. */
  dataRoot: os.Path = PathUtil.dataRoot,
  /** Daemons declaration file for the daemon-panel reference scan
    * (`daemons.json`, `configPanel` blocks). Parameterized like `secretsDir`
    * so tests never touch the real one. */
  daemonsPath: os.Path = PathUtil.dataRoot / "daemons.json",
  /** The credential-narrowing step; production leaves it at the shared
    * `CredentialFileAcl` module and only the zero-residue test injects a
    * failing one (same seam as `SocialChannels.save(restrict)`). */
  restrict: Path => Unit = (p: Path) => CredentialFileAcl.restrict(p),
  /** The best-effort NAMESPACE-narrowing step (the directory leg of the write
    * path). Production leaves it at the shared `CredentialFileAcl` module; the
    * fail-closed test injects a no-op standing for "this namespace cannot be
    * narrowed", which is the only way the directory gate's refusal arm
    * (`Left(SecretMode)`) is reachable in-process — the real
    * `CredentialFileAcl.restrictDirectory` succeeds on any directory we own,
    * so without the seam that arm has no failing test. Same injection-seam
    * pattern as [[restrict]]; the ACL logic itself is never copied here. */
  restrictDirectory: Path => Unit = (p: Path) => CredentialFileAcl.restrictDirectory(p)
):

  import SecretsStore.*

  // ── Naming ─────────────────────────────────────────────────────────────

  /** Service-side name validation (the ONLY path into the filesystem — the
    * caller never hands us a path, only a name, and the charset excludes `/`,
    * so the traversal face is closed by construction). */
  def validateName(name: String): Either[Failure, String] =
    if name.startsWith(".") then Left(Failure.InvalidName(name, "must not start with '.'"))
    else if name.endsWith(".tmp") then Left(Failure.InvalidName(name, "must not end with '.tmp'"))
    else if name.length > MaxLen then
      Left(Failure.InvalidName(name, s"too long (max $MaxLen characters)"))
    else if !NamePattern.matcher(name).matches() then
      Left(Failure.InvalidName(name, "must match ^[a-z0-9][a-z0-9._-]{0,63}$ (lowercase charset)"))
    else Right(name)

  /** Managed namespace check — `social-`/`daemon-` belong to their own panels.
    * The list face still shows these files (metadata + refs); only writes are
    * refused here. */
  def managedNamespace(name: String): Boolean =
    name.startsWith("social-") || name.startsWith("daemon-")

  // ── Read face (metadata only — never a value) ──────────────────────────

  /** One listed secret: name, size (bytes), mtime (epoch millis), the mode
    * string (`0600` on a POSIX readback, `null` where the OS has no POSIX
    * view) and the referencing ids. No `value` key exists anywhere in this
    * shape — enforced structurally by the routes spec. */
  final case class Entry(name: String, size: Long, mtime: Long, mode: Option[String], refs: List[String])

  /** List everything under `secretsDir`. A missing directory is an empty list
    * (no secrets yet), not an error. Managed names are visible like any
    * other; only writes distinguish them. */
  def list(): IO[Either[Failure, List[Entry]]] =
    IO.blocking {
      if !Files.isDirectory(secretsDir.toNIO) then Right(Nil)
      else
        val names = os.list(secretsDir).filter(p => Files.isRegularFile(p.toNIO)).map(_.last).toList.sorted
        val refs = referencedByAll()
        Right(names.map(n =>
          Entry(
            name = n,
            size = Files.size((secretsDir / n).toNIO),
            mtime = Files.getLastModifiedTime((secretsDir / n).toNIO).toMillis,
            mode = modeOf(secretsDir / n),
            refs = refs.getOrElse(n, Nil)
          )
        ))
    }.handleErrorWith(e => IO.pure(Left(Failure.Io(describe(e, secretsDir.toString)))))

  // ── Write face ─────────────────────────────────────────────────────────

  /** Create a new secret. An existing target is `409 already_exists` (the
    * panel's POST semantics; overwrite goes through [[overwrite]] — no
    * upsert, so a typo'd name can never silently mint a new file). The 64 KiB
    * value cap is checked BEFORE any byte touches the filesystem (author
    * ruling ⑦; `400 invalid_value`). */
  def create(name: String, value: String): IO[Either[Failure, Unit]] =
    validateName(name) match
      case Left(f) => IO.pure(Left(f))
      case Right(_) if value.getBytes(StandardCharsets.UTF_8).length > MaxValueBytes =>
        IO.pure(Left(Failure.InvalidValue(name, s"value exceeds the ${MaxValueBytes}-byte limit")))
      case Right(_) if managedNamespace(name) =>
        IO.pure(Left(Failure.ManagedNamespace(name)))
      case Right(_) =>
        IO.blocking {
          if Files.exists(path(name).toNIO) then Left(Failure.AlreadyExists(name))
          else writeThrough(name, value).map(_ => ())
        }.handleErrorWith(e => IO.pure(Left(Failure.Io(describe(e, name)))))

  /** Overwrite an existing secret entirely. A missing target is `404
    * unknown_secret` (no upsert). The 64 KiB cap applies here too. */
  def overwrite(name: String, value: String): IO[Either[Failure, Unit]] =
    validateName(name) match
      case Left(f) => IO.pure(Left(f))
      case Right(_) if value.getBytes(StandardCharsets.UTF_8).length > MaxValueBytes =>
        IO.pure(Left(Failure.InvalidValue(name, s"value exceeds the ${MaxValueBytes}-byte limit")))
      case Right(_) if managedNamespace(name) =>
        IO.pure(Left(Failure.ManagedNamespace(name)))
      case Right(_) =>
        IO.blocking {
          if !Files.exists(path(name).toNIO) then Left(Failure.UnknownSecret(name))
          else writeThrough(name, value).map(_ => ())
        }.handleErrorWith(e => IO.pure(Left(Failure.Io(describe(e, name)))))

  /** Delete a secret. Guard rails in order — the managed check runs BEFORE
    * the reference check (a managed name refuses even when unreferenced):
    *   - missing target            → `404 unknown_secret`
    *   - managed namespace         → `403 managed_namespace` (its own panel owns the lifecycle)
    *   - referenced, non-managed   → `409 referenced` with the referrer list
    *   - otherwise                 → delete the file, never a value in the payload
    */
  def delete(name: String, refs: List[String] = Nil): IO[Either[Failure, Unit]] =
    validateName(name) match
      case Left(f) => IO.pure(Left(f))
      case Right(_) =>
        IO.blocking {
          if !Files.exists(path(name).toNIO) then Left(Failure.UnknownSecret(name))
          else if managedNamespace(name) then Left(Failure.ManagedNamespace(name))
          else if refs.nonEmpty then Left(Failure.Referenced(name, refs))
          else
            try
              Files.delete(path(name).toNIO)
              logger.info(s"Secret deleted: $name")
              Right(())
            catch
              case e: Exception => Left(Failure.Io(describe(e, name)))
        }.handleErrorWith(e => IO.pure(Left(Failure.Io(describe(e, name)))))

  // ── Referenced-by (pure scan, two sources) ─────────────────────────────

  /** Name → referencing ids, BOTH sources (plan card 问7):
    *
    * ① channel face: every `socialChannels.channels.<id>.fields.<storedKey>`
    * value in `nebflow.json` that equals `<rendered root>/secrets/<name>` —
    * the exact string `SocialChannels.persist` writes — contributes `<id>`;
    * PLUS the definition-layer preset names (`SocialChannels.channels`
    * secretName fields): a channel with a configured card claims its preset
    * names even before a value was ever stored.
    *
    * ② daemon-panel face: every `daemons.json` daemon with a `configPanel`
    * declaring a `secret` field contributes `DaemonPanelStore.secretName(id,
    * key)` — the file's existence is the reference (the three-state probe's
    * not-missing reading).
    *
    * Pure over its inputs (files + definitions, never a value): the same
    * bytes in, the same mapping out — which is what makes it testable
    * against fixture roots.
    */
  def referencedByAll(): Map[String, List[String]] =
    val out = scala.collection.mutable.Map.empty[String, List[String]]
    def claim(name: String, referrer: String): Unit =
      if name.nonEmpty then out(name) = out.getOrElse(name, Nil) :+ referrer

    // ① channel face — stored path refs
    channelConfig() match
      case Some(channels) =>
        channels.asObject.getOrElse(JsonObject.empty).toList.foreach { case (id, entry) =>
          entry.hcursor.downField("fields").focus
            .flatMap(_.asObject).getOrElse(JsonObject.empty).toList
            .foreach { case (_, v) =>
              v.asString.filter(_.nonEmpty) match
                case Some(ref) =>
                  secretNameOfStoredRef(ref) match
                    case Some(n) => claim(n, s"social:$id")
                    case None    => ()
                case None => ()
            }
        }
      case None => ()

    // ① channel face — definition-layer preset names
    SocialChannels.channels.foreach { spec =>
      spec.secretFields.foreach { f =>
        // The stored fallback name the write path derives for this field
        // (`SocialChannels.secretPath`, `SocialChannels.scala:248-250`).
        claim(f.secretName.getOrElse(s"social-${spec.id}-${f.key}"), s"social:${spec.id}")
      }
    }

    // ② daemon-panel face — declared secret fields claim their backing name
    daemonsConfig() match
      case Some(daemons) =>
        daemons.asArray.getOrElse(Vector.empty).foreach { d =>
          val id = d.hcursor.downField("id").as[String].getOrElse("")
          if id.nonEmpty then
            d.hcursor.downField("configPanel").downField("fields").focus match
              case Some(fieldsJson) =>
                fieldsJson.asArray.getOrElse(Vector.empty).foreach { f =>
                  val key = f.hcursor.downField("key").as[String].getOrElse("")
                  val tpe = f.hcursor.downField("type").as[String].getOrElse("")
                  if key.nonEmpty && tpe == "secret" then
                    claim(daemonSecretName(id, key), s"daemon:$id")
                }
              case None => ()
        }
      case None => ()

    out.toMap

  /** Referenced-by for ONE name (the delete route's guard input): both scan
    * sources of [[referencedByAll]], restricted to `name`. */
  def referencedBy(name: String): IO[List[String]] =
    IO.blocking(referencedByAll().getOrElse(name, Nil))

  // ── Internals ──────────────────────────────────────────────────────────

  private def logger = nebflow.shared.NebflowLogger.forName("nebflow.core.secrets")

  /** The validated filesystem target — the ONLY path construction in this
    * file. `validateName` already excluded `/`, `.`-prefix and `.tmp`-suffix,
    * so this cannot escape the directory. */
  private def path(validatedName: String): os.Path = secretsDir / validatedName

  /** The four-step write (`SocialChannels.writeSecret` order, see the class
    * doc). `ours` = the file currently at the target was created or written
    * by THIS call; on any failure it is removed — a pre-existing file that was
    * never written into is left exactly as found.
    *
    * The directory gate runs AFTER the best-effort `restrictDirectory` (both
    * legs), so the decision reads the mode the namespace actually has NOW —
    * a legacy-wide `secrets/` is narrowed first and only refuses when the
    * narrowing could not make it owner-only. */
  private def writeThrough(name: String, plain: String): Either[Failure, Unit] =
    val target = path(name).toNIO
    var ours = false
    try
      if Files.exists(target) then
        restrictDirectoryIfPresent()
        // Fail-closed BEFORE touching anything: a secrets dir that is not
        // 0700 (after the best-effort narrowing) refuses the write
        // (`DaemonPanelStore` shape).
        if !dirIsTight(secretsDir.toNIO) then Left(Failure.SecretMode(name, dirTooOpenDetail))
        else
          restrict(target) // ② narrow the pre-existing file BEFORE the plaintext
          ours = true
          Files.write(
            target,
            plain.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          )
          restrict(target) // ④ the authoritative narrowing step
          postVerify(name, target): Either[Failure, Unit]
      else
        Files.createDirectories(secretsDir.toNIO)
        restrictDirectoryIfPresent()
        // The dir-too-open gate applies to the CREATE leg too: after the
        // narrowing attempt, re-read its mode and refuse if it could not be
        // kept owner-only (a wide namespace would hand the new file a wide
        // inheritance source).
        if !dirIsTight(secretsDir.toNIO) then Left(Failure.SecretMode(name, dirTooOpenDetail))
        else
          createOwnerOnly(target) // ① mode from the creation attribute — no window
          ours = true
          Files.write(
            target,
            plain.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          )
          restrict(target) // ④
          postVerify(name, target): Either[Failure, Unit]
    catch
      case e: Exception =>
        val residual =
          if ours then
            try if Files.deleteIfExists(target) then "deleted" else "absent"
            catch case _: Exception => "undeletable"
          else "pre-existing-untouched"
        Left(Failure.SecretMode(name,
          s"could not store secret '$name': ${e.getClass.getSimpleName}: " +
            s"${Option(e.getMessage).getOrElse("")} (path=$target residual=$residual)"))

  /** The post-write fail-closed double: a file that did not end up 0600 is
    * deleted and the write refused — the plaintext never stays behind in a
    * file we could not narrow (`DaemonPanelStore.scala:203-206` shape). */
  private def postVerify(name: String, target: Path): Either[Failure, Unit] =
    fileIsTight(target) match
      case true => Right(())
      case false =>
        try Files.deleteIfExists(target)
        catch case _: Exception => ()
        Left(Failure.SecretMode(name,
          s"could not restrict permissions on secret '$name'; refusing to store it (path=$target)"))

  /** Create an EMPTY file `rw-------` from its very first byte — the mode
    * comes from the creation attribute, so the "write, then narrow" window
    * reported by socpanel-verify cannot exist (`SocialChannels.createOwnerOnly`
    * verbatim semantics, `SocialChannels.scala:269-277`). */
  private def createOwnerOnly(path: Path): Unit =
    try
      Files.createFile(
        path,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(CredentialFileAcl.PosixMode))
      )
    catch
      // A filesystem without POSIX attributes (Windows) refuses the attribute:
      // the file is created with the inherited ACL and step ④ is the narrowing
      // step there — declared, not silently assumed; any half-created file is
      // cleared so the retry cannot trip over FileAlreadyExistsException.
      case _: UnsupportedOperationException =>
        try Files.deleteIfExists(path)
        catch case _: Exception => ()
        Files.createFile(path)
  /** `secretsDir` exists AND carries any group/other bit ⇒ too open. A missing
    * directory is not "too open" (the create leg builds it explicitly 0700). */
  private def dirIsTight(p: Path): Boolean =
    try
      if !Files.exists(p) then true
      else
        val perms = Files.getPosixFilePermissions(p).asScala.toSet
        perms == Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE)
    catch case _: Throwable => true // no POSIX view (Windows): the file ladder owns the decision there

  private def fileIsTight(p: Path): Boolean =
    try
      Files.getPosixFilePermissions(p).asScala.toSet ==
        Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    catch case _: Throwable => false // unreadable mode ⇒ NOT proven tight ⇒ fail closed

  private def dirTooOpenDetail: String =
    s"secrets directory permissions are too permissive; refusing to write (path=$secretsDir)"

  /** Best-effort directory narrowing — the DECISION is [[dirIsTight]], never
    * this call (mirrors the `CredentialFileAcl` warn-not-abort caveat). Runs
    * through the injected [[restrictDirectory]] seam; production default is
    * the shared module. */
  private def restrictDirectoryIfPresent(): Unit =
    try if Files.exists(secretsDir.toNIO) then restrictDirectory(secretsDir.toNIO)
    catch case _: Exception => ()

  /** Mode string of `path` for the list face (`0600`-style on POSIX, `None`
    * where the OS has no POSIX view — the mode renders `null`, the badge
    * falls back to the permissive reading). Metadata only. */
  private def modeOf(p: os.Path): Option[String] =
    try
      val perms = Files.getPosixFilePermissions(p.toNIO).asScala.toSet
      def bit(set: Set[PosixFilePermission], ps: PosixFilePermission*): Int =
        if ps.forall(set.contains) then 1 else 0
      val o = bit(perms, PosixFilePermission.OWNER_READ) * 4 + bit(perms, PosixFilePermission.OWNER_WRITE) * 2 +
        bit(perms, PosixFilePermission.OWNER_EXECUTE)
      val g = bit(perms, PosixFilePermission.GROUP_READ) * 4 + bit(perms, PosixFilePermission.GROUP_WRITE) * 2 +
        bit(perms, PosixFilePermission.GROUP_EXECUTE)
      val w = bit(perms, PosixFilePermission.OTHERS_READ) * 4 + bit(perms, PosixFilePermission.OTHERS_WRITE) * 2 +
        bit(perms, PosixFilePermission.OTHERS_EXECUTE)
      Some(s"$o$g$w")
    catch case _: Throwable => None

  /** The `socialChannels.channels` subtree of nebflow.json, `None` when the
    * config is absent or unparseable (the reference scan then simply claims
    * nothing from stored refs; definition-layer names still apply). Reading
    * this file never touches a secret value — the stored side keeps PATHS. */
  private def channelConfig(): Option[Json] =
    try
      val p = PathUtil.configJsonWritePath(dataRoot)
      if !Files.exists(p.toNIO) then None
      else
        io.circe.parser.parse(os.read(p)).toOption.flatMap(
          _.hcursor.downField("socialChannels").downField("channels").focus)
    catch case _: Throwable => None

  private def daemonsConfig(): Option[Json] =
    try
      if !Files.exists(daemonsPath.toNIO) then None
      else io.circe.parser.parse(os.read(daemonsPath)).toOption.flatMap(_.hcursor.downField("daemons").focus)
    catch case _: Throwable => None

  /** A stored `_ref` value is `<rendered root>/secrets/<name>` — the exact
    * string `SocialChannels.persist` writes (`renderRoot` renders `~/.nebflow`
    * on a default install, the absolute root on an isolated one). Match BOTH
    * renderings so an isolated instance's own paths are caught too. */
  private def secretNameOfStoredRef(ref: String): Option[String] =
    val markers = List(
      s"${renderedRoot()}/secrets/",
      s"${PathUtil.dataRoot}/secrets/"
    ).distinct
    markers.collectFirst(Function.unlift(m => if ref.startsWith(m) then Some(ref.drop(m.length)) else None))

  /** Same rendering rule as `SocialChannels.renderRoot`: a default install
    * renders `~/.nebflow`, an isolated root renders its own absolute path. */
  private def renderedRoot(): String =
    if dataRoot == os.home / Branding.homeDirName then s"~/${Branding.homeDirName}"
    else dataRoot.toString

  /** One-line diagnostic: exception CLASS + path, never a message body — the
    * message could theoretically echo request content, and the class name is
    * the whole story the credential protocol needs. */
  private def describe(e: Throwable, name: String): String =
    s"${e.getClass.getSimpleName} (name=$name path=$secretsDir)"

  /** `DaemonPanelStore.secretName` verbatim shape (`DaemonPanelStore.scala:46`,
    * via the same `safeName` sanitization) — the single source of the daemon
    * family's backing-name mapping; instantiated through the shared store so
    * the two faces cannot drift. */
  private def daemonSecretName(id: String, key: String): String =
    new nebflow.core.daemon.DaemonPanelStore(dataRoot).secretName(id, key)

end SecretsStore

object SecretsStore:

  /** Name contract: lowercase start, `[a-z0-9._-]`, 1-64 chars. */
  val NamePattern: java.util.regex.Pattern = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$")
  val MaxLen: Int = 64

  /** Largest accepted value: 64 KiB (超限 400 invalid_value — 防误贴大文件,
    * author ruling ⑦). Checked BEFORE any byte touches the filesystem. */
  val MaxValueBytes: Int = 64 * 1024

  /** Failure vocabulary → the routes' error-code family
    * (`RestApiRoutes` social face, `RestApiRoutes.scala:4211-4231`). Bodies
    * carry the NAME and the code — never a value. */
  enum Failure:
    case InvalidName(name: String, reason: String)
    case InvalidValue(name: String, reason: String)
    case UnknownSecret(name: String)
    case AlreadyExists(name: String)
    case Referenced(name: String, refs: List[String])
    case ManagedNamespace(name: String)
    case SecretMode(name: String, reason: String)
    case Io(reason: String)

end SecretsStore
