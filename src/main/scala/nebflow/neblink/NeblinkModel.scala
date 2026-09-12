package nebflow.neblink

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import nebflow.core.{AtomicJson, Branding, NebflowLogger, PathUtil}

import java.util.UUID
import scala.util.matching.Regex

// ===== Device Identity =====

/** Local device identity — generated once, stored in ~/.nebflow/device.json. */
case class DeviceIdentity(
  deviceId: String,
  deviceName: String,
  platform: String,
  deviceSecret: String = "",
  capabilities: Map[String, String] = Map.empty,
  userDescription: String = "",
  avatarUrl: Option[String] = None,
  githubLogin: Option[String] = None
)

object DeviceIdentity:
  private val logger = NebflowLogger.forName("nebflow.neblink.device")

  given Encoder[DeviceIdentity] = deriveEncoder

  /** Manual decoder: deriveDecoder does NOT honor Scala parameter defaults
    * (circe semiauto limitation — a defaulted field is still REQUIRED on the
    * wire). The gateway's own encoder writes every field, so gateway-written
    * files round-trip; but any hand-written or backup-restored device.json
    * that omits `capabilities`/`userDescription`/... silently failed the
    * WHOLE decode -> loadOrCreate fell back to createNew() -> a fresh random
    * identity every boot -> "403 Invalid device credential" against the
    * server (2026-08-30 E2E finding; identity is never persisted on the
    * decode-failure path, which kept the file looking pristine). */
  given Decoder[DeviceIdentity] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[String]
      platform <- c.downField("platform").as[String]
      deviceSecret <- c.downField("deviceSecret").as[Option[String]].map(_.getOrElse(""))
      capabilities <- c.downField("capabilities").as[Option[Map[String, String]]].map(_.getOrElse(Map.empty))
      userDescription <- c.downField("userDescription").as[Option[String]].map(_.getOrElse(""))
      avatarUrl <- c.downField("avatarUrl").as[Option[Option[String]]].map(_.flatten)
      githubLogin <- c.downField("githubLogin").as[Option[Option[String]]].map(_.flatten)
    yield DeviceIdentity(deviceId, deviceName, platform, deviceSecret, capabilities, userDescription, avatarUrl, githubLogin)
  }

  // def, not val: PathUtil.dataRoot is redirectable (setDataRoot); a val would
  // freeze the path at object-init and break per-test data roots (f1cd3709 rule).
  private def devicePath = PathUtil.dataRoot / "device.json"

  private def detectPlatform: String =
    val osName = System.getProperty("os.name", "unknown").toLowerCase
    if osName.contains("mac") then "macos"
    else if osName.contains("win") then "windows"
    else if osName.contains("linux") then "linux"
    else "unknown"

  private def detectDeviceName: String =
    Option(System.getenv("HOSTNAME"))
      .orElse(Option(System.getenv("COMPUTERNAME")))
      .orElse(
        try Some(java.net.InetAddress.getLocalHost.getHostName)
        catch case _: Exception => None
      )
      .map(_.stripSuffix(".local")) // macOS mDNS returns "hostname.local"
      .getOrElse("Unknown")

  // ── Machine-code derived device id (2026-09-11 作者裁定) ────────────────────
  //
  // 过去 deviceId = UUID.randomUUID()，每次重铸都在服务端留下一条新的 devices
  // 行（服务端对同一 id 幂等 —— store.rs 四条 ON CONFLICT(id, network_id)
  // upsert，但它从不铸造 id），这是「同机 5 条设备行」的近因
  // （dup-device-verdict §1.2 类 2）。现在改为「机器码 + scope」的确定性派生：
  //
  //   deviceId = UUIDv5(namespace = DeviceIdNamespace, name = machineCode|scope)
  //
  // 语义（一次钉死，勿混）：
  //   - 同一 (机器码, scope) ⇒ 同 id。默认 home 下任意重装 / 删 device.json /
  //     换安装目录都得到同一个 id（服务端因此不再堆幽灵行）。
  //   - 非默认 home（CLI --home 或 <PREFIX>_HOME，见 PathUtil.dataRoot）的 scope
  //     = dataRoot 路径字符串 ⇒ **有意**派生不同 id：隔离/测试实例与作者主客户端
  //     不撞身份（同 id 会因服务端 one-live-session-per-(device, network) 把主
  //     客户端踢下线，见 NeblinkClient.scala:448 一带注释）。
  //   - 一台机器上开两个不同账号的实例（各自 home）互不影响：身份按 home 隔离，
  //     服务端踢线只发生在同一 (device_id, network_id) 维度（store.rs:1039 /
  //     :1801）——「同机同账号被踢」允许发生，但**不是**必须发生。
  //   - 机器码读不到（权限 / 平台不支持 / 沙箱受限）⇒ 回退随机 UUID，但**照样
  //     落盘**（见 loadOrCreate），不再有「新铸不落盘 ⇒ 每次启动都新铸」的粘性循环。
  //
  // 结果保持 UUID 形态（36 字符）：wire 契约不变。服务端 devices.id 是 TEXT、
  // 无长度/字符集约束（store.rs:145-153），故 36 字符 UUID 恒在可容纳范围内。

  /** Fixed UUIDv5 namespace for the device-id derivation. Wire-visible constant:
    * changing it re-mints every client's id — do not touch without a migration. */
  private[neblink] val DeviceIdNamespace: UUID =
    UUID.fromString("6f1a2c3d-4e5b-4a7c-8d9e-0f1a2b3c4d5e")

  /** Scope value for the standard (production) data root. */
  private[neblink] val DefaultScope: String = "default"

  /** Env override for the machine code — test/debug ONLY (see the value table in
    * the batch report). It replaces the machine-code *source*, not the semantics:
    * the id stays a pure function of (value, scope), so reproducibility is
    * unaffected — same override value always yields the same id. */
  private[neblink] val MachineIdEnv: String = "NEBLINK_MACHINE_ID"

  /** True when the data root was redirected away from `<user.home>/<brand dir>`
    * (CLI `--home`, see Main.scala; or the `<PREFIX>_HOME` env, both funnel
    * through PathUtil.dataRoot). */
  private[neblink] def isNonDefaultHome: Boolean =
    PathUtil.dataRoot.toNIO.toAbsolutePath.normalize !=
      (os.home / Branding.homeDirName).toNIO.toAbsolutePath.normalize

  /** Scope component of the derivation (see the block comment above). */
  private[neblink] def deviceIdScope: String =
    if isNonDefaultHome then PathUtil.dataRoot.toString else DefaultScope

  /** Pure: the device id for a (machineCode, scope) pair — same input, same
    * output, always UUID-shaped. */
  def deriveDeviceId(machineCode: String, scope: String): String =
    uuidV5(DeviceIdNamespace, s"$machineCode|$scope").toString

  /** RFC 4122 §4.3 UUIDv5 (SHA-1, name-based). The JDK ships no v5 generator
    * (`UUID.nameUUIDFromBytes` is v3/MD5). Namespace and name bytes are hashed
    * in network (big-endian) byte order, then the version/variant bits are
    * overwritten per the RFC. */
  private[neblink] def uuidV5(namespace: UUID, name: String): UUID =
    val md = java.security.MessageDigest.getInstance("SHA-1")
    md.update(bytesOf(namespace))
    md.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val h = md.digest()
    h(6) = ((h(6) & 0x0f) | 0x50).toByte // version 5
    h(8) = ((h(8) & 0x3f) | 0x80).toByte // RFC 4122 variant
    val bb = java.nio.ByteBuffer.wrap(h)
    new UUID(bb.getLong, bb.getLong)

  private def bytesOf(u: UUID): Array[Byte] =
    val bb = java.nio.ByteBuffer.allocate(16)
    bb.putLong(u.getMostSignificantBits)
    bb.putLong(u.getLeastSignificantBits)
    bb.array()

  // ── Machine code (platform probes) ────────────────────────────────────────
  //
  // macOS: IOPlatformUUID (ioreg) · Windows: MachineGuid (registry) ·
  // Linux: /etc/machine-id, falling back to the DMI product UUID.

  private val PlatformUuidRe: Regex = """"IOPlatformUUID"\s*=\s*"([^"]+)"""".r
  private val MachineGuidRe: Regex = """(?im)^\s*MachineGuid\s+REG_SZ\s+(\S+)\s*$""".r

  /** Known non-identifying placeholder values (systemd leaves "uninitialized"
    * when it never generated an id) — treated as "unreadable" so that every such
    * machine does NOT collapse onto one shared derived id. */
  private val MachineCodePlaceholders: Set[String] = Set("uninitialized", "none", "unknown")

  /** Machine code, or None when unreadable (permission / unsupported platform /
    * restricted sandbox / placeholder value). `NEBLINK_MACHINE_ID` (test/debug)
    * REPLACES the probe verbatim — it is a source override, not a semantic one:
    * the id stays a pure function of (value, scope), so the same override always
    * yields the same id. */
  private[neblink] def readMachineCode(): Option[String] =
    envValue(MachineIdEnv)
      .orElse(envValue("NEBFLOW_MACHINE_ID")) match
      case Some(overridden) => sanitizeMachineCode(Some(overridden))
      case None             => sanitizeMachineCode(readMachineCodeFromOs())

  private[neblink] def sanitizeMachineCode(raw: Option[String]): Option[String] =
    raw
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(v => MachineCodePlaceholders.contains(v.toLowerCase))

  private[neblink] def readMachineCodeFromOs(): Option[String] =
    val osName = System.getProperty("os.name", "").toLowerCase
    if osName.contains("mac") then
      firstMatch(
        runCapture(Seq("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")),
        PlatformUuidRe
      )
    else if osName.contains("win") then
      firstMatch(
        runCapture(
          Seq("reg", "query", """HKLM\SOFTWARE\Microsoft\Cryptography""", "/v", "MachineGuid")
        ),
        MachineGuidRe
      )
    else if osName.contains("linux") then
      readTrimmed(os.Path("/etc/machine-id"))
        .orElse(readTrimmed(os.Path("/sys/class/dmi/id/product_uuid")))
    else None

  private def envValue(name: String): Option[String] =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty)

  private def firstMatch(out: Option[String], re: Regex): Option[String] =
    out.flatMap(s => re.findFirstMatchIn(s).map(_.group(1).trim)).filter(_.nonEmpty)

  private def runCapture(cmd: Seq[String]): Option[String] =
    try
      val r = os.proc(cmd).call(check = false, stdout = os.Pipe, stderr = os.Pipe, timeout = 5000)
      if r.exitCode == 0 then Some(r.out.text()) else None
    catch case _: Exception => None

  private def readTrimmed(p: os.Path): Option[String] =
    try if os.exists(p) then Some(os.read(p).trim).filter(_.nonEmpty) else None
    catch case _: Exception => None

  // ── Load / persist (self-healing) ─────────────────────────────────────────

  /** One load attempt: the identity to use, whether it must be written back,
    * and a log line to emit (`IO.unit` on the silent happy path). */
  private case class Loaded(identity: DeviceIdentity, needsSave: Boolean, log: IO[Unit])

  /**
   * Load the persisted identity, minting one when there is none usable.
   *
   * Exactly one decode per call (the old shape decoded twice — once to load,
   * once to decide `needsSave`). Three branches:
   *  1. file present + decodes  -> reuse verbatim; write back ONLY if a legacy
   *     migration changed something (empty deviceSecret / ".local" deviceName).
   *  2. file present + decode fails -> back the corrupt file up, mint, and
   *     persist immediately (the old code minted WITHOUT saving, so every boot
   *     minted again — the sticky re-mint loop of dup-device-verdict §1.2).
   *  3. file absent -> mint (machine-code-derived, see above) and persist.
   *
   * A redirected data root keeps an existing decodable identity (conservative
   * reading: identity reuse only when the CURRENT dataRoot holds a decodable
   * file); otherwise the deterministic derivation supplies the id.
   */
  def loadOrCreate: IO[DeviceIdentity] =
    for
      loaded <- IO.blocking(loadOnce())
      _ <- loaded.log
      id <-
        if loaded.needsSave then save(loaded.identity).as(loaded.identity)
        else IO.pure(loaded.identity)
    yield id

  private def loadOnce(): Loaded =
    val path = devicePath
    if os.exists(path) then
      decode[DeviceIdentity](os.read(path)) match
        case Right(saved) =>
          val migrated = ensureSecret(ensureCleanDeviceName(saved))
          Loaded(migrated, needsSave = migrated != saved, log = IO.unit)
        case Left(err) =>
          val backup = backupCorruptFile()
          val m = mint()
          Loaded(
            m.identity,
            needsSave = true,
            log = logger.warn(
              s"device.json at $path is not decodable (${err.getMessage}) — corrupt file moved to " +
                s"$backup, minted deviceId=${m.identity.deviceId} (${m.source}) and persisted"
            )
          )
    else
      val m = mint()
      Loaded(
        m.identity,
        needsSave = true,
        log = logger.info(
          s"no device.json at $path — minted deviceId=${m.identity.deviceId} " +
            s"(scope=${deviceIdScope}, ${m.source}) and persisted"
        )
      )

  /** Move the undecodable file aside so the failure is forensically visible
    * (`device.json.corrupt-<ts>`) and the next boot is a clean one. A failed
    * move is NOT fatal — `save` rewrites the path anyway. Returns the backup
    * path, or the failure description. */
  private def backupCorruptFile(): String =
    val ts = java.time.format.DateTimeFormatter
      .ofPattern("yyyyMMdd-HHmmss-SSS")
      .withZone(java.time.ZoneId.systemDefault())
      .format(java.time.Instant.now())
    val backup = devicePath / os.up / s"${devicePath.last}.corrupt-$ts"
    try
      os.move(devicePath, backup)
      backup.toString
    catch case e: Exception => s"(backup failed: ${e.getMessage})"

  /** Atomic write (tmp + `ATOMIC_MOVE`): a crash mid-write can no longer leave
    * a half-written device.json, which used to be the entry into the "decode
    * fails -> new id every boot" loop. */
  def save(identity: DeviceIdentity): IO[Unit] =
    AtomicJson.write(devicePath, identity.asJson.spaces2)

  /** Minted identity plus the (log-only) provenance of its device id. */
  private case class Minted(identity: DeviceIdentity, source: String)

  /** A brand-new identity. The id is the machine-code derivation when the
    * machine code is readable, else a random UUID — either way `loadOrCreate`
    * persists it, so the fallback is a one-time event, not a per-boot loop.
    * `source` is logged so an unreadable machine code is never silent. */
  private def mint(): Minted =
    val name = detectDeviceName
    val platform = detectPlatform
    val secret = UUID.randomUUID().toString + UUID.randomUUID().toString
    readMachineCode() match
      case Some(code) =>
        Minted(
          DeviceIdentity(deriveDeviceId(code, deviceIdScope), name, platform, secret),
          "machine-code derived"
        )
      case None =>
        Minted(
          DeviceIdentity(UUID.randomUUID().toString, name, platform, secret),
          "machine code unreadable — random UUID fallback"
        )

  /** Migrate old DeviceIdentity without deviceSecret — generate one on first load. */
  private def ensureSecret(id: DeviceIdentity): DeviceIdentity =
    if id.deviceSecret.isEmpty then id.copy(deviceSecret = UUID.randomUUID().toString + UUID.randomUUID().toString)
    else id

  /** Migrate old DeviceIdentity with ".local" suffix in deviceName (macOS mDNS artifact). */
  private def ensureCleanDeviceName(id: DeviceIdentity): DeviceIdentity =
    if id.deviceName.endsWith(".local") then id.copy(deviceName = id.deviceName.stripSuffix(".local"))
    else id
