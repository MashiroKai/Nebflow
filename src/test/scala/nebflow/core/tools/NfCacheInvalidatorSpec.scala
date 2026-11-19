package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import scala.collection.mutable

/**
 * M2 缓存失效广播面（设计 v1 §6.1 事件失效腿）——[[NfCacheInvalidator]] 单点发射器。
 *
 * 质量闸映射（「失效」spec 域）：①未注册 = no-op（spec/CLI 场景零副作用，fail-safe
 * 缺省）；②发射帧形 = `{type:"nf-cache-invalidate", path}`（前端 ws.js GLOBAL 契约）；
 * ③空路径 = no-op；④重复注册 = 后者胜（幂等装配）；⑤reset 防泄漏。
 */
class NfCacheInvalidatorSpec extends CatsEffectSuite:

  test("invalidated：注册后发射标准帧形 {type, path}（前端账本按 path 淘汰的契约帧）") {
    NfCacheInvalidator.reset()
    val frames = mutable.ArrayBuffer[Json]()
    NfCacheInvalidator.register(j => IO(frames += j))
    NfCacheInvalidator.invalidated("/tmp/a/b.md").map { _ =>
      assertEquals(frames.size, 1)
      val f = frames.head
      assertEquals(f.hcursor.downField("type").as[String], Right("nf-cache-invalidate"))
      assertEquals(f.hcursor.downField("path").as[String], Right("/tmp/a/b.md"))
    }
  }

  test("invalidated：未注册 = no-op（spec/CLI 场景零副作用，不抛）") {
    NfCacheInvalidator.reset()
    // 无 register：直接发射必须安静无事。
    NfCacheInvalidator.invalidated("/tmp/x")
  }

  test("invalidated：空路径 = no-op（防御性：无路径事实就不广播）") {
    NfCacheInvalidator.reset()
    val frames = mutable.ArrayBuffer[Json]()
    NfCacheInvalidator.register(j => IO(frames += j))
    NfCacheInvalidator.invalidated("").map { _ => assertEquals(frames.size, 0) }
  }

  test("register：重复注册 = 后者胜（幂等装配，帧只走新 sink）") {
    NfCacheInvalidator.reset()
    val superseded = mutable.ArrayBuffer[Json]()
    val active = mutable.ArrayBuffer[Json]()
    NfCacheInvalidator.register(j => IO(superseded += j))
    NfCacheInvalidator.register(j => IO(active += j))
    NfCacheInvalidator.invalidated("/p").map { _ =>
      assertEquals(superseded.size, 0, "superseded sink must go silent")
      assertEquals(active.size, 1)
    }
  }

  test("reset：清注册回到未注册态（防 spec 间泄漏）") {
    NfCacheInvalidator.reset()
    val frames = mutable.ArrayBuffer[Json]()
    NfCacheInvalidator.register(j => IO(frames += j))
    NfCacheInvalidator.reset()
    NfCacheInvalidator.invalidated("/after-reset").map { _ => assertEquals(frames.size, 0) }
  }

end NfCacheInvalidatorSpec
