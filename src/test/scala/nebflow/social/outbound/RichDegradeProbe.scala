package nebflow.social.outbound

import java.io.{File, PrintWriter}
import java.nio.file.{Files, StandardOpenOption}
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Manual live probe for the FAILURE paths of the rich-content outbound leg
 * (richcontent batch, 2026-09-30). NOT a test and NOT part of `sbt test`'s
 * discovery — a minimal `main` in Test scope, same shape as
 * [[RichRenderProbe]] and [[nebflow.social.FeishuLiveProbe]].
 *
 * Why this exists at all: the offline spec asserts the degrade behaviour with
 * recorded fakes, but the acceptance criteria ask for a measured reading of
 * "文本占位 + 报错" on the REAL compiled adapter, produced by a command whose
 * output can be read. So this probe drives the real [[RichFeishuAdapter]] with
 * the real [[RichDegrade]] / [[RichPlanner]] / [[RichRenderer.inspect]] code —
 * only the network seams are replaced by recorders, because the point is to
 * measure the DECISION, and a genuine upload would need a credential (which this
 * batch forbids reading).
 *
 * 🔴 No credential, no network, no browser: the probes below are (a) the
 * degraded-render path, (b) the oversized-product path, (c) the off-table html
 * path with the long-shot seam unavailable (the 2026-10-20 shape under the
 * pop-social-files ruling: the failed conversion lands on the
 * 「文件名+本地路径+一句话摘要」text card, no mechanism words), (d) the
 * missing-product path.
 * `System.exit` is the last statement (the ≤120s self-exit obligation).
 *
 * Modes:
 *   degrade <isolatedHomeDir> <evidenceDir>
 */
