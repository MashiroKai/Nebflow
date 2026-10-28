package nebflow.core.entity

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.Json
import nebflow.core.AtomicJson
import nebflow.shared.{MemoryStore, NebflowLogger, PathUtil}

import java.nio.charset.StandardCharsets

/**
 * AgentIdentityStore —— 智能体**身份/展示面**的引擎侧单点读写（identity-sync 批
 * 2026-10-23）。管辖三样东西，全部是**既有设施**（本批零新存储）：
 *
 *   1. `agents/<name>/agent.json` 的 `displayName` 键（面板改名写穿点，
 *      2026-10-21 诊断批症状⑧ 建立）——本对象把该写路径从 PresenceRoutes 收拢
 *      为单点（根名 `name` 键补底纪律逐字保留），并加注 **`identityUpdatedAt`**
 *      时间戳键（LWW 收敛依据；agent.json 的宽容解码器/读点全部忽略未知键 ⇒
 *      加性零回归）。
 *   2. `agents/<name>/avatar` 头像字节文件（同批建立的落盘位）。
 *   3. **Soul.md 的 `## 自我认知` 段**（personal-agent 批建立的智能体自我认知
 *      存储位）——作者令「对名称的修改要落实到智能体的记忆里」：改名/换头像
 *      落盘时同步改写该段的名字行/头像行（replace-in-place，不新增存储位）。
 *
 * 为什么要有时间戳（跨机同步的根因）：此前展示面只落**本机引擎**的数据根——
 * 两台机器各自跑一个引擎实例，A 机写入对 B 机引擎永不可见；同引擎多浏览器形态
 * 下前端又是「localStorage 优先、缺键才回填」，旧本地键永远遮住服务端新值。
 * 本批把展示面变为 **LWW 可收敛状态**（时间戳 + 内容 sha），由
 * `nebflow.neblink.AgentIdentitySync` 经既有设备数据通道（presence sendData /
 * relay Notify 双腿）在写入时外推、在对端按戳收敛。
 *
 * 红线：头像字节**永不入日志**（日志面只出现 名字/sha 前 8 位/字节长度）。
 */
