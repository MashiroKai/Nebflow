package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import nebflow.actor.{AgentKind, AgentStatus}
import nebflow.core.AgentRegistryPort
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.NebflowLogger

/**
 * D12-b（delegate reset 零闸治理，作者裁定 2026-10-23；arch-review 批
 * `reports/20261022_232018_delegate-arch-review` D12 选项 b）——reset 系 git
 * 命令的审计事件。
 *
 * WHY：auto-all 顶档（启动默认，2026-09-12 作者令）下危险命令清单是**死信**
 * （`ToolReversibility.isReversible` 的 `AutoAll => true` 无条件放行，
 * `BashTool.FixedDangerousPatterns` 只在低档参与闸门）——就地腿 delegate 与根
 * 会话/人共享检出区座位，任意一方一条 `git reset --hard` 即把他方**未持久化**
 * 的进行中工作清盘，且案发前无任何留痕（案例⑦ T2）。作者裁定：不做硬确认闸
 * （选项 a 否），只落审计 + 派发面警示行（[[DelegateTool.assembleBrief]] 头部
 * 警示行）——保留「全放行」语义，消灭「无人知晓」。
 *
 * 审计面 = **既有审计 logger `nebflow.audit`**（`AgentControlTool.auditTeamAction`
 * 同族单点——Team kill 审计先例，随 stand.log 落盘）。每次 reset 系命令到达
 * BashTool 执行腿（权限闸之后、进程起跑之前）落**至少一行**：
 * {{{
 * AUDIT ts=<millis> event=git-reset caller=<sid> callerAgent=<agent>
 *       command="<首行截断>" cwd="<会话座椅>" target=<sid|none>
 *       [targetKind=Delegate] [targetStatus=<status>] [targetTask="<title>"]
 * }}}
 * 目标解析（best-effort）：在册 **live Delegate** 会话（DelegateRegistry 持久
 * 记录 × agentRegistry 活体键），其座位与调用者会话座椅**同检出区**（路径相等
 * 或互含；自带 worktree 的隔离座位只在其 worktree 内被波及——主检出区 reset
 * 不进链接 worktree）。逐目标一行；零目标 = 单行 `target=none`（reset 本身
 * 必须留痕，谁/何时/什么命令三条永不缺）。
 *
 * 失败语义（`RelayExecAudit` 同款）：审计写入失败（盘满/权限/注册表读失败）
 * **绝不影响命令执行**——吞掉异常 + WARN；「审计缺失」比「命令被审计挡住」
 * 危害小得多。零超时设计：不新增任何时间参数，解析面 = 一次小文件目录扫描。
 *
 * 已知边界（与 `FixedDangerousPatterns` git 段同域，出入注在 [[ResetPatterns]]）：
 * `git -C <path> reset --hard` 形态两处都匹配不到；`echo "git reset --hard"`
 * 之类字符串字面两处都会误报——一致性优先，扩面另批。
 */
