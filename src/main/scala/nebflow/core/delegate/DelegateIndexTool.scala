package nebflow.core.delegate

import nebflow.shared.PathUtil

/**
 * DelegateIndexTool —— 阶段③ 索引层的**运维入口**（迁移 / 对账 / 撤销 / 基准）。
 *
 * 用法（隔离数据根；**绝不指向活动实例的数据根做写操作**）：
 * {{{
 *   sbt "runMain nebflow.core.delegate.DelegateIndexTool dedup   --root <dataRoot>"
 *   sbt "runMain nebflow.core.delegate.DelegateIndexTool migrate --root <dataRoot> \
 *        --expect-identities 888 --expect-archived 854 --expect-nonarchived 34 \
 *        --expect-ledgers 118 --expect-ledger-lines 0"
 *   sbt "runMain nebflow.core.delegate.DelegateIndexTool verify  --root <dataRoot>"
 *   sbt "runMain nebflow.core.delegate.DelegateIndexTool bench   --root <dataRoot>"
 *   sbt "runMain nebflow.core.delegate.DelegateIndexTool rollback --root <dataRoot>"
 * }}}
 *
 * 输出 = 人读行 + 一行 JSON（供证据文件逐字留档）。退出码：0 = 目标达成
 * （dedup 合法 / 迁移完成 / 对账 ok），1 = 目标未达成（硬违规 / 对账不自洽 / 库缺席），
 * 2 = 参数错误。
 */
