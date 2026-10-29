package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.parser.decode
import munit.FunSuite
import nebflow.core.daemon.{DaemonConfig, DaemonService, DaemonStatus, DaemonStore}
import nebflow.perception.PerceptionClient
import nebflow.shared.PathUtil

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import scala.jdk.StreamConverters.*

/**
 * 统一 daemon 管理进程生命周期 spec（统一 daemon 批 2026-10-29；作者裁定
 * 「独立daemon吧 正好和我们心跳进程合并 那就是管daemon的」）。
 *
 * 覆盖任务令验证面「daemon 生命周期新 spec（起停/握手/崩溃重启/健康面）」：
 *  - 托管命令面（buildCommand 形状钉 + jpackage 形态拒 spawn）；
 *  - boot token（0600 落盘 + 每 boot 轮换）；
 *  - 托管条目 upsert（autoStart/restartOnExit/port 健康探针旗标 + 用户条目
 *    零触碰 + 幂等）；
 *  - 握手（有界轮询：200 即 dock + 解绑超时 WARN-only）；
 *  - **真实进程起停**（DaemonService 拉起 PerceptionDaemonMain 真 JVM：
 *    /health 200 健康面 + token 门 403 对照 + 干净 stop + 进程收尸）。
 * 崩溃自动重启（restartOnExit 退避）由 DaemonService 既有 DaemonLifecycleSpec
 * 家族覆盖，本 spec 不重复。
 */