object ResetAudit:

  /** 审计 logger 名（`nebflow.audit` —— AgentControlTool 审计同族）。 */
  val LoggerName: String = "nebflow.audit"

  private val logger = NebflowLogger.forName(LoggerName)

  /** 单行 command 摘要上限（首行截断，防多行/超长命令刷屏）。 */
  private val MaxCommandChars = 160

  /**
   * reset 系命令判别（报告 D12 四式；与 `BashTool.FixedDangerousPatterns` 的
   * git 段**同域**，两处出入注明：① 裸 `git checkout .`（无 `--` 分隔符）闸面
   * 匹配不到、审计面覆盖（报告点名 `checkout .`，审计非闸、误报代价低）；②
   * `branch -D` 审计面要求带分支名（裸 `-D` 非法命令），闸面只钉 main|master）。
   * 已知同隙（两处都匹配不到，不单方面收紧）：`git -C <path> reset --hard`。
   */
  val ResetPatterns: List[scala.util.matching.Regex] = List(
    """git\s+reset\s+--hard""".r,
    """git\s+checkout\s+(--\s*)?\.""".r,
    """git\s+clean\s+-f""".r,
    """git\s+branch\s+-D\s+\S+""".r
  )

  /** 纯判别（无副作用；BashTool 执行腿与 spec 共用同一份）。 */
  def isResetCommand(command: String): Boolean =
    command != null && ResetPatterns.exists(_.findFirstIn(command).isDefined)

  /**
   * 目标座位的极简投影（纯数据——DelegateRegistry.Record 只取判别四键；
   * spec 零盘依赖可构造）。
   */
  final case class DelegateSeat(id: String, cwd: String, worktree: Option[String], title: String)

  /** 路径归一：反斜杠 → 正斜杠、去尾分隔符（跨设备座位字符串可比）。 */
  private def norm(p: String): String =
    p.replace('\\', '/').stripSuffix("/")

  private def contains(a: String, b: String): Boolean =
    a == b || b.startsWith(a + "/") || a.startsWith(b + "/")

  /**
   * 同检出区判定（纯函数）：座位与 delegate 座位路径互含 = 同一工作树；
   * 自带 worktree 的 delegate 只在「调用者也坐进该 worktree」时被波及
   * （主检出区的 reset 不进链接 worktree——git 隔离语义）。
   */
  def sharesCheckout(seat: String, delegateCwd: String, delegateWorktree: Option[String]): Boolean =
    val s = norm(seat)
    val c = norm(delegateCwd)
    if s.isEmpty || c.isEmpty then false
    else if !contains(s, c) then false
    else
      delegateWorktree.map(norm) match
        case Some(wt) if wt.nonEmpty => s == wt || s.startsWith(wt + "/")
        case _                       => true

  /**
   * 目标解析（纯函数）：live × 同检出区 × 非调用者自身，按 id 稳序
   * （审计行顺序确定性）。座椅未知（None / 空）= 解析不出目标 ⇒ Nil
   * （上层落 `target=none` 单行，reset 本身仍留痕）。
   */
  def affectedTargets(
    seat: Option[String],
    callerSessionId: String,
    delegates: List[DelegateSeat],
    liveIds: Set[String]
  ): List[DelegateSeat] =
    seat.map(norm).filter(_.nonEmpty) match
      case None => Nil
      case Some(s) =>
        delegates
          .filter(d => d.id != callerSessionId && liveIds.contains(d.id))
          .filter(d => sharesCheckout(s, d.cwd, d.worktree))
          .sortBy(_.id)

  /** 引号包裹值：转义内部反斜杠/双引号、折叠换行（行式审计的单行不变量）。 */
  private def quote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\r\\n]+", " ") + "\""

  /**
   * 单行审计事件组装（纯函数；`target=None` ⇒ `target=none`——谁/何时/什么
   * 命令三条永不缺）。格式与 `AgentControlTool.auditTeamAction` 同族
   * （`AUDIT ts=… key=value …`）。
   */
  def line(
    command: String,
    callerSid: String,
    callerAgent: String,
    seat: Option[String],
    ts: Long,
    target: Option[(DelegateSeat, Option[AgentStatus])]
  ): String =
    // command 摘要 = 首行截断（多行命令只取首行——「首行截断」契约，换行不进行）。
    val cmdExcerpt = quote(command.linesIterator.nextOption().getOrElse(command).take(MaxCommandChars))
    val base = s"AUDIT ts=$ts event=git-reset caller=${callerSid} callerAgent=${quote(callerAgent)} " +
      s"command=$cmdExcerpt cwd=${quote(seat.getOrElse("-"))}"
    target match
      case None => base + " target=none"
      case Some((t, statusOpt)) =>
        base + s" target=${t.id} targetKind=Delegate targetStatus=${statusOpt.map(_.toString).getOrElse("unknown")} " +
          s"targetTask=${quote(t.title)}"

  /**
   * 审计入口（BashTool 执行腿唯一调用点）：解析 + 组装 + 逐行发射。
   *
   * @param emit   发射面（缺省 = `nebflow.audit` logger.info；spec 注入收集器
   *               验证「写入」本体）。**单行失败不中断其余行**（逐行 handleError）。
   * @return 永不 raise（失败语义：吞掉异常 + WARN，绝不影响命令执行）。
   */
  def record(
    command: String,
    ctx: ToolContext,
    runtime: Option[AgentRegistryPort],
    ts: Long = System.currentTimeMillis(),
    emit: String => IO[Unit] = s => IO(logger.info(s))
  ): IO[Unit] =
    val callerSid = ctx.sessionId.getOrElse("-")
    val callerAgent = ctx.agentDef.map(_.name).orElse(ctx.sessionName).getOrElse("-")
    val seat = ctx.sessionCwd.filter(_.nonEmpty)

    val targetsIo: IO[(List[DelegateSeat], Map[String, AgentStatus])] = runtime match
      case None => IO.pure((Nil, Map.empty))
      case Some(port) =>
        IO.blocking(DelegateRegistry.list())
          .map(rows => rows.map(r => DelegateSeat(r.id, r.cwd, r.worktree, r.title)))
          .handleError { e =>
            logger.warn(s"ResetAudit: delegate registry scan failed (audit continues, targets unresolved): ${e.getMessage}")
            Nil
          }
          .flatMap { seats =>
            port.agentRegistry.get
              .map { reg =>
                val live = reg.collect { case (id, rec) if rec.kind == AgentKind.Delegate => id }.toSet
                val statuses = reg.collect { case (id, rec) => id -> rec.status }
                (affectedTargets(seat, callerSid, seats, live), statuses)
              }
              .handleError { e =>
                logger.warn(s"ResetAudit: agent registry read failed (audit continues, targets unresolved): ${e.getMessage}")
                (Nil, Map.empty)
              }
          }

    targetsIo.flatMap { case (targets, statuses) =>
      val lines: List[String] =
        if targets.isEmpty then List(line(command, callerSid, callerAgent, seat, ts, target = None))
        else
          targets.map(t => line(command, callerSid, callerAgent, seat, ts, target = Some((t, statuses.get(t.id)))))
      lines.traverse_(l => emit(l).handleErrorWith(e => IO(logger.warn(s"ResetAudit: emit failed (command unaffected): ${e.getMessage}"))))
    }
      .handleErrorWith(e => IO(logger.warn(s"ResetAudit: audit failed (command unaffected): ${e.getMessage}")))
end ResetAudit
