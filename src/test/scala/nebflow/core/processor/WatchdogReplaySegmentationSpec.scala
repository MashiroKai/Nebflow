package nebflow.core.processor

import cats.effect.IO
import munit.CatsEffectSuite

import WatchdogReplayCore.{Result, Row, Status, Verdict}

/**
 * **分段断言的判别力实证**（2026-09-12 `wdreplayfix` 批，R1′）——「只让测试变绿 = 不通过」，
 * 故本 spec 用**提交进仓的对照 fixture**（`src/test/resources/wd-replay/contrast-fixture.jsonl`）
 * 在同一套 [[WatchdogReplayCore]] 上取证三条读数：
 *
 *   1. **对照（注入违反 ⇒ 判红）**：fixture 里有一行**注入的违反**
 *      （`sessionId=node-fixture-fp2true`，note 删掉 `progress signal Ns ago` 读数、保留
 *      `window Ns` 出处标记 ⇒ 重放判 class-1 而日志登记 `false-positive`
 *      = `false-positive → true-stuck` 翻转）⇒ 新形态的**不变量判定必需判红**，
 *      且读数点名该行、条数 = 1；同时 fixture 里**正常的窗口后行**不得被判红（无假阳）。
 *   2. **反向对照（旧形态读数）**：把修复面反转回旧形态（[[WatchdogReplayCore.wholeCorpusLegacyVerdicts]]，
 *      「全语料一律断言旧判据保真」）在**同一 fixture** 上判红 **4 条**；而**新形态的窗口前段
 *      保真度断言在同一 fixture 上 0 条**——同一输入、两种形态读数不同 ⇒ 分段不是把断言改恒真。
 *   3. **窗口前段断言非空洞**：在 fixture 的**窗口前段行**上注入一处不一致（改正信号读数
 *      ⇒ 旧判据重放 class 与日志不符）⇒ 新形态的**窗口前段保真度判定判红 1 条**并点名该行
 *      ⇒ 该段断言仍有区分力（不是「窗口前段一律放行」）。
 *
 * fixture 出处与注入项逐条见同目录 `README.md`；本 spec **不依赖生产语料**（CI 也跑），
 * 故**不设** `munitIgnore`。**零生产行为改动**：只读 fixture + 纯函数判定。
 */
