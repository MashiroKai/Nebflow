package nebflow.core

import java.nio.file.attribute.{
  AclEntry,
  AclEntryPermission,
  AclEntryType,
  AclFileAttributeView,
  PosixFilePermissions,
  UserPrincipal
}
import java.nio.file.{Files, LinkOption, Path}
import java.util.EnumSet

/** Owner-only access control for on-disk credential files (device.json, …).
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
  * injectable `osName` string — branch-selection tests run on any host. The
  * Windows *mechanism* itself is NOT executed anywhere in this batch (Q7); the
  * residual is recorded in
  * `.nebflow/evidence/20260911_sandbox-minimal-set/T3/`.
  */
object CredentialFileAcl:

  /** POSIX mode for credential files: owner read+write only. */
  val PosixMode: String = "rw-------"

  /** OS enforcement port — one method per branch, so a test double can record
    * *which* branch ran (and that the other one did not). */
  trait Port:
    /** POSIX branch: `chmod 600`. */
    def ownerOnlyPosix(path: Path): Unit

    /** Windows branch: replace the DACL with a single owner-only ALLOW ACE. */
    def ownerOnlyWindows(path: Path): Unit

  /** Production port: POSIX mode bits on Unix, DACL on Windows. */
  val systemPort: Port = new Port:
    def ownerOnlyPosix(path: Path): Unit =
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(PosixMode))

    def ownerOnlyWindows(path: Path): Unit =
      val view =
        Files.getFileAttributeView(path, classOf[AclFileAttributeView], LinkOption.NOFOLLOW_LINKS)
      if view == null then
        throw new UnsupportedOperationException(
          s"no ACL attribute view for $path (filesystem without ACL support) — refusing to fall " +
            "back to POSIX permissions, which is a silent no-op on Windows"
        )
      // setAcl REPLACES the DACL: ACEs inherited from the parent directory
      // (profile dir, Everyone, BUILTIN\Users) are not carried over.
      view.setAcl(java.util.List.of(ownerOnlyAce(view.getOwner)))

  /** Permissions of the single ACE the Windows branch installs: read/write data
    * + attributes + synchronise (the `(R,W)` icacls vocabulary). Fresh EnumSet
    * per call — the ACL builders must not share mutable state. */
  private[core] def ownerPermissions: EnumSet[AclEntryPermission] =
    EnumSet.of(
      AclEntryPermission.READ_DATA,
      AclEntryPermission.WRITE_DATA,
      AclEntryPermission.APPEND_DATA,
      AclEntryPermission.READ_ATTRIBUTES,
      AclEntryPermission.WRITE_ATTRIBUTES,
      AclEntryPermission.SYNCHRONIZE
    )

  /** The one ACE the Windows branch installs: ALLOW, owner, read+write, no
    * inheritance flags — it applies to this file only and cannot leak into
    * children. */
  private[core] def ownerOnlyAce(owner: UserPrincipal): AclEntry =
    AclEntry
      .newBuilder()
      .setType(AclEntryType.ALLOW)
      .setPrincipal(owner)
      .setPermissions(ownerPermissions)
      .build()

  /** Live `os.name`; read per call so tests (and `-Dos.name=…`) can override. */
  def currentOsName: String = sys.props.getOrElse("os.name", "")

  /** Branch predicate: Windows-family OS names, case-insensitive — same shape
    * as the other `os.name` checks in the codebase. */
  def isWindows(osName: String): Boolean = osName.toLowerCase.contains("win")

  /** Enforce owner-only access on `path`, choosing the branch by `osName`.
    *
    * Throws on failure; the caller decides whether that is fatal. The
    * credential write path logs a warning instead of aborting — the credential
    * is on disk either way — but it never stays silent (silence was the Q2
    * defect). */
  def restrict(path: Path, osName: String = currentOsName, port: Port = systemPort): Unit =
    if isWindows(osName) then port.ownerOnlyWindows(path) else port.ownerOnlyPosix(path)
end CredentialFileAcl
