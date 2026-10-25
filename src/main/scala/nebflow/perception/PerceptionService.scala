package nebflow.perception

import PerceptionModel.*
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.SharedResources
import nebflow.shared.{NebflowLogger, PathUtil}
import os.Path

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 感知守护进程（裁决 4：独立生命周期——不挂在任何会话/流之下，随网关进程
 * 启停；引擎会话消亡不影响收信，信件入 spool 回放）。
 *
 * 职责：IMAP 自适应轮询（OTP 窗开 15s / 常态 60s）→ L0 管线（黑名单略/
 * 白名单直投/OTP 单码槽/账单缓冲）→ 投递（root 会话 ImmediateInput，不可达
 * 入 spool）→ 账单日结（HH:mm 攒批注入，零 UI）→ 草稿/发送面 → 审计。
 *
 * 全部状态在 Ref；单例 object（网关进程一份），spec 经 `new PerceptionCore`
 * 走纯逻辑面。WS 广播经窄端口注入（`broadcast: Json => IO[Unit]`，GatewayMain
 * 接线先例同款）。
 */
object PerceptionService:

  private val logger = NebflowLogger.forName("nebflow.perception")

  /** 固定裁决值（r3 假设 5：三项零 UI 锁定值）。 */
  val OtpWindowSec: Int = 600
  val BillingDigestHour: Int = 9
  val PollIntervalIdleSec: Int = 60
  val PollIntervalWindowSec: Int = 15
  val BackoffBaseSec: Int = 2
  val BackoffMaxSec: Int = 300

  // ── singleton runtime ─────────────────────────────────────────────────

  private sealed trait Phase
  private case object Cold extends Phase
  private case class Hot(
    core: PerceptionCore,
    loops: List[cats.effect.Fiber[IO, Throwable, Unit]]
  ) extends Phase

  private val phase: Ref[IO, Phase] = Ref.unsafe[IO, Phase](Cold)

  /** Boot once (idempotent). Called from GatewayMain's startup chain. */
  def boot(broadcast: Json => IO[Unit], resources: SharedResources): IO[Unit] =
    phase.get.flatMap:
      case Hot(_, _) => IO.unit
      case Cold      => doBoot(broadcast, resources).void

  /** Routes-side defensive idempotent boot; returns the live core. */
  def ensureBoot(broadcast: Json => IO[Unit], resources: SharedResources): IO[PerceptionCore] =
    phase.get.flatMap:
      case Hot(core, _) => IO.pure(core)
      case Cold         => doBoot(broadcast, resources)

  private def doBoot(broadcast: Json => IO[Unit], resources: SharedResources): IO[PerceptionCore] =
    val core = new PerceptionCore(
      store = new PerceptionStore(PerceptionStore.defaultRoot),
      resources = resources,
      broadcast = broadcast
    )
    for
      _ <- core.bootInitialize
      poll <- core.pollLoop.start
      sweeper <- core.sweepLoop.start
      digest <- core.digestLoop.start
      spool <- core.spoolLoop.start
      _ <- phase.set(Hot(core, List(poll, sweeper, digest, spool)))
      _ <- logger.info("perception daemon booted (poll/sweep/digest/spool loops live)")
    yield core

  /** Test/panel access to the live core (None before boot). */
  def coreNow: IO[Option[PerceptionCore]] =
    phase.get.map:
      case Hot(core, _) => Some(core)
      case Cold         => None

end PerceptionService

/**
 * The perception core: all state + flows. Constructed once by
 * [[PerceptionService.boot]]; tests construct directly with a temp root.
 */
