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
 * (`route -n get 192.0.2.145` → `gateway 198.51.100.1`). Same-subnet `/24` is
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

  /** `/24` prefix key (`"198.51.100."`) — the cheap "same LAN?" approximation. */
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

/** 附件元数据镜像（4b 腿 A-1；逐字对齐 neblink-server 契约件 §B.2 `M6`：
  * `{id, name, size, mime?, sha256, state}`，**camelCase**）。
  *
  * 🔴 `state` **必填**（不是 `Option`）：§B.7 的线上枚举恒为
  *   `"uploading" | "ready" | "expired"` ⇒ 客户端**总能**判读。
  *   键缺失/取值越界**不**折叠成 None（那会让「不可判」与「无附件」同形），而是
  *   落进 [[AttachmentState.Unknown]]（可判读的降级态，见 `messages.js` 渲染面）；
  *   这与 §B.3 的「旧端容忍」不冲突——那条只针对**键缺席**（服务端无附件时根本不发
  *   该键，见本文件 `attachmentsOf`）。
  *
  * 🔴 blob 是瞬态的、**元数据永久保留**（§F.2）：`state == "expired"` 时 `name`/`size`
  *   仍是真值，必须继续渲染（禁静默消失，§B.7 ②1）。 */
case class AttachmentSummary(
  id: String,
  name: String,
  size: Long,
  sha256: String,
  state: String,
  mime: Option[String] = None
):
  def stateKind: AttachmentState = AttachmentState.of(state)

  /** 可下载判据 = 线上就绪 **且** 有 id。非就绪态一律不可下载（§B.7 ②1 末句：
    * 禁渲染成「可点但点了报错」的按钮）。 */
  def downloadable: Boolean = stateKind == AttachmentState.Ready && id.nonEmpty

/** `AttachmentDto.state` 的客户端口径（唯一映射点，§B.7 ① 的线上枚举 + 越界兜底）。 */
enum AttachmentState:
  case Uploading
  case Ready
  case Expired
  case Unknown(raw: String)

object AttachmentState:
  def of(raw: String): AttachmentState = raw match
    case "uploading" => Uploading
    case "ready"     => Ready
    case "expired"   => Expired
    case other       => Unknown(other)

case class MessageSummary(
  id: Long,
  senderId: String,
  kind: String,
  body: String,
  createdAt: Long,
  /** 4b 腿 A-1 新增（§B.2 `M1`）：`Option` + 缺省 `None` ⇒ 旧端形态逐字节等价
    * （无附件消息**不含该键**，见 `FriendCodecs` 的 Encoder）。 */
  attachments: Option[List[AttachmentSummary]] = None,
  /** 批 D 新增（agent 代发 footer 标识）：消息来源 —— 线上取值 `"agent" | "user"`，
    * **键可缺席**（`#290 spec v1.1 §2.4`：wire 上可选，缺席 = `"user"`）。
    *
    * 🔴 这是**语义承载键**，不是纯展示字段（徽标 / 审计 / §7.2 限速区分）；
    *   本批**不得**把它降级为纯 UI 字段。
    *
    * 两条纪律（与 `attachments` 同款，`derive*` codec 已因 r2 扩面改手写 ⇒ 本字段
    * **必须**在**手写** decoder / encoder 两侧同时给，缺一侧即静默丢字段）：
    *  ① **解码**：键缺席 / `null` ⇒ `None`（不折叠成 `Some("user")` —— 「不可判」与
    *     「服务端明说 user」是两态，折叠会把老服务端的缺键伪装成确证值）；
    *  ② **编码**：`None` ⇒ **省键**（不是 `"origin":null`）⇒ 无来源消息的出参形态与
    *     旧形态逐字节一致，且「缺键 = user」的线上口径端到端保持。
    *
    * 键序**追加在末位**：既有位置实参调用（5 参 / 6 参）零改动。 */
  origin: Option[String] = None,
  /** MVP-2 设备会话域统一（契约 §8.3）：**发送设备 id**，仅 `kind='device'` 会话的
    * 消息非 NULL；直聊/群聊消息（两端是不同 **user**）**永不带**该键。
    *
    * 🔴 方向（out/in）**不落库、不上线**：设备会话两端是同一账号 ⇒ 方向是「每台
    * 机器的视角」（契约 §8.3 逐字「方向（out/in）由 `sender_device_id == 本机 id`
    * 重算，不落库（P2 缓解）」）。本字段是前端做那次重算的**唯一**输入。
    *
    * 键缺席 / null ⇒ `None`：legacy 直聊/群聊行的出参形态**逐字节不变**，
    * 旧客户端的解码路径零改动（与 `attachments`/`origin` 同一条纪律）。 */
  senderDeviceId: Option[String] = None
)

