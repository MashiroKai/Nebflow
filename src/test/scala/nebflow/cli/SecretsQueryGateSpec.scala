package nebflow.cli

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * 可查询批（作者令 2026-11-18 21:31）· **本批的闸（CLI 腿）**。
 *
 * 一个哨兵串 + 自造哑元值铺进隔离库，然后让每一条能出字的 CLI 通道都跑一遍：
 * `secrets list`（文本面 + `--json` 面 + 带 `--keyword`/`--tag` 的查询面）、
 * `secrets show`、`secrets meta`、`secrets usage`。任何一处出现哨兵 ⇒ 本 spec 红。
 *
 * 闸的自证检测力（「破坏 → 红、还原 → 绿、对未修源码直跑应红」）：
 *   - **对未修源码直跑应红**：`SecretsShow` / `SecretsMeta` / `SecretsStore.show` /
 *     `SecretsQuery` / `Entry.tags` 全部是本批新增 ⇒ 在基点上这个文件编译不过
 *     （读数见报告），这是「红」的第一形态。
 *   - **破坏 → 红**：把任一条读面改成回显值（或让 `list` 去读值文件），哨兵断言
 *     立刻红（报告给出被破坏点的实测读数）。还原即绿。
 *
 * 不变式腿（与哨兵同为「结构上不可能泄漏」的推导）：
 *   - 列出条目数 == 值目录里的普通文件数（rollback 备份与子目录不算）；
 *   - 元数据侧车缺失 / 是 `version:1` 老文件 / 是坏 JSON ⇒ 仍能列出、不崩；
 *   - 查询语义：`--tag a,b` 取交集、`--keyword` 命中 名称/说明/标签/消费方、
 *     无命中给「没有匹配」而不是一张空表。
 *
 * 全部走 `Files.createTempDirectory` 的隔离根 —— 真库（`~/.stand/secrets/`）
 * 一个字节都不读、不写。所有值都是哑元 + 哨兵。
 *
 * REST 腿（`GET /secrets` 等）与面板腿另见
 * `nebflow.gateway.rest.SecretsQueryRoutesGateSpec`（同一条闸的另外两面；
 * 拆文件只因 `SecretsList` 等是 `private[cli]`）。
 */
