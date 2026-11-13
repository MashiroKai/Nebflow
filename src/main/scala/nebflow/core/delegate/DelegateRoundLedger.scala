package nebflow.core.delegate

import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * DelegateRoundLedger —— delegate 执行轮次的 append-only 台账
 * （`delegates/<id>.rounds.jsonl`，每轮一 JSON 行）。
 *
 * == 三层分离中的轮次层 ==
 * 持久身份（Registry Record）与执行轮次分离后，本件是轮次的唯一明细载体：
 *   - Record 只存**当前轮指针**（`roundNo` / `roundId`）——防单文件膨胀与整写竞态；
 *   - 轮次明细（起跑时刻、成因、差异基线快照、结算时刻、判词、已计提交、
 *     partial 注记）全部落本台账，**只追加不改写**——旧轮真值永久保留，后续轮
 *     成功不覆盖前轮失败。
 *
 * == 行形态 ==
 *   - `kind="round"`：轮次行。同一 `roundNo` 可有多行（起跑行 + 结算行）——读面
 *     取**每轮最后一行**为该轮现值（append-only 下「结算 = 追加一份带 settledAt 的
 *     全量行」，不回改起跑行）。
 *   - `kind="stale-round"`：旧轮次迟到事件的审计行（不触发投递、不重写归档、
 *     不重启清理链——只留痕）。
 *
 * == 旧数据兼容 ==
 * 本批之前的记录没有轮次台账（Record.roundNo 缺失 = 旧形态，读面按 round 1 隐式
 * 处理）；台账文件缺失 = Nil，一切读写照常。坏行跳过（fail-soft，绝不抛）。
 */