case class ConversationSummary(
  conversationId: String,
  friend: FriendSummary,
  lastMessage: Option[MessageSummary] = None,
  unreadCount: Int = 0,
  /** MVP-2 设备会话判别键（契约 §8.1）：`Some("device")` = 设备行；直聊行**缺键**。
    *
    * 🔴 判别**只认这个键**，不靠 `conversationId` 前缀猜（契约 §8.1 逐字：
    * 「直聊/群聊行无 `kind` / `deviceId` 两个键」）。前缀猜法会在「用户 id 恰好
    * 以 `dev:` 开头」这类退化输入上把直聊行误判成设备行。 */
  kind: Option[String] = None,
  /** MVP-2：设备行所依据的设备 id（会话 id = `dev:<deviceId>`）。设备**显示名**
    * 不在本行（`title` 恒 NULL，契约 §8.1 + §9.7）——名字由前端自己的 `peers`
    * 列表提供，服务端不冻结会过期的名字快照。 */
  deviceId: Option[String] = None
)

/** 群会话行（`GET /api/groups` 裸数组的元素，也是 `GET /api/sync/bootstrap` 的
  * `groups` 行形态；跨仓真源 = neblink-server `src/model.rs` 的 `GroupSummary`，
  * `#[serde(rename_all = "camelCase")]`）。
  *
  * 为什么有本件（gmsgsend 批 · 补充卡 §6.2）：群目标解析（`FriendRoster.resolveGroup`）
  * 需要「本用户所属、未解散群会话」的 `groupId` + `title` 两个键。此前本仓只在网关侧
  * **逐字转发**群列表（群行从未解码成领域类型）；本件是最小解码件，
  * **不新增任何线上面**（纯客户端侧解码）。
  *
  * 字段取舍（🔴 只落解析必需 + 契约已冻结的行内键，其余键由解码器忽略）：
  *  - `groupId` / `title`：解析链 L1/L2/L3 的唯二匹配键。`title` **可重名**（服务端
  *    只校验非空且 ≤64 字符，无唯一性约束）⇒ 重名走候选列表，见 `resolveGroup`。
  *  - `role` / `memberCount` / `unreadCount` / `lastMessageId` / `createdAt`：服务端
  *    冻结行内键，保留供后续面读数；**本批零消费点**。
  *  - `selfUserId`（加性小批 `53c0be7`，契约 v2.1 §11.4）：viewer 自证键。
  *    🔴 本仓**不把它当权威**（身份权威 = 服务端鉴权解出的身份）—— 只解码、不消费，
  *    缺省空串（零群账号的裸数组退化态读不到该值，服务端已明写该退化态）。
  *  - **不解码** `lastMessage`：本批零消费点，解码它会把整条消息图钉进解析路径。
  *  - 全部非必填键带缺省值 ⇒ 行内键集未来加性扩面**不破**本解码器（与 `FriendSummary`
  *    的 Option 折叠口径同族的「键缺席 = 缺省」纪律）。
  */
case class GroupSummary(
  groupId: String,
  title: String,
  role: String = "",
  memberCount: Int = 0,
  unreadCount: Int = 0,
  lastMessageId: Long = 0L,
  createdAt: Long = 0L,
  selfUserId: String = ""
)

