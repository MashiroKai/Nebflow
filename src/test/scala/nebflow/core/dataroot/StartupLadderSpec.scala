package nebflow.core.dataroot

import java.nio.file.Files
import java.security.MessageDigest

import nebflow.core.root.{AccountProfile, AccountRoot, ActiveAccount, RootLadder, RootLayer}
import nebflow.neblink.identity.NeblinkEnrollment
import nebflow.shared.PathUtil

/**
 * 三层数据根批 · b1-ladder（tenant-full-flow-batch2）—— **Main 启动改走 RootLadder 的接线 spec**。
 *
 * == 断言原文（派单）==
 *  - (a) ladder 启动后 active-account 指针落盘读回一致；
 *  - (b) 迁移失败回滚后旧根字节不变 + 指针清空；
 *  - (c) --home 显式换根仍平铺档。
 *
 * == 契约权威（design-api.md §2/§4/§8；符号对齐 main 现行 core/root，不得自创）==
 *  - 启动序 §8.1：machineRoot = `--home` | `PathUtil.machineRoot`（今日解析链原样）⇒
 *    `PathUtil.setMachineRoot`（**生产启动序唯一调用点**，paths.scala:252）⇒ 有效根由
 *    `RootLadder` 阶梯派生（进程级单值，定案一次）；
 *  - 「禁热切」的判定面 = `LoginGuard.checkHotSwitch`（core/root/Guards.scala ——
 *    守卫判定逻辑只许在 Guards.scala 一处的铁律；生产调用点 = Main 启动序）；
 *  - 登录腿（契约 §4.1）：平铺机器首次登录 ⇒ `RootLayout.migrate`（复制 + 逐条 sha256
 *    记账，原件不动）**先于**写指针；**触发点 = 显式动作，绝不在 boot 自动跑**（兼容红线：
 *    无 layout.json 的平铺根升级后第一次启动仍 flat + 零写盘）—— 本 spec 直接驱动登录腿的
 *    建层编排（`NeblinkEnrollment.establishAccountRootAfterSignIn`），从不经启动序触发迁移；
 *  - 迁移失败回滚 = `RootLayout.rollback`（按 manifest 校验后删副本 + `clearPointer` +
 *    删 layout.json ⇒ 下次进程起来回到 flat；RootLayout.scala:185 的 clearPointer 回滚用法）；
 *  - 显式 --home 平铺档钉死语义（paths.scala:272-273）：显式换根 ⇒ 平铺档、有效根 = 那个值，
 *    不探盘 —— 测试工具面 `setDataRoot` 保留不动（713 处既有引用的前提）。
 *
 * == 红绿态登记（测试纪律：spec 先写、能出红态的先红后绿）==
 *  - (a)/(a2)/(b)：实现前 spec 引用的 `NeblinkEnrollment.establishAccountRootAfterSignIn`
 *    尚不存在 ⇒ `sbt Test/compile` 红（缺符号，输出原样留证于提交信息），实现后转绿；
 *  - (c)：断言的是 `setMachineRoot` / `setDataRoot` 的**既有**钉死语义（回归钉 —— 防启动序
 *    改接时破坏平铺档语义），改前即绿 ⇒ **新增 spec 无红态**（如实标注）。
 *
 * 隔离纪律：样本根一律 `Files.createTempDirectory("b1-red-")`（⇒ `/tmp/b1-red-*`），用完即删；
 * **绝不读写生产根 `~/.stand`**（用例开头 `PathUtil.dataRoot` 的读取只是恢复进程级换根用的
 * 旧值捕获，零写盘——与既有 5 件套同一口径）。
 */