object RichDegradeProbe:

  private def now(): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MILLIS))

  private def write(dir: File, name: String, text: String): Unit =
    val w = new PrintWriter(new File(dir, name), "UTF-8")
    try w.println(text)
    finally w.close()

  /**
   * Records what the adapter would have sent, and reports success — so the
   *  reading shows the DECISION rather than a transport outcome.
   */
  private final class Recorder:
    var images: List[String] = Nil
    var files: List[String] = Nil
    var msgs: List[(String, String)] = Nil
    var texts: List[String] = Nil

    def uploadImage: RichFeishuAdapter.UploadImage = p =>
      images = images :+ p.toString
      cats.effect.IO.pure(RichFeishuAdapter.UploadResult(true, "img_probe_key", 0, "ok"))

    def uploadFile: RichFeishuAdapter.UploadFile = (p, n) =>
      files = files :+ s"$p ($n)"
      // 2026-10-20 起 html 文件腿退役（pop-social-files：文件类走长图腿）；本
      // 探针测的是降级链，上传一律拒绝（案例 B 的超大件在闸前已被拒，不受影响，
      // 案例 C 走的是长图转换失败 ⇒ 文本卡，不经上传腿）。
      cats.effect.IO.pure(RichFeishuAdapter.UploadResult(false, "", 230021, "upload refused (probe)"))

    def sendMsg: RichFeishuAdapter.SendMsg = (t, c) =>
      msgs = msgs :+ ((t, c))
      cats.effect.IO.pure(RichFeishuAdapter.SendResult(true, Some("om_probe"), 0, "ok"))

    def sendText: RichFeishuAdapter.SendText = t =>
      texts = texts :+ t
      cats.effect.IO.pure(RichFeishuAdapter.SendResult(true, Some("om_probe"), 0, "ok"))

  end Recorder

  private def esc(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "'").replace("\n", "\\n")

  private def run(): String =
    val rec = new Recorder
    val iso = Option(System.getenv("HOME")).getOrElse("/tmp")
    val rendered = new File(iso, "tmp/outbound-render")

    // A renderer that always fails — the host-without-Chrome case (HC-5).
    val failing = RichRenderer.Unavailable
    // The shot seam is pinned Unavailable: the probe measures DECISIONS, never a
    // real node subprocess (the probe's own no-browser discipline).
    val adapter = new RichFeishuAdapter(
      rec.uploadImage,
      rec.uploadFile,
      rec.sendMsg,
      rec.sendText,
      failing,
      () => os.Path(rendered.toPath, os.pwd),
      FileLongShot.Unavailable
    )

    val cardPayload =
      RichKind.CardSentinel + io.circe.Json
        .obj(
          "html" -> io.circe.Json.fromString(
            """<!doctype html><html><body><h1>interactive card</h1><script>console.log("live")</script></body></html>"""
          )
        )
        .noSpaces

    val rt = cats.effect.unsafe.IORuntime.global
    import cats.effect.unsafe.implicits.*

    // (a) card with a renderer that cannot run
    val resA = adapter.deliver(RichKind.Card, None, cardPayload).unsafeRunSync()
    val textsA = rec.texts
    val imagesA = rec.images
    rec.texts = Nil

    // (b) oversized document — sparse, so the REAL gate is exercised without a GB
    val big = new File(new File(System.getenv("HOME")), "tmp/nb-probe-huge.pdf")
    big.getParentFile.mkdirs()
    val ch = Files.newByteChannel(
      big.toPath,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING
    )
    try
      ch.position(nebflow.shared.AttachContract.MaxFileBytes) // one byte over the limit
      ch.write(java.nio.ByteBuffer.wrap(Array[Byte](0)))
    finally ch.close()
    val resB = adapter.deliver(RichKind.Document, Some(os.Path(big.toPath, os.pwd))).unsafeRunSync()
    val textsB = rec.texts
    val filesB = rec.files
    rec.texts = Nil

    // (c) off-table product on disk — the 2026-10-10/20 incident shape: a real
    // report.html whose extension is in neither authority table. Per the
    // 2026-10-20 ruling the LONG-SHOT leg runs (file leg retired); this probe
    // pins the long-shot seam Unavailable, so the conversion failure falls
    // through to the 「文件名+本地路径+一句话摘要」 text card.
    val html = new File(rendered, "diagram.html")
    write(rendered, "diagram.html", "<html><body><h1>diagram</h1></body></html>")
    val resC = adapter
      .deliver(RichKind.Unknown("extension is in neither authority table: html"), Some(os.Path(html.toPath, os.pwd)))
      .unsafeRunSync()
    val textsC = rec.texts
    rec.texts = Nil

    // (d) missing product — the original is gone ⇒ the [未送达] face.
    val gone = new File(rendered, "gone.png")
    val resD = adapter
      .deliver(
        RichKind.Unknown(s"path does not exist or is not a regular file: $gone"),
        Some(os.Path(gone.toPath, os.pwd))
      )
      .unsafeRunSync()
    val textsD = rec.texts

    big.delete()

    val json =
      s"""{
         |  "ts": "${now()}",
         |  "probe": "RichDegradeProbe",
         |  "credentialUsed": false,
         |  "networkUsed": false,
         |  "cases": [
         |    {
         |      "id": "A-card-render-failed",
         |      "input": "card payload, renderer=RichRenderer.Unavailable (HC-5)",
         |      "imagesUploaded": ${imagesA.size},
         |      "readings": ${resA.size},
         |      "textsSent": ${textsA.size},
         |      "text0": "${esc(textsA.headOption.getOrElse(""))}",
         |      "carriesUnrenderedPlaceholder": ${textsA.exists(_.contains("[未渲染]"))},
         |      "carriesInteractivePanelNote": ${textsA.exists(_.contains("交互版见本地面板"))},
         |      "verdict": "${
          if imagesA.isEmpty && textsA.exists(t => t.contains("[未渲染]") && t.contains("交互版见本地面板")) then "PASS"
          else "FAIL"
        }"
         |    },
         |    {
         |      "id": "B-document-oversize",
         |      "input": "sparse file of MaxFileBytes+1 (=${nebflow.shared.AttachContract.MaxFileBytes + 1} bytes)",
         |      "gateConstant": ${nebflow.shared.AttachContract.MaxFileBytes},
         |      "uploadsStarted": ${filesB.size},
         |      "readings": ${resB.size},
         |      "textsSent": ${textsB.size},
         |      "text0": "${esc(textsB.headOption.getOrElse(""))}",
         |      "echoesExistingErrorCode": ${textsB.exists(_.contains("ATTACH_TOO_LARGE"))},
         |      "echoesActualSize": ${textsB.exists(
          _.contains((nebflow.shared.AttachContract.MaxFileBytes + 1).toString)
        )},
         |      "verdict": "${
          if filesB.isEmpty && textsB.exists(t => t.contains("尺寸闸拒绝") && t.contains("ATTACH_TOO_LARGE")) then "PASS"
          else "FAIL"
        }"
         |    },
         |    {
         |      "id": "C-html-off-table-longshot-unavailable",
         |      "input": "on-disk diagram.html, RichKind.Unknown(extension is in neither authority table: html), longshot=Unavailable",
         |      "readings": ${resC.size},
         |      "textsSent": ${textsC.size},
         |      "text0": "${esc(textsC.headOption.getOrElse(""))}",
         |      "carriesTextCard": ${textsC.exists(t =>
          t.contains(RichDegrade.UnconvertedPrefix) && t.contains("原件在桌面端") && t.contains("diagram.html")
        )},
         |      "leaksMechanismWording": ${textsC.exists(t => t.contains("authority") || t.contains("neither"))},
         |      "verdict": "${
          if textsC.exists(t =>
              t.contains(RichDegrade.UnconvertedPrefix) && t.contains("原件在桌面端") && !t.contains("authority")
            )
          then "PASS"
          else "FAIL"
        }"
         |    },
         |    {
         |      "id": "D-missing-product",
         |      "input": "RichKind.Unknown(path does not exist), path names a gone.png",
         |      "readings": ${resD.size},
         |      "textsSent": ${textsD.size},
         |      "text0": "${esc(textsD.headOption.getOrElse(""))}",
         |      "carriesUnreachableLine": ${textsD.exists(t =>
          t.contains(RichDegrade.UnreachablePrefix) && t.contains("gone.png")
        )},
         |      "verdict": "${
          if textsD.exists(t => t.contains(RichDegrade.UnreachablePrefix) && t.contains("gone.png")) then "PASS"
          else "FAIL"
        }"
         |    }
         |  ]
         |}""".stripMargin
    json

  end run

  def main(args: Array[String]): Unit =
    if args.length < 2 then
      println("usage: RichDegradeProbe <isolatedHomeDir> <evidenceDir>")
      System.exit(2)
    val evidenceDir = new File(args(1))
    evidenceDir.mkdirs()
    try
      val json = run()
      write(evidenceDir, "degrade-reading.json", json)
      println("[degrade-probe] " + json.linesIterator.mkString(" "))
      // Surface a non-zero exit when any case failed, so the reading is machine-checkable.
      if json.contains("\"verdict\": \"FAIL\"") then System.exit(1) else System.exit(0)
    catch
      case e: Throwable =>
        write(
          evidenceDir,
          "degrade-reading.json",
          s"""{"ts":"${now()}","exception":"${esc(e.getClass.getName)}: ${esc(Option(e.getMessage).getOrElse(""))}"}"""
        )
        println(s"[degrade-probe] EXCEPTION ${e.getClass.getName}: ${Option(e.getMessage).getOrElse("")}")
        System.exit(1)
    end try
  end main

end RichDegradeProbe
