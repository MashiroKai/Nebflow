package nebflow.social

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

/**
 * 入站图片必达批（2026-10-24）· 飞书腿 — the inbound image download MUST-ARRIVE
 * spec, merged into the weixin batch by the author ruling (2026-10-24 11:1x).
 *
 * The incident (2026-10-24 11:10, production): the author sent images from
 * Feishu and EVERY download failed with
 * `JsonIOException: Failed making field 'java.io.ByteArrayOutputStream#buf'
 * accessible` (message_id=om_x100b62f6542df0accfdb07e3e90de16,
 * image_key=img_v3_0216p_219d4d33-b0e5-4a8e-9aa2-d0dcee51c2bg) — zero images
 * ever landed. Root cause, confirmed against the pinned SDK
 * (com.larksuite.oapi:oapi-sdk:2.8.5): `GetImageResp extends
 * BaseResponse<java.io.ByteArrayOutputStream>` — a STREAM OBJECT inside the
 * Gson-serialized DTO. On any non-binary (error-JSON) response the SDK runs
 * Gson over that DTO and, under JDK 23 strong encapsulation, `setAccessible`
 * on `ByteArrayOutputStream#buf` throws BEFORE Feishu's real code/msg can be
 * read — the refusal is permanently masked and the leg can never report why.
 *
 * The fix owns the wire: [[FeishuChannel.getImageVia]] = tenant token + raw
 * GET over the injectable [[FeishuChannel.RawHttp]] seam, bytes in/out, the
 * error JSON read with circe. These tests pin the three must-arrive
 * obligations:
 *
 *   ① 200 binary → the exact bytes land (no re-encode, no stream DTO);
 *   ② any non-2xx → Feishu's OWN code/msg surfaces — never a
 *     JsonIOException, never a throw (the incident's exact shape is
 *     red-by-construction here);
 *   ③ the transport/token failures become explicit readings (never a raise).
 *
 * feishu-image-endpoint 批（2026-11-07）: the FI* cases above now pin the LEGACY
 * leg (`im/v1/images/:image_key`, the bot's own uploads — it survives only as
 * the controlled fallback and as the bot-upload download face). The MR* cases at
 * the bottom pin the leg inbound message resources must ride:
 * `im/v1/messages/:message_id/resources/:file_key?type=…`.
 *
 * Deliberately NO network and NO credential: every dial is a stub.
 */