class StartupLadderSpec extends munit.FunSuite:

  private val Ts = "2026-11-06T04:00:00Z"
  private val Sub = "logto|user-42"
  private val Email = "user42@example.com"

  test("账号归属闸：同账号/平铺档放行，异账号/未登录层/无身份 fail-closed（logout-login-recovery 批重定形）"):
    val root = os.temp.dir(prefix = "cutover-guard-")
    val account = RootLayer.Account(root, Sub, root / "accounts" / "a")
    val flat = RootLayer.Flat(root, root)
    val unauth = RootLayer.Unauthenticated(root, root / "unauthenticated")
    // 账号档：同账号放行（凭据落回自己的账号根）
    assertEquals(NeblinkEnrollment.accountCutoverRefusal(account, Some(Sub), true), None)
    // 账号档：异账号硬拒（跨账号 enroll 永不盲放）
    assert(NeblinkEnrollment.accountCutoverRefusal(account, Some("other"), true).exists(_.contains("account-switch")))
    // 平铺档：放行（只落凭据，根层零写 —— writeActiveAccountPointer 的非账号档跳过腿同批）
    assertEquals(NeblinkEnrollment.accountCutoverRefusal(flat, Some(Sub), true), None)
    assertEquals(NeblinkEnrollment.accountCutoverRefusal(flat, Some(Sub), false), None)
    assertEquals(NeblinkEnrollment.accountCutoverRefusal(flat, None, false), None)
    // 平铺档 + 显式 + 无身份：放行（配对码腿 E6，无 Logto 令牌面；平铺根无归属可串）
    assertEquals(NeblinkEnrollment.accountCutoverRefusal(flat, None, true), None)
    // 未登录层：fail-closed，稳定码 = enroll-refused-no-account-root（该层不铸设备身份，契约 §6.2）
    assert(
      NeblinkEnrollment.accountCutoverRefusal(unauth, Some(Sub), true).exists(_.startsWith("enroll-refused-no-account-root:")),
      "未登录层拒绝必须带稳定码头（分类面按它映射三段式）"
    )
    // 未登录层 + 显式无身份：同样拒（有主根收无主凭据 = 串号面）
    assert(NeblinkEnrollment.accountCutoverRefusal(unauth, None, true).isDefined)
    // 账号档显式登录无身份：fail-closed（有主根收无主凭据 = 串号面）
    assert(NeblinkEnrollment.accountCutoverRefusal(account, None, true).exists(_.contains("identity-missing")))
    // 自动路径无身份：放行（静默续登/设备码遗产路径，不变）
    assertEquals(NeblinkEnrollment.accountCutoverRefusal(account, None, false), None)
    os.remove.all(root)

  /** 样本根：派单指定的 /tmp/b1-red-*（绝不用 ~/.stand）。 */
  private def tempRoot(): os.Path = os.Path(Files.createTempDirectory("b1-red-"))

  private def sha256(p: os.Path): String =
    MessageDigest.getInstance("SHA-256").digest(os.read.bytes(p)).map("%02x".format(_)).mkString

  /** 平铺样本根（**没有 layout.json**）：项目定义 / secret / 任务账本 / 会话 / 记忆五面。 */
  private def flatSample(root: os.Path): Map[String, String] =
    val files = Map(
      "projects/proj-keep/project.json" -> """{"name":"proj-keep"}""",
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

  /** 把进程级机器层根钉到样本根上跑用例，结束恢复原值并删干净样本根（§8.1 启动序的等价面）。 */
  private def withPinnedMachineRoot[T](f: os.Path => T): T =
    val prev = PathUtil.dataRoot
    val root = tempRoot()
    try
      PathUtil.setMachineRoot(root)
      f(root)
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(root)

  // ───────────────────────────────────────────────────────────────────────
  // (a) ladder 启动后 active-account 指针落盘读回一致
  // ───────────────────────────────────────────────────────────────────────

  test("(a) 登录腿建层后，启动阶梯（setMachineRoot）⇒ 账号档 + active-account 指针落盘读回一致"):
    withPinnedMachineRoot { root =>
      val sample = flatSample(root)
      val originals = sample.map { case (rel, _) => rel -> sha256(root / os.RelPath(rel)) }

      // 前提：无 layout.json ⇒ 平铺档（升级前老机器的形态）
      assertEquals(RootLadder.resolve(root).name, "flat", "前提：平铺旧根（升级前）")

      // 登录腿显式动作：迁移（复制记账，原件不动）+ 写指针（先迁移再写指针，契约 §4.1）
      val dir = NeblinkEnrollment.establishAccountRootAfterSignIn(root, Sub, Email, Ts)
      assertEquals(dir, AccountRoot.accountRoot(root, Sub), "登录腿必须落到 dirNameOf(sub) 的账号根")

      // 指针落盘读回一致（accountSub 保原文，含 '|' 的 Logto 形态不做目录名映射）
      assertEquals(AccountRoot.readPointer(root), Some(ActiveAccount(Sub, Email, Ts)))
      // 布局标记在 ⇒ 下次启动不再判 flat
      assert(
        AccountRoot.readLayoutMarker(root).isDefined,
        "登录腿必须写 layout.json（否则下次起来仍是 flat，指针永远不被认）"
      )
      // 迁移 = 复制不是移动：原件一个字节不动
      originals.foreach { case (rel, digest) =>
        assertEquals(sha256(root / os.RelPath(rel)), digest, s"migrate 后原件必须逐字节不变：$rel")
        assertEquals(sha256(dir / os.RelPath(rel)), digest, s"副本必须逐字节相等：$rel")
      }

      // 启动面（Main §8.1 定档的等价调用：setMachineRoot ⇒ 阶梯派生有效根，进程级单值）
      PathUtil.setMachineRoot(root)
      RootLadder.current match
        case RootLayer.Account(machineRoot, accountSub, effective) =>
          assertEquals(machineRoot, root, "机器层根 = 探测入口")
          assertEquals(accountSub, Sub, "accountSub 保原文（不是目录名）")
          assertEquals(effective, dir, "有效根 = 指针指向的账号根")
        case other => fail(s"指针可用必须判账号档（§2 第 1 档），实际：$other")
      assertEquals(PathUtil.dataRoot, dir, "ladder 启动后有效根 = 账号根")
      // 读回一致（阶梯定档后同一指针再次读回，同一值）
      assertEquals(AccountRoot.readPointer(root), Some(ActiveAccount(Sub, Email, Ts)))
    }

  test("(a2) 账号切换：登录腿写指针 B ⇒ 启动阶梯落在 B 的账号根（指针读写的启动面）"):
    withPinnedMachineRoot { root =>
      // 已登录 A 的机器：layout + A 账号根 + 指针→A
      AccountRoot.writeLayoutMarker(root)
      val dirA = AccountRoot.ensureAccountDir(root, "acct-a")
      AccountRoot.writeProfile(dirA, AccountProfile("acct-a", "a@example.com", Ts, None))
      AccountRoot.writePointer(root, ActiveAccount("acct-a", "a@example.com", Ts))

      // 登录腿切到 B（布局已在 ⇒ 无迁移触发，只写指针 + 就位账号根）
      val dirB = NeblinkEnrollment.establishAccountRootAfterSignIn(root, "acct-b", "b@example.com", Ts)
      assertEquals(dirB, AccountRoot.accountRoot(root, "acct-b"))
      assertEquals(AccountRoot.readPointer(root), Some(ActiveAccount("acct-b", "b@example.com", Ts)))

      // 启动面：setMachineRoot ⇒ 有效根 = B 的账号根（A 的数据面不再可达）
      PathUtil.setMachineRoot(root)
      assertEquals(PathUtil.dataRoot, dirB, "指针→B ⇒ 有效根 = B 的账号根")
      assert(PathUtil.dataRoot != dirA, "切到 B 后有效根绝不能还是 A 的账号根")
      assertEquals(RootLadder.current.name, "account")
    }

  // ───────────────────────────────────────────────────────────────────────
  // (b) 迁移失败回滚后旧根字节不变 + 指针清空
  // ───────────────────────────────────────────────────────────────────────

  test("(b) 迁移成功后写指针失败 ⇒ rollback：旧根字节不变 + 指针清空 + 下次启动回到 flat"):
    withPinnedMachineRoot { root =>
      val sample = flatSample(root)
      val originals = sample.map { case (rel, _) => rel -> sha256(root / os.RelPath(rel)) }

      // 前提：平铺旧根 + 上次进程残留的悬档指针（指向尚未就位的账号目录 ⇒ 阶梯降级 flat）
      AccountRoot.writePointer(root, ActiveAccount(Sub, Email, "2026-01-01T00:00:00Z"))
      assertEquals(RootLadder.resolve(root).name, "flat", "前提：悬档指针降级 flat（§2 第 3 档）")

      // 失败注入（writePointer 可注入面）：迁移整体成功（副本归入账号目录 + layout + 记账）后
      // 写指针一步抛 ⇒ 真实执行「迁移流程失败 ⇒ RootLayout.rollback」腿（按账校验后删副本 +
      // clearPointer + 删 layout.json，RootLayout.scala:185 的 clearPointer 回滚用法）
      val thrown = intercept[Exception] {
        NeblinkEnrollment.establishAccountRootAfterSignIn(
          root,
          Sub,
          Email,
          Ts,
          writePointer = (_, _) => sys.error("injected: pointer write failed after migrate")
        )
      }
      // 不 pin 异常类型（注入桩即异常源），只 pin「确实浮出」（不得静默吞掉）
      assert(thrown != null, "迁移流程失败必须浮出异常（不得静默吞掉）")

      // 指针清空：预置的悬档指针被 rollback 的 clearPointer 腿真删掉
      assertEquals(AccountRoot.readPointer(root), None, "回滚后指针必须清空")
      assert(!os.exists(AccountRoot.pointerPath(root)), "active-account 文件必须被 clearPointer 删掉")

      // 回到 flat 的前提：layout.json 已被回滚删除
      assert(!os.exists(AccountRoot.layoutMarkerPath(root)), "回滚必须删掉 layout.json（下次起来回到 flat）")
      assertEquals(RootLadder.resolve(root).name, "flat", "回滚后下次进程起来必须回到 flat")

      // 旧根字节不变：migrate 只复制不动源，回滚删的是**副本**
      originals.foreach { case (rel, digest) =>
        assertEquals(sha256(root / os.RelPath(rel)), digest, s"回滚后原件必须逐字节不变：$rel")
        assert(os.exists(root / os.RelPath(rel)), s"原件必须仍在原位：$rel")
      }

      // 回滚按账删副本：迁移归入账号目录的副本被逐条删除
      // （profile.json 是登录腿自建的档案，不在迁移账上 —— RootLayout 的既有保证集）
      val dir = AccountRoot.accountRoot(root, Sub)
      originals.foreach { case (rel, _) =>
        assert(!os.exists(dir / os.RelPath(rel)), s"回滚必须删掉本次迁移产出的副本：$rel")
      }

      // 记账件保留（rollback 纪律：报告不删，留审计）；有账号在 ⇒ 绝不落待认领区
      assert(os.exists(root / "migration-report.json"), "migration-report.json 必须保留（审计留痕）")
      assert(!os.exists(root / "pending-claim"), "有账号在 ⇒ 迁移归入账号目录，绝不落 pending-claim")

      // 数据可读：样本仍是可解析的平铺数据面
      assert(os.exists(root / "projects" / "proj-keep" / "project.json"), "回滚后平铺数据的项目定义必须仍在原位")
    }

  // ───────────────────────────────────────────────────────────────────────
  // (c) --home 显式换根仍平铺档（新增 spec 无红态：既有钉死语义的回归钉）
  // ───────────────────────────────────────────────────────────────────────

  test("(c) --home 显式换根仍平铺档（钉死语义）；setDataRoot 测试工具面不探盘 —— 新增 spec 无红态"):
    withPinnedMachineRoot { root =>
      // --home 指向普通目录（无 layout 标记）⇒ 平铺档：有效根 == 机器根（与今日逐字节相同）
      os.write.over(root / "sessions" / "s-1.json", """{"id":"s-1"}""", createFolders = true)
      PathUtil.setMachineRoot(root) // Main §8.1 的 --home 落点
      assertEquals(PathUtil.machineRoot, root, "setMachineRoot 只钉机器层根")
      RootLadder.resolve(root) match
        case RootLayer.Flat(machineRoot, effective) =>
          assertEquals(machineRoot, root)
          assertEquals(effective, root, "平铺档：有效根 == 机器根（无 layout.json 的根字节不变）")
        case other =>
          fail(s"--home 普通目录必须判平铺档（§2 第 3 档 / paths.scala:272-273 钉死语义），实际：$other")
      assertEquals(PathUtil.dataRoot, root, "显式换根 ⇒ 平铺档：有效根 = 那个值（不探盘）")
      assertEquals(RootLadder.current.name, "flat")

      // 测试工具面 setDataRoot 保留不动：即使目录带**完整账号布局**（layout+pointer+profile），
      // setDataRoot 仍平铺钉死 ⇒ 有效根 = 那个值（隔离实例 / canary 的全部前提）
      val root2 = tempRoot()
      try
        val dirB = AccountRoot.ensureAccountDir(root2, "acct-b")
        AccountRoot.writeProfile(dirB, AccountProfile("acct-b", "b@example.com", Ts, None))
        AccountRoot.writeLayoutMarker(root2)
        AccountRoot.writePointer(root2, ActiveAccount("acct-b", "b@example.com", Ts))
        PathUtil.setDataRoot(root2)
        assertEquals(PathUtil.dataRoot, root2, "setDataRoot 平铺钉死语义（不探盘）必须保持")
      finally os.remove.all(root2)
    }

end StartupLadderSpec