object DelegateRoundLedger:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.rounds")

  /** 轮次成因值域。 */
  val CauseSpawn = "spawn"
  val CauseMail = "mail"
  val CauseRevivalSameRound = "revival-same-round"

  /** 行类别：round（轮次行）| stale-round（旧轮次迟到事件审计行）。 */
  val KindRound = "round"
  val KindStaleRound = "stale-round"

  final case class RoundRow(
    kind: String,
    roundNo: Int,
    roundId: String,
    startedAt: Long,
    cause: String,
    baseHeadSha: Option[String] = None,
    baseDirty: Option[List[String]] = None,
    settledAt: Option[Long] = None,
    verdict: Option[String] = None,
    countedOids: Option[List[String]] = None,
    partial: Option[Boolean] = None,
    detail: Option[String] = None
  )

  object RoundRow:
    given Encoder[RoundRow] = Encoder.instance { r =>
      Json.obj(
        "kind" -> r.kind.asJson,
        "roundNo" -> r.roundNo.asJson,
        "roundId" -> r.roundId.asJson,
        "startedAt" -> r.startedAt.asJson,
        "cause" -> r.cause.asJson,
        "baseHeadSha" -> r.baseHeadSha.asJson,
        "baseDirty" -> r.baseDirty.asJson,
        "settledAt" -> r.settledAt.asJson,
        "verdict" -> r.verdict.asJson,
        "countedOids" -> r.countedOids.asJson,
        "partial" -> r.partial.asJson,
        "detail" -> r.detail.asJson
      )
    }
    given Decoder[RoundRow] = Decoder.instance { c =>
      for
        kind <- c.get[Option[String]]("kind")
        roundNo <- c.get[Int]("roundNo")
        roundId <- c.get[Option[String]]("roundId")
        startedAt <- c.get[Option[Long]]("startedAt")
        cause <- c.get[Option[String]]("cause")
        baseHeadSha <- c.get[Option[String]]("baseHeadSha")
        baseDirty <- c.get[Option[List[String]]]("baseDirty")
        settledAt <- c.get[Option[Long]]("settledAt")
        verdict <- c.get[Option[String]]("verdict")
        countedOids <- c.get[Option[List[String]]]("countedOids")
        partial <- c.get[Option[Boolean]]("partial")
        detail <- c.get[Option[String]]("detail")
      yield RoundRow(
        kind = kind.getOrElse(KindRound),
        roundNo,
        roundId = roundId.getOrElse(""),
        startedAt = startedAt.getOrElse(0L),
        cause = cause.getOrElse(""),
        baseHeadSha,
        baseDirty,
        settledAt,
        verdict,
        countedOids,
        partial,
        detail
      )
    }
  end RoundRow

  private def fileOf(id: String): os.Path =
    PathUtil.dataRoot / "delegates" / s"$id.rounds.jsonl"

  /** 全量行读（坏行跳过；文件缺失 = Nil）。 */
  def rowsOf(id: String): List[RoundRow] =
    try
      val p = fileOf(id)
      if !os.exists(p) then Nil
      else
        os.read.lines(p).flatMap(line => decode[RoundRow](line.trim).toOption).toList
    catch case _: Throwable => Nil

  /**
   * 每轮现值（append-only 读面：同一 roundNo 取最后一行；稳定序 = roundNo 升序）。
   * 只含 `kind="round"` 行——stale-round 审计行不参与轮次状态求值。
   */
  def currentRounds(id: String): List[RoundRow] =
    val latest = scala.collection.mutable.LinkedHashMap.empty[Int, RoundRow]
    rowsOf(id).filter(_.kind == KindRound).foreach(r => latest.put(r.roundNo, r))
    latest.values.toList.sortBy(_.roundNo)

  /** 当前轮（最大 roundNo 的现值行；无台账 = None）。 */
  def currentRound(id: String): Option[RoundRow] = currentRounds(id).lastOption

  /**
   * 已计提交全集：所有**已结算**轮次 `countedOids` 的并集——新轮结算时按此对
   * 提交 OID 去重（跨轮不重复计数）。
   */
  def countedOids(id: String): Set[String] =
    currentRounds(id).filter(_.settledAt.isDefined).flatMap(_.countedOids.getOrElse(Nil)).toSet

  private def appendSync(id: String, row: RoundRow): Unit =
    val dir = PathUtil.dataRoot / "delegates"
    os.makeDir.all(dir)
    os.write.append(fileOf(id), row.asJson.noSpaces + "\n", createFolders = true)

  /**
   * 开轮（幂等）：该 roundNo 已有**未结算**现值行 ⇒ 零写返回原行（同轮重复开 =
   * 冻结唤醒/boot 复活的同轮续跑语义）；否则追加起跑行。
   */
  def openRound(
    id: String,
    roundNo: Int,
    roundId: String,
    cause: String,
    baseHeadSha: Option[String],
    baseDirty: Option[List[String]]
  ): RoundRow =
    currentRound(id) match
      case Some(r) if r.roundNo == roundNo && r.settledAt.isEmpty => r
      case _ =>
        val row = RoundRow(
          kind = KindRound,
          roundNo = roundNo,
          roundId = roundId,
          startedAt = System.currentTimeMillis(),
          cause = cause,
          baseHeadSha = baseHeadSha,
          baseDirty = baseDirty
        )
        try appendSync(id, row)
        catch case e: Throwable => logger.warnSync(s"round ledger append failed for '$id': ${e.getMessage}")
        row

  /**
   * 结算轮次（append-only）：以该轮现值行为基（保留起跑时刻与差异基线），追加一份
   * 带 `settledAt`/`verdict`/`countedOids`/`partial` 的全量行。查无该轮（旧盘面
   * 无台账）⇒ 以入参合成最小结算行（缺基线照实为空）。已结算 ⇒ 幂等零写。
   */
  def settleRound(
    id: String,
    roundNo: Int,
    roundId: String,
    verdict: String,
    countedOids: List[String],
    partial: Option[Boolean]
  ): Unit =
    val existing = currentRounds(id).find(_.roundNo == roundNo)
    existing.filter(_.settledAt.isDefined) match
      case Some(_) => () // idempotent: already settled, never rewritten
      case None =>
        val base = existing.getOrElse(
          RoundRow(KindRound, roundNo, roundId, 0L, CauseRevivalSameRound)
        )
        val row = base.copy(
          roundId = if roundId.nonEmpty then roundId else base.roundId,
          settledAt = Some(System.currentTimeMillis()),
          verdict = Some(verdict),
          countedOids = Some(countedOids.distinct),
          partial = partial.orElse(base.partial)
        )
        try appendSync(id, row)
        catch case e: Throwable => logger.warnSync(s"round ledger settle append failed for '$id': ${e.getMessage}")

  /**
   * 取消结算：把**所有**未结算轮次标记 `cancelled` 终值（墓碑压过一切；append-only，
   * 旧轮真值保留——已结算轮的既有判词不回改）。
   */
  def settleOpenRoundsCancelled(id: String, detail: String): Unit =
    currentRounds(id).filter(_.settledAt.isEmpty).foreach { r =>
      val row = r.copy(
        settledAt = Some(System.currentTimeMillis()),
        verdict = Some("cancelled"),
        partial = r.partial.orElse(None),
        detail = Some(detail)
      )
      try appendSync(id, row)
      catch case e: Throwable => logger.warnSync(s"round ledger cancel append failed for '$id': ${e.getMessage}")
    }

  /**
   * 旧轮次迟到事件的审计行（round M 的事件到达时当前已是 round N>M）：只留痕——
   * 不触发投递、不重写归档、不重启清理链。
   */
  def recordStaleRound(id: String, roundNo: Int, roundId: String, detail: String): Unit =
    val row = RoundRow(
      kind = KindStaleRound,
      roundNo = roundNo,
      roundId = roundId,
      startedAt = System.currentTimeMillis(),
      cause = "late-event",
      detail = Some(detail)
    )
    try appendSync(id, row)
    catch case e: Throwable => logger.warnSync(s"round ledger stale audit append failed for '$id': ${e.getMessage}")

  // ── 开轮编排（Mail 新轮 = 本对象与 Registry 的单点组合面）────────────────

  /**
   * Mail 到达时的轮次判定（纯函数——输入全部机械事实，判据单点）：
   *   - 忙碌（actor 在册 ∨ 任务行在飞/冻结/待并）⇒ [[MailRoundDecision.InjectCurrentRound]]
   *     ——注入当前轮防双活（roundNo 不变、不建第二执行体）；
   *   - 已结算（任务行终态 completed/failed，或行缺而 archivedAt 在）⇒
   *     [[MailRoundDecision.OpenNewRound]]——开新轮（roundNo+1、新 roundId、
   *     archivedAt 清除、差异基线重设、旧轮台账保留）；
   *   - 证据不足（行缺且未归档——旧盘面形态）⇒ [[MailRoundDecision.LeaveAsIs]]
   *     ——沿用既有续起行为（同轮续跑语义，零新写）。
   * 墓碑拒绝不在本判定内（调用方先行——取消压过一切）。
   */
  enum MailRoundDecision:
    case InjectCurrentRound, OpenNewRound, LeaveAsIs

  /** 任务行的「未完业务」状态集（同轮续跑域）：在飞、退避、冻结、待并。 */
  val UnfinishedRowStatuses: Set[String] = Set("running", "restarting", "frozen", "ready-to-merge")

  /** 任务行的「已结算」状态集（可开新轮域）。 */
  val SettledRowStatuses: Set[String] = Set("completed", "failed")

  def decideMailRound(
    record: DelegateRegistry.Record,
    taskStatus: Option[String],
    actorRegistered: Boolean
  ): MailRoundDecision =
    if actorRegistered then MailRoundDecision.InjectCurrentRound
    else
      taskStatus match
        case Some(s) if UnfinishedRowStatuses.contains(s) => MailRoundDecision.InjectCurrentRound
        case Some(s) if SettledRowStatuses.contains(s) => MailRoundDecision.OpenNewRound
        case Some(_) => MailRoundDecision.LeaveAsIs // unknown status: legacy behavior, no new writes
        case None =>
          if record.archivedAt.isDefined then MailRoundDecision.OpenNewRound else MailRoundDecision.LeaveAsIs

  /** 座位差异基线快照（fail-soft：非 git / 读失败 = None）。 */
  private def seatBaseline(seat: String): (Option[String], Option[List[String]]) =
    try
      val p = os.Path(seat)
      if !nebflow.core.GitWorktrees.isGitRepo(p) then (None, None)
      else (nebflow.core.GitWorktrees.headSha(p), nebflow.core.GitWorktrees.dirtyPaths(p))
    catch case _: Throwable => (None, None)

  /**
   * 开新轮（Mail 到达已结算身份时的单点写）：roundNo+1、新铸 roundId、
   * `archivedAt` 清除（身份回现役窗）、差异基线重设（座位快照）、台账追加起跑行
   * （旧行原样保留）。返回新轮读数。Registry 写失败 ⇒ Left（调用方回旧腿，
   * 不破坏现场）。
   */
  def openNewRound(record: DelegateRegistry.Record): Either[String, RoundRow] =
    val nextNo = record.roundNo.getOrElse(1) + 1
    val nextId = java.util.UUID.randomUUID().toString
    val (baseSha, baseDirty) = seatBaseline(record.cwd)
    DelegateRegistry.startRound(record.id, nextNo, nextId, clearArchive = true) match
      case Left(error) => Left(error)
      case Right(()) =>
        openRound(record.id, nextNo, nextId, CauseMail, baseSha, baseDirty)
        logger.infoSync(
          s"[delegate-rounds] opened round $nextNo ($nextId) for '${record.id}' via Mail — " +
            s"archivedAt cleared, diff baseline re-anchored (head=${baseSha.getOrElse("-")}, " +
            s"dirty=${baseDirty.map(_.size).getOrElse(0)} path(s)); prior rounds preserved in the ledger"
        )
        Right(RoundRow(KindRound, nextNo, nextId, System.currentTimeMillis(), CauseMail, baseSha, baseDirty))

  /**
   * spawn 轮 1 的单点（DelegateTool 记录落盘后调用；幂等——已有台账零写）。
   * roundId 由调用方传入（与 Record 同值落盘）。
   */
  def openSpawnRound(id: String, roundId: String, cwd: String): Unit =
    currentRound(id) match
      case Some(r) if r.roundNo == 1 && r.settledAt.isEmpty => () // idempotent
      case _ =>
        val (baseSha, baseDirty) = seatBaseline(cwd)
        openRound(id, 1, roundId, CauseSpawn, baseSha, baseDirty)

end DelegateRoundLedger