end DeviceIdentity

// ===== Device Discovery Info =====

/**
 * Device info exchanged during NebLink discovery (returned by GET /api/neblink/discover).
 *
 *  Note: userDescription is intentionally NOT included — descriptions are purely local,
 *  never exchanged between devices. See NeblinkService.handleAnnounce.
 */
case class DeviceDiscoveryInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  capabilities: Map[String, String] = Map.empty
)

object DeviceDiscoveryInfo:
  given Encoder[DeviceDiscoveryInfo] = deriveEncoder
  given Decoder[DeviceDiscoveryInfo] = deriveDecoder

// ===== Peer Info =====

case class PeerInfo(
  deviceId: String,
  deviceName: String,
  platform: String,
  address: String,
  deviceSecret: String = "",
  capabilities: Map[String, String] = Map.empty,
  userDescription: String = "",
  lastSeen: Long = System.currentTimeMillis(),
  /** C1 (2026-09-11 P2P 直连修复批): **every** endpoint the peer declared to the
    * NebLink Server, preference-ordered (see [[EndpointPreference]]), `address`
    * being `endpoints.head`.
    *
    * WHY: the namelist drops down to `endpoints.head` on the receiving side
    * (`NeblinkClient.toNeblinkPeers`) — in the 2026-09-11 incident that head was
    * an unreachable LAN address while a working Tailscale endpoint sat at index
    * 1, so P2P could never come up. `address` alone is a single point of failure;
    * this field is the candidate list the dial / execute sides walk.
    *
    * Empty (`Nil`) for peers built by paths that carry no server namelist
    * (inbound presence route, test fixtures) — those fall back to `address`,
    * i.e. pre-C1 behaviour. Defaulted ⇒ wire/JSON decoding stays backward
    * compatible (`NeblinkModelSpec` "endpoints 缺省" 回归). */
  endpoints: List[String] = Nil
)

