package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

import nebflow.gateway.NfFilePolicy.NfVerdict

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * 存量根双读（`NfFilePolicy.legacyRootResidual`）的判据 spec ——
 * empty-state-must-not-lie 批 · 老账核实件（2026-11-05）。
 *
 * 背景（既有实现已落地，此前**零 spec**）：数据根迁移（`~/.nebflow` → `~/.stand`）
 * 之后，存量消息/转录里的媒体引用是**旧根字面绝对路径**
 * （`/Users/x/.nebflow/uploads/<sid>/<file>`），文件实际在新根 ⇒ 字面 `exists=false`
 * ⇒ 若只按字面判，`GET /api/nf-file` 恒 404（作者实测 2026-10-20「媒体加载失败」）。
 * `legacyRootResidual`（`NfFilePolicy.scala:454`）是第一问落空后的同尾双读：
 *   ① `<dataRoot>/<tail>`（迁移克隆落位处，常态命中）；
 *   ② `<home>/.nebflow-migrated-<时间戳>/<tail>`（改名原树兜底，最新树优先）。
 *
 * 本 spec 钉的是**判据本身**（纯函数 + 一个走 `nfFileVerdict` 的端到端腿），
 * 让「兜底覆盖哪些读面」这句话有机械读数：读面 = 一切经 `nfFileVerdict` /
 * `nfFileVerdictTolerant` 的路径，即 `GET /api/nf-file`、`POST /api/nf-ticket`、
 * `GET /api/nf-office`（三处调用点，全仓仅此三处）。
 *
 * 🔴 零真实用户数据参与：全部在 `Files.createTempDirectory` 下合成，既不动
 * `~/.stand`，也不动 `~/.nebflow`（作者令：只核实与补齐，不 rm 任何用户文件）。
 */
