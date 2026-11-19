/* 严格DAG第⑥步第一批:core 的 LlmInterface 在飞消费面倒置为注册器(行为保持,2026-09-26)。 */
package nebflow.core

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

/**
 * `llm.LlmInterface` 的窄投影(严格DAG第⑥步第一批):core 侧的实际调用面 =
 * 在飞查询(inflightFor / inflightCount)+ 会话级取消与 transport abort
 * (cancelInflightFor / transportAbortFor)+ 关机钩子同步全局取消
 * (cancelAllInflightSync)。签名与 object LlmInterface 对应成员逐字一致;
 * 参数/返回全为基本类型(零 llm 符号),无需收窄。llm 的 `LlmInterface` 原地
 * 混入本 trait,接线见 llm/interface.scala 的注册行。
 */
// 严格DAG第⑥步第一批裁定(2026-09-26):窄口倒置,签名镜像现实现,行为保持
trait LlmRuntime:
  def inflightFor(sessionId: String): IO[Int]
  def inflightCount: IO[Int]
  def cancelAllInflightSync(): Unit
  def cancelInflightFor(sessionId: String): IO[Int]
  def transportAbortFor(sessionId: String): IO[Int]
end LlmRuntime

object LlmRuntimePort:
  private val runtime = new AtomicReference[Option[LlmRuntime]](None)

  def set(r: LlmRuntime): Unit =
    runtime.set(Some(r))

  def clear(): Unit =
    runtime.set(None)

  // 严格DAG第⑥步第一批裁定(2026-09-26):零回归兜底——未注册时按「空在飞注册表」
  // 语义回答:inflightCount / inflightFor / cancelInflightFor / transportAbortFor
  // 返回 IO.pure(0)(计数面的空集合同型值),cancelAllInflightSync 无操作。这与
  // object LlmInterface 尚未被触碰时的空注册表在观测上逐字节一致,不产生新的
  // 失败面;注册点 = llm/interface.scala 的对象初始化行(生产 boot 与测试共用)。
  def inflightFor(sessionId: String): IO[Int] =
    runtime.get match
      case Some(r) => r.inflightFor(sessionId)
      case None => IO.pure(0)

  def inflightCount: IO[Int] =
    runtime.get match
      case Some(r) => r.inflightCount
      case None => IO.pure(0)

  def cancelAllInflightSync(): Unit =
    runtime.get match
      case Some(r) => r.cancelAllInflightSync()
      case None => ()

  def cancelInflightFor(sessionId: String): IO[Int] =
    runtime.get match
      case Some(r) => r.cancelInflightFor(sessionId)
      case None => IO.pure(0)

  def transportAbortFor(sessionId: String): IO[Int] =
    runtime.get match
      case Some(r) => r.transportAbortFor(sessionId)
      case None => IO.pure(0)
end LlmRuntimePort
