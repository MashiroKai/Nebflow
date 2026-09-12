package nebflow.dropbox

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import scala.concurrent.duration.*

/**
 * Child-JVM driver for [[WtMoveGuardSpec]] (P0 wtmove).
 *
 * The defect under test *renames the process working directory*, so the parent
 * (sbt) JVM must never drive the code under test — it only orchestrates. Every
 * filesystem-touching call happens in a child JVM launched with
 * `cwd = <disposable tmp>/victim` and `-Duser.home=<disposable tmp>/home`, so a
 * red run relocates a throwaway directory and nothing else.
 *
 * Everything here goes through reflection on purpose: this file must compile —
 * and the same spec must run — against both the pre-fix code (`tempPath: String`,
 * `commitTempFile: IO[Unit]`) and the fixed code (`tempPath: Option[String]`,
 * `commitTempFile: IO[TempPathDecision]`). The only version-sensitive point is
 * `adaptTempPath`, which adapts to the *declared* parameter type.
 *
 * Output protocol (one `[probe] key=value` line per fact; the logback WARN lines
 * emitted by the code under test interleave on the same stream):
 *
 *   [probe] case=<id> / cwd= / user.home= / downloadsDir= / tempArg=
 *   [probe] tempPathField=absent|blank|<path>
 *   [probe] invoked=<method>
 *   [probe] outcome=<decision or ()>
 *   [probe] rebuildTempPath=absent|blank|<path>
 *   [probe] done
 */
