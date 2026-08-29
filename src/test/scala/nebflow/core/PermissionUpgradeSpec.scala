package nebflow.core

import munit.FunSuite

/**
 * 递进式放行链 (2026-08-30): permission-card answer escalation parsing.
 *
 * Wire contract under test:
 *   { approved: true, upgradeMode?: "auto-edits" | "auto-all" }
 * Old replies without upgradeMode are byte-compatible; deny replies and
 * non-ladder values must never become upgrades.
 */
class PermissionUpgradeSpec extends FunSuite:

  test("no upgradeMode parses to None — old replies byte-compatible") {
    assertEquals(PermissionUpgrade.parse(approved = true, upgradeMode = None), Right(None))
    assertEquals(PermissionUpgrade.parse(approved = false, upgradeMode = None), Right(None))
  }

  test("allow + auto-edits / auto-all are the only valid escalations") {
    assertEquals(PermissionUpgrade.parse(approved = true, upgradeMode = Some("auto-edits")), Right(Some(SafetyMode.AutoEdits)))
    assertEquals(PermissionUpgrade.parse(approved = true, upgradeMode = Some("auto-all")), Right(Some(SafetyMode.AutoAll)))
  }

  test("deny + upgradeMode is rejected — a deny must not carry an upgrade") {
    val r = PermissionUpgrade.parse(approved = false, upgradeMode = Some("auto-all"))
    assert(r.isLeft, s"expected Left, got $r")
    assert(clue(r).toString.contains("requires approved=true"))
  }

  test("confirm-edits is not an escalation target — ladder only goes up") {
    val r = PermissionUpgrade.parse(approved = true, upgradeMode = Some("confirm-edits"))
    assert(r.isLeft, s"expected Left, got $r")
    assert(clue(r).toString.contains("unknown upgradeMode"))
  }

  test("unknown values are rejected with the valid-target hint") {
    val r = PermissionUpgrade.parse(approved = true, upgradeMode = Some("yolo"))
    assert(r.isLeft, s"expected Left, got $r")
    assert(clue(r).toString.contains("auto-edits, auto-all"))
  }

end PermissionUpgradeSpec
