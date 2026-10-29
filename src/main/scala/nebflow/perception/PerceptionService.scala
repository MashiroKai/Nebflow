package nebflow.perception

import PerceptionModel.*
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PathUtil}
import os.Path

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import scala.concurrent.duration.*

/**
 * 感知守护进程（统一 daemon 管理进程批 2026-10-29：作者裁定「独立daemon吧
 * 正好和我们心跳进程合并 那就是管daemon的」——感知五循环自引擎进程抽出，
 * 成独立 JVM 进程（[[nebflow.perception.PerceptionDaemonMain]]），经引擎
 * DaemonService 声明托管（autoStart/崩溃重启/端口健康探针/pid marker/stopAll
 * ——现行心跳语义逐字）；引擎会话消亡不影响收信，信件入 spool 回放）。
 *
 * 职责：IMAP 自适应轮询（OTP 窗开 15s / 常态 60s）→ L0 管线（黑名单略/
 * 白名单直投/OTP 单码槽/账单缓冲）→ 投递（经引擎 ingest 腿入 root 会话
 * ImmediateInput，引擎不可达入本地 spool 回放）→ 账单日结（HH:mm 攒批注入，
 * 零 UI）→ 草稿/发送面 → 审计。
 *
 * 全部状态在 Ref；单例 object（daemon 进程一份），spec 经 `new PerceptionCore`
 * 走纯逻辑面。进程边界全部走窄端口注入：WS 广播 `broadcast: Json => IO[Unit]`、
 * 投递出域 `deliverEnvelope: (DeliveryEnvelope, String) => IO[Boolean]`
 * （true=引擎已注入；false=不可达 ⇒ 本地 spool）、L1 判定 seam（judge）——
 * 本类对引擎零编译期依赖（SharedResources 已随进程拆分移除）。
 */
object PerceptionService:

  private val logger = NebflowLogger.forName("nebflow.perception")

  /** 固定裁决值（r3 假设 5：三项零 UI 锁定值）。 */
  val OtpWindowSec: Int = 600
  val BillingDigestHour: Int = 9
  val PollIntervalIdleSec: Int = 60
  val PollIntervalWindowSec: Int = 15
  /** 退避序列（r1 §4.2：1s → 2s → 4s → … 上限 5min，±20% 抖动防惊群）。 */
  val BackoffBaseSec: Int = 1
  val BackoffMaxSec: Int = 300
  val BackoffJitterRatio: Double = 0.2
  /** L1 kill-switch（r1 §5.2 旁路开关；false = L1 整层跳过，行为=LLM 持续不可用）。 */
  val LlmJudgeEnabled: Boolean =
    sys.props.getOrElse("nebflow.perception.llm.enabled", "true").equalsIgnoreCase("true")
  /** IDLE 重续节奏（r1 §4.1：每 25 分钟主动终止并重发；留 30s 余量）。 */
  val IdleRenewMs: Long = 25L * 60L * 1000L + 30L * 1000L
  /** 静默掐断容忍（r1 §4.3：连续 ≥3 次 → 退化轮询，冷却 10min 后再试）。 */
  val IdleSilentCutLimit: Int = 3
  val IdleCooldownMs: Long = 10L * 60L * 1000L
  /** UIDVALIDITY 重建时的补拉上限（r1 §4.1：全量拉最近 N=200 封）。 */
  val RebuildFetchCap: Int = 200

  /** 退避延迟（纯函数，spec 直测）：attempt 从 1 计，1s→2s→4s…上限 5min；±20% 抖动。 */
  def backoffDelaySec(attempt: Int, rand01: Double): Long =
    val base = math.min(BackoffBaseSec.toLong << math.min(math.max(attempt, 1) - 1, 20), BackoffMaxSec.toLong)
    val jitter = 1.0 - BackoffJitterRatio + 2 * BackoffJitterRatio * rand01.max(0.0).min(1.0)
    math.max(1L, math.round(base * jitter))

  /**
   * 连接失败的人类可读面（纯函数，spec 直测；报障 2026-10-28：SELECT 成功
   * 的 untagged 响应曾被当错误原样倾倒并 take(160) 半截截断上屏）。
   * 纪律：raw 线上数据永不透传 UI；授权码强制擦除（防任何回显路径）；
   * 输出恒为单行短句。
   */
  def readableConnectError(err: String, imapHost: String, imapPort: Int, authCode: String = ""): String =
    val scrubbed = if authCode.nonEmpty && err.contains(authCode) then err.replace(authCode, "••••") else err
    val e = scrubbed.toLowerCase
    if scrubbed.contains("AUTHENTICATIONFAILED") || scrubbed.contains("LOGIN")
      || e.contains("invalid") || scrubbed.contains("AUTH") then
      "授权码无效 · 请确认已在邮箱设置开启 IMAP 服务并使用授权码（非登录密码）"
    else if e.contains("超时") || e.contains("timeout") || e.contains("timed out") then
      s"连接 $imapHost:$imapPort 响应超时 · 请检查网络后重试"
    else if e.contains("unknownhost") || e.contains("unreachable") || e.contains("connect") then
      s"无法连接 $imapHost:$imapPort · 请检查网络与服务器设置"
    else if e.contains("imap no") || e.contains("imap bad") || e.contains("响应不完整") then
      "邮箱服务器拒绝了该操作 · 请确认已开启 IMAP 服务后重试"
    else
      "连接失败 · 请检查邮箱地址、授权码与服务器设置"

  // ── singleton runtime ─────────────────────────────────────────────────

  private sealed trait Phase
  private case object Cold extends Phase
  private case class Hot(
    core: PerceptionCore,
    loops: List[cats.effect.Fiber[IO, Throwable, Unit]]
  ) extends Phase

  private val phase: Ref[IO, Phase] = Ref.unsafe[IO, Phase](Cold)

  /**
   * Boot once (idempotent). Called from PerceptionDaemonMain (the unified
   * daemon process); the engine process no longer boots perception in-process
   * (统一 daemon 批 2026-10-29).
   */
  def boot(
    broadcast: Json => IO[Unit],
    deliverEnvelope: (DeliveryEnvelope, String) => IO[Boolean],
    judge: MailIncomingJudge = None
  ): IO[Unit] =
    phase.get.flatMap:
      case Hot(_, _) => IO.unit
      case Cold      => doBoot(broadcast, deliverEnvelope, judge).void

  /** Routes-side defensive idempotent boot; returns the live core. */
  def ensureBoot(
    broadcast: Json => IO[Unit],
    deliverEnvelope: (DeliveryEnvelope, String) => IO[Boolean],
    judge: MailIncomingJudge = None
  ): IO[PerceptionCore] =
    phase.get.flatMap:
      case Hot(core, _) => IO.pure(core)
      case Cold         => doBoot(broadcast, deliverEnvelope, judge)

  private def doBoot(
    broadcast: Json => IO[Unit],
    deliverEnvelope: (DeliveryEnvelope, String) => IO[Boolean],
    judge: MailIncomingJudge
  ): IO[PerceptionCore] =
    val core = new PerceptionCore(
      store = new PerceptionStore(PerceptionStore.defaultRoot),
      broadcast = broadcast,
      judge = judge,
      deliverEnvelope = deliverEnvelope
    )
    for
      _ <- core.bootInitialize
      poll <- core.pollLoop.start
      sweeper <- core.sweepLoop.start
      digest <- core.digestLoop.start
      spool <- core.spoolLoop.start
      idle <- core.idleLoop.start
      _ <- phase.set(Hot(core, List(poll, sweeper, digest, spool, idle)))
      _ <- logger.info("perception daemon booted (poll/sweep/digest/spool/idle loops live)")
    yield core

  /** Test/panel access to the live core (None before boot). */
  def coreNow: IO[Option[PerceptionCore]] =
    phase.get.map:
      case Hot(core, _) => Some(core)
      case Cold         => None

