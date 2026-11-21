package nebflow.agent

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import io.circe.Json
import munit.CatsEffectSuite

/**
 * AgentLibrary 装载面 spec —— builtin-def 批（2026-10-03 作者令①「四个 agent 全部
 * 代码硬编码，不扫盘，唯一标准源就是代码」）改写：
 *  - 四件收敛名（Nebula / project-dispatcher / general / kernel）= **代码定义**
 *    （`BuiltinAgents` 单点）：空盘即有、磁盘文件是死信、写通道拒绝；
 *  - seedDefaults / Seeds 代码种子**退役**（史实测试随批次删除——空盘 Nebula 断言
 *    由「代码定义恒在」承接）；
 *  - 自定义（非收敛名）agent 仍走磁盘扫描，用户编辑 > 一切。
 */
class AgentDefSpec extends CatsEffectSuite:

  test("loadAll returns all four builtins even on an empty disk (code-defined, builtin-def 2026-10-03)"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    // P1-3 归因修正（2026-10-03）：名集判据源 = BuiltinAgents.Names 单点（builtin-merge
    // 批把它收敛为 {Nebula, project-dispatcher, nebflow, subagent}）——旧断言把四名
    // （含已退役的 general/kernel）写死，属断言过时；此处按现状对齐，静态字面不再复述。
    for name <- nebflow.core.entity.BuiltinAgents.Names do
      assert(result.contains(name), s"builtin '$name' must always exist (code-defined)")
      assert(result(name).systemPrompt.nonEmpty, s"builtin '$name' carries the code system prompt")
    // The code def declares no tools: builtins are converged agent names,
    // so their tool surface is mechanism-fixed (AgentCore.fixedToolsFor,
    // auto-injected) and any `tools` value here grants nothing.
    assertEquals(result("Nebula").tools, List.empty[String], "builtins must declare no tools (non-authoritative field)")

  test("loadAll reads CUSTOM agents from disk agent.json (non-converged names only)"):
    val tmpDir = os.temp.dir()
    val customDir = tmpDir / "CustomAgent"
    os.makeDir.all(customDir)
    os.write.over(
      customDir / "agent.json",
      Json
        .obj(
          "name" -> "CustomAgent".asJson,
          "description" -> "A test agent".asJson,
          "tools" -> List("Read", "Grep").asJson
        )
        .noSpaces
    )
    os.write.over(customDir / "system.md", "You are a custom agent.")
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.contains("CustomAgent"), "Custom agent should be loaded from disk")
    assertEquals(result("CustomAgent").tools, List("Read", "Grep"))
    assertEquals(result("CustomAgent").systemPrompt, "You are a custom agent.")

  test("disk files for a BUILTIN name are dead letters — code wins, disk never read (builtin-def 2026-10-03)"):
    val tmpDir = os.temp.dir()
    val nebulaDir = tmpDir / "Nebula"
    os.makeDir.all(nebulaDir)
    // 一套与代码定义完全不同的磁盘假定义：读侧必须整体跳过（不 merger、不覆盖、
    // 不回退）——「唯一标准源就是代码」的读侧单点钉。
    os.write.over(
      nebulaDir / "agent.json",
      Json
        .obj(
          "name" -> "Nebula".asJson,
          "description" -> "STRAY-DISC-DESCRIPTION".asJson,
          "tools" -> List("Read").asJson
        )
        .noSpaces
    )
    os.write.over(nebulaDir / "system.md", "STRAY-DISK-PROMPT")
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(
      !result("Nebula").description.contains("STRAY-DISC-DESCRIPTION"),
      "the disk agent.json is a dead letter — description comes from code"
    )
    assert(
      !result("Nebula").systemPrompt.contains("STRAY-DISK-PROMPT"),
      "the disk system.md is a dead letter — the prompt comes from code"
    )

  test("updateSystemPrompt refuses builtin names (read-only face) and writes custom names"):
    val tmpDir = os.temp.dir()
    val lib = new AgentLibrary(tmpDir, None)
    // builtin：响亮拒绝（WARN，零写盘）
    lib.updateSystemPrompt("Nebula", "New prompt.").unsafeRunSync()
    assert(
      !os.exists(tmpDir / "Nebula" / "system.md"),
      "builtin prompt write must be refused — no disk mirror is created"
    )
    val readBack = lib.readSystemPrompt("Nebula").unsafeRunSync()
    assert(
      readBack.exists(_.nonEmpty) && !readBack.contains("New prompt."),
      "readSystemPrompt for a builtin serves the CODE prompt"
    )
    // custom：写通道照旧
    lib.updateSystemPrompt("CustomAgent", "Custom prompt.").unsafeRunSync()
    assertEquals(os.read(tmpDir / "CustomAgent" / "system.md"), "Custom prompt.")

  test("Nebula survives a corrupted disk agent.json (code definition is unconditional)"):
    val tmpDir = os.temp.dir()
    val nebulaDir = tmpDir / "Nebula"
    os.makeDir.all(nebulaDir)
    os.write.over(nebulaDir / "agent.json", "{invalid json}")
    os.write.over(nebulaDir / "system.md", "Prompt from disk.")
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.contains("Nebula"), "Nebula comes from code regardless of disk state")
    assert(result("Nebula").systemPrompt.nonEmpty)
    assert(
      !result("Nebula").systemPrompt.contains("Prompt from disk."),
      "even a well-formed disk system.md is a dead letter for a builtin"
    )

  test("multiple custom agents loaded from disk and coexist with the four builtins"):
    val tmpDir = os.temp.dir()
    for name <- List("AgentA", "AgentB", "AgentC") do
      val dir = tmpDir / name
      os.makeDir.all(dir)
      os.write.over(
        dir / "agent.json",
        Json
          .obj(
            "name" -> name.asJson,
            "tools" -> List("Read").asJson
          )
          .noSpaces
      )
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assert(result.contains("AgentA"))
    assert(result.contains("AgentB"))
    assert(result.contains("AgentC"))
    for name <- nebflow.core.entity.BuiltinAgents.Names do
      assert(result.contains(name), s"builtin '$name' must coexist with custom agents")

  // 停教批（2026-11-21 作者令，作者 10:08「按你推荐」）：显示/TTS 面封存期间
  // 程序化构造的 AgentDef 默认不产出 `<voice>` 块 —— 默认值 true 翻转为 false，
  // 防内置 agent 走 agent.json 以外的路径（Ask 分身等）重新打开语音面。
  // 恢复语音功能时连同 PromptSections.voiceTeachingParked 一并翻回 true。
  test("programmatic AgentDef defaults to voiceEnabled = false (voice teaching parked)"):
    val defn = nebflow.actor.AgentDef(name = "probe", description = "d")
    assertEquals(defn.voiceEnabled, false, "默认值必须是封存态（false）")

  test("custom agent.json without a `voice` key resolves to voiceEnabled = false"):
    val tmpDir = os.temp.dir()
    val dir = tmpDir / "NoVoiceKey"
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json", Json.obj("name" -> "NoVoiceKey".asJson).noSpaces)
    val lib = new AgentLibrary(tmpDir, None)
    val result = lib.loadAll().unsafeRunSync()
    assertEquals(result("NoVoiceKey").voiceEnabled, false)

end AgentDefSpec
