package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.core.SessionStore
import nebflow.shared.UiMessage

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Ask 面板历史对话丢失批（2026-11-03，作者 15:26 报障「Ask 的历史对话记录会消失，
 * 主要是 AI 回复的内容，我提问的内容还存在」）。
 *
 * == 缺陷形状 ==
 * 文档阅读面 Ask 面板（`askDock.js`）重开/刷新后，历史里**用户提问行还在，AI 回复
 * 行全没**——不对称丢失。数据面（`<dataRoot>/sessions/ask-*.ui.json`）逐行完好
 * （本 spec ① 用真实 SessionStore 冷读钉住），丢的是**回放编码腿**。
 *
 * == 根因（代码判据） ==
 * `WsAskHandlers.encodeTranscript` 把 `.ui.json` 转录投影成 dock 气泡面时，ai 族只
 * 匹配 `UiMessage.Agent`（typeName `"agent"`）——而 ask 会话的 ai 行由
 * `WebSocketRoutes.makeRecordingWsSend` 的 `done`/`toolStart`/`roundComplete` 落成
 * `UiMessage.Ai`（typeName `"ai"`）。两个 case class 互不继承 ⇒ **AI 回复行在
 * `askOpen` 回放里恒被静默丢弃**，一条不漏；user 行照常下发 ⇒ 正是作者看到的
 * 「问还在、答没了」。
 *
 * 掩蔽面（为何 2026-11-01 之后才偶发）：前端 localStorage write-through
 * （`askDock.js` 文档会话持久批）把 ai 行存在本机作体验加速，且 `askState` 合并律
 * 「永不缩水」在本地 ≥ 快照时保留本地 ⇒ 同一浏览器同档期内看不出丢。本地账缺席/
 * 被淘汰（`SESS_MAX_SESSIONS=24` 全局上限 vs 本机 34 个 ask 绑定）/私密模式/换设备/
 * 重连后回放更长时，回放是唯一真源 ⇒ AI 回复整批消失。
 *
 * == 机械断言（改前红 / 改后绿） ==
 *   ① **落盘往返**：user 行 + ai 行（含多段 flush + 纯 thinking 空行）经
 *      `appendUiMessages` → flush → **磁盘字节解码**（冷读，不经缓存）两族俱在；
 *   ② **回放投射钉**（本批主断言）：`encodeTranscript(冷读解码行)` 必须含 ai 族，
 *      且文本 = 作答正文 ⇒ 前端 `askState` 真能拿到 AI 回复；
 *   ③ **live 同形**：一轮内被工具轮切开的 ai 段在 dock 面合成**一个**气泡
 *      （live 腿 tool 帧不进转录、textDelta 续同一 ai 泡 = 一轮一泡），user 行不并、
 *      纯 thinking 空行不出空泡；
 *   ④ **sys 族与顺序**：注入 user 行排 sys 且带 source，逐行顺序不变（回问/转交
 *      可见性面零回归）；
 *   ⑤ **旧行兼容**：磁盘上既存的瘦形态 ai 行（无 `terminal`、带 `thinking`、
 *      无 `attachments` 键）解码后照样进回放（宽松解码，不回溯清洗历史行）。
 *
 * 读数一律经**生产本体**：`WsAskHandlers.encodeTranscript`（askOpen 唯一投影点）+
 * 真实 `SessionStore` 落盘/读盘。
 */