object AgentIdentityStore:

  private val logger = NebflowLogger.forName("nebflow.agent.identity")

  /** agent.json 内的 LWW 时间戳键（加性键；既有读点全部忽略未知键）。 */
  val UpdatedAtKey: String = "identityUpdatedAt"

  /** Soul.md 里自我认知段的段标题（OnboardingArtifacts.buildSoul 同名段）。 */
  val SoulSectionTitle: String = "## 自我认知"

  /** 单个 agent 的身份读数（元数据面；头像字节不进本件）。 */
  final case class AgentIdentityRec(
    agent: String,
    /** agent.json `displayName` 键的现读（None = 未起名/未设置）。 */
    displayName: Option[String],
    /** 头像文件在场时的内容 sha256（hex）；None = 无自定义头像。 */
    avatarSha256: Option[String],
    /** LWW 时间戳（`identityUpdatedAt`；0 = 从未加戳——onboarding 时代的旧行）。 */
    updatedAt: Long
  )

  /** 该 agent 是否携带任何身份内容（推送枚举的入选判据）。 */
  def hasIdentity(r: AgentIdentityRec): Boolean =
    r.displayName.exists(_.trim.nonEmpty) || r.avatarSha256.isDefined

  private def agentsDir: os.Path = PathUtil.dataRoot / "agents"
  private def agentDir(agent: String): os.Path = agentsDir / agent
  private def agentJsonPath(agent: String): os.Path = agentDir(agent) / "agent.json"
  private def avatarPath(agent: String): os.Path = agentDir(agent) / "avatar"

  def sha256Hex(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** 现读一个 agent 的身份读数（文件缺席 = 无身份，恒不抛）。 */
  def readRec(agent: String): AgentIdentityRec =
    try
      val jsonPath = agentJsonPath(agent)
      val parsed: Option[io.circe.Json] =
        if !os.exists(jsonPath) then None
        else io.circe.parser.parse(os.read(jsonPath)).toOption
      val dn = parsed
        .flatMap(_.hcursor.downField("displayName").as[Option[String]].toOption.flatten)
        .map(_.trim)
        .filter(_.nonEmpty)
      val stamp = parsed.flatMap(_.hcursor.downField(UpdatedAtKey).as[Option[Long]].toOption.flatten).getOrElse(0L)
      val p = avatarPath(agent)
      val sha = if os.exists(p) && os.isFile(p) then Some(sha256Hex(os.read.bytes(p))) else None
      AgentIdentityRec(agent, dn, sha, stamp)
    catch case _: Throwable => AgentIdentityRec(agent, None, None, 0L)

  // ── 本机写面（面板 PUT 路由的落盘单点；每次写都加 LWW 戳）────────────────

  /**
   * 写 `displayName`（既有 PUT /agents/:name/display-name 路由体的收拢，行为
   * 逐字保持 + 加戳）：AtomicJson 原子合并；根名沿 OnboardingArtifacts 同款
   * `name` 键补底纪律（仅在该键缺席时补）。`stampMs` = LWW 戳（生产 = 当前
   * 时刻；收敛写入传远端戳，见 [[applyRemote]]）。
   */
  def writeDisplayName(agent: String, displayName: String, stampMs: Long = System.currentTimeMillis()): IO[Unit] =
    IO.blocking {
      val dir = agentDir(agent)
      os.makeDir.all(dir)
      val jsonPath = agentJsonPath(agent)
      val existing =
        if os.exists(jsonPath) then io.circe.parser.parse(os.read(jsonPath)).toOption.getOrElse(io.circe.Json.obj())
        else io.circe.Json.obj()
      val base =
        if agent == nebflow.actor.RootAgentIdentity.Name && !existing.asObject.exists(_.contains("name"))
        then existing.deepMerge(Json.obj("name" -> agent.asJson))
        else existing
      AtomicJson.writeSync(
        jsonPath,
        base
          .deepMerge(Json.obj("displayName" -> displayName.asJson))
          .deepMerge(Json.obj(UpdatedAtKey -> stampMs.asJson))
          .noSpaces
      )
    }

  /** 写头像字节（PUT /agents/:name/avatar 路由体的收拢，行为保持 + 加戳）。 */
  def writeAvatar(agent: String, bytes: Array[Byte], stampMs: Long = System.currentTimeMillis()): IO[Unit] =
    IO.blocking {
      val dir = agentDir(agent)
      os.makeDir.all(dir)
      os.write.over(avatarPath(agent), bytes)
      stampAgentJson(agent, stampMs)
    }

  /** 清头像（DELETE /agents/:name/avatar 路由体的收拢，行为保持 + 加戳）。 */
  def clearAvatar(agent: String, stampMs: Long = System.currentTimeMillis()): IO[Unit] =
    IO.blocking {
      val p = avatarPath(agent)
      if os.exists(p) then os.remove(p)
      stampAgentJson(agent, stampMs)
    }

  /** 只加戳不动内容（收敛到「与本地一致但戳更新」的远端状态时用）。 */
  private def stampAgentJson(agent: String, stampMs: Long): Unit =
    val jsonPath = agentJsonPath(agent)
    val existing =
      if os.exists(jsonPath) then io.circe.parser.parse(os.read(jsonPath)).toOption.getOrElse(io.circe.Json.obj())
      else
        // 无 agent.json 的空目录写头像：补最小 name 键，保住「文件可归属」。
        Json.obj("name" -> agent.asJson)
    AtomicJson.writeSync(jsonPath, existing.deepMerge(Json.obj(UpdatedAtKey -> stampMs.asJson)).noSpaces)

  // ── 远端收敛写面（LWW；只被 AgentIdentitySync 收帧调用，绝不回推）────────

  /**
   * 应用一份远端身份状态（last-write-wins）：远端戳**严格更旧** ⇒ 跳过（返回
   * false）；等号/更新 ⇒ 覆盖本地（displayName 键按远端**全量**语义：None =
   * 摘除键；头像按 bytes 全量语义：None = 清文件）并**写远端戳**。写完返回
   * true（调用方据此触发 Soul 腿 + 面板广播）。**不回推**（回声抑制靠结构：
   * 本函数不在任何广播路径上）。
   */
  def applyRemote(rec: AgentIdentityRec, avatarBytes: Option[Array[Byte]]): IO[Boolean] =
    IO.blocking {
      val local = readRec(rec.agent)
      if rec.updatedAt < local.updatedAt then false
      else
        val dir = agentDir(rec.agent)
        os.makeDir.all(dir)
        val jsonPath = agentJsonPath(rec.agent)
        val existing =
          if os.exists(jsonPath) then io.circe.parser.parse(os.read(jsonPath)).toOption.getOrElse(io.circe.Json.obj())
          else io.circe.Json.obj()
        // 根名 `name` 键补底纪律与本地写面一致。
        val withName =
          if rec.agent == nebflow.actor.RootAgentIdentity.Name && !existing.asObject.exists(_.contains("name"))
          then existing.deepMerge(Json.obj("name" -> rec.agent.asJson))
          else existing
        val withDn = rec.displayName match
          case Some(dn) => withName.deepMerge(Json.obj("displayName" -> dn.asJson))
          case None =>
            withName.asObject match
              case Some(o) if o.contains("displayName") => Json.fromJsonObject(o.remove("displayName"))
              case _ => withName
        AtomicJson.writeSync(
          jsonPath,
          withDn.deepMerge(Json.obj(UpdatedAtKey -> rec.updatedAt.asJson)).noSpaces
        )
        avatarBytes match
          case Some(bytes) if bytes.nonEmpty =>
            os.write.over(avatarPath(rec.agent), bytes)
          case _ =>
            val p = avatarPath(rec.agent)
            if os.exists(p) then os.remove(p)
        true
      end if
    }

  // ── 提示词自我认知面（PromptSections order-396 段的渲染源）────────────────

  /**
   * 渲染 system prompt 的 `## Self` 段体（**只**在非默认身份时产出——无名字、
   * 无头像的 agent 返回空串 = 段不注入 = 既有会话字节零变化，前缀缓存零失效）：
   *   - 名字行：displayName 非空且 ≠ 机制名（用户真起过名）；
   *   - 头像行：自定义头像文件在场。
   * 根 Nebula 的名字同时出现在提示词首句（{{agent_display_name}}），段内重复
   * 是**有意的显式自认知**（验收：上下文明确携带名字与头像）。
   */
  def selfSectionText(agentName: String, displayName: Option[String], avatarSha: Option[String]): String =
    val lines = scala.collection.mutable.ListBuffer.empty[String]
    displayName
      .map(_.trim)
      .filter(_.nonEmpty)
      .filter(_ != agentName)
      .foreach(dn =>
        lines += s"- Display name: \"$dn\" (the name the user gave you; \"$agentName\" stays your mechanism address)"
      )
    avatarSha.foreach { sha =>
      lines += s"- Avatar: a custom avatar image is set (shown next to your messages in the panel; content sha256 ${sha.take(8)}…)"
    }
    lines.toList match
      case Nil => ""
      case nonEmpty => nonEmpty.mkString("\n")

  end selfSectionText

  /** 从磁盘现读一个 agent 的 Self 段体（AgentSessionExecution 每 turn 调用点）。 */
  def readSelfSection(agentName: String, displayName: Option[String]): IO[String] = IO.blocking {
    val rec = readRec(agentName)
    selfSectionText(agentName, displayName.orElse(rec.displayName), rec.avatarSha256)
  }

  // ── Soul.md 自我认知段（「落实到智能体的记忆」腿）──────────────────────────

  /**
   * 把名字/头像落到 Soul.md 的 `## 自我认知` 段（根 agent 专属——Soul.md 是根
   * 层单文件）。纪律：
   *   - Soul.md 不存在 ⇒ **跳过**（不为了这段凭空造 Soul——onboarding 才是它的
   *     生产者；此时提示词面已携带身份）；
   *   - 段在场 ⇒ **replace-in-place**：名字行/头像行就地改写（「取代而非追加」
   *     的记忆纪律），其余行一字不动；
   *   - 段缺席 ⇒ 文件尾**追加**最小段（不碰既有内容）；
   *   - 内容无变化 ⇒ 不写（幂等，零落盘）；
   *   - 落盘走 [[MemoryStore.saveSoulMemory]]（预算闸 + 写前快照 + 缓存失效全套
   *     既有设施）；预算闸拒绝 ⇒ WARN + 放弃（不阻塞主写路径）。
   *
   * @return true = Soul.md 实际更新。
   */
  def touchSoulIdentity(
    agent: String,
    displayName: Option[String],
    avatarSet: Boolean
  ): IO[Boolean] =
    // Soul.md 是根 agent 的自我认知位（MemoryStore 根层单文件）——非根 agent 无
    // Soul 位面，直接跳过（它们的身份落 agent.json + 提示词面，已覆盖）。
    if agent != nebflow.actor.RootAgentIdentity.Name then IO.pure(false)
    else
      IO.blocking {
        val soul = MemoryStore.soulMemoryPath
        if !os.exists(soul) then None
        else
          val nameLine =
            s"- 名字：${displayName.map(_.trim).filter(_.nonEmpty).getOrElse(nebflow.actor.RootAgentIdentity.Name)}"
          val avatarLine = if avatarSet then "- 头像：已设置（面板会话头展示）" else "- 头像：未设置（面板展示首字母圆牌）"
          val original = os.read(soul)
          val updated = rewriteSoulSection(original, nameLine, avatarLine)
          Option.when(updated != original)(updated)
      }.flatMap {
        case None => IO.pure(false)
        case Some(updated) =>
          // 预算闸拒绝（超软/硬线）= IO 错误通道；此处 WARN + 放弃，不外抛。
          MemoryStore.saveSoulMemory(updated).attempt.flatMap {
            case Right(_) => IO.pure(true)
            case Left(e) =>
              logger.warn(s"Soul identity section update refused/failed: ${e.getMessage}").as(false)
          }
      }

  /**
   * `## 自我认知` 段的名字行/头像行 replace-in-place（纯函数，spec 直测）。
   * 段边界 = 标题行起至下一个 `## ` 行或 EOF；段外内容逐字不动。
   */
  private[entity] def rewriteSoulSection(original: String, nameLine: String, avatarLine: String): String =
    val lines = original.linesIterator.toIndexedSeq
    val titleIdx = lines.indexOf(SoulSectionTitle)
    if titleIdx < 0 then
      val sep = if original.endsWith("\n") || original.isEmpty then "" else "\n"
      original + s"$sep$SoulSectionTitle\n$nameLine\n$avatarLine\n"
    else
      val nextTitle = lines.indices
        .drop(titleIdx + 1)
        .find(i => lines(i).startsWith("## "))
        .getOrElse(lines.length)
      val head = lines.take(titleIdx + 1)
      val body = lines.slice(titleIdx + 1, nextTitle)
      val tail = lines.drop(nextTitle)
      // 段体内就地换名字行 / 头像行（缺席则插在段体首/次位）。
      val nameIdx = body.indexWhere(_.startsWith("- 名字："))
      val body1 =
        if nameIdx >= 0 then body.updated(nameIdx, nameLine)
        else nameLine +: body
      val avatarIdx = body1.indexWhere(_.startsWith("- 头像："))
      val body2 =
        if avatarIdx >= 0 then body1.updated(avatarIdx, avatarLine)
        else
          val at = body1.indexWhere(_.startsWith("- 名字：")) + 1
          body1.patch(at, Seq(avatarLine), 0)
      (head ++ body2 ++ tail).mkString("\n") + (if tail.nonEmpty || body2.nonEmpty then "\n" else "")
    end if
  end rewriteSoulSection

end AgentIdentityStore
