package nebflow.core

import cats.effect.IO
import io.circe.{Json, parser}
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * CanvasTabStore + CanvasTabs 校验契约（F1 服务端持久化，2026-08-30）。
 *
 * 验收点：
 * - 读写往返：save 后 load 返回相同数据
 * - schema 拒绝：v≠2 / tabs 非数组 / 坏字段类型 → Left（路由层转 400），不落盘
 * - 原子写：磁盘内容精确 = json.noSpaces，目录无 *.tmp.* 残留
 * - 大小限制：payload 超 MaxBodyBytes → Left(413)
 * - 无存档：load → None（路由层转 404）
 * - 损坏/非法存档：load → None（视同无存档，前端 fallback localStorage）
 */
class CanvasTabStoreSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def json(s: String): Json =
    parser.parse(s).fold(e => throw new RuntimeException(s"bad fixture: $e"), identity)

  private val validPayload: Json = json(
    """{"v":2,"tabs":[{"id":"teams","title":"Teams","type":"teams","absPath":null,"pinned":true,"closable":true},{"id":"file:/a/b.md","title":"b.md","type":"markdown","absPath":"/a/b.md"}],"activeTabId":"teams"}"""
  )

  // ── validate：接受 ────────────────────────────────────────

  test("validate accepts a valid v2 payload and returns it unchanged") {
    assertEquals(CanvasTabs.validate(validPayload), Right(validPayload))
  }

  test("validate accepts minimal tabs (optional fields absent)") {
    val minimal = json("""{"v":2,"tabs":[{"id":"teams","title":"T","type":"teams"}]}""")
    assertEquals(CanvasTabs.validate(minimal).isRight, true)
  }

  test("validate accepts null absPath and activeTabId") {
    val p = json("""{"v":2,"tabs":[{"id":"x","title":"X","type":"file","absPath":null}],"activeTabId":null}""")
    assertEquals(CanvasTabs.validate(p).isRight, true)
  }

  test("validate ignores unknown fields (forward-compatible)") {
    val p = json("""{"v":2,"tabs":[{"id":"x","title":"X","type":"t","futureField":"zz"}],"extra":1}""")
    assertEquals(CanvasTabs.validate(p).isRight, true)
  }

  // ── validate：拒绝（路由层转 400）─────────────────────────

  test("validate rejects non-object top level") {
    assert(CanvasTabs.validate(json("""[1,2]""")).isLeft)
    assert(CanvasTabs.validate(json(""""str"""")).isLeft)
  }

  test("validate rejects v != 2") {
    assert(CanvasTabs.validate(json("""{"v":1,"tabs":[]}""")).isLeft)
    assert(CanvasTabs.validate(json("""{"v":3,"tabs":[]}""")).isLeft)
    assert(CanvasTabs.validate(json("""{"tabs":[]}""")).isLeft, "missing v rejected")
  }

  test("validate rejects tabs not an array") {
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":{}}""")).isLeft)
    assert(CanvasTabs.validate(json("""{"v":2}""")).isLeft, "missing tabs rejected")
  }

  test("validate rejects bad tab entry field types") {
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":[{"title":"T","type":"x"}]}""")).isLeft, "missing id")
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":[{"id":"","title":"T","type":"x"}]}""")).isLeft, "empty id")
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":[{"id":7,"title":"T","type":"x"}]}""")).isLeft, "non-string id")
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":[{"id":"x","title":"T"}]}""")).isLeft, "missing type")
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":[{"id":"x","type":"x"}]}""")).isLeft, "missing title")
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":[{"id":"x","title":"T","type":"x","absPath":5}]}""")).isLeft, "absPath must be string or null")
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":[{"id":"x","title":"T","type":"x","pinned":"yes"}]}""")).isLeft, "pinned must be boolean")
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":[{"id":"x","title":"T","type":"x","closable":1}]}""")).isLeft, "closable must be boolean")
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":["not-an-object"]}""")).isLeft, "entry must be object")
  }

  test("validate rejects bad activeTabId type") {
    assert(CanvasTabs.validate(json("""{"v":2,"tabs":[],"activeTabId":5}""")).isLeft)
  }

  // ── parseBody：大小限制 + JSON 解析 + 校验链 ──────────────

  test("parseBody accepts valid payload under the limit") {
    val body = validPayload.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(CanvasTabs.parseBody(body), Right(validPayload))
  }

  test("parseBody rejects payload over the byte limit with 413") {
    val big = ("x" * (CanvasTabs.MaxBodyBytes + 1)).getBytes
    assertEquals(
      CanvasTabs.parseBody(big, CanvasTabs.MaxBodyBytes).left.map(_._1),
      Left(413)
    )
  }

  test("parseBody rejects valid JSON that exceeds the limit (size gate wins over schema)") {
    // 单个 title 字段就 ≥ Max 字节 → 合法 JSON 也必超限，证明大小检查不因
    // schema 通过而绕过（防滥用是硬门）。
    val fill = "a" * CanvasTabs.MaxBodyBytes
    val body = s"""{"v":2,"tabs":[{"id":"f","title":"$fill","type":"x"}]}"""
      .getBytes(java.nio.charset.StandardCharsets.UTF_8)
    assert(body.length > CanvasTabs.MaxBodyBytes)
    assertEquals(
      CanvasTabs.parseBody(body, CanvasTabs.MaxBodyBytes).left.map(_._1),
      Left(413)
    )
  }

  test("parseBody rejects malformed JSON with 400") {
    val body = "{not json".getBytes
    assertEquals(CanvasTabs.parseBody(body).left.map(_._1), Left(400))
  }

  test("parseBody rejects schema-invalid JSON with 400") {
    val body = """{"v":1,"tabs":[]}""".getBytes
    assertEquals(CanvasTabs.parseBody(body).left.map(_._1), Left(400))
  }

  // ── store：读写往返 / 原子写 / 无存档 / 损坏容错 ──────────

  test("save then load returns identical data (round-trip)") {
    for
      dir <- IO(os.temp.dir())
      store = new CanvasTabStore(dir / "canvas_tabs.json")
      _ <- store.save(validPayload)
      loaded <- store.load()
      onDisk <- IO(os.read(dir / "canvas_tabs.json"))
    yield
      assertEquals(loaded, Some(validPayload))
      assertEquals(onDisk, validPayload.noSpaces, "disk content exact")
  }

  test("save overwrites previous archive (last write wins)") {
    for
      dir <- IO(os.temp.dir())
      f = dir / "canvas_tabs.json"
      store = new CanvasTabStore(f)
      _ <- store.save(json("""{"v":2,"tabs":[{"id":"a","title":"A","type":"t"}]}"""))
      second = json("""{"v":2,"tabs":[{"id":"b","title":"B","type":"t"}]}""")
      _ <- store.save(second)
      loaded <- store.load()
      residue <- IO(os.list(dir).filter(_.last.contains(".tmp.")))
    yield
      assertEquals(loaded, Some(second))
      assertEquals(residue.toList, List.empty[os.Path], s"no tmp residue: $residue")
  }

  test("load returns None when no archive exists") {
    for
      dir <- IO(os.temp.dir())
      store = new CanvasTabStore(dir / "canvas_tabs.json")
      loaded <- store.load()
    yield assertEquals(loaded, None)
  }

  test("load returns None for corrupt archive (treat as no archive)") {
    for
      dir <- IO(os.temp.dir())
      f = dir / "canvas_tabs.json"
      _ <- IO(os.write.over(f, "{truncated json!!!"))
      store = new CanvasTabStore(f)
      loaded <- store.load()
    yield assertEquals(loaded, None)
  }

  test("load returns None for schema-invalid archive (e.g. legacy v1)") {
    for
      dir <- IO(os.temp.dir())
      f = dir / "canvas_tabs.json"
      _ <- IO(os.write.over(f, """{"v":1,"tabs":[]}"""))
      store = new CanvasTabStore(f)
      loaded <- store.load()
    yield assertEquals(loaded, None)
  }

end CanvasTabStoreSpec
