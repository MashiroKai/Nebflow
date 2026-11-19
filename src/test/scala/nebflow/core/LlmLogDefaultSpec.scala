package nebflow.core

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import nebflow.service.ConfigService
import nebflow.shared.PathUtil

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * LLM 日志「默认关」批（2026-09-13 作者裁定，只改默认值 + D-A 持久化）的回归门：
 *
 *   1. 默认态 = **关**（单点常量 [[LlmLogWriter.DefaultEnabled]]——禁残留第二份
 *      「默认 true」）；
 *   2. boot 读侧解析契约（无落盘值 / 节非法 ⇒ `None` ⇒ 调用方保持默认关；
 *      `None` 绝不回落 true）；
 *   3. WS 写侧定向落盘契约（顶层 `llmLog.enabled` 写入；其它顶层键零损失；
 *      写读同源 ⇒ 下次冷启动读到用户显式值）。
 *
 * 断言取舍（Verification Rigor：不写会假绿的门）：`LlmLogWriter.isEnabled` 是
 * **JVM 全局态**，suite 间共享同一 forked JVM（`Test / parallelExecution := false`
 * 只保证串行，不保证我的 suite 先跑），故「初值 = 关」**不**用 `isEnabled`
 * 断言（顺序相关的假绿/假红），改为钉单点常量；「冷启动实例读到的就是关」由
 * 隔离实例实测读数承担（交付说明 §验收①/③）——两层证据合起来覆盖默认态。
 */
