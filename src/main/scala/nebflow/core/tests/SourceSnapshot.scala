package nebflow.core.tests

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.PathUtil

import java.nio.file.{Files, StandardCopyOption}
import java.security.MessageDigest

/**
 * 源快照：把「这一刻的工作区」冻结成带版本号的清单 + 按摘要寻址的对象（M2）。
 *
 * 契约（实施文档 M2 行，作者令 2026-11-19）：
 *   - 覆盖已暂存后又编辑、未暂存修改、删除、改名、预设明确纳入的新增文件；
 *   - `.git`、数据根、凭据面、嵌套工作区与通用构建缓存不得进入；
 *   - 捕获前后各读一次「变更代数」，不一致就显式返回「工作区正在变化」，绝不静默产出一份
 *     看起来稳定其实读串了的清单；
 *   - 清单格式 v1：`{path, type, mode, sha256 或 linkTarget}` 按确定顺序编码并带格式版本；
 *     快照 ID = 清单根摘要（内容寻址：同内容同 ID，与绝对路径无关）；
 *   - 存储 `<snapshotRoot>/<snapshotId>/{manifest.json, objects/<sha256>}`，清单写入走
 *     [[AtomicJson]]，对象按目录原子发布（先写 staging 再 rename）。
 *
 * 路径枚举的取舍（文档「快照取谁」条）：**路径与溯源取自 Git 索引，字节取自工作区**。
 * 索引只用来知道「哪些路径算数」（追踪但已删除的算删除项，纯新增文件要显式点名），
 * 测试要读的字节永远是工作区的真字节 —— 否则「远端复现不了我的失败」。不在 Git 仓库里
 * 的目录走纯文件系统遍历（本次改动对任意普通树也可用）。
 *
 * 独占执行槽（[[ExecutionSlot]]）：`Files.createFile` 的 CREATE_NEW 是原子判据，第二个
 * 并发提交拿到的是**明确的拒绝** + 持有者读数，而不是排队等待（文档允许这两种行为之一，
 * 这里取可判定的那种：拒绝比排队更能证明「同一时刻只有一个执行者」）。
 */
