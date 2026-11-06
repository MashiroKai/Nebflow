package nebflow.core.msgtokens

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.nio.file.Files

/**
 * 消息身份牌「面向 agent 一律展开全文」批（msgcard-agent-expand，2026-11-05 作者裁定
 * 「修：发给 agent 就展开全文」）的判据回归：
 *
 * ①展开形态：抬头 `[引用 · 短名 · 来自 作者 · 时间 · 共 N 字]` + 正文逐字 + 结束标记
 * ②长引用：全文保留、字数前后一致、超长注记在场、**零截断**
 * ③解析失败显式：映射缺失 / 缺 `msg:` 前缀 / 序号宽度错 / 空核心 ⇒ `[引用未能解析：…]`
 *   （原串只允许出现在标记里），并且 malformedSeen / missingSeen 计数如实
 * ④作者侧不变：牌面语法（TokenRegex）与聊天转录原文不经过本解析器——引擎侧只服务
 *   派发腿；前后端 chip 面（web/js/chat/msgTokenChip.js）零改动（本文件只钉语法面）
 * ⑤机制关闭 = 全量原样透传（零行为变化）
 */
class MsgTokenAgentExpandSpec extends CatsEffectSuite:

  private def withRegistry[A](enabled: Boolean = true)(test: (MsgTokenRegistry, os.Path) => IO[A]): A =
    val tmp = os.Path(Files.createTempDirectory("nebflow-msgexpand-test"))
    try
      val sessions = tmp / "sessions"
      os.makeDir.all(sessions)
      val reg = new MsgTokenRegistry(sessions, () => enabled)
      test(reg, sessions).unsafeRunSync()
    finally os.remove.all(tmp)

  private val Sid = "abcdef01-2345-6789-abcd-ef0123456789"

  /** 展开块正文（首行抬头之后、尾行 `[引用结束` 之前）——「有没有被截」的逐字比对面。 */
  private def bodyOfBlock(block: String): String =
    val start = block.indexOf('\n')
    val end = block.lastIndexOf("\n[引用结束")
    if start < 0 || end < 0 || end < start then "" else block.substring(start + 1, end)

  test("① 展开形态：抬头带短名/作者/时间/字数，正文逐字，结束标记带核心与字数") {
    withRegistry() { (reg, _) =>
      val userText = "把这份月报总结一下，周五前给我"
      for
        m <- reg.mint(Some(Sid), userText)
        core = m.get.token.stripPrefix("{{msg:").stripSuffix("}}")
        out <- reg.resolveForAgent(s"前文【{{msg:$core|输入统一令}}】后文")
        _ = assert(out.startsWith("前文【[引用 · 输入统一令"), s"抬头须以短名开场：$out")
        _ = assert(out.contains("来自 作者"), "抬头须注明原作者（真人作者 = 作者）")
        _ = assert(out.contains(s"共 ${userText.length} 字"), "抬头须注明字数")
        _ = assert(out.contains("[引用结束 · " + core), "尾行须带裸核心（不带花括号，防二次消解）")
        _ = assertEquals(bodyOfBlock(out), userText, "抬头/尾注之间的正文 = 源消息逐字")
        _ = assert(out.contains(userText), "正文必须是源消息全文")
        _ = assert(out.endsWith("】后文"), s"牌外文本必须原样保留：$out")
        _ = assert(!out.contains("{{msg:"), "展开后不许残留牌面")
        // 时间戳形态（yyyy-MM-dd HH:mm）落地
        _ = assert(out.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}.*"), s"抬头须带时间戳：$out")
        // 无短名形态：抬头用「引用 · 消息 <core>」
        out2 <- reg.resolveForAgent(s"引 ${m.get.token}")
        _ = assert(out2.contains(s"[引用 · 消息 $core"), s"裸牌抬头：$out2")
      yield ()
    }
  }

  test("② 长引用：全文保留（前后字数一致）、超长注记在场、零静默截断") {
    withRegistry() { (reg, _) =>
      val longText = ("长引用的第%d段：这段文字必须逐字到达收信方，不许任何静默截断。" * 400).replace("%d", "1")
      val total = longText.length
      for
        _ <- IO(assert(total > MsgTokenRegistry.LongQuoteThreshold, s"夹具须超门槛：$total"))
        m <- reg.mint(Some(Sid), longText)
        out <- reg.resolveForAgent(s"请按这条执行：${m.get.token}")
        body = bodyOfBlock(out)
        _ = assertEquals(body.length, total, "正文长度必须与原文一致（零截断）")
        _ = assertEquals(body, longText, "正文必须与原文逐字一致")
        _ = assert(out.contains(s"超长引用 $total 字（全文保留，未截断）"), s"超长注记必须在场：${out.take(200)}")
        _ = assert(out.contains(s"共 $total 字"), "抬头的字数与尾行字数同源")
        // 非超长文本不得出现超长注记（噪声面）
        m2 <- reg.mint(Some(Sid), "短消息")
        out2 <- reg.resolveForAgent(m2.get.token)
        _ = assert(!out2.contains("超长引用"), s"短引用不该有超长注记：$out2")
      yield ()
    }
  }

  test("③ 解析失败显式（缺 msg: 前缀）：替换为 [引用未能解析：…]，原串不泄漏在标记之外") {
    withRegistry() { (reg, _) =>
      val core = s"${reg.shortCode(Sid)}-000101"
      val bad = s"{{$core}}"                       // 作者现场写法：少了 msg:
      for
        m <- reg.mint(Some(Sid), "真实消息")
        out <- reg.resolveForAgent(s"请看 $bad 这条")
        _ = assert(out.contains(s"[引用未能解析：$bad]"), s"必须出现显式标记：$out")
        _ = assert(out.contains("缺少 `msg:` 前缀"), s"诊断原因须可读：$out")
        _ = assert(out.contains("双花括号"), s"诊断须给出正确写法（不带可再解析花括号）：$out")
        _ = assert(!out.replace(s"[引用未能解析：$bad]", "").contains(bad), s"标记外不许残留原串：$out")
        _ = assertEquals(reg.malformedSeen, 1L)
        // 反证：同一核心写对了就能展开（证明差别只在写法，不在数据）
        good <- reg.resolveForAgent(s"请看 ${m.get.token} 这条")
        _ = assert(good.contains("真实消息"), s"正确写法必须展开：$good")
      yield ()
    }
  }

  test("③ 解析失败显式（id 不存在 / 跨实例不可达）：显式标记，绝不放过原串") {
    withRegistry() { (reg, _) =>
      val ghost = "{{msg:deadbeef-000001}}"
      for
        out <- reg.resolveForAgent(s"A $ghost B")
        _ = assert(out.contains(s"[引用未能解析：$ghost]"), s"映射缺失须显式：$out")
        _ = assert(out.contains("映射缺失"), s"原因须写清映射缺失：$out")
        _ = assert(out.startsWith("A [引用未能解析"), s"前后文原样：$out")
        _ = assertEquals(reg.missingSeen, 1L)
        // 另一实例的登记表（不同 sessionsDir）⇒ 本实例同样解不了 = 显式标记
        otherTmp = os.Path(Files.createTempDirectory("nebflow-msgexpand-other"))
        otherSessions = otherTmp / "sessions"
        _ = os.makeDir.all(otherSessions)
        other = new MsgTokenRegistry(otherSessions, () => true)
        mOther <- other.mint(Some(Sid), "另一实例里的消息")
        crossOut <- reg.resolveForAgent(s"跨实例：${mOther.get.token}")
        _ = assert(crossOut.contains("[引用未能解析："), s"跨实例牌必须显式标记：$crossOut")
        _ = assert(!crossOut.contains("另一实例里的消息"), "跨实例牌不得被外部数据命中")
        _ = os.remove.all(otherTmp)
      yield ()
    }
  }

  test("③ 其余残缺形态：序号宽度错 / 空核心 / 大写前缀 / 多余空格 全部显式标记") {
    withRegistry() { (reg, _) =>
      val core8 = s"${reg.shortCode(Sid)}-000001"
      val cases = List(
        s"{{msg:${reg.shortCode(Sid)}-001}}" -> "核心格式不符",     // 3 位序号
        s"{{MSG:$core8}}" -> "核心格式不符",                        // 大写前缀（正则严格小写）
        "{{msg:}}" -> "核心为空",                                   // 空核心
        s"{{ msg:$core8 }}" -> "核心格式不符"                        // 多余空格
      )
      for
        outs <- cases.map { case (raw, expect) =>
                  reg.resolveForAgent(s"x$raw y").map(out => (raw, expect, out))
                }.sequence
        _ = outs.foreach { case (raw, expect, out) =>
              assert(out.contains(s"[引用未能解析：$raw]"), s"须显式标记：$raw → $out")
              assert(out.contains(expect), s"诊断原因须命中「$expect」：$out")
              assert(!out.replace(s"[引用未能解析：$raw]", "").contains(raw), s"标记外不许残留：$out")
            }
        _ = assertEquals(reg.malformedSeen, cases.size.toLong)
      yield ()
    }
  }

  test("④ 作者侧语法面不回退：TokenRegex 仍认带名/裸牌面；lookup 仍可只读取原文") {
    withRegistry() { (reg, _) =>
      for
        m <- reg.mint(Some(Sid), "作者侧 chip 取数的原文")
        core = m.get.token.stripPrefix("{{msg:").stripSuffix("}}")
        _ = assert(MsgTokenRegistry.TokenRegex.matches(s"{{msg:$core}}"), "裸牌面语法不变")
        _ = assert(MsgTokenRegistry.TokenRegex.matches(s"{{msg:$core|输入统一令}}"), "带名牌面语法不变")
        _ = assert(MsgTokenRegistry.CoreRegex.matches(core), "裸核心语法不变")
        fetched <- reg.lookup(s"{{msg:$core|输入统一令}}")
        _ = assertEquals(fetched.map(_.text), Some("作者侧 chip 取数的原文"), "chip 权威取数腿不变")
      yield ()
    }
  }

  test("⑤ 机制关闭：完整原文块不出现、显式标记也不出现（原样透传，零行为变化）") {
    withRegistry(enabled = false) { (reg, _) =>
      val core = s"{{msg:${reg.shortCode(Sid)}-000001}}"
      val bad = s"{{${reg.shortCode(Sid)}-000101}}"
      for
        out <- reg.resolveForAgent(s"包裹 $core / $bad 文本")
        _ = assertEquals(out, s"包裹 $core / $bad 文本")
        _ = assertEquals(reg.malformedSeen, 0L)
        _ = assertEquals(reg.missingSeen, 0L)
      yield ()
    }
  }

  test("多腿复消解幂等：同一文本过两遍解析器（Mail node: 腿 = MailTool + NodeDelivery 两次）结果不变") {
    withRegistry() { (reg, _) =>
      val core = s"${reg.shortCode(Sid)}-000101"
      val bad = s"{{$core}}"                                   // 缺 msg: 前缀
      val ghost = "{{msg:deadbeef-000001}}"                    // 映射缺失
      for
        m <- reg.mint(Some(Sid), "要被引用的原文")
        once <- reg.resolveForAgent(s"A ${m.get.token} B $bad C $ghost D")
        twice <- reg.resolveForAgent(once)
        _ = assertEquals(twice, once, "第二次消解必须零变化（标记不叠层、原文块不重包）")
        _ = assertEquals(once.split("\\[引用未能解析：").length - 1, 2, "两个坏牌 = 两个标记，恰好")
        _ = assert(once.contains("要被引用的原文"))
      yield ()
    }
  }

  test("多牌同文：逐枚展开、顺序与牌外文本稳定；重复引用同牌各自展开") {
    withRegistry() { (reg, _) =>
      for
        a <- reg.mint(Some(Sid), "第一条原文")
        b <- reg.mint(Some(Sid), "第二条原文")
        text = s"[1]${a.get.token} [2]${b.get.token} [3]${a.get.token}"
        out <- reg.resolveForAgent(text)
        _ = assertEquals(out.split("第一条原文").length - 1, 2, "同牌引用两次应展开两次")
        _ = assertEquals(out.split("第二条原文").length - 1, 1)
        _ = assert(out.indexOf("第一条原文") < out.indexOf("第二条原文"), "展开顺序随写入顺序")
        _ = assert(out.contains("[1]") && out.contains("[2]") && out.contains("[3]"), "牌外文本原样")
      yield ()
    }
  }
