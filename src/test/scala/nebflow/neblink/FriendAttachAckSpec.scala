package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import munit.FunSuite

/**
 * 补件批 4b1 · 件①：**E4 回执调用点**（fail-closed 闸 + 唯一调用链）。
 *
 * ## 夹具声明（🔴 如实声明，不冒充生产实测）
 * 本件**不连真 neblink-server**：判定链对端 = `com.sun.net.httpserver` 的**本机回环桩**
 * （127.0.0.1，临时端口），按 `e4Mode` 模拟服务端回执面的三种形态（200 / 404 / 500）。
 * 客户端侧**全部是生产代码**：真 `NeblinkClient`（真 HTTP 传输 + 真 `withSession` 鉴权缝）
 * + 真 `FriendService.ackAttachmentReceived` + 真 [[AttachmentAck]] 闸。
 * ⇒ 读数是「闸 + 回执发送」的行为实测；**不是**与真 neblink-server 联调的读数（腿 B 未落地）。
 *
 * ## 判据（逐条对齐任务书；「恰好一次」= 桩上 `/received` 请求计数）
 *   - (a) 完整字节 + sha 相符 + 落最终位置 ⇒ **恰好一次** E4、body digest 逐字相等；
 *   - (b) sha 不符 ⇒ 零 E4（负控）；
 *   - (c) 落盘失败/未落最终位置 ⇒ 零 E4（负控）；
 *   - (d) 拿不到字节（OS 级下载器路径）⇒ 零 E4（负控）；
 *   - (e) E4 失败 ⇒ 静默容忍：**不抛**、用户面（调用方可见的 `IO[Unit]`）与成功路径逐字相同；
 *   - (f) `404`/`410` ⇒ 无需回执（信息级）、**零重试**；
 *   - (g) 其余证据缺口（字节数不可核 / digest 缺失 / digest 形态非法）⇒ 零 E4（负控）。
 */
