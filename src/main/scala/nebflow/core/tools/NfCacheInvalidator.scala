package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

/**
 * NfCacheInvalidator —— 引擎侧文件变更 ⇒ `nf-cache-invalidate {path}` WS 广播的
 * 单一发射点（M2 · 设计 v1 §6.1 事件失效腿）。
 *
 * 依赖方向纪律（shared ← core ← gateway）：core 的写路径工具（Write/Edit/MultiEdit）
 * 与 gateway 的文件操作 WS 面（createFile/deletePath/movePath/writeFile）是**变更事实
 * 的持有方**，但没有 gateway 的广播面 ⇒ 走既有「窄端口倒置」先例（core `EventSink` /
 * `MemoryChangeNotifier` 同族）：gateway 在装配期调 [[register]] 注入广播实现
 * （`wsHub.broadcast`），core 侧只调 [[invalidated]]。未注册 = no-op（spec/CLI 场景
 * 零副作用，fail-safe 缺省）。
 *
 * 语义：本发射点只报**路径事实**（"这个路径刚被引擎/用户改写或删除"），不做任何
 * 判定（路径在不在缓存、值不值当淘汰都在前端账本单点 `nfFileCache.js` 判）。
 * 广播帧无 sessionId ⇒ 前端 ws.js 必须把 `nf-cache-invalidate` 列入 GLOBAL
 * 消息类型（否则会被当会话内事件切走视图——同款坑见 ws.js updateProgress 注记）。
 */
object NfCacheInvalidator:

  @volatile private var sink: Json => IO[Unit] = null

  /** 装配期注入（gateway：`wsHub.broadcast`）。重复注册 = 后者胜（幂等装配）。 */
  def register(s: Json => IO[Unit]): Unit =
    sink = s

  /** 变更事实发射：一条 `nf-cache-invalidate {path}` 广播（未注册/空路径 = no-op）。 */
  def invalidated(path: String): IO[Unit] =
    val s = sink
    if s == null || path == null || path.isEmpty then IO.unit
    else s(Json.obj("type" -> "nf-cache-invalidate".asJson, "path" -> path.asJson))

  /** 测试 seam：清注册（防 spec 间泄漏）。 */
  def reset(): Unit =
    sink = null

end NfCacheInvalidator