object PeerInfo:
  given Encoder[PeerInfo] = deriveEncoder

  given Decoder[PeerInfo] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[String]
      platform <- c.downField("platform").as[String]
      address <- c.downField("address").as[String]
      deviceSecret <- c.downField("deviceSecret").as[Option[String]].map(_.getOrElse(""))
      capabilities <- c.downField("capabilities").as[Option[Map[String, String]]].map(_.getOrElse(Map.empty))
      userDescription <- c.downField("userDescription").as[Option[String]].map(_.getOrElse(""))
      lastSeen <- c.downField("lastSeen").as[Option[Long]].map(_.getOrElse(System.currentTimeMillis()))
      // C1: absent (all pre-existing persisted records / API payloads) ⇒ Nil.
      endpoints <- c.downField("endpoints").as[Option[List[String]]].map(_.getOrElse(Nil))
    yield PeerInfo(deviceId, deviceName, platform, address, deviceSecret, capabilities, userDescription, lastSeen, endpoints)
  }
end PeerInfo

/**
 * Endpoint preference ordering (C1, 2026-09-11 P2P 直连修复批) — 方案 §3.3 目标口径.
 *
 * Order: `100.64.0.0/10` (Tailscale / CGNAT) → 本机同网段 (`/24`) → 其余.
 * Rationale: a Tailscale address is reachable across networks by construction,
 * while a LAN address only works on the same link — in the incident the server
 * picked the LAN one and dialing it went out via the default gateway
 * (`route -n get 192.168.1.145` → `gateway 192.168.2.1`). Same-subnet `/24` is
 * the next best guess when Tailscale is absent.
 *
 * Pure and side-effect free so the ordering is unit-testable without a network;
 * ties keep the server's original order (`sortBy` is stable), so a namelist that
 * already arrives well-ordered is left untouched.
 */
