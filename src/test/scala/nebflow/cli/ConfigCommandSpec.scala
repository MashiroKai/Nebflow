package nebflow.cli

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

/** qa #339 打回防回归：buildNestedJson 末段路径必须作键名（set/get 对称）。
  * 旧基例返回裸值——`set a.b.c true` 实发 {"b": true}，静默丢层写错位置；
  * 单段路径旧实现返回裸值（无键），服务端 mergeConfig leaf 分支会整文件替换。 */
class ConfigCommandSpec extends FunSuite:

  private def parse(s: String): Json = io.circe.parser.parse(s).fold(e => throw e, identity)

  test("三段路径：全深度嵌套，末段是键名") {
    val got = ConfigCommand.buildNestedJson(List("llm", "providers", "queuePersist"), "true")
    assertEquals(got, parse("""{"llm":{"providers":{"queuePersist":true}}}"""))
  }

  test("四段路径（真实 provider 字段形状）：无丢层") {
    val got = ConfigCommand.buildNestedJson(List("llm", "providers", "openai", "queuePersist"), "true")
    assertEquals(got, parse("""{"llm":{"providers":{"openai":{"queuePersist":true}}}}"""))
  }

  test("单段路径：包成对象（防服务端整文件替换）") {
    val got = ConfigCommand.buildNestedJson(List("stuckThresholdMs"), "600000")
    assertEquals(got, parse("""{"stuckThresholdMs":600000}"""))
  }

  test("值解析：JSON 字面量按类型、裸串回退字符串") {
    assertEquals(
      ConfigCommand.buildNestedJson(List("a", "flag"), "false"),
      parse("""{"a":{"flag":false}}""")
    )
    assertEquals(
      ConfigCommand.buildNestedJson(List("a", "name"), "hello world"),
      parse("""{"a":{"name":"hello world"}}""")
    )
  }

  test("空路径：Null（CLI 侧 key 非空守卫之外的兜底）") {
    assertEquals(ConfigCommand.buildNestedJson(Nil, "x"), Json.Null)
  }

  test("与 ConfigGet 下钻对称：build 出的树可用同名路径读回") {
    val key = "workSchedule.enabled".split("\\.").toList
    val built = ConfigCommand.buildNestedJson(key, "false")
    val readBack = key.foldLeft(built)((j, seg) => j.hcursor.downField(seg).as[Json].getOrElse(Json.Null))
    assertEquals(readBack, false.asJson)
  }
end ConfigCommandSpec
