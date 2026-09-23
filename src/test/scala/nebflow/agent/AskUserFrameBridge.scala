package nebflow.agent

import io.circe.Json
import nebflow.core.AskItem

/**
 * 测试面桥（S3 批 r4 返工；作者 2026-09-17 裁定 R3 帧级红因 = **(甲) 测试面缺陷**）：
 * 把测试桩的 askUser 载荷接到**生产序列化单点**
 * `AgentActor.buildAskUserJson`（`AgentActor.scala:834`；生产调用点
 * `AgentActor.scala:2554` 与 `SendConfirm.scala:141`）。
 *
 * 存在理由：该单点为 `private[agent]`，而调用方
 * `ProjectCreatePanelSpec` 位于包 `nebflow.core.project` ⇒ 包外不可见。帧级断言若要
 * 检**真 `AskItem` 序列化输出**（而非测试自造的字面量，即被裁定的 (甲) 缺陷本体），
 * 只能由本包内的桥转接。
 *
 * **生产面零改动**：本件只**调用**该单点，不复制、不改写任何序列化逻辑；桩也不再
 * 自造 item 字面量（原桩硬写 `question`/`options`/`allowOther` 三键 ⇒ 结构性不含
 * `dirPicker`/`freeInput`）。副作用（正向）：生产发射面改动可使帧级断言转红
 * ⇒ 该断言的红锚可分离性恢复。
 */
object AskUserFrameBridge:

  /**
   * 与生产调用点 `AgentActor.scala:2554` 同参形态：首参 `Some(rootSid)` 即
   * `state.session.rootSessionId`、`sourceSession` 同源；本测试面无
   * project/nodeName（与 panel 链一致）。
   */
  def payload(rootSid: String, items: List[AskItem]): Json =
    AgentActor.buildAskUserJson(
      Some(rootSid),
      "Nebula",
      items,
      Some("Nebula"),
      Some(rootSid)
    )
end AskUserFrameBridge
