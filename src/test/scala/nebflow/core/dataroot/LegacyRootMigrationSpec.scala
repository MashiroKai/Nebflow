package nebflow.core.dataroot

import java.nio.file.Files
import java.security.MessageDigest

import nebflow.core.root.{AccountProfile, AccountRoot, MigrationReport, RootLadder, RootLayer, RootLayout}
import nebflow.shared.PathUtil

/**
 * 三层数据根批 · B1 第二腿 —— **验收 V4（老用户升级）的红证据 spec**。
 *
 * == 编号口径（勿混）==
 * 本文件用**派单验收编号 V4** = 「平铺样本根 ⇒ 升级 ⇒ 内容一次性归入首个账号目录、**原件仍在**可回滚；
 * 未登录时落 `pending-claim/<ts>/` 且不被认作生效根」。契约里的「V3 迁移与 autostart」是
 * **实现腿分组**，不是验收编号。
 *
 * == 断言原文（派单）==
 * 「V4（平铺样本根 ⇒ 升级 ⇒ 内容一次性归入首个账号目录、**原件仍在**可回滚；未登录时落
 * `pending-claim/<ts>/` 且不被认作生效根）」
 *
 * == 契约权威（design-api.md §2/§4 + §10 A10）==
 * - `RootLayout.migrate(machineRoot): MigrationReport`；`RootLayout.rollback(report)`；
 *   `MigrationReport(machineRoot, accountSub, accountRoot, pendingClaim, movedEntries, reportPath)`；
 * - 「migrate = **复制** + 逐条 sha256 记账，源目录一个字节不动」（作者裁定，§4.3）；
 * - 「未登录 ⇒ `machineRoot/pending-claim/<ts>/`（ts = 本地 `yyyyMMdd-HHmmss`），layout.json 写上，
 *   指针不写」（§4.2）；
 * - 「rollback 先按 manifest 校验副本摘要，一致才删副本，然后 clearPointer + 删 layout.json ⇒
 *   下次进程起来回到 flat」（§4.3）；
 * - 「触发点 = 显式动作，**绝不在 boot 里自动跑**」⇒ 本 spec 只调 `migrate`，从不通过启动序触发（§4.1）；
 * - A10：「`migrate` 后原件逐条 sha256 不变；`rollback` 后回到 flat 且数据可读」。
 *
 * == 未在这里断言的两条（归属别的腿）==
 * - `migration-report.json` 的**内部键布局**（逐条 `{name, kind, bytes, sha256}` + 引擎版本）：
 *   契约给了语义没冻结字段名，本 spec 只断言「报告落盘且在机器层」（拿字段名断言等于替实现腿定名）；
 * - `[migrate] copying <n> entries, <size> total` 那行日志的文本：日志面断言需要抓 logger，
 *   与本进程其它套件共享，留给能隔离实例的腿。
 *
 * 隔离纪律：样本根一律 `Files.createTempDirectory("b1-red-")`（⇒ `/tmp/b1-red-*`），用完即删；
 * **绝不读写生产根 `~/.stand`**（用例开头 `PathUtil.dataRoot` 的读取只是恢复进程级换根用的旧值捕获）。
 */
