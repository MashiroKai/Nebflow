package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.JsonObject
import io.circe.syntax.*
import java.net.InetSocketAddress
import munit.FunSuite
import nebflow.core.tools.{FriendMessageTool, ToolContext}

/**
 * 4b 腿 A-5：**「无自报字段下判读对方能力」的三态实测钉**（裁定④）。
 *
 * ## 夹具声明（🔴 如实声明，不冒充生产实测）
 * 本件**不连真 neblink-server**：判定链对端 = 一个 `com.sun.net.httpserver` 的
 * **本机回环桩**（127.0.0.1，临时端口），它按 `mode` 模拟三种服务端形态：
 *   - `new`    = 有附件路由（E1 存在；非法声明 ⇒ 422；合法声明 ⇒ 201；E2 ⇒ 200；
 *                发消息 ⇒ 201 带 `attachments`）
 *   - `old`    = **无**该路由（任何未知路径 ⇒ 404，即 §G.2 的「老服务端」）
 *   - `broken` = 路由未知 + 服务端错误（500）
 * 客户端侧**全部是生产代码**（真 `NeblinkClient` 的 `withSession` + 真 HTTP 传输 +
 * 真 `FriendService.sendAsAgent` + 真 `AttachContract` 闸 + 真 `NeblinkFiles` 摘要）。
 * ⇒ 读数是「判定链行为」的实测；**不是**「与真 neblink-server 联调」的读数（后者
 * 本批不可得：腿 B 未落地，服务端无这些路由）。
 *
 * ## 判据（裁定④：零自报字段）
 * 只看**探测请求的响应码**：404 ⇒ 无能力；任何非 404 的 HTTP 码 ⇒ 路由存在 ⇒ 有能力；
 * 传输失败 / 5xx ⇒ 不可判。**不读**任何 body 里的能力声明（见「零自报字段」两钉）。
 */
