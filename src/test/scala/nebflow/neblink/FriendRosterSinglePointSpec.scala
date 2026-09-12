package nebflow.neblink

import munit.FunSuite

/**
 * 好友面「候选文案 / 解析单点」只读哨兵 spec（2026-09-12 好友消息改造批 ⑦，
 * 承接 ⑩-6 推荐 + ⑦-D7 并存口径；仿 ⑤ 的 `scripts/check-ime-guard.mjs` 思路）。
 *
 * 存在理由：好友面历史上出现过**两份独立实现**（`FriendMessageTool` 的
 * `displayName (username)` 与 `TransferFileTool` 的 `displayName [username …]`，
 * 方案 §7.2 M-8 登记的既成分叉）。⑩ 把好友消息支收归到
 * `nebflow.neblink.FriendRoster` 单点；⑦ 又在其上加了 L0 备注层与候选行备注渲染
 * ⇒ 若将来有人再复制一份候选文案，模型会学到**第二套词表**（同一个 query 两条
 * 失败路径给不同候选），而这是**代码评审看不出来、只有机制能拦**的漂移。
 *
 * 判据（本 spec 即机制）：扫描 `src/main/scala/nebflow/` 全树（**不含测试**）里两个
 * 候选文案字面量的**每一处出现文件**，必须 ∈ 允许集：
 *   - `nebflow/neblink/FriendRoster.scala`（唯一实现点），或
 *   - `nebflow/core/tools/TransferFileTool.scala`（**显式登记的偏差**，⑦-D7）。
 *
 * ⑦-D7 口径（允许清单的正当性，逐字落地）：`TransferFileTool.resolveFriend` /
 * `friendCandidates` **本批不对齐**——「暂不对齐，仅加偏差注释」（与 ⑩-D5 合并
 * 裁定）。所以它是**已登记的第二处偏差**，不是漏改；允许清单条目注释即登记位，
 * 其对齐属**另批**（跨工具改名耦合风险，`TransferFileTool.scala` 原注释自陈）。
 * 这意味着：**同一 query 在两工具下可能给出不同结论/不同候选文案**（本文件不阻止
 * 这个既成事实，只阻止**第三处**实现出现）。
 *
 * 本 spec **只读零生产改动**：不改任何 `src/main` 文件，也不改 `TransferFileTool`
 * 的行为（其行为零变更由 `TransferFileToolSpec` 逐字断言另行钉住：该 spec 仍断言
 * `Available friends: 林小满 [username lin@example.com]` 形态）。
 *
 * 已知同允许清单内的**设备面**字面量：`TransferFileTool.scala` 的
 * `"Available devices: …"` / `Device '$q' is ambiguous (…` —— 设备名册与好友名册是
 * **正交信任域**（方案 §4.5「与设备面区分口径」），⑦/⑩ 的收归范围**只覆盖好友面**
 * （设备支的实现属另批），故该文件整体进允许清单（文件级，不细分好友/设备行）。
 *
 * 红判据：任一**新文件**出现这两个字面量 ⇒ 红（新分叉点）；且两处允许文件里
 * 都必须仍有该字面量（防「字面量被抽成常量」让哨兵静默失效——哨兵本身的自检）。
 */
