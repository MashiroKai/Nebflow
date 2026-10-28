package nebflow.gateway

import cats.effect.IO
import nebflow.bridge.OwnerAnchor
import nebflow.neblink.{DeviceCredential, LogtoAuthCode}

/**
 * owner 等值判据的引擎侧锚（门户身份透传批，方案 A2/A4，2026-10-28）。
 *
 * 「这个已认证会话是不是 owner 本人在用」的判据 = `session.accountSub == 引擎
 * 本机登录账号的 Logto sub`（设计 §5 A2）。本对象是那一判据的**取数半面**：
 *
 *   - **来源**：引擎本机凭据文件已持久化的 Logto id_token（`DeviceCredential.
 *     logto.idToken`——TLS 信任链落盘的令牌，非客户端帧可注入）；解码单点 =
 *     `LogtoAuthCode.decodeIdTokenClaims`（与 /api/neblink/status 的 email/name
 *     读数同一调用族），本批把 `sub` 加进 Seq（id_token 标准 claim）。
 *   - **缓存**：token 键控的内存缓存（`@volatile` 单槽）——id_token 仅在重新
 *     登录时轮换，同一 token 的解码结果（base64 + JSON parse + 标签拼装）逐帧
 *     重付是纯浪费；凭据文件换 token 即自动失效换新（随凭据文件生命周期）。
 *   - **fail-open**：凭据读不开/解码坏/sub 缺席 ⇒ `None` ⇒ resolve 的第 2 级
 *     整环缺席 ⇒ 现状默认名兜底（保守：读不到锚永不冒认 owner，与花名册不可得
 *     ⇒ 恒未登记同一条纪律）。
 *   - **隐私**：`sub` 只用于等值比较，不出本对象；标记词只携带 `label`
 *     （name → email 本地段 → 「门户账号」，[[nebflow.bridge.DeviceProvenance.accountLabel]]），
 *     完整 email 与 sub 不进模型面。
 *
 * `localAnchor` 的调用时机由调用方门控：仅当连接携会话身份（方案 A1 的
 * `SessionIdentity` 在场）才读本面——Bearer 轨/CLI 帧零文件 IO，行为逐字节不变。
 */
object OwnerIdentity:

  /** token 键控单槽缓存：`(idToken, anchor 读数)`；None 读数同样入缓存（负缓存）。 */
  @volatile private var cache: (String, Option[OwnerAnchor]) = null

  /** 缓存命中（同 token）直读；否则解码落槽（token 变 = 重新登录轮换，自动换新）。 */
  private def cachedDecode(tok: String): Option[OwnerAnchor] =
    val hit = cache
    if hit != null && hit._1 == tok then hit._2
    else
      val anchor = anchorFromIdToken(tok)
      cache = (tok, anchor)
      anchor

  /**
   * 从 id_token 解码 owner 锚（纯函数面）：`sub` 缺席/空白 ⇒ None（无锚 ⇒ 不做
   * owner 判定）；`label` 走 [[nebflow.bridge.DeviceProvenance.accountLabel]]
   * 词表（name → email 本地段 → 「门户账号」）。
   */
  def anchorFromIdToken(idToken: String): Option[OwnerAnchor] =
    val claims = LogtoAuthCode.decodeIdTokenClaims(idToken, Seq("sub", "email", "name"))
    claims
      .get("sub")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(sub => OwnerAnchor(sub, nebflow.bridge.DeviceProvenance.accountLabel(claims)))

  /**
   * 引擎本机 owner 锚读数（带缓存）：凭据文件 → id_token → 解码（token 变了才
   * 重解码）。读失败 fail-open 为 None，绝不抛——本面在消息派发热路径上，任何
   * 异常都不得阻塞消息本身（device-provenance 批同一条 fail-open 纪律）。
   */
  def localAnchor: IO[Option[OwnerAnchor]] =
    DeviceCredential.loadDiagnosed.attempt.map {
      // 双层 Either：attempt 的外层（Throwable）在此 match 剥；内层 Diagnostic
      // （凭据坏档诊断态）按 NeblinkRoutes 同款 getOrElse(None) 归一为无凭据。
      case Right(credRead) =>
        // 双层 Either：attempt 的外层（Throwable）在此 match 剥；内层 Diagnostic
        // （凭据坏档诊断态）按 NeblinkRoutes 同款 getOrElse(None) 归一为无凭据。
        val idToken: Option[String] = credRead.getOrElse(None).flatMap(_.logto.flatMap(_.idToken))
        idToken.flatMap(cachedDecode)
      case Left(_) => None // 凭据读失败（磁盘 IO 异常）＝ 无锚，默认名兜底
    }

  /** 测试面：清空单槽缓存（spec 间隔离）。 */
  def resetCache(): Unit = cache = null