class FeishuInboundImageSpec extends FunSuite:

  private val PNG = Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)

  /** A stub [[FeishuChannel.RawHttp]] capturing safe request shape, never header values. */
  private final class StubHttp:
    final case class Call(method: String, url: String, hasAuthorization: Boolean, bodyPresent: Boolean)
    val calls = scala.collection.mutable.ListBuffer.empty[Call]
    var routes: PartialFunction[String, Either[String, (Int, Array[Byte])]] = PartialFunction.empty

    def tokenOk(token: String = "stub-token-shape-only"): Unit =
      routes = routes.orElse {
        case url if url.endsWith("/open-apis/auth/v3/tenant_access_token/internal") =>
          Right((200, Json.obj("code" -> 0.asJson, "tenant_access_token" -> token.asJson).noSpaces.getBytes("UTF-8")))
      }

    def toSeam: FeishuChannel.RawHttp = (url, headers, body) =>
      calls.synchronized {
        calls += Call(if body.isDefined then "POST" else "GET", url, headers.contains("Authorization"), body.isDefined)
      }
      if routes.isDefinedAt(url) then routes(url) else Left("no stub route")
  end StubHttp

  /** Unique appId per test — the object-level token cache must not leak between tests. */
  private var appSeq = 0

  private def freshCreds(): (String, String) =
    appSeq += 1
    (s"app-fi-$appSeq-${System.identityHashCode(this)}", s"secret-shape-only-$appSeq")

  test("FI1 a 200 binary body lands byte-exact (the must-arrive happy path)") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse {
      case url if url.endsWith("/open-apis/im/v1/images/img_key_1") =>
        Right((200, PNG))
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getImageVia(stub.toSeam)(appId, secret, "feishu", "img_key_1")
    assert(r.ok, s"must land, got $r")
    assertEquals(r.bytes.map(_.toList), Some(PNG.toList), "the exact channel bytes — no re-encode")
    assertEquals(r.code, 200)
    val imageCall = stub.calls.find(_.url.contains("/open-apis/im/v1/images/")).get
    assertEquals(imageCall.method, "GET")
    assert(imageCall.url.endsWith("/open-apis/im/v1/images/img_key_1"))
    assert(imageCall.hasAuthorization, "Authorization header must be present; its value is intentionally not captured")
    assert(!imageCall.bodyPresent)
  }

  test("FI1b image_key is exactly one URL-encoded path segment") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse { case _ => Right((200, PNG)) }
    val (appId, secret) = freshCreds()
    val key = "key/with space?"
    val r = FeishuChannel.getImageVia(stub.toSeam)(appId, secret, "feishu", key)
    assert(r.ok)
    val imageCall = stub.calls.find(_.url.contains("/open-apis/im/v1/images/")).get
    assert(imageCall.url.endsWith("/open-apis/im/v1/images/key%2Fwith%20space%3F"), imageCall.url)
    assertEquals(imageCall.url.split("/open-apis/im/v1/images/", 2).last.count(_ == '/'), 0)
  }

  test(
    "FI2 the incident shape: an error-JSON refusal surfaces feishu's OWN code/msg (never JsonIOException, never a throw)"
  ) {
    val stub = new StubHttp
    stub.tokenOk()
    // the 2026-10-24 11:10:39 burst shape — feishu refusing the storm
    stub.routes = stub.routes.orElse {
      case url if url.endsWith("/open-apis/im/v1/images/img_v3_0216p_incident") =>
        Right(
          (429, Json.obj("code" -> 99991400L.asJson, "msg" -> "too many requests".asJson).noSpaces.getBytes("UTF-8"))
        )
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getImageVia(stub.toSeam)(appId, secret, "feishu", "img_v3_0216p_incident")
    assert(!r.ok)
    assertEquals(r.code, 99991400, "feishu's code — the SDK used to mask this behind JsonIOException")
    assert(r.msg.contains("http=429") && r.msg.contains("endpoint=im/v1/images/:image_key"))
    assert(r.msg.contains("code=99991400") && r.msg.contains("msg=too many requests"))
    assert(
      !r.msg.contains("JsonIOException") && !r.msg.contains("ByteArrayOutputStream"),
      s"the stream-DTO plague must never resurface, got ${r.msg}"
    )
  }

  test("FI2b 234001 refusal returns safe diagnostic evidence without exposing the image key") {
    val stub = new StubHttp
    stub.tokenOk()
    val testKey = "diagnostic-key-do-not-leak"
    stub.routes = stub.routes.orElse {
      case url if url.endsWith("/open-apis/im/v1/images/diagnostic-key-do-not-leak") =>
        Right((400, Json.obj("code" -> 234001.asJson, "msg" -> "Invalid request param.".asJson).noSpaces.getBytes("UTF-8")))
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getImageVia(stub.toSeam)(appId, secret, "feishu", testKey)
    assert(!r.ok)
    assertEquals(r.code, 234001)
    assert(r.msg.contains("http=400") && r.msg.contains("endpoint=im/v1/images/:image_key"))
    assert(r.msg.contains("fields=code:true,msg:true") && r.msg.contains("Invalid request param."))
    assert(!r.msg.contains(testKey), "diagnostic must not contain the complete user image key")
  }

  test("FI3 a refused tenant-token request is an explicit reading (code/msg, no secret on the wire)") {
    val stub = new StubHttp
    stub.routes = {
      case url if url.endsWith("/open-apis/auth/v3/tenant_access_token/internal") =>
        Right((200, Json.obj("code" -> 9499.asJson, "msg" -> "bad app_id".asJson).noSpaces.getBytes("UTF-8")))
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getImageVia(stub.toSeam)(appId, secret, "feishu", "img_x")
    assert(!r.ok)
    assertEquals(r.code, -1)
    assert(r.msg.contains("token refused code=9499") && r.msg.contains("bad app_id"), s"got ${r.msg}")
    assert(!r.msg.contains(secret), "the secret must never ride a reading")
  }

  test("FI4 a dead transport (token leg) is an explicit reading, not a raise") {
    val stub = new StubHttp
    stub.routes = PartialFunction.empty // nothing answers
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getImageVia(stub.toSeam)(appId, secret, "feishu", "img_x")
    assert(!r.ok)
    assert(r.msg.contains("token request failed"), s"got ${r.msg}")
  }

  test("FI5 a non-JSON error body gets an honest shape note") {
    // revive-p0 批 2026-10-25 字面勘误："<html>gateway</html>" 是 20 字节（6+7+7），
    // 快照 spec 手写 21B 未跑过（构建机饱和）；JSON 分支语义按 FI2 钉死的
    // 「feishu 原生 code/msg 上浮」对齐——code 取 body 里的 99991400，非 HTTP 429。
    assertEquals(
      FeishuChannel.imageErrorReading(502, "<html>gateway</html>".getBytes("UTF-8")),
      (502, "Feishu image API refused (http=502 endpoint=im/v1/images/:image_key fields=code:false,msg:false; non-JSON body 20B)")
    )
    assertEquals(
      FeishuChannel.imageErrorReading(
        429,
        Json.obj("code" -> 99991400L.asJson, "msg" -> "busy".asJson).noSpaces.getBytes("UTF-8")
      ),
      (99991400, "Feishu image API refused (http=429 endpoint=im/v1/images/:image_key fields=code:true,msg:true; code=99991400 msg=busy)")
    )
  }

  test("FI6 an empty 200 is a failure, never an empty-success") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse {
      case url if url.endsWith("/open-apis/im/v1/images/img_empty") =>
        Right((200, Array.emptyByteArray))
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getImageVia(stub.toSeam)(appId, secret, "feishu", "img_empty")
    assert(!r.ok)
    assertEquals(r.code, 200)
    assert(r.msg.contains("returned no bytes"), s"got ${r.msg}")
  }

  test("FI7 the tenant token is cached per (appId, region): a second download does NOT re-POST the token") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse {
      case url if url.contains("/open-apis/im/v1/images/") =>
        Right((200, PNG))
    }
    val (appId, secret) = freshCreds()
    val r1 = FeishuChannel.getImageVia(stub.toSeam)(appId, secret, "feishu", "img_a")
    val r2 = FeishuChannel.getImageVia(stub.toSeam)(appId, secret, "feishu", "img_b")
    assert(r1.ok && r2.ok)
    val tokenPosts = stub.calls.count(call => call.method == "POST" && call.url.contains("tenant_access_token"))
    assertEquals(tokenPosts, 1, "exactly one token fetch for two downloads")
  }

  // ─────────── feishu-image-endpoint 批（2026-11-07）: 入站资源走哪条腿 ───────────
  //
  // 作者报障（2026-11-07，飞书入口反复弹）：
  //   code=234001 msg=Invalid request param.
  //   endpoint=im/v1/images/:image_key
  //   message_id=om_x100b61de7010653cb09af6a3e618fef
  //   image_key=img_v3_02177_7c134581-4928-40cf-a89b-8b29f82f1b5g
  //
  // 官方文档（2026-11-07 核对）：
  //   · 「下载图片」GET /open-apis/im/v1/images/:image_key
  //     https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/image/get
  //     「只能下载由当前机器人上传的图片，且上传时图片类型为 用于发送消息」；并明写
  //     「如果你需要下载用户发送消息内的资源文件，可使用[获取消息中的资源文件]接口」。
  //   · 「获取消息中的资源文件」GET /open-apis/im/v1/messages/:message_id/resources/:file_key
  //     https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/message-resource/get
  //     查询参数 type 必填（image|file），message_id 与 file_key「需要匹配」。
  //
  // 入站事件的 image_key 属于后一条腿 ⇒ 前一条腿必然 234001。下面的 MR* 钉死：
  //   ① 默认腿是 messages/resources（路径、查询参数 type、单段编码）；
  //   ② 故障读数复用 cd68b495e 的 http/code/msg/endpoint 四件，且标明 leg=；
  //   ③ 完整 key 不进读数（截断头尾）；
  //   ④ 只有「没有 message_id」才受控回退旧腿，且读数标 leg=legacy-images。

  private val MID = "om_x100b61de7010653cb09af6a3e618fef"
  private val KEY = "img_v3_02177_7c134581-4928-40cf-a89b-8b29f82f1b5g"

  test("MR1 the inbound leg is im/v1/messages/:message_id/resources/:file_key?type=image — never the images leg") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse {
      case url if url.endsWith(s"/open-apis/im/v1/messages/$MID/resources/$KEY?type=image") => Right((200, PNG))
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getInboundResourceVia(stub.toSeam)(appId, secret, "feishu", MID, KEY, "image")
    assert(r.ok, s"must land, got $r")
    assertEquals(r.bytes.map(_.toList), Some(PNG.toList), "the exact channel bytes — no re-encode")
    assertEquals(r.code, 200)
    assert(r.msg.contains("leg=message-resources"), s"the reading names the leg it rode: ${r.msg}")
    val call = stub.calls.find(_.url.contains("/open-apis/im/v1/messages/")).get
    assertEquals(call.method, "GET")
    assertEquals(
      call.url,
      s"https://open.feishu.cn/open-apis/im/v1/messages/$MID/resources/$KEY?type=image",
      "the exact official shape of 「获取消息中的资源文件」"
    )
    assert(call.hasAuthorization, "Authorization header must be present; its value is intentionally not captured")
    assert(!call.bodyPresent)
    assert(
      !stub.calls.exists(_.url.contains("/open-apis/im/v1/images/")),
      "with a message_id present the legacy images leg must NOT be ridden"
    )
  }

  test("MR2 type=file for a file/audio/video resource, same path shape") {
    val stub = new StubHttp
    stub.tokenOk()
    val fileKey = "file_v3_9e2c"
    stub.routes = stub.routes.orElse {
      case url if url.endsWith(s"/open-apis/im/v1/messages/$MID/resources/$fileKey?type=file") =>
        Right((200, PNG))
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getInboundResourceVia(stub.toSeam)(appId, secret, "feishu", MID, fileKey, "file")
    assert(r.ok, s"must land, got $r")
    val call = stub.calls.find(_.url.contains("/open-apis/im/v1/messages/")).get
    assert(call.url.endsWith(s"/resources/$fileKey?type=file"), call.url)
    assertEquals(FeishuChannel.ResourceKind.ofMessageType("file"), Some("file"))
    assertEquals(FeishuChannel.ResourceKind.ofMessageType("audio"), Some("file"))
    assertEquals(FeishuChannel.ResourceKind.ofMessageType("media"), Some("file"))
    assertEquals(FeishuChannel.ResourceKind.ofMessageType("image"), Some("image"))
    assertEquals(FeishuChannel.ResourceKind.ofMessageType("text"), None, "unknown types are not guessed")
  }

  test("MR3 message_id and file_key are each exactly ONE URL-encoded path segment") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse { case _ => Right((200, PNG)) }
    val (appId, secret) = freshCreds()
    val mid = "om_x/1 2?3"
    val key = "img_v3/a b#c"
    val r = FeishuChannel.getInboundResourceVia(stub.toSeam)(appId, secret, "feishu", mid, key, "image")
    assert(r.ok, s"must land, got $r")
    val call = stub.calls.find(_.url.contains("/open-apis/im/v1/messages/")).get
    assertEquals(
      call.url,
      "https://open.feishu.cn/open-apis/im/v1/messages/om_x%2F1%202%3F3/resources/img_v3%2Fa%20b%23c?type=image",
      "no key character may escape its own segment"
    )
    assertEquals(call.url.count(_ == '?'), 1, "exactly one query separator — the encoded keys must not smuggle another")
    assertEquals(call.url.split("\\?", 2)(1), "type=image")
  }

  test("MR4 the author's exact failure shape on the CORRECT leg: code/msg/http/endpoint + leg, no full key") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse {
      case url if url.startsWith(s"https://open.feishu.cn/open-apis/im/v1/messages/$MID/resources/") =>
        Right((400, Json.obj("code" -> 234001.asJson, "msg" -> "Invalid request param.".asJson).noSpaces.getBytes("UTF-8")))
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getInboundResourceVia(stub.toSeam)(appId, secret, "feishu", MID, KEY, "image")
    assert(!r.ok)
    assertEquals(r.code, 234001)
    assert(r.msg.contains("leg=message-resources"), s"the diagnostic names the leg: ${r.msg}")
    assert(r.msg.contains("http=400"), s"http status rides the reading: ${r.msg}")
    assert(r.msg.contains("endpoint=im/v1/messages/:message_id/resources/:file_key"), s"endpoint rides it: ${r.msg}")
    assert(r.msg.contains("fields=code:true,msg:true") && r.msg.contains("Invalid request param."), s"got ${r.msg}")
    assert(!r.msg.contains(KEY), s"the COMPLETE key must not leak: ${r.msg}")
    // 这条 body 没回显 key，「截断」在这里是无操作——回显时的截断形由 MR8 钉死。
    assert(r.msg.length < 400, "the reading stays a one-liner, not a body dump")
  }

  test("MR5 an event with NO message_id falls back to the legacy images leg — and the reading says so") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse {
      case url if url.endsWith(s"/open-apis/im/v1/images/$KEY") => Right((200, PNG))
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getInboundResourceVia(stub.toSeam)(appId, secret, "feishu", "  ", KEY, "image")
    assert(r.ok, s"the controlled fallback must still land, got $r")
    assert(r.msg.contains("leg=legacy-images"), s"the reading names the fallback leg: ${r.msg}")
    assert(
      stub.calls.exists(_.url.endsWith(s"/open-apis/im/v1/images/$KEY")),
      "the fallback rode im/v1/images/:image_key"
    )
    assert(
      !stub.calls.exists(_.url.contains("/open-apis/im/v1/messages/")),
      "no message_id ⇒ the messages leg is unreachable by construction"
    )
  }

  test("MR5b a fallback refusal still names the leg and the underlying endpoint") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse {
      case url if url.startsWith("https://open.feishu.cn/open-apis/im/v1/images/") =>
        Right((400, Json.obj("code" -> 234008.asJson, "msg" -> "The app is not the resource sender".asJson).noSpaces.getBytes("UTF-8")))
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getInboundResourceVia(stub.toSeam)(appId, secret, "feishu", "", KEY, "image")
    assert(!r.ok)
    assertEquals(r.code, 234008)
    assert(r.msg.contains("leg=legacy-images (no message_id on the event)"), s"got ${r.msg}")
    assert(r.msg.contains("endpoint=im/v1/images/:image_key"), s"got ${r.msg}")
    assert(!r.msg.contains(KEY))
  }

  test("MR6 an unsupported resource type is refused BEFORE any network call") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse { case _ => Right((200, PNG)) }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getInboundResourceVia(stub.toSeam)(appId, secret, "feishu", MID, KEY, "video")
    assert(!r.ok)
    assert(r.msg.contains("unsupported resource type 'video'") && r.msg.contains("expected image|file"), s"got ${r.msg}")
    assertEquals(stub.calls.size, 0, "no token POST, no GET — the type gate is local")
  }

  test("MR7 a missing file_key is refused BEFORE any network call") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse { case _ => Right((200, PNG)) }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getInboundResourceVia(stub.toSeam)(appId, secret, "feishu", MID, "   ", "image")
    assert(!r.ok)
    assert(r.msg.contains("file_key missing"), s"got ${r.msg}")
    assertEquals(stub.calls.size, 0)
  }

  test("MR8 an echoed full key in feishu's msg is scrubbed to the truncated form") {
    val stub = new StubHttp
    stub.tokenOk()
    stub.routes = stub.routes.orElse {
      case url if url.startsWith(s"https://open.feishu.cn/open-apis/im/v1/messages/$MID/resources/") =>
        Right((400, Json.obj("code" -> 234003.asJson, "msg" -> s"File not in message: $KEY".asJson).noSpaces.getBytes("UTF-8")))
    }
    val (appId, secret) = freshCreds()
    val r = FeishuChannel.getInboundResourceVia(stub.toSeam)(appId, secret, "feishu", MID, KEY, "image")
    assert(!r.ok)
    assertEquals(r.code, 234003)
    assert(!r.msg.contains(KEY), s"the full key must be scrubbed: ${r.msg}")
    assert(r.msg.contains(s"File not in message: ${FeishuChannel.maskKey(KEY)}"), s"got ${r.msg}")
  }

  test("MR9 maskKey: short keys survive intact, long keys keep head+tail only") {
    assertEquals(FeishuChannel.maskKey("img_v3_bad"), "img_v3_bad")
    val masked = FeishuChannel.maskKey(KEY)
    assertEquals(masked, s"${KEY.take(8)}…${KEY.takeRight(4)}")
    assert(!masked.contains(KEY) && masked.length < KEY.length)
    assertEquals(FeishuChannel.maskKey(""), "")
  }

end FeishuInboundImageSpec
