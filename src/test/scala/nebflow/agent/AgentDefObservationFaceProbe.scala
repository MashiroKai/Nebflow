package nebflow.agent

import cats.effect.unsafe.implicits.global
import nebflow.core.PathUtil

/**
 * agentdef-tidy 批（2026-09-11）观测面读数探针 —— **raw stdout，零断言**。
 *
 * 用法：
 * {{{
 *   sbt -batch "Test/runMain nebflow.agent.AgentDefObservationFaceProbe"
 * }}}
 *
 * 面：删 `tools` 键后 keeper payload 的 `AgentDef.tools` 形变（`[]` / 7 件 → `["*"]`）。
 * 两条**真实解码路径**各读一遍（均为纯读：
 *   ① `AgentLibrary.loadFromDir` → `AgentJson` decoder 默认
 *      `tools.getOrElse(List("*"))` → `AgentService.listAgents` → WS `listAgents` 帧；
 *   ② `EntityLoader.loadAgentFromDir` → `AgentEntry` decoder 默认同款
 *      → REST `GET /agents/list` / `GET /agents/:name`）。
 *
 * 逐案对照：`*.bak`（删键前）+ 当前运行时文件，各复制进隔离 scratch 目录后解码，
 * 真实 `~/.nebflow` **只读打开取源 sha256，零写入**。
 */
object AgentDefObservationFaceProbe:

  private val Root = os.Path("/tmp/agentdef-tidy-obsfmt")

  private val Cases: List[String] = List("Nebula", "project-dispatcher", "general", "kernel")

  def main(args: Array[String]): Unit =
    val evidence = args.headOption.map(os.Path(_)).getOrElse(
      os.Path("/tmp/agentdef-tidy-evidence")
    )
    os.remove.all(Root)
    val liveAgents = PathUtil.dataRoot / "agents"

    println("PROBE PathUtil.dataRoot = " + PathUtil.dataRoot.toString)
    println("")

    Cases.foreach { name =>
      val live = liveAgents / name / "agent.json"
      val bak = evidence / s"$name.agent.json.bak"
      println(s"-- $name")
      println(s"   live   sha256(16) = " + sha16(live) + "  " + live.toString)
      if os.exists(bak) then println(s"   pre    sha256(16) = " + sha16(bak) + "  " + bak.toString)
      else println(s"   pre    sha256(16) = (备份缺失: $bak)")
      println(s"   keys(live) = " + keysOf(live))
      if os.exists(bak) then println(s"   keys(pre)  = " + keysOf(bak))

      List(
        "pre (tools 键存在)" -> bak,
        "post(tools 键删除)" -> live
      ).foreach { (label, src) =>
        if os.exists(src) then
          val dir = Root / label.takeWhile(_ != ' ') / name
          os.makeDir.all(dir)
          os.copy.over(src, dir / "agent.json")
          val viaLibrary = new AgentLibrary(dir / os.up, None).loadFromDir(dir)
          val viaEntity = nebflow.core.entity.EntityLoader.loadAgentFromDir(dir)
          println(
            s"   [$label] AgentLibrary.AgentDef.tools = " +
              viaLibrary.map(_.tools).getOrElse("<load failed>") +
              "   category = " + viaLibrary.map(_.category).getOrElse("-")
          )
          println(
            s"   [$label] EntityLoader.AgentEntry.tools = " +
              viaEntity.map(_.tools).getOrElse("<load failed>") +
              "   category = " + viaEntity.map(_.category).getOrElse("-")
          )
      }
      println("")
    }

    println("PROBE 两条解码默认值锚点：AgentLibrary.AgentJson `tools.getOrElse(List(\"*\"))`（解码器内）；")
    println("PROBE                       EntityLoader/EntityTypes `tools.getOrElse(List(\"*\"))`（同款）")
    os.remove.all(Root)
  end main

  private def sha16(p: os.Path): String =
    if !os.exists(p) then "(absent)"
    else
      val md = java.security.MessageDigest.getInstance("SHA-256")
      val hex = md.digest(os.read.bytes(p)).map("%02x".format(_)).mkString
      hex.take(16)

  private def keysOf(p: os.Path): String =
    if !os.exists(p) then "(absent)"
    else
      io.circe.parser.parse(os.read(p)).toOption
        .flatMap(_.asObject)
        .map(_.keys.toList.sorted.mkString("[", ", ", "]"))
        .getOrElse("(parse failed)")

end AgentDefObservationFaceProbe
