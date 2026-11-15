/* 严格DAG第⑥步第二批:DeviceMailAck 的 await 用面倒置为注册器(行为保持,2026-09-27)。 */
package nebflow.core

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

/**
 * `neblink.DeviceMailAck.await` 的窄投影(严格DAG第⑥步第二批 R4):DeviceMailAck
 * 本体不下沉(import core.tools.RelayExecAudit、进程内全局 Ref、logger ⇒ 非纯),
 * core 侧(MailTool 设备腿的回执登记)经本注册器调用。签名与 `DeviceMailAck.await`
 * 逐字一致((targetDeviceId, innerId) => IO[String]);neblink boot 注册
 * `DeviceMailAck.await _`(注册点 = object DeviceMailAck 对象初始化行,生产 boot 与
 * 测试直接引用该对象的 spec 共用;另有 NeblinkWiring 的统一 boot 段落幂等再注册,
 * 见 R12 接线说明)。
 */
// 严格DAG第⑥步第二批裁定(2026-09-27):注册器倒置,签名镜像现实现,行为保持
object DeviceMailAckPort:

  private val awaitFn = new AtomicReference[Option[(String, String) => IO[String]]](None)

  def set(f: (String, String) => IO[String]): Unit = awaitFn.set(Some(f))

  def clear(): Unit = awaitFn.set(None)

  // 严格DAG第⑥步第二批裁定(2026-09-27):未注册兜底 = 返回与现未完成路径同型的
  // 立即失败 IO(IO.raiseError ⇒ 调用方的 IO 链以错误收口,不静默、不伪造回执);
  // 生产 boot(NeblinkWiring 段落)与 neblink 对象初始化双注册 ⇒ 未注册只在「neblink
  // 整包未装配且统一 boot 段落未跑」的极端早启窗口可达,该窗口内设备腿本就不可用。
  def await(targetDeviceId: String, innerId: String): IO[String] =
    awaitFn.get match
      case Some(f) => f(targetDeviceId, innerId)
      case None =>
        IO.raiseError(
          new IllegalStateException(
            "DeviceMailAckPort 未注册(neblink boot 段落应注册 DeviceMailAck.await)— device-mail ack leg unavailable"
          )
        )

end DeviceMailAckPort
