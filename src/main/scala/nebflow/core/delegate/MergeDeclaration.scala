package nebflow.core.delegate

import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.util.Try

/**
 * MergeDeclaration —— 合并队列的**属主宣告账**（缺陷批 2026-11-04 · 作者报障
 * 「合并队列会把『明确压着不合的半成品分支』自动合进 main」）。
 *
 * == 病灶（代码坐标见 [[MergeQueue]] 放行腿注）==
 *
 * 队列原来的**授予判据**里没有一条问「这批到底完成没完成」：`pump` 只核
 * 账实（已并）/ 道闸 / 净空闸 / 通道可达。于是「分支上有提交」「会话 idle 够久」
 * 「write-ahead 通道有镜像」这些**与完成无关**的形态都能拿到合并窗（
 * [[MergeEnqueueWatch]] 与 [[DelegateTerminal]] 的 queueFlip 就是两条这样的入队腿），
 * 属主失联时更是没人能被征询——队列自己把账推到「可合」。
 *
 * == 本件是什么 ==
 *
 * 一份**可机械检查**的完成宣告账：`<dataRoot>/merge-declarations.json`。只有**属主
 * 自己的显式动作**写它（`delegate_report(category="merge")` → [[MergeEnqueue]]；
 * 终态腿重放同一自申报 → [[DelegateTerminal.holdForMerge]]）。放行闸
 * （[[MergeQueue.grant]] ②）只认这份账：**无宣告 = 不授窗**，成员留在账上等人处理
 * （可见告警 + needsHuman），绝不自动合。
 *
 * 纪律：
 *   - **引擎推断不算宣告**（[[Source.EngineInferred]]：判定机判词 / queueFlip /
 *     活跃守卫代办）——`gate` 一律拒，`isOwnerDeclared` 为 false；
 *   - 宣告是**持久事实**，独立于队列成员资格（账本丢了/重启了，宣告还在；反之
 *     成员在账但没有宣告，照样不许合）；
 *   - 只写不删（`clear` 仅测试面），fail-soft：读失败 = 无宣告（**保守方向**——
 *     宁可不合，绝不误合）。
 */
