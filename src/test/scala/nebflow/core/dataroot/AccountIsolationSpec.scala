package nebflow.core.dataroot

import cats.effect.unsafe.implicits.global

import java.nio.file.Files

import nebflow.core.project.{ProjectStore, TaskLedgerStore}
import nebflow.core.root.{AccountProfile, AccountRoot, ActiveAccount, RootLadder, RootLayer}
import nebflow.core.secrets.SecretsStore
import nebflow.shared.PathUtil

/**
 * 三层数据根批 · B1 第二腿 —— **验收 V3（双号隔离）的红证据 spec**。
 *
 * == 编号口径（勿混）==
 * 本文件用**派单验收编号 V3** = 「隔离根上建 A/B 两账号，各写 projects/secrets/tasks 样本；
 * 切到 B ⇒ **目录读取 + 工具面双证**都看不到 A 的任何一项」。契约里的「V3 迁移与 autostart」
 * 是**实现腿分组**，不是验收编号。
 *
 * == 断言原文（派单）==
 * 「V3（隔离根上建 A/B 两账号，各写 projects/secrets/tasks 样本；切到 B ⇒ **目录读取 + 工具面双证**
 * 都看不到 A 的任何一项。工具面用同一 store/collect API 在 `PathUtil.setDataRoot` 换根后取数，
 * 不联网、不起实例）」
 *
 * == 契约权威（design-api.md §0/§2/§3/§9.2）==
 * - 内容面（`projects/`、`secrets/`、`tasks-v2.json`）**保持** `PathUtil.dataRoot` ⇒ 账号层下自动进账号根
 *   （§9.2，490 处盘点的核心结论）—— 这正是本 spec 能靠换根取证的前提；
 * - 阶梯：`RootLadder.resolve(machineRoot)`，指针 = A ⇒ `RootLayer.Account(_, "acct-a", accounts/<dir>)`；
 *   指针切到 B ⇒ 有效根换成 B 的账号根（§2 第 1 档）；
 * - 双证里的「工具面」= 与生产同一份 store/collect API（`ProjectStore` / `SecretsStore` /
 *   `TaskLedgerStore`），全部只读本进程数据根，**零网络、零实例**。
 *
 * == 取证结构（为什么两条正控不能省）==
 * 1. 先在 A 的根上用同一组 API 看得见 A 的三面样本（证「样本真写进去了、API 真能读」）；
 * 2. 切到 B：同一组 API 与目录读取都必须**空**；
 * 3. 再在 B 写 B 自己的样本：看得见的**恰好只有 B 的**（排除「一律空」的假绿）。
 *
 * 隔离纪律：样本根一律 `Files.createTempDirectory("b1-red-")`（⇒ `/tmp/b1-red-*`），用完即删；
 * **绝不读写生产根 `~/.stand`**（用例开头 `PathUtil.dataRoot` 的读取只是恢复进程级换根用的旧值捕获）。
 */
