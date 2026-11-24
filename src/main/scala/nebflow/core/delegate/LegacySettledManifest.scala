package nebflow.core.delegate

import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.PathUtil

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}

/** Sidecar is presentation-only. Missing or invalid manifests fail open. */
object LegacySettledManifest:
  private def path = PathUtil.dataRoot / "delegates" / ".legacy-settled.json"
  private var cachedPath = ""
  private var cachedStamp = ""
  private var cachedIds = Set.empty[String]

  def contains(id: String): Boolean = synchronized {
    refresh()
    cachedIds.contains(id)
  }

  /** 只读：当前 manifest 的 id 全集（扫描/审计面用；缺档/坏形 = 空集，fail-open）。 */
  def loadedIds(): Set[String] = synchronized {
    refresh()
    cachedIds
  }

  /** 路径+mtime+size 快照校验后重读（缺档/坏形 = 空集，绝不抛）。 */
  private def refresh(): Unit =
    val p = path
    val stamp = if os.exists(p) then s"${os.mtime(p)}:${os.size(p)}" else "missing"
    if cachedPath != p.toString || cachedStamp != stamp then
      cachedIds =
        try
          if !os.exists(p) then Set.empty
          else
            io.circe.parser
              .parse(os.read(p))
              .toOption
              .flatMap(_.hcursor.get[List[String]]("ids").toOption)
              .getOrElse(Nil)
              .toSet
        catch case _: Throwable => Set.empty
      cachedPath = p.toString
      cachedStamp = stamp

  end refresh

  /** Explicit invocation only: data root must be an offline isolated root. */
  def generate(): (Int, Boolean) =
    val records = DelegateRegistry.list()
    val ids = records
      .filter(r => DelegateRoundLedger.currentRounds(r.id).isEmpty && DelegateVisibility.readOf(r.id).settled)
      .map(_.id)
      .sorted
    val p = path
    val existing = if os.exists(p) then io.circe.parser.parse(os.read(p)).toOption else None
    val same = existing.exists(j =>
      j.hcursor.get[Int]("version").toOption.contains(1) &&
        j.hcursor.get[List[String]]("ids").toOption.contains(ids)
    )
    if same then (ids.size, false)
    else
      os.makeDir.all(p / os.up)
      val body = Json
        .obj("version" -> 1.asJson, "generatedAt" -> System.currentTimeMillis().asJson, "ids" -> ids.asJson)
        .noSpaces
      val tmp = Files.createTempFile((p / os.up).toNIO, ".legacy-settled-", ".tmp")
      try
        Files.write(tmp, body.getBytes(StandardCharsets.UTF_8))
        Files.move(tmp, p.toNIO, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      finally Files.deleteIfExists(tmp)
      (ids.size, true)

  end generate

  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: LegacySettledManifest <isolated-data-root>")
    val root = os.Path(args(0), os.pwd)
    require(root.startsWith(os.Path("/tmp")) || root.startsWith(os.Path("/private/tmp")), "isolated /tmp root required")
    require(os.exists(root) && os.isDir(root), "root must exist")
    PathUtil.setDataRoot(root)
    val (count, written) = generate()
    println(s"manifest ids=$count written=$written path=${path}")
end LegacySettledManifest
