package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Response, Status, Uri}
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/**
 * Pop 图片 403 批（2026-10-24）：`/api/nf-file` **判据拒的日志可见性**验收。
 *
 * 作者报障「Failed to render: HTTP 403 看不到图」反复出现时，宿主日志对
 * nf-file 全族拒绝**零命中**——修前路由对 verdict 判据拒（credential-path /
 * credential-hardlink / not-found）与票拒（invalid-ticket / expired /
 * path-mismatch）都不留痕，403 的确切分支只能靠 curl 复现猜。本 spec 把
 * 日志形状钉死：
 *
 *   ① 票拒（bogus ticket ⇒ 403 invalid-ticket）：恰好 1 行 `nf-file denied:`，
 *      含 status=403 / reason=invalid-ticket / path / remote 四键；
 *   ② 判据拒（basename 命中凭据形态 ⇒ 403 credential-path）：同样恰好 1 行
 *      带 reason=credential-path（identity 层，不依赖策略根）；
 *   ③ 负控：有效票 200 字节面**零** deny 行（成功腿不得刷日志）；
 *   ④ 隧道面等权：带 tunnel-mark 头（x-stand-tunnel-mark）的请求与直请求
 *      同判——有效票 200、票拒 403 同 reason（网关 nf-file 判据零隧道依赖，
 *      隧道-leg 与 localhost-leg 等权的机械钉）。
 *
 * 日志捕获 = logback ListAppender 挂到生产同名 logger `nebflow.nf-file`
 * （NebflowLogger 经 LoggerFactory 取同名 logger ⇒ 捕获的是生产写点本体，
 * 与 DeletePathAuditLogSpec 同法）。
 */
