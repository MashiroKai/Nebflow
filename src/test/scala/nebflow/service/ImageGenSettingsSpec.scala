package nebflow.service

import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.Json
import nebflow.shared.PathUtil

import cats.effect.unsafe.implicits.global

import java.nio.file.Files

/**
 * settings-panel-jev-imagegen 批（2026-10-28）spec——生图设置面三件：
 *  1. `validateImagegenSection`：白名单越界拒绝 / 不可解码拒绝 / 适配器缺席的
 *     type 拒绝（typos fail-loud 不落盘）；
 *  2. `setImagegenSection`：整节替换语义（缺键=从节消失）+ 读回一致 + 写盘限权；
 *  3. `imagegenStatus`：compiledIn = 编译期 seal 常量的**镜像**（seal=false 构建
 *     跑本 spec 即全绿 false 形态，seal=true 构建镜像全绿——两形态 spec 钉）+
 *     配置热读投影 + keyConfigured **在位性**（存在且非空即真；值永不进投影）。
 */
class ImageGenSettingsSpec extends munit.FunSuite:

  private val fullSection =
    """{"enabled":true,"defaultProvider":"zhipu","providers":{"zhipu":{"type":"cogview","model":"cogview-3-flash","keyRef":"zhipu-api-key"}}}"""

  private def withDataRoot[A](f: os.Path => A): A =
    val tmp = os.Path(Files.createTempDirectory("imagegen-settings-spec"))
    val original = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    try f(tmp)
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  test("validate: 全节合法、越界键拒绝、坏 type 拒绝、非对象拒绝") {
    assertEquals(ConfigService.validateImagegenSection(parse(fullSection).toOption.get), Right(()))
    val unknown = parse("""{"enabled":true,"mystery":1}""").toOption.get
    assert(ConfigService.validateImagegenSection(unknown).isLeft, "白名单越界键必须拒绝")
    val badType = parse("""{"providers":{"x":{"type":"nope","model":"m"}}}""").toOption.get
    assert(ConfigService.validateImagegenSection(badType).isLeft, "适配器缺席的 type 必须 fail-loud")
    assert(ConfigService.validateImagegenSection(Json.fromInt(1)).isLeft, "非对象节必须拒绝")
  }

  test("setImagegenSection: 整节替换（缺键=消失）+ 读回一致 + 空体拒写") {
    withDataRoot { root =>
      // 前置节：enabled=true + 两个 provider；替换后仅剩新形态。
      val seed = parse("""{"imagegen":{"enabled":true,"defaultProvider":"a","providers":{"a":{"type":"cogview","model":"m1"},"b":{"type":"imagen","model":"m2"}}}}""").toOption.get
      val prep = ConfigService.setImagegenSection(seed.hcursor.downField("imagegen").focus.get)
      assert(prep.unsafeRunSync().isRight)

      val next = parse("""{"enabled":false,"providers":{"z":{"type":"openai-images","model":"gpt-image-1"}}}""").toOption.get
      ConfigService.setImagegenSection(next).unsafeRunSync() match
        case Left(err) => fail(s"write refused: $err")
        case Right(()) => ()

      val status = ConfigService.imagegenStatus.unsafeRunSync()
      assertEquals(status.hcursor.downField("enabled").as[Boolean].toOption, Some(false), "整节替换：旧 enabled 置换")
      assertEquals(status.hcursor.downField("defaultProvider").as[Option[String]].toOption, Some(None), "缺键=从节消失")
      val providers = status.hcursor.downField("providers").as[Map[String, Json]].toOption.get
      assertEquals(providers.keySet, Set("z"), "旧 providers 全部让位（整节替换语义）")
      assertEquals(providers("z").hcursor.downField("model").as[String].toOption, Some("gpt-image-1"))

      // 空对象 = 整节清空（回到未配置态——graceful degrade 方向，与删节同
      // 义；读回 configured=false）。
      ConfigService.setImagegenSection(parse("{}").toOption.get).unsafeRunSync() match
        case Left(err) => fail(s"empty section = clear-to-unconfigured, refused: $err")
        case Right(()) => ()
      val cleared = ConfigService.imagegenStatus.unsafeRunSync()
      assertEquals(cleared.hcursor.downField("configured").as[Boolean].toOption, Some(false))
      assertEquals(cleared.hcursor.downField("providers").as[Map[String, Json]].toOption.get.isEmpty, true)
    }
  }

  test("imagegenStatus: compiledIn 镜像 seal 常量（两形态 spec 锚）+ 未配置态零造假") {
    withDataRoot { _ =>
      val status = ConfigService.imagegenStatus.unsafeRunSync()
      // 🔴 镜像锚：投影的 compiledIn 与编译期 seal 常量逐字相等——seal=OFF
      //   构建 = false（面板显示「未编入」），seal=ON 构建 = true（正常配置面）。
      //   任何一侧不同步 = 两形态契约破损。
      assertEquals(status.hcursor.downField("compiledIn").as[Boolean].toOption, Some(nebflow.core.ImageGenSeal.CompiledIn))
      assertEquals(status.hcursor.downField("configured").as[Boolean].toOption, Some(false))
      assertEquals(status.hcursor.downField("enabled").as[Boolean].toOption, Some(false))
      val providers = status.hcursor.downField("providers").as[Map[String, Json]].toOption.get
      assertEquals(providers.isEmpty, true, "未配置态 providers 空对象（不是 null/缺席）")
      // 调试标记随投影走（面板徽标数据源；设计六验收⑥的面板腿）。
      assertEquals(status.hcursor.downField("debugLabel").as[String].toOption, Some("调试中"))
    }
  }

  test("imagegenStatus: keyConfigured 在位性——缺文件/空文件=false，有值=true，投影零 key 值") {
    withDataRoot { root =>
      os.makeDir.all(root / "secrets")
      val section = parse("""{"enabled":true,"defaultProvider":"zhipu","providers":{"zhipu":{"type":"cogview","model":"cogview-3-flash","keyRef":"spec-key"}}}""").toOption.get
      ConfigService.setImagegenSection(section).unsafeRunSync() match
        case Left(err) => fail(s"write refused: $err")
        case Right(()) => ()

      def keyConfigured: Boolean =
        ConfigService.imagegenStatus.unsafeRunSync().hcursor
          .downField("providers").downField("zhipu").downField("keyConfigured").as[Boolean].toOption.get

      // ① 缺文件 = 待录入（面板「待录入」形态的数据源；引擎腿关同判据）。
      assertEquals(keyConfigured, false)
      // ② 空文件 = 仍缺（readSecret 同判据：空串视为缺件）。
      os.write(root / "secrets" / "spec-key", "")
      assertEquals(keyConfigured, false)
      // ③ 有值 = 已配置。
      os.write.over(root / "secrets" / "spec-key", "sk-spec-value")
      assertEquals(keyConfigured, true)
      // 🔴 投影零 key 值：值的出现位置只能是 secrets 文件本身。
      val wire = ConfigService.imagegenStatus.unsafeRunSync().noSpaces
      assert(!wire.contains("sk-spec-value"), "key 值绝不进状态投影载荷")
    }
  }
end ImageGenSettingsSpec
