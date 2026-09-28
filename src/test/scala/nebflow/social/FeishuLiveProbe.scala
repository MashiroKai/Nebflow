package nebflow.social

import io.circe.Json
import nebflow.shared.PathUtil // W1 shim: main had nebflow.core.PathUtil; PR moved it to shared

import java.io.{File, PrintWriter}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.time.temporal.ChronoUnit

/**
 * Manual live probe for the Feishu long-connection channel (feishu-chan,
 * 2026-09-23). NOT a test and NOT part of `sbt test`'s discovery: it is a
 * minimal `main` that the batch's manual evidence step invokes, in Test scope so
 * nothing of it reaches the shipped artifact.
 *
 * Why a separate main instead of `sbt run`: `sbt run` boots the whole Nebflow
 * application (gateway ports, host data root). The batch forbids that outright,
 * so this entry point does exactly one thing and owns its own lifecycle:
 *
 *   · `stop()` on the WebSocket in a `finally` — the socket never outlives main;
 *   · `System.exit(0)` as the last statement — the SDK keeps non-daemon threads,
 *     so without an explicit exit the JVM would hang and the tool call would
 *     never return (the batch's ≤120s self-exit obligation);
 *   · the credential VALUE is never printed, never written; only masked id,
 *     fingerprint and the ids Feishu itself returns.
 *
 * Modes:
 *   send <chat_id|open_id> <receiveId> <text>   — send one text message
 *   listen <seconds>                            — hold the socket and print each
 *                                                 inbound im.message.receive_v1
 *   both <chat_id|open_id> <receiveId> <text> <seconds> — send, then listen
 */
