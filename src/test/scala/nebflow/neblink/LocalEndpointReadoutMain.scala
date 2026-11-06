package nebflow.neblink

import nebflow.neblink.core.EndpointPreference

import nebflow.neblink.core.NeblinkServerConfig

import nebflow.neblink.core.NeblinkClient

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*

/**
 * IPv6 优先改造的**真机读数夹具**（2026-11-04，非 spec）：
 * `sbt "Test/runMain nebflow.neblink.LocalEndpointReadoutMain"`。
 *
 * 为什么单列一个文件：`NeblinkEndpoint` 是 `private[neblink]`，候选面读数只能在
 * `nebflow.neblink` 包内取；把它塞进 `core.tools` 的夹具里就得要么放宽可见性、要么在
 * 别的包里绕可见性——两条都不该为了「打一条读数」去做。
 *
 * 输出 `MEASURE ipv6 <json>`：本机**实际**上报的候选面（改前只取 `Inet4Address`，
 * 双栈主机的 v6 面整条被丢）+ `preferV6` 的排序结果 + 「无 v6 环境原样返回」的自检。
 */
object LocalEndpointReadoutMain:

  def main(args: Array[String]): Unit =
    val client = new NeblinkClient(
      NeblinkServerConfig(url = "http://unused.invalid", networkId = "n", secret = "s"),
      6138
    )
    val list = client.detectLocalEndpoints.attempt.unsafeRunSync().getOrElse(Nil)
    val v6 = list.filter(e => EndpointPreference.isIpv6Literal(e.address))
    val v4 = list.filterNot(e => EndpointPreference.isIpv6Literal(e.address))
    // 改前语义（只取 Inet4Address）的等价读数：v4 条数与顺序。
    val asBefore = v4
    println(
      s"MEASURE ipv6 ${
          io.circe.Json
            .obj(
              "reported" -> list.map(e => s"${e.address}:${e.port}#${e.kind}@${e.label}").asJson,
              "v6Count" -> v6.size.asJson,
              "v4Count" -> v4.size.asJson,
              "v6First" -> (if v6.isEmpty || v4.isEmpty then "n/a" else list.headOption.exists(v6.contains).toString).asJson,
              "preV6Behaviour" -> asBefore.map(_.address).asJson,
              "noV6EnvIdentity" -> (PresenceCandidateProbe.preferV6(v4) == v4).asJson,
              "maxV6Slots" -> PresenceCandidateProbe.MaxV6Slots.asJson
            )
            .noSpaces
        }"
    )
