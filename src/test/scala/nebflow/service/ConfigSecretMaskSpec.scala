package nebflow.service

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import nebflow.shared.PathUtil

import scala.compiletime.uninitialized

/**
 * keyaudit-quickfix ③ 防回归：GET /api/config（与 WS getConfig 同一读取面
 * [[ConfigService.getConfig]]）不再明文回显密钥——`llm.providers.<id>.apiKey`
 * 与 `search.apiKey` 读时投影为 `"***"`。
 *
 * 钉的三件事：
 *   1. **打码形态**：只动两个 apiKey 形态字段，其余键（含 `jev`/`embedding` 的
 *      `keyRef` 指针——那是 secrets 目录里的**名**，不是值）原样保留；
 *   2. **语义闭环**：打码文本 round-trip 回 [[ConfigService.updateConfig]] 时，
 *      写侧 mergeConfig 的 `"***"` 规则保留真值——真 key 永不被掩码覆写；
 *   3. **盘上真相不变**：打码只发生在读出路径，盘上文件仍是真值。
 */
class ConfigSecretMaskSpec extends FunSuite:

  private var originalRoot: os.Path = uninitialized
  private var tmpRoot: os.Path = uninitialized

  override def beforeAll(): Unit =
    // Redirect PathUtil.dataRoot BEFORE any ConfigService access (ConfigServiceSpec
    // 同款 fixture；Test / parallelExecution := false 保证串行)
    originalRoot = PathUtil.dataRoot
    tmpRoot = os.pwd / "target" / "config-secret-mask-test" / s"data-${System.nanoTime()}"
    os.makeDir.all(tmpRoot)
    PathUtil.setDataRoot(tmpRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(tmpRoot)
    catch case _: Exception => ()

  private def providerJson(apiKey: String): Json = Json.obj(
    "baseUrl" -> Json.fromString("https://prov.example.com/v1"),
    "apiKey" -> Json.fromString(apiKey),
    "protocol" -> Json.fromString("openai"),
    "models" -> Json.arr(
      Json.obj("id" -> Json.fromString("m1"), "contextWindow" -> Json.fromInt(8192))
    )
  )

  /** 带两类密钥 + keyRef 指针 + 普通字段的样本配置（磁盘上的真值形态）。 */
  private def seedConfig(): String =
    Json
      .obj(
        "llm" -> Json.obj(
          "providers" -> Json.obj(
            "kimi" -> providerJson("sk-real-kimi-1"),
            "deepseek" -> providerJson("sk-real-ds-2")
          )
        ),
        "search" -> Json.obj("provider" -> Json.fromString("zhipu"), "apiKey" -> Json.fromString("zhipu-real-3")),
        "jev" -> Json.obj("enabled" -> Json.fromBoolean(true), "keyRef" -> Json.fromString("jev-key")),
        "embedding" -> Json.obj("keyRef" -> Json.fromString("embed-key"), "model" -> Json.fromString("emb-1")),
        "ui" -> Json.obj("theme" -> Json.fromString("dark"))
      )
      .spaces2

  private def readDisk(): Json = parse(os.read(PathUtil.dataRoot / "nebflow.json")).toOption.get

  private def apiKeyOf(disk: Json, id: String): Option[String] =
    disk.hcursor.downField("llm").downField("providers").downField(id).downField("apiKey").as[String].toOption

  // ── 1. 打码形态（纯函数面 maskSecretsJson）────────────────────────────

  test("maskSecretsJson masks llm.providers.*.apiKey and search.apiKey to ***") {
    val masked = ConfigService.maskSecretsJson(parse(seedConfig()).toOption.get)
    val hc = masked.hcursor
    assertEquals(
      hc.downField("llm").downField("providers").downField("kimi").downField("apiKey").as[String],
      Right("***")
    )
    assertEquals(
      hc.downField("llm").downField("providers").downField("deepseek").downField("apiKey").as[String],
      Right("***")
    )
    assertEquals(hc.downField("search").downField("apiKey").as[String], Right("***"))
  }

  test("maskSecretsJson keeps every non-secret field: baseUrl/models/keyRef pointers/other sections") {
    val seed = parse(seedConfig()).toOption.get
    val masked = ConfigService.maskSecretsJson(seed)
    val hc = masked.hcursor
    // baseUrl / models 原样（对象键序在重序列化中可能重排——JSON 键序处处无语义）
    assertEquals(
      hc.downField("llm").downField("providers").downField("kimi").downField("baseUrl").as[String],
      Right("https://prov.example.com/v1")
    )
    assertEquals(
      hc.downField("llm")
        .downField("providers")
        .downField("kimi")
        .downField("models")
        .downArray
        .downField("id")
        .as[String],
      Right("m1")
    )
    // keyRef 指针（jev/embedding）不是值——不打码
    assertEquals(hc.downField("jev").downField("keyRef").as[String], Right("jev-key"))
    assertEquals(hc.downField("embedding").downField("keyRef").as[String], Right("embed-key"))
    // 其他节原样
    assertEquals(hc.downField("search").downField("provider").as[String], Right("zhipu"))
    assertEquals(hc.downField("ui").downField("theme").as[String], Right("dark"))
    assertEquals(hc.downField("jev").downField("enabled").as[Boolean], Right(true))
  }

  test("maskSecretsJson is a no-op shape when the keys are absent (fresh-config safe)") {
    val masked = ConfigService.maskSecretsJson(parse("""{"llm":{"providers":{}}}""").toOption.get)
    assertEquals(masked.noSpaces, """{"llm":{"providers":{}}}""")
  }

  test("maskSecrets passes unparseable text through unchanged (write side ref-parse-fails identically)") {
    val raw = "{not json"
    assertEquals(ConfigService.maskSecrets(raw), raw)
  }

  // ── 2. getConfig 读出即打码 + 盘上真相不变 ─────────────────────────────

  test("getConfig returns the masked projection; the on-disk file keeps the real keys") {
    os.write.over(PathUtil.dataRoot / "nebflow.json", seedConfig())

    val served = ConfigService.getConfig.unsafeRunSync()
    val servedJson = parse(served).toOption.get
    val hc = servedJson.hcursor
    assertEquals(
      hc.downField("llm").downField("providers").downField("kimi").downField("apiKey").as[String],
      Right("***")
    )
    assertEquals(hc.downField("search").downField("apiKey").as[String], Right("***"))

    // 盘上真相不变：打码只发生在读出投影
    val disk = readDisk()
    assertEquals(apiKeyOf(disk, "kimi"), Some("sk-real-kimi-1"))
    assertEquals(apiKeyOf(disk, "deepseek"), Some("sk-real-ds-2"))
    assertEquals(disk.hcursor.downField("search").downField("apiKey").as[String], Right("zhipu-real-3"))
  }

  test("getConfig on a missing file still returns {} (unchanged behaviour)") {
    os.remove.all(PathUtil.dataRoot / "nebflow.json")
    assertEquals(ConfigService.getConfig.unsafeRunSync(), "{}")
  }

  // ── 3. 语义闭环：打码文本回写 ⇒ 真值保留（mergeConfig 的 "***" 规则）────

  test("masked read round-trips through updateConfig without clobbering the real key") {
    os.write.over(PathUtil.dataRoot / "nebflow.json", seedConfig())
    val masked = ConfigService.getConfig.unsafeRunSync()

    // 用户在设置面板改一个非密字段（baseUrl），apiKey 输入框留空 ⇒ 前端按既有
    // 约定回 "***"（与打码后的 configText 同形态）
    val edited = parse(masked).toOption.get.mapObject { root =>
      val llm = root("llm").flatMap(_.asObject).getOrElse(io.circe.JsonObject.empty)
      val provs = llm("providers").flatMap(_.asObject).getOrElse(io.circe.JsonObject.empty)
      val kimi = provs("kimi").flatMap(_.asObject).getOrElse(io.circe.JsonObject.empty)
      val kimiEdited = kimi.add("baseUrl", Json.fromString("https://kimi.example.com/v2"))
      root.add(
        "llm",
        Json.fromJsonObject(
          llm.add("providers", Json.fromJsonObject(provs.add("kimi", Json.fromJsonObject(kimiEdited))))
        )
      )
    }

    val result = ConfigService.updateConfig(edited.noSpaces).unsafeRunSync()
    assertEquals(result, Right(()))

    val disk = readDisk()
    // 真 key 存活（*** = 保留旧值）；被编辑字段落盘
    assertEquals(apiKeyOf(disk, "kimi"), Some("sk-real-kimi-1"))
    assertEquals(apiKeyOf(disk, "deepseek"), Some("sk-real-ds-2"))
    assertEquals(
      disk.hcursor.downField("llm").downField("providers").downField("kimi").downField("baseUrl").as[String],
      Right("https://kimi.example.com/v2")
    )
  }

  test("save through the masked surface lands nebflow.json owner-only (quickfix ① cross-check)") {
    posixOnly()
    val saved = PathUtil.dataRoot / "nebflow.json"
    os.write.over(saved, seedConfig())
    os.perms.set(saved, "rw-r--r--") // 遗留 0644
    ConfigService.updateConfig(seedConfig()).unsafeRunSync() match
      case Right(_) => ()
      case other => fail(s"updateConfig failed: $other")
    import java.nio.file.Files
    import java.nio.file.attribute.PosixFilePermission
    import scala.jdk.CollectionConverters.*
    val perms = Files.getPosixFilePermissions(saved.toNIO).asScala.toSet
    assertEquals(
      perms,
      Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
      "the config write path must land 0600 (keyaudit-quickfix ①)"
    )
  }

  private def posixOnly(): Unit =
    assume(
      !nebflow.core.CredentialFileAcl.isWindows(nebflow.core.CredentialFileAcl.currentOsName),
      "POSIX-only mode readback assertion"
    )

end ConfigSecretMaskSpec
