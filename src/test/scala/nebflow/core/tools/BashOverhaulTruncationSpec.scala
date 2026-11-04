package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

/**
 * bash overhaul T5 红相（作者令 2026-11-04「组织优化 bash，用业界成熟做法完整
 * 实现，一步到位」）：**输出截断口径**——旧口径「保头 10MB、丢尾」必须改成
 * 「保头 + 保尾、中间省略，全文落盘」。
 *
 * 事实源：`docs/20261104_110500_our-bash-tool-defects.md` 缺陷 D6 ——
 * `shell.scala:1325-1345`（`MaxOutputSize = 10 * 1024 * 1024`，截断时**保留头部**
 * 再补 `[Output truncated due to size limit]`，**丢的是尾部**）；调研件 §1/Cline
 * 一格（中段截断，「构建/测试失败通常就在输出末尾」）与 §5.5-2 的修法
 * （头 8MB + 尾 2MB + 中间省略标注 + 全文落 BgTaskOutputStore 留存区）。
 *
 * 验收面（本 spec 的断言就是改后口径的机械定义）：
 *   1. 一条 >11MiB 的真实进程输出，**尾部标记必须在回执里**（旧口径丢尾 ⇒ 红）；
 *   2. 回执必须带新的双端截断标注 `[Output truncated: showing last`（旧标注
 *      `[Output truncated due to size limit]` 退役）；
 *   3. 标注里必须给出**全文落盘路径**，该文件存在且**含头部标记**（= 全文而非半段）；
 *   4. 回执总量必须远小于 11MiB（尾部 2MB 量级的预算，不是把整条流灌进上下文）。
 *
 * 命令形态（刻意压低 CPU：纯管道写，无计算）：头标记 → 11MiB 填充 → 尾标记。
 * `yes … | head -c` 是恒定速率写管道，落地时间取决于读线程（行数 ~1.6M）。
 */
class BashOverhaulTruncationSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 300.seconds

  private val StreamBytes = 11 * 1024 * 1024 + 4096 // 11MiB + 一点余量

  test("T5: >11MiB output keeps BOTH ends — tail marker + head/tail truncation marker + full-output spill file") {
    val uuid = java.util.UUID.randomUUID().toString.replace("-", "").take(12)
    val headMark = s"HEAD-MARK-$uuid"
    val tailMark = s"TAIL-MARK-$uuid"
    // 长填充行（~400B/行 ⇒ ~2.9 万行）：既压住 CPU/行处理开销，又稳稳越过 10MB 阈值。
    val filler = "filler-" + ("x" * 392)
    // `echo` 分隔：保证 TAIL-MARK 独占一行（head -c 可能停在半行）。
    val command =
      s"echo '$headMark'; yes '$filler' | head -c $StreamBytes; echo; echo '$tailMark'"
    val input = JsonObject(
      "command" -> command.asJson,
      "description" -> "big-output-truncation".asJson
    )
    BashTool.call(input, ToolContext(projectRoot = "/tmp")).map {
      case Left(e) => fail(s"the >11MiB command must not fail: ${e.message.take(300)}")
      case Right(out) =>
        val bytes = out.getBytes(StandardCharsets.UTF_8).length
        // 诊断面只取小片——回执本身可能上 MB，绝不整段进失败信息。
        val head = out.take(200).replace("\n", "\\n")
        val tail = out.takeRight(400).replace("\n", "\\n")

        // ① 尾部必须活着（旧口径在第 10MB 处截断头部，TAIL-MARK 整段蒸发）。
        assert(
          out.contains(tailMark),
          s"tail must survive truncation (the tail marker is GONE) | bytes=$bytes | head=$head | tail=$tail"
        )

        // ② 新标注（双端截断）：旧标注退役。
        val markerIdx = out.indexOf("[Output truncated: showing last")
        assert(
          markerIdx >= 0,
          s"receipt must carry the head+tail truncation marker '[Output truncated: showing last …' | bytes=$bytes | tail=$tail"
        )
        assert(
          !out.contains("[Output truncated due to size limit]"),
          "the retired head-only marker '[Output truncated due to size limit]' must be gone"
        )

        // ③ 标注里给出全文落盘路径；文件存在且含头部标记（全文 = 双端都在）。
        val spillRe = """full output[^:]*:\s*(\S+)""".r
        val spillPath = spillRe
          .findFirstMatchIn(out.drop(markerIdx))
          .map(_.group(1).stripSuffix("]").stripSuffix("."))
          .getOrElse(
            fail(s"the truncation marker must name the full-output file | marker=${out.drop(markerIdx).take(200)}")
          )
        val p = Paths.get(spillPath)
        assert(Files.exists(p), s"the named full-output file must exist: $spillPath")
        val full = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
        assert(full.contains(headMark), s"the spill file must be the FULL output — head marker missing in $spillPath")
        assert(full.contains(tailMark), s"the spill file must be the FULL output — tail marker missing in $spillPath")

        // ④ 回执量级：尾部预算（~2MB 档），绝不是整条 11MiB 流。
        assert(
          bytes < 3 * 1024 * 1024,
          s"receipt must be far smaller than the 11MiB stream (head+tail budget), got $bytes bytes"
        )
    }
  }

end BashOverhaulTruncationSpec
