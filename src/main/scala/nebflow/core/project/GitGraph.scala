/* gitviz-mvp 批（2026-11-19）——项目面板次级面板「Git 可视化」的**只读**数据面。
 *
 * 设计源：`<dataRoot>/projects/stand/.stand/docs/git-viz-merge-research-design__20261119_123007.md`
 * §七「项目面板次级面板」的数据来源表（端点声明 = web/js/nodeData.js 的 API.gitGraph；
 * 后端挂载点 = ProjectsRoutes.routes）+ §八「新增面收敛为两件：一条只读路由、一个只读
 * SVG 页签」。本文件即那条只读路由的唯一实现（P0 ①）。
 *
 * 🔴 零写面（本文件的硬不变量，改本文件必须先读这一段）：
 *   · 允许的 git 子命令**只有** [[ReadOnlySubcommands]] 里那五条（全是只读读面）；
 *     调用点只能经 [[git]] 取用，越界子命令直接返回 Left，不做任何尝试。
 *   · 本文件不建分支、不 checkout、不 merge、不 fetch、不写工作区、不写 `.git/`；
 *     合并/删分支/变基一律不在面板侧发生（设计稿 §七「权限口径」表：写操作唯一执行者
 *     = root，经既有合并通道）。
 *   · 无缓存、无落盘：每次请求现读现算，进程内不保留任何仓库快照。
 *
 * 泳道算法（与前端同契约；参考实现见工作树的 tools/gitviz-dev/git-graph.mjs）： *   ① 每支按泳道序认领：0 = HEAD 所在支先认领它能到达的**全部**提交（基线因此是一条
 *      连续泳道，含与其余支共用的祖先），其余支再认领各自独有的段 ⇒ 图上读到的是
 *      「一条基线 + 若干分叉」，而不是「谁先遍历到谁当主线」；
 *   ② 只在本窗内认领（窗 = 一次 `git log --date-order -n <limit>` 的结果）；
 *   ③ 逐个 ref tip 兜底补取：多支共用 `-n` 时窗口按提交时间切，tip 更旧的支会被整段
 *      切掉 ⇒ 图上画不出那条支（实测过的真实缺陷，禁止删掉这段兜底）。
 */
package nebflow.core.project

import io.circe.Json
import io.circe.syntax.*

import java.security.MessageDigest
import java.time.{Instant, ZoneId}
import java.util.concurrent.TimeUnit

/**
 * 可见性 = `private[nebflow]`（第二轮 2026-11-19 放宽，原 `private[gateway]`）：
 *  分支管理问答的只读取数件 `core/tools/GitVizGraphTool` 必须走**同一个** `build`
 *  （作者令「数据面沿用只读子命令白名单」——白名单在下面一张表里，绝不能出现第二份
 *  实现）。放宽到 `nebflow` 包内 = core 可见、包外仍不可见；本文件仍是唯一取数口。
 */
