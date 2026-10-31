package nebflow.core.msgtokens

import cats.effect.IO
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
