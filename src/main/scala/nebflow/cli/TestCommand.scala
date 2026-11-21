package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.tests.SourceSnapshot
import nebflow.shared.PathUtil

/**
 * `stand test` —— 测试输入面的 CLI（M2：快照冻结 / 清单校验 / 独占执行槽）。
 *
 * 本批只做**输入侧**四件事，不执行任何测试（执行与回传是 M3/M4，尚未立项）：
 *
 *   - `stand test snapshot [--root PATH] [--include a,b] [--json]`
 *     冻结当前工作区的字节面，打印快照 ID（= 清单根摘要）、条目数、字节数。同一内容
 *     重复捕获得到同一个 ID 且不再重写对象；内容变了就是另一个 ID —— 不存在「静默复用
 *     旧快照」这条路（这正是判据①「两次摘要一致」与判据②「变更后摘要变化」的形态）。
 *   - `stand test verify <id>` 逐对象重算 sha256 与清单比对，不符时**点名文件**；
 *     目录名与清单摘要不一致（清单被改动）同样红。
 *   - `stand test diff <id> [--root PATH]` 现有工作区 vs 快照：changed / added / removed。
 *     这是「重跑原版本」与「验证当前改动」两种取法的显式分叉，不互相顶替。
 *   - `stand test slot acquire|release|status [--task NAME]` 独占执行槽：原子抢占，
 *     第二个并发提交拿**明确的拒绝** + 持有者读数（退出码 3），不排队。
 *
 * 落位：与 `secrets`/`health` 同族——**纯本机命令**，因此在 `CliRouter` 的
 * `OfflineSubcommands` 里登记（网关不在时恰恰更该可用）。快照落
 * `<dataRoot>/snapshots/<id>/{manifest.json, objects/<sha256>}`，清单写入走 `AtomicJson`。
 */