/** 客户端本地未读 cursor 状态（spec §3.4：自己看角标，无回执）。
  *
  * ## §3.5 字段拆分（好友消息静默丢失修复批 A）
  *
  * 修前**三个语义挤在一个字段**上：拉取锚点（keyset `after=`）与已读水位共用
  * `lastReadMessageId`。两处直接后果：
  *  ① 「推进而未派发」与「派发而未推进」两态**塌成一个数** ⇒ 两种失效都不可判；
  *  ② 更坏的一条：`setRead`（前端开窗收帧即 `markConversationRead`）会**顺带把
  *     补拉锚点推走** ⇒ 落在锚点之后的未派发消息**永久不可达**（静默丢失机制之一，
  *     与「推送丢了就永远丢」同族）。`FriendMessageOriginSpec` 之外的本族回归钉子在
  *     `FriendUnreadCursorRebuildSpec` L4（本轮按其新语义同步更新）。
  *
  * 拆后**一字段一语义**（三字段互不代偿）：
  *  - `lastReadMessageId` = **已读**水位（用户读到哪儿）；唯一写入口 `FriendMessagingGuard.setRead`；
  *  - `pullAnchor`        = **拉取**水位（keyset `after=` 的唯一取值来源）；唯一写入口
  *    `FriendMessagingGuard.advancePullCursor`；
  *  - `dispatchedMax`     = **已派发**水位（已构造帧并投给 UI 的最大消息 id）；与
  *    `pullAnchor` 由**同一个原子更新**同时推进（见 `advancePullCursor`）。
  *
  * 两条不变式（`FriendPulledDispatchSpec` 逐条钉死）：
  *  ① `pullAnchor == dispatchedMax`——锚点**只在派发成功后**前进；🔴 **禁**
  *     `pullAnchor > dispatchedMax`（越过未派发条前进 = 该条此后不可达）；
  *  ② `dispatchedMax <= serverMax`（派发水位不得越过服务端本次实际返回的最大 id）。
  *
  * 纯本地态：本 case class **从不序列化/永不入 wire** ⇒ 零跨仓依赖（§3.5）。
  *
  * 后两位参数取缺省 0L：既有构造点 `ConversationCursor(id, 0L, 0)` 全部保持可编译，
  * 且缺省值 = 「全新会话，尚未拉取/尚未派发」，与四条兄弟路径的语义逐字一致。 */
