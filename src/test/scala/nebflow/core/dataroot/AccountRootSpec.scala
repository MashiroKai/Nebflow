package nebflow.core.dataroot

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

import nebflow.core.root.{AccountProfile, AccountRoot, ActiveAccount, LayoutMarker, RootLadder, RootLayer}
import nebflow.shared.PathUtil

/**
 * 三层数据根批 · B1 第二腿 —— **验收 V1（未登录层）与 V5（账号层 0700）的红证据 spec**。
 *
 * == 编号口径（勿混）==
 * 本文件用**派单验收编号**：
 *  - **V1** = 「无指针 + layout.json ⇒ 未登录层；该层不产生 device.json（不铸身份、不借别的层的 id）；
 *    日志/状态面有未登录标记」；
 *  - **V5** = 「`accounts/<sub>` 权限 0700」。
 * 契约 `design-api.md` 里说的「V1 根阶梯 / V2 登录链 / V3 迁移与 autostart」是**实现腿分组**，
 * 不是验收编号 —— 两个编号体系各说各的，本 spec 只覆盖上面两条验收断言（原文见下）。
 *
 * == 断言原文（派单）==
 *  - V1：「无指针 + layout.json ⇒ 未登录层；账号层无 device.json；日志/状态有未登录标记」；
 *  - V5：「`accounts/<sub>` 权限 0700」；
 *  - 附：指针读写（`AccountRoot.pointerPath/readPointer/writePointer/clearPointer`）、
 *    `profile.json` 四字段可存可读。
 *
 * == 契约权威（符号名逐字照 design-api.md，§2/§3/§6，不得自创）==
 *  - 阶梯：`RootLadder.resolve(machineRoot): RootLayer`，四档顺序即契约（§2）；
 *    第 0 档 `profile.json` 在场 / 第 1 档指针可用 / 第 2 档 `layout.json` + 无指针 ⇒ 未登录层 /
 *    第 3 档其余 ⇒ flat（含「指针坏档降级 flat」，A6）。
 *  - 账号层：`AccountRoot.accountRoot/ensureAccountDir/dirNameOf/readProfile/writeProfile/
 *    layoutMarkerPath/readLayoutMarker/writeLayoutMarker/isAccountRoot/findBySub`（§3）。
 *  - 未登录层：不铸 deviceId 的 fail-closed 守卫 + `UNAUTHENTICATED-LAYER` 标记（§6.2/§6.3），
 *    `readDeviceIdOnly` 在未登录层返回 None。
 *  - 权限：账号根 0700；`active-account` / `profile.json` 0600（§3.2）。
 *
 * == 未在这里断言的两条（归属别的腿，原因写在断言旁）==
 *  - `/api/health.rootLayer`（A4）与「boot 期恰好一条 `UNAUTHENTICATED-LAYER` WARN」：都需要**跑起实例**
 *    读 HTTP / 抓日志；本腿硬约束「不许起实例」，故状态面只断言 `RootLadder.current.name`
 *    （即 `/api/health.rootLayer` 的值源），日志面只断言 §6.3 规定同样带该标记串的**异常消息**。
 *  - Windows 的 DACL 面：`CredentialFileAcl.restrictDirectory` 在 Windows 为 no-op（§3.2 既有纪律），
 *    故权限断言按 POSIX 平台假设执行（Windows runner 上跳过，并在断言文案里写明）。
 *
 * 隔离纪律：每个用例的样本根一律 `Files.createTempDirectory("b1-red-")`（⇒ `/tmp/b1-red-*`），
 * 用完即删；**绝不读写生产根 `~/.stand`**（用例开头 `PathUtil.dataRoot` 的读取只是恢复进程级换根用的
 * 旧值捕获 —— 在未换根状态下它是纯路径构造（HOME 环境变量分支），零磁盘访问）。
 */