class AccountIsolationSpec extends munit.FunSuite:

  private val Ts = "2026-11-04T09:00:00Z"

  private def tempRoot(): os.Path = os.Path(Files.createTempDirectory("b1-red-"))

  /** 机器层隔离根 + 两个账号目录 + 档案 + layout 标记（V5 面已在 AccountRootSpec 钉死，这里只作前提）。 */
  private def fixture(machineRoot: os.Path): (os.Path, os.Path) =
    AccountRoot.writeLayoutMarker(machineRoot)
    val a = AccountRoot.ensureAccountDir(machineRoot, "acct-a")
    val b = AccountRoot.ensureAccountDir(machineRoot, "acct-b")
    AccountRoot.writeProfile(a, AccountProfile("acct-a", "a@example.com", Ts, None))
    AccountRoot.writeProfile(b, AccountProfile("acct-b", "b@example.com", Ts, None))
    (a, b)

  /** 项目样本：与生产同形的 `projects/<name>/project.json`（`ProjectDef` 最小可解码形态）。 */
  private def writeProjectSample(accountRoot: os.Path, name: String): Unit =
    val dir = accountRoot / "projects" / name
    os.makeDir.all(dir)
    os.write.over(
      dir / "project.json",
      s"""{"name":"$name","workspace":"${accountRoot / "ws" / name}","agentFile":"AGENTS.md","createdAt":1700000000000}"""
    )

  /** 工具面 store：与生产同一份 `SecretsStore`（只把根显式固定成本用例的账号根）。 */
  private def secretsStore(accountRoot: os.Path): SecretsStore =
    new SecretsStore(
      secretsDir = accountRoot / "secrets",
      dataRoot = accountRoot,
      daemonsPath = accountRoot / "daemons.json"
    )

  private def secretNames(accountRoot: os.Path): List[String] =
    secretsStore(accountRoot).list().unsafeRunSync() match
      case Right(entries) => entries.map(_.name).sorted
      case Left(failure) => fail(s"SecretsStore.list 读取失败（$accountRoot）：$failure")

  private def projectNames(): List[String] =
    ProjectStore.listAll().unsafeRunSync().map(_.name).sorted

  private def ledgerIds(): List[String] =
    TaskLedgerStore.open().entriesSync().map(_.id).sorted

  private def dirEntries(dir: os.Path): List[String] =
    if !os.exists(dir) then Nil else os.list(dir).map(_.last).toList.sorted

  test("V3: A/B 两账号各写 projects/secrets/tasks 样本 ⇒ 切到 B 后目录读取 + 工具面双证都看不到 A"):
    val prev = PathUtil.dataRoot
    val machineRoot = tempRoot()
    try
      val (accountA, accountB) = fixture(machineRoot)
      AccountRoot.writePointer(machineRoot, ActiveAccount("acct-a", "a@example.com", Ts))

      // ── 阶段 1：A 的样本（写面 = 与读面同一 store/collect API）──────────────────
      PathUtil.setDataRoot(accountA)
      writeProjectSample(accountA, "proj-alpha")
      assertEquals(
        secretsStore(accountA).create("alpha-token", "value-alpha").unsafeRunSync(),
        Right(()),
        "SecretsStore.create：A 的 secrets 样本必须写入成功"
      )
      val alphaTaskId = TaskLedgerStore
        .open()
        .createSyncReturningId(title = "alpha-task")
        .getOrElse(fail("TaskLedgerStore.create：A 的 tasks 样本必须写入成功"))

      // 正控 ①：同一组 API 在 A 的根上确实看得见 A 的三面样本
      assertEquals(projectNames(), List("proj-alpha"), "正控：A 的根上看得见 A 的项目")
      assertEquals(secretNames(accountA), List("alpha-token"), "正控：A 的根上看得见 A 的 secret")
      assertEquals(ledgerIds(), List(alphaTaskId), "正控：A 的根上看得见 A 的任务")

      // ── 阶段 2：阶梯面 —— 指针切到 B ⇒ 有效根 = B 的账号根（不是 A）───────────────
      AccountRoot.writePointer(machineRoot, ActiveAccount("acct-b", "b@example.com", Ts))
      PathUtil.setMachineRoot(machineRoot)
      assertEquals(PathUtil.machineRoot, machineRoot, "机器层根不随账号切换而变")
      assertEquals(PathUtil.dataRoot, accountB, "指针 = B ⇒ 有效根必须是 B 的账号根（§2 第 1 档）")
      assert(PathUtil.dataRoot != accountA, "有效根绝不仍指向 A")
      RootLadder.resolve(machineRoot) match
        case RootLayer.Account(machineRootResolved, accountSub, effective) =>
          assertEquals(machineRootResolved, machineRoot)
          assertEquals(accountSub, "acct-b", "accountSub 保原文（不是目录名）")
          assertEquals(effective, accountB)
        case other => fail(s"指针 = B 时必须判 Account(acct-b)，实际：$other")

      // ── 阶段 3：切到 B —— 工具面按派单口径用 PathUtil.setDataRoot 换根后取数 ──────
      PathUtil.setDataRoot(accountB)

      // 证一：目录读取（B 的账号根里没有 A 的任何一项）
      assertEquals(dirEntries(accountB / "projects"), Nil, "目录证：B 的账号根下看不到 A 的 projects")
      assertEquals(dirEntries(accountB / "secrets"), Nil, "目录证：B 的账号根下看不到 A 的 secrets")
      assert(!os.exists(accountB / "tasks-v2.json"), "目录证：B 的账号根下不落 A 的任务账本")
      assertEquals(
        os.walk(accountB).filter(os.isFile).map(_.last).filter(_.contains("alpha")).toList,
        Nil,
        "目录证：B 全树无任何 alpha* 痕（任务账本 / secret / 项目定义全算）"
      )

      // 证二：工具面（同一 store/collect API，同一调用形态）
      assertEquals(projectNames(), Nil, "工具面：B 的根上读不到 A 的项目定义")
      assertEquals(secretNames(accountB), Nil, "工具面：B 的根上读不到 A 的 secret")
      assertEquals(ledgerIds(), Nil, "工具面：B 的根上读不到 A 的任务")

      // ── 阶段 4：正控 ②——B 自己的样本可见，且 A 的仍然不可见 ────────────────────
      writeProjectSample(accountB, "proj-beta")
      assertEquals(secretsStore(accountB).create("beta-token", "value-beta").unsafeRunSync(), Right(()))
      val betaTaskId = TaskLedgerStore
        .open()
        .createSyncReturningId(title = "beta-task")
        .getOrElse(fail("TaskLedgerStore.create：B 的 tasks 样本必须写入成功"))
      assertEquals(projectNames(), List("proj-beta"), "正控②：B 只看得见自己的项目")
      assertEquals(secretNames(accountB), List("beta-token"), "正控②：B 只看得见自己的 secret")
      assertEquals(ledgerIds(), List(betaTaskId), "正控②：B 只看得见自己的任务")

      // 换根不是搬数据、也不是删数据：A 的样本仍在 A 的账号根
      assert(os.exists(accountA / "projects" / "proj-alpha" / "project.json"), "A 的项目样本必须原地不动")
      assert(os.exists(accountA / "secrets" / "alpha-token"), "A 的 secret 必须原地不动")
      assertEquals(secretNames(accountA), List("alpha-token"), "A 的根上仍只看得见 A 的 secret")
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(machineRoot)

    end try

  test("V3: 账号根目录名映射不同（'|' 形态与可读形态）时同样隔离，且两个根互不为前缀"):
    val prev = PathUtil.dataRoot
    val machineRoot = tempRoot()
    try
      AccountRoot.writeLayoutMarker(machineRoot)
      val piped = AccountRoot.ensureAccountDir(machineRoot, "logto|user-42")
      val readable = AccountRoot.ensureAccountDir(machineRoot, "acct-plain")
      assertNotEquals(piped, readable, "两个账号根必须不同目录")
      assert(
        !readable.toString.startsWith(piped.toString + "/") && !piped.toString.startsWith(readable.toString + "/"),
        s"两个账号根不得互为前缀（否则一个账号的路径会落进另一个账号的树）：$piped vs $readable"
      )
      assertEquals(piped / os.up, readable / os.up, "两个账号根同属 machineRoot/accounts/")

      AccountRoot.writeProfile(piped, AccountProfile("logto|user-42", "u42@example.com", Ts, None))
      AccountRoot.writeProfile(readable, AccountProfile("acct-plain", "plain@example.com", Ts, None))
      AccountRoot.writePointer(machineRoot, ActiveAccount("logto|user-42", "u42@example.com", Ts))

      PathUtil.setDataRoot(piped)
      writeProjectSample(piped, "proj-42")
      assertEquals(projectNames(), List("proj-42"))

      AccountRoot.writePointer(machineRoot, ActiveAccount("acct-plain", "plain@example.com", Ts))
      PathUtil.setDataRoot(readable)
      assertEquals(projectNames(), Nil, "切到另一账号后工具面读不到前一账号的项目")
      assertEquals(dirEntries(readable / "projects"), Nil, "目录面同样为空")
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(machineRoot)
    end try

end AccountIsolationSpec