object WtMoveGuardProbe:

  private val svcClass = classOf[DropboxService]
  private val ftClass = classOf[FileTransfer]

  // --- version-agnostic FileTransfer access ---

  private val ftCtor: java.lang.reflect.Constructor[?] =
    val all = ftClass.getDeclaredConstructors.sortBy(c => -c.getParameterCount)
    val c = all.head
    c.setAccessible(true)
    c

  private val ftTempPathM: java.lang.reflect.Method =
    val m = ftClass.getDeclaredMethod("tempPath")
    m.setAccessible(true)
    m

  /** `String` before the fix, `Option[String]` after — follow whatever is declared. */
  private def adaptTempPath(paramType: Class[?], raw: String): AnyRef =
    if paramType.getName == "scala.Option" then Some(raw) else raw

  private def makeTransfer(raw: String): FileTransfer =
    val ps = ftCtor.getParameterTypes
    if ps.length != 11 then sys.error(s"[probe] unexpected FileTransfer arity ${ps.length}")
    val args: Array[AnyRef] = Array[AnyRef](
      "t-probe",                                     // transferId
      "in",                                          // direction
      "peer-probe",                                  // peerDeviceId
      "",                                            // peerAddress
      "probe-target",                                // fileName
      java.lang.Long.valueOf(0L),                    // fileSize
      "text/plain",                                  // mimeType
      "m-probe",                                     // msgId
      "accepted",                                    // status
      adaptTempPath(ps(9), raw),                     // tempPath
      ""                                             // receiverHash
    )
    ftCtor.newInstance(args*).asInstanceOf[FileTransfer]

  /** Collapses `String` / `Option[String]` / decision values into one vocabulary. */
  private def normalise(v: Any): String = v match
    case null        => "absent"
    case Some(inner) => normalise(inner)
    case None        => "absent"
    case s: String   => if s.trim.isEmpty then "blank" else s
    case other       => other.toString

  private def tempPathFieldOf(t: FileTransfer): String = normalise(ftTempPathM.invoke(t))

  // --- version-agnostic DropboxService access ---

  private def newService(): DropboxService =
    val ctor = svcClass.getDeclaredConstructors
      .find(_.getParameterCount == 5)
      .getOrElse(sys.error("[probe] DropboxService 5-arg constructor not found"))
    ctor.setAccessible(true)
    ctor
      .newInstance(null.asInstanceOf[AnyRef], null.asInstanceOf[AnyRef], 120.seconds, 10.minutes, 31.minutes)
      .asInstanceOf[DropboxService]

  private def call(svc: DropboxService, name: String, t: FileTransfer): String =
    val m = svcClass.getDeclaredMethod(name, ftClass)
    m.setAccessible(true)
    val io = m.invoke(svc, t).asInstanceOf[IO[Any]]
    normalise(io.unsafeRunSync())

  private def setMessagesRef(svc: DropboxService, msgs: Map[String, List[DropboxMessage]]): Unit =
    val f = svcClass.getDeclaredField("messagesRef")
    f.setAccessible(true)
    val ref = f.get(svc).asInstanceOf[cats.effect.Ref[IO, Map[String, List[DropboxMessage]]]]
    ref.set(msgs).unsafeRunSync()

  /** Persist one inbound file message (the state a restart rebuild works from). */
  private def seedMessage(svc: DropboxService, fileName: String, transferId: String): Unit =
    setMessagesRef(
      svc,
      Map(
        "peer-probe" -> List(
          DropboxMessage(
            msgId = "m-probe",
            direction = "in",
            kind = "file",
            ts = 0L,
            transferId = transferId,
            fileName = fileName,
            fileSize = 0L,
            mimeType = "text/plain",
            status = "accepted"
          )
        )
      )
    )

  private def rebuildTransferOf(svc: DropboxService, transferId: String): Option[FileTransfer] =
    val m = svcClass.getDeclaredMethod("rebuildTransfer", classOf[String], classOf[String])
    m.setAccessible(true)
    m.invoke(svc, transferId, "in").asInstanceOf[IO[Option[FileTransfer]]].unsafeRunSync()

  // --- entry point ---

  def main(args: Array[String]): Unit =
    val caseId = args.lift(0).getOrElse(sys.error("[probe] usage: WtMoveGuardProbe <caseId> <sandboxRoot> [tempArg]"))
    val sandbox = os.Path(args(1), os.pwd)
    val tempArg = args.lift(2).filter(_ != "-").getOrElse("")

    val cwd = os.pwd
    val home = os.Path(System.getProperty("user.home"), os.pwd)

    println(s"[probe] case=$caseId")
    println(s"[probe] cwd=$cwd")
    println(s"[probe] user.home=$home")
    println(s"[probe] downloadsDir=${DropboxUtil.downloadsDir.toString}")
    println(s"[probe] tempArg=${if tempArg.isEmpty then "<empty>" else tempArg}")
    println(s"[probe] ftArity=${ftCtor.getParameterCount} tempParamType=${ftCtor.getParameterTypes()(9).getName}")

    if caseId == "ping" then
      println("[probe] pong")
    else if !cwd.startsWith(sandbox) || !home.startsWith(sandbox) then
      // Hard interlock: the pre-fix code really does relocate the working
      // directory, so refuse to drive it unless both cwd and user.home live
      // inside the disposable sandbox the parent handed us.
      println(s"[probe] ABORT sandbox interlock: cwd=$cwd home=$home sandbox=$sandbox")
      System.exit(2)
    else if caseId.startsWith("commit-") then
      val t = makeTransfer(tempArg)
      println(s"[probe] tempPathField=${tempPathFieldOf(t)}")
      println("[probe] invoked=commitTempFile")
      println(s"[probe] outcome=${call(newService(), "commitTempFile", t)}")
    else if caseId.startsWith("delete-") then
      val t = makeTransfer(tempArg)
      println(s"[probe] tempPathField=${tempPathFieldOf(t)}")
      println("[probe] invoked=deleteTempFile")
      println(s"[probe] outcome=${call(newService(), "deleteTempFile", t)}")
    else if caseId == "rebuild-no-temp" then
      val svc = newService()
      seedMessage(svc, "probe-target", "t-probe")
      println("[probe] invoked=rebuildTransfer")
      println(s"[probe] rebuildTempPath=${rebuildTransferOf(svc, "t-probe").map(tempPathFieldOf).getOrElse("<no rebuild>")}")
    else if caseId == "r5-rebuild-commit" then
      // The in-repo trigger, reproduced end to end: the exact shape of
      // DropboxServiceSpec's R5 case — a persisted inbound file message named
      // "a.txt", no leftover temp file in Downloads, success=true ⇒ commitTempFile.
      val svc = newService()
      seedMessage(svc, "a.txt", "t-restart")
      println("[probe] invoked=rebuildTransfer")
      val rebuilt = rebuildTransferOf(svc, "t-restart")
      println(s"[probe] rebuildTempPath=${rebuilt.map(tempPathFieldOf).getOrElse("<no rebuild>")}")
      rebuilt match
        case Some(t) =>
          println("[probe] invoked=commitTempFile")
          println(s"[probe] outcome=${call(svc, "commitTempFile", t)}")
        case None => println("[probe] ABORT no rebuilt transfer")
    else
      println(s"[probe] ABORT unknown case $caseId")
      System.exit(2)

    println("[probe] done")
  end main

end WtMoveGuardProbe