class FriendAttachAckSpec extends FunSuite:

  // 夹具：16 B；sha256 由 `printf 'friendattach-4b\n' | shasum -a 256` **独立**算得
  private val FixtureBody = "friendattach-4b\n"
  private val FixtureSha  = "8db0362fc0a30b7b224dbc9306016d57bea5c5a0e4eb882c38d337dccde454e3"
  private val OtherSha    = "0" * 64 // 合法形态、不同值
  private val BadShapeSha = "ABC"    // 非法形态（非 64 位小写 hex）

  private final class Stub(val e4Code: Int):
    private val requests = scala.collection.mutable.ListBuffer.empty[String]
    private var server: HttpServer = null

    def start(): Unit =
      val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      s.createContext(
        "/",
        (ex: HttpExchange) =>
          val body = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
          val path = ex.getRequestURI.getPath
          requests.synchronized {
            requests += s"${ex.getRequestMethod} $path | body=$body"
          }
          val (code, resp) =
            if path.startsWith("/api/device/") then
              (200, """{"token":"stub-session","networkId":"n1","deviceId":"d1","peers":[]}""")
            else if path.endsWith("/received") then
              e4Code match
                case 200 => (200, """{"state":"deleted"}""")
                case 404 => (404, """{"error":"attachment_not_found"}""")
                case _   => (500, """{"error":"internal"}""")
            else (404, """{"error":"not found"}""")
          val bytes = resp.getBytes("UTF-8")
          ex.getResponseHeaders.add("Content-Type", "application/json")
          ex.sendResponseHeaders(code, bytes.length.toLong)
          ex.getResponseBody.write(bytes)
          ex.close()
      )
      s.setExecutor(null)
      s.start()
      server = s

    def stop(): Unit = if server != null then server.stop(0)

    def url: String = s"http://127.0.0.1:${server.getAddress.getPort}"

    /** E4 请求（路径以 `/received` 结尾）的原始行 —— **「恰好一次」的唯一判据面**。 */
    def e4Requests: List[String] =
      requests.synchronized(requests.toList).filter(_.contains("/received"))

    def allRequests: List[String] = requests.synchronized(requests.toList)

  private def withStub[A](e4Code: Int)(use: Stub => A): A =
    val stub = new Stub(e4Code)
    stub.start()
    try use(stub)
    finally stub.stop()

  private def newClient(url: String): NeblinkClient =
    new NeblinkClient(NeblinkServerConfig(url = url, networkId = "n1", secret = "s"), serverPort = 1)

  private def loggedIn(url: String): NeblinkClient =
    val c = newClient(url)
    c.login("dev-1", "TestMac", "macos", List(NeblinkEndpoint("10.0.0.5", 1, "lan"))).unsafeRunSync()
    c

  private def fsFor(c: NeblinkClient): FriendService =
    new FriendService(IO.pure(Some(c)), AgentMessagingConfig(mode = "auto"))

  /** 「一切正常」的证据（= UI 侧 sha 自算成功 + 字节数相符 + 已交给保存）。 */
  private def goodEvidence: AttachmentAck.Evidence =
    AttachmentAck.Evidence(
      localSha256 = Some(FixtureSha),
      declaredSha256 = FixtureSha,
      receivedBytes = Some(FixtureBody.length.toLong),
      expectedBytes = Some(FixtureBody.length.toLong),
      landedFinal = true
    )

  /** 逐条落盘读数（stdout）：断言只判绿红，这行把**读数本身**打出来，供报告逐字引用。 */
  private def evidence(tag: String, lines: String*): Unit =
    lines.foreach(l => println(s"[4b1-ack $tag] $l"))

  // ===== (a) 完整字节 + sha 相符 + 落最终位置 ⇒ 恰好一次 E4、body digest 逐字相等 =====

  test("(a) 完整字节 + sha 相符 + 落最终位置 ⇒ 恰好一次 E4，body digest 逐字相等") {
    withStub(200) { stub =>
      val fs = fsFor(loggedIn(stub.url))
      val res = fs.ackAttachmentReceived("att-1", goodEvidence).unsafeRunSync()
      val e4  = stub.e4Requests
      evidence("a", s"result=$res", s"e4Count=${e4.size}", s"e4=${e4.mkString(" || ")}")
      assertEquals(res, AttachmentAck.Result.Acknowledged)
      assertEquals(e4.size, 1, "恰好一次：一次下载落盘 ⇒ 一次 E4，不得重复")
      assertEquals(
        e4.head,
        """POST /api/attachments/att-1/received | body={"wholeSha256":"8db0362fc0a30b7b224dbc9306016d57bea5c5a0e4eb882c38d337dccde454e3"}"""
      )
    }
  }

  // ===== (b)(c)(d)(g) 负控：一律零 E4 =====

  test("(b) sha 不符 ⇒ 零 E4（负控；服务端零副作用面不被触碰）") {
    withStub(200) { stub =>
      val fs  = fsFor(loggedIn(stub.url))
      val res = fs
        .ackAttachmentReceived("att-1", goodEvidence.copy(localSha256 = Some(OtherSha)))
        .unsafeRunSync()
      evidence("b", s"result=$res", s"e4Count=${stub.e4Requests.size}")
      assertEquals(res, AttachmentAck.Result.Skipped("digest-mismatch"))
      assertEquals(stub.e4Requests, Nil)
    }
  }

  test("(c) 落盘失败/未落最终位置 ⇒ 零 E4（负控）") {
    withStub(200) { stub =>
      val fs  = fsFor(loggedIn(stub.url))
      val res = fs
        .ackAttachmentReceived("att-1", goodEvidence.copy(landedFinal = false))
        .unsafeRunSync()
      evidence("c", s"result=$res", s"e4Count=${stub.e4Requests.size}")
      assertEquals(res, AttachmentAck.Result.Skipped("not-landed-final"))
      assertEquals(stub.e4Requests, Nil)
    }
  }

  test("(d) 拿不到字节（OS 级下载器路径 / 非安全上下文无 WebCrypto）⇒ 零 E4（负控）") {
    withStub(200) { stub =>
      val fs  = fsFor(loggedIn(stub.url))
      val res = fs
        .ackAttachmentReceived("att-1", goodEvidence.copy(localSha256 = None, receivedBytes = None))
        .unsafeRunSync()
      evidence("d", s"result=$res", s"e4Count=${stub.e4Requests.size}")
      assertEquals(res, AttachmentAck.Result.Skipped("bytes-unavailable"))
      assertEquals(stub.e4Requests, Nil)
    }
  }

  test("(g) 其余证据缺口 ⇒ 零 E4（字节数不符 / 字节数不可核 / digest 缺失 / digest 形态非法）") {
    val gaps = List(
      goodEvidence.copy(receivedBytes = Some(15L))            -> AttachmentAck.Result.Skipped("bytes-incomplete"),
      goodEvidence.copy(expectedBytes = None)                 -> AttachmentAck.Result.Skipped("bytes-incomplete-unverifiable"),
      goodEvidence.copy(declaredSha256 = "")                  -> AttachmentAck.Result.Skipped("digest-missing"),
      goodEvidence.copy(declaredSha256 = BadShapeSha)         -> AttachmentAck.Result.Skipped("digest-shape"),
      goodEvidence.copy(localSha256 = Some(""))               -> AttachmentAck.Result.Skipped("bytes-unavailable")
    )
    withStub(200) { stub =>
      val fs = fsFor(loggedIn(stub.url))
      gaps.foreach { case (ev, expected) =>
        val res = fs.ackAttachmentReceived("att-1", ev).unsafeRunSync()
        evidence("g", s"evidence=$ev", s"result=$res")
        assertEquals(res, expected)
      }
      assertEquals(stub.e4Requests, Nil, "全部缺口都必须是零 E4（fail-closed：缺证据 ≠ 通过）")
    }
  }

  // ===== (e) 失败静默容忍：不抛 + 用户面与成功路径逐字相同 =====

  test("(e) E4 失败（500 / 传输失败）⇒ 静默容忍：不抛，用户面结果与成功路径逐字相同") {
    // 成功路径的用户面（调用方可见的那个 IO[Unit]）
    val okUnit = withStub(200) { stub =>
      fsFor(loggedIn(stub.url)).ackAttachmentReceived("att-1", goodEvidence).void.attempt.unsafeRunSync()
    }
    // 失败路径：上游 500
    val boomUnit = withStub(500) { stub =>
      fsFor(loggedIn(stub.url)).ackAttachmentReceived("att-1", goodEvidence).void.attempt.unsafeRunSync()
    }
    // 失败路径：链路层失败（先登录，再停桩 ⇒ 真「连接被拒」，非「未登录」）
    val stub = new Stub(200)
    stub.start()
    val cli = loggedIn(stub.url)
    stub.stop()
    val fsNet   = fsFor(cli)
    val netUnit = fsNet.ackAttachmentReceived("att-1", goodEvidence).void.attempt.unsafeRunSync()
    val netRes  = fsNet.ackAttachmentReceived("att-1", goodEvidence).unsafeRunSync()

    evidence(
      "e",
      s"okUnit=$okUnit",
      s"boomUnit=$boomUnit",
      s"netUnit=$netUnit",
      s"netResult=$netRes"
    )
    assertEquals(okUnit, Right(()))
    assertEquals(boomUnit, Right(()), "上游 5xx 不得上抛")
    assertEquals(netUnit, Right(()), "传输失败不得上抛")
    assertEquals(boomUnit, okUnit, "用户面（调用方可见结果）与成功路径逐字相同")
    assertEquals(netUnit, okUnit, "用户面（调用方可见结果）与成功路径逐字相同")
    assert(netRes.isInstanceOf[AttachmentAck.Result.Failed], "结局如实是 Failed —— 只是**不上抛、不外溢**")
  }

  // ===== (f) 404/410 ⇒ 无需回执、零重试 =====

  test("(f) 404 ⇒ 无需回执（信息级）、零重试；幂等语义不靠客户端重发") {
    withStub(404) { stub =>
      val fs  = fsFor(loggedIn(stub.url))
      val res = fs.ackAttachmentReceived("att-1", goodEvidence).unsafeRunSync()
      evidence("f", s"result=$res", s"e4Count=${stub.e4Requests.size}")
      assertEquals(res, AttachmentAck.Result.Skipped("no-ack-needed-404"))
      assertEquals(stub.e4Requests.size, 1, "零重试：一次事件至多一次 E4（丢 ack 的兜底 = 服务端 TTL）")
    }
  }

  test("(f) 未登录（无 client）⇒ 静默失败、不抛、零请求") {
    val fs  = new FriendService(IO.pure(None), AgentMessagingConfig(mode = "auto"))
    val res = fs.ackAttachmentReceived("att-1", goodEvidence).unsafeRunSync()
    evidence("f-nologin", s"result=$res")
    assertEquals(res, AttachmentAck.Result.Failed("Not logged in"))
  }

  // ===== 闸本体（纯判定，无 IO）：判据表逐行钉死 =====

  test("闸本体：decide 表 —— 任一缺口即 Skip，命中才 Fire（本地 digest 原样回传）") {
    assertEquals(AttachmentAck.decide(goodEvidence), AttachmentAck.Decision.Fire(FixtureSha))
    assertEquals(
      AttachmentAck.decide(goodEvidence.copy(localSha256 = Some(FixtureSha.toUpperCase))),
      AttachmentAck.Decision.Fire(FixtureSha),
      "形态归一只做去空白/转小写；归一后仍须逐字相等"
    )
    assertEquals(AttachmentAck.decide(goodEvidence.copy(localSha256 = None)), AttachmentAck.Decision.Skip("bytes-unavailable"))
    assertEquals(AttachmentAck.decide(goodEvidence.copy(landedFinal = false)), AttachmentAck.Decision.Skip("not-landed-final"))
    assertEquals(AttachmentAck.decide(goodEvidence.copy(declaredSha256 = "  ")), AttachmentAck.Decision.Skip("digest-missing"))
    assertEquals(AttachmentAck.decide(goodEvidence.copy(localSha256 = Some(BadShapeSha))), AttachmentAck.Decision.Skip("digest-shape"))
    assertEquals(AttachmentAck.decide(goodEvidence.copy(receivedBytes = Some(1L))), AttachmentAck.Decision.Skip("bytes-incomplete"))
    assertEquals(AttachmentAck.decide(goodEvidence.copy(expectedBytes = None)), AttachmentAck.Decision.Skip("bytes-incomplete-unverifiable"))
    assertEquals(AttachmentAck.decide(goodEvidence.copy(localSha256 = Some(OtherSha))), AttachmentAck.Decision.Skip("digest-mismatch"))
    assert(AttachmentAck.isDigest(FixtureSha))
    assert(!AttachmentAck.isDigest(FixtureSha.toUpperCase))
    assert(!AttachmentAck.isDigest(BadShapeSha))
  }

  test("闸本体：默认（全缺省）证据 ⇒ 零 E4 —— 「什么都没上报」绝不等于「通过」") {
    val empty = AttachmentAck.Evidence(
      localSha256 = None,
      declaredSha256 = "",
      receivedBytes = None,
      expectedBytes = None,
      landedFinal = false
    )
    assertEquals(AttachmentAck.decide(empty), AttachmentAck.Decision.Skip("not-landed-final"))
    withStub(200) { stub =>
      val res = fsFor(loggedIn(stub.url)).ackAttachmentReceived("att-1", empty).unsafeRunSync()
      assertEquals(res, AttachmentAck.Result.Skipped("not-landed-final"))
      assertEquals(stub.e4Requests, Nil)
      assertEquals(
        stub.allRequests.filterNot(_.contains("/api/device/")),
        Nil,
        "闸在**发请求之前**拦下 ⇒ 桩上除登录外不该有任何请求"
      )
    }
  }

end FriendAttachAckSpec
