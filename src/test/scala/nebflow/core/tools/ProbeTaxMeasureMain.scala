package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*

import java.net.InetSocketAddress
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

/**
 * 「砍探测税 / 串行搬运开销」实施批的**实测夹具**（2026-11-04，非 spec）。
 *
 * 作者令：「修复完必须要交付真实的实测结果对比」。本文件就是产出那组数字的机器，
 * 之所以进仓而不是一次性的 /tmp 脚本：**任何人都能重跑同一组数字**（`sbt
 * "Test/runMain nebflow.core.tools.ProbeTaxMeasureMain"`），报告里的每个毫秒都可复核。
 *
 * 两段测量，都跑**生产代码本体**（不是替身实现）：
 *
 *   - `probe`：对**真机的两台死端点**（KAI 的 Tailscale/局域网地址，实测 SYN 黑洞）
 *     分别跑「改前串行挨个拨（3 000 ms + 1 500 ms）」与「改后并发探测 + 负缓存」，
 *     给出三次调用的耗时（冷 / 缓存命中 / 半开探测）。
 *   - `transfer`：对一台**本机伪对端**（真 HTTP、真 base64、真 5 MiB 字节、每请求注入
 *     43 ms 单程延迟 = 实测 relay RTT 档位）跑 `BrowseGetLoop` 本体，比较
 *     「串行窗口 1」与「流水线窗口 N」以及块长档位。
 *
 * 输出 = 一行行 `MEASURE …` 的 JSON（便于落盘成证据文件）。
 */
