package nebflow.core

import java.nio.file.*
import java.nio.file.attribute.*
import java.util.EnumSet

import scala.jdk.CollectionConverters.*

/**
 * Owner-only access control for on-disk credential files (device.json, …).
 *
 * WHY (Q2 ruling, 2026-09-11): `Files.setPosixFilePermissions` is a silent
 * no-op on Windows — the provider has no POSIX view, and the previous call
 * sites swallowed the resulting exception (`catch case _: Exception => ()`).
 * device.json therefore kept the DACL it inherited from the profile
 * directory: principals other than the current user could read the live
 * device token. This module makes the OS branch explicit and total:
 *
 *   - POSIX (macOS/Linux): mode `rw-------` — byte-identical to the previous
 *     behaviour, no change for existing installs.
 *   - Windows: the DACL is REPLACED with a single ALLOW entry for the file
 *     owner, so no inherited `Everyone` / `BUILTIN\Users` / third-party ACE
 *     survives; `icacls <file>` then lists exactly one principal.
 *
 * Scope (this batch = D6-b "narrow only"): file-mode/ACL narrowing only.
 * Credential externalisation (D6-a) and container `denyRead` are out of scope.
 *
 * Testability: macOS/Linux has no NTFS ACL view and no `icacls`, so the OS
 * enforcement sits behind [[Port]] and the branch is chosen from an
 * injectable `osName` string — branch-selection tests run on any host.
 *
 * The Windows *mechanism* was NOT executed anywhere in the original batch
 * (Q7), and that gap is exactly what shipped the missing-EA defect above;
 * `DeviceCredentialAclSpec` T3-R6 now runs it for real on a Windows host.
 * The residual is recorded in
 * sandbox-minimal-set 批 T3 证据集.
 *
 * ── 2026-09-19 (credaacl 批): the residual this file used to carry ──────────
 * `setAcl` was the WHOLE story: nothing ever proved that the ACE just written
 * leaves the owner able to open the file. On 2026-09-18 the DACL on
 * `C:\Users\Kai\.nebflow\neblink\device.json` came out as
 * `D:P(A;;0x100187;;;<owner>)` — protected, ONE owner ACE, and four bits short
 * (`FILE_READ_EA` / `FILE_WRITE_EA` / `READ_CONTROL` / `WRITE_DAC`), so
 * `java.nio`'s own `GENERIC_READ`/`GENERIC_WRITE` open — which asks for those
 * bits — was denied BY THE OWNER'S OWN ACE. The file became unreadable and
 * unwritable to the process that had just written it ("login required"
 * forever, mtime frozen), and there was NO self-healing path: the only exit
 * was an external `icacls` on the machine (kaifla-fix forensics:
 * `.nebflow/evidence/20260919_kaifla-fix/p16…p22`).
 *
 * This module now runs a ladder on the Windows branch — prove usability by a
 * REAL open ([[WindowsAcl.proveUsable]]), repair by rebuilding the file with
 * its content preserved ([[WindowsAcl.repair]]), and fail EXPLICITLY with a
 * `file:line` anchor plus the ACL mask reading when even that does not restore
 * the file ([[AclSelfLockedException]]). Everything in the ladder sits behind
 * [[WindowsAcl]], so the whole defect is reproducible — and its repair
 * verifiable — on a host without ACLs. The POSIX branch is untouched (one
 * call, same exception semantics).
 */