class FriendAttachGateSpec extends FunSuite:

  // ===== 夹具：本机回环桩 =====

  private val FixtureBody  = "friendattach-4b\n" // 16 B；sha256 由 shasum 独立算得
  private val FixtureSha   = "8db0362fc0a30b7b224dbc9306016d57bea5c5a0e4eb882c38d337dccde454e3"

  private final class Stub(val mode: String):
    private val requests = scala.collection.mutable.ListBuffer.empty[String]
    private var server: HttpServer = null

    def start(): Unit =
      val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      s.createContext(
        "/",
        (ex: HttpExchange) =>
          val body = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
          val uri  = ex.getRequestURI.toString // path **含** query（E2 的 ?offset= 是判据的一部分）
          val path = ex.getRequestURI.getPath
          val auth = Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("<none>")
          val chunkSha = Option(ex.getRequestHeaders.getFirst("X-Chunk-Sha256")).getOrElse("<none>")
          requests.synchronized {
            requests += s"${ex.getRequestMethod} $uri | auth=${if auth.startsWith("Bearer ") then "Bearer <token>" else auth} | " +
              s"X-Chunk-Sha256=$chunkSha | body=${body.replace("\n", "\\n")}"
          }
          val (code, resp) =
            if path.startsWith("/api/device/") then (200, """{"token":"stub-session","networkId":"n1","deviceId":"d1","peers":[]}""")
            else if mode == "old" then (404, """{"error":"not found"}""")
            else if mode == "broken" then (500, """{"error":"internal"}""")
            else if path == "/api/friends" then
              (200, """{"friends":[{"userId":"u1","username":"alice","display_name":"Alice"}],"incoming":[],"outgoing":[]}""")
            else if path.endsWith("/attachments") && body.contains("\"size\":0") then (422, """{"error":"INVALID_ARGUMENT"}""")
            else if path.endsWith("/attachments") then (201, """{"attachmentId":"att-1","receivedBytes":0,"state":"uploading"}""")
            else if path.contains("/chunks") then (200, """{"receivedBytes":16,"complete":true,"state":"staged"}""")
            else if path.endsWith("/messages") then
              (
                201,
                """{"messageId":5,"conversationId":"c1","createdAt":123,"attachments":[{"id":"att-1","name":"probe.bin","size":16,"sha256":"x","state":"ready"}]}"""
              )
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

    /** 桩上收到的请求（原始行；证据用）。 */
    def log: List[String] = requests.synchronized(requests.toList)

    /** 除登录与能力探测外**没有**任何请求 ⇒ 零副作用断言（不白传字节）。 */
    def nonProbeRequests: List[String] =
      log.filterNot(l => l.contains("/api/device/") || (l.contains("/attachments") && l.contains("\"size\":0")))

  private def withStub[A](mode: String)(use: Stub => A): A =
    val stub = new Stub(mode)
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

  private def fixtureFile(): os.Path =
    val p = os.Path(java.nio.file.Files.createTempFile("friendattach-gate-", ".bin"))
    os.write.over(p, FixtureBody.getBytes("UTF-8"))
    p

  /** 证据落盘（stdout）：A-5 要求「逐态给命令原文 + 原始输出」——
    * 断言只判绿红，这行把**读数本身**打出来，供报告逐字引用。 */
  private def evidence(tag: String, lines: String*): Unit =
    lines.foreach(l => println(s"[A-5 $tag] $l"))

  private def emptyFile(): os.Path =
    os.Path(java.nio.file.Files.createTempFile("friendattach-empty-", ".bin"))

  // ===== 纯判定（无 IO；三态的**判定本体**） =====

  test("A-5 judge: 404 ⇒ Unsupported；422/429/403 ⇒ Supported；5xx/传输失败 ⇒ Undetermined") {
    assert(AttachmentCapability.judge(Left("""HTTP 404: {"error":"not found"}""")).isInstanceOf[AttachmentCapability.Unsupported])
    assert(AttachmentCapability.judge(Left("""HTTP 422: {"error":"INVALID_ARGUMENT"}""")).isInstanceOf[AttachmentCapability.Supported])
    assert(AttachmentCapability.judge(Left("""HTTP 429: {"error":"rate_limited"}""")).isInstanceOf[AttachmentCapability.Supported])
    assert(AttachmentCapability.judge(Left("""HTTP 403: {"error":"not_friends"}""")).isInstanceOf[AttachmentCapability.Supported])
    assert(AttachmentCapability.judge(Right("""{"attachmentId":"x"}""")).isInstanceOf[AttachmentCapability.Supported])
    assert(AttachmentCapability.judge(Left("HTTP 500: boom")).isInstanceOf[AttachmentCapability.Undetermined])
    assert(AttachmentCapability.judge(Left("Connection refused")).isInstanceOf[AttachmentCapability.Undetermined])
    assert(AttachmentCapability.judge(Left("Not logged in")).isInstanceOf[AttachmentCapability.Undetermined])
  }

  test("A-5 零自报字段（①）：body 里有没有「能力声明」都不改判词 —— 判据只在状态码") {
    assertEquals(
      AttachmentCapability.judge(Right("""{"supportsAttachments":true,"capabilities":["attachments"]}""")),
      AttachmentCapability.judge(Right("""{}"""))
    )
    // 404 的 body 里**声称**支持，也仍然是「无能力」（老服务端不可能返回这些码以外的东西）
    assert(
      AttachmentCapability.judge(Left("""HTTP 404: {"supportsAttachments":true}"""))
        .isInstanceOf[AttachmentCapability.Unsupported]
    )
  }

  test("A-5 零自报字段（②）：探测体是**固定常量**，不含任何客户端声明/协商字段") {
    assertEquals(AttachmentCapability.ProbeBody, """{"name":"","size":0,"sha256":"probe"}""")
    assertEquals(AttachmentCapability.probePath("u1"), "/api/friends/u1/attachments")
  }

  // ===== 态 1：对齐（允许带附件，且确实到达 wire/payload 面） =====

  test("A-5 态1 对齐：探测 = Supported，且上传链真的把 sha256/分块/附件 id 送上 wire") {
    withStub("new") { stub =>
      val cli  = loggedIn(stub.url)
      val cap  = cli.probeAttachmentCapability("u1").unsafeRunSync()
      val file = fixtureFile()
      val res = fsFor(cli).sendAsAgent("u1", "", List(file)).unsafeRunSync()

      val probe = cli.probeAttachmentCapability("u1").unsafeRunSync()
      val log   = stub.log

      // ① 探测读数
      assert(cap.isInstanceOf[AttachmentCapability.Supported], s"got $cap")
      assert(probe.asInstanceOf[AttachmentCapability.Supported].evidence.contains("422"), s"got $probe")
      // ② 探测请求本身：带应用会话令牌 + 固定常量体
      val probeLine = log.find(l => l.contains("/api/friends/u1/attachments") && l.contains("\"size\":0")).getOrElse(fail(s"no probe request: $log"))
      assert(probeLine.contains("auth=Bearer <token>"), probeLine)
      assert(probeLine.contains(AttachmentCapability.ProbeBody), probeLine)
      // ③ 发送真绿：FriendService 的面（回执在工具面加件数，见态 1b）
      assertEquals(res, Right("Message sent"))
      // ④ wire 面：E1 声明整件 sha256（独立算得）；E2 带块 sha 头；消息体带 attachmentId 列表
      assert(log.exists(l => l.contains("POST /api/friends/u1/attachments") && l.contains(FixtureSha)), s"E1 must carry the real whole-file sha256: $log")
      assert(log.exists(l => l.contains("/chunks?offset=0") && l.contains("X-Chunk-Sha256=" + FixtureSha)), s"E2 must carry the chunk sha header: $log")
      assert(log.exists(l => l.contains("POST /api/friends/u1/messages") && l.contains("\"attachments\":[\"att-1\"]")), s"send payload must carry attachment ids: $log")
      evidence(
        "态1 对齐",
        s"probe=$cap",
        s"requests=${log.filterNot(_.contains("/api/device/")).map(_.take(230)).mkString(" ;; ")}",
        s"sendResult=$res"
      )
    }
  }

  test("A-5 态1b 工具面：同一路径经 SendMessage 工具回执可判读（件数可见，禁「成功但附件消失」）") {
    withStub("new") { stub =>
      val cli  = loggedIn(stub.url)
      val fs   = fsFor(cli)
      val file = fixtureFile()
      // 注：`FriendMessageTool.service` 是**全局装配缝**（@volatile），并行跑的其它 spec
      // 也会 initialize 它 ⇒ 极小概率被顶掉。命中该形态时重试一次（并在报告里如实登记
      // 这一夹具面的已知竞态），断言本身不变。
      def attempt(): Either[nebflow.core.tools.ToolError, String] =
        FriendMessageTool.initialize(fs)
        FriendMessageTool
          .call(
            JsonObject(
              "to"          -> "alice".asJson,
              "message"     -> "".asJson,
              "attachments" -> io.circe.Json.arr(file.toString.asJson)
            ),
            ToolContext(projectRoot = "/tmp")
          )
          .unsafeRunSync()
      val out = attempt() match
        case Left(e) if e.message.contains("unavailable") || e.message.contains("not found") => attempt()
        case other                                                                            => other
      assert(out.isRight, s"tool send must succeed on a capable server, got ${out.left.toOption.map(_.message)}")
      val receipt = out.toOption.get
      assert(receipt.contains("已发送给"), receipt)
      assert(receipt.contains("1 件附件"), s"receipt must not hide the attachment (禁「成功但附件消失」), got: $receipt")
      // 空正文 + 附件 ⇒ 合法（§B.4），且 wire 上确实带了附件 id
      assert(stub.log.exists(l => l.contains("POST /api/friends/u1/messages") && l.contains("\"attachments\":[\"att-1\"]")), stub.log.toString)
      evidence("态1b 工具面", s"toolResult=$receipt")
    }
  }

  // ===== 态 2：不对齐（明确拒绝 + 零上传） =====

  test("A-5 态2 不对齐：404 ⇒ Unsupported，工具面明确拒绝且**零上传**（不白传字节）") {
    withStub("old") { stub =>
      val cli  = loggedIn(stub.url)
      val file = fixtureFile()
      val res  = fsFor(cli).sendAsAgent("u1", "hi", List(file)).unsafeRunSync()

      assert(res.isLeft, s"unsupported server must NOT send: $res")
      val msg = res.left.toOption.get
      assert(msg.contains("does not support attachments"), msg)
      assert(msg.contains("Nothing was uploaded and no message was sent"), msg)
      assert(msg.contains("404"), msg)
      assertEquals(stub.nonProbeRequests, Nil, s"zero uploads/zero send expected, got: ${stub.log}")
      evidence("态2 不对齐", s"probe=${cli.probeAttachmentCapability("u1").unsafeRunSync()}", s"refusal=$msg", s"非探测请求=${stub.nonProbeRequests}")
    }
  }

  // ===== 态 3：不可判（确定且可判读的行为；禁猜） =====

  test("A-5 态3a 不可判（服务端 5xx）：Undetermined，明确拒绝且零上传，文案与「不支持」可区分") {
    withStub("broken") { stub =>
      val cli  = loggedIn(stub.url)
      val file = fixtureFile()
      val res  = fsFor(cli).sendAsAgent("u1", "hi", List(file)).unsafeRunSync()

      assert(res.isLeft, s"undetermined must NOT send: $res")
      val msg = res.left.toOption.get
      assert(msg.contains("could not confirm"), msg)
      assert(!msg.contains("does not support attachments"), msg)
      assert(msg.contains("Nothing was uploaded and no message was sent"), msg)
      assertEquals(stub.nonProbeRequests, Nil, s"zero uploads expected, got: ${stub.log}")
      evidence("态3a 服务端 5xx", s"probe=${cli.probeAttachmentCapability("u1").unsafeRunSync()}", s"refusal=$msg", s"非探测请求=${stub.nonProbeRequests}")
    }
  }

  test("A-5 态3b 不可判（传输层不可达）：Undetermined（不是 Unsupported —— 两者文案必须可区分）") {
    val stub = new Stub("new")
    stub.start()
    val cli = loggedIn(stub.url)
    val file = fixtureFile()
    stub.stop() // 会话已建立 ⇒ 之后任何请求都是传输失败（连接被拒）
    try
      val cap = cli.probeAttachmentCapability("u1").unsafeRunSync()
      assert(cap.isInstanceOf[AttachmentCapability.Undetermined], s"got $cap")
      val res = fsFor(cli).sendAsAgent("u1", "hi", List(file)).unsafeRunSync()
      assert(res.isLeft, res.toString)
      assert(res.left.toOption.get.contains("could not confirm"), res.left.toOption.get)
      evidence("态3b 传输不可达", s"probe=$cap", s"refusal=${res.left.toOption.get}")
    finally stub.stop()
  }

  // ===== 本地闸（A-4 的「能发就能带附件」不改变既有闸位） =====

  test("A-4 本地闸：>9 件 / 空件 在**上传之前** fail-fast（零上游往返）") {
    withStub("new") { stub =>
      val cli = loggedIn(stub.url)
      val f   = fixtureFile()
      val before = stub.log.size
      val ten    = List.fill(10)(f)
      val tooMany = fsFor(cli).sendAsAgent("u1", "hi", ten).unsafeRunSync()
      assert(tooMany.isLeft)
      assert(tooMany.left.toOption.get.contains("ATTACH_TOO_MANY"), tooMany.left.toOption.get)
      val empty = emptyFile()
      val emptyRes = fsFor(cli).sendAsAgent("u1", "hi", List(empty)).unsafeRunSync()
      assert(emptyRes.isLeft)
      assert(emptyRes.left.toOption.get.contains("size <= 0"), emptyRes.left.toOption.get)
      assertEquals(stub.log.size, before, "本地闸必须在任何上游请求之前拦住（含探测）")
    }
  }
end FriendAttachGateSpec
