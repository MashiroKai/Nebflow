package nebflow.dropbox

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/**
 * P0 wtmove — regression spec for "`os.move(os.pwd)` relocates the node's working directory".
 *
 * `DropboxService.commitTempFile` used to resolve `os.Path(t.tempPath, os.pwd)`.
 * With a blank `tempPath` that IS `os.pwd`, so a file-complete whose temp file was
 * never recorded (relay direct delivery) or never found (restart rebuild)
 * unconditionally renamed the JVM's working directory into `~/Downloads/<fileName>`
 * — entire worktrees relocated out from under live JVMs (21 recorded cases,
 * 2026-09-10..12; one of them is still sitting in ~/Downloads as a full checkout).
 *
 * ## Isolation (the hardest constraint of this batch)
 *
 * The parent JVM is the sbt runner: **its cwd is the worktree**. So the parent is
 * *orchestration and assertion only* — it never calls the code under test. Every
 * filesystem-touching call happens in a **child JVM** launched with
 * `cwd = <tmp>/<case>/victim` and `-Duser.home=<tmp>/<case>/home`, which makes the
 * Downloads target disposable too. A red run therefore relocates a throwaway
 * directory and nothing else (the child additionally refuses to run unless both
 * cwd and user.home sit inside the sandbox — see `WtMoveGuardProbe`).
 *
 * ## Red/green
 *
 * The seam is reflection (`getDeclaredMethod(...).setAccessible(true)`) on the
 * *unchanged* method names `commitTempFile` / `deleteTempFile`, so the identical
 * spec compiles and runs against pre-fix and fixed code: red on the old code
 * (victim directory gone, moved directory present in the temp Downloads), green on
 * the new. `WtMoveGuardProbe` carries the version-agnostic shims
 * (`tempPath: String` vs `Option[String]`, `IO[Unit]` vs `IO[TempPathDecision]`).
 *
 * Run:
 * {{{
 *   sbt "testOnly *WtMoveGuardSpec"     # self-contained
 *   # or, if the child classpath cannot be derived, supply it explicitly:
 *   #   CP=$(sbt -batch --error 'export Test/fullClasspath')
 *   #   WTMOVE_CHILD_CLASSPATH="$CP" sbt "testOnly *WtMoveGuardSpec"
 * }}}
 */