object EndpointPreference:

  /** Extract the host from `"http://100.x.y.z:8080"` (mirrors presence `extractHost`). */
  def hostOf(url: String): String =
    val stripped = url.replaceFirst("(?i)^https?://", "")
    val slashIdx = stripped.indexOf('/')
    val authority = if slashIdx >= 0 then stripped.substring(0, slashIdx) else stripped
    // IPv6 literals are bracketed; keep them whole rather than splitting on ':'
    if authority.startsWith("[") then
      val close = authority.indexOf(']')
      if close > 0 then authority.substring(1, close) else authority
    else
      val colonIdx = authority.indexOf(':')
      (if colonIdx > 0 then authority.substring(0, colonIdx) else authority).trim

  private def octets(host: String): Option[Vector[Int]] =
    val parts = host.split('.')
    if parts.length != 4 then None
    else
      val nums = parts.toVector.map(_.toIntOption.filter(n => n >= 0 && n <= 255))
      if nums.forall(_.isDefined) then Some(nums.map(_.get)) else None

  /** `100.64.0.0/10` — the Tailscale / CGNAT carrier-grade range. */
  def isTailscaleHost(host: String): Boolean =
    octets(host).exists(o => o(0) == 100 && o(1) >= 64 && o(1) <= 127)

  /** `/24` prefix key (`"192.168.2."`) — the cheap "same LAN?" approximation. */
  def subnet24(host: String): Option[String] =
    octets(host).map(o => s"${o(0)}.${o(1)}.${o(2)}.")

  /** Local `/24` prefixes derived from this device's own IPv4 addresses. */
  def localPrefixesOf(localAddresses: List[String]): Set[String] =
    localAddresses.flatMap(subnet24).toSet

  /** Lower = tried first. 0 = Tailscale/CGNAT, 1 = same `/24` as a local NIC, 2 = the rest. */
  def rank(url: String, localPrefixes: Set[String]): Int =
    val host = hostOf(url)
    if isTailscaleHost(host) then 0
    else if subnet24(host).exists(localPrefixes.contains) then 1
    else 2

  /** Preference-ordered, de-duplicated candidate list (stable within a rank). */
  def order(urls: List[String], localPrefixes: Set[String]): List[String] =
    urls.filter(_.nonEmpty).distinct.sortBy(u => rank(u, localPrefixes))

