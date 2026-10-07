package nebflow.core.index

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * VectorStore —— 检索向量落盘（task-rag 批 #1227 / §Batch 7）。
 *
 * 单文件 `~/.nebflow/index/vectors.json`（`PathUtil.dataRoot / "index"`，测试
 * 随 dataRoot 重根隔离——`def` 而非 `val`，同 FileTaskStore 陷阱）。条目 =
 * `entryId → VectorRecord`；entryId 形态 `"task:<ledgerId>"` / `"delegate:<id>"`。
 *
 * ==形制（零新建重库）==
 * 余弦暴力扫、千级条目——一个 JSON 文件足够（原文「千级够用」）；零数据库、
 * 零 ANN 索引、零新增重依赖（circe + AtomicJson 既有面）。`hash` = 源文本+
 * 模型的内容指纹（增量索引判据：hash 未变 ⇒ 向量复用，不重复 embed；模型
 * 换挡 ⇒ 全量 hash 变化 ⇒ 自然重嵌）。
 *
 * ==读写纪律（仿 TaskLedgerStore）==
 * 读 = 容忍解码（坏形 → 空视图 + WARN，绝不抛）；写 = 进程内单锁 read-
 * modify-write + AtomicJson（tmp + ATOMIC_MOVE，crash-safe）。无跨进程锁
 * （单宿主前提，同账本取舍）。
 */
object VectorStore:

  private val logger = NebflowLogger.forName("nebflow.index.vectors")

  val FileName: String = "vectors.json"
  val IndexDirName: String = "index"

  val Version: Int = 1

  /** entryId 前缀（kind 判据；禁字面量散落）。 */
  val TaskKind: String = "task"
  val DelegateKind: String = "delegate"

  /** 一条向量记录（连同检索返回面的展示字段——返回结构直接读盘面，零二次装配）。 */
  case class VectorRecord(
      vector: List[Float],
      dim: Int,
      /** 嵌入模型 id —— hash 的组成部份：换模型 ⇒ 全量重嵌。 */
      model: String,
      /** 内容指纹 = sha256(model + "\n" + text)；增量索引判据。 */
      hash: String,
      kind: String,
      title: String,
      status: String,
      /** 执行器图标键（iconKey = executor id，前端单点映射；空 = 无）。 */
      executor: String,
      /** 续聊地址（`delegate:<id>` 形态；空 = 无）。 */
      address: String,
      /** 关联账本条目 id（无 # 形态；空 = 无）。 */
      taskId: String,
      snippet: String,
      updatedAt: String
  )

  object VectorRecord:
    given Configuration = Configuration.default.withDefaults
    given Codec[VectorRecord] = ConfiguredCodec.derived

  case class VectorFile(version: Int = Version, entries: Map[String, VectorRecord] = Map.empty)

  object VectorFile:
    given Configuration = Configuration.default.withDefaults
    given Codec[VectorFile] = ConfiguredCodec.derived

  /** `def` not `val`: dataRoot 可被测试重根（FileTaskStore 同款陷阱）。 */
  private def file: os.Path = PathUtil.dataRoot / IndexDirName / FileName

  private val fileLock = new Object

  // ── 读 ────────────────────────────────────────────────────────────────

  /** 容忍读：文件缺/坏形 → 空文件 + WARN（读路径零副作用，绝不抛）。 */
  def read(): VectorFile =
    fileLock.synchronized {
      try
        if !os.exists(file) then VectorFile()
        else
          decode[VectorFile](os.read(file)) match
            case Right(f)  => f
            case Left(err) =>
              logger.warnSync(s"[vectors] $FileName malformed, degrading to empty view: ${err.getMessage}")
              VectorFile()
      catch
        case e: Exception =>
          logger.warnSync(s"[vectors] $FileName unreadable (${e.getClass.getSimpleName}) — empty view")
          VectorFile()
    }

  // ── 写 ────────────────────────────────────────────────────────────────

  /** 单锁 read-modify-write：调用方拿到最新全量，改完原子落盘。 */
  def update(f: VectorFile => VectorFile): Either[String, VectorFile] =
    fileLock.synchronized {
      try
        val next = f(read())
        // 写前恢复 version 单点（防调用方蓝图改丢版本号）。
        val stamped = next.copy(version = Version)
        AtomicJson.writeSync(file, stamped.asJson.noSpaces)
        Right(stamped)
      catch
        case e: Exception =>
          val msg = s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("(no message)")}"
          logger.warnSync(s"[vectors] $FileName write failed ($msg) — file untouched (atomic write)")
          Left(msg)
    }

  /** Upsert 一批记录（同 id 覆盖）。 */
  def putAll(records: Map[String, VectorRecord]): Either[String, VectorFile] =
    update(f => f.copy(entries = f.entries ++ records))

  /** 删除一批 id（缺的不算错）。 */
  def removeAll(ids: Iterable[String]): Either[String, VectorFile] =
    update(f => f.copy(entries = f.entries -- ids))

  /** 清空（测试/重建用）。 */
  def clear(): Either[String, VectorFile] = update(_ => VectorFile())

end VectorStore