class LlmLogDefaultSpec extends munit.FunSuite:

  // ── ① 默认态单点 ────────────────────────────────────────────────────

  test("默认态 = 关：LlmLogWriter.DefaultEnabled == false（单点定义，禁回落 true）") {
    assertEquals(LlmLogWriter.DefaultEnabled, false)
  }

  // ── ② boot 读侧：fail-safe 解析 ─────────────────────────────────────

  test("loadEnabled: 无落盘值（None）⇒ None——调用方保持默认关，不再回落 true") {
    assertEquals(LlmLogWriter.loadEnabled(None), None)
  }

  test("loadEnabled: 节缺失 / 非对象 / enabled 非布尔 ⇒ None（fail-safe，绝不解释成 true）") {
    assertEquals(LlmLogWriter.loadEnabled(Some(Json.obj())), None)
    assertEquals(LlmLogWriter.loadEnabled(Some(Json.obj("enabled" -> Json.fromString("yes")))), None)
    assertEquals(LlmLogWriter.loadEnabled(Some(Json.obj("enabled" -> Json.Null))), None)
    assertEquals(LlmLogWriter.loadEnabled(Some(Json.fromString("llmLog"))), None)
    assertEquals(LlmLogWriter.loadEnabled(Some(Json.Null)), None)
    assertEquals(LlmLogWriter.loadEnabled(Some(Json.arr())), None)
  }

  test("loadEnabled: 显式布尔是唯一有效输入") {
    assertEquals(LlmLogWriter.loadEnabled(Some(Json.obj("enabled" -> Json.True))), Some(true))
    assertEquals(LlmLogWriter.loadEnabled(Some(Json.obj("enabled" -> Json.False))), Some(false))
    // 节内其它字段不影响解析（前向兼容：将来加键不破坏读侧）
    assertEquals(
      LlmLogWriter.loadEnabled(Some(Json.obj("enabled" -> Json.True, "futureKey" -> Json.fromInt(1)))),
      Some(true)
    )
  }

  test("toConfigJson ↔ loadEnabled round-trip（读写同源，键名单点）") {
    assertEquals(LlmLogWriter.loadEnabled(Some(LlmLogWriter.toConfigJson(true))), Some(true))
    assertEquals(LlmLogWriter.loadEnabled(Some(LlmLogWriter.toConfigJson(false))), Some(false))
    assertEquals(LlmLogWriter.configSection, "llmLog")
  }

  // ── ③ 写侧：ConfigService 定向落盘（隔离 dataRoot）─────────────────

  private def withIsolatedHome[A](body: os.Path => A): Unit =
    val prevRoot = PathUtil.dataRoot
    val tmp = Files.createTempDirectory("nb-llmlogdefault-spec")
    try
      PathUtil.setDataRoot(os.Path(tmp.toString))
      body(PathUtil.dataRoot)
    finally
      PathUtil.setDataRoot(prevRoot)
      Files
        .walk(tmp)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(Files.deleteIfExists)

  private def readConfigJson(): Json =
    parse(os.read(PathUtil.configJsonWritePath(PathUtil.dataRoot))).fold(e => fail(s"config unparsable: $e"), identity)

  test("setLlmLogEnabled(true): 顶层 llmLog.enabled 落盘；其它顶层键零损失；读侧解析 ⇒ Some(true)") {
    withIsolatedHome { root =>
      val cfgPath = PathUtil.configJsonWritePath(root)
      os.write.over(
        cfgPath,
        Json
          .obj(
            "llm" -> Json.obj("providers" -> Json.obj("p1" -> Json.obj("baseUrl" -> Json.fromString("http://x")))),
            "toolResultTtl" -> Json.obj("enabled" -> Json.False),
            "safety" -> Json.obj("defaultMode" -> Json.fromString("ask"))
          )
          .spaces2,
        createFolders = true
      )

      ConfigService.setLlmLogEnabled(true).unsafeRunSync()

      val parsed = readConfigJson()
      // 键名契约（读侧 loadEnabled 与写侧 configSection 同源）
      assertEquals(
        LlmLogWriter.loadEnabled(parsed.hcursor.downField(LlmLogWriter.configSection).focus),
        Some(true)
      )
      // 其它顶层键零损失（read-modify-write 不吞邻居）
      assertEquals(parsed.hcursor.downField("toolResultTtl").downField("enabled").as[Boolean].toOption, Some(false))
      assertEquals(parsed.hcursor.downField("safety").downField("defaultMode").as[String].toOption, Some("ask"))
      assertEquals(
        parsed.hcursor.downField("llm").downField("providers").downField("p1").downField("baseUrl").as[String].toOption,
        Some("http://x")
      )
    }
  }

  test("setLlmLogEnabled(false): 同一节覆写（不新增第二个键、不回滚邻居）；读侧解析 ⇒ Some(false)") {
    withIsolatedHome { root =>
      val cfgPath = PathUtil.configJsonWritePath(root)
      os.write.over(
        cfgPath,
        Json.obj("toolResultTtl" -> Json.obj("enabled" -> Json.True)).spaces2,
        createFolders = true
      )

      ConfigService.setLlmLogEnabled(true).unsafeRunSync()
      ConfigService.setLlmLogEnabled(false).unsafeRunSync()

      val parsed = readConfigJson()
      assertEquals(
        LlmLogWriter.loadEnabled(parsed.hcursor.downField(LlmLogWriter.configSection).focus),
        Some(false)
      )
      assertEquals(parsed.asObject.map(_.keys.size), Some(2), "顶层键 = toolResultTtl + llmLog（不新增第三个）")
      assertEquals(parsed.hcursor.downField("toolResultTtl").downField("enabled").as[Boolean].toOption, Some(true))
    }
  }

  test("无落盘值 ⇒ 无 llmLog 节 ⇒ 读侧 None（既有安装升级后 = 默认关，不静默写盘）") {
    withIsolatedHome { root =>
      val cfgPath = PathUtil.configJsonWritePath(root)
      os.write.over(cfgPath, Json.obj("llm" -> Json.obj("providers" -> Json.obj())).spaces2, createFolders = true)
      val parsed = readConfigJson()
      assertEquals(parsed.hcursor.downField(LlmLogWriter.configSection).focus, None)
      assertEquals(LlmLogWriter.loadEnabled(parsed.hcursor.downField(LlmLogWriter.configSection).focus), None)
    }
  }

end LlmLogDefaultSpec
