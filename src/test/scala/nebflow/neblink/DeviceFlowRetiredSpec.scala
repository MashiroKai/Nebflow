package nebflow.neblink

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * 设备码流退役面清理断言（台账 #924, 2026-10-26）。
 *
 * 作者裁定：CLI `--device-code` 子命令、网关 device-flow 路由、中继 device-code
 * 端点族（本仓侧词汇）、官网 /device 批准页 —— 全部下线，**不留死面**。本件把
 * 「不留死面」变成回归闸：退役标识符在本仓各活面文件里必须是**零命中**。
 *
 * 范围 = 本仓写面（统一登录批的拆除清单）。有意排除：
 *   · src/main/resources/seed/plugins/slideblocks 树 —— slideblocks 插件自己的
 *     设备码流（独立产品协议，与登录设备码无关）；
 *   · reports/、evidence/ —— 历史留档（设备码死局报告等），档案不是活面；
 *   · 中继（link-server, Rust）与官网仓 —— 跨仓面，本仓只断言自己的调用词汇。
 * 注释里「retired / 退役」的历史性提及**允许**存在 —— 断言只对准活的调用词汇
 * （路由字面量、函数名、CLI 旗标、wire 字段名），不搞全词封禁。
 */
class DeviceFlowRetiredSpec extends FunSuite:

  private def repoRoot: Path =
    val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath
    // sbt 以模块基目录为 cwd；向上找含 build.sbt 的根（子模块安全）。
    Iterator
      .iterate(cwd)(_.getParent)
      .take(6)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(cwd)

  private def read(rel: String): String =
    val p = repoRoot.resolve(rel)
    if !Files.exists(p) then fail(s"清理断言找不到活面文件: $p（文件被移动时请同步本清单）")
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /**
   * 剥注释（块注释与行注释）——断言只对准**代码面**：注释里的历史性提及
   * （「retired」「退役」的记事）不算死面。朴素扫描对本批面足够（不处理字符串
   * 内容的边界，剥除只会变少不会误红——缺席闸宁可过宽不可误报）。
   */
  private def stripComments(src: String): String =
    val sb = new StringBuilder(src.length)
    var i = 0
    var inBlock = false
    var inLine = false
    while i < src.length do
      val rest = src.substring(i)
      if inBlock then
        if rest.startsWith("*/") then
          inBlock = false; i += 2; sb.append(' ')
        else i += 1
      else if inLine then
        if src.charAt(i) == '\n' then
          inLine = false; sb.append('\n'); i += 1
        else i += 1
      else if rest.startsWith("/*") then
        inBlock = true; i += 2; sb.append(' ')
      else if rest.startsWith("//") then
        inLine = true; i += 2
      else
        sb.append(src.charAt(i)); i += 1
    end while
    sb.toString
  end stripComments

  private def codeOf(rel: String): String = stripComments(read(rel))

  /** 断言文件**代码面**不含任何退役字面量。 */
  private def assertRetiredAbsent(rel: String, retired: List[String]): Unit =
    val content = codeOf(rel)
    for lit <- retired do assert(!content.contains(lit), s"$rel 代码面含退役字面量「$lit」 — 设备码死面复活了？")

  test("neblink 包：设备流客户端与 RFC 8628 词汇已退役") {
    assertRetiredAbsent(
      "src/main/scala/nebflow/neblink/LogtoTransport.scala",
      List("PollOutcome", "startRequest", "pollRequest", "classifyPoll", "parseStartResponse", "/oidc/device/auth")
    )
    assertRetiredAbsent(
      "src/main/scala/nebflow/neblink/Protocol.scala",
      List("deviceAuth", "/oidc/device/auth", "/api/device/code", "/api/device/token")
    )
    assert(
      !Files.exists(repoRoot.resolve("src/main/scala/nebflow/neblink/LogtoDeviceFlow.scala")),
      "LogtoDeviceFlow.scala 必须删除"
    )
  }

  test("网关：device-flow 路由字面量已退役") {
    assertRetiredAbsent(
      "src/main/scala/nebflow/gateway/NeblinkRoutes.scala",
      List("device-flow", "DeviceApi.code", "DeviceApi.token", "/api/device/code", "/api/device/token")
    )
    assertRetiredAbsent(
      "src/main/scala/nebflow/gateway/RestApiCtx.scala",
      List("device-flow")
    )
  }

  test("CLI：--device-code 旗标与设备码提示面已退役") {
    assertRetiredAbsent(
      "src/main/scala/nebflow/cli/LoginCommand.scala",
      List(
        "--device-code",
        "device-flow",
        "DevicePrompt",
        "promptLines",
        "userCode",
        "deviceCode",
        "authorization_pending",
        "PollStep",
        "heartbeatLine",
        "expired_token",
        "access_denied"
      )
    )
  }

  test("web：前端设备流面（函数、状态、轮询、文案键）已退役") {
    assertRetiredAbsent(
      "src/main/resources/web/js/neblink.js",
      List("device-flow", "deviceCode", "startDeviceFlow", "pollDeviceFlow", "cancelDeviceFlow", "userCode")
    )
    assertRetiredAbsent(
      "src/main/resources/web/js/activityBar.js",
      List(
        "device-flow",
        "waiting-device",
        "userCode",
        "deviceCode",
        "startDeviceFlow",
        "pollDeviceFlow",
        "cancelDeviceFlow"
      )
    )
    for f <- List("src/main/resources/web/js/locales/en.js", "src/main/resources/web/js/locales/zh-CN.js") do
      assertRetiredAbsent(
        f,
        List(
          "login.deviceCodeCaption",
          "login.hintAuthorize",
          "login.waitingAuthorize",
          "login.deviceStartFailed",
          "login.deviceTimeout",
          "login.deviceFailed",
          "login.openAuthPage"
        )
      )
  }

  test("两路径注册汇聚点：登录面词表存在性（正面断言，防误删活面）") {
    // 路径① 浏览器 PKCE 链仍在（不受本批影响）
    assert(read("src/main/scala/nebflow/neblink/LogtoAuthCode.scala").contains("def authorizeUrl"))
    assert(read("src/main/scala/nebflow/gateway/AuthRoutes.scala").contains("beginPkceLogin"))
    // 路径② 无头密码链在位
    assert(read("src/main/scala/nebflow/neblink/LogtoPasswordFlow.scala").contains("def signIn"))
    assert(read("src/main/scala/nebflow/gateway/NeblinkRoutes.scala").contains("\"neblink\" / \"auth\" / \"password\""))
    assert(read("src/main/scala/nebflow/cli/LoginCommand.scala").contains("/api/neblink/auth/password"))
    // 汇聚点：同一 register 面（两路径共用）
    assert(read("src/main/scala/nebflow/neblink/LogtoTransport.scala").contains("/api/device/register"))
  }

end DeviceFlowRetiredSpec