class PerceptionCore(
  val store: PerceptionStore,
  resources: SharedResources,
  broadcast: Json => IO[Unit]
):
  import PerceptionService.*

  private val logger = NebflowLogger.forName("nebflow.perception")

  val accounts: Ref[IO, List[MailAccount]] = Ref.unsafe(Nil)
  val accountRuntime: Ref[IO, Map[String, AccountRuntime]] = Ref.unsafe(Map.empty)
  val windows: Ref[IO, Map[String, OtpWindow]] = Ref.unsafe(Map.empty)
  val drafts: Ref[IO, List[DraftRecord]] = Ref.unsafe(Nil)
  private val booted = Ref.unsafe[IO, Boolean](false)

  case class AccountRuntime(
    state: EntryState = EntryState.Unconfigured,
    lastError: String = "",
    nextAttemptAt: Long = 0L,
    backoffAttempt: Int = 0
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

  /** Adaptive poll: window open → 15s, else 60s (recursive sleep loop). */
  def pollLoop: IO[Unit] =
    for
      _ <- pollOnce.attempt.void
      sec <- pollIntervalSec
      _ <- IO.sleep(scala.concurrent.duration.Duration(sec.toLong, "seconds"))
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
    val stateMap = store.loadState()
    val st = stateMap.getOrElse(account.id, PerceptionStore.AccountState())
    for
      authCode <- readAuthCode(account).flatMap {
        case Some(v) => IO.pure(v)
        case None => IO.raiseError(new java.lang.IllegalStateException("auth code missing"))
      }
      result <- IO.blocking {
        val conn = MailClient.connectImap(account.imapHost, account.imapPort)
        conn.flatMap { session =>
          // pure Either flow inside the blocking lane (session is synchronous)
          val flow: Either[String, List[IncomingMail]] = for
            _ <- session.login(account.address, authCode)
            _ <- session.selectInbox()
            uids <- session.searchUids(st.lastSeenUid)
            // first sync: only record the max UID (history is not backfilled)
            toFetch = if st.lastSeenUid <= 0 then Nil else uids
            mails <- toFetch.foldLeft[Either[String, List[IncomingMail]]](Right(Nil)) { (acc, uid) =>
              acc.flatMap(list => session.fetchMessage(uid).map(raw => list :+ MailClient.toIncomingMail(uid, raw)))
            }
            _ = session.logout()
          yield mails
          flow match
            case Right(mails) => session.close(); Right(mails)
            case Left(err)    => session.close(); Left(err)
        }
      }.flatMap {
        case Right(mails) =>
          recordOk(account) *>
            (if mails.isEmpty then IO.unit else processMails(account, mails))
        case Left(err) =>
          recordFailure(account, err)
      }
    yield ()

  /** New mail pipeline (L0). Order: OTP window hit → trusted delivery → billing → drop. */
  def processMails(account: MailAccount, mails: List[IncomingMail]): IO[Unit] =
    val ordered = mails.sortBy(_.uid)
    ordered.traverse_ { mail =>
      val uidKey = account.id
      for
        _ <- audit("L0", s"mail-from:${mail.fromDomain}", s"uid=${mail.uid} subjectMasked=${maskSubject(mail.subject)}")
        windowsNow <- windows.get
        liveWindows = windowsNow.values.filter(w => w.expiresAt > System.currentTimeMillis()).toList
        otpHit <-
          if liveWindows.isEmpty then IO.pure(None)
          else IO.pure(OtpExtractor.extract(mail.subject, mail.bodyText).headOption)
        _ <- otpHit match
          case Some(cand) => deliverOtpHit(liveWindows, mail, cand)
          case None => IO.unit
        _ <-
          if otpHit.isDefined then IO.unit // OTP 邮件不进投递/账单面
          else if isBlocked(mail) then audit("L0", "blocked", s"uid=${mail.uid}")
          else if isTrusted(account, mail) then deliverMail(mail)
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
                // L1（LLM 重要性分类）本批为启发式占位：非白名单/非账单/非 OTP 不投递
                audit("L0", "held-no-pipeline", s"uid=${mail.uid} reason=unmatched")
        _ <- advanceUid(account, mail.uid)
      yield ()
    }

  private def maskSubject(s: String): String =
    if s.length > 24 then s.take(24) + "…" else s

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
        val delaySec = math.min(BackoffBaseSec * (1L << math.min(attempt, 8)), BackoffMaxSec)
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

  /** v1 无黑名单 UI（r3 高级折叠裁撤白名单外显）；占位恒 false，文件面留扩展位。 */
  def isBlocked(mail: IncomingMail): Boolean = false

  // ── delivery（ImmediateInput + spool 回放）───────────────────────────

  def deliverMail(mail: IncomingMail): IO[Unit] =
    val envelope = DeliveryEnvelope(
      priority = "normal",
      text = s"【感知 · 新邮件】发件人 ${mail.from}\n主题：${mail.subject}\n\n${mail.bodyText.take(4000)}"
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

  /** Inject an envelope into the live root session; spool when unreachable. */
  def injectEnvelope(env: DeliveryEnvelope, dedupKey: String): IO[Boolean] =
    nebflow.core.tools.MailTool.resolveRoots(resources, senderSessionId = "", preferredRootSid = None).flatMap {
      case List((sid, ref)) =>
        (ref ! nebflow.actor.AgentCommand.ImmediateInput(
          text = env.text,
          source = Some("perception"),
          eventType = Some(s"perception.${env.priority}"),
          sender = Some("感知"),
          fromUser = false
        )).as(true)
      case _ =>
        IO.blocking(store.spoolWrite(env)).flatMap {
          case Right(id) => audit("deliver", "spooled", s"key=$dedupKey spoolId=$id").as(false)
          case Left(err) => audit("deliver", "spool-failed", s"key=$dedupKey err=$err").as(false)
        }
    }

  def replaySpool(): IO[Unit] =
    for
      items <- IO.blocking(store.spoolList())
      _ <- items.traverse_ { (id, env) =>
        injectEnvelope(env, s"spool:$id").flatMap {
          case true  => IO.blocking(store.spoolRemove(id)) *> audit("deliver", "spool-replayed", s"spoolId=$id")
          case false => IO.unit
        }
      }
    yield ()

  // ── OTP windows ───────────────────────────────────────────────────────

  def openOtpWindow(requestId: String, windowSec: Int = OtpWindowSec): IO[OtpWindow] =
    for
      now <- IO(System.currentTimeMillis())
      w = OtpWindow(
        windowId = s"otpw-$now-${PerceptionStore.rand4()}",
        requestId = requestId,
        openedAt = now,
        expiresAt = now + windowSec * 1000L
      )
      _ <- windows.update(m => m + (w.windowId -> w))
      _ <- audit("otp", "window-open", s"requestId=$requestId sec=$windowSec")
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
  ): IO[Either[String, MailAccount]] =
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
              _ <- session.selectInbox()
            yield session
            flow match
              case Right(s) => Right(s)
              case Left(err) => session.close(); Left(err)
          }
        }.flatMap {
          case Right(session) =>
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
                yield Right(account)
            }
          case Left(err) =>
            val readable =
              if err.contains("LOGIN") || err.toLowerCase.contains("invalid") || err.contains("AUTH") then
                "授权码无效 · 请确认已开启 IMAP 并使用授权码（非登录密码）"
              else if err.contains("connect") || err.contains("UnknownHost") then
                s"无法连接 $imapH:$imapP · 请检查网络"
              else err.take(160)
            IO.pure(Left(readable))
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
      _ <- audit("state", "removed", s"id=$id")
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
              val waitSec = math.max(0, (r.nextAttemptAt - now) / 1000).toInt
              (EntryState.Backoff, s"${a.address} · 正在自动恢复", s"连接中断 · 稍后自动重试（约 ${waitSec}s），新邮件会先存本机")
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

  case class DraftView(record: DraftRecord, content: String, mtimeMs: Long, stale: Boolean, doc: Either[String, DraftCodec.DraftDoc])

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
        yield DraftView(r, content, mtime, mtime > r.snapshotAt, doc)
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

  private def doSend(r: DraftRecord): IO[Either[SendFailure, DraftRecord]] =
    accounts.get.flatMap { accs =>
      IO.blocking(store.readDraftFile(r.path)).flatMap { contentE =>
        val doc = DraftCodec.parse(r.draftId, contentE.getOrElse(""))
        (accs.headOption, doc) match
          case (None, _) => IO.pure(Left(SendFailure.NoAccount))
          case (Some(_), Left(err)) => IO.pure(Left(SendFailure.Invalid(s"草稿损坏：$err")))
          case (Some(account), Right(d)) =>
            if d.to.isEmpty then IO.pure(Left(SendFailure.Invalid("收件人为空")))
            else if d.attachments.nonEmpty then
              IO.pure(Left(SendFailure.Invalid("附件发送本批未开放 · 请先移除附件")))
            else
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
                      attachments = Nil,
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