object MergeDeclaration:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-declaration")

  /** 宣告来源（审计面常量：谁说了「完成且可合」）。 */
  object Source:
    /** 属主显式动作 `delegate_report(category="merge")`（权威来源）。 */
    val ReportMerge: String = "report-merge"
    /** 内部腿终态重放同一自申报（verdict 走 ready-to-merge 的落地腿）。 */
    val TerminalSelfReport: String = "terminal-self-report"
    /** **引擎推断**（判定机 / queueFlip / 活跃守卫 / boot 重放）——不构成宣告。 */
    val EngineInferred: String = "engine-inferred"

    val All: Set[String] = Set(ReportMerge, TerminalSelfReport, EngineInferred)

  end Source

  /**
   * 一条宣告（属主说的「这批做完了、可以合」）。
   * `branch`/`baseBranch`/`tip` = 宣告**当时**的分支面（可回溯记录：分支后来长没长，
   * 对账可判——见 [[MergeQueue.grant]] 的 granted 审计行）。
   */
  final case class Decl(
    delegateId: String,
    at: Long,
    source: String,
    branch: Option[String] = None,
    baseBranch: Option[String] = None,
    tip: Option[String] = None
  ):
    /** 只有属主来源算宣告；引擎推断 = 不算（gate 拒）。 */
    def isOwnerDeclared: Boolean = source != Source.EngineInferred
  end Decl

  private def path: os.Path = PathUtil.dataRoot / "merge-declarations.json"

  private def readAll(): Map[String, Decl] =
    try
      if !os.exists(path) then Map.empty
      else
        io.circe.parser.parse(os.read(path)).toOption
          .flatMap(_.hcursor.downField("declarations").as[Map[String, io.circe.Json]].toOption)
          .map(_.flatMap { case (id, j) => fromJson(id, j).map(d => id -> d) })
          .getOrElse(Map.empty)
    catch
      case e: Throwable =>
        logger.warn(s"[merge-declaration] read failed at $path: ${Option(e.getMessage).getOrElse(e.toString).take(200)}")
        Map.empty

  private def fromJson(id: String, j: Json): Option[Decl] =
    val c = j.hcursor
    (for
      at <- c.get[Long]("at").toOption
      source <- c.get[String]("source").toOption
    yield Decl(
      delegateId = id,
      at = at,
      source = source,
      branch = c.get[String]("branch").toOption,
      baseBranch = c.get[String]("baseBranch").toOption,
      tip = c.get[String]("tip").toOption
    )).filter(_.isOwnerDeclared)

  private def writeAll(all: Map[String, Decl]): Unit =
    try
      val payload = Json.obj(
        "version" -> 1.asJson,
        "declarations" -> Json.fromFields(
          all.toList.map { (id, d) =>
            id -> Json.obj(
              "at" -> d.at.asJson,
              "source" -> d.source.asJson,
              "branch" -> d.branch.fold(Json.Null)(_.asJson),
              "baseBranch" -> d.baseBranch.fold(Json.Null)(_.asJson),
              "tip" -> d.tip.fold(Json.Null)(_.asJson)
            )
          }
        )
      )
      val tmp = PathUtil.dataRoot / s"${path.last}.tmp"
      os.write.over(tmp, payload.noSpaces, createFolders = true)
      os.move(tmp, path, replaceExisting = true)
    catch
      case e: Throwable =>
        logger.warn(s"[merge-declaration] write failed at $path: ${Option(e.getMessage).getOrElse(e.toString).take(200)}")

  /** 查一条宣告（无 = None；**引擎来源**的记录视为无——它本来就不写账）。 */
  def find(delegateId: String): Option[Decl] = readAll().get(delegateId).filter(_.isOwnerDeclared)

  /** 有没有属主的完成宣告（放行闸的唯一读面）。 */
  def hasOwnerDeclaration(delegateId: String): Boolean = find(delegateId).isDefined

  /**
   * 落一条宣告（幂等：同 delegateId 后写覆盖前写——重申报以最新锚为准）。
   * `source` 必须是属主来源；引擎推断来源**不落账**（返回 None，队列侧照旧按未宣告处理）。
   * fail-soft：任何故障只 WARN，返回写进去的那条（或 None）。
   */
  def write(
    delegateId: String,
    source: String,
    branch: Option[String] = None,
    baseBranch: Option[String] = None,
    tip: Option[String] = None,
    nowMs: Long = System.currentTimeMillis()
  ): Option[Decl] =
    if source == Source.EngineInferred then
      logger.warn(
        s"[merge-declaration] refusing to record an ENGINE-INFERRED 'ready' for '$delegateId' — " +
          "only the owner's explicit action (delegate_report(category=\"merge\")) may declare a branch mergeable"
      )
      None
    else
      val decl = Decl(delegateId, nowMs, source, branch, baseBranch, tip)
      val all = readAll().updated(delegateId, decl)
      writeAll(all)
      logger.info(
        s"[merge-declaration] owner declared ready-to-merge: '$delegateId' (source=$source, branch=${branch.getOrElse("-")} → " +
          s"${baseBranch.getOrElse("-")}, tip=${tip.map(_.take(12)).getOrElse("-")})"
      )
      Some(decl)

  /**
   * 放行判据（**纯函数**——spec 判红锚）：`Right(decl)` = 属主宣告在案，可授窗；
   * `Left(reason)` = 无宣告，队列不许授窗（原因全文给面板/父通知，含可行动指引）。
   */
  def gate(delegateId: String, declared: Option[Decl], branch: String, baseBranch: String): Either[String, Decl] =
    declared.filter(_.isOwnerDeclared) match
      case Some(d) => Right(d)
      case None =>
        Left(
          s"no owner declaration of 'done and mergeable' on record for '$delegateId' — a branch that merely HAS commits " +
            s"is not a finished batch, so the merge queue will not hand out a merge turn (branch '$branch' → '$baseBranch'). " +
            "The owner must declare it explicitly (delegate_report(category=\"merge\")), or the dispatcher merges the branch " +
            "by hand (the queue re-checks every tick and closes the membership as merged once it is an ancestor)."
        )

  /** 测试面：清空宣告账（生产永不调用——宣告是持久事实）。 */
  private[nebflow] def reset(): Unit =
    try if os.exists(path) then os.remove(path)
    catch case _: Throwable => ()

end MergeDeclaration
