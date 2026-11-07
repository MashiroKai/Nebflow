package nebflow.gateway.nf

import nebflow.gateway.*

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.{Files, Paths}

/**
 * 细粒度单元（nf 模块）：serve 闸的计数语义 + 配置纯解析 + 票 TTL 纯解析 +
 * deny 日志字面 + 路径归一/文件身份。
 *
 * 既有 `NfServeGateSpec` / `NfTicketRoutesSpec` / `NfFileRoutesSpec` 走的是路由与
 * 真配置文件。这里只钉「一个函数一件事」：解析、夹取、计数、字面。
 */
class NfUnitSpec extends CatsEffectSuite:

  // ── NfServeGate：无等待的槽位计数 ─────────────────────────────────────

  test("tryAcquire/release: cap admits N, refuses N+1, release returns the slot"):
    val gate = NfServeGate.apply(2)
    (for
      a <- gate.tryAcquire
      b <- gate.tryAcquire
      c <- gate.tryAcquire
      inFlightFull <- gate.inFlightNow
      _ <- gate.release
      d <- gate.tryAcquire
      inFlightAfter <- gate.inFlightNow
      _ <- gate.release
      _ <- gate.release
    yield (a, b, c, inFlightFull, d, inFlightAfter)).map { case (a, b, c, full, d, after) =>
      assertEquals(List(a, b), List(true, true))
      assertEquals(c, false, "third slot must be refused without waiting")
      assertEquals(full, 2)
      assertEquals(d, true, "released slot is immediately reusable")
      assertEquals(after, 2, "released then re-acquired ⇒ back at the cap")
    }

  test("apply: maxConcurrent is clamped into [1, 4096]"):
    assertEquals(NfServeGate.apply(0).maxConcurrent, NfServeGate.MinMaxConcurrent)
    assertEquals(NfServeGate.apply(-7).maxConcurrent, NfServeGate.MinMaxConcurrent)
    assertEquals(NfServeGate.apply(8).maxConcurrent, 8)
    assertEquals(NfServeGate.apply(999999).maxConcurrent, NfServeGate.MaxMaxConcurrent)

  test("parseMaxConcurrent: a positive integer under nfFile.serveConcurrency counts"):
    assertEquals(NfServeGate.parseMaxConcurrent("""{"nfFile":{"serveConcurrency":8}}"""), Some(8))
    assertEquals(NfServeGate.parseMaxConcurrent("""{"nfFile":{"serveConcurrency":0}}"""), None)
    // 实测：circe 的 as[Int] 对数字字符串宽容（与 gatewayConfig.configFilePort 显式 asNumber 的口径不同）
    assertEquals(NfServeGate.parseMaxConcurrent("""{"nfFile":{"serveConcurrency":"8"}}"""), Some(8))
    assertEquals(NfServeGate.parseMaxConcurrent("""{"nfFile":{"serveConcurrency":"x"}}"""), None)
    assertEquals(NfServeGate.parseMaxConcurrent("""{"serveConcurrency":8}"""), None)
    assertEquals(NfServeGate.parseMaxConcurrent("not json"), None)

  // ── NfTicketStore：TTL 纯解析与构造夹取 ───────────────────────────────

  test("parseTtl: a positive value under nfFile.ticketTtlSeconds counts, non-positive/absent does not"):
    assertEquals(NfTicketStore.parseTtl("""{"nfFile":{"ticketTtlSeconds":600}}"""), Some(600L))
    assertEquals(NfTicketStore.parseTtl("""{"nfFile":{"ticketTtlSeconds":-5}}"""), None)
    // 同 parseMaxConcurrent：数字字符串被 circe as[Long] 宽容解码（实测行为，非注释口径）
    assertEquals(NfTicketStore.parseTtl("""{"nfFile":{"ticketTtlSeconds":"600"}}"""), Some(600L))
    assertEquals(NfTicketStore.parseTtl("""{"nfFile":{"ticketTtlSeconds":"x"}}"""), None)
    assertEquals(NfTicketStore.parseTtl("{}"), None)

  test("unsafeCreate: non-positive TTL falls back to DefaultTtlSeconds"):
    (for
      dflt <- NfTicketStore.unsafeDefault().ttlSeconds
      zero <- NfTicketStore.unsafeCreate(0).ttlSeconds
      neg <- NfTicketStore.unsafeCreate(-5).ttlSeconds
      custom <- NfTicketStore.unsafeCreate(60).ttlSeconds
    yield (dflt, zero, neg, custom)).map { case (dflt, zero, neg, custom) =>
      assertEquals(dflt, NfTicketStore.DefaultTtlSeconds)
      assertEquals(zero, NfTicketStore.DefaultTtlSeconds)
      assertEquals(neg, NfTicketStore.DefaultTtlSeconds)
      assertEquals(custom, 60L)
    }

  // ── NfFileRoutes：deny 日志单行字面 ───────────────────────────────────

  test("denyLogLine: one greppable line, fields in the frozen order"):
    assertEquals(
      NfFileRoutes.denyLogLine(403, "outside-workspace", "/a/b.txt", "192.168.1.9"),
      "nf-file denied: status=403 reason=outside-workspace path=/a/b.txt remote=192.168.1.9"
    )

  // ── NfFilePolicy.NfPathPolicy：归一与文件身份 ─────────────────────────

  test("canonicalOrSelf: existing path canonicalizes, missing path stays absolute+normalized"):
    val tmp = Files.createTempFile("nf-unit", ".txt")
    try assertEquals(NfFilePolicy.NfPathPolicy.canonicalOrSelf(tmp), tmp.toRealPath())
    finally Files.deleteIfExists(tmp)

    val missing = Paths.get("build", "definitely-missing-nf-unit")
    assertEquals(NfFilePolicy.NfPathPolicy.canonicalOrSelf(missing), missing.toAbsolutePath.normalize())

  test("inodeKey: Some for an existing file where the JVM exposes a fileKey, None when gone"):
    val tmp = Files.createTempFile("nf-unit", ".txt")
    // ⚠ 平台差异：Windows 的 BasicFileAttributes.fileKey() 常为 null ⇒ inodeKey=None；
    // 这里只钉「有值时非空」+「路径消失必 None」两条不依赖平台强弱的判据。
    try assert(NfFilePolicy.NfPathPolicy.inodeKey(tmp).forall(_.nonEmpty))
    finally Files.deleteIfExists(tmp)
    assertEquals(NfFilePolicy.NfPathPolicy.inodeKey(tmp), None)

end NfUnitSpec