object CredentialFileAcl:

  /** POSIX mode for credential files: owner read+write only. */
  val PosixMode: String = "rw-------"

  /**
   * POSIX mode for credential DIRECTORIES: `rwx------`.
   *
   * A6 (2026-09-20 device-face hardening batch): a directory needs its OWN
   * mode string — [[PosixMode]] (`rw-------`) applied to a directory strips the
   * execute bit, which would make the directory untraversable (the credential
   * inside becomes unreachable for the owner too). Hence a separate constant
   * and a separate port method rather than reusing the file branch.
   */
  val PosixDirMode: String = "rwx------"

  /**
   * OS enforcement port — one method per branch, so a test double can record
   * *which* branch ran (and that the other one did not).
   */
  trait Port:
    /** POSIX branch: `chmod 600`. */
    def ownerOnlyPosix(path: Path): Unit

    /** Windows branch: replace the DACL with a single owner-only ALLOW ACE. */
    def ownerOnlyWindows(path: Path): Unit

    /**
     * POSIX DIRECTORY branch: `chmod 700` (see [[PosixDirMode]]). Defaulted so
     * existing doubles keep compiling; the production port overrides it.
     */
    def ownerOnlyPosixDir(path: Path): Unit =
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(PosixDirMode))

  /** Production port: POSIX mode bits on Unix, DACL on Windows. */
  val systemPort: Port = new Port:
    def ownerOnlyPosix(path: Path): Unit =
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(PosixMode))

    def ownerOnlyWindows(path: Path): Unit =
      installOwnerOnlyAce(aclView(path))

    override def ownerOnlyPosixDir(path: Path): Unit =
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(PosixDirMode))

  /**
   * The ACL view of `path`, or an explicit refusal — a filesystem without ACL
   * support must not silently fall back to POSIX permissions (a no-op on
   * Windows, which is the original Q2 defect).
   */
  private[core] def aclView(path: Path): AclFileAttributeView =
    val view =
      Files.getFileAttributeView(path, classOf[AclFileAttributeView], LinkOption.NOFOLLOW_LINKS)
    if view == null then
      throw new UnsupportedOperationException(
        s"no ACL attribute view for $path (filesystem without ACL support) — refusing to fall " +
          "back to POSIX permissions, which is a silent no-op on Windows"
      )
    view

  /**
   * Install the single owner-only ACE. `setAcl` REPLACES the DACL: ACEs
   * inherited from the parent directory (profile dir, Everyone,
   * BUILTIN\Users) are not carried over.
   */
  private[core] def installOwnerOnlyAce(view: AclFileAttributeView): Unit =
    view.setAcl(java.util.List.of(ownerOnlyAce(view.getOwner)))

  /**
   * Permissions of the single ACE the Windows branch installs: read/write data
   * + attributes + synchronise (the `(R,W)` icacls vocabulary). Fresh EnumSet
   * per call — the ACL builders must not share mutable state.
   *
   * `READ_NAMED_ATTRS` / `WRITE_NAMED_ATTRS` are LOAD-BEARING (2026-09-16):
   * a Windows open that asks for `GENERIC_READ` / `GENERIC_WRITE` — what
   * `java.nio.file` asks for — also asks for `FILE_READ_EA` /
   * `FILE_WRITE_EA`. An ACE carrying only the data/attribute bits above makes
   * Windows deny that open outright, so the ACE locks the OWNER out of the
   * very file it has just written: `~/.nebflow/neblink/device.json` became
   * unreadable AND unwritable to the Nebflow process itself, which then broke
   * every [[nebflow.neblink.DeviceCredential.load]] — enrollment persist,
   * silent re-login, and `GET /api/neblink/status` (HTTP 500) all failed with
   * `AccessDeniedException`. Measured on Windows 11 / JDK 21: the six rights
   * alone ⇒ open denied; + the two EA bits ⇒ open allowed.
   *
   * `READ_ACL` / `WRITE_ACL` are the owner-side security-descriptor rights.
   * Without `WRITE_ACL` a later [[restrict]] cannot rewrite the DACL, and
   * without `READ_ACL` neither `icacls` nor `Get-Acl` can display it — the
   * state in which this defect was not diagnosable in place. Neither bit
   * grants any principal access to the credential's *contents*; the ACE stays
   * a single non-inheriting ALLOW for the owner, so the Q2 goal (no
   * `Everyone` / `BUILTIN\Users` / third-party ACE survives) is unchanged.
   *
   * Public (widened 2026-09-19): this set is the module's EXPECTED-MASK
   * definition, and `DeviceCredentialAclSpec` T4-R1 pins it bit-by-bit against
   * the live-machine readings (0x16019f repaired / 0x100187 locked).
   */
  def ownerPermissions: EnumSet[AclEntryPermission] =
    EnumSet.of(
      AclEntryPermission.READ_DATA,
      AclEntryPermission.WRITE_DATA,
      AclEntryPermission.APPEND_DATA,
      AclEntryPermission.READ_ATTRIBUTES,
      AclEntryPermission.WRITE_ATTRIBUTES,
      AclEntryPermission.READ_NAMED_ATTRS,
      AclEntryPermission.WRITE_NAMED_ATTRS,
      AclEntryPermission.READ_ACL,
      AclEntryPermission.WRITE_ACL,
      AclEntryPermission.SYNCHRONIZE
    )

  /**
   * The one ACE the Windows branch installs: ALLOW, owner, read+write, no
   * inheritance flags — it applies to this file only and cannot leak into
   * children.
   */
  private[core] def ownerOnlyAce(owner: UserPrincipal): AclEntry =
    AclEntry
      .newBuilder()
      .setType(AclEntryType.ALLOW)
      .setPrincipal(owner)
      .setPermissions(ownerPermissions)
      .build()

  /** Live `os.name`; read per call so tests (and `-Dos.name=…`) can override. */
  def currentOsName: String = sys.props.getOrElse("os.name", "")

  /**
   * Branch predicate: Windows-family OS names, case-insensitive — same shape
   * as the other `os.name` checks in the codebase.
   */
  def isWindows(osName: String): Boolean = osName.toLowerCase.contains("win")

  // ───────────────────── 定义层：期望位集合与 mask 读数 ─────────────────────
  //
  // 2026-09-19 (credaacl 批). 「期望权限位集合」与它的 Win32 mask 读数是本模块
  // **可单测的定义层**：判据不靠平台探测，靠位集合 + 读数比对，在任意宿主都能跑。

  /**
   * Win32 access-mask bit of every [[AclEntryPermission]] — the SAME vocabulary
   * the kaifla-fix forensics read off the live Windows machine
   * (`.nebflow/evidence/20260919_kaifla-fix/p19_acl_repair.txt`,
   * `p20_acl_converge.txt`):
   *
   *   - locked form  : SDDL `D:P(A;;0x100187;;;<owner>)`, icacls `(S,RD,WD,AD,RA,WA)`
   *   - repaired form: icacls `(S,RD,WD,AD,RA,WA,REA,WEA,RC,WDAC)` = `0x16019f`
   *
   * Both literals are pinned in `DeviceCredentialAclSpec` T4-R1, so this table
   * cannot drift away from the machine evidence unnoticed.
   */
  private[core] val Win32Bit: Map[AclEntryPermission, Long] = Map(
    AclEntryPermission.READ_DATA -> 0x000001L, // FILE_READ_DATA
    AclEntryPermission.WRITE_DATA -> 0x000002L, // FILE_WRITE_DATA
    AclEntryPermission.APPEND_DATA -> 0x000004L, // FILE_APPEND_DATA
    AclEntryPermission.READ_NAMED_ATTRS -> 0x000008L, // FILE_READ_EA
    AclEntryPermission.WRITE_NAMED_ATTRS -> 0x000010L, // FILE_WRITE_EA
    AclEntryPermission.EXECUTE -> 0x000020L, // FILE_EXECUTE
    AclEntryPermission.DELETE_CHILD -> 0x000040L, // FILE_DELETE_CHILD
    AclEntryPermission.READ_ATTRIBUTES -> 0x000080L, // FILE_READ_ATTRIBUTES
    AclEntryPermission.WRITE_ATTRIBUTES -> 0x000100L, // FILE_WRITE_ATTRIBUTES
    AclEntryPermission.DELETE -> 0x010000L, // DELETE
    AclEntryPermission.READ_ACL -> 0x020000L, // READ_CONTROL
    AclEntryPermission.WRITE_ACL -> 0x040000L, // WRITE_DAC
    AclEntryPermission.WRITE_OWNER -> 0x080000L, // WRITE_OWNER
    AclEntryPermission.SYNCHRONIZE -> 0x100000L // SYNCHRONIZE
  )

  /**
   * The permission set the Windows ladder DEMANDS of the file it just wrote
   * (identical to the set [[ownerOnlyAce]] installs — one definition, two uses).
   */
  def expectedBits: Set[AclEntryPermission] = ownerPermissions.asScala.toSet

  /**
   * Win32 mask of a permission set: `maskOf(ownerPermissions) == 0x16019f`, the
   * repaired form measured on the live Windows host.
   */
  def maskOf(perms: Iterable[AclEntryPermission]): Long =
    perms.foldLeft(0L)((acc, p) => acc | Win32Bit.getOrElse(p, 0L))

  /** Inverse of [[maskOf]]: the permission set a mask literal carries. */
  def bitsOf(mask: Long): Set[AclEntryPermission] =
    Win32Bit.collect { case (p, b) if (mask & b) != 0 => p }.toSet

  /**
   * A Win32-mask reading of a file's DACL: the **only** currency this module
   * emits in diagnostics (§七 凭据协议 / kaifla-fix p7 教训 — field names,
   * shapes, paths and ACL bit sets; 🔴 never file contents, never token values).
   *
   * `entries` / `nonOwnerAces` report the DACL's *scope* (Q2 goal = exactly one
   * non-inheriting owner ACE); they are reported, not judged — the verdict is
   * the bit set plus the real open.
   */
  final case class AclMask(mask: Long, bits: Set[AclEntryPermission], entries: Int, nonOwnerAces: Int):

    /** Bits `expected` demands but this ACL does not carry. */
    def missing(expected: Set[AclEntryPermission]): Set[AclEntryPermission] = expected -- bits

    /**
     * `false` when the ACL holds no owner ACE at all (mask 0 — the shape a DACL
     * for a different principal reads as).
     */
    def hasOwnerAce: Boolean = bits.nonEmpty

    /**
     * `DELETE` is deliberately NOT part of the expected set (bit-identical to the
     * upstream-verified repair mask 0x16019f), yet the rebuild rung's swap and
     * `clear`'s rename-aside both need it — so it is REPORTED, not demanded.
     */
    private def deleteReading: String =
      if bits.contains(AclEntryPermission.DELETE) then "delete=present"
      else "delete=ABSENT(reported, not demanded: rename/replace needs it)"

    def render: String = renderAgainst(CredentialFileAcl.expectedBits)

    def renderAgainst(expected: Set[AclEntryPermission]): String =
      val fmt = f"0x$mask%06x"
      val exp = f"0x${maskOf(expected)}%06x"
      s"mask=$fmt bits=[${AclMask.names(bits)}] missing=[${AclMask.names(missing(expected))}] " +
        s"expected=$exp aces=$entries nonOwner=$nonOwnerAces $deleteReading"

  end AclMask

  object AclMask:

    /**
     * Pure construction from a mask literal — tests model the locked form with
     * `AclMask.of(0x100187L)`, the reading kaifla-fix took off the live file.
     */
    def of(mask: Long, entries: Int = 1, nonOwnerAces: Int = 0): AclMask =
      AclMask(mask, bitsOf(mask), entries, nonOwnerAces)

    def names(ps: Set[AclEntryPermission]): String =
      ps.toList.map(_.name).sorted.mkString(",")

  // ─────────────── 执行层：自检 / 重建梯（probe·repair 端口） ───────────────

  /**
   * A rebuilt, correctly-ACL'd copy that could NOT be swapped into place — the
   * honest degradation of the rebuild rung. `copy` + `why` are path/reading
   * only, never content.
   */
  final case class Salvage(copy: String, why: String)

  /**
   * Rung 2's outcome: `Repaired` = `path` itself is usable again; `Salvaged` =
   * only a rebuilt copy could be produced (the swap was refused).
   */
  enum RepairOutcome:
    case Repaired
    case Salvaged(salvage: Salvage)

  /**
   * Windows probe/repair port (2026-09-19, credaacl 批) — everything the ladder
   * needs *beyond* installing the ACE. Everything platform-shaped sits here, so
   * the whole self-lock defect is reproducible, and its repair verifiable, in a
   * unit test on a host with no ACLs and no Windows.
   *
   * Branch selection itself stays driven by the injected `osName`
   * ([[restrict]]): 🔴 no platform sniffing hides inside an untestable implicit
   * branch.
   */
  trait WindowsAcl:

    /**
     * Rung 1/3 — the REAL usability proof. MUST open the file the way
     * `java.nio.file` itself does for a read/write handle (READ + WRITE, which
     * carries the EA bits and `READ_CONTROL`), so a deficient ACE denies it
     * exactly as it denied `DeviceCredential`; a permission-bit *query* would
     * not reproduce the defect. Implementations must close the handle on every
     * path (no fd leak) and must throw when the owner is locked out.
     */
    def proveUsable(path: Path): Unit

    /**
     * Structural reading of `path`'s DACL. `None` = this host exposes no ACL
     * view (POSIX filesystem) — the reading is then reported as *unavailable*,
     * never as clean.
     */
    def readAcl(path: Path): Option[AclMask]

    /**
     * Rung 2 — repair, content preserved. Must first try the cheap in-place
     * path (re-apply the correct ACE) and only then rebuild the file; when the
     * rebuilt copy cannot be swapped into place it must say so
     * ([[RepairOutcome.Salvaged]]) instead of pretending success.
     */
    def repair(path: Path): RepairOutcome

  end WindowsAcl

  /** Production probe/repair steps (the Windows mechanism face). */
  val systemWindowsAcl: WindowsAcl = new WindowsAcl:

    def proveUsable(path: Path): Unit =
      // REAL open. `Files.newByteChannel(READ, WRITE)` maps to a Windows open
      // asking for GENERIC_READ|GENERIC_WRITE ⇒ FILE_READ_DATA|FILE_WRITE_DATA|
      // FILE_APPEND_DATA|FILE_READ_EA|FILE_WRITE_EA|FILE_READ_ATTRIBUTES|
      // FILE_WRITE_ATTRIBUTES|READ_CONTROL|SYNCHRONIZE — the request that the
      // 0x100187 ACE denies. No TRUNCATE/CREATE flag: an existing file's content
      // and mtime are left alone, and a missing file is NoSuchFileException
      // (never silently created).
      val handle = Files.newByteChannel(
        path,
        java.util.EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
      )
      try ()
      finally handle.close() // 🔴 no fd leak on any path

    def readAcl(path: Path): Option[AclMask] =
      val view =
        Files.getFileAttributeView(path, classOf[AclFileAttributeView], LinkOption.NOFOLLOW_LINKS)
      if view == null then None
      else
        val owner = view.getOwner
        val acl = view.getAcl.asScala.toList
        // The ACE this module installs is the single ALLOW entry for the owner;
        // a DACL carrying no such entry reads as mask 0 (⇒ every expected bit
        // reported missing, which is the truthful reading).
        val ownerAce = acl.find(e => e.`type` == AclEntryType.ALLOW && e.principal == owner)
        val bits = ownerAce.map(_.permissions.asScala.toSet).getOrElse(Set.empty[AclEntryPermission])
        Some(AclMask(maskOf(bits), bits, acl.size, acl.count(_.principal != owner)))

    def repair(path: Path): RepairOutcome =
      // ── Rung 2a: re-apply the correct ACE to the file ITSELF ────────────────
      // Chosen first because it is the rung that actually cures the observed
      // defect and costs no content movement: on Windows the file OWNER holds
      // implicit WRITE_DAC/READ_CONTROL on the object regardless of its DACL, so
      // this succeeds in the exact state where every *data* access (reads
      // included) is denied — which is why the machine could only be rescued by
      // an external `icacls` before this ladder existed.
      installOwnerOnlyAce(aclView(path))
      if usable(path) then RepairOutcome.Repaired
      else
        // ── Rung 2b: content-preserving rebuild ──────────────────────────────
        // Form chosen: read the bytes, write them into a SIBLING file in the same
        // directory, give that copy the correct ACE, then swap it in with a
        // REPLACE_EXISTING move. Why this shape: (1) content is never written in
        // place, so a failure cannot leave a half-written credential; (2) the
        // sibling lives in the same directory ⇒ same volume ⇒ the swap is a
        // rename, not a copy; (3) the ACE is installed on the copy BEFORE it
        // becomes the credential, so the credential never exists in a
        // not-yet-narrowed state.
        //
        // 🔴 What is NOT feasible in the self-locked state, and why: reading the
        // bytes needs READ_DATA, and the swap needs DELETE on the target (or
        // FILE_DELETE_CHILD on the parent) — both of which a deficient ACE denies
        // (the observed 0x100187 form lacks DELETE as well). Those steps are
        // therefore allowed to fail; the failure is reported with its reason
        // instead of being papered over (see the Salvaged branch below).
        val bytes = Files.readAllBytes(path)
        val copy = path.resolveSibling(
          s"${path.getFileName}.aclrebuild-${CredentialFileAcl.timestamp()}"
        )
        Files.write(copy, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        installOwnerOnlyAce(aclView(copy))
        try
          Files.move(copy, path, StandardCopyOption.REPLACE_EXISTING)
          RepairOutcome.Repaired
        catch
          case e: Exception =>
            // Honest degradation: the rebuilt, correctly-ACL'd copy KEEPS the
            // content and stays next to the locked original (nothing is deleted,
            // nothing pretends to have succeeded); the swap reason is reported so
            // the operator can finish the swap by hand.
            RepairOutcome.Salvaged(Salvage(copy.toString, describe(e)))
      end if
    end repair

    /**
     * In-rung usability shortcut (the ladder re-runs the full verdict anyway, so
     * a false negative here only costs the rebuild attempt, never a wrong verdict).
     */
    private def usable(path: Path): Boolean =
      try
        proveUsable(path)
        true
      catch case _: Exception => false

  /**
   * The Windows enforcement ladder: prove → repair → prove again → explicit
   * failure. This is the residual defect's fix — before 2026-09-19 `setAcl` was
   * the whole story, so a deficient ACE "succeeded" and locked the owner out
   * with no self-healing path.
   *
   * `expected` is injectable so the judgment itself is unit-testable.
   */
  final class WindowsLadder(val acl: WindowsAcl, val expected: Set[AclEntryPermission]):

    /**
     * Rung 0 (installing the ACE) belongs to [[Port]]; this runs rungs 1-4.
     *
     * Throws [[AclSelfLockedException]] — a locked file is never reported as a
     * success, and the throw carries the diagnosis (site anchor + mask reading
     * + what the repair managed).
     */
    def enforce(path: Path): Unit =
      verdict(path) match
        case None => () // rung 1 holds: usable, no repair attempted
        case Some(first) =>
          val repair =
            try
              acl.repair(path) match
                case RepairOutcome.Repaired => "repaired"
                case RepairOutcome.Salvaged(s) =>
                  s"salvaged(copy=${s.copy} why=${s.why})"
            catch case e: Exception => s"refused(${describe(e)})"
          verdict(path) match
            case None => () // rung 3 holds: the repair restored usability
            case Some(last) =>
              val site = anchorHere
              throw AclSelfLockedException(
                path,
                acl.readAcl(path),
                site,
                s"owner-only DACL left the owner locked out and the repair rung did not " +
                  s"restore it (site=$site path=$path acl=${reading(path)} repair=$repair) " +
                  s"first=[$first] last=[$last]"
              )

    /**
     * Rung 1 / rung 3. `None` = the owner really can use the file.
     *
     * Order matters: the REAL open runs first (it is the ground truth the defect
     * was measured with — it is what `DeviceCredential` does), then the
     * structural bit check. The structural check is consulted only when the host
     * exposes an ACL view; it adds the one case the open cannot see — a missing
     * `WRITE_ACL`, which no open requests but every future DACL rewrite needs.
     */
    private def verdict(path: Path): Option[String] =
      open(path) match
        case Opened.Usable => structural(path)
        case Opened.Absent => None
        case Opened.LockedOut(why) => Some(s"$why — ${reading(path)}")

    /**
     * `Absent` is an explicit, documented no-op rather than a silent pass: a path
     * that does not exist has no DACL and no content, so there is nothing that
     * can be self-locked ([[restrict]] is called on files that have just been
     * written; a missing file is not this ladder's business).
     */
    private def open(path: Path): Opened =
      try
        acl.proveUsable(path)
        Opened.Usable
      catch
        case _: java.nio.file.NoSuchFileException => Opened.Absent
        case e: Exception => Opened.LockedOut(s"open ${describe(e)}")

    private def structural(path: Path): Option[String] =
      readAcl(path) match
        case Right(Some(m)) if m.missing(expected).nonEmpty =>
          Some(s"owner ACE deficient although the open succeeded — ${m.renderAgainst(expected)}")
        case _ => None // bits complete, or no ACL view / unreadable: nothing to judge here

    /**
     * Mask reading for the diagnosis: rendered, *unavailable* and *unreadable*
     * are three distinct readings — none of them is reported as clean.
     */
    private def reading(path: Path): String =
      readAcl(path) match
        case Right(Some(m)) => m.renderAgainst(expected)
        case Right(None) => "unavailable(host exposes no ACL view)"
        case Left(why) => why

    /**
     * A reading error is diagnostic information, not a verdict: it can never by
     * itself fail a file the real open has already proven usable.
     */
    private def readAcl(path: Path): Either[String, Option[AclMask]] =
      try Right(acl.readAcl(path))
      catch case e: Exception => Left(s"unreadable(${describe(e)})")

  end WindowsLadder

  private enum Opened:
    case Usable
    case Absent
    case LockedOut(why: String)

  /** Production ladder: the real open, the real reading, the real rebuild. */
  val systemLadder: WindowsLadder = new WindowsLadder(systemWindowsAcl, expectedBits)

  /**
   * Explicit, diagnosable self-lock failure — the failure adjudication of the
   * ladder. The message carries (a) a `file:line` anchor of the failing site,
   * (b) the ACL mask reading of the file (mask hex + bit set +
   * missing-vs-expected diff), (c) what the repair rung managed. It never
   * carries file contents or token values (§七 凭据协议 / kaifla-fix p7 教训) —
   * paths and bit sets only.
   *
   * An `IOException` on purpose: the credential write path treats ACL failures
   * as non-fatal-but-loud (the credential is already on disk) and catches
   * `Exception` there.
   */
  final class AclSelfLockedException(
    val path: Path,
    val reading: Option[AclMask],
    val site: String,
    message: String
  ) extends java.io.IOException(message)

  object AclSelfLockedException:

    def apply(
      path: Path,
      reading: Option[AclMask],
      site: String,
      message: String
    ): AclSelfLockedException =
      new AclSelfLockedException(path, reading, site, message)

  /**
   * `file:line` anchor of the calling site — derived from the LIVE stack frame,
   * so it cannot drift when lines move above it (the diagnosis must be
   * actionable on the failing machine, where the source is a checkout).
   */
  private[core] def anchorHere: String =
    new Throwable().getStackTrace.toList
      .dropWhile(_.getMethodName == "anchorHere")
      .find(_.getFileName == "CredentialFileAcl.scala")
      .map(f => s"${f.getFileName}:${f.getLineNumber}")
      .getOrElse("CredentialFileAcl.scala:?")

  /**
   * One-line, content-free description of a throwable (class + message). Every
   * message this module can see is a path or a JDK ACL complaint — never a
   * credential value.
   */
  private[core] def describe(e: Throwable): String =
    s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"

  /**
   * Timestamp tail for the rebuild's sibling copy (same shape as the
   * rename-aside suffix the store's self-heal uses).
   */
  private def timestamp(): String =
    java.time.format.DateTimeFormatter
      .ofPattern("yyyyMMdd-HHmmss-SSS")
      .withZone(java.time.ZoneId.systemDefault())
      .format(java.time.Instant.now())

  /**
   * Enforce owner-only access on `path`, choosing the branch by `osName`.
   *
   * Throws on failure; the caller decides whether that is fatal. The
   * credential write path logs a warning instead of aborting — the credential
   * is on disk either way — but it never stays silent (silence was the Q2
   * defect).
   *
   * Windows = a LADDER, not a single call: install the ACE
   * ([[Port.ownerOnlyWindows]]), then prove by a real open that the owner can
   * still use the file, repair by rebuilding it when not, and only then fail —
   * with a `file:line` anchor and the ACL mask reading
   * ([[AclSelfLockedException]]). POSIX is unchanged: one call, `chmod 600`,
   * same exception semantics.
   */
  def restrict(
    path: Path,
    osName: String = currentOsName,
    port: Port = systemPort,
    ladder: WindowsLadder = systemLadder
  ): Unit =
    if isWindows(osName) then
      port.ownerOnlyWindows(path)
      ladder.enforce(path)
    else port.ownerOnlyPosix(path)

  /**
   * Leave no `group`/`other` bit set — i.e. this directory is ALREADY
   * owner-only and narrowing it would be a no-op. Read-only probe; a filesystem
   * without a POSIX view (Windows) throws, and the caller treats that as
   * "nothing to do here" (see [[restrictDirectory]]).
   */
  private[core] def needsNarrowing(dir: Path): Boolean =
    val perms = Files.getPosixFilePermissions(dir)
    // Compare by ENUM CONSTANT, not by name string: `PosixFilePermission` is a
    // Java enum, so the only name accessor is `Enum.name()` — matching on
    // `OWNER_*` is both type-safe and immune to a future constant rename.
    !perms.asScala.forall(p =>
      p == PosixFilePermission.OWNER_READ ||
        p == PosixFilePermission.OWNER_WRITE ||
        p == PosixFilePermission.OWNER_EXECUTE
    )

  /**
   * Owner-only access on the DIRECTORY that holds credential files — the
   * "目录兜底" half of A6 (2026-09-20 device-face hardening batch).
   *
   * WHY a directory step at all: the file bits stop a reader who KNOWS the
   * path, but a world-readable directory still lets any local principal
   * enumerate which credential files exist (and `device-profiles.json` next to
   * them is deliberately not owner-only). Measured state that motivated it:
   * `~/.nebflow/neblink` was `0755` while `secrets/` was already `0700`.
   *
   * Scope, stated explicitly (report §申报): POSIX only — the Windows
   * DIRECTORY DACL face is NOT touched by this batch (the file-level ladder in
   * [[restrict]] is file-shaped: it "repairs" by rebuilding the node, which is
   * meaningless for a directory). On Windows this is a no-op. Idempotent:
   * already-owner-only directories are left alone.
   *
   * Throws on failure; the caller decides whether that is fatal (the write path
   * warns, never silently swallows — the Q2 lesson).
   */
  def restrictDirectory(
    dir: Path,
    osName: String = currentOsName,
    port: Port = systemPort
  ): Unit =
    if isWindows(osName) then ()
    else if Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && needsNarrowing(dir) then port.ownerOnlyPosixDir(dir)
end CredentialFileAcl
