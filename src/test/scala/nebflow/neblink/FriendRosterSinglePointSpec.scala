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
 *   - `nebflow/neblink/FriendRoster.scala`（好友面唯一实现点），或
 *   - `nebflow/core/tools/FriendMessageTool.scala`（**设备面字面量**——2026-09-14
 *     #145 批把退役的 `TransferFileTool` 设备解析原样迁入此处；设备名册与好友名册
 *     是**正交信任域**，⑦/⑩ 的收归范围只覆盖好友面。好友面解析本工具**零实现**，
 *     只委托 `FriendRoster.resolve`——由本 spec 的「委托仍在」钉住）。
 *
 * 历史：`TransferFileTool.scala` 曾是 ⑦-D7 显式登记的第二处偏差（文件级豁免）；
 * 该工具已随 #145 退役（2026-09-14），豁免随之撤销——文件删除后若仍留在允许清单，
 * 「探针自检」会立即红（允许文件不在扫描面内）。
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
    // 设备面字面量（#145 2026-09-14：退役的 TransferFileTool 设备解析原样迁入；
    // 正交信任域，好友面零实现——委托关系由下方「委托仍在」测试钉住）
    "nebflow/core/tools/FriendMessageTool.scala"
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

  test("#145 后的好友面单点：FriendMessageTool 的好友解析仍委托 FriendRoster（零第二实现）") {
    // TransferFileTool 退役后，其设备面解析迁入 FriendMessageTool（允许清单内）；
    // 但该工具的**好友面**必须仍然零实现——只委托 FriendRoster.resolve。去注释后
    // 判定：解释口径注释里会提到 FriendRoster，只有**代码**引用才算委托。
    val p = scalaRoot / "nebflow" / "core" / "tools" / "FriendMessageTool.scala"
    val src = os.read(p)
    val code = stripComments(src)
    assert(code.contains("FriendRoster.resolve"),
      "FriendMessageTool 不再委托 FriendRoster.resolve —— 好友面出现第二实现，请回改实现并同步本登记")
    assert(!code.contains("Available friends: "),
      "FriendMessageTool 自带了一份好友候选文案 —— 好友面单点被打破（第二分叉），禁；候选文案只准在 FriendRoster")
  }

  test("唯一实现点仍在 FriendRoster（解析与候选文案都在，未搬空）") {    val src = os.read(scalaRoot / "nebflow" / "neblink" / "FriendRoster.scala")
    assert(src.contains("def resolve("), "FriendRoster.resolve 不在——解析单点被搬走")
    assert(src.contains("def candidateLine("), "FriendRoster.candidateLine 不在——候选文案单点被搬走")
    assert(src.contains("def availableHint("), "FriendRoster.availableHint 不在")
    assert(src.contains("remark"), "FriendRoster 必须含 ⑦ 的备注层（L0 匹配与候选行渲染）")
  }

end FriendRosterSinglePointSpec
