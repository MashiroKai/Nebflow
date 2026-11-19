package nebflow.cli

import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * `<dataRoot>/.pid.meta` codec + writeFresh rule (phase 2 daemon plan §5,
 * PidMetaSpec). The writeFresh matrix is the crash-leftover defence (R2-6 +
 * F-1): a leftover meta must never outlive its dead pid, and a process may
 * always write meta for its OWN fresh pid.
 *
 * Hermetic: everything under a temp data root (setDataRoot + restore); the
 * one "live foreign pid" fixture is a spec-owned `sleep` process, verified
 * alive and destroyed in a finally.
 */
class PidMetaSpec extends FunSuite:

  private def withTempRoot(f: => Unit): Unit =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "pidmeta-spec-")
    try
      PathUtil.setDataRoot(tmp)
      f
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  // ── codec ──────────────────────────────────────────────────────────────

  test("encode/decode roundtrip keeps every field") {
    val m = PidMeta.Meta(
      startedBy = "launcher",
      mode = Some("daemon"),
      startedAt = Some("2026-10-10T12:00:00Z"),
      pid = Some(12345L),
      cmdlineSha256 = Some("ab" * 32),
      cmdline = Some("java -cp x nebflow.Main start")
    )
    assertEquals(PidMeta.decode(io.circe.parser.parse(PidMeta.encode(m)).toOption.get), Some(m))
  }

  test("decode tolerates the one-line patch form (only startedBy)") {
    val json = io.circe.parser.parse("""{"startedBy":"restart-script"}""").toOption.get
    assertEquals(
      PidMeta.decode(json),
      Some(PidMeta.Meta("restart-script", None, None, None, None, None))
    )
  }

  test("decode rejects JSON without startedBy (callers map to unknown)") {
    val json = io.circe.parser.parse("""{"mode":"daemon"}""").toOption.get
    assertEquals(PidMeta.decode(json), None)
  }

  test("read: missing file ⇒ None; bad JSON ⇒ None") {
    withTempRoot {
      assertEquals(PidMeta.read(), None)
      os.write.over(PathUtil.dataRoot / ".pid.meta", "{not json")
      assertEquals(PidMeta.read(), None)
    }
  }

  // ── writeFresh rule (pure matrix, R2-6 + F-1) ──────────────────────────

  test("writeFreshAllowed: writable ⇔ meta absent ∨ .pid absent ∨ dead ∨ self") {
    val self = 100L
    // meta absent — always writable
    assert(PidMeta.writeFreshAllowed(metaExists = false, pidOpt = None, pidAlive = false, currentPid = self))
    assert(PidMeta.writeFreshAllowed(metaExists = false, pidOpt = Some(999L), pidAlive = true, currentPid = self))
    // .pid absent — writable even with a leftover meta
    assert(PidMeta.writeFreshAllowed(metaExists = true, pidOpt = None, pidAlive = false, currentPid = self))
    // .pid names a dead pid — leftover meta is garbage, overwrite
    assert(PidMeta.writeFreshAllowed(metaExists = true, pidOpt = Some(999L), pidAlive = false, currentPid = self))
    // self-exemption: .pid is my OWN fresh pid (bootGateway wrote it) — writable
    assert(PidMeta.writeFreshAllowed(metaExists = true, pidOpt = Some(self), pidAlive = true, currentPid = self))
    // live foreign pid owns the root — no-clobber
    assert(!PidMeta.writeFreshAllowed(metaExists = true, pidOpt = Some(999L), pidAlive = true, currentPid = self))
  }

  // ── writeFresh integration against real files ──────────────────────────

  test("writeFresh writes when no meta and no .pid exist") {
    withTempRoot {
      PidMeta.writeFresh("launcher", "daemon")
      val m = PidMeta.read()
      assertEquals(m.map(_.startedBy), Some("launcher"))
      assertEquals(m.flatMap(_.mode), Some("daemon"))
      assert(m.flatMap(_.pid).contains(java.lang.ProcessHandle.current().pid()))
      // cmdline is truncated AFTER hashing — sha present, cmdline capped
      assert(m.exists(_.cmdlineSha256.nonEmpty))
      assert(m.exists(_.cmdline.forall(_.length <= 200 + "…(truncated)".length)))
    }
  }

  test("writeFresh overwrites when the .pid pid is dead (crash leftover)") {
    withTempRoot {
      // a spec-owned process that is already gone
      val p = new ProcessBuilder("true").start()
      p.waitFor()
      ProcessManager.writePid(p.pid())
      os.write.over(PathUtil.dataRoot / ".pid.meta", """{"startedBy":"sbt"}""")
      PidMeta.writeFresh("launcher", "daemon")
      assertEquals(PidMeta.read().map(_.startedBy), Some("launcher"))
    }
  }

  test("writeFresh self-exemption applies when .pid already names this JVM (misordered bootGateway)") {
    withTempRoot {
      // simulate writePid happening BEFORE writeFresh: the .pid is OUR OWN
      // live pid — the rule must treat it as self, not a foreign instance.
      ProcessManager.writePid(java.lang.ProcessHandle.current().pid())
      os.write.over(PathUtil.dataRoot / ".pid.meta", """{"startedBy":"sbt"}""")
      PidMeta.writeFresh("launcher", "foreground")
      assertEquals(PidMeta.read().map(_.startedBy), Some("launcher"))
    }
  }

  test("writeFresh no-clobbers a live foreign pid's meta") {
    withTempRoot {
      val foreign = new ProcessBuilder("sleep", "30").start()
      try
        assert(foreign.isAlive)
        ProcessManager.writePid(foreign.pid())
        os.write.over(PathUtil.dataRoot / ".pid.meta", """{"startedBy":"sbt","mode":"foreground"}""")
        PidMeta.writeFresh("launcher", "daemon") // must be a no-op
        assertEquals(PidMeta.read().map(_.startedBy), Some("sbt"))
      finally
        foreign.destroy()
        foreign.waitFor()
    }
  }

  test("removePid deletes BOTH .pid and .pid.meta") {
    withTempRoot {
      PidMeta.writeFresh("launcher", "daemon")
      ProcessManager.writePid(123L)
      assert(os.exists(PathUtil.dataRoot / ".pid"))
      assert(os.exists(PathUtil.dataRoot / ".pid.meta"))
      ProcessManager.removePid()
      assert(!os.exists(PathUtil.dataRoot / ".pid"))
      assert(!os.exists(PathUtil.dataRoot / ".pid.meta"))
    }
  }

end PidMetaSpec
