package nebflow.gateway

import org.http4s.Request

/**
 * 「环回腿」判据（本地加速 M1 引擎侧批 ①/② 的共用传输事实）。
 *
 * 一个请求是否真的来自**本机**，唯一机械可信的信号 = 服务器接受的 socket
 * 对端地址（`req.remoteAddr`，ip4s 类型化值，客户端无法伪造——与 ConnGuard
 * 的信任判据同一来源、同一归一法）。请求头（Origin/Host/X-Forwarded-*）都
 * 是自申报值，不作判据。
 *
 * 归一口径逐字沿用 [[ConnGuard.normalizeIp]]（R-1b D1 修正）：v4-mapped
 * （`::ffff:127.0.0.1`）先经 `collapseMappedV4` 折回点分 v4，再比对白名单
 * `{127.0.0.1, ::1}`——双栈 socket 报告的 v4-mapped 环回必须落在环回腿内。
 *
 * 消费方（同一判据，不许第二实现）：
 *  - [[GatewayAuth]] —— `local-ticket` 会话的**腿绑定**（防 localhost 会话
 *    令牌被搬到隧道/LAN 腿重放做任意读；本地加速批 ①）；
 *  - [[HealthRoutes]] —— 同机近路探测面（v2 §3.3）：`deviceId` 披露与 CORS
 *    白名单应答都只发生在环回腿上（批 ②/④）。
 *
 * `remoteAddr = None`（隧道桥的进程内 HTTP 请求、无连接信息的测试构造）⇒
 * **不是**环回腿：保守判非（宁可走慢腿/少披露，不给未证实的腿放行）——
 * 与 GatewayAuth 的 fall-through 纪律一致，这正是隧道腿该有的答案。
 */
private[gateway] object LoopbackLeg:

  /** 归一后的环回白名单（与 ConnGuard.DefaultTrustedIps 同集）。 */
  private val LoopbackForms: Set[String] = Set("127.0.0.1", "::1")

  /** True iff the request arrived on a real loopback socket. */
  def isLoopback(req: Request[?]): Boolean =
    req.remoteAddr.fold(false)(a => LoopbackForms.contains(a.collapseMappedV4.toString))

end LoopbackLeg