class WtMoveGuardSpec extends CatsEffectSuite:

  private val keepSandbox = sys.props.get("wtmove.keepSandbox").contains("1")

  private val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString

  // --- child classpath (the child needs target/classes + test-classes + every dep) ---

  private def codeSourceOf(className: String): Option[String] =
    try
      val cls = Class.forName(className, false, getClass.getClassLoader)
      for
        pd <- Option(cls.getProtectionDomain)
        cs <- Option(pd.getCodeSource)
      yield Paths.get(cs.getLocation.toURI).toString
    catch case _: Throwable => None

  private val sentinelClasses: Seq[String] = Seq(
    // project output (main + test)
    classOf[DropboxService].getName,
    WtMoveGuardProbe.getClass.getName,
    // deps reachable from the touched code (each anchors one jar)
    "os.Source",
    "geny.Generator",
    "cats.effect.IO",
    "cats.effect.std.Dispatcher",
    "cats.effect.unsafe.IORuntime",
    "cats.Monad",
    "cats.kernel.Eq",
    "fs2.Stream",
    "scodec.bits.ByteVector",
    "io.circe.Json",
    "io.circe.JsonNumber",
    "org.typelevel.jawn.Facade",
    "org.slf4j.Logger",
    "ch.qos.logback.classic.Logger",
    "ch.qos.logback.core.Appender",
    "scala.runtime.BoxedUnit",
    "scala.deriving.Mirror"
  )

  /** Every entry a URLClassLoader in this JVM's loader chain exposes (child-first order). */
  private def urlChain(): (Seq[String], Seq[String]) =
    def loop(cl: ClassLoader): List[ClassLoader] =
      if cl == null then Nil else cl :: loop(cl.getParent)
    val loaders = loop(getClass.getClassLoader)
    val urlLoaders = loaders.collect { case u: java.net.URLClassLoader => u }
    val urls = urlLoaders.flatMap(_.getURLs.toSeq).map(u => Paths.get(u.toURI).toString)
    (urls, loaders.map(_.getClass.getName))

  private def sentinelJars: Seq[String] =
    sentinelClasses.flatMap(codeSourceOf).distinct

  /**
   * How the child's classpath was resolved: `env` | `prop` | `url-chain` | `sentinels`.
   *
   * `java.class.path` is deliberately NOT trusted first: under sbt it is the
   * launcher's own classpath, whose Scala 2.12 `scala-library` would shadow the
   * project's 2.13/3.5 libraries and break the child with `NoSuchMethodError`.
   * The loader chain (the actual test classpath) is child-first, so it wins.
   */
  private lazy val childClasspathResolved: (String, String) =
    sys.env.get("WTMOVE_CHILD_CLASSPATH").filter(_.nonEmpty).map(cp => (cp, "env"))
      .orElse(sys.props.get("wtmove.child.classpath").filter(_.nonEmpty).map(cp => (cp, "prop")))
      .orElse {
        val (urls, _) = urlChain()
        Option.when(urls.exists(_.contains("scala3-library")))((urls.mkString(java.io.File.pathSeparator), "url-chain"))
      }
      .orElse {
        val jars = sentinelJars
        Option.when(jars.nonEmpty)((jars.mkString(java.io.File.pathSeparator), "sentinels"))
      }
      .getOrElse((sys.props.getOrElse("java.class.path", ""), "java.class.path-fallback"))

  private def childClasspath: String = childClasspathResolved._1
  private def classpathSource: String = childClasspathResolved._2

  // --- sandbox layout ---

  private case class Case(
    id: String,
    root: Path,   // <tmp>/wtmove-<id>-XXXX
    victim: Path, // child cwd
    home: Path,   // -Duser.home; the Downloads target is home/Downloads
    tmpIn: Path,  // holds the "real" temp file for the cases that need one
    tempArg: String // what the child puts into FileTransfer.tempPath
  ):
    def downloads: Path = home.resolve("Downloads")
    def log: Path = root.resolve("child.log")

  private val logbackXml =
    """<configuration>
      |  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      |    <encoder><pattern>%-5level %logger{0} - %msg%n</pattern></encoder>
      |  </appender>
      |  <logger name="nebflow" level="WARN"/>
      |  <root level="WARN"><appender-ref ref="CONSOLE"/></root>
      |</configuration>
      |""".stripMargin

  /**
   * Fresh sandbox on disk — created outside the parent's cwd, never inside it.
   *
   * `seedVictim` plants a small tree in the victim directory so that a pre-fix run
   * (which renames the whole working directory away) shows the *content* travelling
   * with it — the sandbox counterpart of the full checkout that ended up in
   * `~/Downloads/a.txt`. It is switched off only where the case needs an empty
   * working directory (`delete-cwd`, where the pre-fix code deletes it outright).
   */
  private def makeCase(
    id: String,
    tempArgOf: Case => String,
    homeInsideVictim: Boolean,
    victimUnder: Option[String],
    seedVictim: Boolean = true
  ): Case =
    val root = Files.createTempDirectory(s"wtmove-$id-").toRealPath()
    val anchor = victimUnder.map(root.resolve).getOrElse(root)
    val victim = anchor.resolve("victim")
    val home = if homeInsideVictim then victim.resolve("home") else root.resolve("home")
    val tmpIn = root.resolve("tmpin")
    Files.createDirectories(victim)
    Files.createDirectories(home.resolve("Downloads"))
    Files.createDirectories(tmpIn)
    if seedVictim then
      Files.write(victim.resolve("AGENTS.md"), "node workspace marker".getBytes(StandardCharsets.UTF_8))
      Files.createDirectories(victim.resolve("src"))
      Files.write(victim.resolve("src").resolve("main.scala"), "// node source".getBytes(StandardCharsets.UTF_8))
      Files.createDirectories(victim.resolve("target"))
      Files.write(victim.resolve("target").resolve("built.jar"), Array[Byte](1, 2, 3))
    val c = Case(id, root, victim, home, tmpIn, "")
    c.copy(tempArg = tempArgOf(c))

  /** Launch the child JVM: cwd = victim, user.home = disposable home, output to a file. */
  private def runChild(c: Case): (Int, String) =
    val cmd = new java.util.ArrayList[String]()
    cmd.add(javaBin)
    cmd.add(s"-Duser.home=${c.home}")
    cmd.add(s"-Dlogback.configurationFile=${c.root.resolve("logback.xml")}")
    cmd.add("-Dfile.encoding=UTF-8")
    cmd.add("-cp")
    cmd.add(childClasspath)
    cmd.add("nebflow.dropbox.WtMoveGuardProbe")
    cmd.add(c.id)
    cmd.add(c.root.toString)
    cmd.add(if c.tempArg.isEmpty then "-" else c.tempArg)
    val pb = new ProcessBuilder()
    pb.command(cmd)
    pb.directory(c.victim.toFile)
    pb.redirectErrorStream(true)
    pb.redirectOutput(c.log.toFile)
    val proc = pb.start()
    val finished = proc.waitFor(120, TimeUnit.SECONDS)
    if !finished then
      proc.destroyForcibly()
      fail(s"[${c.id}] child JVM did not finish in 120s; partial log:\n${readLog(c)}")
    (proc.exitValue(), readLog(c))

  private def readLog(c: Case): String =
    if Files.exists(c.log) then new String(Files.readAllBytes(c.log), StandardCharsets.UTF_8) else "<no child log>"

  private def entriesOf(dir: Path): List[String] =
    if !Files.isDirectory(dir) then Nil
    else
      val s = Files.list(dir)
      try s.iterator().asScala.map(_.getFileName.toString).toList.sorted
      finally s.close()

  private def probeLine(out: String, key: String): Option[String] =
    out.linesIterator.find(_.startsWith(s"[probe] $key=")).map(_.stripPrefix(s"[probe] $key="))

  private def probeField(out: String, key: String): String =
    probeLine(out, key).getOrElse(fail(s"child output has no `[probe] $key=` line:\n$out"))

  /** Always printed, so the run log itself carries the discriminating facts. */
  private def layout(c: Case, out: String): String =
    s"layout[${c.id}] root=${c.root}" +
      s" victim=${if Files.exists(c.victim) then "PRESENT" else "GONE"}" +
      s" downloads=${entriesOf(c.downloads).mkString("[", ", ", "]")}" +
      s" tmpin=${entriesOf(c.tmpIn).mkString("[", ", ", "]")}" +
      s" probe=${probeLine(out, "outcome").orElse(probeLine(out, "rebuildTempPath")).getOrElse("?")}"

  /** Plain NIO recursive delete — the sandbox holds moved directories on a red run. */
  private def deleteRecursively(p: Path): Unit =
    if Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) then
      val s = Files.list(p)
      try s.iterator().asScala.foreach(deleteRecursively)
      finally s.close()
      Files.deleteIfExists(p)
    else Files.deleteIfExists(p)

  private def cleanup(c: Case): Unit =
    if keepSandbox then ()
    else
      try deleteRecursively(c.root)
      catch case _: Throwable => ()

  /**
   * Orchestrate one case: run the child, then assert on the **filesystem** (the only
   * thing that can be "red" pre-fix) plus the child's WARN log. `extra` runs before
   * cleanup, for case-specific assertions.
   */
  private def check(
    c: Case,
    expectDownloads: List[String] = Nil,
    expectTmpIn: List[String] = Nil,
    expectWarn: Option[String] = None,
    expectField: Option[(String, String)] = None,
    extra: Case => Unit = _ => ()
  ): IO[Unit] =
    IO.blocking {
      Files.write(c.root.resolve("logback.xml"), logbackXml.getBytes(StandardCharsets.UTF_8))
      val (code, out) = runChild(c)
      val report = layout(c, out)
      println(s"[wtmove-spec] $report")
      try
        assertEquals(code, 0, s"[${c.id}] child JVM failed:\n$out")
        // the child must really have run with the sandbox as its working directory —
        // otherwise a "green" result would prove nothing (and a red run could not be
        // trusted not to have touched something real).
        assert(
          probeField(out, "cwd").startsWith(c.root.toString),
          s"[${c.id}] child cwd is not inside the sandbox — refusing to trust this run:\n$out"
        )
        assert(out.contains("[probe] done"), s"[${c.id}] child did not complete:\n$out")
        assert(Files.exists(c.victim), s"[${c.id}] the working directory was moved/deleted — $report\n$out")
        assertEquals(entriesOf(c.downloads), expectDownloads, s"[${c.id}] Downloads contents wrong — $report\n$out")
        assertEquals(entriesOf(c.tmpIn), expectTmpIn, s"[${c.id}] temp-input contents wrong — $report\n$out")
        expectField.foreach { case (k, v) =>
          assertEquals(probeField(out, k), v, s"[${c.id}] probe field $k wrong — $report\n$out")
        }
        expectWarn.foreach(w =>
          assert(out.contains(w), s"[${c.id}] expected a logged WARN containing `$w` (the no-op must be visible):\n$out")
        )
        extra(c)
      finally cleanup(c)
    }

  // ===== 0 : the child JVM must actually start (classpath preflight) =====

  test("0 child JVM launches with the resolved classpath") {
    // A failure here is a scaffolding problem, not a code-under-test problem: the
    // message carries the child's log so the missing entry is visible immediately.
    val c = makeCase("ping", _ => "", homeInsideVictim = false, victimUnder = None)
    println(
      s"[wtmove-spec] child classpath source=$classpathSource entries=${childClasspath.split(java.io.File.pathSeparator).length}"
    )
    check(c).handleErrorWith(e =>
      IO.blocking {
        val hint =
          "if the failure is ClassNotFound/NoClassDefFound, re-run with an explicit child classpath:\n" +
            "  CP=$(sbt -batch --error 'export Test/fullClasspath')\n" +
            "  WTMOVE_CHILD_CLASSPATH=\"$CP\" sbt \"testOnly *WtMoveGuardSpec\"\n" +
            s"resolved source=$classpathSource entries=${childClasspath.split(java.io.File.pathSeparator).length}"
        throw new AssertionError(s"$hint\n${e.getMessage}", e)
      }
    )
  }

  // ===== ①③④ : a temp path reaching the working directory must be a no-op =====

  test("① tempPath is the empty string ⇒ zero filesystem change (pre-fix: the cwd is renamed away)") {
    val c = makeCase("commit-blank", _ => "", homeInsideVictim = false, victimUnder = None)
    check(c, expectField = Some("tempPathField" -> "blank"), expectWarn = Some("commitTempFile"))
  }

  test("② tempPath is pure whitespace ⇒ zero filesystem change") {
    val c = makeCase("commit-ws", _ => "   ", homeInsideVictim = false, victimUnder = None)
    check(c, expectWarn = Some("commitTempFile"))
  }

  test("③ tempPath is the absolute working directory ⇒ refused + WARN (pre-fix: cwd renamed away)") {
    val c = makeCase("commit-cwd", x => x.victim.toString, homeInsideVictim = false, victimUnder = None)
    check(c, expectWarn = Some("REFUSED"))
  }

  test("④ tempPath is an ancestor of the working directory ⇒ refused + WARN (pre-fix: ancestor renamed away)") {
    // victim lives under <root>/movable/, so the ancestor IS movable and the
    // (disposable) Downloads target sits outside it.
    val c = makeCase("commit-ancestor", x => x.victim.getParent.toString, homeInsideVictim = false, victimUnder = Some("movable"))
    check(
      c,
      expectWarn = Some("REFUSED"),
      extra = x => assert(Files.isDirectory(x.victim.getParent), s"ancestor ${x.victim.getParent} must survive")
    )
  }

  test("⑤ the Downloads destination lies inside the working directory ⇒ refused + WARN") {
    // user.home inside the cwd ⇒ downloadsDir ⊂ cwd. Source is fine, destination is
    // not: the temp file must stay where it is.
    val c = makeCase("commit-dest-inside", x => x.tmpIn.resolve("probe-target").toString, homeInsideVictim = true, victimUnder = None)
    Files.write(c.tmpIn.resolve("probe-target"), "payload".getBytes(StandardCharsets.UTF_8))
    check(c, expectTmpIn = List("probe-target"), expectWarn = Some("destination"))
  }

  // ===== ⑥ : the normal path still works (zero functional regression) =====

  test("⑥ a real temp file is still moved into Downloads, content intact") {
    val c = makeCase("commit-normal", x => x.tmpIn.resolve("probe-target").toString, homeInsideVictim = false, victimUnder = None)
    Files.write(c.tmpIn.resolve("probe-target"), "payload".getBytes(StandardCharsets.UTF_8))
    check(
      c,
      expectDownloads = List("probe-target"),
      expectTmpIn = Nil,
      extra = x =>
        assertEquals(
          new String(Files.readAllBytes(x.downloads.resolve("probe-target")), StandardCharsets.UTF_8),
          "payload",
          "the moved file must keep its content"
        )
    )
  }

  // ===== ⑦ : the delete path =====

  test("⑦ deleteTempFile with a blank tempPath ⇒ zero deletion") {
    val c = makeCase("delete-blank", _ => "", homeInsideVictim = false, victimUnder = None)
    check(c, expectField = Some("tempPathField" -> "blank"), expectWarn = Some("deleteTempFile"))
  }

  test("⑦b deleteTempFile whose tempPath is the working directory ⇒ refused + WARN (pre-fix: cwd deleted)") {
    // the victim is left empty on purpose: pre-fix `os.remove(cwd)` succeeds only then
    val c = makeCase("delete-cwd", x => x.victim.toString, homeInsideVictim = false, victimUnder = None, seedVictim = false)
    check(c, expectWarn = Some("REFUSED"))
  }

  // ===== ⑧ : "no leftover temp file" must be an explicit state, not a blank sentinel =====

  test("⑧ rebuild with no leftover temp file yields Absent, not a blank path") {
    val c = makeCase("rebuild-no-temp", _ => "", homeInsideVictim = false, victimUnder = None)
    check(c, expectField = Some("rebuildTempPath" -> "absent"))
  }

  test("⑨ the in-repo trigger end to end: rebuild(a.txt) then success ⇒ commitTempFile") {
    // Reproduces DropboxServiceSpec's R5 case in the sandbox: the persisted inbound
    // message is named "a.txt" (the real 21 cases produced ~/Downloads/a.txt and
    // ~/Downloads/a_<ts>.txt), there is no leftover temp file, and the completion
    // says success=true. Pre-fix the throwaway working directory ends up as
    // <Downloads>/a.txt; post-fix nothing moves.
    val c = makeCase("r5-rebuild-commit", _ => "", homeInsideVictim = false, victimUnder = None)
    check(c, expectField = Some("rebuildTempPath" -> "absent"), expectWarn = Some("commitTempFile"))
  }

end WtMoveGuardSpec