class NfFileDenyLogSpec extends CatsEffectSuite:

  private val store = NfTicketStore.unsafeCreate(1800)

  /** 与 NfFileRoutesSpec 同款注入策略：判据主体不在 P1/P3，R2 inode 集为空。 */
  private val policy = NfFilePolicy.NfPathPolicy(
    java.nio.file.Paths.get("/nonexistent-nebflow-data-root"),
    java.nio.file.Paths.get("/nonexistent-nebflow-workspace"),
    Set.empty
  )

  private val routes = NfFileRoutes.nfFileRoutes(store, policy)

  private def capturedLogs[A](body: => A): (A, List[String]) =
    val lg   = LoggerFactory.getLogger("nebflow.nf-file").asInstanceOf[LogbackLogger]
    val app  = new ListAppender[ILoggingEvent]()
    app.start()
    lg.addAppender(app)
    try
      val out = body
      (out, app.list.asScala.toList.map(_.getFormattedMessage))
    finally lg.detachAppender(app)

  private def denyLines(lines: List[String]): List[String] =
    lines.filter(_.contains("nf-file denied:"))

  private def get(path: String, ticket: String, extraHeader: (String, String) = null): IO[org.http4s.Response[IO]] =
    var req = Request[IO](Method.GET, Uri.unsafeFromString(s"http://localhost/api/nf-file?path=$path&ticket=$ticket"))
    if extraHeader != null then req = req.putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString(extraHeader._1), extraHeader._2))
    routes.run(req).value.map(_.getOrElse(Response[IO](Status.NotFound)))

  test("① 票拒写 1 行可 grep 日志：status/reason/path/remote 四键齐") {
    val tmp  = os.temp.dir(prefix = "nffile-deny-log")
    val file = tmp / "shot.png"
    os.write(file, Array[Byte](1, 2, 3))
    val q     = java.net.URLEncoder.encode(file.toString, "UTF-8")
    val (resp, logs) = capturedLogs {
      get(q, "bogus-ticket-value").unsafeRunSync()
    }
    assertEquals(resp.status, Status.Forbidden, "bogus ticket ⇒ 403（行为不变）")
    assertEquals(resp.headers.get(org.typelevel.ci.CIString("X-Nf-Reason")).map(_.head.value), Some("invalid-ticket"))
    val mine = denyLines(logs)
    assertEquals(mine.size, 1, s"票拒恰好 1 行 deny 日志，实得 $mine")
    val line = mine.head
    assert(line.contains("status=403"), line)
    assert(line.contains("reason=invalid-ticket"), line)
    assert(line.contains(s"path=${file.toString}"), line) // 路由日志记 req.params（percent-decode 后的实路径）
    assert(line.contains("remote="), line)
    assert(!line.contains("bogus-ticket-value"), "ticket 本体（opaque 凭据）永不落日志（T2 纪律）")
    os.remove.all(tmp)
  }

  test("② 判据拒（credential-path）同样留痕：reason=credential-path") {
    val tmp  = os.temp.dir(prefix = "nffile-deny-log-cred")
    val file = tmp / "auth.json" // basename 命中 NfCredentialNamePattern（identity 层，根无关）
    os.write(file, "{}")
    val q     = java.net.URLEncoder.encode(file.toString, "UTF-8")
    val (resp, logs) = capturedLogs {
      get(q, "any-ticket").unsafeRunSync()
    }
    assertEquals(resp.status, Status.Forbidden, "凭据形态 basename ⇒ 403 credential-path（行为不变）")
    assertEquals(resp.headers.get(org.typelevel.ci.CIString("X-Nf-Reason")).map(_.head.value), Some("credential-path"))
    val mine = denyLines(logs)
    assertEquals(mine.size, 1, s"判据拒恰好 1 行 deny 日志，实得 $mine")
    assert(mine.head.contains("reason=credential-path"), mine.head)
    assert(mine.head.contains("status=403"), mine.head)
    os.remove.all(tmp)
  }

  test("③ 负控：有效票 200 成功腿零 deny 日志") {
    val tmp  = os.temp.dir(prefix = "nffile-deny-log-ok")
    val file = tmp / "ok.png"
    os.write(file, Array[Byte](9, 8, 7))
    val q = java.net.URLEncoder.encode(file.toString, "UTF-8")
    val t = store.issue("sess-denylog", file.toString).unsafeRunSync().token
    val (resp, logs) = capturedLogs {
      get(q, t).unsafeRunSync()
    }
    assertEquals(resp.status, Status.Ok, "有效票 ⇒ 200")
    assert(denyLines(logs).isEmpty, s"成功腿不得刷 deny 日志，实得 ${denyLines(logs)}")
    os.remove.all(tmp)
  }

  test("④ 隧道面等权：tunnel-mark 头请求与直请求同判（200 / 同 reason 403）") {
    val tmp  = os.temp.dir(prefix = "nffile-deny-log-tunnel")
    val file = tmp / "t.png"
    os.write(file, Array[Byte](5, 5, 5))
    val q = java.net.URLEncoder.encode(file.toString, "UTF-8")
    val mark = nebflow.shared.TunnelOriginMark.headerName
    // 等权正控：带 tunnel-mark 头的有效票请求 ⇒ 200 字节面。
    val t = store.issue("sess-tunnel", file.toString).unsafeRunSync().token
    val ok = get(q, t, mark -> "any-mark-value").unsafeRunSync()
    assertEquals(ok.status, Status.Ok, "隧道 leg 与 localhost leg 等权：判据不读 mark（红线）")
    // 等权负控：带 tunnel-mark 头的 bogus 票 ⇒ 403 + 同 reason + 日志留痕同形。
    val (resp, logs) = capturedLogs {
      get(q, "bogus-again", mark -> "any-mark-value").unsafeRunSync()
    }
    assertEquals(resp.status, Status.Forbidden)
    assertEquals(resp.headers.get(org.typelevel.ci.CIString("X-Nf-Reason")).map(_.head.value), Some("invalid-ticket"))
    val mine = denyLines(logs)
    assertEquals(mine.size, 1, s"隧道 leg 票拒同样 1 行 deny 日志，实得 $mine")
    assert(mine.head.contains("reason=invalid-ticket"), mine.head)
    os.remove.all(tmp)
  }
end NfFileDenyLogSpec
