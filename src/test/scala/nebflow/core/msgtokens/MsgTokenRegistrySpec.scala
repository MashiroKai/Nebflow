package nebflow.core.msgtokens

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.nio.file.Files

/**
 * 消息身份牌机制（msgtokens 批，2026-10-31 作者四项拍板）回归：
 * ①牌格式 {{msg:sid8-seq6}}、每会话序号单调、跨会话短码不同
 * ②铸牌→reminder 反查→派发引牌→接收方展开全链
 * ③压缩后映射存活（= 会话旁挂 JSONL，重启重载即解）
 * ④映射缺失 = 显式报错行注入（不静默错引、不回退路径引用）
 * ⑤msgTokens.enabled 默认开 / 关 = 铸牌 no-op + 消解原样透传
 * ⑥启动对账：内存索引重建 + nextSeq 续走 + 已删会话映射清除
 */
class MsgTokenRegistrySpec extends CatsEffectSuite:

  private def withRegistry[A](enabled: Boolean = true)(test: (MsgTokenRegistry, os.Path) => IO[A]): A =
    val tmp = os.Path(Files.createTempDirectory("nebflow-msgtokens-test"))
    try
      val sessions = tmp / "sessions"
      os.makeDir.all(sessions)
      val reg = new MsgTokenRegistry(sessions, () => enabled)
      test(reg, sessions).unsafeRunSync()
    finally os.remove.all(tmp)

  private def mkSessionFile(sessions: os.Path, sid: String): Unit =
    os.write(sessions / s"$sid.json", "[]", createFolders = true)

  private val Sid1 = "11111111-2222-3333-4444-555555555555"
  private val Sid2 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

  test("牌格式 {{msg:sid8-seq6}}：sid8=sessionId SHA-256 前 8 位 hex，seq 六位数零填充单调") {
    withRegistry() { (reg, _) =>
      for
        m1 <- reg.mint(Some(Sid1), "第一条用户消息")
        m2 <- reg.mint(Some(Sid1), "第二条用户消息")
        _ = assert(m1.isDefined, "首铸应有牌")
        _ = assert(m2.isDefined, "次铸应有牌")
        t1 = m1.get.token
        t2 = m2.get.token
        _ = assertEquals(t1, s"{{msg:${reg.shortCode(Sid1)}-000001}}")
        _ = assertEquals(t2, s"{{msg:${reg.shortCode(Sid1)}-000002}}")
        _ = assertNotEquals(reg.shortCode(Sid1), reg.shortCode(Sid2))
        // 牌面与正则自洽
        _ = assert(MsgTokenRegistry.TokenRegex.findFirstIn(t1).isDefined)
      yield ()
    }
  }

  test("全链：铸牌 → reminder 反查含牌 → 派发文本引牌 → 消解展开为全文") {
    withRegistry() { (reg, _) =>
      val userText = "把这份月报总结一下，周五前给我"
      for
        m <- reg.mint(Some(Sid1), userText)
        token = m.get.token
        // reminder 反查（system-reminder 管线面）：按本条消息文本命中其牌
        reminder <- reg.reminderFor(Sid1, userText)
        _ = assert(reminder.isDefined, "铸牌后 reminder 应命中")
        _ = assertEquals(reminder.get.category, MsgTokenRegistry.ReminderCategory)
        _ = assert(reminder.get.content.contains(token), "reminder 应宣告牌面")
        // 派发引牌 → 接收方展开（MailTool/DelegateTool/NodeDelivery/NodeStarter 共用此消解）
        dispatch = s"请处理这个请求：$token —— 优先级高"
        expanded <- reg.resolve(dispatch)
        _ = assert(expanded.contains(userText), "牌应展开为消息全文")
        _ = assert(!expanded.contains(token), "展开后不应残留牌面")
        // 未铸牌的文本零注入
        none <- reg.reminderFor(Sid1, "一条没有铸过牌的旧消息")
        _ = assert(none.isEmpty)
      yield ()
    }
  }

  test("压缩后映射存活：重开注册表（重启/压缩后唯一权威路径）引牌仍展开") {
    withRegistry() { (reg, sessions) =>
      val userText = "压缩窗口之前的用户消息"
      for
        m <- reg.mint(Some(Sid1), userText)
        token = m.get.token
        // 模拟压缩 + 重启：消息数组被覆写、注册表进程内态丢弃，JSONL 旁挂不动
        _ = os.write.over(sessions / s"$Sid1.json", """[{"role":"user","content":"<context-compact …>"}]""")
        reg2 = new MsgTokenRegistry(sessions, () => true)
        expanded <- reg2.resolve(s"引用：$token")
        _ = assert(expanded.contains(userText), "压缩/重启后映射必须存活")
        // reminder 反查也按 JSONL 重建
        reminder <- reg2.reminderFor(Sid1, userText)
        _ = assert(reminder.isDefined)
        // 序号续走：不重号
        m3 <- reg2.mint(Some(Sid1), "压缩后的新消息")
        _ = assertEquals(m3.get.token, s"{{msg:${reg2.shortCode(Sid1)}-000002}}")
      yield ()
    }
  }

  test("映射缺失 = 显式报错行注入：不静默错引、原文其余部分原样") {
    withRegistry() { (reg, _) =>
      val badToken = s"{{msg:${reg.shortCode(Sid1)}-009999}}"
      val surrounding = "前缀文本保持不变"
      for
        out <- reg.resolve(s"$surrounding $badToken 后缀")
        _ = assert(out.contains(MsgTokenRegistry.missingErrorLine(badToken)), "必须注入显式报错行")
        _ = assert(out.contains(surrounding), "牌前文本原样")
        _ = assert(out.contains("后缀"), "牌后文本原样")
        // 未知会话短码（root 未见的牌）同样报错
        out2 <- reg.resolve("{{msg:deadbeef-000001}}")
        _ = assert(out2.contains("映射缺失"))
      yield ()
    }
  }

  test("enabled=false：铸牌 no-op、消解原样透传、reminder 无注入") {
    withRegistry(enabled = false) { (reg, _) =>
      for
        m <- reg.mint(Some(Sid1), "机制关闭时的消息")
        _ = assert(m.isEmpty)
        raw = s"{{msg:${reg.shortCode(Sid1)}-000001}}"
        out <- reg.resolve(s"包裹${raw}文本")
        _ = assertEquals(out, s"包裹${raw}文本")
        r <- reg.reminderFor(Sid1, "机制关闭时的消息")
        _ = assert(r.isEmpty)
      yield ()
    }
  }

  test("无会话号 / 空文本：静默无牌（禁铸条件，零副作用）") {
    withRegistry() { (reg, _) =>
      for
        a <- reg.mint(None, "有文本无会话")
        b <- reg.mint(Some(Sid1), "")
        _ = assert(a.isEmpty)
        _ = assert(b.isEmpty)
      yield ()
    }
  }

  test("启动对账：全量载入 + 已删会话映射清除 + nextSeq 续走") {
    withRegistry() { (reg, sessions) =>
      val keep = "keep-session-00000000000000000000000000"
      val gone = "gone-session-0000000000000000000000000"
      for
        _ <- reg.mint(Some(keep), "存活会话的消息一")
        _ <- reg.mint(Some(keep), "存活会话的消息二")
        _ <- reg.mint(Some(gone), "已删会话的消息")
        // 对账前：两个会话的映射文件都在
        _ = assert(os.exists(sessions / keep / "msg-tokens.jsonl"))
        _ = assert(os.exists(sessions / gone / "msg-tokens.jsonl"))
        // 只给 keep 建会话主体文件 ⇒ gone 成为孤儿
        _ = mkSessionFile(sessions, keep)
        report <- reg.reconcileAll()
        _ = assertEquals(report.sessionsScanned, 2)
        _ = assertEquals(report.prunedSessions, 1)
        _ = assert(!os.exists(sessions / gone / "msg-tokens.jsonl"), "孤儿映射文件应被清除")
        _ = assert(os.exists(sessions / keep / "msg-tokens.jsonl"), "在册会话映射保留")
        // 在册会话映射仍可解
        out <- reg.resolve(s"引用 {{msg:${reg.shortCode(keep)}-000001}}")
        _ = assert(out.contains("存活会话的消息一"))
        // gone 的牌映射缺失 ⇒ 报错行（不静默）
        outGone <- reg.resolve(s"引用 {{msg:${reg.shortCode(gone)}-000001}}")
        _ = assert(outGone.contains("映射缺失"))
        // 全新实例（纯靠对账装载）序号续走 = max(seq)+1
        reg2 = new MsgTokenRegistry(sessions, () => true)
        _ <- reg2.reconcileAll()
        m3 <- reg2.mint(Some(keep), "对账后的新消息")
        _ = assertEquals(m3.get.token, s"{{msg:${reg2.shortCode(keep)}-000003}}")
      yield ()
    }
  }

  test("幂等键防双铸：同一 clientMessageId 经入队+转发两路径只铸一次，重启后幂等面仍在") {
    withRegistry() { (reg, sessions) =>
      for
        // 路径一：busy 入队铸牌
        m1 <- reg.mint(Some(Sid1), "排队后转发的消息", Some("ws-msg-id-42"))
        _ = assert(m1.isDefined)
        // 路径二：turn 边界转发回 idle 腿重投同一条（同 clientMessageId）——不得双铸
        m2 <- reg.mint(Some(Sid1), "排队后转发的消息", Some("ws-msg-id-42"))
        _ = assert(m2.isEmpty, "同一幂等键不得重复铸牌")
        // 序号未被重复消耗
        m3 <- reg.mint(Some(Sid1), "下一条消息")
        _ = assertEquals(m3.get.token, s"{{msg:${reg.shortCode(Sid1)}-000002}}")
        // 重启：幂等键从 JSONL 重建，同键再投仍不铸
        _ = mkSessionFile(sessions, Sid1)
        reg2 = new MsgTokenRegistry(sessions, () => true)
        m4 <- reg2.mint(Some(Sid1), "排队后转发的消息", Some("ws-msg-id-42"))
        _ = assert(m4.isEmpty, "重启后幂等面必须存活")
        // 无幂等面（ImmediateInput 腿）不受限
        m5 <- reg2.mint(Some(Sid1), "immediate 消息")
        _ = assert(m5.isDefined)
      yield ()
    }
  }

  test("坏行容忍：JSONL 混入损坏行，装载跳过不炸") {
    withRegistry() { (reg, sessions) =>
      for
        m <- reg.mint(Some(Sid1), "正常消息")
        token = m.get.token
        _ = os.write.append(sessions / Sid1 / "msg-tokens.jsonl", "not-a-json-line\n{\"broken\":true}\n")
        reg2 = new MsgTokenRegistry(sessions, () => true)
        out <- reg2.resolve(s"引用 $token")
        _ = assert(out.contains("正常消息"), "坏行不得影响正常映射")
      yield ()
    }
  }

  // ── msgchip 批（2026-11-01 作者令「消息身份牌系统升级」）：带短名引用形态 ────

  test("带短名引用形态：{{msg:sid8-seq6|短名}} 展开为完整原文块，短名进抬头（不进正文）") {
    withRegistry() { (reg, _) =>
      val userText = "把这份月报总结一下，周五前给我"
      for
        m <- reg.mint(Some(Sid1), userText)
        core = m.get.token.stripPrefix("{{msg:").stripSuffix("}}")
        named = s"{{msg:$core|输入统一令}}"
        // 正则自洽：组3 = 短名
        _ = assert(MsgTokenRegistry.TokenRegex.findFirstMatchIn(named).isDefined)
        mm = MsgTokenRegistry.TokenRegex.findFirstMatchIn(named).get
        _ = assertEquals(mm.group(3), "输入统一令")
        // 消解（msgcard-agent-expand 批 2026-11-05 起）：抬头带短名，正文 = 原文逐字
        expanded <- reg.resolveForAgent(s"先看这条：$named —— 再办下一条")
        _ = assert(expanded.contains(userText), "带名形态应展开为消息全文")
        _ = assert(expanded.contains("[引用 · 输入统一令"), "短名进展开抬头（作者裁定「展开成完整原文」）")
        _ = assert(expanded.contains("[引用结束"), "展开块必须有结束标记")
        _ = assert(!expanded.contains("{{msg:"), "展开后不应残留牌面")
        // 裸形态混合在场的双牌全消解
        m2 <- reg.mint(Some(Sid1), "第二条消息")
        mixed = s"$named 与 ${m2.get.token}"
        out2 <- reg.resolve(mixed)
        _ = assert(out2.contains(userText) && out2.contains("第二条消息"))
        _ = assert(!out2.contains("{{msg:"), "两种形态都不残留牌面")
      yield ()
    }
  }

  test("带短名映射缺失：报错行引用写入原文（连同短名现形，便于定位笔误）") {
    withRegistry() { (reg, _) =>
      val bad = s"{{msg:${reg.shortCode(Sid1)}-009999|不存在的牌}}"
      for
        out <- reg.resolve(s"前缀 $bad 后缀")
        _ = assert(out.contains(MsgTokenRegistry.missingErrorLine(bad)), "报错行必须引用写入原文（含短名）")
        _ = assert(out.contains("前缀") && out.contains("后缀"))
      yield ()
    }
  }

  test("畸形但显然是牌 = 显式标记（不原样透传）；与牌无关的 {{…}} 文本逐字节原样") {
    withRegistry() { (reg, _) =>
      // 判据③（msgcard-agent-expand 批 2026-11-05）：写坏的牌一律替换成 [引用未能解析：…]
      val malformed = List(
        "{{msg:zzzzzzzz-000001|坏sid8}}",            // sid8 非 hex
        s"{{msg:${reg.shortCode(Sid1)}-0001|短序号}}",   // seq 非 6 位
        s"{{msg:${reg.shortCode(Sid1)}-000001|}}",       // 空短名
        s"{{msg:${reg.shortCode(Sid1)}-000001|${"长" * 41}}}", // 超 40 字
        s"{{msg:${reg.shortCode(Sid1)}-0001}}",          // 裸形态短序号
        "{{msg:abc-1|x}}"                                // 坏核裸牌
      )
      for
        outs <- malformed.map(s => reg.resolveForAgent(s"保持${s}原样")).sequence
        _ = outs.zip(malformed).foreach { case (out, raw) =>
              assert(
                out.contains(s"[引用未能解析：$raw]"),
                s"畸形牌必须被替换为显式标记（不许原样递送）: $raw → $out"
              )
              assert(!out.replace(s"[引用未能解析：$raw]", "").contains(raw), s"标记之外不许残留原串: $raw")
            }
        // 与牌无关的花括号文本（模板/代码片段）——零噪声逐字节原样
        plains = List("{{user.name}}", "{{ foo-bar }}", "{{msg|无核心}}", "{{msg.body}}", "{{#if x}}y{{/if}}")
        pouts <- plains.map(p => reg.resolveForAgent(s"代码：$p 结束")).sequence
        _ = pouts.zip(plains).foreach { case (out, raw) =>
              assertEquals(out, s"代码：$raw 结束", s"非牌文本必须原样: $raw")
            }
      yield ()
    }
  }

  test("畸形诊断计数：malformedSeen / missingSeen 如实计数（可观测面）") {
    withRegistry() { (reg, _) =>
      for
        _ <- reg.resolveForAgent("{{11111111-000101}}")            // 缺 msg: 前缀
        _ <- reg.resolveForAgent("{{msg:bad-1}}")                  // 坏核
        miss <- reg.resolveForAgent("{{msg:deadbeef-000001}}")     // 合法牌面但映射缺失
        _ = assertEquals(reg.malformedSeen, 2L)
        _ = assertEquals(reg.missingSeen, 1L)
        _ = assert(miss.contains("[引用未能解析：{{msg:deadbeef-000001}}]"))
        _ = assert(miss.contains("映射缺失"))
      yield ()
    }
  }

  test("enabled=false：带名形态原样透传（机制关 = 零行为变化）") {
    withRegistry(enabled = false) { (reg, _) =>
      val named = s"{{msg:${reg.shortCode(Sid1)}-000001|输入统一令}}"
      for
        out <- reg.resolve(s"包裹${named}文本")
        _ = assertEquals(out, s"包裹${named}文本")
      yield ()
    }
  }

  test("lookup：裸核心 / 完整牌面 / 带名牌面三形态同值命中；未铸/畸形 = None") {
    withRegistry() { (reg, _) =>
      for
        m <- reg.mint(Some(Sid1), "查询目标消息")
        token = m.get.token
        core = token.stripPrefix("{{msg:").stripSuffix("}}")
        byCore <- reg.lookup(core)
        byToken <- reg.lookup(token)
        byNamed <- reg.lookup(s"{{msg:$core|输入统一令}}")
        _ = assert(byCore.isDefined && byToken.isDefined && byNamed.isDefined)
        _ = assertEquals(byCore.get.text, "查询目标消息")
        _ = assertEquals(byToken.get.token, token)
        _ = assertEquals(byNamed.get.token, token)
        // 未铸序号 / 未知 sid8 / 畸形入参 = None
        miss <- reg.lookup(s"${reg.shortCode(Sid1)}-999999")
        unknown <- reg.lookup("deadbeef-000001")
        malformed <- reg.lookup("not-a-token")
        _ = assert(miss.isEmpty)
        _ = assert(unknown.isEmpty)
        _ = assert(malformed.isEmpty)
      yield ()
    }
  }