class AskTranscriptReplaySpec extends CatsEffectSuite:

  private val Sid = "ask-1a2b3c4d"

  private def withStore(test: (SessionStore, os.Path) => IO[Unit]): Unit =
    val tmp = Files.createTempDirectory("nebflow-askreplay-test")
    val sessionsDir = os.Path(tmp.resolve("sessions"))
    val store = SessionStore(sessionsDir, os.Path(tmp.resolve("tasks")))
    try
      store.load.unsafeRunSync()
      test(store, sessionsDir).unsafeRunSync()
    finally
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists)

  private def uiFile(sessionsDir: os.Path, sid: String): os.Path = sessionsDir / s"$sid.ui.json"

  /** 冷读：直接解磁盘上的 `.ui.json` 字节（不经任何内存缓存）——重开面板的真形态。 */
  private def coldDecode(sessionsDir: os.Path, sid: String): List[UiMessage] =
    val p = uiFile(sessionsDir, sid)
    assert(os.exists(p), s"missing ui file on disk: $p")
    io.circe.parser.decode[List[UiMessage]](os.read(p)) match
      case Right(msgs)    => msgs
      case Left(decoding) => throw AssertionError(s"decode failed: ${decoding.getMessage}")

  private def roles(rows: List[Json]): List[String] =
    rows.map(_.hcursor.downField("role").as[String].getOrElse("?"))

  private def texts(rows: List[Json]): List[String] =
    rows.map(_.hcursor.downField("text").as[String].getOrElse(""))

  // ============================================================
  // ① 落盘往返（ask 会话的写者是 makeRecordingWsSend / SessionRecorder 同款行）
  // ============================================================
  test("① 落盘往返：user 行与 ai 行两族俱进 .ui.json（冷读，不经缓存）") {
    withStore { (store, dir) =>
      val rows = List(
        UiMessage.User("能不能用一句话总结一下？", Nil, timestamp = 1793678548660L),
        // 被工具轮切开的中间段（toolStart/roundComplete flush 形状：无 terminal）
        UiMessage.Ai("先看文件。", None, None, Some("思考中"), 1793678600000L),
        UiMessage.Tool(
          "Read(20261103_113000_x.md)",
          "146 lines",
          "1\tdoc body",
          false,
          """{"file_path":"/tmp/x.md"}"""
        ),
        // 轮终态行（done flush 形状：terminal=true + durationMs + model）
        UiMessage.Ai("一句话：这是一份影响面盘点。", Some(3100L), Some("m"), None, 1793678630000L, Some(true))
      )
      for
        _ <- rows.traverse_(r => store.appendUiMessages(Sid, List(r)))
        _ <- store.flushPendingUiWrites
        _ <- IO {
          val disk = coldDecode(dir, Sid)
          assertEquals(
            disk.map {
              case _: UiMessage.User => "user"
              case _: UiMessage.Ai   => "ai"
              case _: UiMessage.Tool => "tool"
              case other             => other.typeName
            },
            List("user", "ai", "tool", "ai"),
            "磁盘上的行族顺序"
          )
          assertEquals(disk.collect { case a: UiMessage.Ai => a.text }, List("先看文件。", "一句话：这是一份影响面盘点。"))
        }
      yield ()
    }
  }

  // ============================================================
  // ② 回放投射钉（本批主断言；改前红：ai 族恒被 encodeTranscript 丢弃）
  // ============================================================
  test("② askOpen 回放投射：AI 回复行必须随 user 行一并下发给 dock") {
    withStore { (store, dir) =>
      val rows = List(
        UiMessage.User("这个文档讲了一个什么事？", Nil, timestamp = 1793533290408L),
        UiMessage.Ai("先看文件。", None, None, None, 1793533300000L),
        UiMessage.Tool("Read(x.md)", "229 lines", "1\tbody", false, "{}"),
        UiMessage.Ai("这份文档讲的是进程级 recorder 的设计。", Some(2500L), Some("m"), None, 1793533323434L, Some(true))
      )
      for
        _ <- rows.traverse_(r => store.appendUiMessages(Sid, List(r)))
        _ <- store.flushPendingUiWrites
        // ↓ 生产腿逐字：WsAskHandlers.handleAskOpen 的 `sessionStore.getUiMessages(sid, 0, 200)`
        read <- store.getUiMessages(Sid, 0, 200).map(_._1)
        replay = WsAskHandlers.encodeTranscript(read)
        replayCold = WsAskHandlers.encodeTranscript(coldDecode(dir, Sid))
        _ <- IO {
          assertEquals(roles(replay), List("user", "ai"), s"回放行族（生产读腿）：${roles(replay)}")
          assertEquals(roles(replayCold), List("user", "ai"), s"回放行族（冷读腿）：${roles(replayCold)}")
          // 提问行与回复行都在，且回复正文逐字到达 dock
          assertEquals(texts(replay).head, "这个文档讲了一个什么事？")
          assert(
            texts(replay).last.contains("这份文档讲的是进程级 recorder 的设计。"),
            s"AI 回复正文必须进回放，实际：${texts(replay).last}"
          )
          assert(
            texts(replay).last.contains("先看文件。"),
            s"中间段同样不丢，实际：${texts(replay).last}"
          )
        }
      yield ()
    }
  }

  // ============================================================
  // ③ live 同形：一轮一泡（tool 帧不进 dock 转录 ⇒ textDelta 续同一 ai 泡）
  // ============================================================
  test("③ 一轮内的多个 ai 段合成一个气泡；user 行不并；纯 thinking 空行不出空泡") {
    withStore { (store, dir) =>
      val rows = List(
        UiMessage.User("问一", Nil, timestamp = 1L),
        UiMessage.Ai("段一\n", None, None, None, 2L),
        UiMessage.Tool("Read(a)", "", "", false, "{}"),
        // 纯 thinking 段（无正文）：live 腿不产空泡，回放同样不出
        UiMessage.Ai("", None, None, Some("只想不做"), 3L),
        UiMessage.Ai("段二", None, None, None, 4L, Some(true)),
        UiMessage.User("问二", Nil, timestamp = 5L),
        UiMessage.Ai("答二", Some(900L), Some("m"), None, 6L, Some(true))
      )
      for
        _ <- rows.traverse_(r => store.appendUiMessages(Sid, List(r)))
        _ <- store.flushPendingUiWrites
        replay = WsAskHandlers.encodeTranscript(coldDecode(dir, Sid))
        _ <- IO {
          assertEquals(roles(replay), List("user", "ai", "user", "ai"), "两轮 = 两问两答（同轮 ai 段已并泡）")
          // 原文直连 = live 腿 delta 拼接同形（无分隔符；段自带换行照常保留）
          assertEquals(texts(replay)(1), "段一\n段二", "同轮 ai 段合成一泡（正文直连）")
          assertEquals(texts(replay)(0), "问一")
          assertEquals(texts(replay)(2), "问二")
          assertEquals(texts(replay)(3), "答二")
        }
      yield ()
    }
  }

  // ============================================================
  // ④ sys 族与顺序（注入 user 行 = 主会话回问/转交待见性面）
  // ============================================================
  test("④ 注入 user 行 → sys 角色且带 source，全序不变") {
    withStore { (store, dir) =>
      val rows = List(
        UiMessage.User("问一", Nil, timestamp = 1L),
        UiMessage.Ai("答一", None, None, None, 2L, Some(true)),
        UiMessage.User("主会话回问：再说细一点", Nil, injected = true, timestamp = 3L, source = Some("main")),
        UiMessage.Ai("答二", None, None, None, 4L, Some(true))
      )
      for
        _ <- rows.traverse_(r => store.appendUiMessages(Sid, List(r)))
        _ <- store.flushPendingUiWrites
        replay = WsAskHandlers.encodeTranscript(coldDecode(dir, Sid))
        _ <- IO {
          assertEquals(roles(replay), List("user", "ai", "sys", "ai"))
          assertEquals(
            replay(2).hcursor.downField("source").as[String].toOption,
            Some("main")
          )
        }
      yield ()
    }
  }

  // ============================================================
  // ⑤ 旧行兼容：瘦形态 ai 行（无 terminal / 带 thinking / 无 attachments 键）照样进回放
  // ============================================================
  test("⑤ 磁盘既存瘦形态 ai 行（旧行字节形态）解码后照常进回放") {
    withStore { (store, dir) =>
      val legacy = """[
        |{"timestamp":1793533290408,"type":"user","text":"旧问题","attachments":[]},
        |{"type":"tool","label":"Read(x.md)","summary":"229 lines","content":"1\tbody","isError":false,"input":"{}"},
        |{"terminal":true,"timestamp":1793533323434,"thinking":"想一想","model":"glm-5.3-flash","durationMs":33026,"type":"ai","text":"旧答复"}
        |]""".stripMargin
      val p = uiFile(dir, Sid)
      for
        _ <- IO {
          // 直接写旧形态字节（不经编码器）——就是作者盘上既存行的样子
          if !os.exists(dir) then os.makeDir.all(dir)
          os.write.over(p, legacy)
        }
        // 生产读腿（缓存旁路：新 store 无该条目 ⇒ 冷读磁盘）
        read <- store.getUiMessages(Sid, 0, 200).map(_._1)
        _ <- IO {
          val replay = WsAskHandlers.encodeTranscript(read)
          assertEquals(roles(replay), List("user", "ai"), s"旧行回放行族：${roles(replay)}")
          assertEquals(texts(replay).last, "旧答复")
        }
      yield ()
    }
  }
end AskTranscriptReplaySpec
