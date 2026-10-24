package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Method, Request, Status, Uri}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * 存量根双读 spec（数据根切换批 · 媒体/附件服务面收口，2026-10-20 紧急修复批）。
 *
 * 场景复刻（作者机器 19:08 迁移后的真实盘面）：
 *   - `<home>/.nebflow/`         —— 旧根**重建 stub**（只剩 projects/，无 uploads）；
 *   - `<home>/.nebflow-migrated-<ts>/` —— 改名原树（迁移前文件在此）；
 *   - `<dataRoot>/`              —— 现役根（迁移克隆落位处，`uploads/` 齐全）。
 *
 * 存量消息落库的媒体引用是旧根字面绝对路径；修复前第一问 not-found ⇒ 铸票被拒
 * ⇒ 前端「媒体加载失败」。本 spec 焊四条契约：
 *   ① 迁移前路径引用**可加载**（铸票 → 取回全链 route-level）；
 *   ② 新路径（现役根字面）可加载（直读零变化）；
 *   ③ 双读**回退序**断言（现役根 > 最新迁移树 > 旧迁移树）；
 *   ④ 防线零松动（改写后撞凭据照拒；非旧根 not-found 不漂网；根未动的老机器零变化）。
 *
 * 形态对齐 `NfTicketRoutesSpec`：companion 纯路由直调 + 内存 store + 注入
 * `NfPathPolicy` + 合成 home（`nfFileVerdict`/`nfFileVerdictTolerant` 的 `home`
 * 参数），零实例、零 sys.props 改写、零真实家目录触碰。
 */