class AccountRootSpec extends munit.FunSuite:

  private val IsWindows: Boolean = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** 样本根：派单指定的 /tmp/b1-red-*（绝不用 ~/.stand）。 */
  private def tempRoot(): os.Path = os.Path(Files.createTempDirectory("b1-red-"))

  /**
   * 把进程级机器层根钉到样本根上跑用例，结束恢复原值并删干净样本根。
   * `setMachineRoot` = 生产启动序唯一调用点（§1）：只钉机器层，有效根交给 §2 阶梯。
   */
  private def withPinnedMachineRoot[T](f: os.Path => T): T =
    val prev = PathUtil.dataRoot
    val root = tempRoot()
    try
      PathUtil.setMachineRoot(root)
      f(root)
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(root)

  /** POSIX 权限串（"rwx------" 形态）；Windows 上返回空串（该平台权限走 DACL，不是 mode 位）。 */
  private def modeOf(p: os.Path): String =
    if IsWindows then "" else PosixFilePermissions.toString(Files.getPosixFilePermissions(p.toNIO))

  private def walkNamed(root: os.Path, name: String): List[String] =
    os.walk(root).filter(os.isFile).filter(_.last == name).map(_.relativeTo(root).toString).toList.sorted

  private def deviceJson(id: String): String =
    s"""{"deviceId":"$id","deviceName":"b1-red-probe","platform":"macos"}"""

  // ───────────────────────────────────────────────────────────────────────
  // V1 —— 未登录层（无指针 + layout.json）
  // ───────────────────────────────────────────────────────────────────────

  test("V1: layout.json 在场且无 active-account 指针 ⇒ 未登录层（root = machineRoot/unauthenticated）"):
    withPinnedMachineRoot { root =>
      // 机器层布局标记（§3「layout 标记」）：写到 machineRoot/layout.json，内容 {"layout":"accounts-v1"}
      AccountRoot.writeLayoutMarker(root)
      assertEquals(AccountRoot.layoutMarkerPath(root), root / "layout.json")
      assertEquals(
        AccountRoot.readLayoutMarker(root),
        Some(LayoutMarker("accounts-v1")),
        "layout 标记值域当前唯一合法值 = accounts-v1"
      )
      assertEquals(AccountRoot.readPointer(root), None, "前提：本用例没有任何 active-account 指针")

      // 阶梯第 2 档（§2）：有 layout 标记且无可用指针 ⇒ 未登录层
      RootLadder.resolve(root) match
        case RootLayer.Unauthenticated(machineRoot, effective) =>
          assertEquals(machineRoot, root, "机器层根 = 探测入口")
          assertEquals(effective, root / "unauthenticated", "未登录层的有效根 = machineRoot/unauthenticated")
        case other =>
          fail(s"无指针 + layout.json 必须判未登录层（§2 第 2 档），实际：$other")

      // 未登录层**不是**平铺根：两者混淆会把用户数据面指向机器根
      assert(
        !RootLadder.resolve(root).isInstanceOf[RootLayer.Flat],
        "有 layout 标记时绝不回落 flat —— 那等于静默忽略布局标记"
      )

      // 生产启动序唯一调用点：setMachineRoot ⇒ 有效根走阶梯（进程级单值，§0 总则 2）
      PathUtil.setMachineRoot(root)
      assertEquals(PathUtil.machineRoot, root, "setMachineRoot 只钉机器层根")
      assertEquals(PathUtil.dataRoot, root / "unauthenticated", "有效根必须由阶梯派生，不是钉死的值")

      // 状态面标记（§6.3 第 2 条）：/api/health.rootLayer 的取值就是 layer.name
      assertEquals(
        RootLadder.current.name,
        "unauthenticated",
        "状态面未登录标记：RootLadder.current.name = /api/health.rootLayer 必须报的值"
      )
      // 零写盘：resolve 是纯探测（§2）—— 解一次不得在磁盘上留下任何字节
      assertEquals(walkNamed(root, "device.json"), Nil, "resolve 期间不得铸任何设备身份")
    }

  test("V1: 未登录层拒绝铸造设备身份（UNAUTHENTICATED-LAYER，fail-closed）且全树零 device.json"):
    withPinnedMachineRoot { root =>
      AccountRoot.writeLayoutMarker(root)
      PathUtil.setMachineRoot(root)
      assertEquals(RootLadder.current.name, "unauthenticated", "前提：本进程已定档为未登录层")

      // §6.2 唯一守卫：未登录层不铸 deviceId —— 抛，不静默返回临时 id
      // （静默返回一次，服务端就留一条幽灵设备行：NeblinkModel.scala:178-212 的历史病根）
      val thrown = intercept[IllegalStateException] {
        nebflow.neblink.DeviceIdentity.loadOrCreate.unsafeRunSync()
      }
      assert(
        Option(thrown.getMessage).exists(_.contains("UNAUTHENTICATED-LAYER")),
        s"拒绝铸造时抛出的异常消息必须含稳定标记串 UNAUTHENTICATED-LAYER（§6.2/§6.3），实际：${thrown.getMessage}"
      )
      // §6.2：只读面同样不得在未登录层给出 id
      assertEquals(
        nebflow.neblink.DeviceIdentity.readDeviceIdOnly,
        None,
        "readDeviceIdOnly 在未登录层必须返回 None（不铸造、不读别的层的 id）"
      )
      // A5 的进程内半边：任何层都不得因这次启动产生 device.json
      assertEquals(
        walkNamed(root, "device.json"),
        Nil,
        "A5：未登录层启动后 machineRoot 全树零 device.json（含未登录层自身与账号层）"
      )
    }

  test("V1: 未登录层不借账号层 / 平铺面的 device.json（'账号层无 device.json' 的隔离证明）"):
    withPinnedMachineRoot { root =>
      AccountRoot.writeLayoutMarker(root)
      // 前提夹具：机器根与某个账号根里都躺着可解码的 device.json（历史遗留 ⇔ 他人身份）
      val accountDir = AccountRoot.ensureAccountDir(root, "acct-first")
      AccountRoot.writeProfile(
        accountDir,
        AccountProfile("acct-first", "first@example.com", "2026-11-04T09:00:00Z", None)
      )
      os.write.over(accountDir / "device.json", deviceJson("acct-layer-device"), createFolders = true)
      os.write.over(root / "device.json", deviceJson("flat-layer-device"), createFolders = true)

      PathUtil.setMachineRoot(root)
      // 阶梯仍判未登录层：账号目录存在但没有指针 ≠ 已登录
      assertEquals(RootLadder.current.name, "unauthenticated", "有账号目录但无指针 ⇒ 仍是未登录层")
      assertEquals(
        nebflow.neblink.DeviceIdentity.readDeviceIdOnly,
        None,
        "未登录层不得借用账号层/平铺面的 device.json（有效根 = machineRoot/unauthenticated，读不到就 None）"
      )
      assertEquals(
        RootLadder.resolve(root).root,
        root / "unauthenticated",
        "未登录层的有效根与账号根/平铺面逐字不同"
      )
    }

  test("V1: 指针坏档 ⇒ 降级 flat（绝不落未登录层，A6）"):
    withPinnedMachineRoot { root =>
      AccountRoot.writeLayoutMarker(root)
      os.write.over(AccountRoot.pointerPath(root), "{", createFolders = true) // 坏 JSON
      assertEquals(
        AccountRoot.readPointer(root),
        None,
        "坏档 ⇒ None（不抛；WARN ROOT-POINTER-DAMAGED 由读面留痕，§3）"
      )
      RootLadder.resolve(root) match
        case RootLayer.Flat(machineRoot, effective) =>
          assertEquals(machineRoot, root)
          assertEquals(effective, root, "平铺档：有效根 == 机器根（无 layout.json 的根字节不变，§0 总则 1）")
        case other =>
          fail(
            s"指针坏档必须降级 flat（§2 第 3 档）而不是未登录层 —— 判未登录层会把一个坏 JSON " +
              s"放大成「用户数据根消失」，实际：$other"
          )
    }

  // ───────────────────────────────────────────────────────────────────────
  // 指针读写（V1/V2 共用面，§3）
  // ───────────────────────────────────────────────────────────────────────

  test("指针读写：roundtrip 四字段、0600、clearPointer 只删指针（不删账号目录）"):
    withPinnedMachineRoot { root =>
      val acct = ActiveAccount("logto|user-42", "user42@example.com", "2026-11-04T09:00:00Z")
      assertEquals(AccountRoot.pointerPath(root), root / "active-account", "机器层指针文件 = machineRoot/active-account")
      assertEquals(AccountRoot.readPointer(root), None, "缺档 ⇒ None（不是未登录，只是没有指针）")

      AccountRoot.writePointer(root, acct)
      assertEquals(
        AccountRoot.readPointer(root),
        Some(acct),
        "指针三字段必须原样往返（accountSub 保持原文，含 '|' 这类 Logto 形态 —— 不做目录名映射）"
      )
      if !IsWindows then
        assertEquals(modeOf(AccountRoot.pointerPath(root)), "rw-------", "active-account 必须 0600（§3.2）")

      // clearPointer = 登出面：只删指针文件，账号目录一个字节不动
      val accountDir = AccountRoot.ensureAccountDir(root, acct.accountSub)
      AccountRoot.clearPointer(root)
      assertEquals(AccountRoot.readPointer(root), None)
      assert(!os.exists(AccountRoot.pointerPath(root)), "clearPointer 必须删掉指针文件")
      assert(os.exists(accountDir), "clearPointer 只删指针，绝不删账号目录（登出 ≠ 删数据）")
    }

  // ───────────────────────────────────────────────────────────────────────
  // V5 —— accounts/<sub> 权限 0700 + dirNameOf 映射（§3.1/§3.2）
  // ───────────────────────────────────────────────────────────────────────

  test("V5: ensureAccountDir ⇒ accounts/<dirNameOf(sub)>，权限 0700，幂等"):
    withPinnedMachineRoot { root =>
      val accountDir = AccountRoot.ensureAccountDir(root, "acct-first")
      assertEquals(AccountRoot.accountRoot(root, "acct-first"), accountDir, "accountRoot 与 ensureAccountDir 必须同一个路径")
      assertEquals(
        accountDir,
        root / "accounts" / AccountRoot.dirNameOf("acct-first"),
        "<accountSub> 一律指 dirNameOf(sub) 的结果（§3.1 末条）"
      )
      assert(os.isDir(accountDir), "ensureAccountDir 必须真的建目录")
      if !IsWindows then
        assertEquals(
          modeOf(accountDir),
          "rwx------",
          "V5/A3：账号层根必须 0700（CredentialFileAcl.restrictDirectory，POSIX 平台）"
        )

      // 幂等：二次调用不改路径、不改权限（§3.2「已是 owner-only 的目录不再 chmod」）
      assertEquals(AccountRoot.ensureAccountDir(root, "acct-first"), accountDir, "幂等")
      if !IsWindows then assertEquals(modeOf(accountDir), "rwx------", "幂等调用不得放宽权限")
    }

  test("V5/dirNameOf: '|' 不能当目录名（Windows 非法）⇒ x-<sha256 前 24>；可读档原样；稳定且单射"):
    withPinnedMachineRoot { root =>
      val piped = "logto|user-42"
      val mapped = AccountRoot.dirNameOf(piped)
      assert(mapped.startsWith("x-"), s"'|' 等非法字符必须走映射分支，实际：$mapped")
      assertEquals(mapped.length, 2 + 24, "映射形态 = x- + sha256(sub) 十六进制前 24 位")
      assertEquals(AccountRoot.dirNameOf(piped), mapped, "映射必须稳定（同 sub 恒同目录名）")
      assertNotEquals(AccountRoot.dirNameOf("logto|user-43"), mapped, "不同 sub 必须不同目录名（单射）")
      List("|", "/", "\\", "..").foreach { bad =>
        assert(!mapped.contains(bad), s"目录名不得含 '$bad'（防穿越）：$mapped")
      }
      assertEquals(
        AccountRoot.dirNameOf("acct-first"),
        "acct-first",
        "可读档（^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$）原样，人判优先"
      )

      // 落地：两个账号各得一个目录，权威值留在 profile.json（可反查）
      val pipedDir = AccountRoot.ensureAccountDir(root, piped)
      assertEquals(pipedDir.last, mapped)
      AccountRoot.writeProfile(pipedDir, AccountProfile(piped, "user42@example.com", "2026-11-04T09:00:00Z", None))
      val read = AccountRoot.ensureAccountDir(root, "acct-first")
      AccountRoot.writeProfile(read, AccountProfile("acct-first", "first@example.com", "2026-11-04T09:00:00Z", None))
      assertEquals(AccountRoot.findBySub(root, piped), Some(pipedDir), "按 profile.json 里的原文反查（账号数=个位数）")
      assertEquals(AccountRoot.findBySub(root, "acct-first"), Some(read))
      assertEquals(AccountRoot.findBySub(root, "logto|nobody"), None)
      if !IsWindows then
        assertEquals(modeOf(pipedDir), "rwx------", "映射出来的账号根同样 0700（V5 不看目录名形态）")
    }

  test("profile.json: 四字段（accountSub/email/createdAt/hostPromptDeclinedAt）可存可读 + 0600 + isAccountRoot"):
    withPinnedMachineRoot { root =>
      val accountDir = AccountRoot.ensureAccountDir(root, "acct-first")
      assert(!AccountRoot.isAccountRoot(accountDir), "空目录还不是账号根：判据 = profile.json 存在（§3 + 阶梯第 0 档）")

      val profile = AccountProfile("acct-first", "first@example.com", "2026-11-04T09:00:00Z", None)
      AccountRoot.writeProfile(accountDir, profile)
      assertEquals(AccountRoot.readProfile(accountDir), Some(profile), "四字段必须原样往返（None 形态）")
      assert(AccountRoot.isAccountRoot(accountDir), "写了 profile.json ⇒ 本目录即账号根")
      if !IsWindows then
        assertEquals(modeOf(accountDir / "profile.json"), "rw-------", "profile.json 必须 0600（§3.2）")

      val declined = profile.copy(hostPromptDeclinedAt = Some("2026-11-05T10:00:00Z"))
      AccountRoot.writeProfile(accountDir, declined)
      assertEquals(
        AccountRoot.readProfile(accountDir),
        Some(declined),
        "hostPromptDeclinedAt 必须可存可读（Some 形态是「用户拒绝过宿主提示」的唯一记账）"
      )
      assertEquals(AccountRoot.readProfile(root / "accounts" / "missing"), None, "缺档 ⇒ None（不抛）")
    }

  // ───────────────────────────────────────────────────────────────────────
  // 阶梯第 0/1 档（账号层 ↔ 机器层的双向定位，§2 + §8.2）
  // ───────────────────────────────────────────────────────────────────────

  test("V1/V5: 指针可用 ⇒ RootLayer.Account；--home <账号根> 走第 0 档并还原机器层根"):
    withPinnedMachineRoot { root =>
      val accountDir = AccountRoot.ensureAccountDir(root, "acct-first")
      AccountRoot.writeProfile(
        accountDir,
        AccountProfile("acct-first", "first@example.com", "2026-11-04T09:00:00Z", None)
      )
      AccountRoot.writeLayoutMarker(root)
      AccountRoot.writePointer(root, ActiveAccount("acct-first", "first@example.com", "2026-11-04T09:00:00Z"))

      // 第 1 档：指针 → accounts/<dir>
      RootLadder.resolve(root) match
        case RootLayer.Account(machineRoot, accountSub, effective) =>
          assertEquals(machineRoot, root)
          assertEquals(accountSub, "acct-first", "accountSub 保原文（不是目录名）")
          assertEquals(effective, accountDir)
        case other => fail(s"指针可用必须判 Account（§2 第 1 档），实际：$other")
      assertEquals(RootLadder.resolve(root).name, "account")

      PathUtil.setMachineRoot(root)
      assertEquals(PathUtil.dataRoot, accountDir, "账号档的有效根 = 指针指向的账号根")

      // 第 0 档：--home <账号根>（DaemonSpawn / autostart 传的是已解析好的根，§8.2）
      assertEquals(
        RootLadder.machineRootOf(accountDir),
        root,
        "machineRootOf：.../accounts/<dir> ⇒ parents(2)，零 IO 纯结构推断"
      )
      RootLadder.resolve(accountDir) match
        case RootLayer.Account(machineRoot, accountSub, effective) =>
          assertEquals(machineRoot, root, "第 0 档：该目录自身是账号根，机器层 = parents(2)")
          assertEquals(accountSub, "acct-first")
          assertEquals(effective, accountDir)
        case other => fail(s"profile.json 在场的目录必须判 Account（§2 第 0 档），实际：$other")
    }

end AccountRootSpec