case class ConversationCursor(
  conversationId: String,
  lastReadMessageId: Long,
  unreadCount: Int,
  pullAnchor: Long = 0L,
  dispatchedMax: Long = 0L
)

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

  // ── 附件镜像面（4b 腿 A-1，§B.2 M1/M6 + §B.3）─────────────────────────
  //
  // 容错方向（**单点**，与上文 FriendSummary 的 2026-09-04 审计口径同源）：
  //   ① **键缺席 / null** ⇒ None —— 这就是 §B.3 的「旧端容忍 = 逐字节等价现状」
  //      （服务端只在真有附件时下发该键）；
  //   ② 数组 ⇒ 逐条解码，**任何一条都不丢弃**：字段坏掉一项就折成安全缺省并让
  //      `state` 落 Unknown（渲染成「附件状态不可判读」灰卡），绝不把附件条目从
  //      消息里抹掉 —— 「静默丢弃附件」正是本批（§B.7 ②1）明令禁止的缺陷形态；
  //   ③ 出现了非数组值（越出契约）⇒ 合成一条**不可判读条目**，同样是「可见的降级」
  //      而不是静默消失。
  //   ④ 其余 5 个既有字段（id/senderId/kind/body/createdAt）**语义零变更**。
  private def strField(c: HCursor, name: String): Option[String] =
    c.downField(name).focus.flatMap(v => if v.isNull then None else v.asString)

  private def longField(c: HCursor, name: String): Option[Long] =
    c.downField(name).focus.flatMap(v => if v.isNull then None else v.asNumber.flatMap(_.toLong))

  /** 不可判读条目的**唯一**构造点（id 空 ⇒ 不可下载 ⇒ 前端渲染降级卡）。 */
  private def unreadableAttachment(rawState: String): AttachmentSummary =
    AttachmentSummary(id = "", name = "", size = 0L, sha256 = "", state = rawState)

  given Decoder[AttachmentSummary] = Decoder.instance { c =>
    Right(
      AttachmentSummary(
        id = strField(c, "id").getOrElse(""),
        name = strField(c, "name").getOrElse(""),
        size = longField(c, "size").getOrElse(0L),
        sha256 = strField(c, "sha256").getOrElse(""),
        state = strField(c, "state").getOrElse(""),
        mime = strField(c, "mime")
      )
    )
  }

  private def attachmentsOf(c: HCursor): Option[List[AttachmentSummary]] =
    c.downField("attachments").focus match
      case None => None
      case Some(v) if v.isNull => None
      case Some(v) =>
        v.asArray match
          case Some(items) =>
            Some(items.toList.map(_.as[AttachmentSummary].getOrElse(unreadableAttachment(""))))
          case None => Some(List(unreadableAttachment(v.noSpaces)))

  given Decoder[MessageSummary] = Decoder.instance { c =>
    for
      id        <- c.get[Long]("id")
      senderId  <- c.get[String]("senderId")
      kind      <- c.get[String]("kind")
      body      <- c.get[String]("body")
      createdAt <- c.get[Long]("createdAt")
      // 批 D：`origin` 可选（键缺席 / null ⇒ None）。手写 decoder 的每一行都是一条
      // 白名单 —— 漏一行即静默丢字段（本批的病灶形态），故此处与 encoder 成对维护。
      origin    <- c.get[Option[String]]("origin")
      // MVP-2（契约 §8.3）：设备消息的发送设备 id —— 与 `origin` 同款「键缺席 /
      // null ⇒ None」，直聊/群聊行永不带该键 ⇒ 解码结果与旧形态一致。
      senderDeviceId <- c.get[Option[String]]("senderDeviceId")
    yield MessageSummary(id, senderId, kind, body, createdAt, attachmentsOf(c), origin, senderDeviceId)
  }

  /** 会话行解码（MVP-2 加性扩面 · 契约 §8.1）：`kind` / `deviceId` 两个键**只在
    * 设备行**出现 ⇒ 一律 `Option` 宽容解码（缺席 / null ⇒ `None`），直聊/群聊行
    * 解码结果与 MVP-2 之前**逐字段相同**。
    *
    * 🔴 `lastMessage` 仍是同一条解码链（`MessageSummary` 的 `senderDeviceId` 由上面
    * 的手写 decoder 一并带上）——不得为设备行开第二条会话解码路径。 */
  given Decoder[ConversationSummary] = Decoder.instance { c =>
    for
      conversationId <- c.get[String]("conversationId")
      friend         <- c.downField("friend").as[FriendSummary]
      lastMessage    <- c.get[Option[MessageSummary]]("lastMessage")
      unreadCount    <- c.get[Option[Int]]("unreadCount")
      kind           <- c.get[Option[String]]("kind")
      deviceId       <- c.get[Option[String]]("deviceId")
    yield ConversationSummary(conversationId, friend, lastMessage, unreadCount.getOrElse(0), kind, deviceId)
  }

  /** 群行解码（gmsgsend 批）：两个解析键 `groupId`/`title` 为**硬键**（缺席即解码
    * 失败 —— 缺这两个键的「群行」对群寻址无意义，且**不得**退化成空串后参与 L1
    * 精确匹配：空串 groupId 会把任何 `group:` 空串查询变成一次假命中）。
    *
    * 其余键一律**宽容**（缺席 / `null` ⇒ 缺省值）：它们是加性行内键（`selfUserId`
    * 即加性小批新增），上游老版本缺席时**不得**让整份群列表解码失败 —— 那会把
    * 「一个可选键缺席」升级成「群全部不可寻址」（本仓「静默不达」缺陷族）。
    * 🔴 宽容仅限**非解析键**：解析键的缺席仍是硬失败（显式，不静默）。 */
  given Decoder[GroupSummary] = Decoder.instance { c =>
    for
      groupId <- c.get[String]("groupId")
      title   <- c.get[String]("title")
    yield GroupSummary(
      groupId = groupId,
      title = title,
      role = strField(c, "role").getOrElse(""),
      memberCount = longField(c, "memberCount").map(_.toInt).getOrElse(0),
      unreadCount = longField(c, "unreadCount").map(_.toInt).getOrElse(0),
      lastMessageId = longField(c, "lastMessageId").getOrElse(0L),
      createdAt = longField(c, "createdAt").getOrElse(0L),
      selfUserId = strField(c, "selfUserId").getOrElse("")
    )
  }
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

  /** 附件出参（§B.2 M6）：`mime` 缺席即**省键**（`skip_serializing_if` 同形）。 */
  given Encoder[AttachmentSummary] = Encoder.instance { a =>
    Json.fromFields(
      List(
        Some("id" -> a.id.asJson),
        Some("name" -> a.name.asJson),
        Some("size" -> a.size.asJson),
        a.mime.map(m => "mime" -> m.asJson),
        Some("sha256" -> a.sha256.asJson),
        Some("state" -> a.state.asJson)
      ).flatten
    )
  }

  /** 🔴 出参**不得静默丢弃**附件字段（4b 腿 A-1 的病灶：网关 REST 出口
    * `convs.asJson` / `r.map(_.asJson)` 用本编码器重编码，`deriveEncoder` 会按
    * case class 形状过滤掉服务端已下发的键）。
    *
    * 两条硬约束：
    *  ① **无附件 ⇒ 不含 `attachments` 键**（不是 `null`）：§B.3 要求服务端→旧端
    *     路径逐字节不变；`deriveEncoder` 会输出 `"attachments":null` ⇒ 字节不等价。
    *     故此处手写编码器，前 5 键顺序与旧形态逐字一致。
    *  ② 有附件 ⇒ 数组随消息一起出（含空数组：`Some(Nil)` 与「无附件」同义，省键）。
    *
    * 批 D 加性扩面（同一条纪律）：`origin` 为 `Some` ⇒ 随消息一起出；`None` ⇒
    * **省键**（不是 `null`）⇒ 无来源消息的出参形态与旧形态逐字节一致，且
    * 「缺键 = `user`」的线上口径（`#290 spec v1.1 §2.4`）端到端保持。 */
  given Encoder[MessageSummary] = Encoder.instance { m =>
    val legacy = List(
      "id"        -> m.id.asJson,
      "senderId"  -> m.senderId.asJson,
      "kind"      -> m.kind.asJson,
      "body"      -> m.body.asJson,
      "createdAt" -> m.createdAt.asJson
    )
    Json.fromFields(
      legacy
        ++ m.origin.map(o => "origin" -> o.asJson)
        ++ m.attachments.filter(_.nonEmpty).map(a => "attachments" -> a.asJson)
        // MVP-2（契约 §8.3）：设备消息多出**第 8 键**（`senderDeviceId`）；
        // `None` ⇒ **省键**（不是 `null`）⇒ legacy 直聊/群聊消息的出参形态与
        // 旧形态**逐字节一致**（与 `origin` / `attachments` 同一条纪律）。
        ++ m.senderDeviceId.map(d => "senderDeviceId" -> d.asJson)
    )
  }

  /** 会话行出参：**手写**（替换 `deriveEncoder`）—— 理由是 `kind` / `deviceId` 必须
    * **省键**而非输出 `null`（契约 §8.1 逐字：「直聊/群聊行无 `kind` / `deviceId`
    * 两个键」，legacy 响应**逐字不变**）；`deriveEncoder` 会把 `None` 写成
    * `"kind":null`，即在每一条直聊行上多出两个键（静默改线上形态）。
    *
    * 🔴 既有四键（`conversationId` / `friend` / `lastMessage` / `unreadCount`）的
    * **顺序与取值逐字不变**，`lastMessage = None` 仍输出 `null`（不是省键）——
    * 这是本编码器与旧 `deriveEncoder` 的唯一差异面：只**追加**两个仅在设备行出现的键。
    * 🔴 加性扩面须与本行成对维护：新增会话行字段时，忘改本编码器 = 静默丢字段。 */
  given Encoder[ConversationSummary] = Encoder.instance { cv =>
    Json.fromFields(
      List(
        Some("conversationId" -> cv.conversationId.asJson),
        Some("friend" -> cv.friend.asJson),
        Some("lastMessage" -> cv.lastMessage.asJson),
        Some("unreadCount" -> cv.unreadCount.asJson),
        cv.kind.map(k => "kind" -> k.asJson),
        cv.deviceId.map(d => "deviceId" -> d.asJson)
      ).flatten
    )
  }
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