object DelegateIndexTool:

  private val Commands = Set("dedup", "migrate", "verify", "bench", "rollback")

  private val usage: String =
    """usage: DelegateIndexTool <dedup|migrate|verify|bench|rollback> --root <dataRoot>
      |       [--expect-identities N --expect-archived N --expect-nonarchived N
      |        --expect-ledgers N --expect-ledger-lines N]""".stripMargin

  def main(args: Array[String]): Unit =
    parseArgs(args.toList) match
      case Left(err) =>
        println(s"args error: $err")
        println(usage)
        sys.exit(2)
      case Right((cmd, root, expect)) =>
        PathUtil.setDataRoot(root)
        println(s"[delegate-index] tool cmd=$cmd root=$root index=${DelegateIndex.dbPathOf(root)}")
        val code = cmd match
          case "dedup"    => runDedup(root)
          case "migrate"  => runMigrate(root, expect)
          case "verify"   => runVerify(root)
          case "bench"    => runBench(root)
          case "rollback" => runRollback(root)
          case _          => 2
        sys.exit(code)

  private def parseArgs(args: List[String]): Either[String, (String, os.Path, Map[String, Long])] =
    args match
      case cmd :: rest if Commands.contains(cmd) =>
        val pairs = rest.sliding(2).collect { case List(k, v) if k.startsWith("--") => k -> v }.toMap
        pairs.get("--root") match
          case None => Left("--root <dataRoot> is required")
          case Some(r) =>
            val expect = Map(
              "identities" -> pairs.get("--expect-identities"),
              "archived" -> pairs.get("--expect-archived"),
              "nonArchived" -> pairs.get("--expect-nonarchived"),
              "ledgers" -> pairs.get("--expect-ledgers"),
              "ledgerLines" -> pairs.get("--expect-ledger-lines")
            ).collect { case (k, Some(v)) => k -> v.toLong }
            Right((cmd, os.Path(r, os.pwd), expect))
      case other => Left(s"unknown command: ${other.headOption.getOrElse("(none)")}")

  // ── dedup（迁移前双区去重语义验证，只读）────────────────────────────────

  private def runDedup(root: os.Path): Int =
    val d = DelegateIndex.validateDedup(root)
    println(s"identity zone : files=${d.identityFiles} distinctIds=${d.distinctIds} " +
      s"archived=${d.archivedIdentities} nonArchived=${d.nonArchivedIdentities}")
    println(s"  idFieldMismatches=${d.idFieldMismatches.size} duplicateIds=${d.duplicateIds.size} " +
      s"undecodable=${d.undecodableIdentities.size}")
    println(s"ledger zone   : files=${d.ledgerFiles} lines=${d.ledgerLines} withIdentity=${d.ledgerIdsWithIdentity} " +
      s"orphans=${d.ledgerOrphanIds.size}")
    println(s"  roundLines=${d.roundLines} distinctRounds=${d.distinctRounds} " +
      s"droppedDuplicateLines=${d.droppedDuplicateLines} staleRoundLines=${d.staleRoundLines}")
    d.idFieldMismatches.take(10).foreach(m => println(s"  MISMATCH $m"))
    d.undecodableIdentities.take(10).foreach(m => println(s"  UNDECODABLE $m"))
    d.ledgerOrphanIds.take(10).foreach(m => println(s"  ORPHAN-LEDGER $m"))
    d.notes.foreach(n => println(s"  note: $n"))
    println("DEDUP_JSON " + json(
      List(
        "identityFiles" -> d.identityFiles.toString,
        "distinctIds" -> d.distinctIds.toString,
        "idFieldMismatches" -> d.idFieldMismatches.size.toString,
        "duplicateIds" -> d.duplicateIds.size.toString,
        "undecodable" -> d.undecodableIdentities.size.toString,
        "archived" -> d.archivedIdentities.toString,
        "nonArchived" -> d.nonArchivedIdentities.toString,
        "ledgerFiles" -> d.ledgerFiles.toString,
        "ledgerLines" -> d.ledgerLines.toString,
        "ledgerWithIdentity" -> d.ledgerIdsWithIdentity.toString,
        "ledgerOrphans" -> d.ledgerOrphanIds.size.toString,
        "roundLines" -> d.roundLines.toString,
        "distinctRounds" -> d.distinctRounds.toString,
        "droppedDuplicateLines" -> d.droppedDuplicateLines.toString,
        "staleRoundLines" -> d.staleRoundLines.toString,
        "ok" -> d.ok.toString
      )))
    if d.ok then 0 else 1

  // ── migrate（可逆迁移 + 漂移报数）───────────────────────────────────────

  private def runMigrate(root: os.Path, expect: Map[String, Long]): Int =
    DelegateIndex.migrate(root, expect) match
      case Left(err) =>
        println(s"MIGRATE_REFUSED $err")
        1
      case Right(r) =>
        println(s"imported: identities=${r.importedIdentities} ledgers=${r.importedLedgers} lines=${r.importedLines} " +
          s"in ${r.ms}ms")
        r.skipped.foreach(s => println(s"  skipped $s"))
        r.drift.foreach(d => println(s"  drift ${d.field} expected=${d.expected} actual=${d.actual} delta=${d.delta}"))
        println("MIGRATE_JSON " + json(
          List(
            "identities" -> r.importedIdentities.toString,
            "ledgers" -> r.importedLedgers.toString,
            "lines" -> r.importedLines.toString,
            "skipped" -> r.skipped.size.toString,
            "driftRows" -> r.drift.count(_.delta != 0L).toString,
            "ms" -> r.ms.toString
          ) ++ r.drift.map(d => s"drift_${d.field}" -> d.delta.toString)))
        0

  // ── verify（逐记录 + 计数 + 两窗分区对账）───────────────────────────────

  private def runVerify(root: os.Path): Int =
    DelegateIndex.verify(root) match
      case None =>
        println(s"VERIFY_UNAVAILABLE no index at ${DelegateIndex.dbPathOf(root)}")
        1
      case Some(v) =>
        println(s"identities: source=${v.sourceIdentities} index=${v.indexIdentities}")
        println(s"ledgers   : source=${v.sourceLedgers} index=${v.indexLedgers} " +
          s"lines source=${v.sourceLines} index=${v.indexLines}")
        println(s"windows   : active=${v.windowActive} history=${v.windowHistory} " +
          s"total=${v.windowTotal} partitionOk=${v.partitionOk}")
        println(s"per-record mismatches: identity=${v.recordMismatches.size} " +
          s"ledger=${v.roundMismatches.size} extraRows=${v.extraRows.size}")
        v.recordMismatches.take(10).foreach(m => println(s"  MISMATCH ${m.zone} ${m.id}: ${m.reason}"))
        v.roundMismatches.take(10).foreach(m => println(s"  MISMATCH ${m.zone} ${m.id}: ${m.reason}"))
        v.extraRows.take(10).foreach(m => println(s"  EXTRA-ROW $m"))
        println("VERIFY_JSON " + json(
          List(
            "sourceIdentities" -> v.sourceIdentities.toString,
            "indexIdentities" -> v.indexIdentities.toString,
            "sourceLedgers" -> v.sourceLedgers.toString,
            "indexLedgers" -> v.indexLedgers.toString,
            "sourceLines" -> v.sourceLines.toString,
            "indexLines" -> v.indexLines.toString,
            "identityMismatches" -> v.recordMismatches.size.toString,
            "ledgerMismatches" -> v.roundMismatches.size.toString,
            "extraRows" -> v.extraRows.size.toString,
            "windowActive" -> v.windowActive.toString,
            "windowHistory" -> v.windowHistory.toString,
            "windowTotal" -> v.windowTotal.toString,
            "partitionOk" -> v.partitionOk.toString,
            "countsMatch" -> v.countsMatch.toString,
            "ok" -> v.ok.toString
          )))
        if v.ok then 0 else 1

  // ── bench（迁移前/后：delegate 专属启动加载计数与耗时对照）───────────────

  /**
   * 三段落读数：① 基线全扫（阶段③ 前读面）；② 索引冷加载（模拟进程冷启：先
   * [[DelegateIndex.close]] 再读）；③ 索引热身加载（水位的稳态代价）。
   * **只度量 delegate 子树**（`<root>/delegates` + `<root>/delegate-index`）——作用域
   * 挂载、搜索对账、子代理父文件与本基准无关，既不纳入也不解释。
   */
  private def runBench(root: os.Path): Int =
    DelegateIndex.close()
    DelegateIndex.resetCounters()
    val before = DelegateIndex.benchSourceScan(root)
    println(f"① source scan (pre-stage-③ read face) : records=${before.records} parses=${before.sourceParses} " +
      f"stats=${before.sourceStats} ${before.ms}ms")

    DelegateIndex.migrate(root) match
      case Left(err) =>
        println(s"   migrate refused: $err")
        1
      case Right(r) =>
        println(s"   migrate: identities=${r.importedIdentities} ledgers=${r.importedLedgers} " +
          s"lines=${r.importedLines} drift=${r.drift.count(_.delta != 0L)} ${r.ms}ms")

        DelegateIndex.close()
        DelegateIndex.resetCounters()
        val cold = DelegateIndex.listRecords()
        val coldStats = DelegateIndex.lastLoadStats
        println(f"② index cold load (fresh process shape) : records=${coldStats.records} " +
          f"parses=${coldStats.sourceParses} stats=${coldStats.sourceStats} dbReads=${coldStats.dbReads} ${coldStats.ms}ms")

        DelegateIndex.resetCounters()
        val warm = DelegateIndex.listRecords()
        val warmStats = DelegateIndex.lastLoadStats
        println(f"③ index warm load (steady state)       : records=${warmStats.records} " +
          f"parses=${warmStats.sourceParses} stats=${warmStats.sourceStats} dbReads=${warmStats.dbReads} ${warmStats.ms}ms")

        val parity = cold.exists(c => warm.exists(w => c.map(_.id).sorted == w.map(_.id).sorted))
        println("   parity(cold/warm)=" + parity +
          " excludedStartupItems=(918 mounts, 9402 search reconcile, 212 subagent parent files)")
        println("BENCH_JSON " + json(
          List(
            "sourceScanRecords" -> before.records.toString,
            "sourceScanParses" -> before.sourceParses.toString,
            "sourceScanMs" -> before.ms.toString,
            "indexColdRecords" -> coldStats.records.toString,
            "indexColdParses" -> coldStats.sourceParses.toString,
            "indexColdStats" -> coldStats.sourceStats.toString,
            "indexColdMs" -> coldStats.ms.toString,
            "indexWarmRecords" -> warmStats.records.toString,
            "indexWarmParses" -> warmStats.sourceParses.toString,
            "indexWarmMs" -> warmStats.ms.toString,
            "parity" -> parity.toString
          )))
        if parity && coldStats.records == before.records then 0 else 1

  // ── rollback（撤索引回源码；源零字节变化）───────────────────────────────

  private def runRollback(root: os.Path): Int =
    val r = DelegateIndex.rollback(root)
    println(s"rollback: dropped=${r.dropped} db=${r.dbFile} backup=${r.backup.getOrElse("-")}")
    DelegateIndex.resetCounters()
    val after = DelegateIndex.benchSourceScan(root)
    println(s"post-rollback read face: source scan records=${after.records} parses=${after.sourceParses} ${after.ms}ms")
    println("ROLLBACK_JSON " + json(
      List("dropped" -> r.dropped.toString, "backup" -> r.backup.getOrElse(""), "records" -> after.records.toString)))
    0

  private def json(pairs: List[(String, String)]): String =
    "{" + pairs.map { case (k, v) => s""""$k":"$v"""" }.mkString(",") + "}"

end DelegateIndexTool
