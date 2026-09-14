package nebflow.agent

import munit.CatsEffectSuite

/** 多 AskUser 并发批（#250 第⑤项，2026-09-13 作者裁定「6 项全补」）：
  * requestId 熵加强的**正负控**。
  *
  * 改前形态：5 个生成点各自内联 `UUID.randomUUID().toString.take(8)` —— 32 bit
  * 随机段、无作用域，而 requestId 是 hub `pending` Map 的全局唯一键（四类请求共
  * 用一个命名空间）。碰撞 ⇒ 后到者覆盖前者槽位、被覆盖的请求方永久等待。
  *
  * 证据级别：**单测级**（无运行实例、无并发运行观测）。
  */
class InteractionRequestIdSpec extends CatsEffectSuite:

  private val HexOnly = "^[0-9a-f]+$".r

  /** 判据（本 spec 的「强 requestId」定义）：`<scope>-<随机段>`，随机段 ≥16 hex。
    * 负控 = 旧口径形态（8 hex、无前缀）必须**不满足**本判据。 */
  private def wellFormed(id: String, scope: String): Boolean =
    id.startsWith(s"$scope-") && {
      val rnd = InteractionRequestId.randomPart(id)
      rnd.length >= InteractionRequestId.RandomHexChars && HexOnly.matches(rnd)
    }

  test("⑤ 正控：五个生成点都产出 `scope-<16 hex>`（随机段 ≥16 hex = ≥60 bit 熵）") {
    val samples = List(
      "ask" -> InteractionRequestId.forAskUser(),
      "asknb" -> InteractionRequestId.forAskUserNonBlocking(),
      "perm" -> InteractionRequestId.forPermission(),
      "confirm" -> InteractionRequestId.forSendConfirm(),
      "panel" -> InteractionRequestId.forDirPanel()
    )
    samples.foreach { case (scope, id) =>
      assert(wellFormed(id, scope), s"$scope 生成物不符合强判据: $id")
      assertEquals(
        InteractionRequestId.randomPart(id).length,
        InteractionRequestId.RandomHexChars,
        s"随机段长度必须是 RandomHexChars（$id）"
      )
    }
    assert(InteractionRequestId.RandomHexChars >= 16, "熵不得回落到 8 hex（32 bit，旧口径）")
  }

  test("⑤ 负控：旧口径形态（8 hex / 无作用域前缀）判据性变红——本判据确实能抓旧形态") {
    val legacy = "a1b2c3d4" // 改前 5 个生成点的产物形态
    assert(!wellFormed(legacy, "ask"), "8 hex 无前缀不得通过强判据")
    assert(!wellFormed(s"ask-$legacy", "ask"), "把 8 hex 段接到前缀后仍不得通过（长度不足）")
    assert(wellFormed("ask-" + "a1b2c3d4a1b2c3d4", "ask"), "16 hex 才通过——判据是长度敏感的，不是恒真")
  }

  test("⑤ 唯一性：20 万次生成（跨 5 个作用域）零重复") {
    val n = 40_000
    val ids = (1 to n).flatMap(_ =>
      List(
        InteractionRequestId.forAskUser(),
        InteractionRequestId.forAskUserNonBlocking(),
        InteractionRequestId.forPermission(),
        InteractionRequestId.forSendConfirm(),
        InteractionRequestId.forDirPanel()
      )
    )
    assertEquals(ids.size, n * 5)
    val distinct = ids.toSet.size
    assertEquals(distinct, ids.size, s"生成了重复 requestId（${ids.size - distinct} 个碰撞）——槽位会互相覆盖")
  }

  test("⑤ 冲突面：作用域前缀使跨类碰撞在结构上不可能（前缀互不为前缀）") {
    val prefixes = List("ask", "asknb", "perm", "confirm", "panel")
    val ids = prefixes.map { p =>
      p -> List.fill(200)(
        p match
          case "ask"     => InteractionRequestId.forAskUser()
          case "asknb"   => InteractionRequestId.forAskUserNonBlocking()
          case "perm"    => InteractionRequestId.forPermission()
          case "confirm" => InteractionRequestId.forSendConfirm()
          case _         => InteractionRequestId.forDirPanel()
      )
    }
    // 跨类零重叠（旧口径下四类共用一个 32 bit 命名空间，这是碰撞的唯一来源）
    prefixes.combinations(2).foreach { pair =>
      val a = pair(0)
      val b = pair(1)
      val ia = ids.find(_._1 == a).get._2.toSet
      val ib = ids.find(_._1 == b).get._2.toSet
      assertEquals(ia.intersect(ib).size, 0, s"$a 与 $b 的 id 空间必须不相交")
    }
    // 类内仍可能碰撞，但空间是 2^60（而非 2^32）——类内唯一性由上一个用例的 4 万次/类覆盖
    assert(ids.forall(_._2.forall(id => id.startsWith(s"${id.takeWhile(_ != '-')}-"))))
  }

  test("⑤ scope 参数不抛异常（生成器不得成为新失败点）") {
    val weird = InteractionRequestId.newId("a/b c")
    assert(weird.startsWith("abc-"), s"非法字符必须被过滤而不是抛异常: $weird")
    assert(InteractionRequestId.newId("").startsWith("x-"), "空 scope 必须回落为 x- 而不是空前缀")
  }
end InteractionRequestIdSpec