class FriendRosterSinglePointSpec extends FunSuite:

  /** 候选文案字面量（唯一实现点 = `FriendRoster`）。 */
  private val literals = List("Available friends: ", "is ambiguous (")

  /** 允许清单：相对 `src/main/scala/` 的路径（`/` 分隔，平台无关）。 */
  private val allowed: Set[String] = Set(
    // 唯一实现点（⑩ 收归 + ⑦ 的 L0/备注渲染都在这里）
    "nebflow/neblink/FriendRoster.scala",
    // 已登记偏差（⑦-D7：本批行为零变更，仅加注释；对齐属另批）——见类注释。
    "nebflow/core/tools/TransferFileTool.scala"
  )

  private val scalaRoot = os.pwd / "src" / "main" / "scala"

  /** `src/main/scala/nebflow/` 全树 的 .scala 文件（**不含测试**——测试里的
    * 字面量是断言而非实现，本哨兵只管生产实现面）。 */
  private lazy val sources: List[os.Path] =
    os.walk(scalaRoot / "nebflow")
      .filter(os.isFile)
      .filter(_.ext == "scala")
      .toList
      .sortBy(_.toString)

  private def relPath(p: os.Path): String =
    p.relativeTo(scalaRoot).toString.replace(java.io.File.separatorChar, '/')

  /** 去注释（块注释 + 行注释）：偏差注释里会**提到** `FriendRoster` 解释口径，
    * 只有**代码里**的引用才算「已对齐」。仅本 spec 的自检用，不参与生产判定。 */
  private def stripComments(src: String): String =
    val noBlocks = src.replaceAll("(?s)/\\*.*?\\*/", "")
    noBlocks.linesIterator.map(l => l.split("//", 2).head).mkString("\n")

  /** 命中字面量的文件 → 命中行号（1-based）。 */
  private def hits(literal: String): List[(String, Int)] =
    sources.flatMap { p =>
      os.read.lines(p).zipWithIndex.collect {
        case (line, idx) if line.contains(literal) => (relPath(p), idx + 1)
      }
    }

  test("探针自检：源文件集合非空且允许清单两文件都在扫描面内（哨兵不空转）") {
    assert(sources.nonEmpty, s"扫描面为空——src/main/scala 路径约定变了？(${scalaRoot})")
    val rels = sources.map(relPath).toSet
    allowed.foreach(a => assert(rels.contains(a), s"允许清单文件不在扫描面内（路径漂移会静默放过）：$a"))
  }

  literals.foreach { literal =>
    test(s"候选文案单点：「$literal」只准出现在 FriendRoster ∪ ⑦-D7 允许清单") {
      val found = hits(literal)
      assert(found.nonEmpty,
        s"「$literal」全仓零命中——字面量被抽成常量/改写会让本哨兵静默失效；请同步本 spec 的 literals")
      val files = found.map(_._1).distinct.toSet
      val offenders = files.diff(allowed)
      assert(offenders.isEmpty,
        s"新增候选文案实现点（第二个分叉！）：${offenders.toList.sorted.mkString(", ")} —— " +
          s"候选文案/解析必须收归 nebflow.neblink.FriendRoster（命中原样见下）\n" +
          found.map((f, l) => s"  $f:$l").mkString("\n"))
    }
  }

  test("⑦-D7 允许清单的正当性：TransferFileTool 仍是内联实现（偏差仍在，未偷偷对齐同一常量）") {
    // 该文件必须仍然自带一份 `friendCandidates`/`resolveFriend`（偏差登记前提），
    // 且**不含** `FriendRoster` 的代码引用 —— 若某天有人把它改成委托，本 spec 会红：
    // 那时必须回改允许清单并同步 ⑦-D7 的登记（不许「改了实现留旧豁免」）。
    // 去注释后判断：偏差注释里会**提到** FriendRoster（说明口径），提到不算引用。
    val p = scalaRoot / "nebflow" / "core" / "tools" / "TransferFileTool.scala"
    val src = os.read(p)
    val code = stripComments(src)
    assert(src.contains("def friendCandidates"), "TransferFileTool 的内联候选实现不在了（允许清单需同步修订）")
    assert(src.contains("def resolveFriend"), "TransferFileTool 的内联解析实现不在了（允许清单需同步修订）")
    assert(!code.contains("FriendRoster"),
      "TransferFileTool 的**代码**引用了 FriendRoster —— 说明已对齐，请同步 ⑦-D7 登记与允许清单")
    assert(src.contains("⑦-D7"), "偏差注释必须留在文件内（⑦-D7：只加偏差注释、行为零变更）")
  }

  test("唯一实现点仍在 FriendRoster（解析与候选文案都在，未搬空）") {    val src = os.read(scalaRoot / "nebflow" / "neblink" / "FriendRoster.scala")
    assert(src.contains("def resolve("), "FriendRoster.resolve 不在——解析单点被搬走")
    assert(src.contains("def candidateLine("), "FriendRoster.candidateLine 不在——候选文案单点被搬走")
    assert(src.contains("def availableHint("), "FriendRoster.availableHint 不在")
    assert(src.contains("remark"), "FriendRoster 必须含 ⑦ 的备注层（L0 匹配与候选行渲染）")
  }

end FriendRosterSinglePointSpec