end PerceptionService

/**
 * The perception core: all state + flows. Constructed once by
 * [[PerceptionService.boot]] (in the unified daemon process); tests construct
 * directly with a temp root.
 */
class PerceptionCore(
  val store: PerceptionStore,
  broadcast: Json => IO[Unit],
  /** L1 判定 seam（None = LLM 判定关——行为等同 LLM 持续不可用，搁置 spool）。 */
  judge: MailIncomingJudge = None,
  /** IMAP 连接 seam（spec 注本地明文 mock；生产 = MailClient.connectImap）。 */
  imapConnect: (String, Int) => Either[String, MailClient.ImapSession] = ((h: String, p: Int) => MailClient.connectImap(h, p)),
  /**
   * 投递出域 seam（统一 daemon 批 2026-10-29）：信封出本进程的唯一出口——
   * daemon 进程 = 引擎 ingest HTTP 腿（引擎侧 resolveRoots + ImmediateInput，
   * 引擎不可达返回 false）；spec = 可注入桩。返回 true = 已注入（不落 spool）；
   * false = 不可达（[[injectEnvelope]] 落本地 spool，回放腿补投）。
   * 原 `resources: SharedResources` 参数（引擎耦合，恒未触达）与
   * `MailTool.resolveRoots` 进程内直调随本批移除——本类对引擎零编译期依赖。
   */
  deliverEnvelope: (DeliveryEnvelope, String) => IO[Boolean]
):
  import PerceptionService.*

  private val logger = NebflowLogger.forName("nebflow.perception")

  val accounts: Ref[IO, List[MailAccount]] = Ref.unsafe(Nil)
  val accountRuntime: Ref[IO, Map[String, AccountRuntime]] = Ref.unsafe(Map.empty)
  val windows: Ref[IO, Map[String, OtpWindow]] = Ref.unsafe(Map.empty)
  val drafts: Ref[IO, List[DraftRecord]] = Ref.unsafe(Nil)
  private val booted = Ref.unsafe[IO, Boolean](false)
  /**
   * IDLE→轮询即取踢门槽（idleWatch 命中 EXISTS 时 complete；pollLoop 每轮放入
   * 新 Deferred 并限时等待——等待被提前唤醒 = 推送即取，轮询退化为兜底）。
   * 槽位单发：complete 与置新之间的极窄竞态窗丢一脚 = 该信按 60s 轮询兜底取
   * （与 IDLE 缺席时同态，零丢失）。
   */
  private val kickSlot: Ref[IO, Option[Deferred[IO, Unit]]] = Ref.unsafe(None)
  def kickPoll: IO[Unit] = kickSlot.getAndSet(None).flatMap(_.traverse_(_.complete(())))
  /** IDLE 监视腿的每账户运行态（跨轮存活的会话 + 静默掐断计数 + 冷却/不支持期限）。 */
  val idleState: Ref[IO, Map[String, IdleRuntime]] = Ref.unsafe(Map.empty)

  case class AccountRuntime(
    state: EntryState = EntryState.Unconfigured,
    lastError: String = "",
    nextAttemptAt: Long = 0L,
    backoffAttempt: Int = 0
  )

  case class IdleRuntime(
    session: Option[MailClient.ImapSession] = None,
    silentCuts: Int = 0,
    /** epoch-ms 前不再尝试 IDLE（静默掐断冷却 / 服务器不支持 IDLE 的重试窗）。 */
    cooldownUntil: Long = 0L
  )

  // ── boot ───────────────────────────────────────────────────────────────

  def bootInitialize: IO[Unit] =
    for
      _ <- IO.blocking(store.ensureDirs()).attempt.void
      accs <- IO.blocking(store.loadAccounts())
      _ <- accounts.set(accs)
      ds <- IO.blocking(store.loadDrafts())
      _ <- drafts.set(ds)
      st <- IO.blocking(store.loadState())
      _ <- accountRuntime.set(st.map { (k, s) =>
        val state =
          if s.lastError.contains("auth") then EntryState.AuthFailed
          else if s.lastError.nonEmpty then EntryState.Backoff
          else if accs.find(_.id == k).exists(!_.enabled) then EntryState.Paused
          else EntryState.Idle
        k -> AccountRuntime(state, s.lastError, 0L, 0)
      })
      _ <- booted.set(true)
      _ <- broadcastState()
    yield ()

  // ── loops ──────────────────────────────────────────────────────────────

  /**
   * Adaptive poll: window open → 15s, else 60s（r1 §4 的轮询腿；IDLE 主推时
   * idleWatch 命中 EXISTS 即踢 [[pollKick]]，此处的等待被提前唤醒 = 推送即取，
   * 轮询退化为兜底）。递归腿在 for-fla­tMap lambda 内 = 惰性（构造期零递归）。
   */
  def pollLoop: IO[Unit] =
    for
      _ <- pollOnce.attempt.void
      sec <- pollIntervalSec
      latch <- Deferred[IO, Unit]
      _ <- kickSlot.set(Some(latch))
      _ <- latch.get.timeoutTo(FiniteDuration(sec.toLong, "seconds"), IO.unit)
      _ <- kickSlot.update {
        case Some(l) if l eq latch => None
        case other                 => other
      }
      _ <- pollLoop
    yield ()

  private def pollIntervalSec: IO[Int] =
    windows.get.map(w => if w.nonEmpty then PollIntervalWindowSec else PollIntervalIdleSec)

  /**
   * NOTE: recursion leg must be lazy (`IO.defer`) — `*>` evaluates its right
   * operand strictly, so a bare `*> sweepLoop` self-reference recurses
   * infinitely while BUILDING the IO description (StackOverflowError at boot,
   * before any fiber starts; same bug class as ProjectActor.ttlScanner /
   * TaskStuckWatcher.run — QA e2e SOE 回归先例）。Semantics unchanged:
   * sleep 10s → sweepOnce → repeat.
   */
  def sweepLoop: IO[Unit] =
    IO.sleep(scala.concurrent.duration.Duration(10, "seconds")) *> IO.defer(sweepOnce *> sweepLoop)

  /** Digest at HH:MM local (single fire per day; loop sleeps till next). */
  def digestLoop: IO[Unit] =
    for
      now <- IO(LocalDateTime.now())
      target = now.toLocalDate.atTime(BillingDigestHour, 5)
      next = if now.isBefore(target) then target else target.plusDays(1)
      waitMs = java.time.Duration.between(now, next).toMillis.max(1000L)
      _ <- IO.sleep(scala.concurrent.duration.Duration(waitMs, "milliseconds"))
      _ <- runBillingDigest().attempt.void
    yield ()

  /** 同型修净（与 sweepLoop 同批）：递归腿 `IO.defer` 包住，构造期零递归。 */
  def spoolLoop: IO[Unit] =
    IO.sleep(scala.concurrent.duration.Duration(45, "seconds")) *> IO.defer(replaySpool().attempt.void *> spoolLoop)

  // ── IDLE 推送腿（r1 §4 主推；作者构想 2「不要用轮询，要像 hook 那样」）────

  /**
   * IDLE 监视循环（单 fiber 轮转，加性腿——任何失败只回退轮询，零既有行为回归）：
   * 每轮对每个「启用 + 有授权码 + 不在冷却」的账户：取回/新建 IDLE 会话 →
   * idleOnce(25min 重续)——EXISTS ⇒ 踢 [[pollKick]]（轮询等待被提前唤醒=推送
   * 即取）、Renew ⇒ 会话留轮、静默断连计数 ≥3 ⇒ 冷却 10min（r1 §4.3 退化
   * 轮询语义，轮询腿兜底）、服务器无 IDLE 能力 ⇒ 冷却后重探。
   * 会话与计数随轮存活于 [[idleState]]；账户被移除/停用 → 下一轮清理关闭。
   */
  def idleLoop: IO[Unit] =
    for
      _ <- idleRound.attempt.void
      _ <- IO.sleep(FiniteDuration(2, "seconds"))
      _ <- idleLoop
    yield ()

  private def idleEligible: IO[List[MailAccount]] =
    for
      accs <- accounts.get
      rt <- accountRuntime.get
      now <- IO(System.currentTimeMillis())
      ids = accs.filter(a => a.enabled && rt.get(a.id).forall(_.state != EntryState.AuthFailed))
      _ <- idleState.update { m =>
        val live = ids.map(_.id).toSet
        // 清理：不在册/停用/授权失效账户的残留会话关闭。
        m.foreach { (id, r) =>
          if !live.contains(id) then r.session.foreach(_.close())
        }
        m.view.filterKeys(live.contains).toMap
      }
      eligible <- ids.filterA { a =>
        readAuthCode(a).map(_.isDefined)
      }
      cooled <- idleState.get.map { m =>
        val now0 = System.currentTimeMillis()
        eligible.filter(a => m.get(a.id).forall(_.cooldownUntil <= now0))
      }
    yield cooled

  private def idleRound: IO[Unit] =
    idleEligible.flatMap(_.traverse_(a => idleWatch(a).attempt.void))

  private def idleWatch(account: MailAccount): IO[Unit] =
    for
      st0 <- idleState.get.map(_.getOrElse(account.id, IdleRuntime()))
      auth <- readAuthCode(account)
      _ <- auth match
        case None => IO.unit
        case Some(code) =>
          st0.session match
            case Some(sess) => idleStep(account, sess, freshLogin = false)
            case None =>
              IO.blocking(imapConnect(account.imapHost, account.imapPort)).flatMap {
                case Left(err) =>
                  audit("conn", "idle-connect-failed", s"id=${account.id} err=${err.take(120)}") *>
                    markIdle(account, st0.copy(cooldownUntil = System.currentTimeMillis() + IdleCooldownMs))
                case Right(sess) =>
                  audit("conn", "idle-connected", s"id=${account.id}") *>
                    idleStep(account, sess, freshLogin = true)
              }
    yield ()

  /**
   * 一拍监视：新建会话先补 LOGIN/SELECT/CAPABILITY（r1 §4.3：服务器无 IDLE
   * 能力 ⇒ 退化轮询、冷却后重探），随后 idleOnce 阻塞等待。整段 blocking 腿
   * 内 Either 短路（Either 组合是急切的——网络面不许落在 IO 计算线程）。
   */
  private def idleStep(account: MailAccount, sess: MailClient.ImapSession, freshLogin: Boolean): IO[Unit] =
    IO.blocking {
      val pre: Either[String, Unit] =
        if !freshLogin then Right(())
        else
          for
            _ <- sess.login(account.address, authCodeAt(account))
            _ <- sess.selectInbox()
            caps <- sess.capability()
            _ <- Either.cond(MailClient.hasIdleCapability(caps), (), "no-idle-capability")
          yield ()
      pre.flatMap(_ => sess.idleOnce(IdleRenewMs))
    }.flatMap {
      case Left("no-idle-capability") =>
        sess.close()
        audit("conn", "idle-unsupported", s"id=${account.id} fallback=poll") *>
          markIdle(account, IdleRuntime(None, 0, System.currentTimeMillis() + IdleCooldownMs))
      case Left(err) => onIdleFailure(account, sess, err)
      case Right(MailClient.IdleEvent.Exists) =>
        audit("conn", "idle-exists", s"id=${account.id}") *>
          kickPoll *>
          keepIdleSession(account, sess) // DONE 已发——会话留轮，下轮直接再 IDLE
      case Right(MailClient.IdleEvent.Renew) =>
        keepIdleSession(account, sess)
      case Right(MailClient.IdleEvent.ConnectionLost) =>
        sess.close()
        idleState.get.map(_.getOrElse(account.id, IdleRuntime())).flatMap { st =>
          val cuts = st.silentCuts + 1
          if cuts >= IdleSilentCutLimit then
            audit("conn", "idle-degraded-poll", s"id=${account.id} silentCuts=$cuts") *>
              markIdle(account, IdleRuntime(None, 0, System.currentTimeMillis() + IdleCooldownMs))
          else markIdle(account, IdleRuntime(None, cuts, 0L))
        }
    }

  private def onIdleFailure(account: MailAccount, sess: MailClient.ImapSession, err: String): IO[Unit] =
    IO.blocking(sess.close()).attempt.void *>
      audit("conn", "idle-failed", s"id=${account.id} err=${err.take(120)}") *>
      markIdle(account, IdleRuntime(None, 0, System.currentTimeMillis() + IdleCooldownMs))

  private def keepIdleSession(account: MailAccount, sess: MailClient.ImapSession): IO[Unit] =
    markIdle(account, IdleRuntime(Some(sess), 0, 0L))

  private def markIdle(account: MailAccount, st: IdleRuntime): IO[Unit] =
    idleState.update(m => m + (account.id -> st))

  /** login 需要 authCode——idleStep 的 blocking 腿内直读（secrets 0600 文件）。 */
  private def authCodeAt(account: MailAccount): String =
    val p = PathUtil.dataRoot / "secrets" / account.authCodeSecret
    if account.authCodeSecret.nonEmpty && os.exists(p) then os.read(p).trim else ""

  // ── polling ────────────────────────────────────────────────────────────

  /** One poll pass over every enabled account (error-isolated per account). */
  def pollOnce: IO[Unit] =
    for
      accs <- accounts.get
      rt <- accountRuntime.get
      now <- IO(System.currentTimeMillis())
      due = accs.filter(a => a.enabled && rt.get(a.id).forall(r => now >= r.nextAttemptAt))
      _ <- due.traverse_(a => pollAccount(a).handleErrorWith(e =>
        logger.warn(s"poll ${a.address}: ${e.getMessage}").void
      ))
    yield ()

  private def pollAccount(account: MailAccount): IO[Unit] =
    for
      authCode <- readAuthCode(account).flatMap {
        case Some(v) => IO.pure(v)
        case None => IO.raiseError(new java.lang.IllegalStateException("auth code missing"))
      }
      result <- IO.blocking {
        val conn = imapConnect(account.imapHost, account.imapPort)
        conn.flatMap { session =>
          // pure Either flow inside the blocking lane (session is synchronous)
          val stateMap = store.loadState()
          val st0 = stateMap.getOrElse(account.id, PerceptionStore.AccountState())
          val flow: Either[String, (List[IncomingMail], PerceptionStore.AccountState, Boolean)] = for
            _ <- session.login(account.address, authCode)
            stats <- session.selectInbox()
            // UIDVALIDITY 守卫（r1 §4.1）：文件夹重建 → UID 空间重排 ⇒ 重置
            // lastSeenUid 并走「最近 N=200 封 + Message-ID 台账去重」补拉。
            (uidReset, st1) = stats.uidValidity match
              case Some(vw) if st0.uidvalidity > 0 && vw != st0.uidvalidity =>
                (true, st0.copy(uidvalidity = vw, lastSeenUid = 0L))
              case Some(vw) => (false, st0.copy(uidvalidity = vw))
              case None     => (false, st0)
            _ = store.saveState(stateMap + (account.id -> st1))
            uids <- session.searchUids(st1.lastSeenUid)
            // 首连（无 UIDVALIDITY 基线）只记 maxUID 不回填历史（既有语义）；
            // UIDVALIDITY 重建则补拉最近 N=200 封（台账去重防重投）。
            firstSync = st0.lastSeenUid <= 0 && st0.uidvalidity == 0L
            selected = if uidReset then uids.takeRight(RebuildFetchCap) else uids
            toFetch = if firstSync then Nil else selected
            mails <- toFetch.foldLeft[Either[String, List[IncomingMail]]](Right(Nil)) { (acc, uid) =>
              acc.flatMap(list => session.fetchMessage(uid).map(raw => list :+ MailClient.toIncomingMail(uid, raw)))
            }
            _ = session.logout()
          yield (mails, st1, firstSync)
          flow match
            case Right((mails, st1, firstSync)) =>
              session.close(); Right((mails, firstSync))
            case Left(err) =>
              session.close(); Left(err)
        }
      }.flatMap {
        case Right((mails, firstSync)) =>
          val firstSyncWrite: IO[Unit] =
            if firstSync then
              // 首连：记录当前 maxUID 作基线（历史不回填），不进管线。
              IO.blocking {
                val cur = store.loadState().getOrElse(account.id, PerceptionStore.AccountState())
                val maxUid = mails.map(_.uid).appended(cur.lastSeenUid).maxOption.getOrElse(cur.lastSeenUid)
                store.saveState(store.loadState() + (account.id -> cur.copy(lastSeenUid = maxUid)))
                ()
              }
            else IO.unit
          firstSyncWrite *> recordOk(account) *>
            (if mails.isEmpty || firstSync then IO.unit else processMails(account, mails))
        case Left(err) =>
          recordFailure(account, err)
      }
    yield ()

  /**
   * 新邮件管线（L0 判定序 = r1 §5.1 逐字：0.1 黑名单 → 0.2 白名单直投 →
   * 0.3 验证码单码槽 → 0.4 账单缓冲 → 0.6 L1 判定；命中即短路）。
   * Message-ID 台账双保险（r1 §4.1/§5.2）在判定序之前拦截已判定邮件。
   * L1（r2 §5.2）：一次 LLM 调用三值裁决——Deliver 投递 / Drop 丢弃 /
   * Unavailable 搁置 spool 待恢复（spoolLoop 补判），不臆测。
   */
  def processMails(account: MailAccount, mails: List[IncomingMail]): IO[Unit] =
    for
      blacklist <- IO.blocking(store.loadBlacklist())
      ordered = mails.sortBy(_.uid)
      _ <- ordered.traverse_ { mail =>
        val uidKey = account.id
        for
          _ <- audit("L0", s"mail-from:${mail.fromDomain}", s"uid=${mail.uid} subjectMasked=${maskSubject(mail.subject)}")
          decided <- isAlreadyDecided(account, mail.messageId)
          continue <-
            if decided then audit("L0", "duplicate-skip", s"uid=${mail.uid} mid=${maskSubject(mail.messageId)}").as(false)
            else IO.pure(true)
          _ <-
            if !continue then IO.unit
            else
              windows.get.flatMap { windowsNow =>
                val liveWindows = windowsNow.values.filter(w => w.expiresAt > System.currentTimeMillis()).toList
                val otpHit =
                  if liveWindows.isEmpty then None
                  else OtpExtractor.extract(mail.subject, mail.bodyText).headOption
                val decision: IO[Unit] =
                  otpHit match
                    case Some(cand) => deliverOtpHit(liveWindows, mail, cand)
                    case None =>
                      if isBlocked(blacklist, mail) then audit("L0", "blocked", s"uid=${mail.uid}")
                      else if isTrusted(account, mail) then deliverMail(mail, via = "whitelist")
                      else
                        BillingClassifier.classify(mail.fromDomain, mail.subject, mail.bodyText) match
                          case v if v.isBilling =>
                            for
                              today <- IO(LocalDate.now().toString)
                              _ <- IO.blocking(store.billingAppend(today, PerceptionStore.BillingLine(
                                ts = System.currentTimeMillis(),
                                messageId = mail.messageId,
                                fromDomain = mail.fromDomain,
                                subject = mail.subject.take(120),
                                amountSample = v.amountSample.take(40),
                                matchedBy = v.matchedBy
                              )))
                              _ <- audit("L0", "billing-buffered", s"uid=${mail.uid} by=${v.matchedBy}")
                            yield ()
                          case _ =>
                            judge match
                              case None =>
                                // L1 关（kill-switch/未装配）＝ LLM 持续不可用：搁置待恢复。
                                spoolForJudge(mail).flatMap(_ =>
                                  audit("L1", "held-spooled", s"uid=${mail.uid} reason=llm-off"))
                              case Some(j) =>
                                j(mail).flatMap {
                                  case L1Verdict.Deliver =>
                                    audit("L1", "deliver-full", s"uid=${mail.uid}") *>
                                      deliverMail(mail, via = "llm")
                                  case L1Verdict.Drop =>
                                    audit("L1", "dropped", s"uid=${mail.uid}")
                                  case L1Verdict.Unavailable =>
                                    spoolForJudge(mail).flatMap(_ =>
                                      audit("L1", "held-spooled", s"uid=${mail.uid} reason=unavailable"))
                                }
                decision *> recordDecided(account, mail.messageId) *> advanceUid(account, mail.uid)
              }
        yield ()
      }
    yield ()

  /** L1 降级搁置：整信入 spool（0600），spoolLoop 每 45s 补判（恢复后按序）。 */
  private def spoolForJudge(mail: IncomingMail): IO[Unit] =
    IO.blocking(store.spoolWrite(DeliveryEnvelope(priority = "normal", text = "", pendingMail = Some(mail)))).void

  private def maskSubject(s: String): String =
    if s.length > 24 then s.take(24) + "…" else s

  /** Message-ID 台账命中？（r1 §5.2「Message-ID 已判过的不再判」；空 mid 不拦。） */
  private def isAlreadyDecided(account: MailAccount, messageId: String): IO[Boolean] =
    if messageId.trim.isEmpty then IO.pure(false)
    else IO.blocking(store.loadState().get(account.id).exists(_.decidedMsgIds.contains(messageId)))

  /** 判定完成后入账（上限裁剪最旧；空 mid 不入）。 */
  private def recordDecided(account: MailAccount, messageId: String): IO[Unit] =
    if messageId.trim.isEmpty then IO.unit
    else
      IO.blocking {
        val st = store.loadState()
        val cur = st.getOrElse(account.id, PerceptionStore.AccountState())
        val trimmed = (cur.decidedMsgIds :+ messageId).takeRight(PerceptionStore.DecidedMsgIdCap)
        store.saveState(st + (account.id -> cur.copy(decidedMsgIds = trimmed)))
        ()
      }

  private def advanceUid(account: MailAccount, uid: Long): IO[Unit] =
    IO.blocking {
      val st = store.loadState()
      val cur = st.getOrElse(account.id, PerceptionStore.AccountState())
      store.saveState(st + (account.id -> cur.copy(lastSeenUid = math.max(cur.lastSeenUid, uid), lastOkAt = System.currentTimeMillis())))
      ()
    }

  private def recordOk(account: MailAccount): IO[Unit] =
    for
      rt <- accountRuntime.updateAndGet { m =>
        m + (account.id -> AccountRuntime(EntryState.Idle, "", 0L, 0))
      }
      _ <- IO.blocking {
        val st = store.loadState()
        val cur = st.getOrElse(account.id, PerceptionStore.AccountState())
        store.saveState(st + (account.id -> cur.copy(lastError = "", lastOkAt = System.currentTimeMillis())))
        ()
      }
      _ <- broadcastState()
    yield ()

  private def recordFailure(account: MailAccount, err: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      rt <- accountRuntime.updateAndGet { m =>
        val cur = m.getOrElse(account.id, AccountRuntime())
        val attempt = cur.backoffAttempt + 1
        // r1 §4.2：1s→2s→4s…上限 5min，±20% 抖动防惊群。
        val delaySec = backoffDelaySec(attempt, java.util.concurrent.ThreadLocalRandom.current().nextDouble())
        val state = if err.toLowerCase.contains("auth") || err.contains("LOGIN") || err.contains("INVALID") then EntryState.AuthFailed
        else EntryState.Backoff
        m + (account.id -> cur.copy(
          state = state,
          lastError = err.take(200),
          backoffAttempt = attempt,
          nextAttemptAt = now + delaySec * 1000L
        ))
      }
      _ <- IO.blocking {
        val st = store.loadState()
        val cur = st.getOrElse(account.id, PerceptionStore.AccountState())
        store.saveState(st + (account.id -> cur.copy(lastError = err.take(200))))
        ()
      }
      _ <- broadcastState()
    yield ()

  // ── trust / block ─────────────────────────────────────────────────────

  def isTrusted(account: MailAccount, mail: IncomingMail): Boolean =
    val trusted = account.trustedSenders.map(_.toLowerCase).filter(_.nonEmpty)
    if trusted.isEmpty then false
    else
      val from = mail.from.toLowerCase
      val dom = mail.fromDomain.toLowerCase
      trusted.exists(t => from == t || from.endsWith("<" + t) || dom == t.stripPrefix("@") || (t.startsWith("@") && dom == t.drop(1)))

  /**
   * 黑名单判定（r1 §5.1 判定 0.1：`perception/blacklist.json` 用户可编辑，域或
   * 完整地址，大小写不敏感；`@domain` / 裸域 / 完整地址三形态均命中）。
   * 表为空 = 恒 false（零行为变化）。
   */
  def isBlocked(blacklist: List[String], mail: IncomingMail): Boolean =
    if blacklist.isEmpty then false
    else
      val from = mail.from.toLowerCase
      val dom = mail.fromDomain.toLowerCase
      blacklist.exists { b =>
        val e = b.trim.toLowerCase
        e.nonEmpty && (from == e || dom == e.stripPrefix("@") || (e.startsWith("@") && dom == e.drop(1)))
      }

  // ── delivery（ImmediateInput + spool 回放）───────────────────────────

  /** 正文上限（r1 §5.3：≤64KB，超出截断并注明）。 */
  val EnvelopeBodyCapChars: Int = 64 * 1024

  /**
   * 投递信封（r1 §5.3 格式逐字：from/时间、主题、分类行、优先级行、正文全文
   * ≤64KB 超出截断注明）。`via` = L0/L1 判定来源（whitelist / llm）。
   */
  def deliverMail(mail: IncomingMail, via: String): IO[Unit] =
    val time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    val body = Option(mail.bodyText).getOrElse("")
    val clipped =
      if body.length > EnvelopeBodyCapChars then body.take(EnvelopeBodyCapChars) + s"\n[正文超长已截断：原文 ${body.length} 字符]"
      else body
    val envelope = DeliveryEnvelope(
      priority = "normal",
      text = s"""[感知 · 邮件] from: ${mail.from} · $time
主题: ${mail.subject}
分类: ${via match
        case "whitelist" => "白名单直通"
        case "llm"       => "LLM 判定 · 投递"
        case other       => other
      }
优先级: normal
——— 正文 ———
$clipped"""
    )
    injectEnvelope(envelope, s"mail:${mail.messageId}").void

  def deliverOtpHit(live: List[OtpWindow], mail: IncomingMail, cand: OtpExtractor.OtpCandidate): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      hit = OtpHit(cand.code, mail.from, mail.fromDomain, now)
      _ <- windows.update { m =>
        live.foldLeft(m) { (acc, w) =>
          val nid = w.copy(hit = Some(hit), consumed = false)
          acc + (w.windowId -> nid)
        }
      }
      _ <- audit("otp", "hit", s"windowCount=${live.size} codeMasked=${OtpExtractor.mask(cand.code)} from=${mail.fromDomain}")
      _ <- live.traverse_(w =>
        broadcast(Json.obj(
          "type" -> "perception.otp.code".asJson,
          "windowId" -> w.windowId.asJson,
          "requestId" -> w.requestId.asJson,
          "from" -> mail.from.asJson,
          "fromDomain" -> mail.fromDomain.asJson,
          "receivedAt" -> now.asJson
        ))
      )
    yield ()

  /** Inject an envelope into the live root session (via the engine ingest leg); spool when unreachable. */
  def injectEnvelope(env: DeliveryEnvelope, dedupKey: String): IO[Boolean] =
    deliverEnvelope(env, dedupKey).flatMap {
      case true => IO.pure(true)
      case false =>
        IO.blocking(store.spoolWrite(env)).flatMap {
          case Right(id) => audit("deliver", "spooled", s"key=$dedupKey spoolId=$id").as(false)
          case Left(err) => audit("deliver", "spool-failed", s"key=$dedupKey err=$err").as(false)
        }
    }

  /**
   * spool 回放：已判定信封直接注入；L1 待判信封（pendingMail）先补判（r2 §5.2
   * 「恢复后按序补判」）——判 Deliver 注入、Drop 丢弃、Unavailable 留存下轮再试。
   */
  def replaySpool(): IO[Unit] =
    for
      items <- IO.blocking(store.spoolList())
      _ <- items.traverse_ { (id, env) =>
        env.pendingMail match
          case Some(mail) =>
            judge match
              case None => IO.unit // LLM 仍不可用：留存待恢复
              case Some(j) =>
                j(mail).flatMap {
                  case L1Verdict.Deliver =>
                    audit("L1", "spool-rejudged", s"spoolId=$id verdict=deliver") *>
                      injectEnvelope(DeliveryEnvelope(priority = "normal", text = envelopeTextOf(mail), pendingMail = None), s"spool-judged:$id")
                        .flatMap(ok => IO.whenA(ok)(IO.blocking(store.spoolRemove(id))))
                  case L1Verdict.Drop =>
                    audit("L1", "spool-rejudged", s"spoolId=$id verdict=drop") *> IO.blocking(store.spoolRemove(id))
                  case L1Verdict.Unavailable => IO.unit
                }
          case None =>
            injectEnvelope(env, s"spool:$id").flatMap {
              case true  => IO.blocking(store.spoolRemove(id)) *> audit("deliver", "spool-replayed", s"spoolId=$id")
              case false => IO.unit
            }
      }
    yield ()

  /** 补判后的注入文本（与 deliverMail 同一信封口径）。 */
  private def envelopeTextOf(mail: IncomingMail): String =
    val body = Option(mail.bodyText).getOrElse("")
    val clipped = if body.length > EnvelopeBodyCapChars then body.take(EnvelopeBodyCapChars) + s"\n[正文超长已截断：原文 ${body.length} 字符]" else body
    s"""[感知 · 邮件] from: ${mail.from}（恢复补判）
主题: ${mail.subject}
分类: LLM 判定 · 投递
优先级: normal
——— 正文 ———
$clipped"""

  // ── OTP windows ───────────────────────────────────────────────────────

  /**
   * 开窗（幂等 by requestId）：同一 ask 的引擎侧开窗（r1 §6.1 T0 = ask 弹出）
   * 与前端渲染腿开窗收敛到同一窗口——先到者建窗，后到者复用（防双窗双播）。
   */
  def openOtpWindow(requestId: String, windowSec: Int = OtpWindowSec): IO[OtpWindow] =
    for
      now <- IO(System.currentTimeMillis())
      existing <- windows.get.map(_.values.find(w => w.requestId == requestId && w.expiresAt > now))
      w <- existing match
        case Some(w) => IO.pure(w)
        case None =>
          val nw = OtpWindow(
            windowId = s"otpw-$now-${PerceptionStore.rand4()}",
            requestId = requestId,
            openedAt = now,
            expiresAt = now + windowSec * 1000L
          )
          windows.update(m => m + (nw.windowId -> nw)) *> audit("otp", "window-open", s"requestId=$requestId sec=$windowSec").as(nw)
    yield w

  def consumeOtp(windowId: String): IO[Option[OtpHit]] =
    for
      now <- IO(System.currentTimeMillis())
      // getAndUpdate = 原子清槽并拿旧值（一次性消费）；窄竞态窗内双击同窗以
      // consumed 标记裁决（第二击拿不到值），v1 口径可接受。
      prev <- windows.getAndUpdate { m =>
        m.get(windowId) match
          case Some(w) if shouldConsume(w, now) =>
            m.updated(windowId, w.copy(hit = None, consumed = true))
          case _ => m
      }
      got = prev.get(windowId).flatMap(w => if shouldConsume(w, now) then w.hit else None)
      _ <- got.traverse_(h => audit("otp", "consumed", s"windowId=$windowId codeMasked=${OtpExtractor.mask(h.code)}"))
    yield got

  private def shouldConsume(w: OtpWindow, nowMs: Long): Boolean =
    w.expiresAt > nowMs && w.hit.isDefined && !w.consumed

  /** 窗内命中元数据（只读，不消费、不含码值）——开窗响应的补渲染数据源。 */
  def windowHitMeta(windowId: String): IO[Option[OtpHit]] =
    for
      now <- IO(System.currentTimeMillis())
      got <- windows.get.map(_.get(windowId).flatMap(w => if shouldConsume(w, now) then w.hit else None))
    yield got

  def sweepOnce: IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      snap <- windows.get
      expired = snap.values.filter(_.expiresAt <= now).toList
      _ <- windows.set(snap.filter { case (_, w) => w.expiresAt > now })
      _ <- expired.traverse_(w => audit("otp", "window-expired", s"requestId=${w.requestId}"))
      _ <- expired.traverse_(w =>
        broadcast(Json.obj("type" -> "perception.otp.expired".asJson, "requestId" -> w.requestId.asJson))
      )
    yield ()

  // ── billing digest ────────────────────────────────────────────────────

  /** HH:mm 到点：汇总当日及未清的历史缓冲 → 一封注入 → 清缓冲。空 = 零空报。 */
  def runBillingDigest(): IO[Unit] =
    for
      today <- IO(LocalDate.now().toString)
      files <- IO.blocking {
        try
          import scala.jdk.CollectionConverters.*
          java.nio.file.Files.list(store.billingDir.toNIO).iterator().asScala
            .map(p => os.Path(p))
            .filter(_.ext == "jsonl")
            .filter(n => n.last.stripSuffix(".jsonl") <= today)
            .toList
            .sortBy(_.last)
        catch case _: Throwable => Nil
      }
      lines = files.flatMap(f => store.billingRead(f.last.stripSuffix(".jsonl")))
      _ <-
        if lines.isEmpty then audit("digest", "empty-skip", "no billing lines")
        else
          for
            _ <- injectEnvelope(DeliveryEnvelope(
              priority = "billing",
              text = billingDigestText(lines)
            ), s"digest:$today")
            _ <- files.traverse_(f => IO.blocking(store.billingClear(f.last.stripSuffix(".jsonl"))))
            _ <- audit("digest", "injected", s"day=$today count=${lines.size}")
          yield ()
    yield ()

  private def billingDigestText(lines: List[PerceptionStore.BillingLine]): String =
    val byDay = lines.groupBy(l => java.time.Instant.ofEpochMilli(l.ts).atZone(java.time.ZoneId.systemDefault()).toLocalDate.toString)
    val sb = new StringBuilder
    sb.append(s"【感知 · 账单日结】共 ${lines.size} 封账单类邮件\n")
    byDay.toSeq.sortBy(_._1).foreach { (day, ls) =>
      sb.append(s"\n— $day（${ls.size} 封）—\n")
      ls.take(50).foreach { l =>
        sb.append(s"· ${l.fromDomain} · ${l.subject.take(60)}${if l.amountSample.nonEmpty then s" · ${l.amountSample}" else ""}\n")
      }
    }
    sb.toString

  // ── accounts / config（两问配置）────────────────────────────────────

  /**
   * Connect + test + save (one shot). authCode goes to the secrets store only;
   * the account row never carries the value.
   *
   * 成功 = Right((account, InboxStats))——统计供连接卡显示「已连接：收件箱
   * N 封、M 封未读」（报障 2026-10-28）。失败路径持久化零写入（secret/账户/
   * 状态均不动），重试幂等（同地址同 id 覆盖、secret overwrite）。
   */
  def connectAccount(
    address: String,
    authCode: String,
    imapHost: Option[String] = None,
    imapPort: Option[Int] = None,
    smtpHost: Option[String] = None,
    smtpPort: Option[Int] = None,
    smtpImplicitTls: Option[Boolean] = None,
    displayName: Option[String] = None,
    trustedSenders: List[String] = Nil
  ): IO[Either[String, (MailAccount, MailClient.InboxStats)]] =
    val addr = address.trim.toLowerCase
    if !DraftCodec.looksLikeAddress(addr) then IO.pure(Left("邮箱地址格式不正确"))
    else if authCode.isBlank then IO.pure(Left("请填写授权码"))
    else
      val preset = MailboxPresets.forAddress(addr)
      val imapH = imapHost.orElse(preset.map(_.imapHost)).getOrElse("")
      val smtpH = smtpHost.orElse(preset.map(_.smtpHost)).getOrElse("")
      val imapP = imapPort.orElse(preset.map(_.imapPort)).getOrElse(993)
      val smtpP = smtpPort.orElse(preset.map(_.smtpPort)).getOrElse(465)
      val implicitTls = smtpImplicitTls.orElse(preset.map(_.smtpImplicitTls)).getOrElse(true)
      if imapH.isBlank || smtpH.isBlank then IO.pure(Left("无法识别邮箱服务商 · 请在高级设置中手填服务器"))
      else
        IO.blocking {
          MailClient.connectImap(imapH, imapP).flatMap { session =>
            val flow = for
              _ <- session.login(addr, authCode)
              stats <- session.selectInbox()
            yield stats
            flow match
              case Right(stats) => Right((session, stats))
              case Left(err) => session.close(); Left(err)
          }
        }.flatMap {
          case Right((session, stats)) =>
            session.close()
            val secretName = MailAccount.secretNameFor(addr)
            val account = MailAccount(
              id = accountIdFor(addr),
              address = addr,
              provider = preset.map(_.provider).getOrElse(""),
              imapHost = imapH,
              imapPort = imapP,
              smtpHost = smtpH,
              smtpPort = smtpP,
              smtpImplicitTls = implicitTls,
              displayName = displayName,
              trustedSenders = trustedSenders,
              enabled = true,
              authCodeSecret = secretName,
              createdAt = System.currentTimeMillis()
            )
            saveSecret(secretName, authCode).flatMap {
              case Left(err) => IO.pure(Left(err))
              case Right(_) =>
                for
                  _ <- accounts.update(list => list.filterNot(_.id == account.id) :+ account)
                  accs <- accounts.get
                  _ <- IO.blocking(store.saveAccounts(accs))
                  _ <- accountRuntime.update(m => m + (account.id -> AccountRuntime(EntryState.Idle)))
                  _ <- audit("state", "connected", s"addr=$addr provider=${account.provider}")
                  _ <- broadcastState()
                  _ <- pollAccount(account).attempt.void // immediate first sync
                yield Right((account, stats))
            }
          case Left(err) =>
            IO.pure(Left(readableConnectError(err, imapH, imapP, authCode)))
        }

  private def accountIdFor(address: String): String =
    address.toLowerCase.replaceAll("[^a-z0-9]", "").take(24)

  /** Write the auth code through the platform secrets store (F1 write order). */
  private def saveSecret(name: String, value: String): IO[Either[String, Unit]] =
    IO.blocking {
      val st = new nebflow.core.secrets.SecretsStore(PathUtil.dataRoot / "secrets")
      val existing = os.exists(PathUtil.dataRoot / "secrets" / name)
      if existing then st.overwrite(name, value) else st.create(name, value)
    }.flatten.attempt.map {
      case Right(Right(_)) => Right(())
      case Right(Left(f))  => Left(s"secrets: ${f}")
      case Left(e)         => Left(s"secrets: ${e.getMessage}")
    }

  def readAuthCode(account: MailAccount): IO[Option[String]] =
    IO.blocking {
      val p = PathUtil.dataRoot / "secrets" / account.authCodeSecret
      if account.authCodeSecret.nonEmpty && os.exists(p) then Some(os.read(p).trim)
      else None
    }.attempt.map(_.toOption.flatten)

  def toggleAccount(id: String, enabled: Boolean): IO[Either[String, Unit]] =
    for
      _ <- accounts.update(_.map(a => if a.id == id then a.copy(enabled = enabled) else a))
      accs <- accounts.get
      _ <- IO.blocking(store.saveAccounts(accs))
      _ <- accountRuntime.update(m =>
        m.get(id).map(r => m + (id -> r.copy(state = if enabled then EntryState.Connecting else EntryState.Paused)))
          .getOrElse(m)
      )
      _ <- audit("state", if enabled then "resumed" else "paused", s"id=$id")
      _ <- broadcastState()
    yield Right(())

  def removeAccount(id: String): IO[Either[String, Unit]] =
    for
      accs <- accounts.get
      victim = accs.find(_.id == id)
      _ <- victim.traverse_(a =>
        IO.blocking {
          val p = PathUtil.dataRoot / "secrets" / a.authCodeSecret
          if a.authCodeSecret.nonEmpty && os.exists(p) then
            new nebflow.core.secrets.SecretsStore(PathUtil.dataRoot / "secrets").delete(a.authCodeSecret)
          ()
        }.attempt.void
      )
      _ <- accounts.update(_.filterNot(_.id == id))
      accs2 <- accounts.get
      _ <- IO.blocking(store.saveAccounts(accs2))
      // 残留清理（r1 §11 删除授权语义）：内存 runtime + state.json 账户条目一并
      // 清除（会话状态不跨删除残留；重连同地址 = 全新会话，首连只记 maxUID）。
      _ <- accountRuntime.update(m => m - id)
      _ <- IO.blocking {
        val st = store.loadState()
        store.saveState(st - id)
        ()
      }
      _ <- audit("state", "removed", s"id=$id residual=state+runtime+secret")
      _ <- broadcastState()
    yield Right(())

  /** Test seam: register an account without touching real IMAP/secrets. */
  def testInjectAccount(account: MailAccount): IO[Unit] =
    for
      _ <- accounts.update(list => list.filterNot(_.id == account.id) :+ account)
      _ <- accountRuntime.update(m => m + (account.id -> AccountRuntime(EntryState.Idle)))
      _ <- broadcastState()
    yield ()

  // ── panel projection ──────────────────────────────────────────────────

  def entriesPayload: IO[EntriesPayload] =
    for
      accs <- accounts.get
      rt <- accountRuntime.get
      now <- IO(System.currentTimeMillis())
      entries = accs.map { a =>
        val r = rt.getOrElse(a.id, AccountRuntime())
        val (state, summary, notice): (EntryState, String, String) =
          if !a.enabled then (EntryState.Paused, s"${a.address} · 已暂停", "")
          else r.state match
            case EntryState.AuthFailed =>
              (EntryState.AuthFailed, s"${a.address} · 授权失效", "授权码已失效，请重新连接邮箱")
            case EntryState.Backoff =>
              // r3 裁决 2（过程态全隐）：无次数、无倒计时、无转轮询——只说人话给动作。
              (EntryState.Backoff, s"${a.address} · 正在自动恢复", "正在自动恢复，新邮件先存本机，恢复后即刻补上")
            case EntryState.Connecting =>
              (EntryState.Connecting, s"${a.address} · 连接中", "")
            case _ =>
              (EntryState.Idle, s"${a.address} · 已连接", "")
        MailEntry(
          id = a.id,
          icon = "mail",
          name = "邮件",
          state = state,
          summary = summary,
          notice = notice,
          actions = List("config", "toggle")
        )
      }
      payload = EntriesPayload(entries, now)
    yield payload

  def broadcastState(): IO[Unit] =
    entriesPayload.flatMap(p =>
      broadcast(Json.obj("type" -> "perception.state.changed".asJson, "entries" -> p.entries.asJson))
    )

  // ── drafts / send ─────────────────────────────────────────────────────

  /** Any connected (configured) account present? */
  def hasAccount: IO[Boolean] = accounts.get.map(_.nonEmpty)

  /** Bind a snapshot-card requestId onto a draft record (receipt/close linkage). */
  def attachRequest(draftId: String, requestId: String): IO[Unit] =
    drafts.update(_.map(d => if d.draftId == draftId then d.copy(requestId = requestId) else d))

  def createDraft(doc: DraftCodec.DraftDoc): IO[Either[String, DraftRecord]] =
    for
      now <- IO(System.currentTimeMillis())
      draftId = s"draft-$now-${PerceptionStore.rand4()}"
      content = DraftCodec.serialize(doc)
      pathE <- IO.blocking(store.writeDraftFile(draftId, content))
      // snapshotAt = 落盘后文件 mtime（快照基线与文件状态严格对齐——严格式
      // stale 判据 `mtime > snapshotAt` 在创建时刻恒 false，编辑后才置真）。
      snapshotAt = pathE.toOption
        .flatMap(p => try Some(java.nio.file.Files.getLastModifiedTime(java.nio.file.Path.of(p)).toMillis)
        catch case _: Throwable => None)
        .getOrElse(now)
      rec = DraftRecord(
        draftId = draftId,
        path = pathE.getOrElse(""),
        createdAt = now,
        snapshotAt = snapshotAt,
        state = "pending",
        toSummary = doc.to.mkString(", ").take(200),
        subject = doc.subject.take(200),
        attachmentCount = doc.attachments.size
      )
      _ <- drafts.update(_ :+ rec)
      all <- drafts.get
      _ <- IO.blocking(store.saveDrafts(all))
      _ <- audit("send", "draft-created", s"draftId=$draftId to=${rec.toSummary}")
    yield pathE.map(_ => rec)

  case class DraftView(record: DraftRecord, content: String, mtimeMs: Long, stale: Boolean, doc: Either[String, DraftCodec.DraftDoc], attachmentStats: List[AttachmentStat] = Nil)

  /** 附件卡面统计（路径在位性 + 人读大小；快照卡/画布 chip 的数据源）。 */
  case class AttachmentStat(path: String, name: String, exists: Boolean, sizeBytes: Long)

  object AttachmentStat:
    given io.circe.Encoder[AttachmentStat] = io.circe.generic.semiauto.deriveEncoder

  def attachmentStats(paths: List[String]): List[AttachmentStat] =
    paths.map { p =>
      val f = java.nio.file.Path.of(p)
      val exists =
        try java.nio.file.Files.isRegularFile(f)
        catch case _: Throwable => false
      val size = if exists then try java.nio.file.Files.size(f) catch case _: Throwable => 0L else 0L
      AttachmentStat(p, p.replaceAll(".*/", ""), exists, size)
    }

  def draftView(draftId: String): IO[Option[DraftView]] =
    for
      all <- drafts.get
      rec = all.find(_.draftId == draftId)
      view <- rec.traverse { r =>
        for
          content <- IO.blocking(store.readDraftFile(r.path)).map(_.getOrElse(""))
          mtime <- IO.blocking {
            try java.nio.file.Files.getLastModifiedTime(java.nio.file.Path.of(r.path)).toMillis
            catch case _: Throwable => r.snapshotAt
          }
          doc = DraftCodec.parse(r.draftId, content)
          stats <- IO.blocking(attachmentStats(doc.fold(_ => Nil, _.attachments)))
        yield DraftView(r, content, mtime, mtime > r.snapshotAt, doc, stats)
      }
    yield view

  def saveDraft(draftId: String, content: String): IO[Either[String, Long]] =
    for
      all <- drafts.get
      rec = all.find(_.draftId == draftId)
      res <- rec match
        case None => IO.pure(Left("draft not found"))
        case Some(r) if r.state == "sent" || r.state == "abandoned" => IO.pure(Left(s"draft already ${r.state}"))
        case Some(r) =>
          IO.blocking(store.writeDraftFile(r.draftId, content)).flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right(_) =>
              val mtime = try java.nio.file.Files.getLastModifiedTime(java.nio.file.Path.of(r.path)).toMillis
              catch case _: Throwable => System.currentTimeMillis()
              IO.pure(Right(mtime))
          }
      _ <- res.traverse_(_ =>
        broadcast(Json.obj("type" -> "perception.draft.updated".asJson, "draftId" -> draftId.asJson))
      )
    yield res

  /** 画布「发送」与快照卡「照此发送」同一端点（snapshot=true 时一致性守则 409）。 */
  def sendDraft(draftId: String, snapshot: Boolean): IO[Either[SendFailure, DraftRecord]] =
    drafts.get.flatMap { all =>
      all.find(_.draftId == draftId) match
        case None => IO.pure(Left(SendFailure.Invalid("草稿不存在或已过期")))
        case Some(r) if r.state == "sent" => IO.pure(Left(SendFailure.Invalid("该邮件已发送")))
        case Some(r) if r.state == "abandoned" => IO.pure(Left(SendFailure.Invalid("该草稿已放弃")))
        case Some(r) =>
          val mtimeIO = IO.blocking {
            try java.nio.file.Files.getLastModifiedTime(java.nio.file.Path.of(r.path)).toMillis
            catch case _: Throwable => r.snapshotAt
          }
          mtimeIO.flatMap { mtime =>
            // 快照一致性守则（r2 §7，严格式）：画布保存 mtime > 快照时刻 ⇒ 409。
            val stale = snapshot && mtime > r.snapshotAt
            val outcome: IO[Either[SendFailure, DraftRecord]] =
              if stale then IO.pure(Left(SendFailure.Stale))
              else doSend(r)
            outcome.flatMap {
              case Right(sent) =>
                for
                  _ <- drafts.update(_.map(d => if d.draftId == draftId then sent else d))
                  upd <- drafts.get
                  _ <- IO.blocking(store.saveDrafts(upd))
                  _ <- audit("send", "sent", s"draftId=$draftId mid=${sent.messageId.take(80)}")
                  _ <- broadcast(Json.obj(
                    "type" -> "perception.draft.sent".asJson,
                    "draftId" -> draftId.asJson,
                    "messageId" -> sent.messageId.asJson,
                    "sentAt" -> sent.sentAt.asJson
                  ))
                yield Right(sent)
              case Left(f) =>
                audit("send", "failed", s"draftId=$draftId reason=${SendFailure.readable(f)}").as(Left(f))
            }
          }
    }

  /**
   * 附件组装（r2 §7.2「发送时按行解析」+ §7.3 校验）：路径逐个读盘；任一不存在
   * ⇒ MissingAttachment（逐路径列出，发送前置拒绝——服务端兜底，UI 侧同判据）。
   */
  def prepareAttachments(paths: List[String]): Either[PerceptionModel.SendFailure.MissingAttachment, List[(String, Array[Byte])]] =
    val stats = attachmentStats(paths)
    val missing = stats.filterNot(_.exists).map(_.path)
    if missing.nonEmpty then Left(PerceptionModel.SendFailure.MissingAttachment(missing))
    else
      try Right(stats.map(s => s.name -> java.nio.file.Files.readAllBytes(java.nio.file.Path.of(s.path))))
      catch case _: Throwable => Left(PerceptionModel.SendFailure.MissingAttachment(paths))

  private def doSend(r: DraftRecord): IO[Either[SendFailure, DraftRecord]] =
    accounts.get.flatMap { accs =>
      IO.blocking(store.readDraftFile(r.path)).flatMap { contentE =>
        val doc = DraftCodec.parse(r.draftId, contentE.getOrElse(""))
        (accs.headOption, doc) match
          case (None, _) => IO.pure(Left(SendFailure.NoAccount))
          case (Some(_), Left(err)) => IO.pure(Left(SendFailure.Invalid(s"草稿损坏：$err")))
          case (Some(account), Right(d)) =>
            if d.to.isEmpty then IO.pure(Left(SendFailure.Invalid("收件人为空")))
            else
              IO.blocking(prepareAttachments(d.attachments)).flatMap {
                case Left(missing) => IO.pure(Left(missing))
                case Right(atts) =>
                  readAuthCode(account).flatMap {
                    case None => IO.pure(Left(SendFailure.AuthFailed))
                    case Some(code) =>
                      IO.blocking(MailClient.send(
                        smtpHost = account.smtpHost,
                        smtpPort = account.smtpPort,
                        implicitTls = account.smtpImplicitTls,
                        user = account.address,
                        authCode = code,
                        mail = MailClient.OutgoingMail(
                          from = account.address,
                          fromName = account.displayName.getOrElse(""),
                          to = d.to,
                          cc = d.cc,
                          bcc = d.bcc,
                          subject = d.subject,
                          bodyHtml = d.bodyHtml,
                          attachments = atts,
                          inReplyTo = d.replyToMessageId
                        )
                      )).map {
                        case Right(mid) =>
                          Right(r.copy(state = "sent", sentAt = System.currentTimeMillis(), messageId = mid))
                        case Left(f) => Left(f)
                      }
                  }
              }
      }
    }

  def abandonDraft(draftId: String): IO[Either[String, Unit]] =
    drafts.get.flatMap { all =>
      all.find(_.draftId == draftId) match
        case None => IO.pure(Left("draft not found"))
        case Some(r) if r.state != "pending" => IO.pure(Left(s"draft already ${r.state}"))
        case Some(r) =>
          for
            _ <- IO.blocking(store.deleteDraftFile(r.path))
            _ <- drafts.update(_.map(d => if d.draftId == draftId then d.copy(state = "abandoned") else d))
            upd <- drafts.get
            _ <- IO.blocking(store.saveDrafts(upd))
            _ <- audit("send", "abandoned", s"draftId=$draftId")
            _ <- broadcast(Json.obj("type" -> "perception.draft.updated".asJson, "draftId" -> draftId.asJson, "state" -> "abandoned".asJson))
          yield Right(())
    }

  // ── audit ─────────────────────────────────────────────────────────────

  def audit(stage: String, reason: String, detail: String): IO[Unit] =
    IO.blocking(store.appendAudit(AuditLine(System.currentTimeMillis(), stage, reason, detail)))

end PerceptionCore