class NfFileLegacyRootSpec extends CatsEffectSuite:

  /** 合成一套「home + 现役根 + 迁移树」并返回三条坐标。 */
  private def fixture(): (Path, Path, Path) =
    val root = Files.createTempDirectory("nfile-legacy-spec")
    val home = Files.createDirectories(root.resolve("home"))
    val dataRoot = Files.createDirectories(home.resolve(".stand"))
    (root, home, dataRoot)

  private def write(file: Path, body: String = "x"): Path =
    Files.createDirectories(file.getParent)
    Files.write(file, body.getBytes(StandardCharsets.UTF_8))
    file

  test("① 旧根字面 + 文件在现役根 ⇒ 同尾命中现役根（迁移机常态）") {
    val (_, home, dataRoot) = fixture()
    val rel = Path.of("uploads", "sess-1", "pic.png")
    val actual = write(dataRoot.resolve(rel), "png-bytes")
    val legacyLiteral = home.resolve(".nebflow").resolve(rel) // 字面：~/.nebflow/uploads/…
    // 字面路径必然不存在（迁移后旧根只剩 stub），这正是事故条件。
    assertEquals(Files.exists(legacyLiteral), false)
    val got = NfFilePolicy.legacyRootResidual(legacyLiteral, dataRoot, home)
    assertEquals(got.map(_.toString), Some(actual.toString))
  }

  test("② 文件只在改名原树 ⇒ 迁移树兜底命中") {
    val (_, home, dataRoot) = fixture()
    val rel = Path.of("uploads", "sess-2", "doc.pdf")
    val migrated = write(home.resolve(".nebflow-migrated-20261020_190800").resolve(rel), "pdf")
    val legacyLiteral = home.resolve(".nebflow").resolve(rel)
    assertEquals(NfFilePolicy.legacyRootResidual(legacyLiteral, dataRoot, home).map(_.toString), Some(migrated.toString))
  }

  test("③ 两棵迁移树都有 ⇒ 最新（字典序降序）优先") {
    val (_, home, dataRoot) = fixture()
    val rel = Path.of("uploads", "s", "f.bin")
    write(home.resolve(".nebflow-migrated-20261020_190800").resolve(rel), "old")
    val newer = write(home.resolve(".nebflow-migrated-20261021_090000").resolve(rel), "new")
    val legacyLiteral = home.resolve(".nebflow").resolve(rel)
    assertEquals(NfFilePolicy.legacyRootResidual(legacyLiteral, dataRoot, home).map(_.toString), Some(newer.toString))
  }

  test("④ 不在旧根前缀下的字面 ⇒ None（禁借双读漂网）") {
    val (root, home, dataRoot) = fixture()
    val outside = write(root.resolve("workspace").resolve("a.txt"))
    assertEquals(NfFilePolicy.legacyRootResidual(outside, dataRoot, home), None)
    // 现役根内的字面同样不参与（它们由第一问直接判，不靠兜底）
    assertEquals(NfFilePolicy.legacyRootResidual(dataRoot.resolve("uploads/x/y.png"), dataRoot, home), None)
  }

  test("⑤ 旧根前缀下的字面但两侧都没有该文件 ⇒ None（调用方维持原 404）") {
    val (_, home, dataRoot) = fixture()
    val legacyLiteral = home.resolve(".nebflow").resolve("uploads/ghost/none.png")
    assertEquals(NfFilePolicy.legacyRootResidual(legacyLiteral, dataRoot, home), None)
  }

  test("⑥ 旧根本身（tail 为空）⇒ None（不做整根替换）") {
    val (_, home, dataRoot) = fixture()
    assertEquals(NfFilePolicy.legacyRootResidual(home.resolve(".nebflow"), dataRoot, home), None)
  }

  test("⑦ 端到端读面：nfFileVerdict 用旧根字面取到现役根文件（GET /api/nf-file 的判据腿）") {
    val (_, home, dataRoot) = fixture()
    val rel = Path.of("uploads", "sess-9", "voice.wav")
    val actual = write(dataRoot.resolve(rel), "wav")
    val legacyLiteral = home.resolve(".nebflow").resolve(rel)
    val policy = NfFilePolicy.NfPathPolicy(
      dataRoot = NfFilePolicy.NfPathPolicy.canonicalOrSelf(dataRoot),
      workspaceRoot = NfFilePolicy.NfPathPolicy.canonicalOrSelf(home.resolve("ws")),
      credentialInodes = Set.empty
    )
    NfFilePolicy.nfFileVerdict(legacyLiteral.toString, policy, home).map { v =>
      v match
        case NfVerdict.Allowed(real, _) =>
          assertEquals(real.toString, actual.toRealPath().toString)
        case other => fail(s"expected Allowed, got $other")
    }
  }

  test("⑧ 端到端读面：双读**不**放宽凭据面（旧根字面指向现役根 secrets/ ⇒ 仍拒）") {
    val (_, home, dataRoot) = fixture()
    // 旧根字面 + 同尾落在现役根 `secrets/…`：候选路径必须照旧过凭据身份层。
    write(dataRoot.resolve(Path.of("secrets", "leaked.json")), "{}")
    val legacyLiteral = home.resolve(".nebflow").resolve(Path.of("secrets", "leaked.json"))
    val policy = NfFilePolicy.NfPathPolicy(
      dataRoot = NfFilePolicy.NfPathPolicy.canonicalOrSelf(dataRoot),
      workspaceRoot = NfFilePolicy.NfPathPolicy.canonicalOrSelf(home.resolve("ws")),
      credentialInodes = Set.empty
    )
    NfFilePolicy.nfFileVerdict(legacyLiteral.toString, policy, home).map { v =>
      v match
        case NfVerdict.Denied(status, reason, _) =>
          assertEquals(status, org.http4s.Status.Forbidden)
          assert(reason.nonEmpty, "deny reason must be machine-readable")
        case other => fail(s"expected Denied, got $other")
    }
  }