object ProbeTaxMeasureMain:

  private val KaiEndpoints = List("http://100.91.165.120:6138", "http://192.168.1.145:6138")

  /**
   * 改前的串行拨号预算（`DialBudget` 实测档位：Tailscale 3 000 ms、同网段 1 500 ms）。
   * 逐端点回读数（`ok` + 实际耗时）——「税从哪来」必须逐跳可读，禁止只报一个合计数。
   */
  private def serialProbe(eps: List[String]): (Long, List[(String, Long, Boolean)]) =
    val t0 = System.nanoTime()
    val per = eps.map { ep =>
      val budgetMs = if ep.contains("100.") then 3000 else 1500
      val e0 = System.nanoTime()
      // 与改后的探针**同一形态**（有界 HTTP GET /api/health），只差「预算档位」与
      // 「串行/并发」两项 —— 否则比出来的不是流水线收益，是探针形态差异。
      val ok = P2pProbePolicy.probeTarget(ep) match
        case Left(_) => false
        case Right((host, port)) =>
          val client = java.net.http.HttpClient
            .newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(budgetMs.toLong))
            .build()
          try
            val req = java.net.http.HttpRequest
              .newBuilder(java.net.URI.create(s"http://$host:$port/api/health"))
              .timeout(java.time.Duration.ofMillis(budgetMs.toLong))
              .GET()
              .build()
            client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() > 0
          catch case _: Throwable => false
      (ep, (System.nanoTime() - e0) / 1000000L, ok)
    }
    ((System.nanoTime() - t0) / 1000000L, per)

  end serialProbe

  private def measureProbe(label: String, eps: List[String]): Unit =
    // ① 改前形态：串行挨个拨，各付满自己的预算（逐端点读数外发）。
    val (legacyMs, per) = serialProbe(eps)
    val perJson = per
      .map { case (ep, ms, ok) => s"""{"endpoint":"$ep","ms":$ms,"connected":$ok}""" }
      .mkString("[", ",", "]")
    println(
      s"""MEASURE probe {"set":"$label","mode":"legacy-serial","endpoints":${eps.size},"ms":$legacyMs,"per":$perJson}"""
    )

    // ② 改后形态：并发探测（首胜即放行）+ 负缓存的三种状态。
    val ledger = new P2pReachability
    val key = eps.sorted.mkString("|")
    val coldT0 = System.nanoTime()
    val coldWinner = P2pReachability.probeConcurrently(eps, P2pProbePolicy.ProbeConnectTimeoutMs).unsafeRunSync()
    val coldMs = (System.nanoTime() - coldT0) / 1000000L
    println(
      s"""MEASURE probe {"set":"$label","mode":"concurrent-cold","endpoints":${eps.size},"winner":${coldWinner
          .map(w => "\"" + w + "\"")
          .getOrElse("null")},"ms":$coldMs}"""
    )

    // 全不通 ⇒ 写负证据；随后一次调用的成本 = 0（纯查表）。
    val n = ledger.noteFailure("measure", key, System.currentTimeMillis())
    val cachedT0 = System.nanoTime()
    val cachedHit = ledger.shouldSkip("measure", key, System.currentTimeMillis())
    val cachedMs = (System.nanoTime() - cachedT0) / 1000000L
    println(
      s"""MEASURE probe {"set":"$label","mode":"negative-cache-hit","hit":$cachedHit,"consecutive":$n,"windowMs":${P2pProbePolicy
          .negativeWindowMs(n)},"ms":$cachedMs}"""
    )

    // 窗口到期后的半开探测：判据放行（一次），且窗口按连续失败次数增长。
    val expireAt = System.currentTimeMillis() + P2pProbePolicy.negativeWindowMs(n) + 1
    val stillHit = ledger.shouldSkip("measure", key, expireAt)
    println(s"""MEASURE probe {"set":"$label","mode":"negative-cache-expired-half-open","hit":$stillHit,"ms":0}""")
  end measureProbe

  // ---- 传送腿：本机伪对端 + 注入 RTT ----

  /** 一台伪对端：真 HTTP + 真 5 MiB 字节 + 每请求注入 `rttMs` 单程延迟。 */
  private final class StubPeer(val bytes: Array[Byte], rttMs: Long):
    private val server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

    server.createContext(
      "/api/neblink/remote-exec",
      (ex: com.sun.net.httpserver.HttpExchange) =>
        try
          val body = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          val json = decode[Json](body).getOrElse(Json.Null)
          val params = json.hcursor.downField("params")
          val index = params.downField("chunkIndex").as[Int].getOrElse(0)
          val chunkSize = params.downField("chunkSize").as[Int].getOrElse(524288)
          val wantWhole = params.downField("wantWholeSha256").as[Boolean].getOrElse(false)
          Thread.sleep(rttMs) // 注入一次单向 RTT（实测 relay RTT 43 ms 档）
          val offset = index.toLong * chunkSize
          val slice = bytes.slice(offset.toInt, math.min(bytes.length.toLong, offset + chunkSize).toInt)
          val sha = nebflow.dropbox.ChunkedTransfer.sha256Hex(slice)
          val totalChunks = (bytes.length + chunkSize - 1) / chunkSize
          val resp = Json
            .obj(
              "content" -> Base64.getEncoder.encodeToString(slice).asJson,
              "size" -> bytes.length.asJson,
              "chunkIndex" -> index.asJson,
              "chunkBytes" -> slice.length.asJson,
              "chunkSha256" -> sha.asJson,
              "totalChunks" -> totalChunks.asJson
            )
          val wholeSha =
            if wantWhole then nebflow.dropbox.ChunkedTransfer.sha256Hex(bytes) else ""
          val payload = Json.obj("output" -> resp.noSpaces.asJson, "error" -> "".asJson).noSpaces
          val out = payload.getBytes(StandardCharsets.UTF_8)
          ex.getResponseHeaders.add("Content-Type", "application/json")
          ex.sendResponseHeaders(200, out.length.toLong)
          ex.getResponseBody.write(out)
        catch case _: Throwable => ()
        finally ex.close()
    )
    server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16))
    server.start()
    val url: String = s"http://127.0.0.1:${server.getAddress.getPort}/api/neblink/remote-exec"
    def close(): Unit = server.stop(0)

  end StubPeer

  private def measureTransfer(totalBytes: Int, chunkSize: Int, window: Int, rttMs: Long): Unit =
    val bytes = Array.tabulate(totalBytes)(i => ((i * 31 + 7) % 251).toByte)
    val stub = new StubPeer(bytes, rttMs)
    val client = HttpClient.newBuilder().build()
    val workDir = Files.createTempDirectory("probe-tax-measure")
    try
      val target = Paths.get(workDir.toString, "pickup.bin")
      val osTarget = os.Path(target)
      def fetch(index: Int, cs: Int, wantWholeSha: Boolean): IO[Either[String, BrowseGetCursor.ChunkReply]] =
        IO.blocking {
          val requestBody = Json
            .obj(
              "action" -> "FileTransfer".asJson,
              "params" -> Json.obj(
                "direction" -> "get".asJson,
                "path" -> "sample.bin".asJson,
                "scope" -> "browse".asJson,
                "chunkIndex" -> index.asJson,
                "chunkSize" -> cs.asJson,
                "wantWholeSha256" -> wantWholeSha.asJson
              )
            )
            .noSpaces
          val req = HttpRequest
            .newBuilder(java.net.URI.create(stub.url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
          val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
          decode[Json](resp.body()) match
            case Left(err) => Left(s"decode: ${err.getMessage}")
            case Right(json) =>
              val output = json.hcursor.downField("output").as[String].getOrElse("")
              decode[Json](output) match
                case Left(err) => Left(s"inner decode: ${err.getMessage}")
                case Right(inner) => BrowseGetCursor.parseReply(inner)
        }
      val t0 = System.nanoTime()
      val result = BrowseGetLoop
        .run(
          osTarget,
          fetch,
          (_, _, _) => IO.unit,
          new AtomicBoolean(false),
          startChunkSize = chunkSize,
          window = window
        )
        .unsafeRunSync()
      val ms = (System.nanoTime() - t0) / 1000000L
      result match
        case Right((path, size, sha)) =>
          val ok = os.read.bytes(path).sameElements(bytes)
          val expectedSha = nebflow.dropbox.ChunkedTransfer.sha256Hex(bytes)
          val chunks = (totalBytes + chunkSize - 1) / chunkSize
          println(
            s"""MEASURE transfer {"bytes":$size,"chunkSize":$chunkSize,"window":$window,"chunks":$chunks,"rttMs":$rttMs,""" +
              s""""ms":$ms,"bytesIntact":$ok,"shaMatch":${sha == expectedSha}}"""
          )
        case Left(err) => println(s"""MEASURE transfer {"error":"${err.replace('"', '\'')}"}""")
    finally
      stub.close()
      // 夹具自清：一轮 5 MiB 落盘件不留在临时目录（本机磁盘常年紧张，测量工具不该成为负担）。
      try os.remove.all(os.Path(workDir))
      catch case _: Throwable => ()

    end try

  end measureTransfer

  def main(args: Array[String]): Unit =
    println(s"""MEASURE env {"pid":${ProcessHandle.current().pid()},"note":"real sockets, real 5MiB bytes"}""")
    // 两组端点：① 真机 KAI 的两个候选（今天的实测对象）；② TEST-NET-3 两个地址
    // （RFC 5737，链路上无人应答 ⇒ 确定性 SYN 黑洞，用来把「税」钉成可复现的数）。
    measureProbe("kai", KaiEndpoints)
    measureProbe("blackhole-rfc5737", List("http://203.0.113.7:6138", "http://198.51.100.9:6138"))
    val fiveMb = 5 * 1024 * 1024
    // 改前形态（512KiB 串行）× 2 次取中位口径；改后两档。
    measureTransfer(fiveMb, 512 * 1024, 1, 43)
    measureTransfer(fiveMb, 512 * 1024, 1, 43)
    measureTransfer(fiveMb, 512 * 1024, 4, 43)
    measureTransfer(fiveMb, 512 * 1024, 4, 43)
    measureTransfer(
      fiveMb,
      ChunkSizing.chunkSizeFor(fiveMb.toLong),
      ChunkSizing.windowFor(ChunkSizing.chunkSizeFor(fiveMb.toLong)),
      43
    )
    measureTransfer(
      fiveMb,
      ChunkSizing.chunkSizeFor(fiveMb.toLong),
      ChunkSizing.windowFor(ChunkSizing.chunkSizeFor(fiveMb.toLong)),
      43
    )

    // 🔴 收尾纪律（2026-11-04 e2e 收口时踩到）：`import cats.effect.unsafe.implicits.global`
    // 的 IORuntime 工作线程是**非 daemon**，不 shutdown ⇒ main 返回后 JVM 挂住不退
    // （实测连开两次都留了一个 0% CPU 的僵尸 JVM）。夹具必须自退。
    cats.effect.unsafe.IORuntime.global.shutdown()
  end main

end ProbeTaxMeasureMain
