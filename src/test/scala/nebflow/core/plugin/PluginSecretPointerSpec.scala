package nebflow.core.plugin

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.{McpServerConfig, PathUtil}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * mcp.json env 的 **`${secret:<name>}` 指针形态** spec（安全批 2026-10-29 ④a）。
 *
 * 判据五条：
 *  ① 合法指针 env ⇒ 正常装载（server entry 不被跳过、零 malformed 告警）
 *  ② 半截指针（`${secret:` 存在但形态非法）⇒ entry 拒 + 告警（fail-closed）
 *  ③ 审批面：合法指针 ⇒ 凭据类键名**不**红标 + 一条「secrets 库解析」信息旗
 *     （红标启发降为兜底）；非指针明文凭据值照旧红标
 *  ④ 运行期展开：指针 → secrets 库实值（读取语义 = SecretInjection.materialize：
 *     去一个尾换行）；PLUGIN_ROOT/PLUGIN_DATA 照常注入
 *  ⑤ 展开失败面：secret 缺失 ⇒ Left 点名 env 键与 secret 名（值零出现）；坏名
 *     已被装载层拦（③① 的装载守卫）
 */
class PluginSecretPointerSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-secret-pointer"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home)
    catch case _: Exception => ()

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(home / "plugins")
    os.remove.all(home / "secrets")
    if os.exists(PathUtil.configJsonWritePath(home)) then os.remove(PathUtil.configJsonWritePath(home))
    PluginRegistry.invalidateCache()

  private def writePlugin(name: String, envJson: String): os.Path =
    val d = home / "plugins" / name
    os.makeDir.all(d / "skills" / "hello")
    os.write.over(
      d / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","description":"$name fixture"}"""
    )
    os.write.over(d / "skills" / "hello" / "SKILL.md", "---\nname: hello\ndescription: hello skill\n---\n# hello\n")
    os.write.over(
      d / "mcp.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalMcpSchema}","mcpServers":{"srv":{"type":"stdio","command":"python3","args":["-c","print(1)"],"env":$envJson}}}"""
    )
    d

  private def scanned(name: String): PluginRegistry.PluginDef =
    PluginRegistry.scan().unsafeRunSync().find(_.name == name).getOrElse(fail(s"plugin '$name' not loaded"))

  private def writeSecret(nm: String, value: String): Unit =
    os.makeDir.all(home / "secrets")
    os.write.over(home / "secrets" / nm, value)
    try
      Files.setPosixFilePermissions(
        (home / "secrets" / nm).toNIO,
        PosixFilePermissions.fromString("rw-------")
      )
    catch case _: Exception => ()

  // ── ① 合法指针 ⇒ 正常装载 ─────────────────────────────────
  test("① 合法 ${secret:name} env 指针 ⇒ entry 正常装载（零 malformed 告警）"):
    writePlugin("ptr-ok", """{"MY_TOKEN":"${secret:my-token}"}""")
    val p = scanned("ptr-ok")
    assert(p.mcpServers.contains("srv"), "① server entry 在（指针不拒载）")
    assert(!p.warnings.exists(_.contains("malformed")), s"① 零 malformed 告警：${p.warnings}")

  // ── ② 半截指针 ⇒ entry 拒 ─────────────────────────────────
  test("② 残缺指针（坏名字符/多余尾缀）⇒ entry 拒 + 告警（fail-closed）"):
    writePlugin("ptr-bad", """{"MY_TOKEN":"${secret:Bad Name!}"}""")
    val p = scanned("ptr-bad")
    assert(!p.mcpServers.contains("srv"), "② entry 被跳过（残缺指针必拒）")
    assert(p.warnings.exists(_.contains("malformed")), s"② malformed 告警在：${p.warnings}")

  // ── ③ 审批面：指针不红标 + 信息旗 ──────────────────────────
  test("③ 审批面：凭据类键名 + 指针值 ⇒ 不红标（兜底收窄）；明文值照旧红标"):
    writePlugin("ptr-flags", """{"MY_TOKEN":"${secret:my-token}","OTHER_TOKEN":"sk-live-plain"}""")
    val p = scanned("ptr-flags")
    val flags = PluginRegistry.approvalManifest(p).hcursor.downField("flags").as[List[String]].toOption.getOrElse(Nil)
    val tokenFlags = flags.filter(_.contains("MY_TOKEN"))
    assert(tokenFlags.nonEmpty && tokenFlags.forall(_.contains("secrets store")), s"③ 指针键只出信息旗不出红标：$tokenFlags")
    assert(flags.exists(f => f.contains("OTHER_TOKEN") && f.contains("credential")), s"③ 明文凭据值照旧红标：$flags")

  // ── ④ 运行期展开 ──────────────────────────────────────────
  test("④ expandRuntimeConfig：指针 → secrets 实值（去尾换行）+ 占位符照常"):
    writeSecret("my-token", "tok-value-123\n")
    val cfg = McpServerConfig(
      command = Some("python3"),
      args = Some(List("${PLUGIN_ROOT}/run.py")),
      env = Some(Map("MY_TOKEN" -> "${secret:my-token}", "PLAIN" -> "plain-value")),
      url = None,
      headers = None,
      enabled = None,
      timeoutMs = None,
      cwd = Some("${PLUGIN_ROOT}/.")
    )
    val dir = home / "plugins" / "ptr-run"
    os.makeDir.all(dir)
    val eff = PluginMcpManager.expandRuntimeConfig(dir.toString, cfg).toOption.get
    val env = eff.env.getOrElse(Map.empty)
    assertEquals(env.get("MY_TOKEN"), Some("tok-value-123"), "④ 指针解析为实值且去一个尾换行")
    assertEquals(env.get("PLAIN"), Some("plain-value"), "④ 非指针值原样")
    assertEquals(env.get("PLUGIN_ROOT"), Some(dir.toString), "④ PLUGIN_ROOT 照常注入")
    assertEquals(eff.cwd, Some(s"$dir/."), "④ cwd 占位符照常展开")

  // ── ⑤ 失败面：secret 缺失 ⇒ Left 点名（值零出现）───────────
  test("⑤ secret 缺失 ⇒ Left 点名 env 键与 secret 名（消息零值）"):
    val cfg = McpServerConfig(
      command = Some("python3"),
      args = None,
      env = Some(Map("MY_TOKEN" -> "${secret:absent-token}")),
      url = None,
      headers = None,
      enabled = None,
      timeoutMs = None,
      cwd = None
    )
    val dir = home / "plugins" / "ptr-missing"
    os.makeDir.all(dir)
    val res = PluginMcpManager.expandRuntimeConfig(dir.toString, cfg)
    val err = res.left.toOption.getOrElse(fail("⑤ 期望 Left"))
    assert(err.contains("MY_TOKEN") && err.contains("absent-token"), s"⑤ 点名键与 secret：$err")
    assert(!res.toString.contains("tok-value"), "⑤ 消息零值（不泄 secret 内容）")

end PluginSecretPointerSpec
