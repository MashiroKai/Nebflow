package nebflow.core

import cats.effect.IO
import io.circe.JsonObject
import io.circe.parser.parse
import munit.CatsEffectSuite
import nebflow.core.tools.RelayExecAudit

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * T4（2026-09-11 Q3 裁定批）：relay/P2P 远端执行审计行的单元面——redact 矩阵 +
 * JSONL 行形态 + dataRoot 契约。端到端（真下发一条命令后审计行出现）见
 * `nebflow.neblink.RemoteExecutorAuditSpec`。
 */
class RelayExecAuditSpec extends CatsEffectSuite:

  private var tmpDir: Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-relay-audit-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def auditLines: List[String] =
    val p = RelayExecAudit.auditFile
    if !Files.exists(p) then Nil else Files.readAllLines(p).asScala.toList

  private val MaskShape = """\[redacted len=\d+( pre=\S{1,3})? sha256:[0-9a-f]{8}\]""".r

  private def assertNoPlaintext(out: String, secret: String): Unit =
    assert(!out.contains(secret), s"secret leaked in plaintext: $out")

  // ── redact 矩阵 ────────────────────────────────────────────────────

  test("redact: Bearer / Authorization 头 → 长度+前缀+哈希，且关键字 Bearer 保留可读") {
    val cmd = """curl -H "Authorization: Bearer sk-live-abcdefghijklmnop" https://api.example.com"""
    val out = RelayExecAudit.redact(cmd)
    assertNoPlaintext(out, "sk-live-abcdefghijklmnop")
    assert(out.contains("Bearer [redacted len=24 pre=sk- sha256:"), out)
    assert(out.contains("https://api.example.com"), s"非密钥部分必须保留可读: $out")
  }

  test("redact: Basic base64 凭据") {
    val out = RelayExecAudit.redact("""curl -H "Authorization: Basic dXNlcjpwYXNzd29yZA==" https://x.dev""")
    assertNoPlaintext(out, "dXNlcjpwYXNzd29yZA==")
    assert(out.contains("Basic [redacted len=20 pre=dXN"), out)
  }

  test("redact: NAME=value / NAME: value 密钥结构") {
    assertEquals(
      RelayExecAudit.redact("export API_TOKEN=abc123").contains("abc123"),
      false
    )
    assert(RelayExecAudit.redact("export API_TOKEN=abc123").contains("API_TOKEN=[redacted len=6 sha256:"))
    assert(
      RelayExecAudit.redact("""mysql --password: "hunter2xyz"""").contains("""password: [redacted len=10 pre=hun sha256:""")
    )
    assert(
      RelayExecAudit.redact("""{"api_key": "0123456789abcdef"}""").contains("""[redacted len=16 pre=012 sha256:""")
    )
  }

  test("redact: CLI 旗标 --token <value>") {
    val out = RelayExecAudit.redact("mytool --token 1234567890abcdef --env prod")
    assertNoPlaintext(out, "1234567890abcdef")
    assert(out.contains("--token [redacted len=16 pre=123 sha256:"), out)
    assert(out.contains("--env prod"), out)
  }

  test("redact: 已知密钥前缀（sk-/ghp_/AKIA）") {
    assertNoPlaintext(RelayExecAudit.redact("deploy --key sk-ABCDEFGH12345678"), "sk-ABCDEFGH12345678")
    assertNoPlaintext(RelayExecAudit.redact("git clone https://ghp_ABCDEFGHIJKLMNOPQRSTUVWX@github.com/a/b"), "ghp_ABCDEFGHIJKLMNOPQRSTUVWX")
  }

  test("redact: URL userinfo 口令") {
    val out = RelayExecAudit.redact("git clone https://alice:s3cretpw@git.example.com/repo.git")
    assertNoPlaintext(out, "s3cretpw")
    assert(out.contains("://alice:[redacted len=8 pre=s3c sha256:"), out)
    assert(out.contains("git.example.com/repo.git"), out)
  }

  test("redact: 通用长串（≥32 字符 token 字符集）走长度+前缀+哈希") {
    val long = "A" * 40
    val out = RelayExecAudit.redact(s"cmd --header X-Junk:$long done")
    assertNoPlaintext(out, long)
    assert(out.contains("[redacted len=40 pre=AAA sha256:"), out)
  }

  test("redact: 短串 / 普通路径不受影响（不误伤可读性）") {
    val cmd = "cd /Users/dev/project && ls -la src/main/scala && git status"
    assertEquals(RelayExecAudit.redact(cmd), cmd)
  }

  test("redact: 超长命令整体封顶（长度+前缀+哈希），且遮蔽确定（同串同哈希）") {
    val cmd = (1 to 120).map(_ => "abcdefgh").mkString(" ")
    val out = RelayExecAudit.redact(cmd)
    assert(out.length < cmd.length, s"cap 未生效: ${out.length} vs ${cmd.length}")
    assert(out.contains(s"…[len=${cmd.length} sha256:"), out)
    assertEquals(RelayExecAudit.redact(cmd), out, "redact 必须确定——否则审计行无法关联")
  }

  // ── JSONL 行形态 ───────────────────────────────────────────────────

  test("record: 落一行 JSONL（字段齐全 + 命令 redact + 追加语义）") {
    val secret = "sk-live-SUPERSECRETVALUE123456"
    val cmd = s"scp -i /k ./a.tar user@10.0.0.9:/tmp/ && export API_TOKEN=$secret"
    for
      _ <- RelayExecAudit.record(
             sourceDeviceId = "qa-source",
             targetDeviceId = "peer-1",
             via = "relay",
             action = "Bash",
             command = cmd,
             projectRoot = "/tmp/qa-proj",
             cwd = "/tmp/qa-cwd"
           )
      _ <- RelayExecAudit.record("qa-source", "peer-1", "p2p", "Bash", "echo second", "/tmp/qa-proj", "/tmp/qa-cwd")
      lines <- IO.blocking(auditLines)
    yield
      assertEquals(RelayExecAudit.auditFile.getFileName.toString, "relay-exec-audit.jsonl")
      assertEquals(
        RelayExecAudit.auditFile.getParent,
        (PathUtil.dataRoot / "logs").toNIO,
        "审计文件必须随 dataRoot（隔离实例不污染生产 home）"
      )
      assertEquals(lines.length, 2, "JSONL 追加语义：两次 record = 两行")
      val json = parse(lines.head).fold(e => fail(s"invalid JSONL: $e"), identity)
      val c = json.hcursor
      assertEquals(c.downField("deviceId").as[String], Right("qa-source"), "来源设备必须落行")
      assertEquals(c.downField("targetDeviceId").as[String], Right("peer-1"))
      assertEquals(c.downField("via").as[String], Right("relay"))
      assertEquals(c.downField("action").as[String], Right("Bash"))
      assertEquals(c.downField("projectRoot").as[String], Right("/tmp/qa-proj"))
      assertEquals(c.downField("cwd").as[String], Right("/tmp/qa-cwd"))
      val ts = c.downField("ts").as[String].toOption.getOrElse(fail("ts missing"))
      assert("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""".r.matches(ts), s"ts 形态: $ts")
      val shown = c.downField("command").as[String].toOption.getOrElse(fail("command missing"))
      assert(!shown.contains(secret), s"明文密钥落盘: $shown")
      assert(!shown.contains("SUPERSECRET"), s"明文密钥落盘: $shown")
      assert(MaskShape.findFirstIn(shown).isDefined, s"redact 形态不符: $shown")
      assert(shown.contains("scp -i /k"), s"非密钥部分保留: $shown")
      assertEquals(c.downField("via").as[String], Right("relay"))
      assertEquals(parse(lines(1)).toOption.flatMap(_.hcursor.downField("via").as[String].toOption), Some("p2p"))
  }

  test("summarizeParams: Bash 用 command；其余工具扁平化 key=value（密钥名同样遮蔽路径）") {
    assertEquals(
      RelayExecAudit.summarizeParams("Bash", JsonObject("command" -> io.circe.Json.fromString("ls -la"))),
      "ls -la"
    )
    val read = RelayExecAudit.summarizeParams(
      "Read",
      JsonObject("file_path" -> io.circe.Json.fromString("/etc/hosts"))
    )
    assert(read.contains("file_path"), read)
    assert(read.contains("/etc/hosts"), read)
  }

  test("record: 写盘不可用时不抛出（审计缺失不得挡住远端执行）") {
    // 让 dataRoot/logs 成为**文件**（而非目录）→ createDirectories 必失败
    val blockedRoot =
      os.Path(Files.createTempDirectory("nb-relay-audit-blocked").toFile.getAbsolutePath, os.pwd)
    val logsPath = (blockedRoot / "logs").toNIO
    Files.write(logsPath, "not a directory".getBytes("UTF-8"))
    PathUtil.setDataRoot(blockedRoot)
    // 注意：清理必须挂在 IO 的 guarantee 上——try/finally 包 for-comprehension 只会
    // 在**构造** IO 时跑 finally（此前踩过：断言前目录已被删，用例假红）。
    (for
      _ <- RelayExecAudit.record("src", "peer", "relay", "Bash", "ssh host", "/p", "/c")
      _ <- RelayExecAudit.record("src", "peer", "relay", "Bash", "ssh host", "/p", "/c")
    yield assertEquals(Files.isRegularFile(logsPath), true, "写盘失败不得改动既有文件"))
      .guarantee(IO.blocking(os.remove.all(blockedRoot)))
      .void
  }

end RelayExecAuditSpec