// ===== Neblink Config =====

case class NeblinkConfig(
  enabled: Boolean = false,
  syncIntervalSec: Int = 45,
  neblinkServer: Option[NeblinkServerConfig] = None,
  /** External OIDC provider for device-flow authentication (Logto stage 1).
    * When set, the gateway's device-flow start and poll routes talk to the
    * provider's RFC 8628 endpoints instead of neblink-server's self-hosted
    * ones. */
  logto: Option[LogtoConfig] = None,
  /** Agent messaging permissions (A2A 一期, spec §7.2): how the
    * SendMessage tool may send on the user's behalf. */
  agentMessaging: AgentMessagingConfig = AgentMessagingConfig()
):
  /** Login-chain resolution: an explicit `logto` block wins verbatim; a
    * missing block falls back to `LogtoConfig.embeddedDefault` so fresh
    * installs get the hosted PKCE login out of the box. PKCE consumers
    * (auth/start, silent re-login) read this instead of the raw `logto`
    * field; the legacy device-flow sites keep reading raw `logto` so their
    * no-provider branch (neblink-server proxy) stays reachable exactly as
    * before. */
  def effectiveLogto: Option[LogtoConfig] = Some(logto.getOrElse(LogtoConfig.embeddedDefault))

/**
 * Agent messaging permission tier (A2A 一期, spec §7.2-7.3). `mode`:
 *  - auto (default, user ruling 2026-08-17): send directly, no prompt; bounded
 *    by the two-layer client rate limit (perFriendPerHour / globalPerHour);
 *    on exceeding, auto-downgrades to ask (confirmation prompt) — never a hard
 *    failure.
 *  - ask: every send prompts the user (60s timeout = declined).
 *  - off: tool returns "user has disabled agent messaging".
 * Server side enforces an independent 30 msg/min token bucket (§7.3).
 */
case class AgentMessagingConfig(
  mode: String = "auto",
  perFriendPerHour: Int = 20,
  globalPerHour: Int = 60
)

object AgentMessagingConfig:
  given Encoder[AgentMessagingConfig] = deriveEncoder

  given Decoder[AgentMessagingConfig] = Decoder.instance { c =>
    for
      mode <- c.downField("mode").as[Option[String]].map(_.getOrElse("auto"))
      perFriend <- c.downField("perFriendPerHour").as[Option[Int]].map(_.getOrElse(20))
      global <- c.downField("globalPerHour").as[Option[Int]].map(_.getOrElse(60))
    yield AgentMessagingConfig(mode, perFriend, global)
  }

/** Logto (OIDC provider) connection settings — Native apps (public clients,
  * no secret). `clientId` = the device-flow app (RFC 8628 legacy + fallback);
  * `pkceClientId` = the Authorization Code + PKCE app (stage 2 primary
  * login, 2026-08-28). Separate apps because the deployed Logto pins a
  * device-flow app to the device_code grant via `isDeviceFlow` and that
  * metadata is not editable through the Management API.
  *
  * Config surface (single, deliberate): decoded from
  * `<home>/neblink/config.json` → `logto{endpoint,clientId,pkceClientId}`.
  * There is NO reader for a nebflow.json `neblink.logto` block — entries
  * there are inert (2026-08-28 dispatch misdirected the file once; qa
  * fact-checked it). */
case class LogtoConfig(
  endpoint: String,
  clientId: String,
  pkceClientId: Option[String] = None
)

