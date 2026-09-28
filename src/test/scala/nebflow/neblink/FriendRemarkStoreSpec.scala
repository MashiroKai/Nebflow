package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.PathUtil

import java.nio.file.Files

/**
 * 好友备注存储 + 写入语义单测（2026-09-12 好友消息改造批 ⑦；方案
 * `20260912_011320` §4.3(a)1/5）。
 *
 * 钉死四件：
 *  1. **`def path` 而非 `val`**（f1cd3709 规则）：路径必须随 `PathUtil.setDataRoot`
 *     重定向 —— `val` 会在 object-init 冻结路径，per-test data root 会写进真实
 *     `~/.nebflow`（本 spec 的隔离断言 + 「重定向后旧根文件不变」就是这条的机制反证）。
 *  2. `load` / `save` 往返（`Map[String,String]`，键 = friend `userId`）。
 *  3. `setRemark`：**trim**；**trim 后空串 = 清除（删键）**；未知 `userId` 也照存
 *     （备注是纯本地态，**零上游校验/零上游往返** ⇒ 无 502 面，也不因上游好友列表
 *     此刻读不到而丢用户输入）。
 *  4. 持久化落点 = `<dataRoot>/friend-remarks.json`，改 Ref 与落盘同步完成。
 *
 * 隔离：每个用例独占临时 dataRoot（复用 `FriendApiRoutesSpec` 的 before/after 形态），
 * 绝不碰真实 `~/.nebflow`。
 */
class FriendRemarkStoreSpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("friend-remark-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def remarksFile: os.Path = PathUtil.dataRoot / "friend-remarks.json"

  /** 备注写入不需要 client（零上游）——故意用 `IO.pure(None)`（未登录）证明这点。 */
  private def service(initial: Map[String, String] = Map.empty): FriendService =
    new FriendService(
      IO.pure(None),
      AgentMessagingConfig(),
      remarkRef = Ref.unsafe[IO, Map[String, String]](initial)
    )

  test("load on a fresh home → empty map（文件缺席不抛错）") {
    assert(!os.exists(remarksFile))
    FriendRemarkStore.load.map(m => assertEquals(m, Map.empty[String, String]))
  }

  test("save → load 往返：形态 Map[userId → remark]，落盘在 <dataRoot>/friend-remarks.json") {
    val data = Map("u1" -> "老林", "u2" -> "王选(a.k.a. 学长)")
    for
      _ <- FriendRemarkStore.save(data)
      onDisk <- IO.blocking(os.read(remarksFile))
      back <- FriendRemarkStore.load
    yield
      assertEquals(back, data)
      // 落点形态 = JSON 对象（键 = userId），非数组/非包裹层
      assert(onDisk.contains("\"u1\""), s"file must be a userId-keyed JSON object, got: $onDisk")
      assert(os.exists(remarksFile), "path must be <dataRoot>/friend-remarks.json")
  }

  test("def path：dataRoot 重定向后新旧根各持己值（val 会冻结路径 ⇒ 本用例必红）") {
    val rootA = PathUtil.dataRoot
    for
      _ <- FriendRemarkStore.save(Map("u1" -> "A 根备注"))
      aHasIt <- IO.blocking(os.exists(rootA / "friend-remarks.json"))
      // 切到第二个隔离根
      rootB = os.Path(Files.createTempDirectory("friend-remark-spec-b"), os.pwd)
      _ <- IO.blocking(PathUtil.setDataRoot(rootB))
      emptyOnB <- FriendRemarkStore.load
      _ <- FriendRemarkStore.save(Map("u2" -> "B 根备注"))
      bFile <- IO.blocking(os.exists(rootB / "friend-remarks.json"))
      aStill <- IO.blocking(os.read(rootA / "friend-remarks.json"))
      backToA <- IO.blocking(PathUtil.setDataRoot(rootA))
      aReloaded <- FriendRemarkStore.load
      _ <- IO.blocking(os.remove.all(rootB)) // 自建第二隔离根，用完即清（不依赖进程退出）
    yield
      assert(aHasIt, "首次 save 必须落在 root A")
      assertEquals(emptyOnB, Map.empty[String, String], "换根后不得读到 A 根的备注（路径随 dataRoot 走）")
      assert(bFile, "第二次 save 必须落在 root B（若 path 是 val，会写回 A）")
      assert(aStill.contains("A 根备注") && !aStill.contains("B 根备注"),
        s"B 根写入不得污染 A 根文件，got: $aStill")
      assertEquals(aReloaded, Map("u1" -> "A 根备注"), "回到 A 根仍读到 A 根值")
  }

  test("setRemark：trim 后入库，并同步落盘（改 Ref 与 save 同一动作）") {
    val fs = service()
    for
      _ <- fs.setRemark("u1", "  老林  ")
      snap <- fs.remarks
      onDisk <- FriendRemarkStore.load
    yield
      assertEquals(snap, Map("u1" -> "老林"), "前后空白必须被 trim（前端显示与 L0 匹配键都按 trim 后值）")
      assertEquals(onDisk, Map("u1" -> "老林"), "Ref 与磁盘必须一致（重启后备注不丢）")
  }

  test("setRemark：trim 后空串 = 清除（删键，而非存空串）；未知 userId 照存不报错") {
    val fs = service(Map("u1" -> "老林"))
    for
      _ <- fs.setRemark("u1", "   ")
      afterClear <- fs.remarks
      diskAfterClear <- FriendRemarkStore.load
      _ <- fs.setRemark("u-not-in-roster", "陌生键")
      afterUnknown <- fs.remarks
      diskAfterUnknown <- FriendRemarkStore.load
    yield
      assertEquals(afterClear, Map.empty[String, String], "空串即清除：键必须消失（不留 \"\" 值）")
      assertEquals(diskAfterClear, Map.empty[String, String], "清除必须落盘（否则重启后备注复活）")
      assertEquals(afterUnknown, Map("u-not-in-roster" -> "陌生键"),
        "未知 userId 照存：备注写入零上游校验（不因好友列表读不到而丢用户输入）")
      assertEquals(diskAfterUnknown, afterUnknown)
  }

  test("两实例共享同一持久层：A 写 → B（新装配，同 dataRoot）加载即见") {
    val a = service()
    for
      _ <- a.setRemark("u1", "老林")
      b = service(FriendRemarkStore.load.unsafeRunSync()) // 启动装配形态（load → Ref）
      seenByB <- b.remarks
    yield assertEquals(seenByB, Map("u1" -> "老林"),
      "重启等价路径：启动期 load → Ref 后，备注（含 L0 匹配键）立刻可用")
  }

end FriendRemarkStoreSpec
