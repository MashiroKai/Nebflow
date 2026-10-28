package nebflow.gateway

import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.shared.PathUtil

import cats.effect.unsafe.implicits.global

import java.nio.file.Files

/**
 * settings-panel-jev-imagegen 批 D 件（2026-10-28）spec——STT 总开关 enabled：
 *  1. parsePatch 三态（省略=保留 / 显式布尔=设值 / 非布尔=拒）；
 *  2. mergeConfig：enabled 省略保留旧值；显式写盘；清空三串 + enabled=false ⇒
 *     文件保留 {enabled:false}（停用态跨重启，不再整体删档）；
 *  3. serverConfigNode：sttEnabled 权威回声（缺席文件=true 缺省——存量用户
 *     零感知）；apiKey 永不外露的既有铁律不回归；
 *  4. 引擎腿纵深：enabled=false 的服务 transcribe 一律拒绝；
 *  5. create()：盘上 enabled=false 读入服务。
 */
class SttEnabledSpec extends munit.FunSuite:

  private def withDataRoot[A](f: os.Path => A): A =
    val tmp = os.Path(Files.createTempDirectory("stt-enabled-spec"))
    val original = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp)
    try f(tmp)
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  private val noPatch = SttService.SttConfigPatch(None, None, None)

  test("parsePatch: enabled 省略=None，显式布尔=Some，非布尔拒绝") {
    assertEquals(SttService.parsePatch(parse("{}").toOption.get).map(_.enabled), Right(None))
    assertEquals(SttService.parsePatch(parse("""{"enabled":false}""").toOption.get).map(_.enabled), Right(Some(false)))
    assertEquals(SttService.parsePatch(parse("""{"enabled":true}""").toOption.get).map(_.enabled), Right(Some(true)))
    assert(SttService.parsePatch(parse("""{"enabled":"yes"}""").toOption.get).isLeft, "非布尔 enabled 必须 400")
    // 既有三字段形态零回归：字符串字段照旧解析。
    val mixed = SttService.parsePatch(parse("""{"endpoint":"https://x/y","model":"asr"}""").toOption.get)
    assertEquals(mixed.map(_.endpoint), Right(Some(Some("https://x/y"))))
    assertEquals(mixed.map(_.model), Right(Some(Some("asr"))))
  }

  test("mergeConfig: 省略=保留旧值；显式=覆写；清空三串+关 ⇒ 文件保留 {enabled:false}") {
    val old = parse("""{"apiKey":"k","model":"m","endpoint":"https://e/x","enabled":true}""").toOption.get
    // 省略 enabled → 旧值保留。
    val kept = SttService.mergeConfig(Some(old), noPatch).get
    assertEquals(kept.hcursor.downField("enabled").as[Boolean].toOption, Some(true))
    // 显式 false → 覆写，其余字段原样。
    val off = SttService.mergeConfig(Some(old), noPatch.copy(enabled = Some(false))).get
    assertEquals(off.hcursor.downField("enabled").as[Boolean].toOption, Some(false))
    assertEquals(off.hcursor.downField("apiKey").as[String].toOption, Some("k"))
    // 清空三串 + enabled=false：结果只剩 {enabled:false}（非 None——停用态要
    // 落盘存活，下次开回只需翻开关，凭据字段已被显式清除）。
    val clearOff = SttService.mergeConfig(Some(old), SttService.SttConfigPatch(Some(None), Some(None), Some(None), Some(false)))
    assertEquals(clearOff.map(_.hcursor.downField("enabled").as[Boolean].toOption), Some(Some(false)))
    assertEquals(clearOff.map(_.hcursor.downField("apiKey").as[Option[String]].toOption.flatten), Some(None))
    // 清空三串 + enabled=true ⇒ None（整体删档，回退浏览器腿——既有语义）。
    assertEquals(SttService.mergeConfig(Some(old), SttService.SttConfigPatch(Some(None), Some(None), Some(None), Some(true))), None)
    // 无旧文件 + 仅 enabled=false：文件 = {enabled:false}（先关后配的形态）。
    assertEquals(
      SttService.mergeConfig(None, noPatch.copy(enabled = Some(false))).map(_.hcursor.downField("enabled").as[Boolean].toOption),
      Some(Some(false))
    )
  }

  test("serverConfigNode: sttEnabled 权威回声——缺文件=true 缺省（存量零感知），停用文件=false；apiKey 永不外露") {
    withDataRoot { root =>
      // ① 无配置文件：sttEnabled=true（缺省开——存量行为逐字不变）。
      val none = SttService.serverConfigNode(None).unsafeRunSync()
      assertEquals(none.hcursor.downField("sttEnabled").as[Boolean].toOption, Some(true))
      assertEquals(none.hcursor.downField("sttConfigured").as[Boolean].toOption, Some(false))
      // ② 停用文件（无 apiKey）：sttEnabled=false 权威回声——前端据此收口。
      os.write(root / "stt-config.json", """{"enabled":false}""")
      assertEquals(
        SttService.serverConfigNode(None).unsafeRunSync().hcursor.downField("sttEnabled").as[Boolean].toOption,
        Some(false)
      )
      // ③ 在配服务 + 停用：configured 与 enabled 两个真值并陈。
      os.write.over(root / "stt-config.json", """{"apiKey":"sk-secret","model":"asr","endpoint":"https://e/x","enabled":false}""")
      val svc = SttService.create().unsafeRunSync()
      assert(svc.isDefined, "apiKey 在 ⇒ 服务对象仍建（停用是开关语义，不是配置丢失）")
      val node = SttService.serverConfigNode(svc).unsafeRunSync()
      assertEquals(node.hcursor.downField("sttEnabled").as[Boolean].toOption, Some(false))
      assertEquals(node.hcursor.downField("sttConfigured").as[Boolean].toOption, Some(true))
      assert(!node.noSpaces.contains("sk-secret"), "apiKey 永不外露铁律不回归")
    }
  }

  test("引擎腿纵深: enabled=false 的服务 transcribe 一律拒绝（直连 WS 帧也拿不到转写）") {
    val svc = new SttService("k", "m", "https://e/x", enabled = false)
    svc.transcribe(Array(1.toByte, 2, 3), None).unsafeRunSync() match
      case Left(err) => assert(err.toLowerCase.contains("disabled"), s"拒绝文案须可诊断: $err")
      case Right(_)  => fail("disabled service must not transcribe")
    // 开启态 + 空音频 = 既有空载错误（零回归）。
    val on = new SttService("k", "m", "https://e/x", enabled = true)
    assertEquals(on.transcribe(Array.empty, None).unsafeRunSync(), Left("Empty audio data"))
  }

  test("create(): 盘上 enabled=false 读入服务；缺席=true 缺省") {
    withDataRoot { root =>
      os.write(root / "stt-config.json", """{"apiKey":"k","model":"m","endpoint":"https://e/x","enabled":false}""")
      assertEquals(SttService.create().unsafeRunSync().map(_.enabled), Some(false))
      os.write.over(root / "stt-config.json", """{"apiKey":"k","model":"m","endpoint":"https://e/x"}""")
      assertEquals(SttService.create().unsafeRunSync().map(_.enabled), Some(true), "缺席 enabled = true（存量文件零迁移）")
    }
  }
end SttEnabledSpec