object LogtoConfig:
  given Encoder[LogtoConfig] = Encoder.instance { c =>
    val base = JsonObject(
      "endpoint" -> c.endpoint.asJson,
      "clientId" -> c.clientId.asJson
    )
    Json.fromJsonObject(
      c.pkceClientId.fold(base)(v => base.add("pkceClientId", v.asJson))
    )
  }

  given Decoder[LogtoConfig] = Decoder.instance { c =>
    for
      endpoint <- c.downField("endpoint").as[String]
      // Optional with empty default (mirrors the encoder + embeddedDefault):
      // a hand-written {endpoint, pkceClientId} block must not fail the WHOLE
      // block decode just because the legacy device-flow clientId is absent
      // (2026-08-30 login-blocked finding — the block silently decoded to
      // None and the PKCE callback reported "Logto 登录未配置").
      clientId <- c.downField("clientId").as[Option[String]].map(_.getOrElse(""))
      pkceClientId <- c.downField("pkceClientId").as[Option[String]]
    yield LogtoConfig(endpoint, clientId, pkceClientId)
  }

  /** Embedded default login provider: the product's own hosted auth service
    * (production constants — public client identifiers, not secrets and not
    * user-private knowledge; the 2026-08-19 red line targets user-specific
    * runtime config like private gateways/keys, which this is not). This is
    * the distribution fallback for fresh installs whose
    * `<home>/neblink/config.json` has no `logto` block yet — without it every
    * new user silently falls back to the legacy device-flow chain
    * (beta.53 install-test finding, 2026-08-28). An explicit config.json
    * `logto` block always wins (self-hosted scenarios). `clientId` is
    * intentionally empty: the embedded default covers the PKCE login chain
    * only; the legacy device-flow chain keeps its neblink-server proxy
    * fallback and is scheduled for removal (beta.54). */
  val embeddedDefault: LogtoConfig = LogtoConfig(
    endpoint = "https://auth.nebflow.space",
    clientId = "",
    pkceClientId = Some("csxh16cas0x03bgk6w7ej")
  )

object NeblinkConfig:
  given Encoder[NeblinkConfig] = deriveEncoder

  given Decoder[NeblinkConfig] = Decoder.instance { c =>
    for
      enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(false))
      syncIntervalSec <- c.downField("syncIntervalSec").as[Option[Int]].map(_.getOrElse(45))
      // Backward compat: try "neblinkServer" first, fall back to "coordinator"
      neblinkServer <- c.downField(Protocol.neblinkServerField).as[Option[NeblinkServerConfig]].flatMap {
        case Some(config) => Right(Some(config))
        case None => c.downField("coordinator").as[Option[NeblinkServerConfig]]
      }
      logto <- c.downField("logto").as[Option[LogtoConfig]]
      agentMessaging <- c.downField("agentMessaging").as[Option[AgentMessagingConfig]].map(_.getOrElse(AgentMessagingConfig()))
    yield NeblinkConfig(enabled, syncIntervalSec, neblinkServer, logto, agentMessaging)
  }

  // def, not val: PathUtil.dataRoot is redirectable (setDataRoot); a val would
  // freeze the path at object-init and break per-test data roots (f1cd3709 rule).
  private def configPath = PathUtil.dataRoot / "neblink" / "config.json"

  def load: IO[NeblinkConfig] =
    IO.blocking {
      if os.exists(configPath) then
        decode[NeblinkConfig](os.read(configPath)) match
          case Right(c) => c
          case Left(_) => NeblinkConfig()
      else NeblinkConfig()
    }

  def save(config: NeblinkConfig): IO[Unit] =
    IO.blocking {
      os.write.over(configPath, config.asJson.spaces2, createFolders = true)
    }
end NeblinkConfig

// ===== A2A 好友与消息域类型（spec §6.1 REST 响应，客户端侧解码） =====

/** 好友/搜索结果卡（/api/users/lookup 与 /api/friends 共用形态）。
  * blocked：#290 §1.2 拉黑行透传（仅 GET /api/friends 的 friends 数组携带，
  * absent = 未拉黑）——此前该字段被网关丢弃，web 端只能靠 localStorage 镜像。 */
/** 好友/请求/会话共用的档案对象。字段即 friend-search-contract v1.0 契约词汇
 *  （NL 号 = Username，作者 2026-09-05 裁定）：username 可空语义由 String 折叠
 *  ""（未设置 NL 号）；displayName 必填（服务端永不为 null，见 Decoder 镜像
 *  fallback 链）；avatar 可 null。since/blocked 为信封字段（camelCase 维持）。
 *
 *  `remark`（2026-09-12 好友消息改造批 ⑦）：**纯本地字段**——用户设的好友备注，
 *  持久化在 `FriendRemarkStore`（`<dataRoot>/friend-remarks.json`，键 = userId），
 *  由 `FriendService.applyRemarks` 在出站口注入。**上游永不带该键**（协议零变更）：
 *  Decoder 不读它，Encoder 恒出该键（`None` ⇒ `null`，⑦-D8 加性最简形态）。
 *  形参置末且有默认值 ⇒ 既有构造点（含位置实参）零改动。 */
case class FriendSummary(
  userId: String,
  username: String,
  displayName: String,
  avatar: Option[String] = None,
  since: Option[Long] = None,
  blocked: Option[Boolean] = None,
  remark: Option[String] = None
)

