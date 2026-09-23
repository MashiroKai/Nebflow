package nebflow.core.project

/** loop 预算判定单点（nrloop 一期 2026-09-12；设计 §3.6「轮次帽 + 时间帽」维）。
  *
  * 纯函数（无 IO / 无状态）：引擎两条调用腿共用同一判据——
  *   ① `verifierFail` 入口（轮次维的实时判定）：拿**本 verifier** 的 `loopRound`
  *      与**目标节点**的 `loopStartedAt`；
  *   ② `NodeEngine.sweepLoopBudgets`（时间维的 30s 扫描）：拿命中节点的
  *      `loopStartedAt` 与驱动方的轮次（驱动方反查后同走本判据）。
  *
  * 语义（两维独立、**任一命中即熔断**）：自 `loopStartedAt` 起累计的
  *   - 轮次：`rounds >= maxRounds`（`maxRounds <= 0` ⇒ 该维关闭）；
  *   - 时间：`now - startedAt >= maxWallMs`（`maxWallMs <= 0` ⇒ 该维关闭；
  *     `startedAt` 缺失 = 计时未起 ⇒ 该维未命中）。
  * 返回 `Some(reason)` 时 reason 附**实际读数/上限**（取证侧据此判断哪一维先耗尽）：
  *   `rounds=3/3` 或 `wallClock=14400000ms/14400000ms`；两维同时命中则并列返回
  *   （`rounds=… ; wallClock=…`）。
  *
  * 两阈值全部经 `nebflow.shared.Defaults` prop 化（`LoopMaxRounds` /
  * `LoopMaxWallClockMs`），本判定体零常量、零现读——调用方传值 ⇒ 单测可全矩阵覆盖。
  */
object LoopBudget:

  /** 预算判定：`Some(reason)` = 熔断（reason 含读数/上限），`None` = 预算内。 */
  def decide(
    rounds: Int,
    maxRounds: Int,
    startedAt: Option[Long],
    now: Long,
    maxWallMs: Long
  ): Option[String] =
    val roundHit  = maxRounds > 0 && rounds >= maxRounds
    val wallHit   = maxWallMs > 0 && startedAt.exists(st => now - st >= maxWallMs)
    val roundPart = if roundHit then Some(s"rounds=$rounds/$maxRounds") else None
    val wallPart  =
      if wallHit then
        val elapsed = startedAt.map(st => now - st).getOrElse(0L)
        Some(s"wallClock=${elapsed}ms/${maxWallMs}ms")
      else None
    (roundPart, wallPart) match
      case (None, None)           => None
      case (Some(r), None)        => Some(r)
      case (None, Some(w))        => Some(w)
      case (Some(r), Some(w))     => Some(s"$r ; $w")