class PerceptionDaemonLifecycleSpec extends FunSuite:

  private def withDispatcher[A](f: Dispatcher[IO] => A): A =
    val (disp, release) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
    try f(disp)
    finally release.unsafeRunSync()

  private def tmpHome(name: String): os.Path =
    val p = os.pwd / "target" / "perception-daemon-spec" / s"$name-${System.nanoTime()}"
    os.makeDir.all(p)
    p

  private def await(pred: => Boolean, timeoutMs: Long = 30000, intervalMs: Long = 200): Boolean =
    val deadline = System.currentTimeMillis() + timeoutMs
    var ok = false
    while System.currentTimeMillis() < deadline && !ok do
      if pred then ok = true
      else Thread.sleep(intervalMs)
    ok

  private def rawHealth(port: Int, token: Option[String]): (Int, String) =
    try
      val b = HttpRequest.newBuilder
        .uri(java.net.URI.create(s"http://127.0.0.1:$port/health"))
        .timeout(Duration.ofSeconds(2))
        .GET()
      token.foreach(t => b.header("X-Nf-Perception-Token", t))
      val resp = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString())
      (resp.statusCode(), resp.body())
    catch case _: Throwable => (000, "")

  // ── 托管命令面 ─────────────────────────────────────────────────────────

  test("buildCommand: java -cp 主类 + --home/--port/--engine-port + L1 kill-switch 透传") {
    val cmd = PerceptionDaemonDock.buildCommand(
      home = os.Path("/tmp/stand-home"), port = 4567, enginePort = 6138,
      llmEnabled = false, classPath = "/libs/stand-assembly.jar"
    )
    val Some(c) = cmd: @unchecked
    assert(c(0).endsWith("/bin/java"), s"java bin first: ${c(0)}")
    assertEquals(c(1), "-Dnebflow.perception.llm.enabled=false")
    assertEquals(c(2), "-cp")
    assertEquals(c(3), "/libs/stand-assembly.jar")
    assertEquals(c(4), "nebflow.perception.PerceptionDaemonMain")
    assertEquals(c.slice(5, c.size), List("--home", "/tmp/stand-home", "--port", "4567", "--engine-port", "6138"))
  }

  test("buildCommand: 空 classpath（jpackage 形态）⇒ None——spawn 不可行面") {
    val cmd = PerceptionDaemonDock.buildCommand(os.Path("/tmp/h"), 1, 2, llmEnabled = true, classPath = "  ")
    assertEquals(cmd, None)
  }

  // ── boot token ────────────────────────────────────────────────────────

  test("writeToken: 0600 落盘 + 每 boot 轮换（旧值死亡）") {
    val home = tmpHome("token")
    val t1 = PerceptionDaemonDock.writeToken(home).unsafeRunSync()
    val f = PerceptionDaemonDock.tokenFile(home)
    assert(os.exists(f))
    assertEquals(os.read(f), t1)
    if !scala.util.Properties.isWin then
      val perms = java.nio.file.Files.getPosixFilePermissions(f.toNIO)
      assertEquals(perms, PosixFilePermissions.fromString("rw-------"), "token file must be 0600")
    val t2 = PerceptionDaemonDock.writeToken(home).unsafeRunSync()
    assert(t1 != t2, "token rotates per engine boot")
    assertEquals(os.read(f), t2)
  }

  // ── 托管条目 upsert ────────────────────────────────────────────────────

  test("upsertEntry: autoStart/restartOnExit/port 探针旗标；用户条目零触碰；幂等") {
    withDispatcher { disp =>
      val home = tmpHome("upsert")
      val store = new DaemonStore(home / "daemons.json")
      val user = DaemonConfig("dev-server", "Dev Server", List("sleep", "999"), autoStart = false)
      store.save(List(user)).unsafeRunSync()

      val up1 = PerceptionDaemonDock.upsertEntry(store, home, enginePort = 6138).unsafeRunSync()
      assert(up1.spawnable)
      val configs = store.load().unsafeRunSync()
      assertEquals(configs.find(_.id == "dev-server").map(_.command), Some(List("sleep", "999")), "用户条目零触碰")
      val entry = configs.find(_.id == PerceptionDaemonDock.EntryId).getOrElse(fail("perception entry missing"))
      assert(entry.autoStart, "一起起停（autoStart 家族）")
      assert(entry.restartOnExit, "一起被 watchdog 管（崩溃自动重启家族）")
      assertEquals(entry.port, Some(up1.daemonPort), "一起写健康面（端口探针）")
      assert(entry.command.contains("nebflow.perception.PerceptionDaemonMain"))

      // 幂等：第二次 upsert 替换同 id、保留用户条目、token 轮换。
      val up2 = PerceptionDaemonDock.upsertEntry(store, home, enginePort = 6138).unsafeRunSync()
      val configs2 = store.load().unsafeRunSync()
      assertEquals(configs2.count(_.id == PerceptionDaemonDock.EntryId), 1)
      assertEquals(configs2.count(_.id == "dev-server"), 1)
      assert(up2.token != up1.token)
    }
  }

  // ── 握手（有界轮询）────────────────────────────────────────────────────

  test("awaitHandshake: /health 200 即 dock（PerceptionClient.bind 就绪）") {
    withDispatcher { _ =>
      val home = tmpHome("handshake")
      val token = PerceptionDaemonDock.writeToken(home).unsafeRunSync()
      // 假 daemon：裸 ServerSocket 最小 HTTP 面（零 http4s 依赖，确定性收口）。
      FakeDaemon.withHealth(token) { port =>
        PerceptionDaemonDock.awaitHandshake(port, token, budgetMs = 5000).unsafeRunSync()
        PerceptionClient.current.unsafeRunSync() match
          case Some(ep) => assertEquals(ep.port, port)
          case None     => fail("expected docked endpoint")
        // dock 后客户端腿可用（health 路径 200）。
        val call = PerceptionClient.call("GET", "/health", None, timeoutSec = Some(3)).unsafeRunSync()
        assertEquals(call.map(_._1), Some(200))
        PerceptionClient.unbind().unsafeRunSync()
      }
    }
  }

  test("awaitHandshake: 停机窗 = 有界超时 WARN-only（不抛、不 bind）") {
    withDispatcher { _ =>
      // 无任何服务在场的端口——轮询到预算耗尽，走 WARN 路径正常返回。
      val freePort = {
        val s = new java.net.ServerSocket()
        try
          s.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress, 0))
          s.getLocalPort
        finally s.close()
      }
      PerceptionClient.unbind().unsafeRunSync()
      PerceptionDaemonDock.awaitHandshake(freePort, "tok", budgetMs = 600).unsafeRunSync() // 不抛即过
      assertEquals(PerceptionClient.current.unsafeRunSync(), None, "超时不得 bind")
    }
  }

  // ── 真实进程起停（统一 daemon 真 JVM）──────────────────────────────────

  /**
   * 测试 JVM 的真实类路径（sbt 形态下 `java.class.path` 只有 sbt-launch.jar——
   * 真实条目在测试类加载器链上，逐层收集 URLClassLoader 的 URL；
   * URLDecoder 还原 sbt URL 编码的路径空格）。
   */
  private def runtimeClassPath: String =
    val urls = scala.collection.mutable.LinkedHashSet[String]()
    var cl: ClassLoader = getClass.getClassLoader
    while cl != null do
      cl match
        case u: java.net.URLClassLoader =>
          urls ++= u.getURLs.map(url => java.net.URLDecoder.decode(url.getPath, "utf-8"))
        case _ => ()
      cl = cl.getParent
    urls.mkString(java.io.File.pathSeparator)

  test("PerceptionDaemonMain 真实起停：/health 200 + token 门 403 + stop 收尸") {
    withDispatcher { disp =>
      val home = tmpHome("spawn")
      val svc = new DaemonService(disp, home / "daemon-pids.json")
      val store = new DaemonStore(home / "daemons.json")
      val up = PerceptionDaemonDock.upsertEntry(store, home, enginePort = 59999).unsafeRunSync()
      // 托管条目的 -cp 换成测试类加载器链的真实类路径（见 runtimeClassPath 注；
      // 生产形态 java.class.path = assembly jar，无需此替换）。
      val entry = store.load().unsafeRunSync().find(_.id == PerceptionDaemonDock.EntryId).get
      val cpIdx = entry.command.indexOf("-cp")
      val entryFixed = entry.copy(command = entry.command.updated(cpIdx + 1, runtimeClassPath))
      assert(entryFixed.command.contains("nebflow.perception.PerceptionDaemonMain"))

      val state = svc.start(entryFixed).unsafeRunSync()
      assertEquals(state.status, DaemonStatus.Running)
      val pid = state.pid.getOrElse(fail("expected pid"))
      val port = up.daemonPort

      // 健康面：/health 200（token 门后），payload 带 role/loops 标识。
      assert(await(rawHealth(port, Some(up.token))._1 == 200), "daemon /health never turned 200")
      val (code, body) = rawHealth(port, Some(up.token))
      assertEquals(code, 200)
      assert(body.contains("perception-unified-daemon"), s"health payload: $body")
      assert(body.contains("poll"), s"five loops in health face: $body")

      // token 门：无 token / 错 token ⇒ 403（与引擎会话门同款拒绝面）。
      assertEquals(rawHealth(port, None)._1, 403)
      assertEquals(rawHealth(port, Some("wrong-token"))._1, 403)

      // 干净停：DaemonService.stop（SIGTERM 腿）⇒ 进程收尸。
      val stopped = svc.stop(PerceptionDaemonDock.EntryId).unsafeRunSync()
      assertEquals(stopped.map(_.status), Some(DaemonStatus.Stopped))
      assert(await(!Option(java.lang.ProcessHandle.of(pid).orElse(null)).exists(_.isAlive), timeoutMs = 15000),
        s"daemon pid $pid still alive after stop")
    }
  }