object SourceSnapshot:

  val FormatVersion = 1

  /** 清单条目类型：file / symlink / absent（追踪但工作区已删除）。 */
  final case class Entry(
    path: String,
    kind: String,
    mode: String,
    sha256: Option[String],
    linkTarget: Option[String],
    size: Long
  ):

    def toJson: Json = Json.obj(
      "path" -> path.asJson,
      "type" -> kind.asJson,
      "mode" -> mode.asJson,
      "sha256" -> sha256.map(_.asJson).getOrElse(Json.Null),
      "linkTarget" -> linkTarget.map(_.asJson).getOrElse(Json.Null),
      "size" -> size.asJson
    )

  final case class Manifest(format: Int, id: String, createdAt: String, root: String, entries: List[Entry]):

    def toJson: Json = Json.obj(
      "format" -> format.asJson,
      "id" -> id.asJson,
      "createdAt" -> createdAt.asJson,
      "root" -> root.asJson,
      "entries" -> entries.map(_.toJson).asJson
    )

  final case class Ref(id: String, entries: Int, bytes: Long, manifest: os.Path, reused: Boolean)

  sealed trait Failure:
    def message: String

  object Failure:

    final case class Unstable(detail: String) extends Failure:
      def message = s"工作区正在变化（捕获期间变更代数不一致：$detail）；未产出快照，请重试"

    final case class UnsafePath(path: String, detail: String) extends Failure:
      def message = s"非法纳入路径 $path：$detail"

    final case class Missing(id: String, detail: String) extends Failure:
      def message = s"快照 $id 不可读：$detail"

    final case class Tampered(path: String, objectSha: String, detail: String) extends Failure:
      def message = s"快照校验失败：$path（对象 $objectSha）$detail"

    final case class Io(detail: String) extends Failure:
      def message = s"快照 IO 失败：$detail"

    final case class NotAGitTree(path: os.Path) extends Failure:
      def message = s"$path 既不是 Git 工作树、也无法作为普通目录枚举"

  final case class VerifyReport(id: String, entries: Int, objects: Int, bytes: Long)

  /** 必须排除的目录/文件（构建缓存与本地状态：它们不是「这一版源码」的一部分）。 */
  private val ExcludedSegments = Set(
    ".git",
    "node_modules",
    "target",
    "dist",
    ".sbt",
    ".ivy2",
    ".coursier",
    "__pycache__",
    "test-results",
    ".bloop",
    ".metals",
    ".bsp",
    ".idea",
    ".vscode",
    ".nebflow",
    ".zcode",
    ".claude",
    ".stand"
  )

  /** 凭据面：文件名命中即不入清单（值本身永远不进清单，这里连路径也不收）。 */
  private val CredentialNames = Set("credentials.json", "auth.json", ".env")

  private val CredentialSuffixes = List(".env", ".secret", "service-account.json", "key.json", "id_rsa", "id_ed25519")

  def defaultRoot: os.Path = PathUtil.dataRoot / "snapshots"

  // ── 枚举 ────────────────────────────────────────────────────────────────

  private def sha256Bytes(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"$b%02x").mkString

  private def gitTracked(root: os.Path): Option[List[String]] =
    try
      val out = os.proc("git", "-C", root.toString, "ls-files", "-z").call(cwd = root, stderr = os.Pipe)
      if out.exitCode != 0 then None
      else Some(out.out.text().split('\u0000').toList.filter(_.nonEmpty))
    catch case _: Throwable => None

  /** 普通目录（非 Git）的遍历面：每个常规文件与符号链接都算候选，排除面不下钻。 */
  private def walkPaths(root: os.Path): List[String] =
    def loop(dir: os.Path, prefix: String): List[String] =
      os.list(dir).toList.sortBy(_.last).flatMap { child =>
        val rel = if prefix.isEmpty then child.last else s"$prefix/${child.last}"
        if excluded(rel) then Nil
        else if os.isLink(child) then List(rel)
        else if os.isDir(child) then loop(child, rel)
        else if os.isFile(child) then List(rel)
        else Nil
      }
    loop(root, "")

  private def excluded(rel: String): Boolean =
    val segments = rel.split('/').toList
    segments.exists(ExcludedSegments.contains) || {
      val name = segments.lastOption.getOrElse(rel)
      CredentialNames.contains(name) || CredentialSuffixes.exists(name.endsWith)
    }

  /**
   * 纳入路径的安全校验：必须是工作区内的相对路径、不许 `..`、不许绝对路径、不许指向
   * 工作区外（符号链接逃逸按 real path 判）。返回规范化后的相对路径。
   */
  private[core] def safeRelative(root: os.Path, raw: String): Either[Failure, String] =
    val cleaned = raw.replace('\\', '/').trim.stripPrefix("./")
    if cleaned.isEmpty then Left(Failure.UnsafePath(raw, "空路径"))
    else if cleaned.startsWith("/") || cleaned.matches("^[A-Za-z]:.*") then Left(Failure.UnsafePath(raw, "不许绝对路径"))
    else if cleaned.split('/').contains("..") then Left(Failure.UnsafePath(raw, "不许包含 ..（逃出工作区）"))
    else if excluded(cleaned) then Left(Failure.UnsafePath(raw, "属于排除面（构建缓存 / 凭据 / 本地状态）"))
    else
      val inside = root / os.RelPath(cleaned)
      if os.exists(inside) && !inside.toNIO.toAbsolutePath.normalize.startsWith(root.toNIO.toAbsolutePath.normalize)
      then Left(Failure.UnsafePath(raw, "真实路径落在工作区之外"))
      else Right(cleaned)

  private def candidatePaths(root: os.Path, includes: List[String]): Either[Failure, List[String]] =
    val validated = includes.map(p => safeRelative(root, p))
    validated.collectFirst { case Left(f) => f } match
      case Some(f) => Left(f)
      case None =>
        val extra = validated.collect { case Right(p) => p }
        gitTracked(root) match
          case Some(tracked) => Right((tracked ++ extra).filterNot(excluded).distinct.sorted)
          case None if os.exists(root) && os.isDir(root) =>
            Right((walkPaths(root) ++ extra).filterNot(excluded).distinct.sorted)
          case None => Left(Failure.NotAGitTree(root))

  // ── 变更代数 ────────────────────────────────────────────────────────────

  /** 变更代数：路径 + 大小 + mtime 的摘要。前后不一致 ⇒ 捕获期间有人在写。 */
  private def generation(root: os.Path, paths: List[String]): String =
    val md = MessageDigest.getInstance("SHA-256")
    paths.foreach { rel =>
      val p = root / os.RelPath(rel)
      val stamp =
        if os.isLink(p) then s"link:${os.readLink(p)}"
        else if os.exists(p) && os.isFile(p) then
          val st = os.stat(p)
          s"file:${st.size}:${st.mtime.toMillis}"
        else "absent"
      md.update(s"$rel\t$stamp\n".getBytes("UTF-8"))
    }
    md.digest().map(b => f"$b%02x").mkString

  // ── 捕获 ────────────────────────────────────────────────────────────────

  private def entryFor(root: os.Path, rel: String): Entry =
    val p = root / os.RelPath(rel)
    if os.isLink(p) then Entry(rel, "symlink", "120000", None, Some(os.readLink(p).toString), 0L)
    else if !os.exists(p) then Entry(rel, "absent", "100644", None, None, 0L)
    else if os.isFile(p) then
      val bytes = os.read.bytes(p)
      val exec = Files.isExecutable(p.toNIO)
      Entry(rel, "file", if exec then "100755" else "100644", Some(sha256Bytes(bytes)), None, bytes.length.toLong)
    else Entry(rel, "dir", "040000", None, None, 0L)

  /** 清单的确定编码（快照 ID 的摘要输入）：字段顺序固定、条目按路径排序。 */
  private[core] def canonical(entries: List[Entry]): String =
    val header = s"stand-source-snapshot/v$FormatVersion"
    val body = entries.sorted(Ordering.by[Entry, String](_.path)).map { e =>
      s"${e.path}\u0000${e.kind}\u0000${e.mode}\u0000${e.sha256.getOrElse("")}\u0000${e.linkTarget.getOrElse("")}\u0000${e.size}"
    }
    (header :: body).mkString("\n") + "\n"

  /**
   * 冻结 `root` 的当前字节面。`snapshotRoot` 默认为 `<dataRoot>/snapshots`（可注入，
   * 便于测试与隔离实例）。同一内容两次捕获得到同一个 ID，第二次不再重写对象
   * （`reused = true`）；内容变了就是另一个 ID，绝不静默复用旧快照。
   */
  def capture(
    root: os.Path,
    includes: List[String] = Nil,
    snapshotRoot: os.Path = defaultRoot,
    force: Boolean = false
  ): IO[Either[Failure, Ref]] = IO.blocking {
    candidatePaths(root, includes) match
      case Left(f) => Left(f)
      case Right(paths) =>
        val before = generation(root, paths)
        val staging = snapshotRoot / s".staging-${java.util.UUID.randomUUID()}"
        try
          val objects = staging / "objects"
          Objects.mkdirs(objects)
          val entries = paths.map { rel =>
            val entry = entryFor(root, rel)
            entry.sha256.foreach { sha =>
              val target = objects / sha
              if !os.exists(target) then
                val src = root / os.RelPath(rel)
                val tmp = objects / s".$sha.tmp"
                Files.copy(src.toNIO, tmp.toNIO, StandardCopyOption.REPLACE_EXISTING)
                Files.move(tmp.toNIO, target.toNIO, StandardCopyOption.ATOMIC_MOVE)
            }
            entry
          }
          val after = generation(root, paths)
          if before != after then Left(Failure.Unstable(s"generation $before → $after"))
          else
            val id = sha256Bytes(canonical(entries).getBytes("UTF-8"))
            val dir = snapshotRoot / id
            val existing = os.exists(dir / "manifest.json")
            if existing && !force then
              Objects.deleteRecursively(staging)
              Right(Ref(id, entries.size, entries.map(_.size).sum, dir / "manifest.json", reused = true))
            else
              val manifest = Manifest(
                FormatVersion,
                id,
                java.time.Instant.now().toString,
                root.toString,
                entries
              )
              if os.exists(dir) then Objects.deleteRecursively(dir)
              Files.move(staging.toNIO, dir.toNIO, StandardCopyOption.ATOMIC_MOVE)
              AtomicJson.writeSync(dir / "manifest.json", manifest.toJson.spaces2 + "\n")
              Right(Ref(id, entries.size, entries.map(_.size).sum, dir / "manifest.json", reused = false))
        catch case e: Throwable => Left(Failure.Io(s"$staging: ${e.getMessage}"))
        finally if os.exists(staging) then Objects.deleteRecursively(staging)
  }

  // ── 校验 ────────────────────────────────────────────────────────────────

  def readManifest(dir: os.Path): Either[Failure, Manifest] =
    if !os.exists(dir / "manifest.json") then Left(Failure.Missing(dir.last, "manifest.json 不存在"))
    else
      io.circe.parser.parse(os.read(dir / "manifest.json")) match
        case Left(err) => Left(Failure.Missing(dir.last, s"manifest 不可解析：${err.message}"))
        case Right(json) =>
          val cursor = json.hcursor
          val entries = cursor.downField("entries").as[List[Json]].getOrElse(Nil).flatMap { e =>
            val c = e.hcursor
            c.downField("path").as[String].toOption.map { path =>
              Entry(
                path,
                c.downField("type").as[String].getOrElse("file"),
                c.downField("mode").as[String].getOrElse("100644"),
                c.downField("sha256").as[Option[String]].toOption.flatten,
                c.downField("linkTarget").as[Option[String]].toOption.flatten,
                c.downField("size").as[Long].getOrElse(0L)
              )
            }
          }
          Right(
            Manifest(
              cursor.downField("format").as[Int].getOrElse(FormatVersion),
              cursor.downField("id").as[String].getOrElse(dir.last),
              cursor.downField("createdAt").as[String].getOrElse(""),
              cursor.downField("root").as[String].getOrElse(""),
              entries
            )
          )

  /**
   * 逐对象校验：重算每个对象内容的 sha256 并与清单比对，同时校验目录名等于清单摘要。
   * 任一处不符就**点名是哪个文件**（负控：篡改快照里一个字节必须在这里红出来）。
   */
  def verify(snapshotRoot: os.Path, id: String): IO[Either[Failure, VerifyReport]] = IO.blocking {
    val dir = snapshotRoot / id
    readManifest(dir).flatMap { manifest =>
      if sha256Bytes(canonical(manifest.entries).getBytes("UTF-8")) != id then
        Left(Failure.Missing(id, "清单摘要与快照 ID 不一致（清单被改动）"))
      else
        val checked = manifest.entries.collect {
          case e if e.sha256.isDefined && e.kind == "file" =>
            val obj = dir / "objects" / e.sha256.get
            if !os.exists(obj) then Left(Failure.Tampered(e.path, e.sha256.get, "对象缺失"))
            else
              val actual = sha256Bytes(os.read.bytes(obj))
              if actual != e.sha256.get then
                Left(Failure.Tampered(e.path, e.sha256.get, s"对象内容摘要为 $actual（期望 ${e.sha256.get}）"))
              else Right(e.size)
          case e => Right(e.size)
        }
        checked.collectFirst { case Left(f) => f } match
          case Some(f) => Left(f)
          case None =>
            Right(
              VerifyReport(
                id,
                manifest.entries.size,
                manifest.entries.count(_.sha256.isDefined),
                checked.collect { case Right(size) => size }.sum
              )
            )
    }
  }

  /** 现有工作区与快照的差异（复跑原版本 / 验证当前改动 —— 两种取法互不静默替代）。 */
  final case class Diff(changed: List[String], added: List[String], removed: List[String]):
    def isEmpty: Boolean = changed.isEmpty && added.isEmpty && removed.isEmpty

  def diff(root: os.Path, snapshotRoot: os.Path, id: String, includes: List[String] = Nil): IO[Either[Failure, Diff]] =
    IO.blocking {
      readManifest(snapshotRoot / id).flatMap { manifest =>
        candidatePaths(root, includes).map { paths =>
          val now = paths.filterNot(excluded).map(rel => entryFor(root, rel)).map(e => e.path -> e).toMap
          val wanted = manifest.entries.map(e => e.path -> e).toMap
          Diff(
            changed = wanted.keySet.intersect(now.keySet).toList.sorted.filter(k => wanted(k) != now(k)),
            added = now.keySet.diff(wanted.keySet).toList.sorted,
            removed = wanted.keySet.diff(now.keySet).toList.sorted
          )
        }
      }
    }

  // ── 独占执行槽 ──────────────────────────────────────────────────────────

  /**
   * 独占执行槽：同一时刻只允许一个执行者。
   *
   * 判据是 `Files.createFile`（CREATE_NEW = 原子），所以并发提交里只有一个成功；其余拿到
   * [[Failure.SlotBusy]] 携带当前持有者读数（pid / 任务 / 起始时间）。持有者进程已经不在
   * （陈旧锁）时才允许接管 —— 接管也要走同一条原子路径，不做「先删再建」的竞态窗口。
   */
  object ExecutionSlot:
    final case class Holder(pid: Long, task: String, since: String)

    sealed trait Outcome

    object Outcome:
      final case class Acquired(lock: os.Path, holder: Holder) extends Outcome
      final case class Busy(holder: Option[Holder], lock: os.Path) extends Outcome

    def lockPath(slotRoot: os.Path): os.Path = slotRoot / "slot.lock"

    def status(slotRoot: os.Path): Option[Holder] = readHolder(lockPath(slotRoot))

    private def readHolder(lock: os.Path): Option[Holder] =
      if !os.exists(lock) then None
      else
        io.circe.parser.parse(os.read(lock)).toOption.flatMap { json =>
          val c = json.hcursor
          c.downField("pid").as[Long].toOption.map { pid =>
            Holder(pid, c.downField("task").as[String].getOrElse(""), c.downField("since").as[String].getOrElse(""))
          }
        }

    private def pidAlive(pid: Long): Boolean =
      if pid == ProcessHandle.current().pid() then true
      else
        val handle = ProcessHandle.of(pid)
        if handle.isPresent then handle.get.isAlive else false

    /** 抢槽。`now`/`alive` 可注入，便于测试复现「陈旧锁接管」。 */
    def acquire(
      slotRoot: os.Path,
      task: String,
      now: java.time.Instant = java.time.Instant.now(),
      alive: Long => Boolean = pidAlive
    ): IO[Either[Failure, Outcome]] = IO.blocking {
      Objects.mkdirs(slotRoot)
      val lock = lockPath(slotRoot)
      val holder = Holder(ProcessHandle.current().pid(), task, now.toString)
      def write(): Boolean =
        try
          Files.createFile(lock.toNIO)
          os.write.over(lock, holderJson(holder))
          true
        catch case _: java.nio.file.FileAlreadyExistsException => false
      def attempt(retries: Int): Outcome =
        if write() then Outcome.Acquired(lock, holder)
        else
          val current = readHolder(lock)
          current match
            case Some(h) if !alive(h.pid) && retries > 0 =>
              // 陈旧锁（持有者进程已不在）：只清掉这把锁再抢一次，不动任何别的状态。
              try Files.deleteIfExists(lock.toNIO)
              catch case _: Throwable => ()
              attempt(retries - 1)
            case other => Outcome.Busy(other, lock)
      Right(attempt(1))
    }

    /** 释放：只有持有者本人能释放（pid 不符即不删，返回 false）。 */
    def release(slotRoot: os.Path, onBehalfOf: Long = ProcessHandle.current().pid()): IO[Boolean] = IO.blocking {
      val lock = lockPath(slotRoot)
      readHolder(lock) match
        case Some(h) if h.pid == onBehalfOf =>
          Files.deleteIfExists(lock.toNIO)
        case _ => false
    }

    private def holderJson(holder: Holder): String =
      JsonObject(
        "pid" -> holder.pid.asJson,
        "task" -> holder.task.asJson,
        "since" -> holder.since.asJson
      ).asJson.spaces2 + "\n"

  // ── 小工具 ──────────────────────────────────────────────────────────────

  private object Objects:
    def mkdirs(path: os.Path): Unit = Files.createDirectories(path.toNIO)

    def deleteRecursively(path: os.Path): Unit =
      if os.exists(path) then os.remove.all(path)