class WatchdogReplaySegmentationSpec extends CatsEffectSuite:

  private val fixtureResource = "wd-replay/contrast-fixture.jsonl"

  /**
   * fixture 路径：优先 `src/test/resources` 直读（sbt 测试 cwd = 项目根），否则回落 classpath
   * （资源会被拷进 `target/…/test-classes`）——两条路都只为读同一份提交进仓的文件。
   */
  private val fixturePath: os.Path =
    val direct = os.pwd / "src" / "test" / "resources" / "wd-replay" / "contrast-fixture.jsonl"
    if os.exists(direct) then direct
    else
      val url = Option(getClass.getClassLoader.getResource(fixtureResource))
        .getOrElse(throw new AssertionError("fixture 不在 resources 也不在 classpath: " + fixtureResource))
      os.Path(java.nio.file.Paths.get(url.toURI))

  private def fixtureRows(): Vector[Row] = WatchdogReplayCore.rowsFromFile(fixturePath)

  private def verdicts(r: Result, prefix: String): Vector[Verdict] =
    WatchdogReplayCore.segmentedVerdicts(r).filter(_.name.startsWith(prefix))

  // ── 1. 对照：注入的 false-positive → true-stuck 翻转必须判红 ──────────────────

  test("对照 fixture: 注入的 fp→true 翻转必被不变量列表判红（读数点名该行、条数 = 1），正常窗口后行不误红") {
    IO.blocking {
      val r = WatchdogReplayCore.run(fixtureRows())
      val inv = WatchdogReplayCore.invariantVerdicts(r)
      val flip = inv.filter(_.name.contains("翻转"))
      val leak = inv.filter(_.name.contains("violations"))
      val post = WatchdogReplayCore.fidelityVerdicts(r).filter(_.name.startsWith("窗口后段"))
      val pre = verdicts(r, "窗口前段")

      println(
        "[对照 fixture] 窗口起点=" + r.window.start.getOrElse(-1L) +
          " 前段(行/断言)=" + r.pre.rows + "/" + r.pre.asserted +
          " 后段(行/断言)=" + r.post.rows + "/" + r.post.asserted +
          " | 不变量: 翻转 " + r.fpToTrue.size + " / violations " + r.leakedTrueStuck.size +
          " | 前段旧判据不一致 " + r.pre.legacyMismatch.size +
          " | 后段新判据不一致 " + r.post.newMismatch.size +
          " | 旧形态(全语料)读数 " + r.allLegacyMismatch.size
      )
      r.fpToTrue.foreach(s => println("[对照 fixture] 命中翻转列表: " + s))
      r.post.newMismatch.foreach(s => println("[对照 fixture] 命中后段新判据不一致列表: " + s))

      assertEquals(flip.map(_.status), Vector(Status.Fail), "注入的 fp→true 翻转必须让不变量判定判红")
      assertEquals(r.fpToTrue, Vector("node-fixture-fp2true@1789217002500"), "翻转列表必须精确点名注入行（条数 = 1，无其它）")
      assert(
        flip.head.detail.contains("1 条") && flip.head.detail.contains("node-fixture-fp2true"),
        "判红读数必须给条数与命中会话，实得: " + flip.head.detail
      )
      assertEquals(leak.map(_.status), Vector(Status.Ok), "注入的翻转不是「放走真卡死」⇒ 该列表不得非空")
      assertEquals(post.map(_.status), Vector(Status.Fail), "注入行同时使「窗口后段: 新判据重放复现日志 class」判红（该行日志 class 与其读数矛盾）")
      assertEquals(r.post.newMismatch.size, 1, "窗口后段只应有注入的 1 条不一致（正常行零假阳）")
      assertEquals(pre.map(_.status), Vector(Status.Ok, Status.Ok, Status.Ok), "窗口前段（旧构建出处行）不受注入影响 ⇒ 三段判定全绿")
    }
  }

  // ── 2. 反向对照：旧形态在同一 fixture 上判红 4 条 / 新形态前段 0 条 ─────────────

  test("反向对照: 旧形态（全语料一律断言旧判据保真）在同一 fixture 上判红 4 条，而新形态窗口前段 = 0 条") {
    IO.blocking {
      val r = WatchdogReplayCore.run(fixtureRows())
      val oldForm = WatchdogReplayCore.wholeCorpusLegacyVerdicts(r)
      val newPre = verdicts(r, "窗口前段").filter(_.name.contains("复现日志 class"))

      println("[反向对照] 旧形态（全语料旧判据保真）: " + oldForm.head.status + " | " + oldForm.head.detail)
      println("[反向对照] 新形态（窗口前段旧判据保真）: " + newPre.head.status + " | " + newPre.head.detail)
      println(
        "[反向对照] 新形态（窗口后段新判据保真）: " +
          WatchdogReplayCore.fidelityVerdicts(r).filter(_.name.startsWith("窗口后段")).head.detail
      )

      assertEquals(oldForm.map(_.status), Vector(Status.Fail), "旧形态在含窗口后行的语料上必然判红（这正是 R1′：落地即红）")
      assertEquals(r.allLegacyMismatch.size, 4, "旧形态读数 = 4 条（第 i 刀 2 + 第 ii 刀 1 + 注入 1）")
      assertEquals(newPre.map(_.status), Vector(Status.Ok), "同一 fixture 上新形态的窗口前段保真度 = 绿（分段把「修复构建写的行」移出该断言）")
      assertEquals(r.pre.legacyMismatch.size, 0, "窗口前段旧判据不一致 0 条")
      assertEquals(r.pre.asserted, 6, "窗口前段参与断言 6 行（另 1 行出处不明、只计数）")
      assertEquals(r.post.asserted, 7, "窗口后段参与断言 7 行")
    }
  }

  // ── 3. 窗口前段断言非空洞：在前段行上注入不一致 ⇒ 判红 1 条并点名 ──────────────

  test("窗口前段断言非空洞: 在窗口前段行上注入正信号不一致 ⇒ 该段判红 1 条（点名行），后段/不变量不受影响") {
    IO.blocking {
      val rows = fixtureRows()
      val targetTs = 1789142412425L // node-8d8e7f73（窗口前段，旧文案，日志 false-positive）
      val mutated = rows.map { r =>
        if r.ts == targetTs then r.copy(note = r.note.replace("progress signal 24s ago", "progress signal 300s ago"))
        else r
      }
      assert(mutated != rows, "变异必须真的落在 fixture 行上（否则本用例无意义）")
      val r = WatchdogReplayCore.run(mutated)
      val pre = verdicts(r, "窗口前段").filter(_.name.contains("复现日志 class"))
      val post = WatchdogReplayCore.fidelityVerdicts(r).filter(_.name.startsWith("窗口后段"))

      println(
        "[前段注入] 窗口前段旧判据不一致: " + r.pre.legacyMismatch.size +
          " 条 | 后段新判据不一致 " + r.post.newMismatch.size +
          " 条 | 不变量翻转 " + r.fpToTrue.size + " 条"
      )
      r.pre.legacyMismatch.foreach(s => println("[前段注入] " + s))

      assertEquals(pre.map(_.status), Vector(Status.Fail), "窗口前段保真度断言必须判红")
      assertEquals(r.pre.legacyMismatch.size, 1, "只注入 1 处 ⇒ 恰好 1 条")
      assert(r.pre.legacyMismatch.head.contains("node-8d8e7f73"), "读数必须点名被注入的行")
      assertEquals(r.post.newMismatch.size, 1, "注入只作用于窗口前段 ⇒ 后段不一致仍只有 fixture 自带的那 1 条")
      assertEquals(r.fpToTrue.size, 1, "不变量读数不受前段注入影响（仍只有 fixture 自带的注入项）")
      assertEquals(post.map(_.status), Vector(Status.Fail), "后段断言读数不受前段注入影响")
    }
  }

  /**
   * 备查：fixture 出处不明的行（两构建文案逐字相同）只计数、不参与断言——该行为在
   * fixture 上同样成立（防止有人日后把 Unknown 行塞进断言行）。
   */
  test("出处不明的行只计数不断言（fixture 上 1 行：无工具相位分支）") {
    IO.blocking {
      val r = WatchdogReplayCore.run(fixtureRows())
      assertEquals(r.unknownRows.size, 1, "fixture 内 1 行出处不明（r2-stuck-processing 的无工具相位行）")
      assertEquals(r.pre.excluded, 1, "该行落在窗口前段、只计数")
      assertEquals(r.anomalies.size, 0, "fixture 无自相矛盾行")
    }
  }
end WatchdogReplaySegmentationSpec