private[nebflow] object GitGraph:

  /** 允许调用的 git 子命令（唯一取用口 = [[git]]；不在表内一律拒绝）。 */
  private val ReadOnlySubcommands =
    Set("rev-parse", "for-each-ref", "log", "rev-list", "merge-base")

  private val FieldSep = '\u001f' // git `%x1f`
  private val RecordSep = '\u001e' // git `%x1e`

  private final case class Commit(sha: String, parents: List[String], ts: Long, author: String, subject: String)
  private final case class RefRow(name: String, tip: String, ts: Long, isHead: Boolean)

  private def short(sha: String): String = sha.take(8)

  private def isoDay(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate.toString

  /** 跑一条**只读** git 命令：`git -C <root> <args...>`，返回 stdout（失败即 Left）。 */
  private def git(root: String, args: List[String], timeoutMs: Long = 30000L): Either[String, String] =
    val head = args.headOption.getOrElse("")
    if !ReadOnlySubcommands.contains(head) then Left(s"refused non-read-only git subcommand: $head")
    else
      try
        val pb = new java.lang.ProcessBuilder(("git" :: "-C" :: root :: args)*)
        pb.redirectErrorStream(false)
        val proc = pb.start()
        try
          val outBytes = proc.getInputStream.readAllBytes()
          val exited = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
          if !exited then
            proc.destroyForcibly()
            Left(s"git $head timed out after ${timeoutMs}ms")
          else if proc.exitValue() != 0 then Left(s"git $head exited ${proc.exitValue()}")
          else Right(new String(outBytes, java.nio.charset.StandardCharsets.UTF_8))
        finally proc.destroy()
      catch case e: Exception => Left(s"git $head failed: ${e.getMessage}")
    end if
  end git

  private def gitOk(root: String, args: List[String]): String = git(root, args).getOrElse("")

  /** 本地分支清单（真实读数；`--sort=-committerdate`，HEAD 所在支被 `%(HEAD)` 标星）。 */
  private def localBranches(root: String): List[RefRow] =
    val fmt = s"%(refname:short)$FieldSep%(objectname)$FieldSep%(committerdate:unix)$FieldSep%(HEAD)"
    gitOk(root, List("for-each-ref", "--sort=-committerdate", s"--format=$fmt", "refs/heads"))
      .split("\n")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { line =>
        val parts = line.split(FieldSep).map(_.trim)
        if parts.length < 3 then Nil
        else
          List(
            RefRow(parts(0), parts(1), parts(2).toLongOption.map(_ * 1000L).getOrElse(0L), parts.lift(3).contains("*"))
          )
      }
      .toList

  end localBranches

  /** 提交 sha → 全部 ref 名（本支 + 远端支 + tag）。 */
  private def refLabels(root: String): Map[String, List[String]] =
    val fmt = s"%(objectname)$FieldSep%(refname:short)"
    gitKgLabels(gitOk(root, List("for-each-ref", s"--format=$fmt", "refs/heads", "refs/remotes", "refs/tags")))

  private def gitKgLabels(out: String): Map[String, List[String]] =
    out
      .split("\n")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { line =>
        val parts = line.split(FieldSep).map(_.trim)
        if parts.length < 2 then Nil else List(parts(0) -> parts(1))
      }
      .toList
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap

  /** 近 `days` 天按日提交计数（`git log --all --date=short`，真实读数）。 */
  private def heatmap(root: String, days: Int): (List[(String, Int)], Long, Int) =
    val zone = ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val from = today.minusDays(days.toLong - 1)
    val counts =
      gitOk(root, List("log", "--all", s"--since=${from.toString}", "--date=short", "--pretty=%ad", "--no-merges"))
        .split("\n")
        .iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toList
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap
    val list = (0 until days).toList.reverse.map { back =>
      val d = today.minusDays(back.toLong).toString
      d -> counts.getOrElse(d, 0)
    }
    (list, list.map(_._2.toLong).sum, list.map(_._2).maxOption.getOrElse(0))

  end heatmap

  private def parseLog(out: String): List[Commit] =
    out
      .split(RecordSep)
      .iterator
      .map(_.replaceAll("^\\n+", "").trim)
      .filter(_.nonEmpty)
      .flatMap { rec =>
        val parts = rec.split(FieldSep)
        if parts.length < 5 then Nil
        else
          List(
            Commit(
              sha = parts(0),
              parents = parts(1).split(" ").iterator.filter(_.nonEmpty).toList,
              ts = parts(2).toLongOption.getOrElse(0L) * 1000L,
              author = parts(3),
              subject = parts.drop(4).mkString(String.valueOf(FieldSep))
            )
          )
      }
      .toList

  private def digestOf(refs: List[RefRow], n: Int): String =
    val md = MessageDigest.getInstance("SHA-256")
    val text = refs.map(r => s"${r.name}${r.tip}").mkString("|") + s"|$n"
    md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).take(6).map("%02x".format(_)).mkString

  /** 主入口：只读构建 git 图载荷（与前端 fetchGitGraph 的契约逐字段同形）。 */
  def build(project: String, workspace: String, limit: Int, lanes: Int, heatDays: Int): Json =
    val lim = math.max(20, math.min(2000, limit))
    val maxLanes = math.max(2, math.min(12, lanes))
    val hd = math.max(28, math.min(371, heatDays))
    val t0 = System.nanoTime()

    val inRepo = git(workspace, List("rev-parse", "--is-inside-work-tree")).map(_.trim).contains("true")
    if !inRepo then
      Json.obj(
        "state" -> "not-a-repo".asJson,
        "project" -> project.asJson,
        "root" -> workspace.asJson,
        "message" -> "不是 git 工作树（只读探针 git rev-parse --is-inside-work-tree 未通过）".asJson
      )
    else
      val branches = localBranches(workspace)
      val headRow = branches.find(_.isHead)
      val headName = headRow.map(_.name).getOrElse("")
      val headSha = gitOk(workspace, List("rev-parse", "HEAD")).trim

      // 0 号泳道 = HEAD 所在支；其余按 committerdate 降序填满 lanes 档。
      val drawn = (headRow.toList ++ branches.filterNot(_.name == headName)).take(maxLanes)
      val laneOf: Map[String, Int] = drawn.zipWithIndex.map { case (r, i) => r.name -> i }.toMap
      val refArgs = drawn.map(_.name)

      val logFmt = s"--pretty=format:%H$FieldSep%P$FieldSep%at$FieldSep%an$FieldSep%s$RecordSep"
      // 不带 `--`：路径分隔符会把 ref 当路径，log 变空。
      val walked =
        if refArgs.isEmpty then Nil
        else parseLog(gitOk(workspace, List("log", "--date-order", "-n", lim.toString, logFmt) ++ refArgs))
      val byShaAll = scala.collection.mutable.LinkedHashMap.empty[String, Commit]
      walked.foreach(c => byShaAll.getOrElseUpdate(c.sha, c))
      // 逐个 tip 兜底补取（见文件头 ③）。
      val missingTips = drawn.map(_.tip).filter(t => t.nonEmpty && !byShaAll.contains(t))
      missingTips.foreach { t =>
        parseLog(gitOk(workspace, List("log", "-n", "1", logFmt, t))).headOption.foreach(c =>
          byShaAll.getOrElseUpdate(c.sha, c)
        )
      }
      val missingSet = missingTips.toSet
      val commits = byShaAll.values.toList
        .sortBy(c => (if missingSet.contains(c.sha) then 0 else 1, -c.ts))
        .take(lim + missingTips.length)

      val labels = refLabels(workspace)
      val inWindow = commits.map(_.sha).toSet

      // 逐支按泳道序认领（见文件头 ①）。
      val laneBySha = scala.collection.mutable.HashMap.empty[String, Int]
      val claimed = scala.collection.mutable.HashSet.empty[String]
      drawn.zipWithIndex.foreach { case (ref, lane) =>
        val stack = scala.collection.mutable.Stack(ref.tip)
        while stack.nonEmpty do
          val sha = stack.pop()
          if !claimed.contains(sha) && inWindow.contains(sha) then
            claimed += sha
            laneBySha.update(sha, lane)
            byShaAll
              .get(sha)
              .foreach(c => c.parents.filter(p => !claimed.contains(p) && inWindow.contains(p)).foreach(stack.push))
      }
      commits.foreach(c => laneBySha.getOrElseUpdate(c.sha, 0))

      val rowBySha: Map[String, Int] = commits.zipWithIndex.map { case (c, i) => c.sha -> i }.toMap
      val laneMap: Map[String, Int] = laneBySha.toMap

      val nodeJson = commits.zipWithIndex.map { case (c, row) =>
        Json.obj(
          "sha" -> c.sha.asJson,
          "short" -> short(c.sha).asJson,
          "row" -> row.asJson,
          "lane" -> laneMap.getOrElse(c.sha, 0).asJson,
          "ts" -> c.ts.asJson,
          "date" -> isoDay(c.ts).asJson,
          "author" -> c.author.asJson,
          "subject" -> c.subject.asJson,
          "parents" -> c.parents.filter(laneMap.contains).asJson,
          "refs" -> labels.getOrElse(c.sha, Nil).asJson,
          "isTip" -> labels.getOrElse(c.sha, Nil).exists(laneOf.contains).asJson
        )
      }

      val edges = commits.flatMap { c =>
        c.parents.flatMap { p =>
          rowBySha.get(p).map { prow =>
            Json.obj(
              "from" -> c.sha.asJson,
              "to" -> p.asJson,
              "fromLane" -> laneMap.getOrElse(c.sha, 0).asJson,
              "toLane" -> laneMap.getOrElse(p, 0).asJson,
              "fromRow" -> rowBySha.getOrElse(c.sha, 0).asJson,
              "toRow" -> prow.asJson
            )
          }
        }
      }

      val refsJson = drawn.zipWithIndex.map { case (b, lane) =>
        val (ahead, behind) =
          if headSha.isEmpty then (Option.empty[Int], Option.empty[Int])
          else if b.tip == headSha then (Some(0), Some(0))
          else
            git(workspace, List("rev-list", "--left-right", "--count", s"$headSha...${b.tip}")).toOption
              .flatMap(_.trim.split("\\s+") match
                case Array(l, r) => Some((r.toIntOption, l.toIntOption));
                case _ => None)
              .map { case (a, bh) => (a, bh) }
              .getOrElse((None, None))
        val mergeBase =
          if headSha.isEmpty then ""
          else if b.tip == headSha then b.tip
          else gitOk(workspace, List("merge-base", headSha, b.tip)).trim
        Json.obj(
          "name" -> b.name.asJson,
          "kind" -> "local".asJson,
          "lane" -> lane.asJson,
          "tip" -> b.tip.asJson,
          "tipShort" -> short(b.tip).asJson,
          "ts" -> b.ts.asJson,
          "isHead" -> (b.tip == headSha).asJson,
          "ahead" -> ahead.asJson,
          "behind" -> behind.asJson,
          "mergeBase" -> mergeBase.asJson,
          "inGraph" -> rowBySha.contains(b.tip).asJson
        )
      }

      val (heatDaysList, heatTotal, heatMax) = heatmap(workspace, hd)
      Json.obj(
        "state" -> "ok".asJson,
        "project" -> project.asJson,
        "root" -> workspace.asJson,
        "generatedAt" -> System.currentTimeMillis().asJson,
        "serverMs" -> ((System.nanoTime() - t0) / 1000000L).asJson,
        "head" -> Json.obj("ref" -> headName.asJson, "sha" -> headSha.asJson, "short" -> short(headSha).asJson),
        "lanes" -> drawn.length.asJson,
        "refsTotal" -> branches.length.asJson,
        "refsShown" -> drawn.length.asJson,
        "commitsTotal" -> commits.length.asJson,
        "commitsLimit" -> lim.asJson,
        "refs" -> refsJson.asJson,
        "commits" -> nodeJson.asJson,
        "edges" -> edges.asJson,
        "heatmap" -> Json.obj(
          "days" -> heatDaysList.map { case (d, n) => Json.obj("date" -> d.asJson, "count" -> n.asJson) }.asJson,
          "total" -> heatTotal.asJson,
          "max" -> heatMax.asJson,
          "from" -> heatDaysList.headOption.map(_._1).getOrElse("").asJson,
          "to" -> heatDaysList.lastOption.map(_._1).getOrElse("").asJson
        ),
        "digest" -> digestOf(drawn, commits.length).asJson
      )
    end if
  end build
end GitGraph
