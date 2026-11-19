package nebflow.core.device

import cats.effect.IO
import io.circe.*
import io.circe.syntax.*
import nebflow.core.{AtomicJson, CredentialFileAcl}
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * Device pairing registry + HMAC shared keys（v2.1 阶段 1 §A.4 双轨灰度的**登记面**）。
 *
 * 编排权威侧（host）的配对登记表：每个已配对设备一条 [[DeviceEntry]]，落盘
 * `<dataRoot>/neblink/devices.json`（0600 语义由父目录惯例承担——与 device.json
 * 同目录同域；本文件**共享密钥是唯一秘密**，ACL 沿 [[nebflow.core.CredentialFileAcl]]
 * 同族纪律在写入后收紧）。
 *
 * 阶段 1 引导 = **最小面**（spec §E 阶段 1：「配置/ops 手工配对」）：两侧各写一份
 * 相同 `sharedKey` 的 devices.json 即完成配对；注册门户引导属阶段 2，不在本批。
 * 运行期读写纪律 = 每次现读盘（热读，仿 [[nebflow.core.executor.ExecutorRegistry]]
 * readSettings / DelegateRegistry 先例）——手工编辑即刻生效，零重启。
 *
 * 容错纪律（与凭据件同族）：文件缺席 = 空表（正常未配对态）；JSON 坏 = WARN +
 * 按空表继续（**绝不抛**——信任判据不得楔死对端通道）。
 *
 * 归属：`core.device` 包（与 [[DeviceHmac]] 同批下沉——core 消费方 DeviceDelegate /
 * RemoteExecutor 经分层门禁不得 import neblink；存储路径不变）。
 */
final case class DeviceEntry(
  deviceId: String,
  deviceName: String,
  /** HMAC-SHA256 共享密钥（两侧一致；手工配对的唯一秘密）。 */
  sharedKey: String,
  /** 是否接受来自该设备的 delegate-spawn（设备级白名单，v2 §A.3「工具白名单」的设备粒度档）。 */
  allowSpawn: Boolean = true,
  createdAt: Long,
  note: String = ""
)

object DeviceRegistry:

  private val log = NebflowLogger.forName("nebflow.core.device.registry")

  // def, not val: PathUtil.dataRoot is redirectable (setDataRoot); a val would
  // freeze the path at object-init and break per-test data roots (f1cd3709 rule).
  private def registryPath = PathUtil.dataRoot / "neblink" / "devices.json"

  // ── codecs（手写宽容：缺键/坏形逐字段兜默认，禁整文件静默消失）──────────
  given Encoder[DeviceEntry] = Encoder.instance { e =>
    Json.obj(
      "deviceId" -> e.deviceId.asJson,
      "deviceName" -> e.deviceName.asJson,
      "sharedKey" -> e.sharedKey.asJson,
      "allowSpawn" -> e.allowSpawn.asJson,
      "createdAt" -> e.createdAt.asJson,
      "note" -> e.note.asJson
    )
  }

  given Decoder[DeviceEntry] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[String]
      sharedKey <- c.downField("sharedKey").as[String]
      allowSpawn <- c.downField("allowSpawn").as[Option[Boolean]].map(_.getOrElse(true))
      createdAt <- c.downField("createdAt").as[Option[Long]].map(_.getOrElse(0L))
      note <- c.downField("note").as[Option[String]].map(_.getOrElse(""))
    yield DeviceEntry(deviceId, deviceName, sharedKey, allowSpawn, createdAt, note)
  }

  /** 现读盘（同步核；失败按空表继续——信任判据绝不抛）。 */
  def loadSync: List[DeviceEntry] =
    try
      val p = registryPath
      if !os.exists(p) then Nil
      else
        io.circe.parser.decode[List[DeviceEntry]](os.read(p)) match
          case Right(entries) => entries
          case Left(err) =>
            log.warnSync(s"devices.json decode failed (${err.getMessage}) — treating as empty registry")
            Nil
    catch
      case e: Exception =>
        log.warnSync(s"devices.json read failed (${e.getMessage}) — treating as empty registry")
        Nil

  def load: IO[List[DeviceEntry]] = IO.blocking(loadSync)

  /** 整表原子写（AtomicJson；写后收紧 ACL——sharedKey 是唯一秘密）。 */
  def saveSync(entries: List[DeviceEntry]): Either[String, Unit] =
    try
      val p = registryPath
      os.makeDir.all(p / os.up)
      AtomicJson.writeSync(p, entries.asJson.spaces2)
      try CredentialFileAcl.restrict(java.nio.file.Paths.get(p.toString))
      catch case _: Exception => () // ACL 失败非致命（与凭据件同口径：文件已落盘；登记表容忍）
      Right(())
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

  def save(entries: List[DeviceEntry]): IO[Either[String, Unit]] = IO.blocking(saveSync(entries))

  /** Upsert（按 deviceId 幂等）。 */
  def upsertSync(entry: DeviceEntry): Either[String, Unit] =
    val rest = loadSync.filterNot(_.deviceId == entry.deviceId)
    saveSync(rest :+ entry)

  def removeSync(deviceId: String): Either[String, Unit] =
    saveSync(loadSync.filterNot(_.deviceId == deviceId))

  /** 单点查键（验签与签名两侧共用；空键/缺项 = 未配对）。 */
  def secretForSync(deviceId: String): Option[String] =
    loadSync.find(_.deviceId == deviceId).map(_.sharedKey).filter(_.nonEmpty)

  def secretFor(deviceId: String): IO[Option[String]] = IO.blocking(secretForSync(deviceId))

  def entrySync(deviceId: String): Option[DeviceEntry] = loadSync.find(_.deviceId == deviceId)

end DeviceRegistry