class LegacyRootMigrationSpec extends munit.FunSuite:

  private val Ts = "2026-11-04T09:00:00Z"

  private def tempRoot(): os.Path = os.Path(Files.createTempDirectory("b1-red-"))

  private def sha256(p: os.Path): String =
    MessageDigest.getInstance("SHA-256").digest(os.read.bytes(p)).map("%02x".format(_)).mkString

  /**
   * 今日平铺根的样本形态（**没有 layout.json**）：项目定义 / secret / 任务账本 / 会话 / 记忆五面。
   * 返回 `相对路径 -> 文件内容`，供逐条 sha256 对照。
   */
  private def flatSample(root: os.Path): Map[String, String] =
    val files = Map(
      "projects/proj-keep/project.json" ->
        s"""{"name":"proj-keep","workspace":"${root / "ws" / "proj-keep"}","agentFile":"AGENTS.md","createdAt":1700000000000}""",
      "secrets/legacy-token" -> "legacy-value",
      "tasks-v2.json" -> """{"version":1,"tasks":[],"nextId":0}""",
      "sessions/s-1.json" -> """{"id":"s-1"}""",
      "memory/User.md" -> "# User\n"
    )
    files.foreach { case (rel, body) =>
      val p = root / os.RelPath(rel)
      os.makeDir.all(p / os.up)
      os.write.over(p, body)
    }
    files

  /** 顶层条目名（`projects` / `secrets` / `tasks-v2.json` / …）= `movedEntries` 的比对基准。 */
  private def topLevelNames(sample: Map[String, String]): Set[String] =
    sample.keys.map(_.split('/').head).toSet

  test("V4: 平铺根 + 首个账号 ⇒ migrate 一次性归入该账号目录、原件逐条 sha256 不变、rollback 回到 flat"):
    val prev = PathUtil.dataRoot
    val machineRoot = tempRoot()
    try
      val sample = flatSample(machineRoot)
      val originals = sample.map { case (rel, _) => rel -> sha256(machineRoot / os.RelPath(rel)) }

      // 前提：无 layout.json ⇒ 阶梯第 3 档 flat（0 总则 1 的兼容红线：无标记的平铺根字节不变）
      PathUtil.setMachineRoot(machineRoot)
      assertEquals(PathUtil.dataRoot, machineRoot, "无 layout.json 的平铺根：有效根 == 机器根")
      assertEquals(RootLadder.resolve(machineRoot).name, "flat", "平铺根必须判 flat（不是未登录层）")

      // 首次登录：账号根 + 档案已就位（登录腿先建账号根），但**指针尚未写**（先 migrate 再写指针，§4.1）
      val accountDir = AccountRoot.ensureAccountDir(machineRoot, "acct-first")
      AccountRoot.writeProfile(accountDir, AccountProfile("acct-first", "first@example.com", Ts, None))
      assertEquals(AccountRoot.readPointer(machineRoot), None, "前提：migrate 发生在写指针之前")

      val report: MigrationReport = RootLayout.migrate(machineRoot)

      // ── 迁移报告：落机器层、指向首个账号根、不是待认领区
      assertEquals(report.machineRoot, machineRoot)
      assertEquals(report.accountSub, Some("acct-first"), "有账号在 ⇒ 首个登录账号；无 ⇒ None")
      assertEquals(report.accountRoot, accountDir, "已登录迁移必然落进该账号根")
      assertEquals(report.pendingClaim, false, "有账号时绝不落待认领区")
      assertEquals(report.reportPath, machineRoot / "migration-report.json", "报告必须落机器层（§4.4）")
      assert(os.exists(report.reportPath), "migration-report.json 必须真的写出来")
      assert(
        AccountRoot.readLayoutMarker(machineRoot).isDefined,
        "迁移后必须写上 layout.json（否则下次起来仍是 flat，指针永远不被认）"
      )
      assert(!os.exists(machineRoot / "pending-claim"), "有账号时不得产生待认领区目录")

      // ── 一次性归入：顶层条目全数入账
      assertEquals(report.movedEntries.toSet, topLevelNames(sample), s"movedEntries 必须覆盖全部顶层条目：${report.movedEntries}")

      // ── 副本内容逐条 sha256 相等（复制语义，不是移动）
      originals.foreach { case (rel, digest) =>
        val copy = accountDir / os.RelPath(rel)
        assert(os.exists(copy), s"副本缺失：$copy")
        assertEquals(sha256(copy), digest, s"副本内容必须与原件逐字节相等：$rel")
      }

      // ── **原件仍在**（作者裁定）：源目录一个字节不动 ⇒ 可回滚
      originals.foreach { case (rel, digest) =>
        assertEquals(sha256(machineRoot / os.RelPath(rel)), digest, s"migrate 后原件必须逐字节不变：$rel")
      }

      // ── rollback：删副本 + 清 layout.json 与指针，原件不删 ⇒ 下次进程起来回到 flat
      RootLayout.rollback(report)
      assert(!os.exists(machineRoot / "layout.json"), "rollback 必须删掉 layout.json")
      assertEquals(AccountRoot.readPointer(machineRoot), None, "rollback 必须清指针（登出面同款）")
      originals.foreach { case (rel, digest) =>
        assertEquals(sha256(machineRoot / os.RelPath(rel)), digest, s"rollback 绝不删原件：$rel")
        assert(!os.exists(accountDir / os.RelPath(rel)), s"回滚必须删掉本次迁移产出的副本：$rel")
      }
      assertEquals(RootLadder.resolve(machineRoot).name, "flat", "回滚后下次起来必须回到 flat（数据可读）")
      // 数据可读：原件仍是可解析的平铺数据面（这里用项目定义的可读性作证）
      assert(os.exists(machineRoot / "projects" / "proj-keep" / "project.json"), "回滚后平铺数据的项目定义必须仍在原位")
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(machineRoot)

  test("V4: 未登录时 migrate ⇒ 落 pending-claim/<ts>/、不写指针、且不被认作生效根"):
    val prev = PathUtil.dataRoot
    val machineRoot = tempRoot()
    try
      val sample = flatSample(machineRoot)
      val originals = sample.map { case (rel, _) => rel -> sha256(machineRoot / os.RelPath(rel)) }

      // 前提：老平铺机器上先有布局标记、但尚未登录（无账号根、无指针）
      AccountRoot.writeLayoutMarker(machineRoot)
      assertEquals(AccountRoot.readPointer(machineRoot), None)
      assert(!os.exists(machineRoot / "accounts"), "前提：还没有任何账号层目录")

      val report = RootLayout.migrate(machineRoot)

      // ── 待认领区：machineRoot/pending-claim/<ts>/，ts = 本地 yyyyMMdd-HHmmss
      assertEquals(report.pendingClaim, true, "未登录迁移必须标记为待认领")
      assertEquals(report.accountSub, None, "未登录时没有已知账号")
      val claimRoot = machineRoot / "pending-claim"
      assertEquals(report.accountRoot / os.up, claimRoot, s"待认领区目录必须是 pending-claim/<ts>/：${report.accountRoot}")
      assert(
        report.accountRoot.last.matches("""\d{8}-\d{6}"""),
        s"ts 必须是本地 yyyyMMdd-HHmmss（§4.2），实际：${report.accountRoot.last}"
      )
      assert(report.accountRoot.toString.startsWith(claimRoot.toString + "/"), report.accountRoot.toString)

      // ── 内容复制进待认领区；原件仍在
      originals.foreach { case (rel, digest) =>
        val copy = report.accountRoot / os.RelPath(rel)
        assert(os.exists(copy), s"待认领区的副本缺失：$copy")
        assertEquals(sha256(copy), digest, s"待认领区副本必须与原件逐字节相等：$rel")
        assertEquals(sha256(machineRoot / os.RelPath(rel)), digest, s"原件必须原地不动：$rel")
      }

      // ── 布局标记写上、指针不写
      assert(AccountRoot.readLayoutMarker(machineRoot).isDefined, "layout.json 必须写上（后续登录才知道该认领）")
      assertEquals(AccountRoot.readPointer(machineRoot), None, "未登录迁移绝不写指针")

      // ── 阶梯：有 layout 标记 + 无指针 ⇒ 未登录层；待认领区**绝不**成为生效根
      PathUtil.setMachineRoot(machineRoot)
      RootLadder.resolve(machineRoot) match
        case RootLayer.Unauthenticated(machineRootResolved, effective) =>
          assertEquals(machineRootResolved, machineRoot)
          assertEquals(effective, machineRoot / "unauthenticated", "未登录层的有效根 = machineRoot/unauthenticated")
        case other => fail(s"有 layout 标记且无指针必须判未登录层（§2 第 2 档），实际：$other")
      assertEquals(PathUtil.dataRoot, machineRoot / "unauthenticated")
      assert(
        PathUtil.dataRoot != report.accountRoot,
        "待认领区只是中转目录，绝不能被当成生效根（否则未登录层直接拿着老数据的副本对外服务）"
      )
      assert(
        !PathUtil.dataRoot.toString.startsWith(claimRoot.toString + "/"),
        "生效根不得落在 pending-claim 里"
      )
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(machineRoot)

end LegacyRootMigrationSpec