/** 假 daemon：裸 ServerSocket 最小 /health 面（token 门；HTTP/1.0 逐字应答）。 */
private object FakeDaemon:

  def withHealth[A](token: String)(f: Int => A): A =
    val ss = new java.net.ServerSocket(0, 64, java.net.InetAddress.getLoopbackAddress)
    @volatile var running = true
    val t = new Thread(() =>
      while running do
        val accepted: java.net.Socket | Null =
          try ss.accept()
          catch case _: Throwable => null
        if accepted != null then
          val s: java.net.Socket = accepted
          val worker = new Thread(() =>
            try
              s.setSoTimeout(3000)
              val in = s.getInputStream
              val buf = new scala.collection.mutable.ArrayBuffer[Byte]
              val b = new Array[Byte](1)
              // 读到头结束（累积串以 \r\n\r\n 收尾）
              def acc: String = new String(buf.toArray, java.nio.charset.StandardCharsets.ISO_8859_1)
              while {
                val n = in.read(b)
                if n < 0 then false
                else { buf += b(0); !acc.endsWith("\r\n\r\n") }
              } do ()
              val head = acc
              val ok = head.contains(s"X-Nf-Perception-Token: $token")
              val body = if ok then "{\"ok\":true}" else "{\"error\":\"Unauthorized\"}"
              val code = if ok then "200 OK" else "403 Forbidden"
              val resp =
                s"HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
              s.getOutputStream.write(resp.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
              s.getOutputStream.flush()
            catch case _: Throwable => ()
            finally s.close()
          )
          worker.setDaemon(true)
          worker.start()
    )
    t.setDaemon(true)
    t.start()
    try f(ss.getLocalPort)
    finally
      running = false
      ss.close()