object TestCommand extends CliCommand:
  def name = "test"
  def description = "Test inputs: freeze a source snapshot, verify its manifest, claim the exclusive execution slot"
  def subcommands = List(TestSnapshot, TestVerify, TestDiff, TestSlot)

  def examples = List(
    "stand test snapshot",
    "stand test snapshot --include notes.md,docs/plan.md",
    "stand test verify 3f9c1a",
    "stand test diff 3f9c1a",
    "stand test slot acquire --task e2e-selfcontained",
    "stand test slot status"
  )

  /** 槽被占用时的退出码：与「一般错误 1」分开，脚本能区分「忙」与「坏」。 */
  private[cli] val SlotBusyExit = 3

  private def rootOf(ctx: CliContext): os.Path =
    ctx.args.get("root").filter(_.nonEmpty) match
      case None => os.pwd
      case Some(raw) =>
        val path = PathUtil.normalizeSeparators(expand(raw))
        if PathUtil.isAbsolute(path) then os.Path(path) else os.pwd / os.RelPath(path)

  private def expand(path: String): String =
    if path == "~" then System.getProperty("user.home")
    else if path.startsWith("~/") then System.getProperty("user.home") + path.drop(1)
    else path

  private def includesOf(ctx: CliContext): List[String] =
    ctx.args.get("include").map(_.split(',').toList.map(_.trim).filter(_.nonEmpty)).getOrElse(Nil)

  private[cli] object TestSnapshot extends CliSubcommand:
    def name = "snapshot"
    def description = "Freeze the working directory's bytes into a content-addressed snapshot; print its id"

    def params = List(
      CliParam(name = "root", description = "Working tree to freeze (default: current directory)"),
      CliParam(
        name = "include",
        description = "Extra paths to include, comma separated (untracked files are NOT captured implicitly)"
      ),
      CliParam(name = "json", description = "Machine-readable output", isFlag = true)
    )

    def run(ctx: CliContext): IO[CliResult] =
      val root = rootOf(ctx)
      SourceSnapshot.capture(root, includesOf(ctx)).map {
        case Left(f) => CliResult.Error(f.message)
        case Right(ref) if ctx.json =>
          CliResult.Json(
            Json.obj(
              "id" -> ref.id.asJson,
              "entries" -> ref.entries.asJson,
              "bytes" -> ref.bytes.asJson,
              "root" -> root.toString.asJson,
              "manifest" -> ref.manifest.toString.asJson,
              "reused" -> ref.reused.asJson
            )
          )
        case Right(ref) =>
          CliResult.Text(
            List(
              s"snapshot ${ref.id}",
              s"  root     ${root}",
              s"  entries  ${ref.entries}",
              s"  bytes    ${ref.bytes}",
              s"  manifest ${ref.manifest}",
              if ref.reused then "  objects  reused (同内容快照已存在，未重写对象)" else "  objects  written"
            )
          )
      }

  private[cli] object TestVerify extends CliSubcommand:
    def name = "verify"
    def description = "Recompute every object's digest against the manifest; report the offending file"
    def params = List(CliParam(name = "json", description = "Machine-readable output", isFlag = true))

    def run(ctx: CliContext): IO[CliResult] =
      ctx.positionalArgs.headOption match
        case None => IO.pure(CliResult.Error("usage: stand test verify <snapshot-id>"))
        case Some(id) =>
          SourceSnapshot.verify(SourceSnapshot.defaultRoot, id).map {
            case Left(f) => CliResult.Error(f.message)
            case Right(report) if ctx.json =>
              CliResult.Json(
                Json.obj(
                  "id" -> report.id.asJson,
                  "entries" -> report.entries.asJson,
                  "objects" -> report.objects.asJson,
                  "bytes" -> report.bytes.asJson,
                  "ok" -> true.asJson
                )
              )
            case Right(report) =>
              CliResult.Text(
                List(
                  s"ok ${report.id}",
                  s"  entries ${report.entries} · objects ${report.objects} · bytes ${report.bytes}"
                )
              )
          }

  private[cli] object TestDiff extends CliSubcommand:
    def name = "diff"
    def description = "Compare the current working tree against a snapshot (changed / added / removed)"

    def params = List(
      CliParam(name = "root", description = "Working tree to compare (default: current directory)"),
      CliParam(name = "include", description = "Extra paths to include, comma separated")
    )

    def run(ctx: CliContext): IO[CliResult] =
      ctx.positionalArgs.headOption match
        case None => IO.pure(CliResult.Error("usage: stand test diff <snapshot-id> [--root PATH]"))
        case Some(id) =>
          val root = rootOf(ctx)
          SourceSnapshot.diff(root, SourceSnapshot.defaultRoot, id, includesOf(ctx)).map {
            case Left(f) => CliResult.Error(f.message)
            case Right(diff) if ctx.json =>
              CliResult.Json(
                Json.obj(
                  "id" -> id.asJson,
                  "changed" -> diff.changed.asJson,
                  "added" -> diff.added.asJson,
                  "removed" -> diff.removed.asJson
                )
              )
            case Right(diff) if diff.isEmpty =>
              CliResult.Text(List(s"same $id · 工作区与快照一致"))
            case Right(diff) =>
              CliResult.Text(
                List(
                  s"differs $id · changed=${diff.changed.size} added=${diff.added.size} removed=${diff.removed.size}"
                ) ++
                  diff.changed.take(20).map(p => s"  changed $p") ++
                  diff.added.take(20).map(p => s"  added   $p") ++
                  diff.removed.take(20).map(p => s"  removed $p")
              )
          }

  private[cli] object TestSlot extends CliSubcommand:
    def name = "slot"
    def description = "Exclusive execution slot: acquire / release / status (a second claim is refused, not queued)"
    def params = List(CliParam(name = "task", description = "Task label recorded in the slot lease"))

    private def slotRoot: os.Path = PathUtil.dataRoot / "test-slot"

    def run(ctx: CliContext): IO[CliResult] =
      ctx.positionalArgs.headOption.getOrElse("status") match
        case "acquire" =>
          val task = ctx.args.getOrElse("task", "stand-test")
          SourceSnapshot.ExecutionSlot.acquire(slotRoot, task).map {
            case Left(f) => CliResult.Error(f.message)
            case Right(SourceSnapshot.ExecutionSlot.Outcome.Acquired(lock, holder)) =>
              CliResult.Text(
                List(
                  s"acquired ${lock}",
                  s"  pid ${holder.pid} · task ${holder.task} · since ${holder.since}"
                )
              )
            case Right(SourceSnapshot.ExecutionSlot.Outcome.Busy(current, lock)) =>
              val who = current.map(h => s"pid ${h.pid} · task ${h.task} · since ${h.since}").getOrElse("持有者读数不可解析")
              CliResult.Error(
                s"slot busy: ${lock} 已被占用（$who）；本请求被明确拒绝，未排队",
                SlotBusyExit
              )
          }
        case "release" =>
          SourceSnapshot.ExecutionSlot.release(slotRoot).map {
            case true => CliResult.Text(List(s"released ${SourceSnapshot.ExecutionSlot.lockPath(slotRoot)}"))
            case false => CliResult.Error("slot release refused: 当前进程不是持有者（或槽本就空闲）")
          }
        case "status" =>
          IO.pure(
            SourceSnapshot.ExecutionSlot.status(slotRoot) match
              case Some(h) => CliResult.Text(List(s"held pid ${h.pid} · task ${h.task} · since ${h.since}"))
              case None => CliResult.Text(List(s"free ${SourceSnapshot.ExecutionSlot.lockPath(slotRoot)}"))
          )
        case other =>
          IO.pure(CliResult.Error(s"unknown slot action '$other' (acquire|release|status)"))