object FeishuLiveProbe:

  private def now(): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MILLIS))

  /** Append-or-create one evidence file. The JSON written here is built by the
    *  adapter's own `toJson` faces, which carry no credential material. */
  private def write(dir: File, name: String, json: Json): Unit =
    val w = new PrintWriter(new File(dir, name), "UTF-8")
    try w.println(json.spaces2)
    finally w.close()

  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("help")
    val evidenceDir = new File(
      Option(System.getProperty("nebflow.feishu.evidence")).getOrElse(".")
    )
    evidenceDir.mkdirs()

    // 🔴 Exit-code ownership (batch constraint §B-3 / D-2②): the SDK's OkHttp
    // dispatcher and connection-pool threads are NON-daemon, so returning from
    // main is not enough — the JVM would sit alive after the work is done and the
    // enclosing tool call would hang forever (observed on the first send run:
    // the send had already succeeded and been written to evidence, yet the
    // process was still up at the 60s watchdog). Every path through this probe
    // therefore ends in an explicit `System.exit`, and the code it passes is the
    // reading: 0 = done, 2 = credential, 3 = send rejected, 4 = listen failed.
    val exitCode =
      try runMode(mode, args.drop(1), evidenceDir)
      catch
        case e: Throwable =>
          println(s"[probe] FATAL ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
          5
    println(s"[probe] exit=$exitCode")
    System.exit(exitCode)

  /** Resolve the credential and dispatch, returning the exit code rather than
    *  exiting here — [[main]] owns the single exit point so teardown can never
    *  be skipped. */
  private def runMode(mode: String, rest: Array[String], evidenceDir: File): Int =
    // The credential is read from the ACTIVE data root (honours --home/NEBFLOW_HOME).
    val root = nebflow.shared.PathUtil.dataRoot
    resolvedRoot(mode, rest, evidenceDir, root)

  private def resolvedRoot(mode: String, rest: Array[String], dir: File, root: os.Path): Int =
    FeishuCredentials.resolve(root) match
      case Left(err) =>
        println(s"[probe] FATAL credential not resolved: $err")
        2
      case Right(cred) =>
        // 🔴 Only ever the masked id + fingerprint reach stdout/evidence.
        println(s"[probe] credential source=${cred.source}")
        println(s"[probe] appId=${FeishuCredentials.redacted(cred.appId)} " +
          s"len=${cred.appId.length} fp=${FeishuCredentials.fingerprint(cred.appId)}")
        println(s"[probe] appSecret len=${cred.appSecret.length} fp=${FeishuCredentials.fingerprint(cred.appSecret)}")
        run(mode, rest, cred, dir)

  private def run(mode: String, rest: Array[String], cred: FeishuCredentials.Credential, dir: File): Int =
    val listener = new FeishuChannel.Listener(cred.appId, cred.appSecret, region = "feishu")
    try
      mode match
        case "send" =>
          val receiveIdType = rest.lift(0).getOrElse("chat_id")
          val receiveId = rest.lift(1).getOrElse("")
          val text = rest.lift(2).getOrElse("Nebflow feishu channel probe")
          val res = FeishuChannel.sendText(cred.appId, cred.appSecret, "feishu", receiveIdType, receiveId, text)
          write(dir, "send.json", res.toJson)
          println(s"[probe] SEND ok=${res.ok} code=${res.code} msg=${res.msg} " +
            s"messageId=${res.messageId.getOrElse("<none>")} sentAt=${res.sentAt}")
          if res.ok then 0 else 3

        case "listen" =>
          val secs = rest.lift(0).flatMap(_.toIntOption).getOrElse(60)
          hold(listener, secs, dir)

        case "both" =>
          val receiveIdType = rest.lift(0).getOrElse("chat_id")
          val receiveId = rest.lift(1).getOrElse("")
          val text = rest.lift(2).getOrElse("Nebflow feishu channel probe")
          val secs = rest.lift(3).flatMap(_.toIntOption).getOrElse(60)
          val res = FeishuChannel.sendText(cred.appId, cred.appSecret, "feishu", receiveIdType, receiveId, text)
          write(dir, "send.json", res.toJson)
          println(s"[probe] SEND ok=${res.ok} code=${res.code} messageId=${res.messageId.getOrElse("<none>")} sentAt=${res.sentAt}")
          hold(listener, secs, dir)

        case _ =>
          println("usage: send <chat_id|open_id> <receiveId> <text> | listen <secs> | both <type> <id> <text> <secs>")
          0
    finally
      listener.stop()
      println(s"[probe] teardown done connected=${listener.connected} lastErr=${listener.lastErr.getOrElse("<none>")}")

  /** Connect, then collect for `secs` — printing each inbound event and its
    *  receive timestamp. Always returns (never blocks past the budget), so the
    *  caller's `finally` teardown is reached.
    *
    *  Exit code: 0 when the handshake succeeded (even with zero events — an empty
    *  window is a legitimate reading, not a failure); 4 when the handshake did
    *  not complete. The count of received events is reported in the readings
    *  rather than encoded here, so "handshake ok but nothing arrived" stays
    *  distinguishable from "could not connect". */
  private def hold(listener: FeishuChannel.Listener, secs: Int, dir: File): Int =
    val connect = listener.start()
    write(dir, "connect.json", connect.toJson)
    println(s"[probe] CONNECT ok=${connect.ok} at=${connect.connectedAt.getOrElse("<none>")} detail=${connect.detail}")
    if !connect.ok then return 4

    val deadline = System.currentTimeMillis() + secs * 1000L
    var n = 0
    val received = scala.collection.mutable.ArrayBuffer.empty[Json]
    while System.currentTimeMillis() < deadline do
      val remaining = math.max(1L, deadline - System.currentTimeMillis())
      listener.awaitOne(math.min(remaining, 5000L)) match
        case Some(r) =>
          n += 1
          received += r.toJson
          println(s"[probe] RECEIVED#${n} at=${r.receivedAt} messageId=${r.inbound.messageId} " +
            s"chatId=${r.inbound.chatId} type=${r.inbound.messageType} text=${r.inbound.text.getOrElse("<non-text>")}")
          write(dir, "received.json", Json.arr(received.toSeq*))
        case None => () // budget slice expired with nothing delivered
    // Zero received is written as an EMPTY array too, so the evidence file always
    // exists and the absence of events is explicit rather than missing.
    if n == 0 then write(dir, "received.json", Json.arr())
    println(s"[probe] LISTEN window ${secs}s closed, received=$n")
    0

end FeishuLiveProbe