class LegacyRootDualReadSpec extends CatsEffectSuite:

  private val gatewayToken = "test-gateway-token"
  private val TokenRef = cats.effect.Ref.unsafe[IO, String](gatewayToken)
  private val Sessions = GatewaySessionStore.unsafeCreate()

  /** 迁移盘面（见类注释）；`policy` lazy = R2 inode 快照在 seed 之后取。 */
  private final class Env(val root: Path, val home: Path, val dataRoot: Path):
    val sid = "sess-abc123"

    lazy val policy: NfFilePolicy.NfPathPolicy =
      // dataRoot 走 canonicalOrSelf 同构（生产 NfPathPolicy.standard() 语义）：
      // macOS /var → /private/var 不归一的话，改写候选的 realpath 与策略根的
      // startsWith 锚定就分裂（身份层退化为 R2 inode 兜底）。
      NfFilePolicy.NfPathPolicy(
        dataRoot.toRealPath(),
        Files.createDirectories(root.resolve("ws")).toRealPath(),
        NfFilePolicy.NfPathPolicy.scanCredentialInodes(dataRoot)
      )

    val store: NfTicketStore = NfTicketStore.unsafeCreate(1800)

    /** 旧根字面（迁移前形态）——stub 下**不存在**的文件。 */
    def legacyLiteral(rel: String): Path = home.resolve(".nebflow").resolve(rel)

    /** 最新迁移树字面。 */
    def migratedNew(rel: String): Path =
      home.resolve(".nebflow-migrated-20261020-190831").resolve(rel)

    /** 更旧迁移树字面。 */
    def migratedOld(rel: String): Path =
      home.resolve(".nebflow-migrated-20261019-010000").resolve(rel)

    /** 现役根字面。 */
    def liveLiteral(rel: String): Path = dataRoot.resolve(rel)

    /** 落盘小助手：父目录随写随建。 */
    private def put(p: Path, bytes: String): Path =
      Files.createDirectories(p.getParent)
      Files.write(p, bytes.getBytes(StandardCharsets.UTF_8))

    /** seed：三棵树三个落位，同尾不同字节（回退序断言靠字节可辨）。 */
    def seed(): Unit =
      // 现役根：uploads/<sid>/ 在场 + 凭据种子（身份层依赖）
      put(liveLiteral(s"uploads/$sid/shot.jpg"), "LIVE-BYTES")
      put(liveLiteral(s"uploads/$sid/new.jpg"), "NEW-UPLOAD-BYTES")
      put(dataRoot.resolve("secrets/k.txt"), "s")
      put(dataRoot.resolve("auth.json"), "\"t\"")
      // 旧根 stub：只有 projects/，无 uploads（复刻真实盘面）
      Files.createDirectories(home.resolve(".nebflow/projects"))
      // 最新迁移树：同名 shot.jpg 但字节不同 + 一棵独有文件
      put(migratedNew(s"uploads/$sid/shot.jpg"), "MIGRATED-NEW-BYTES")
      put(migratedNew(s"uploads/$sid/only-tree.jpg"), "ONLY-NEW-TREE")
      // 更旧迁移树：shot.jpg 第三份字节 + 一棵独有文件
      put(migratedOld(s"uploads/$sid/shot.jpg"), "MIGRATED-OLD-BYTES")
      put(migratedOld(s"uploads/$sid/only-old-tree.jpg"), "ONLY-OLD-TREE")
      // 双树同尾（现役根缺席）——专验「树间新者优先」
      put(migratedNew(s"uploads/$sid/both-trees.jpg"), "BOTH-NEW")
      put(migratedOld(s"uploads/$sid/both-trees.jpg"), "BOTH-OLD")

    end seed

    def issue(body: Json) =
      val req = SpecRequests
        .withRemote(
          Request[IO](Method.POST, Uri.unsafeFromString("/api/nf-ticket"))
            .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, gatewayToken))),
          Some(SpecRequests.LoopbackV4)
        )
        .withEntity(body)
      NfFileRoutes.nfTicketRoutes(TokenRef, store, policy, Sessions, home)(req).value.unsafeRunSync()

    def get(path: String, ticket: String) =
      val req = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/nf-file?path=$path&ticket=$ticket"))
      NfFileRoutes.nfFileRoutes(store, policy, home)(req).value.unsafeRunSync()

    def ticketOf(resp: org.http4s.Response[IO], key: Path): Option[String] =
      val json = parse(bodyOf(resp)).getOrElse(Json.Null)
      json.hcursor.downField("tickets").downField(key.toString).downField("t").as[String].toOption

    def verdictOf(raw: String): NfFilePolicy.NfVerdict =
      NfFilePolicy.nfFileVerdict(raw, policy, home).unsafeRunSync()

  end Env

  private def withEnv[A](test: Env => IO[A]): A =
    val root = Files.createTempDirectory("nebflow-dualread-test")
    val home = Files.createDirectories(root.resolve("home"))
    val dataRoot = Files.createDirectories(root.resolve("dataroot"))
    val env = Env(root, home, dataRoot)
    env.seed()
    try test(env).unsafeRunSync()
    finally
      Files
        .walk(root)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(Files.deleteIfExists)

  private def bodyOf(resp: org.http4s.Response[IO]): String =
    resp.bodyText.compile.string.unsafeRunSync()

  private def url(p: Path): String = java.net.URLEncoder.encode(p.toString, "UTF-8")

  // ── ① 迁移前路径引用可加载（铸票 → 取回全链，route-level）────────────────

  test("① 旧根字面路径：铸票成功且取回 = 现役根字节（迁移机常态全链)") {
    withEnv { env =>
      IO {
        val legacy = env.legacyLiteral(s"uploads/${env.sid}/shot.jpg")
        val mint = env
          .issue(
            Json.obj("sessionId" -> Json.fromString("spec"), "paths" -> Json.arr(Json.fromString(legacy.toString)))
          )
          .get
        assertEquals(mint.status, Status.Ok)
        val ticket = env.ticketOf(mint, legacy)
        assert(ticket.isDefined, s"旧根字面必须能铸票，rejected=${bodyOf(mint)}")
        val served = env.get(url(legacy), ticket.get).get
        // 同一原始字面在取回腿解出同一 realpath ⇒ 票验证通过、字节来自现役根克隆
        assertEquals(served.status, Status.Ok)
        assertEquals(bodyOf(served), "LIVE-BYTES")
      }
    }
  }

  test("①' 旧根字面取回用的正是现役根文件（realPath 断言，判据级)") {
    withEnv { env =>
      IO {
        val legacy = env.legacyLiteral(s"uploads/${env.sid}/shot.jpg")
        NfFilePolicy.nfFileVerdict(legacy.toString, env.policy, env.home).unsafeRunSync() match
          case NfFilePolicy.NfVerdict.Allowed(real, ext) =>
            assertEquals(real, env.liveLiteral(s"uploads/${env.sid}/shot.jpg").toRealPath())
            assertEquals(ext, "jpg")
          case other => fail(s"expected Allowed, got $other")
      }
    }
  }

  // ── ② 新路径可加载（直读零变化）─────────────────────────────────────────

  test("② 现役根字面路径：直读不受双读影响（字节逐位同）") {
    withEnv { env =>
      IO {
        val live = env.liveLiteral(s"uploads/${env.sid}/new.jpg")
        val mint = env
          .issue(
            Json.obj("sessionId" -> Json.fromString("spec"), "paths" -> Json.arr(Json.fromString(live.toString)))
          )
          .get
        val ticket = env.ticketOf(mint, live)
        assert(ticket.isDefined, s"新路径必须能铸票，rejected=${bodyOf(mint)}")
        val served = env.get(url(live), ticket.get).get
        assertEquals(served.status, Status.Ok)
        assertEquals(bodyOf(served), "NEW-UPLOAD-BYTES")
        // 判据级：第一问直接命中，realPath 就是字面本身
        NfFilePolicy.nfFileVerdict(live.toString, env.policy, env.home).unsafeRunSync() match
          case NfFilePolicy.NfVerdict.Allowed(real, _) =>
            assertEquals(real, live.toRealPath())
          case other => fail(s"expected Allowed, got $other")
      }
    }
  }

  // ── ③ 双读回退序断言 ─────────────────────────────────────────────────────

  test("③a 回退序：现役根 > 迁移树（两处都在 ⇒ 现役根字节赢)") {
    withEnv { env =>
      IO {
        // 三棵树都有 shot.jpg：现役根 LIVE / 最新树 MIGRATED-NEW / 旧树 MIGRATED-OLD
        val legacy = env.legacyLiteral(s"uploads/${env.sid}/shot.jpg")
        env.verdictOf(legacy.toString) match
          case NfFilePolicy.NfVerdict.Allowed(real, _) =>
            assertEquals(
              Files.readAllBytes(real).toSeq,
              "LIVE-BYTES".getBytes(StandardCharsets.UTF_8).toSeq,
              "现役根在场时必须赢过迁移树（resolveRootRef 同语义：ref 跟数据根走）"
            )
          case other => fail(s"expected Allowed, got $other")
      }
    }
  }

  test("③b 回退序：现役根缺席 ⇒ 最新迁移树顶上") {
    withEnv { env =>
      IO {
        val legacy = env.legacyLiteral(s"uploads/${env.sid}/only-tree.jpg")
        env.verdictOf(legacy.toString) match
          case NfFilePolicy.NfVerdict.Allowed(real, _) =>
            assertEquals(
              Files.readAllBytes(real).toSeq,
              "ONLY-NEW-TREE".getBytes(StandardCharsets.UTF_8).toSeq,
              "现役根无此文件时，最新时间戳的迁移树必须被问"
            )
          case other => fail(s"expected Allowed, got $other")
      }
    }
  }

  test("③c 回退序：两棵迁移树都有 ⇒ 时间戳新者优先（字典序降序)") {
    withEnv { env =>
      IO {
        // 现役根无 only-old-tree.jpg / only-tree.jpg 之外的第三独有文件——用
        // only-old-tree.jpg 只在旧树、而把最新树的删掉来构造「仅旧树」面
        Files.deleteIfExists(env.migratedNew(s"uploads/${env.sid}/only-tree.jpg"))
        val legacyNewTreeFile = env.legacyLiteral(s"uploads/${env.sid}/only-old-tree.jpg")
        env.verdictOf(legacyNewTreeFile.toString) match
          case NfFilePolicy.NfVerdict.Allowed(real, _) =>
            assertEquals(
              Files.readAllBytes(real).toSeq,
              "ONLY-OLD-TREE".getBytes(StandardCharsets.UTF_8).toSeq,
              "仅更旧迁移树在场时也必须能兜底（克隆未覆盖的残留）"
            )
          case other => fail(s"expected Allowed, got $other")
        // 全部缺席 ⇒ 维持原 404（消息里是原始字面）
        val gone = env.legacyLiteral(s"uploads/${env.sid}/nowhere.jpg")
        env.verdictOf(gone.toString) match
          case d: NfFilePolicy.NfVerdict.Denied =>
            assertEquals(d.status, Status.NotFound)
            assertEquals(d.reason, "not-found")
            assert(d.message.contains(gone.toString), "404 消息必须携带原始字面路径")
          case other => fail(s"expected Denied, got $other")
      }
    }
  }

  test("③d 回退序：两棵迁移树都有（现役根缺席）⇒ 时间戳新者优先") {
    withEnv { env =>
      IO {
        val legacy = env.legacyLiteral(s"uploads/${env.sid}/both-trees.jpg")
        env.verdictOf(legacy.toString) match
          case NfFilePolicy.NfVerdict.Allowed(real, _) =>
            assertEquals(
              Files.readAllBytes(real).toSeq,
              "BOTH-NEW".getBytes(StandardCharsets.UTF_8).toSeq,
              "零填充时间戳字典序降序 ⇒ 20261020 树必须赢过 20261019 树"
            )
          case other => fail(s"expected Allowed, got $other")
      }
    }
  }

  // ── ④ 防线零松动 ─────────────────────────────────────────────────────────

  test("④a 改写后撞凭据照拒：secrets 子树经双读落现役根 ⇒ credential-path") {
    withEnv { env =>
      IO {
        val legacySecret = env.legacyLiteral("secrets/k.txt")
        env.verdictOf(legacySecret.toString) match
          case d: NfFilePolicy.NfVerdict.Denied =>
            assertEquals(d.status, Status.Forbidden)
            assertEquals(d.reason, "credential-path")
          case other => fail(s"expected credential denial, got $other")
        val legacyAuth = env.legacyLiteral("auth.json")
        env.verdictOf(legacyAuth.toString) match
          case d: NfFilePolicy.NfVerdict.Denied =>
            assertEquals(d.status, Status.Forbidden)
            assertEquals(d.reason, "credential-path")
          case other => fail(s"expected credential denial, got $other")
      }
    }
  }

  test("④b 非旧根 not-found 不漂网：任意缺失路径维持原 404") {
    withEnv { env =>
      IO {
        val stray = env.home.resolve("not-nebflow").resolve("missing.png")
        env.verdictOf(stray.toString) match
          case d: NfFilePolicy.NfVerdict.Denied =>
            assertEquals(d.status, Status.NotFound)
            assertEquals(d.reason, "not-found")
          case other => fail(s"expected NotFound, got $other")
        // 现役根同尾存在、但字面不在旧根前缀下 ⇒ 不得借双读命中
        val fishy = env.home.resolve("projects").resolve("uploads").resolve(s"${env.sid}.jpg")
        env.verdictOf(fishy.toString) match
          case d: NfFilePolicy.NfVerdict.Denied =>
            assertEquals(d.status, Status.NotFound)
          case other => fail(s"expected NotFound, got $other")
      }
    }
  }

  test("④c 老机器（旧根未动）零行为变化：字面在场 ⇒ 第一问命中字面本身") {
    withEnv { env =>
      IO {
        // stub 里放一个真实文件（老机器的 .nebflow 就是现役根，文件真实在场）
        val onDisk = env.legacyLiteral(s"uploads/${env.sid}/local.jpg")
        Files.createDirectories(onDisk.getParent)
        Files.write(onDisk, "LOCAL-BYTES".getBytes(StandardCharsets.UTF_8))
        env.verdictOf(onDisk.toString) match
          case NfFilePolicy.NfVerdict.Allowed(real, _) =>
            assertEquals(real, onDisk.toRealPath())
            assertEquals(Files.readAllBytes(real).toSeq, "LOCAL-BYTES".getBytes(StandardCharsets.UTF_8).toSeq)
          case other => fail(s"expected Allowed, got $other")
      }
    }
  }

  test("④d tolerant facade 同享双读（铸票/取回门面与判据同源)") {
    withEnv { env =>
      IO {
        val legacy = env.legacyLiteral(s"uploads/${env.sid}/shot.jpg")
        val verdict = NfFilePolicy
          .nfFileVerdictTolerant(legacy.toString, env.policy, env.home)
          .unsafeRunSync()
        verdict match
          case NfFilePolicy.NfVerdict.Allowed(real, _) =>
            assertEquals(Files.readAllBytes(real).toSeq, "LIVE-BYTES".getBytes(StandardCharsets.UTF_8).toSeq)
          case other => fail(s"expected Allowed, got $other")
      }
    }
  }

end LegacyRootDualReadSpec
