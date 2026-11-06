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

end FeishuInboundImageSpec
