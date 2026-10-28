package nebflow.llm.imagegen

import io.circe.parser.parse
import nebflow.llm.imagegen.ImageGenTypes.*
import nebflow.shared.ImageGenConfig

/**
 * imagegen 双层批（2026-10-24）spec——钉死六件事：
 *  1. provider 适配器纯函数面（尺寸归一化 / 请求体 / 响应解析，b64+url 双形态）；
 *  2. 配置节 graceful degrade（缺省关、半节解码、keyRef 指针）；
 *  3. job 台账生命周期（非阻塞 job 的状态权威面）；
 *  4. 编译期 seal 门控（release-strip 断言组：工具注册 / Nebula 面交付 / 件数常量）；
 *  5. 身份闸（工具 Nebula 专属——剥离面与授能面双保险）；
 *  6. 「release build 零痕迹」可验证锚（seal=false 构建跑本 spec 即红绿自证）。
 *
 * 真连验证（provider ≥2 实调）不在本 spec（用例需要真凭据）——走
 * scripts/imagegen-smoke.sh（cogview-3-flash 免费档 + 已配 key），结果钉在
 * 批 REPORT。
 */
class ImageGenSpec extends munit.FunSuite:

  private val sampleReq = ImageGenRequest(prompt = "a red apple on a wooden table")

  // ── 1a. 尺寸归一化 ─────────────────────────────────────────

  test("parseSize: 缺省/合法/非法形态") {
    assertEquals(ImageGenAdapters.parseSize(None), ImageGenAdapters.Dim(1024, 1024))
    assertEquals(ImageGenAdapters.parseSize(Some("800x600")), ImageGenAdapters.Dim(800, 600))
    assertEquals(ImageGenAdapters.parseSize(Some("garbage")), ImageGenAdapters.Dim(1024, 1024))
    assertEquals(ImageGenAdapters.parseSize(Some("1024x1024x3")), ImageGenAdapters.Dim(1024, 1024))
  }

  test("cogviewSize: cogview-4 任意分辨率原样传，flash 吸附枚举") {
    assertEquals(ImageGenAdapters.cogviewSize("cogview-4", ImageGenAdapters.Dim(1440, 720)), "1440x720")
    assertEquals(ImageGenAdapters.cogviewSize("cogview-3-flash", ImageGenAdapters.Dim(1440, 720)), "1344x768")
    assertEquals(ImageGenAdapters.cogviewSize("cogview-3-flash", ImageGenAdapters.Dim(700, 1100)), "768x1344")
    assertEquals(ImageGenAdapters.cogviewSize("cogview-3-flash", ImageGenAdapters.Dim(1000, 1000)), "1024x1024")
  }

  test("openAiImagesSize: gpt-image-1 三档按长宽比吸附") {
    assertEquals(ImageGenAdapters.openAiImagesSize(ImageGenAdapters.Dim(1024, 1024)), "1024x1024")
    assertEquals(ImageGenAdapters.openAiImagesSize(ImageGenAdapters.Dim(1400, 900)), "1536x1024")
    assertEquals(ImageGenAdapters.openAiImagesSize(ImageGenAdapters.Dim(800, 1200)), "1024x1536")
  }

  test("imagenAspect: 长宽比换 aspectRatio 枚举") {
    assertEquals(ImageGenAdapters.imagenAspect(ImageGenAdapters.Dim(1024, 1024)), "1:1")
    assertEquals(ImageGenAdapters.imagenAspect(ImageGenAdapters.Dim(1400, 900)), "4:3")
    assertEquals(ImageGenAdapters.imagenAspect(ImageGenAdapters.Dim(900, 1400)), "3:4")
    assertEquals(ImageGenAdapters.imagenAspect(ImageGenAdapters.Dim(1920, 800)), "16:9")
    assertEquals(ImageGenAdapters.imagenAspect(ImageGenAdapters.Dim(800, 1920)), "9:16")
  }

  // ── 1b. 请求体纯函数（token 永不进 body）────────────────────

  test("CogView.buildBody: 自家 size 枚举 + response_format，body 无凭据位") {
    val body = ImageGenAdapters.CogView.buildBody("cogview-3-flash", sampleReq.copy(size = Some("1000x1000")))
    val cursor = body.hcursor
    assertEquals(cursor.downField("model").as[String].toOption, Some("cogview-3-flash"))
    assertEquals(cursor.downField("size").as[String].toOption, Some("1024x1024"))
    assertEquals(cursor.downField("prompt").as[String].toOption, Some(sampleReq.prompt))
    assertEquals(cursor.downField("response_format").as[String].toOption, Some("url"))
    assert(!body.noSpaces.contains("Authorization"), "body must never carry credential material")
  }

  test("OpenAiImages.buildBody: n=1 + size 吸附 + 无凭据位") {
    val body = ImageGenAdapters.OpenAiImages.buildBody("gpt-image-1", sampleReq.copy(size = Some("1400x900")))
    val cursor = body.hcursor
    assertEquals(cursor.downField("n").as[Int].toOption, Some(1))
    assertEquals(cursor.downField("size").as[String].toOption, Some("1536x1024"))
    assert(!body.noSpaces.toLowerCase.contains("bearer"))
  }

  test("Imagen.buildBody: instances/parameters 双段协议") {
    val body = ImageGenAdapters.Imagen.buildBody("imagen-3.0-generate-002", sampleReq)
    assertEquals(
      body.hcursor.downField("instances").downArray.downField("prompt").as[String].toOption,
      Some(sampleReq.prompt)
    )
    assertEquals(body.hcursor.downField("parameters").downField("sampleCount").as[Int].toOption, Some(1))
  }

  test("endpointFor: 协议决定路径形态") {
    assertEquals(
      ImageGenAdapters.endpointFor(ImageGenAdapters.CogView, "https://open.bigmodel.cn/api/paas/v4", "cogview-3-flash"),
      "https://open.bigmodel.cn/api/paas/v4/images/generations"
    )
    assertEquals(
      ImageGenAdapters.endpointFor(
        ImageGenAdapters.Imagen,
        "https://generativelanguage.googleapis.com/v1beta",
        "imagen-3.0-generate-002"
      ),
      "https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:generateImages"
    )
  }

  // ── 1c. 响应解析（url / b64 双形态 + 错误面）────────────────

  test("CogView.parseResult: 优先 url，兼容 b64") {
    val withUrl = parse("""{"data":[{"url":"https://example.com/a.png"}]}""").toOption.get
    assertEquals(ImageGenAdapters.CogView.parseResult(withUrl), Right(ImageRef.Url("https://example.com/a.png")))
    val withB64 = parse("""{"data":[{"b64_json":"QUJD"}]}""").toOption.get
    assertEquals(ImageGenAdapters.CogView.parseResult(withB64), Right(ImageRef.B64("QUJD")))
  }

  test("OpenAiImages.parseResult: gpt-image-1 b64 形态 + 空数据错误面") {
    val b64 = parse("""{"data":[{"b64_json":"QUJD"}]}""").toOption.get
    assertEquals(ImageGenAdapters.OpenAiImages.parseResult(b64), Right(ImageRef.B64("QUJD")))
    val empty = parse("""{"data":[]}""").toOption.get
    assert(ImageGenAdapters.OpenAiImages.parseResult(empty).isLeft)
  }

  test("Imagen.parseResult: bytesBase64Encoded + 缺字段错误面") {
    val ok = parse("""{"predictions":[{"bytesBase64Encoded":"QUJD"}]}""").toOption.get
    assertEquals(ImageGenAdapters.Imagen.parseResult(ok), Right(ImageRef.B64("QUJD")))
    val missing = parse("""{"predictions":[{}]}""").toOption.get
    assert(ImageGenAdapters.Imagen.parseResult(missing).isLeft)
  }

  test("materialize: b64 解码、非法 b64、空载荷三面") {
    val resolved = ResolvedProvider("t", ImageGenAdapters.CogView, "https://x", "m", "token", 1000)
    assertEquals(
      ImageGenService.materialize(ImageRef.B64("QUJD"), resolved).map(_.toList),
      Right(List(65.toByte, 66, 67))
    )
    assert(ImageGenService.materialize(ImageRef.B64("!!!not-base64!!!"), resolved).isLeft)
    assert(ImageGenService.materialize(ImageRef.B64(""), resolved).isLeft)
  }

  test("sniffExt: magic bytes 定格式（cogview 实测回 JPEG——不臆造 PNG）") {
    assertEquals(ImageGenService.sniffExt(Array(0x89.toByte, 80, 78, 71, 13)), "png")
    assertEquals(ImageGenService.sniffExt(Array(0xff.toByte, 0xd8.toByte, 0xff.toByte, 0xe0.toByte)), "jpeg")
    assertEquals(ImageGenService.sniffExt(Array('R'.toByte, 'I'.toByte, 'F'.toByte, 'F'.toByte, 0, 0)), "webp")
    assertEquals(ImageGenService.sniffExt(Array('G'.toByte, 'I'.toByte, 'F'.toByte, 56)), "gif")
    assertEquals(ImageGenService.sniffExt(Array(1.toByte, 2, 3, 4)), "png", "未知格式按 png 落（img 按内容渲染）")
  }

  // ── 2. 配置节 decode ───────────────────────────────────────

  test("ImageGenConfig decode: 全节") {
    val json =
      """{"enabled":true,"defaultProvider":"zhipu","providers":{"zhipu":{"type":"cogview","model":"cogview-3-flash","keyRef":"zhipu-api-key"}}}"""
    val decoded = parse(json).flatMap(_.as[ImageGenConfig]).toOption.get
    assert(decoded.isEnabled)
    assertEquals(decoded.defaultProvider, Some("zhipu"))
    assertEquals(decoded.providers.flatMap(_.get("zhipu")).map(_.model), Some("cogview-3-flash"))
  }

  test("ImageGenConfig decode: 半节 graceful degrade（缺省关、空 providers 合法）") {
    val decoded = parse("""{"enabled":true}""").flatMap(_.as[ImageGenConfig]).toOption.get
    assert(decoded.isEnabled)
    assert(decoded.providers.isEmpty)
    assert(!parse("""{}""").flatMap(_.as[ImageGenConfig]).toOption.get.isEnabled, "enabled 缺省必须关（安全缺省方向）")
  }

  // ── 3. job 台账生命周期 ─────────────────────────────────────

  test("ImageGenJobs: register → complete/fail 翻转 + get/snapshot + 未知 job") {
    val before = ImageGenJobs.snapshot.length
    ImageGenJobs.register("img-spectest-1", Some("sess"), "a cat")
    ImageGenJobs.register("img-spectest-2", None, "a dog")
    val running = ImageGenJobs.get("img-spectest-1").get
    assertEquals(running.status, ImageGenJobs.Status.Running)
    assertEquals(running.path, None)

    ImageGenJobs.complete("img-spectest-1", "/tmp/img-spectest-1.png")
    val done = ImageGenJobs.get("img-spectest-1").get
    assertEquals(done.status, ImageGenJobs.Status.Completed)
    assertEquals(done.path, Some("/tmp/img-spectest-1.png"))

    ImageGenJobs.fail("img-spectest-2", "boom")
    assertEquals(ImageGenJobs.get("img-spectest-2").get.status, ImageGenJobs.Status.Failed)
    assertEquals(ImageGenJobs.get("img-spectest-2").get.error, Some("boom"))

    assert(ImageGenJobs.get("img-nope").isEmpty)
    assert(ImageGenJobs.snapshot.length >= before + 2)
    ImageGenJobs.complete("img-nope", "/x") // 未知 job 的写点是安静 no-op（不回注）
    assert(ImageGenJobs.get("img-nope").isEmpty)
  }

  // ── 4+5+6. 编译期 seal 门控 + 身份面（release-strip 断言组）────
  //
  // 🔴 本组是「release build 零痕迹」的验证锚：seal=false 的构建跑本 spec，
  //    下述断言以 CompiledIn=false 形态全绿——工具不在注册表、不在 Nebula
  //    交付面、非 Nebula 身份永不可得；seal=true 的构建镜像全绿在場。任何一侧
  //    红 = 门控破损。

  test("seal: generate_image 在注册表 iff 编译进产物") {
    val present = nebflow.core.tools.ToolRegistry.TOOL_MAP.contains("generate_image")
    assertEquals(present, nebflow.core.ImageGenSeal.CompiledIn)
  }

  test("seal: Nebula 固定面交付 iff 编译进产物（静态集与件数常量不动）") {
    val nebulaDef = nebflow.actor.AgentDef(name = "Nebula", description = "root")
    val delivered = nebflow.agent.AgentCore.fixedToolsFor(nebulaDef)
    assertEquals(delivered.contains("generate_image"), nebflow.core.ImageGenSeal.CompiledIn)
    // 件数常量钉死静态集大小（FriendsSealKit 同款纪律：期望值由交付快照推导）。
    val constant = nebflow.agent.AgentCore.RootOrchestrationToolsExpectedSize
    assertEquals(nebflow.agent.AgentCore.RootOrchestrationTools.size, constant)
  }

  test("seal: 非 Nebula 身份永不获得 generate_image（防声明逃逸面）") {
    val execDef = nebflow.actor.AgentDef(name = "nebflow", description = "executor")
    assert(!nebflow.agent.AgentCore.fixedToolsFor(execDef).contains("generate_image"))
  }

  test("seal: 期望常量 = 静态集大小（19，imagegen 双层批按面变更）") {
    assertEquals(nebflow.agent.AgentCore.RootOrchestrationToolsExpectedSize, 19)
  }
end ImageGenSpec