/** 收到的好友请求（incoming 分组）。createdAt：请求时间透传（#290 0904 批次
  * UI 打磨——申请行时间显示；旧上游无此字段时为 None）。 */
case class FriendRequestSummary(
  requestId: String,
  from: FriendSummary,
  note: Option[String] = None,
  createdAt: Option[Long] = None
)

/** 发出的好友请求（outgoing 分组）。 */
case class OutgoingRequestSummary(
  requestId: String,
  to: FriendSummary,
  createdAt: Option[Long] = None
)

case class FriendListResponse(
  friends: List[FriendSummary],
  incoming: List[FriendRequestSummary] = Nil,
  outgoing: List[OutgoingRequestSummary] = Nil
)

case class MessageSummary(
  id: Long,
  senderId: String,
  kind: String,
  body: String,
  createdAt: Long
)

case class ConversationSummary(
  conversationId: String,
  friend: FriendSummary,
  lastMessage: Option[MessageSummary] = None,
  unreadCount: Int = 0
)

/** 客户端本地未读 cursor 状态（spec §3.4：自己看角标，无回执）。 */
case class ConversationCursor(conversationId: String, lastReadMessageId: Long, unreadCount: Int)

object FriendCodecs:
  import io.circe.Decoder
  import io.circe.Encoder
  import io.circe.HCursor
  import io.circe.generic.semiauto.*

  // ── 容错解码（2026-09-04 审计修复：契约不对齐会毁掉整个列表） ──
  // Rust 侧 wire 形态（neblink-server model.rs）与早先 spec 形态有三处偏差：
  //  ① FriendRequestEntry/Outgoing 用 #[serde(flatten)] 把 profile 铺平
  //     （无 from/to 嵌套）——deriveDecoder 的非 Option `from` 字段直接
  //     DecodingFailure → 整个 GET /api/friends 折叠为空列表；
  //  ② FriendPublic.neblink_id/name 均为 Option（存量 GitHub 账号 NULL）——
  //     null 打到非 Option String 上同样整表失败；
  //  ③ 请求条目带 created_at（新增透传）。
  // 自定义 Decoder 同时吃两种形态（flat 优先兜底嵌套缺席），空值折叠为 ""，
  // 单行坏数据不再毁整表；web 端本就有 `||` 回退显示。网关对 web 的出参
  // 契约（from/to 嵌套）由 Encoder 保持不变。
  //
  // Username 契约切换（friend-search-contract v1.0 + §8，作者 2026-09-05 裁定：
  // NL 号 = 官网 Username；统一切换、无双写别名期 §4.7）：解码以契约四字段
  // username / display_name / avatar 为准（字面 snake_case，§4.0），旧字段
  // neblinkId/name/avatarUrl 仅作窗口期回退（§4.7 发布窗口自愈，非别名期）。
  // lookup 本身为纯 Json 透传不经此 Decoder，此处护的是 /api/friends 列表链。
  // 注意不能用 get[Option].orElse：circe 的 Option 解码对「字段缺席」也
  // 成功返回 None，orElse 永远不可达——必须以 focus 区分缺席/为 null。

  /** 字段回退读：primary 缺席或为 null → fallback；都缺席 → None。 */
  private def strOr(c: HCursor, primary: String, fallback: String): Option[String] =
    def pick(name: String): Option[String] =
      c.downField(name).focus.flatMap(v => if v.isNull then None else v.asString)
    pick(primary).orElse(pick(fallback))

  /** display_name 必填语义（契约 §4.0/§3.1：服务端永不为 null，服务端 fallback
   *  链 name→username→user_id）。客户端镜像同链后折叠 ""（单行容错不毁整表，
   *  2026-09-04 审计口径）：display_name → name（窗口期旧字段）→ username →
   *  neblinkId → userId → ""。 */
  private def displayNameOf(c: HCursor): String =
    strOr(c, "display_name", "name")
      .orElse(strOr(c, "username", "neblinkId"))
      .orElse(c.downField("userId").focus.flatMap(v => if v.isNull then None else v.asString))
      .getOrElse("")

  private[neblink] def flatFriendSummary(c: HCursor): Decoder.Result[FriendSummary] =
    c.get[String]("userId").map(userId =>
      FriendSummary(
        userId,
        strOr(c, "username", "neblinkId").getOrElse(""),
        displayNameOf(c),
        strOr(c, "avatar", "avatarUrl"),
        c.get[Option[Long]]("since").getOrElse(None),
        c.get[Option[Boolean]]("blocked").getOrElse(None)
      ))

  /** 入参契约（上游 → 网关）：**不读 `remark`**（2026-09-12 ⑦）——备注是本仓
    * 本地态（`FriendRemarkStore`），上游 wire 永不带该键；即便某天带上也必须
    * 忽略，否则上游可覆盖用户自己的备注。`remark` 由 `FriendService.applyRemarks`
    * 在出站口按本地 map 注入。 */
  given Decoder[FriendSummary] = Decoder.instance { c =>
    for
      userId  <- c.get[String]("userId")
      since   <- c.get[Option[Long]]("since")
      blocked <- c.get[Option[Boolean]]("blocked")
    yield FriendSummary(
      userId,
      strOr(c, "username", "neblinkId").getOrElse(""),
      displayNameOf(c),
      strOr(c, "avatar", "avatarUrl"),
      since, blocked)
  }

  given Decoder[FriendRequestSummary] = Decoder.instance { c =>
    for
      requestId <- c.get[String]("requestId")
      from      <- c.downField("from").as[FriendSummary].orElse(flatFriendSummary(c))
      note      <- c.get[Option[String]]("note")
      createdAt <- c.get[Option[Long]]("createdAt")
    yield FriendRequestSummary(requestId, from, note, createdAt)
  }

  given Decoder[OutgoingRequestSummary] = Decoder.instance { c =>
    for
      requestId <- c.get[String]("requestId")
      to        <- c.downField("to").as[FriendSummary].orElse(flatFriendSummary(c))
      createdAt <- c.get[Option[Long]]("createdAt")
    yield OutgoingRequestSummary(requestId, to, createdAt)
  }

  given Decoder[FriendListResponse] = deriveDecoder
  given Decoder[MessageSummary] = deriveDecoder
  given Decoder[ConversationSummary] = deriveDecoder
  // Encoders for gateway REST responses (client decodes server JSON; gateway
  // re-encodes the same domain objects for the frontend UI).
  /** 出参契约钉死（friend-search-contract v1.0 §4.0/§4.5）：档案四字段字面
   *  snake_case（username / display_name / avatar；relation_status 由
   *  /api/users/search 独有），信封字段维持 camelCase（userId/since/blocked，
   *  以及上层 requestId/note/createdAt 由 deriveEncoder 保持）。网关对 web 的
   *  /api/friends、/api/conversations 内嵌档案经此 Encoder 统一切换。
   *
   *  `remark`（2026-09-12 ⑦）：**本地备注**，加性出参键，**键恒在**——未设备注
   *  时输出 `null`（不是省略键；⑦-D8 定稿「加性最简、前端不必容错两态」）。
   *  前端据此在 `GET /api/friends` 与 conversations 内嵌 `friend` 档案两处拿到
   *  备注值。 */
  given Encoder[FriendSummary] = Encoder.instance { f =>
    Json.obj(
      "userId"       -> f.userId.asJson,
      "username"     -> f.username.asJson,
      "display_name" -> f.displayName.asJson,
      "avatar"       -> f.avatar.asJson,
      "since"        -> f.since.asJson,
      "blocked"      -> f.blocked.asJson,
      "remark"       -> f.remark.asJson
    )
  }
  given Encoder[FriendRequestSummary] = deriveEncoder
  given Encoder[OutgoingRequestSummary] = deriveEncoder
  given Encoder[FriendListResponse] = deriveEncoder
  given Encoder[MessageSummary] = deriveEncoder
  given Encoder[ConversationSummary] = deriveEncoder
