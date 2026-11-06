package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.service.ConfigService
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

import scala.concurrent.duration.*

/**
 * 「设置里删掉的 provider 仍在被调用」（配置面 ≠ 运行面）批 spec ——
 * provider-config-vs-runtime-mismatch，2026-11-05 作者令。
 *
 * 本 spec 只钉**候选集 ↔ 配置**这一条线（候选完整性/单候选失败不终止整链由
 * fallback 批负责，不在此重复）。四条机械断言（工件报告 §⑤ 逐条对应）：
 *   A1 显式 agent 链所列模型 provider 被删除 ⇒ 运行期候选集立即不含它；
 *   A2 仅加回 provider 配置仍不授权（候选资格须由 agent 链明确授予）；
 *   A3 盘上配置与内存快照不一致 ⇒ 报告面 `drift.inSync=false` 且名单点名（可见、不静默）；
 *   A4 配置里只剩 1 个可用 provider ⇒ 候选集恰为那一个。
 *
 * 另加两条**根因**断言（本批实测查明的两条复活/漂移通道）：
 *   R1 显式链外模型由配置残留但不进入该 agent 候选；
 *   R2 旁路写者的 lost update：`nebflow.json` 的多名无锁手术式写者
 *      （PluginRegistry / PluginDispatchPolicy / PluginBlockPolicy / SocialChannels …）
 *      与 `updateConfig` 交叉时能把刚删掉的 provider 整块写回 ⇒
 *      `ConfigService.undeletedProviders` 守卫逐名核对并上浮失败。
 *
 * 全程隔离 `PathUtil.setDataRoot(临时目录)`，不读不写真实 `~/.stand`，不碰 6138。
 */
class ProviderCandidateConfigSyncSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val NullBackend = null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]]

  // ── 隔离夹具 ────────────────────────────────────────────────

  private def withTempRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-prov-cfg-sync-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private def providerEntry(port: Int, models: List[String], disabled: Boolean = false): String =
    val ms = models.map(m => s"""{"id":"$m","contextWindow":8192}""").mkString(",")
    val d = if disabled then """"disabled":true,""" else ""
    s"""{"baseUrl":"http://127.0.0.1:$port","apiKey":"k",${d}"protocol":"anthropic","models":[$ms]}"""

  /** 写整份 `nebflow.json`（隔离根内的唯一配置源）。 */
  private def writeConfig(root: os.Path, providers: List[(String, List[String])], disabled: Set[String] = Set.empty): Unit =
    val body = providers
      .map { case (pid, ms) => s""""$pid":${providerEntry(1, ms, disabled.contains(pid))}""" }
      .mkString(",")
    os.write.over(root / nebflow.shared.Branding.configFileName, s"""{"llm":{"providers":{$body}}}""", createFolders = true)

  /** Nebula 的显式主链（= `/model` 面写的那份，全局默认链的锚）。 */
  private def writeNebulaChain(root: os.Path, preferred: String, fallbacks: List[String]): Unit =
    val fb = fallbacks.map(x => s""""$x"""").mkString(",")
    os.write.over(
      root / "agents" / "Nebula" / "agent.json",
      s"""{"name":"Nebula","model":{"preferred":"$preferred","fallbacks":[$fb]}}""",
      createFolders = true
    )

  private def mkRegistryFromDisk: IO[ProviderRegistry] =
    IO.blocking(Config.loadServiceConfig())
      .flatMap(cfg => Ref.of[IO, NebflowServiceConfig](cfg))
      .map(ref => new ProviderRegistry(ref, NullBackend))

  private def reportRefs(j: Json): List[String] =
    j.hcursor
      .downField("candidates")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap(_.hcursor.downField("ref").as[String].toOption)

  private def originOf(j: Json, ref: String): Option[String] =
    j.hcursor
      .downField("candidates")
      .as[List[Json]]
      .getOrElse(Nil)
      .find(_.hcursor.downField("ref").as[String].toOption.contains(ref))
      .flatMap(_.hcursor.downField("origin").as[String].toOption)

  private def strList(j: Json, field: String): List[String] =
    j.hcursor.downField(field).as[List[String]].getOrElse(Nil)

  /** 生产病例的最小镜像：zhipu 头 + kimi 双档 + cmdcode（kimi 即"已删"的那个）。 */
  private val prodLike: List[(String, List[String])] =
    List(
      "zhipu" -> List("glm-5.3-flash"),
      "kimi" -> List("kimi-for-coding", "kimi-for-coding-highspeed", "k3.1-pro-highspeed"),
      "cmdcode" -> List("deepseek/deepseek-v4.1-flash")
    )

  // ── R1：配置存在但 agent 链未授权 ⇒ 不得进入候选 ──────────────
  test("R1 配置里存在但链未引用的 provider 不进 agent 候选集") {
    withTempRoot { root =>
      IO {
        writeConfig(root, prodLike)
        writeNebulaChain(root, "zhipu/glm-5.3-flash", Nil)
      } *> mkRegistryFromDisk.flatMap { reg =>
        reg.candidateReport().map { j =>
          val refs = reportRefs(j)
          assertEquals(refs.head, "zhipu/glm-5.3-flash", s"链头必须排第一，实际 $refs")
          assert(!refs.contains("kimi/kimi-for-coding"), s"未列入当前 agent 链的 kimi 不得进入候选集，实际 $refs")
          assertEquals(originOf(j, "kimi/kimi-for-coding"), None, "未授权模型不得有候选来源")
          assertEquals(originOf(j, "zhipu/glm-5.3-flash"), Some("chain"), "链头来源应标 chain")
        }
      }
    }
  }

  // ── A1：删掉 provider ⇒ 候选集不含（热生效，不重启）────────────
  test("A1 删掉 kimi ⇒ 运行期候选集立刻不含 kimi（无重启）") {
    withTempRoot { root =>
      IO {
        writeConfig(root, prodLike)
        writeNebulaChain(root, "zhipu/glm-5.3-flash", List("kimi/kimi-for-coding"))
      } *> mkRegistryFromDisk.flatMap { reg =>
        for
          before <- reg.candidateReport()
          _ <- ConfigService.updateConfig("""{"llm":{"providers":{"kimi":null}}}""")
          _ <- reg.reloadConfig(None)
          after <- reg.candidateReport()
        yield
          assert(reportRefs(before).exists(_.startsWith("kimi/")), "删除前 agent.json 明确列入 kimi，应在候选集")
          val refs = reportRefs(after)
          assert(!refs.exists(_.startsWith("kimi/")), s"删除后候选集不得含 kimi，实际 $refs")
          // 删除腿连带 scrub 掉 agent.json 里的 kimi 链引用（ConfigService.scrubAgentModelRefs）
          // ⇒ 删除后链里也不该再留着 kimi；链里"写了但配置没有"的 dropped 形态
          // 由 A4（不经删除、直接造不一致）覆盖。
          assert(
            !strList(after, "chainRefs").exists(_.startsWith("kimi/")),
            s"删除后链引用也应被 scrub 掉，实际 ${strList(after, "chainRefs")}"
          )
          assertEquals(strList(after, "configProviders"), List("cmdcode", "zhipu"), "配置面应只剩 cmdcode/zhipu")
          val diskHasKimi = IO.blocking(
            os.read(root / nebflow.shared.Branding.configFileName).contains("\"kimi\"")
          ).unsafeRunSync()
          assert(!diskHasKimi, "删除必须真的落到磁盘（否则运行期下次读又复活）")
      }
    }
  }

  // ── A2：加回 ⇒ 立刻生效 ────────────────────────────────────
  test("A2 加回 kimi ⇒ 候选集立刻含 kimi（热生效，不重启）") {
    withTempRoot { root =>
      IO {
        writeConfig(root, List("zhipu" -> List("glm-5.3-flash")))
        writeNebulaChain(root, "zhipu/glm-5.3-flash", Nil)
      } *> mkRegistryFromDisk.flatMap { reg =>
        for
          before <- reg.candidateReport()
          _ <- ConfigService.updateConfig(s"""{"llm":{"providers":{"kimi":${providerEntry(1, List("kimi-for-coding"))}}}}""")
          _ <- reg.reloadConfig(None)
          after <- reg.candidateReport()
        yield
          assert(!reportRefs(before).exists(_.startsWith("kimi/")), "加回前不得有 kimi（前置条件）")
          assert(!reportRefs(after).contains("kimi/kimi-for-coding"), s"仅加 provider 配置不能授权候选，实际 ${reportRefs(after)}")
      }
    }
  }

  // ── A3：盘上 vs 内存不一致 ⇒ 可见告警（不许静默）──────────────
  test("A3 盘上配置被旁路改掉 ⇒ drift.inSync=false 且点名（可见，不静默沿用旧快照）") {
    withTempRoot { root =>
      IO {
        writeConfig(root, prodLike)
        writeNebulaChain(root, "zhipu/glm-5.3-flash", Nil)
      } *> mkRegistryFromDisk.flatMap { reg =>
        for
          synced <- reg.candidateReport()
          // 旁路写者（PluginRegistry.mutateNebflowJson 同形态）：直接改盘，不经 reloadConfig
          _ <- IO(writeConfig(root, prodLike.filterNot(_._1 == "kimi")))
          drifted <- reg.candidateReport()
          _ <- reg.reloadConfig(None)
          healed <- reg.candidateReport()
        yield
          assertEquals(synced.hcursor.downField("drift").downField("inSync").as[Boolean].toOption, Some(true), "初始应同步")
          val d = drifted.hcursor.downField("drift")
          assertEquals(d.downField("known").as[Boolean].toOption, Some(true), "盘上可读")
          assertEquals(d.downField("inSync").as[Boolean].toOption, Some(false), "盘上删了 kimi 而内存还有 ⇒ 必须报不同步")
          assertEquals(strList(drifted, "configProviders").contains("kimi"), true, "内存侧仍持有 kimi")
          val driftNode = drifted.hcursor.downField("drift")
          val onlyInRuntime = driftNode.downField("onlyInRuntime").as[List[String]].getOrElse(Nil)
          assertEquals(onlyInRuntime, List("kimi"), "点名：运行期多出来的就是 kimi（运行期还在用它）")
          assertEquals(healed.hcursor.downField("drift").downField("inSync").as[Boolean].toOption, Some(true), "reload 后回到同步")
          assert(!reportRefs(healed).exists(_.startsWith("kimi/")), "reload 后 kimi 必须离开候选集")
      }
    }
  }

  // ── A4：只剩 1 个 provider ⇒ 只试那一个 ─────────────────────
  test("A4 配置里只剩 1 个可用 provider ⇒ 候选集恰为那一个") {
    withTempRoot { root =>
      IO {
        writeConfig(root, List("zhipu" -> List("glm-5.3-flash")))
        writeNebulaChain(root, "zhipu/glm-5.3-flash", List("kimi/kimi-for-coding"))
      } *> mkRegistryFromDisk.flatMap { reg =>
        reg.candidateReport().map { j =>
          assertEquals(reportRefs(j), List("zhipu/glm-5.3-flash"), "候选集必须恰为唯一可用 provider 的那一个模型")
          assertEquals(strList(j, "chainRefsDropped"), List("kimi/kimi-for-coding"), "不存在的链成员必须显式标 dropped")
        }
      }
    }
  }

  // ── R2：旁路写者 lost update ⇒ 守卫上浮失败 ─────────────────
  test("R2 无锁旁路写者把已删 provider 写回 ⇒ undeletedProviders 点名上浮（不静默）") {
    withTempRoot { root =>
      IO {
        writeConfig(root, prodLike)
        writeNebulaChain(root, "zhipu/glm-5.3-flash", Nil)
      } *> mkRegistryFromDisk.flatMap { reg =>
        val stale = os.read(root / nebflow.shared.Branding.configFileName) // 旁路写者在删除**之前**读到的底稿
        for
          r <- ConfigService.updateConfig("""{"llm":{"providers":{"kimi":null}}}""")
          assert0 <- IO(assertEquals(r, Right(()), "正常路径删除应成功"))
          afterDelete <- IO.blocking(os.read(root / nebflow.shared.Branding.configFileName).contains("\"kimi\""))
          // 旁路写者此刻回写它的旧底稿（PluginDispatchPolicy/PluginRegistry 同形态：
          // 读全量 → 改自己子树 → 原子写回，且不走 ConfigService.writeLocked）
          _ <- IO(os.write.over(root / nebflow.shared.Branding.configFileName, stale))
          resurrected <- IO.blocking(os.read(root / nebflow.shared.Branding.configFileName).contains("\"kimi\""))
          // 守卫：把"声明删除"逐名回读核对
          leftover <- IO(ConfigService.undeletedProviders(List("kimi")))
          _ <- reg.reloadConfig(None)
          report <- reg.candidateReport()
        yield
          assert(!afterDelete, "删除当时必须真的不在盘上")
          assert(resurrected, "旁路旧底稿回写后 kimi 必须复活（本通道的实证）")
          assertEquals(leftover, List("kimi"), "守卫必须点名未落地的 kimi ⇒ 调用方上浮失败，禁止静默")
          assert(!reportRefs(report).exists(_.startsWith("kimi/")), "仅配置复活不构成当前 agent 的授权")
      }
    }
  }

  // ── disabled 是保留通道：显式摘除也能挡住 reserve ──────────────
  test("R3 disabled:true 的 provider 不进候选集（配置面内的显式摘除同样生效）") {
    withTempRoot { root =>
      IO {
        writeConfig(root, prodLike, disabled = Set("kimi"))
        writeNebulaChain(root, "zhipu/glm-5.3-flash", Nil)
      } *> mkRegistryFromDisk.flatMap { reg =>
        reg.candidateReport().map { j =>
          assert(!reportRefs(j).exists(_.startsWith("kimi/")), s"disabled provider 不得进候选集，实际 ${reportRefs(j)}")
          assertEquals(strList(j, "disabledProviders"), List("kimi"), "disabled 名单也应可读")
        }
      }
    }
  }
