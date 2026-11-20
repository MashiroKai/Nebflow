package nebflow.core.delegate

import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.PathUtil

/**
 * DelegateLivenessScan —— **只读**一次性扫描（legacy-delegate-settle 批 · 证据腿）。
 *
 * 用途：在一份**离线隔离**数据根上（生产数据的只读快照）回答三个数：
 *   1. `total`                     —— 盘上持久 delegate 记录总数；
 *   2. `preFixRunning`             —— **修前**判 running 的行数（旧行投影 = `live` 键缺席，
 *      前端 `rowFromRegistry` 兜底 running：无 taskStatus ∧ reportStatus 非终态 ∧
 *      restartInterruptedAt 缺席 ⇒ running）；
 *   3. `postFixRunningHeld`        —— **修后**真活数（[[DelegateLiveness.verdictOf]] 判 running
 *      = 本实例真正收养/持有的 Processing / WaitingForUser 行）。standalone 扫描没有本实例 agentRegistry
 *      ⇒ 传 `--held <ids.json>` 可注入一份在册快照（真实实例用）；默认空 ⇒ 0（真活地板）。
 *
 * 纪律：**零写盘、零网络、零凭据、零 6138**。仅读盘。
 * 闸：数据根必须在 /tmp（隔离快照）——绝不指向生产根。
 */
object DelegateLivenessScan:

  private def usage: String =
    "usage: DelegateLivenessScan <isolated-data-root under /tmp> [held-ids-json-path]"

  def main(args: Array[String]): Unit =
    require(args.length == 1 || args.length == 2, usage)
    val root = os.Path(args(0), os.pwd)
    val underTmp =
      root.startsWith(os.Path("/tmp")) || root.startsWith(os.Path("/private/tmp"))
    require(underTmp, s"refused: isolated /tmp root required (got '$root')")
    require(os.exists(root) && os.isDir(root), s"root must exist: $root")
    require(root.toString != (os.home / ".stand").toString, "refused: production root")

    PathUtil.setDataRoot(root)

    // 在册快照（`--held <ids.json>`，形 {"ids":[...]}）= 本实例 agentRegistry 里持引用且
    // Processing / WaitingForUser 的 delegate id 集。standalone 扫描没有运行中的实例 ⇒ 默认空集（真活地板 0）。
    // 判据与 DelegateLiveness 逐字同义：held id ∧ 未被打断旗覆盖（此处旗时点与活动时刻由
    // 运行实例自己在快照里先行施加——扫描只做**集合交**，不臆造 agent 细节）。
    val heldIds: Set[String] =
      args.lift(1) match
        case None => Set.empty
        case Some(p) =>
          io.circe.parser.parse(os.read(os.Path(p, os.pwd))) match
            case Left(e) => throw new IllegalArgumentException(s"held ids json unreadable: ${e.getMessage}")
            case Right(j) => j.hcursor.get[List[String]]("ids").toOption.getOrElse(Nil).toSet

    val records = DelegateRegistry.list()
    val readings = records.map(r => r.id -> DelegateVisibility.readOf(r.id)).toMap
    val active = records.filter(r => readings.get(r.id).exists(_.activeWindow))

    def preFixRunning(r: DelegateRegistry.Record): Boolean =
      val reported = r.reportStatus.getOrElse("")
      reported != "finish" && reported != "blocked" && r.restartInterruptedAt.isEmpty

    val preFix = active.filter(preFixRunning)
    // 修后真活 = 在册快照 ∩ 活动窗（未被旗覆盖的那部分由快照侧先行剔除——旗是持久字段，
    // 在册实例读到的就是同一份盘面事实）。
    val postFixHeld = active.count(r => heldIds.contains(r.id))
    val flagged = records.count(_.restartInterruptedAt.isDefined)
    val hiddenLegacy = LegacySettledManifest.loadedIds()

    val out = Json.obj(
      "root" -> root.toString.asJson,
      "total" -> records.size.asJson,
      "activeWindow" -> active.size.asJson,
      "preFixRunning" -> preFix.size.asJson,
      "postFixRunningHeld" -> postFixHeld.asJson,
      "restartInterruptedFlagged" -> flagged.asJson,
      "legacySettledManifestIds" -> hiddenLegacy.size.asJson,
      "heldSnapshotSize" -> heldIds.size.asJson,
      "preFixRunningIds" -> preFix.map(_.id).sorted.asJson
    )
    println(out.noSpaces)
  end main

end DelegateLivenessScan
