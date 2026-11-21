package nebflow.agent

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.{AskItem, AskOption, AskPreview, AskPreviewMedia}

/**
 * #380 backend passthrough contract (V11): buildAskUserJson emits the optional
 * `canvas` (question-level) and `preview` (option-level) fields only when
 * present — a payload without them stays byte-identical to the pre-#380 wire
 * format, so the frontend direction-C consumption is additive and zero-risk.
 */
class AskUserBuildJsonSpec extends FunSuite:

  private def frame(items: List[AskItem]): Json =
    AgentActor.buildAskUserJson(Some("root-1"), "Backend", items, Some("Backend"), Some("team-abc"))

  test("merge configuration is forwarded in the ask event") {
    val config = Json.obj("target" -> "main".asJson, "branches" -> Json.arr(Json.obj("name" -> "feat/a".asJson)))
    val item = AskItem("Merge?", Nil, merge = Some(config))
    assertEquals(frame(List(item)).hcursor.downField("items").downN(0).downField("merge").focus, Some(config))
  }

  test("V11a: canvas + preview are emitted when present") {
    val items = List(
      AskItem(
        "Choose a scheme",
        List(
          AskOption("Morning mist", preview = Some(AskPreview("swatch", colors = Some(List("#6b9c8a", "#a8c3b5"))))),
          AskOption("Image A", preview = Some(AskPreview("image", src = Some("https://example.com/a.png"))))
        ),
        canvas = Some("/abs/path/compare.html")
      )
    )
    val itemsJson = frame(items).hcursor.downField("items").as[List[Json]].toOption.get
    assertEquals(itemsJson.head.hcursor.downField("canvas").as[String], Right("/abs/path/compare.html"))
    val opts = itemsJson.head.hcursor.downField("options").as[List[Json]].toOption.get
    assertEquals(opts(0).hcursor.downField("preview").downField("type").as[String], Right("swatch"))
    assertEquals(
      opts(0).hcursor.downField("preview").downField("colors").as[List[String]],
      Right(List("#6b9c8a", "#a8c3b5"))
    )
    assertEquals(opts(0).hcursor.downField("preview").downField("src").focus, None)
    assertEquals(opts(1).hcursor.downField("preview").downField("type").as[String], Right("image"))
    assertEquals(
      opts(1).hcursor.downField("preview").downField("src").as[String],
      Right("https://example.com/a.png")
    )
  }

  test("V11b: no canvas/preview → payload byte-identical to the pre-#380 shape") {
    val items = List(AskItem("Continue?", List(AskOption("yes"), AskOption("no"))))
    val expected =
      """{"type":"askUser","sessionId":"root-1","agentName":"Backend","sourceAgent":"Backend",""" +
        """"sourceSession":"team-abc","items":[{"question":"Continue?","options":[{"label":"yes"},{"label":"no"}],""" +
        """"allowOther":true}]}"""
    assertEquals(frame(items).noSpaces, expected)
  }

  test("V11c: preview with description keeps both keys in order") {
    val items = List(
      AskItem(
        "Pick",
        List(
          AskOption(
            "A",
            description = Some("explain"),
            preview = Some(AskPreview("swatch", colors = Some(List("#111"))))
          )
        )
      )
    )
    val opts = frame(items).hcursor
      .downField("items")
      .as[List[Json]]
      .toOption
      .get
      .head
      .hcursor
      .downField("options")
      .as[List[Json]]
      .toOption
      .get
    val keys = opts.head.hcursor.keys.map(_.toSet).getOrElse(Set.empty)
    assertEquals(keys, Set("label", "description", "preview"))
    assertEquals(
      opts.head.hcursor.downField("preview").downField("colors").as[List[String]],
      Right(List("#111"))
    )
  }

  // ---- askuser-media 批（2026-11-18）：视频 / 媒体组的载荷契约 ----

  test("V11d: video preview emits type + src + poster (only when present)") {
    val items = List(
      AskItem(
        "Pick a take",
        List(
          AskOption(
            "Take 1",
            preview = Some(AskPreview("video", src = Some("/tmp/a.mp4"), poster = Some("/tmp/a.jpg")))
          ),
          AskOption("Take 2", preview = Some(AskPreview("video", src = Some("https://cdn.example.com/b.mp4"))))
        )
      )
    )
    val opts = frame(items).hcursor
      .downField("items")
      .as[List[Json]]
      .toOption
      .get
      .head
      .hcursor
      .downField("options")
      .as[List[Json]]
      .toOption
      .get
    assertEquals(opts(0).hcursor.downField("preview").downField("type").as[String], Right("video"))
    assertEquals(opts(0).hcursor.downField("preview").downField("src").as[String], Right("/tmp/a.mp4"))
    assertEquals(opts(0).hcursor.downField("preview").downField("poster").as[String], Right("/tmp/a.jpg"))
    assertEquals(opts(0).hcursor.downField("preview").downField("items").focus, None)
    // poster absent ⇒ key absent (only-present emission, the V11 contract)
    assertEquals(opts(1).hcursor.downField("preview").downField("poster").focus, None)
  }

  test("V11e: media group emits items[] with kind/src/poster/name/path (only when present)") {
    val items = List(
      AskItem(
        "Which design?",
        List(
          AskOption(
            "Design A",
            description = Some("warm"),
            preview = Some(
              AskPreview(
                "media",
                items = Some(
                  List(
                    AskPreviewMedia(kind = "image", src = Some("https://example.com/a.png"), name = Some("a.png")),
                    AskPreviewMedia(
                      kind = "video",
                      src = Some("/tmp/b.mp4"),
                      poster = Some("/tmp/b.jpg"),
                      path = Some("/tmp/b.mp4")
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
    val preview = frame(items).hcursor
      .downField("items")
      .as[List[Json]]
      .toOption
      .get
      .head
      .hcursor
      .downField("options")
      .as[List[Json]]
      .toOption
      .get
      .head
      .hcursor
      .downField("preview")
      .focus
      .get
    assertEquals(preview.hcursor.downField("type").as[String], Right("media"))
    val group = preview.hcursor.downField("items").as[List[Json]].toOption.get
    assertEquals(group.size, 2)
    assertEquals(group(0).hcursor.downField("kind").as[String], Right("image"))
    assertEquals(group(0).hcursor.downField("path").focus, None)
    assertEquals(group(1).hcursor.downField("kind").as[String], Right("video"))
    assertEquals(group(1).hcursor.downField("poster").as[String], Right("/tmp/b.jpg"))
    assertEquals(group(1).hcursor.downField("name").focus, None)
  }

  test("V11f: swatch / image previews gain no new keys (byte-identical wire format)") {
    val items = List(
      AskItem(
        "Pick",
        List(
          AskOption("S", preview = Some(AskPreview("swatch", colors = Some(List("#111"))))),
          AskOption("I", preview = Some(AskPreview("image", src = Some("https://example.com/a.png"))))
        )
      )
    )
    val opts = frame(items).hcursor
      .downField("items")
      .as[List[Json]]
      .toOption
      .get
      .head
      .hcursor
      .downField("options")
      .as[List[Json]]
      .toOption
      .get
    assertEquals(opts(0).hcursor.downField("preview").focus.get.noSpaces, """{"type":"swatch","colors":["#111"]}""")
    assertEquals(
      opts(1).hcursor.downField("preview").focus.get.noSpaces,
      """{"type":"image","src":"https://example.com/a.png"}"""
    )
  }

  // ---- D6 批 F1（G9 来源标注）：project/nodeName 字段契约 ----

  test("F1a: project + nodeName are emitted when present (project node ask)") {
    val items = List(AskItem("Which scheme?", List(AskOption("A"), AskOption("B"))))
    val json = AgentActor.buildAskUserJson(
      Some("root-1"),
      "general",
      items,
      Some("general"),
      Some("node-abc"),
      project = Some("Nebflow"),
      nodeName = Some("实施-F1F2")
    )
    assertEquals(json.hcursor.downField("project").as[String], Right("Nebflow"))
    assertEquals(json.hcursor.downField("nodeName").as[String], Right("实施-F1F2"))
  }

  test("F1b: dispatcher ask carries nodeName=dispatcher") {
    val items = List(AskItem("Proceed?", List(AskOption("yes"), AskOption("no"))))
    val json = AgentActor.buildAskUserJson(
      Some("root-1"),
      "dispatcher",
      items,
      Some("dispatcher"),
      Some("disp-1"),
      project = Some("Nebflow"),
      nodeName = Some("dispatcher")
    )
    assertEquals(json.hcursor.downField("project").as[String], Right("Nebflow"))
    assertEquals(json.hcursor.downField("nodeName").as[String], Right("dispatcher"))
  }

  test("F1c: no project/nodeName → payload stays byte-identical to the pre-F1 shape") {
    val items = List(AskItem("Continue?", List(AskOption("yes"), AskOption("no"))))
    val json = frame(items)
    assertEquals(json.hcursor.downField("project").focus, None)
    assertEquals(json.hcursor.downField("nodeName").focus, None)
    val expected =
      """{"type":"askUser","sessionId":"root-1","agentName":"Backend","sourceAgent":"Backend",""" +
        """"sourceSession":"team-abc","items":[{"question":"Continue?","options":[{"label":"yes"},{"label":"no"}],""" +
        """"allowOther":true}]}"""
    assertEquals(json.noSpaces, expected)
  }

  // ---- attachments 批（root 2026-10-02 令 #462 裁③）：顶层可选键 ----

  test("A1: attachments 在场 ⇒ 顶层落键（与 items 平级，非逐问题承载）") {
    val items = List(AskItem("Open which file?", List(AskOption("A"), AskOption("B"))))
    val json = AgentActor.buildAskUserJson(
      Some("root-1"),
      "Nebula",
      items,
      Some("Nebula"),
      Some("root-1"),
      attachments = List("/data/docs/a.pdf", "/data/plots/b.png")
    )
    assertEquals(
      json.hcursor.downField("attachments").as[List[String]],
      Right(List("/data/docs/a.pdf", "/data/plots/b.png"))
    )
    // 顶层 = items 的兄弟键（不是 items 里的元素键）
    assertEquals(
      json.asObject.map(_.keys.toSet),
      Some(Set("type", "sessionId", "agentName", "sourceAgent", "sourceSession", "attachments", "items"))
    )
    val item0 = json.hcursor.downField("items").as[List[Json]].toOption.get.head
    assertEquals(item0.hcursor.downField("attachments").focus, None, "附件不得挂到单条问题上")
  }

  test("A1c: attachments 缺席/空 ⇒ 不落键（无附件载荷逐字节等于既有形态）") {
    val items = List(AskItem("Continue?", List(AskOption("yes"), AskOption("no"))))
    val expected =
      """{"type":"askUser","sessionId":"root-1","agentName":"Backend","sourceAgent":"Backend",""" +
        """"sourceSession":"team-abc","items":[{"question":"Continue?","options":[{"label":"yes"},{"label":"no"}],""" +
        """"allowOther":true}]}"""
    assertEquals(frame(items).noSpaces, expected, "默认态（Nil）必须逐字节等于既有形态")
    assertEquals(
      AgentActor
        .buildAskUserJson(Some("root-1"), "Backend", items, Some("Backend"), Some("team-abc"), attachments = Nil)
        .noSpaces,
      expected
    )
  }

  // ---- askuser-secret 批（2026-10-08 · 规格 §4.3）：secret 型题四键只在场发 ----

  test("SEC1: secret 题四键在场发（secret/secretName/ephemeral/ttlSec），零 options") {
    val items = List(
      AskItem("API key?", Nil, secret = true, secretName = Some("my-api-key"), ephemeral = true, ttlSec = Some(120))
    )
    val item0 = frame(items).hcursor.downField("items").as[List[Json]].toOption.get.head
    assertEquals(item0.hcursor.downField("secret").as[Boolean], Right(true))
    assertEquals(item0.hcursor.downField("secretName").as[String], Right("my-api-key"))
    assertEquals(item0.hcursor.downField("ephemeral").as[Boolean], Right(true))
    assertEquals(item0.hcursor.downField("ttlSec").as[Int], Right(120))
    assertEquals(item0.hcursor.downField("options").as[List[Json]], Right(Nil), "secret 题零选项（值永不在载荷）")
  }

  test("SEC2: secret=true 而其余缺省 ⇒ 只落 secret 键（缺省键不出现）") {
    val items = List(AskItem("key?", Nil, secret = true))
    val item0 = frame(items).hcursor.downField("items").as[List[Json]].toOption.get.head
    assertEquals(
      item0.hcursor.keys.map(_.toSet).getOrElse(Set.empty),
      Set("question", "options", "allowOther", "secret")
    )
  }

  test("SEC3(零回归): secret=false（显式或缺省）⇒ 载荷逐字节等于既有形态") {
    val items = List(AskItem("Continue?", List(AskOption("yes"), AskOption("no")), secret = false))
    val expected =
      """{"type":"askUser","sessionId":"root-1","agentName":"Backend","sourceAgent":"Backend",""" +
        """"sourceSession":"team-abc","items":[{"question":"Continue?","options":[{"label":"yes"},{"label":"no"}],""" +
        """"allowOther":true}]}"""
    assertEquals(frame(items).noSpaces, expected)
  }

  // ---- 归属命名批（作者令 2026-11-05 10:07）：ownerName 归属字段契约 ----

  test("ON1: ownerName 在场 ⇒ 顶层落键（delegate 发起的 ask，段①=批次短名）") {
    val items = List(AskItem("合并窗口到了吗？", List(AskOption("等"), AskOption("现在合"))))
    val json = AgentActor.buildAskUserJson(
      Some("root-1"),
      "general",
      items,
      Some("general"),
      Some("delegate-general-1a2b3c4d"),
      project = None,
      nodeName = None,
      ownerName = Some("ask-card-naming")
    )
    assertEquals(json.hcursor.downField("ownerName").as[String], Right("ask-card-naming"))
    assertEquals(json.hcursor.downField("project").focus, None)
    assertEquals(json.hcursor.downField("nodeName").focus, None)
  }

  test("ON2: 节点提问 ⇒ ownerName + nodeName 同帧（卡片渲染 delegate name · node name）") {
    val items = List(AskItem("两段式归属按哪种折行？", List(AskOption("A"), AskOption("B"))))
    val json = AgentActor.buildAskUserJson(
      Some("root-1"),
      "general",
      items,
      Some("general"),
      Some("node-9f8e7d6c"),
      project = Some("stand"),
      nodeName = Some("改归属两段"),
      ownerName = Some("ask-card-naming")
    )
    assertEquals(json.hcursor.downField("ownerName").as[String], Right("ask-card-naming"))
    assertEquals(json.hcursor.downField("nodeName").as[String], Right("改归属两段"))
    assertEquals(json.hcursor.downField("project").as[String], Right("stand"))
  }

  test("ON3(零回归): ownerName 缺席/空串 ⇒ 不落键，载荷逐字节等于既有形态") {
    val items = List(AskItem("Continue?", List(AskOption("yes"), AskOption("no"))))
    val expected =
      """{"type":"askUser","sessionId":"root-1","agentName":"Backend","sourceAgent":"Backend",""" +
        """"sourceSession":"team-abc","items":[{"question":"Continue?","options":[{"label":"yes"},{"label":"no"}],""" +
        """"allowOther":true}]}"""
    assertEquals(frame(items).noSpaces, expected, "默认态（None）必须逐字节等于既有形态")
    assertEquals(
      AgentActor
        .buildAskUserJson(Some("root-1"), "Backend", items, Some("Backend"), Some("team-abc"), ownerName = Some("   "))
        .noSpaces,
      expected,
      "空白串视同缺席（前端不渲染空段）"
    )
  }

  // ---- 归属命名批：ownerName 的解析单点（AgentActor.askOwnerNameOf）----

  /** 假注册表：`delegate-*` 会话才有名字（与 DelegateRegistry.ownerNameOf 同判据面）。 */
  private val fakeRegistry: Map[String, String] = Map(
    "delegate-general-1a2b3c4d" -> "ask-card-naming"
  )

  private def fakeLookup(sid: Option[String]): Option[String] =
    sid.flatMap(fakeRegistry.get)

  test("ON4: delegate 会话本人提问 ⇒ 取自己的批次短名") {
    assertEquals(
      AgentActor.askOwnerNameOf(Some("delegate-general-1a2b3c4d"), "delegate-general-1a2b3c4d", fakeLookup),
      Some("ask-card-naming")
    )
  }

  test("ON5: 节点会话提问 ⇒ 取委托它的 delegate（rootSessionId 自根）") {
    // node-* 会话自身不注册 delegate 名；rootSessionId = 委托它的 delegate
    assertEquals(
      AgentActor.askOwnerNameOf(Some("node-9f8e7d6c"), "delegate-general-1a2b3c4d", fakeLookup),
      Some("ask-card-naming")
    )
  }

  test("ON6: Nebula 自问 / 项目分发器 / 无名旧记录 ⇒ None（前端回落项目名）") {
    assertEquals(AgentActor.askOwnerNameOf(Some("root-1"), "root-1", fakeLookup), None)
    assertEquals(AgentActor.askOwnerNameOf(Some("dispatcher-4f3e2d1c"), "root-1", fakeLookup), None)
    assertEquals(AgentActor.askOwnerNameOf(None, "", fakeLookup), None, "空 rootSessionId 不查表、不落空键")
  }

  test("ON7: 名字含首尾空白/纯空白 ⇒ trim；纯空白视同无名（不落键）") {
    val padded: Option[String] => Option[String] = _ => Some("  ask-card-naming  ")
    assertEquals(AgentActor.askOwnerNameOf(Some("delegate-x"), "delegate-x", padded), Some("ask-card-naming"))
    val blank: Option[String] => Option[String] = _ => Some("   ")
    assertEquals(AgentActor.askOwnerNameOf(Some("delegate-x"), "delegate-x", blank), None)
  }

end AskUserBuildJsonSpec