end FriendCodecs

// ===== Peer Description Store =====

/** Persists user-set peer descriptions across restarts. Stored in ~/.nebflow/peer-descriptions.json. */
object PeerDescriptionStore:
  // def, not val: PathUtil.dataRoot is redirectable (setDataRoot); a val would
  // freeze the path at object-init and break per-test data roots (f1cd3709 rule).
  private def path = PathUtil.dataRoot / "peer-descriptions.json"

  def load: IO[Map[String, String]] =
    IO.blocking {
      if os.exists(path) then decode[Map[String, String]](os.read(path)).getOrElse(Map.empty)
      else Map.empty
    }

  def save(descs: Map[String, String]): IO[Unit] =
    IO.blocking {
      os.write.over(path, descs.asJson.spaces2, createFolders = true)
    }

// ===== Friend Remark Store（好友备注，2026-09-12 好友消息改造批 ⑦）=====

/** 用户设置的好友备注（备注 = 本地别名），跨重启持久化：`<dataRoot>/friend-remarks.json`。
  *
  * **与 `PeerDescriptionStore` 完全同形**（方案 `20260912_011320` §4.3(a)1 定稿）：
  * 形态 `Map[String, String]`，**键 = friend `userId`**（不用 username/displayName：
  * 后者可变——NL 号可自定义、昵称可改；`userId` 是唯一稳定键，同 `⑦` 的解析与
  * 回映射口径）。
  *
  * 边界（刻意不做）：**零上游协议变更、零跨设备同步**（⑦-D1 不做）——备注按 home
  * 本地存，同族先例 `peer-descriptions.json` / `fm_blocked` / `fm_seen_requests`。
  * 权限面：读写都只在网关进程内（`FriendService`），不经任何 REST 直读文件。
  */
object FriendRemarkStore:
  // def, not val: PathUtil.dataRoot is redirectable (setDataRoot); a val would
  // freeze the path at object-init and break per-test data roots (f1cd3709 rule).
  private def path = PathUtil.dataRoot / "friend-remarks.json"

  def load: IO[Map[String, String]] =
    IO.blocking {
      if os.exists(path) then decode[Map[String, String]](os.read(path)).getOrElse(Map.empty)
      else Map.empty
    }

  def save(remarks: Map[String, String]): IO[Unit] =
    IO.blocking {
      os.write.over(path, remarks.asJson.spaces2, createFolders = true)
    }
