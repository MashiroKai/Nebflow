package nebflow.gateway.nf

import nebflow.gateway.*

import java.net.{InetAddress, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite

/**
 * 判据面的逐格断言（external-open 批，2026-11-19）：SSRF 地址表、scheme/host 闸、
 * 「DNS 解析到私网」的负控、内容嗅探、落盘命名、TTL 清账清单。
 *
 * 全部纯函数，零网络零磁盘（TTL 一段用 `Files.createTempDirectory` 造目录）。
 * 负控（必红）在每条 test 的名字里就写着「拒」/refuses —— 一条绿在这一面 = 红线破。
 */
class ExternalOpenPolicySpec extends FunSuite:

  private val P = ExternalOpenPolicy

  private def ip(s: String): InetAddress = InetAddress.getByName(s)

  private def refused(url: String, resolve: String => List[InetAddress] = P.resolveHost): P.Refusal =
    P.gateUrl(url, resolve) match
      case Left(r) => r
      case Right((uri, _, _)) => fail(s"expected a refusal for $url, got $uri")

  private def allowed(url: String, resolve: String => List[InetAddress] = P.resolveHost): (URI, List[InetAddress]) =
    P.gateUrl(url, resolve) match
      case Left(r) => fail(s"expected $url to pass, got ${r.reason}: ${r.message}")
      case Right((uri, _, addrs)) => (uri, addrs)

  // ── ① 凭据判据（与 nf-file 同一函数，本入口零放宽）──────────────────────
  //
  // 这一段测的不是本对象的表，而是**本入口所用的那一条判据**在凭据形态上的读数
  // ——ExternalOpenRoutes.localLeg 直接调它，所以这里绿 = 入口绿。

  private def denyFor(path: Path, dataRoot: Path): Option[(NfFilePolicy.NfDenyLayer, String)] =
    NfFilePolicy.nfVerdictForRealLayer(
      path,
      NfFilePolicy.NfPathPolicy(dataRoot, dataRoot.resolve("ws").resolve(".nebflow"), Set.empty)
    ).map { case (layer, denied) => (layer, denied.reason) }

  test("credential shapes are refused — credential-path (negative control ①)"):
    val tmp = Files.createTempDirectory("nf-ext-cred").toRealPath()
    val dataRoot = tmp.resolve("data")
    Files.createDirectories(dataRoot.resolve("secrets"))
    val secretsFile = dataRoot.resolve("secrets").resolve("token.txt")
    Files.writeString(secretsFile, "nope")
    val authJson = dataRoot.resolve("auth.json")
    Files.writeString(authJson, "{\"token\":\"x\"}")
    val sshLike = tmp.resolve(".ssh").resolve("id_rsa")
    Files.createDirectories(sshLike.getParent)
    Files.writeString(sshLike, "-----BEGIN OPENSSH PRIVATE KEY-----")
    val pem = tmp.resolve("certs").resolve("server.pem")
    Files.createDirectories(pem.getParent)
    Files.writeString(pem, "pem")

    // secrets 子树 / 数据根 auth.json / 凭据目录段 / 凭据形态 basename 四类全拒
    assertEquals(denyFor(secretsFile.toRealPath(), dataRoot).map(_._2), Some("credential-path"))
    assertEquals(denyFor(authJson.toRealPath(), dataRoot).map(_._2), Some("credential-path"))
    assertEquals(denyFor(sshLike.toRealPath(), dataRoot).map(_._2), Some("credential-path"))
    assertEquals(denyFor(pem.toRealPath(), dataRoot).map(_._2), Some("credential-path"))

  test("an ordinary project file is NOT refused by the credential judge (positive control)"):
    val tmp = Files.createTempDirectory("nf-ext-cred-ok")
    val file = tmp.resolve("report.pdf")
    Files.writeString(file, "%PDF-1.4")
    assertEquals(denyFor(file.toRealPath(), tmp.resolve("data")), None)

  // ── ② URL 闸：scheme / host 名 ─────────────────────────────────────────
  private val publicResolve: String => List[InetAddress] = _ => List(ip("93.184.216.34"))

  test("non-http(s) schemes are refused"):
    assertEquals(refused("file:///etc/passwd", publicResolve).reason, "url-scheme")
    assertEquals(refused("ftp://example.com/a.txt", publicResolve).reason, "url-scheme")
    assertEquals(refused("gopher://example.com/", publicResolve).reason, "url-scheme")
    assertEquals(refused("jar:http://example.com/x.jar!/", publicResolve).reason, "url-scheme")

  test("localhost / metadata host names are refused before any DNS"):
    assertEquals(refused("http://localhost:6138/api/nf-file", publicResolve).reason, "url-private-address")
    assertEquals(refused("http://foo.localhost/x", publicResolve).reason, "url-private-address")
    assertEquals(refused("http://printer.local/x", publicResolve).reason, "url-private-address")
    assertEquals(refused("http://metadata.google.internal/x", publicResolve).reason, "url-private-address")

  test("private / loopback / link-local / metadata addresses are refused (negative control ②)"):
    val blocked = List(
      "http://127.0.0.1/x",
      "http://127.9.9.9/x",
      "http://0.0.0.0/x",
      "http://10.1.2.3/x",
      "http://172.16.0.9/x",
      "http://172.31.255.254/x",
      "http://192.168.1.1/x",
      "http://169.254.169.254/latest/meta-data/",
      "http://100.64.0.1/x",
      "http://[::1]/x",
      "http://[fe80::1]/x",
      "http://[fc00::1]/x",
      "http://[::ffff:127.0.0.1]/x",
      "http://224.0.0.1/x"
    )
    blocked.foreach { url =>
      assertEquals(refused(url).reason, "url-private-address", s"$url must be refused")
    }

  test("a public hostname that RESOLVES to a private address is refused (DNS negative control ②)"):
    // 「名字看着公网、解析落在内网」——判据在解析之后，不在名字上。
    val evil = (host: String) => if host == "evil.example" then List(ip("10.9.8.7")) else Nil
    assertEquals(refused("https://evil.example/a.png", evil).reason, "url-private-address")
    // 一个坏一个好的多地址（轮询绕过）：fail-closed，整条拒。
    val mixed = (host: String) =>
      if host == "mixed.example" then List(ip("93.184.216.34"), ip("192.168.0.2")) else Nil
    assertEquals(refused("https://mixed.example/a.png", mixed).reason, "url-private-address")
    // 解析不出任何地址同样拒（不许「空解析 = 放行」）。
    assertEquals(refused("https://nowhere.example/a", _ => Nil).reason, "url-unresolved")

  test("a genuinely public URL passes the gate (positive control)"):
    val (uri, addrs) = allowed("https://example.com/a/b.png?x=1", publicResolve)
    assertEquals(uri.getHost, "example.com")
    assertEquals(addrs.map(_.getHostAddress), List("93.184.216.34"))

  test("empty / malformed URL is refused"):
    assertEquals(refused("", publicResolve).reason, "url-malformed")
    assertEquals(refused("http://", publicResolve).reason, "url-malformed")
    assertEquals(refused("https:///path-only", publicResolve).reason, "url-host")

  // ── ③ 内容嗅探（按内容不按扩展名）───────────────────────────────────────

  test("magic bytes win over the URL extension (content sniffing)"):
    val png = Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    val pdf = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)
    val gif = "GIF89a".getBytes(StandardCharsets.US_ASCII)
    val text = "hello, world".getBytes(StandardCharsets.UTF_8)
    assertEquals(P.sniffExtension(png), Some("png"))
    assertEquals(P.sniffExtension(pdf), Some("pdf"))
    assertEquals(P.sniffExtension(gif), Some("gif"))
    assertEquals(P.sniffExtension(text), Some("txt"))
    // 扩展名撒谎（URL 说 .png，内容其实是文本）⇒ 服务扩展名按内容走
    assertEquals(P.servedExtension(text, "/x/y.png"), "txt")
    // 扩展名说 .png，内容也真是 png ⇒ png
    assertEquals(P.servedExtension(png, "/x/y.png"), "png")
    // 文本内容 + URL 说 .md ⇒ 保留 md（文本族才允许 URL 扩展名兜底）
    assertEquals(P.servedExtension(text, "/x/y.md"), "md")

  test("unknown binary content is served as .bin, never as the claimed extension"):
    val random = Array[Byte](0x00, 0x01, 0x02, 0x03, 0xfe.toByte, 0xff.toByte)
    assertEquals(P.servedExtension(random, "/a/evil.png"), "bin")

  test("active content is classified (HTML from the internet is an active type)"):
    assert(P.ActiveExtensions.contains("html"))
    assert(P.ActiveExtensions.contains("htm"))
    assert(P.ActiveExtensions.contains("js"))
    assert(!P.ActiveExtensions.contains("png"))
    assert(!P.ActiveExtensions.contains("pdf"))

  // ── ④ 落盘命名 ─────────────────────────────────────────────────────────

  test("file names are sanitized and forced to the served extension"):
    assertEquals(P.fileNameFor("/a/b/Report 2026 Q1.pdf", "pdf"), "Report-2026-Q1.pdf")
    assertEquals(P.fileNameFor("/a/../../etc/passwd", "txt"), "passwd.txt")
    assertEquals(P.fileNameFor("/", "txt"), "download.txt")
    assertEquals(P.fileNameFor("/a/b/%E6%8A%A5%E5%91%8A.pdf", "pdf"), "download.pdf")
    val long = P.fileNameFor("/a/" + ("x" * 300) + ".png", "png")
    assert(long.length <= P.MaxFileNameLength, s"got ${long.length}")
    assert(long.endsWith(".png"))
    // 扩展名一律由判据给，URL 自己的后缀不复用（防 `.html` 伪装）
    assertEquals(P.fileNameFor("/a/page.html", "txt"), "page.txt")

  test("storage path is content addressed"):
    val root = java.nio.file.Paths.get("/data")
    val sha = "0123456789abcdef" + "f" * 48
    assertEquals(P.storagePath(root, sha, "a.png").toString, "/data/external-fetch/0123456789abcdef/a.png")
    assertEquals(P.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

  // ── ⑤ TTL 清账 ─────────────────────────────────────────────────────────

  test("expired fetch directories are listed for sweeping, fresh ones are kept"):
    val root = Files.createTempDirectory("nf-ext-ttl").resolve("external-fetch")
    val oldDir = Files.createDirectories(root.resolve("aaaaaaaaaaaaaaaa"))
    val freshDir = Files.createDirectories(root.resolve("bbbbbbbbbbbbbbbb"))
    Files.writeString(oldDir.resolve("a.png"), "x")
    Files.writeString(freshDir.resolve("b.png"), "x")
    val now = System.currentTimeMillis()
    Files.setLastModifiedTime(oldDir, java.nio.file.attribute.FileTime.fromMillis(now - 48L * 3600 * 1000))
    val expired = P.expiredDirs(root, now)
    assertEquals(expired.map(_.getFileName.toString), List("aaaaaaaaaaaaaaaa"))
    // 反证：TTL 未到的一律不清
    assertEquals(P.expiredDirs(root, now, 72L * 3600 * 1000), Nil)

end ExternalOpenPolicySpec
