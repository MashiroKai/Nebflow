package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.UiMessage

/**
 * WS `historyPage` 页指纹（M2 · 设计 v2 §3.1 ①「本地权威先行」的**数据基础刀**）。
 *
 * 帧附 `pageFingerprint: {total, offset, last, lastAt, pageLen}`——历史 append-only
 * （非尾页不可变）⇒ 前端可据指纹判「本页与上次拉取同页」，为重连/翻页的
 * 「先渲本地、指纹不符拉差」对账提供键。
 *
 * ⚠ 申报口径：指纹是**对账基础数据刀**，不含渲染行为改动——「本地先行渲染」与
 * persistence.js 既有「后端唯一真源」契约的张力是设计件登记的开放问题 Q18（候作者
 * 裁）；本批只把键发出来 + 前端存指纹（`histfp:` 账，main.js），不抢先裁渲染语义。
 *
 * 键的取值（页面成员决定，非消息 id——UiMessage 无消息 id，索引身份在 append-only
 * 前提下稳定；尾页例外 = recall 撤回路径，其变更同时改变 `total` ⇒ 指纹必变）：
 *   - `last`    = `offset + pageLen - 1`（页面末条的全局索引；空页 = -1）
 *   - `lastAt`  = 末条消息时间戳（User/Ai 带 ts；Tool 行无 ts ⇒ 0——与 `last` 组合
 *     仍唯一判页：同索引不同型/不同长 ⇒ total 或 pageLen 变）
 *   - `total`/`pageLen` = 行数读数（撤回/追加即时反映）
 */
private[gateway] object HistoryPageFingerprint:

  def lastTimestamp(msgs: List[UiMessage]): Long =
    msgs.lastOption match
      case Some(u: UiMessage.User) => u.timestamp
      case Some(a: UiMessage.Ai)   => a.timestamp
      case _                       => 0L

  /** 帧指纹对象（空页安全：last = -1）。 */
  def of(msgs: List[UiMessage], total: Long, offset: Long): Json =
    val pageLen = msgs.size.toLong
    Json.obj(
      "total" -> total.asJson,
      "offset" -> offset.asJson,
      "pageLen" -> pageLen.asJson,
      "last" -> (if pageLen == 0L then -1L else offset + pageLen - 1L).asJson,
      "lastAt" -> lastTimestamp(msgs).asJson
    )

end HistoryPageFingerprint
