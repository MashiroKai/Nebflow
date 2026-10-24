package nebflow.gateway

import java.net.{Inet4Address, InetAddress, NetworkInterface}

import scala.jdk.CollectionConverters.*

/**
 * 本机 LAN 地址枚举（console-fastpath 批 2026-10-23，作者令三腿之「设备识别
 * 快速通道」）——给近路探测端点 `/api/neblink/nearpath` 提供「已知网关地址」
 * 候选集：浏览端拿它逐个 nf-reach 探测，命中即换 LAN 直传 / 近路跳转。
 *
 * 过滤口径（保守，宁可少报不可错报）：
 *   - 只取 IPv4（浏览端 fetch 目标，IPv6 字面量需括号转义，近路收益低复杂度实）；
 *   - 只取 site-local（RFC1918 10/172.16-31/192.168）——与
 *     [[RestApiCtx.isPrivateLanIp]] 同一词表，公网地址不回（多拨/直连宽带的
 *     全球单播 IP 不做近路候选：跨境语义未定义，错报比漏报贵）；
 *   - 剔除环回与链路本地（169.254 自分配，非可信 LAN 证据，A2 判据同族）；
 *   - 网卡必须 up 且非 point-to-point（VPN/tun 接口的地址换了网段往往不可达，
 *     枚举进来只是探测噪音；utmbridge 等桥接实地址仍回）。
 *   - 每调用现场枚举（不缓存）：DHCP 换租约 / 换网场景要拿新值，且调用频率
 *     低（会话打开 / 重连一拍），枚举成本（毫秒级）可忽略。
 */
private[gateway] object LanAddresses:

  /** 至多回这么多个候选（多网卡机器防刷屏）。 */
  private val MaxCandidates = 8

  /**
   * 本机的私网 IPv4 列表（去重、字典序稳定——spec 按集合断言，顺序无关）。
   * 枚举任何异常一律按「无候选」回（降级语义：浏览端停留 relay，绝不可因
   * 枚举失败把近路判定卡死）。
   */
  def list(): List[String] =
    try
      NetworkInterface.getNetworkInterfaces.asScala.toList
        .filter { ni =>
          try ni.isUp && !ni.isLoopback && !ni.isPointToPoint
          catch case _: Throwable => false
        }
        .flatMap { ni =>
          try ni.getInetAddresses.asScala.toList
          catch case _: Throwable => Nil
        }
        .collect {
          case a: Inet4Address if a.isSiteLocalAddress && !a.isLoopbackAddress && !a.isLinkLocalAddress =>
            a.getHostAddress
        }
        .distinct
        .sorted
        .take(MaxCandidates)
    catch case _: Throwable => Nil

end LanAddresses