class SecretsQueryGateSpec extends FunSuite:

  /** The value files' content. Never asserted-present anywhere — only
   *  asserted-ABSENT, which is the whole point of the gate. */
  private val Sentinel = "SENTINEL-DO-NOT-LEAK-8f3a41"
  private val MockA = s"$Sentinel-a"
  private val MockB = s"$Sentinel-b"

  private def json(s: String): Json = parse(s).fold(e => throw e, identity)

  private def ctx(
    args: Map[String, String] = Map.empty,
    positional: List[String] = Nil,
    jsonMode: Boolean = false
  ): CliContext =
    CliContext(args, positional, jsonMode, quiet = false, client = None, PathUtil.dataRoot)

  /** An isolated store root with three value files, two of them labelled. */
  private def withRoot(f: os.Path => Unit): Unit =
    val root = os.Path(Files.createTempDirectory("secrets-query-gate").toRealPath().toString)
    val previous = PathUtil.dataRoot
    try
      PathUtil.setDataRoot(root)
      val dir = root / "secrets"
      os.makeDir.all(dir)
      val store = new nebflow.core.secrets.SecretsStore(dir)
      assert(store.create("aliyun-oss", MockA).unsafeRunSync().isRight)
      assert(store.create("vercel-token", MockB).unsafeRunSync().isRight)
      assert(store.create("plain-unlabelled", MockA).unsafeRunSync().isRight)
      assert(
        store
          .setMetadata("aliyun-oss", None, Some("OSS access key for the docs bucket"), List("cloud", "Deploy", "oss"))
          .unsafeRunSync()
          .isRight
      )
      assert(store.setMetadata("vercel-token", None, Some("Vercel deploy token"), List("deploy")).unsafeRunSync().isRight)
      f(root)
    finally
      PathUtil.setDataRoot(previous)
      try os.remove.all(root)
      catch case _: Throwable => ()
    end try
  end withRoot

  /** Every byte one CLI invocation would put on stdout, across both faces.
   *  `CliResult.Text` lines and `CliResult.Json` (spaces2) are exactly what
   *  `CliRouter.renderResult` prints, so asserting over these asserts over
   *  the real output. */
  private def cliOutputs(sub: CliSubcommand, args: Map[String, String] = Map.empty, positional: List[String] = Nil): List[String] =
    cliResults(sub, args, positional).map {
      case CliResult.Text(lines)      => lines.mkString("\n")
      case CliResult.Json(j)          => j.spaces2
      case CliResult.Error(msg, code) => s"error($code): $msg"
      case CliResult.Exit(code, out)  => s"exit($code): $out"
      case CliResult.Success          => ""
    }

  private def cliResults(sub: CliSubcommand, args: Map[String, String] = Map.empty, positional: List[String] = Nil): List[CliResult] =
    List(false, true).map(jsonMode => sub.run(ctx(args, positional, jsonMode)).unsafeRunSync())

  private def assertSentinelFree(label: String, outputs: Iterable[String]): Unit =
    outputs.foreach { line =>
      assert(!line.contains(Sentinel), s"$label leaked the sentinel value: $line")
      // 哨兵只是值的一半：哑元前缀同样不得出现（一个「前缀回显」的口子会连哨兵
      // 一起带出，这里把它单独钉住，读数更直白）。
      assert(!line.contains("SENTINEL-DO-NOT-LEAK"), s"$label leaked a value fragment: $line")
    }

  // ── CLI face ────────────────────────────────────────────────────────────

  test("Q1 · CLI list/show/meta/usage: no face ever carries a stored value") {
    withRoot { _ =>
      val outputs =
        cliOutputs(SecretsList) ++
          cliOutputs(SecretsList, Map("keyword" -> "cloud")) ++
          cliOutputs(SecretsList, Map("tag" -> "deploy")) ++
          cliOutputs(SecretsShow, positional = List("aliyun-oss")) ++
          cliOutputs(SecretsShow, positional = List("plain-unlabelled")) ++
          cliOutputs(SecretsMeta, Map("description" -> "x"), List("aliyun-oss")) ++
          cliOutputs(SecretsUsage)
      assertSentinelFree("CLI", outputs)
    }
  }

  test("Q2 · CLI list: every row carries name/description/tags/updated/path/consumers") {
    withRoot { _ =>
      val text = SecretsList.run(ctx()).unsafeRunSync() match
        case CliResult.Text(lines) => lines.mkString("\n")
        case other                 => fail(s"expected text output, got $other")
      assert(text.contains("aliyun-oss"), text)
      assert(text.contains("#cloud") && text.contains("#deploy") && text.contains("#oss"), text)
      assert(text.contains("OSS access key for the docs bucket"), text)
      assert(text.contains("path:"), text)
      assert(text.contains("/secrets/aliyun-oss"), text)
      assert(text.contains("used by:"), text)

      val payload = SecretsList.run(ctx(jsonMode = true)).unsafeRunSync() match
        case CliResult.Json(j) => j
        case other             => fail(s"expected json output, got $other")
      assertEquals(payload.hcursor.downField("count").as[Int].toOption, Some(3))
      assertEquals(payload.hcursor.downField("total").as[Int].toOption, Some(3))
      val rows = payload.hcursor.downField("secrets").values.map(_.toList).getOrElse(Nil)
      val aliyun = rows.find(_.hcursor.downField("name").as[String].toOption.contains("aliyun-oss")).getOrElse(fail("row missing"))
      assertEquals(aliyun.hcursor.downField("tags").as[List[String]].toOption, Some(List("cloud", "deploy", "oss")))
      assert(aliyun.hcursor.downField("description").as[String].toOption.exists(_.nonEmpty))
      assert(aliyun.hcursor.downField("path").as[String].toOption.exists(_.endsWith("/secrets/aliyun-oss")))
      assert(aliyun.hcursor.downField("variableName").as[String].toOption.contains("ALIYUN_OSS"))
    }
  }

  // ── Query semantics ─────────────────────────────────────────────────────

  test("Q3 · tag filter is an INTERSECTION and keyword spans name/description/tag/consumers") {
    withRoot { _ =>
      def names(args: Map[String, String]): List[String] =
        SecretsList.run(ctx(args, jsonMode = true)).unsafeRunSync() match
          case CliResult.Json(j) =>
            j.hcursor.downField("secrets").values.map(_.toList).getOrElse(Nil).flatMap(_.hcursor.downField("name").as[String].toOption)
          case other => fail(s"expected json, got $other")

      assertEquals(names(Map("tag" -> "deploy")).sorted, List("aliyun-oss", "vercel-token"))
      assertEquals(names(Map("tag" -> "deploy,cloud")).sorted, List("aliyun-oss"))
      // "Deploy" folds to "deploy" — the SAME query as the lowercase form, so
      // it selects every secret carrying that tag (both of them here).
      assertEquals(names(Map("tag" -> "Deploy")).sorted, List("aliyun-oss", "vercel-token"), "tag matching is normalized, not case sensitive")
      assertEquals(names(Map("tag" -> "deploy,oss,nope")), Nil)
      assertEquals(names(Map("keyword" -> "vercel")), List("vercel-token"))
      assertEquals(names(Map("keyword" -> "docs bucket")), List("aliyun-oss"))
      assertEquals(names(Map("keyword" -> "OSS")).sorted, List("aliyun-oss"))
      assertEquals(names(Map.empty).sorted, List("aliyun-oss", "plain-unlabelled", "vercel-token"))
    }
  }

  test("Q4 · a query with no hits answers 'no match' — never a silent empty table") {
    withRoot { _ =>
      val out = SecretsList.run(ctx(Map("tag" -> "nope"))).unsafeRunSync() match
        case CliResult.Text(lines) => lines.mkString("\n")
        case other                 => fail(s"expected text, got $other")
      assert(out.contains("No secrets match this query."), out)
      assert(out.contains("tags=nope"), out)
      assertSentinelFree("no-match face", List(out))
    }
  }

  // ── Invariants ──────────────────────────────────────────────────────────

  test("Q5 · listed rows == regular files in the secrets dir (metadata never hides or invents a row)") {
    withRoot { root =>
      val store = new nebflow.core.secrets.SecretsStore(root / "secrets")
      val entries = store.list().unsafeRunSync().toOption.get
      assertEquals(entries.size, 3)
      assertEquals(entries.map(_.name).sorted, List("aliyun-oss", "plain-unlabelled", "vercel-token"))
      // A rollback backup is not a credential and must not be listed.
      os.write(root / "secrets" / "x-rollback-20261118.json", MockA)
      assertEquals(
        store.list().unsafeRunSync().toOption.get.map(_.name).sorted,
        List("aliyun-oss", "plain-unlabelled", "vercel-token")
      )
    }
  }

  test("Q6 · missing / version:1 / malformed metadata never breaks the list (fields just read empty)") {
    withRoot { root =>
      val store = new nebflow.core.secrets.SecretsStore(root / "secrets")
      val metaPath = nebflow.core.secrets.SecretsCatalog.metaPath(root)
      // (a) no sidecar at all
      os.remove(metaPath)
      val bare = store.list().unsafeRunSync().toOption.get
      assertEquals(bare.size, 3)
      assert(bare.forall(_.tags.isEmpty), bare.toString)
      assert(bare.forall(_.description.isEmpty), bare.toString)
      // (b) a hand-written version:1 sidecar (pre-batch shape, no tags key)
      os.write.over(metaPath, """{"version":1,"secrets":{"aliyun-oss":{"description":"legacy desc"}}}""")
      val legacy = store.list().unsafeRunSync().toOption.get
      assertEquals(legacy.size, 3)
      val aliyun = legacy.find(_.name == "aliyun-oss").getOrElse(fail("row missing"))
      assertEquals(aliyun.description, "legacy desc")
      assertEquals(aliyun.tags, Nil)
      // (c) a malformed sidecar
      os.write.over(metaPath, "{not json")
      assertEquals(store.list().unsafeRunSync().toOption.get.size, 3)
    }
  }

  test("Q7 · the sidecar is schema-versioned and holds tags but never a value fragment") {
    withRoot { root =>
      val raw = os.read(nebflow.core.secrets.SecretsCatalog.metaPath(root))
      assert(!raw.contains(Sentinel), s"the metadata sidecar must never carry a value: $raw")
      val j = json(raw)
      assertEquals(j.hcursor.downField("version").as[Int].toOption, Some(2))
      assertEquals(
        j.hcursor.downField("secrets").downField("aliyun-oss").downField("tags").as[List[String]].toOption,
        Some(List("cloud", "deploy", "oss"))
      )
      // Tags are folded at the write entry: "Deploy" was stored lowercase.
      assertEquals(nebflow.core.secrets.SecretsCatalog.normalizeTag("Deploy"), "deploy")
      // The rendered catalogue carries the tag column too — and no value.
      val catalog = os.read(nebflow.core.secrets.SecretsCatalog.catalogPath(root))
      assert(!catalog.contains(Sentinel), s"the rendered catalogue must never carry a value: $catalog")
      assert(catalog.contains("#cloud"), catalog)
    }
  }

  // ── AskUser entry (the third write door) ────────────────────────────────

  test("Q8 · an AskUser secret is created WITH description+tags and answers `show` (metadata only)") {
    withRoot { root =>
      import nebflow.core.secrets.AskUserSecretSink
      val res = AskUserSecretSink.storeSync(
        root,
        AskUserSecretSink.StoreRequest(
          requestId = "req-gate-1",
          questionIndex = 0,
          value = MockA,
          nameHint = Some("askuser-gate-1"),
          description = Some("Key pasted through an AskUser secret question"),
          tags = List("From-AskUser", "cloud")
        )
      )
      assert(res.isRight, res.toString)
      assertSentinelFree("AskUser receipt", List(res.toOption.get.toString))

      val store = new nebflow.core.secrets.SecretsStore(root / "secrets")
      val shown = store.show("askuser-gate-1").unsafeRunSync().toOption.get
      assertEquals(shown.stored, false, "the .askuser/ namespace keeps its bytes outside the top-level listing")
      assertEquals(shown.tags, List("from-askuser", "cloud"))
      assert(shown.description.contains("AskUser"), shown.toString)
      // 🔴 The managed name never surfaces in the top-level list (unchanged
      // contract), and `show` answers metadata only.
      assert(!store.list().unsafeRunSync().toOption.get.exists(_.name == "askuser-gate-1"))
      val show = SecretsShow.run(ctx(positional = List("askuser-gate-1"), jsonMode = true)).unsafeRunSync() match
        case CliResult.Json(j) => j.spaces2
        case other             => fail(s"expected json, got $other")
      assertSentinelFree("show (managed name)", List(show))
      assert(show.contains("from-askuser"), show)
    }
  }

  // ── Metadata write face ─────────────────────────────────────────────────

  test("Q10 · `secrets meta` writes description+tags (whole replace) and never touches the value file") {
    withRoot { root =>
      val before = os.read(root / "secrets" / "vercel-token")
      val out = SecretsMeta
        .run(ctx(Map("name" -> "vercel-token", "description" -> "Deploy token for the docs site", "tag" -> "ci,Deploy")))
        .unsafeRunSync() match
        case CliResult.Text(lines) => lines.mkString("\n")
        case other                 => fail(s"expected text, got $other")
      assertSentinelFree("meta output", List(out))
      assertEquals(os.read(root / "secrets" / "vercel-token"), before, "the value file must be byte-identical")
      val store = new nebflow.core.secrets.SecretsStore(root / "secrets")
      val e = store.show("vercel-token").unsafeRunSync().toOption.get
      assertEquals(e.description, "Deploy token for the docs site")
      assertEquals(e.tags, List("ci", "deploy"))
      // Clearing every field drops the sidecar entry entirely.
      assert(store.setMetadata("vercel-token", None, None, Nil).unsafeRunSync().isRight)
      assert(!os.read(nebflow.core.secrets.SecretsCatalog.metaPath(root)).contains("vercel-token"))
    }
  }

  test("Q11 · `secrets show` on an unknown name is an error carrying the name, never a value") {
    withRoot { _ =>
      val results = cliResults(SecretsShow, positional = List("no-such-secret"))
      results.foreach {
        case CliResult.Error(msg, code) =>
          assert(msg.contains("unknown_secret"), msg)
          assertEquals(code, 1)
        case other => fail(s"expected an error result, got $other")
      }
      assertSentinelFree("unknown-name error", results.map(_.toString))
    }
  }

end SecretsQueryGateSpec
