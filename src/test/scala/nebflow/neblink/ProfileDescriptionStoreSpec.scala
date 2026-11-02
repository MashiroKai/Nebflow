package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.{FriendListResponse, FriendSummary, GroupSummary, PathUtil}

import java.nio.file.Files

/**
 * 「双描述」本地持久化 + 合并语义单测（件一，2026-11-02；作者令「联系人和群要有微信式
 * Profile，两个描述 = 用户给自己写的 + 我们给好友备注的」）。
 *
 * 钉死五件（对应定案契约 `ProfileDescriptions` 头注的表）：
 *
 *  D1 **`def path` 而非 `val`**（f1cd3709 规则）：`PathUtil.setDataRoot` 重定向后路径
 *     必须随之改变（`val` 会在 object-init 冻结路径 ⇒ per-test data root 写进真实
 *     `~/.stand`）。机制反证 = 换根后读不到旧根值 + 旧根文件不被新根写入污染。
 *  D2 `load` / `save` 往返 + 落点 = `<dataRoot>/profile-descriptions.json`（四键 JSON
 *     对象），文件缺席不抛错（首次启动）。
 *  D3 四个 setter 的写语义：**trim**；trim 后空串 = **清除（删键）**；改 Ref 与落盘
 *     同一动作（重启等价路径 load → Ref 立刻可见）；未知 id 照存（纯本地态，零上游校验）。
 *  D4 **两字段互不覆盖**（作者原话的机械落点）：写 A 不回读改 B；四段（friendBio /
 *     groupRemark / groupDescription / selfBio）彼此独立。
 *  D5 **合并优先级**（`FriendService.applyFriendBio` / `applyGroupDescriptions`）：
 *     好友 `bio` 与群 `description` 语义真源在上游 ⇒ 上游在场**上游赢**、上游缺席回落
 *     本地缓存；群 `remark` 与好友 `remark` **恒本地**（上游值不得穿透）。
 *     这与「本地缓存·未同步」的 UI 标注是同一条事实的两面。
 *
 * 隔离：每个用例独占临时 dataRoot（`FriendRemarkStoreSpec` 同形），绝不碰真实 `~/.stand`。
 */
class ProfileDescriptionStoreSpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("profile-desc-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  private def descFile: os.Path = PathUtil.dataRoot / "profile-descriptions.json"

  /** 描述写入零上游往返——故意 `IO.pure(None)`（未登录）证明这点。 */
  private def service(
    initial: ProfileDescriptions = ProfileDescriptions.empty,
    client: Option[NeblinkClient] = None
  ): FriendService =
    new FriendService(
      IO.pure(client),
      AgentMessagingConfig(),
      descriptionsRef = Ref.unsafe[IO, ProfileDescriptions](initial)
    )

  // ── D2 ────────────────────────────────────────────────────────────────

  test("D2 load on a fresh home → empty（文件缺席不抛错）") {
    assert(!os.exists(descFile))
    ProfileDescriptionStore.load.map(d => assertEquals(d, ProfileDescriptions.empty))
  }

  test("D2 save → load 往返：四键 JSON 对象落在 <dataRoot>/profile-descriptions.json") {
    val data = ProfileDescriptions(
      friendBio = Map("u1" -> "我是老林，做后端"),
      groupRemark = Map("g1" -> "球局群"),
      groupDescription = Map("g1" -> "每周六晚八点"),
      selfBio = "我在写 Nebflow"
    )
    for
      _ <- ProfileDescriptionStore.save(data)
      onDisk <- IO.blocking(os.read(descFile))
      back <- ProfileDescriptionStore.load
    yield
      assertEquals(back, data)
      for key <- List("friendBio", "groupRemark", "groupDescription", "selfBio") do
        assert(onDisk.contains(s"\"$key\""), s"落盘必须是四键 JSON 对象（缺 $key）：$onDisk")
  }

  test("D2 逐键缺省：只带一段的旧文件不得整份失败（其余段取缺省）") {
    for
      _ <- IO.blocking(os.write.over(descFile, """{"groupRemark":{"g1":"球局群"}}""", createFolders = true))
      loaded <- ProfileDescriptionStore.load
    yield assertEquals(
      loaded,
      ProfileDescriptions(groupRemark = Map("g1" -> "球局群")),
      "手写 Decoder 逐键 getOrElse：缺段用缺省，禁整份解密失败导致四段全丢"
    )
  }

  // ── D1 ────────────────────────────────────────────────────────────────

  test("D1 def path：dataRoot 重定向后新旧根各持己值（val 会冻结路径 ⇒ 本用例必红）") {
    val rootA = PathUtil.dataRoot
    for
      _ <- ProfileDescriptionStore.save(ProfileDescriptions(selfBio = "A 根介绍"))
      aHasIt <- IO.blocking(os.exists(rootA / "profile-descriptions.json"))
      rootB = os.Path(Files.createTempDirectory("profile-desc-spec-b"), os.pwd)
      _ <- IO.blocking(PathUtil.setDataRoot(rootB))
      emptyOnB <- ProfileDescriptionStore.load
      _ <- ProfileDescriptionStore.save(ProfileDescriptions(selfBio = "B 根介绍"))
      bFile <- IO.blocking(os.exists(rootB / "profile-descriptions.json"))
      aStill <- IO.blocking(os.read(rootA / "profile-descriptions.json"))
      _ <- IO.blocking(PathUtil.setDataRoot(rootA))
      aReloaded <- ProfileDescriptionStore.load
      _ <- IO.blocking(os.remove.all(rootB))
    yield
      assert(aHasIt, "首次 save 必须落在 root A")
      assertEquals(emptyOnB, ProfileDescriptions.empty, "换根后不得读到 A 根值（路径随 dataRoot 走）")
      assert(bFile, "第二次 save 必须落在 root B（若 path 是 val，会写回 A）")
      assert(
        aStill.contains("A 根介绍") && !aStill.contains("B 根介绍"),
        s"B 根写入不得污染 A 根文件：$aStill"
      )
      assertEquals(aReloaded, ProfileDescriptions(selfBio = "A 根介绍"), "回到 A 根仍读到 A 根值")
  }

  // ── D3 / D4 写语义 + 互不覆盖 ─────────────────────────────────────────

  test("D3 四个 setter：trim 后入库并同步落盘（改 Ref 与 save 同一动作）") {
    val fs = service()
    for
      _ <- fs.setFriendBio("u1", "  我是老林  ")
      _ <- fs.setGroupRemark("g1", "  球局群  ")
      _ <- fs.setGroupDescription("g1", "  每周六晚八点  ")
      _ <- fs.setSelfBio("  我在写 Nebflow  ")
      snap <- fs.descriptions
      onDisk <- ProfileDescriptionStore.load
    yield
      val expected = ProfileDescriptions(
        friendBio = Map("u1" -> "我是老林"),
        groupRemark = Map("g1" -> "球局群"),
        groupDescription = Map("g1" -> "每周六晚八点"),
        selfBio = "我在写 Nebflow"
      )
      assertEquals(snap, expected, "前后空白必须被 trim（前端显示与搜索匹配都按 trim 后值）")
      assertEquals(onDisk, expected, "Ref 与磁盘必须一致（重启后描述不丢）")
  }

  test("D3 trim 后空串 = 清除（删键而非存空串），且清除同样落盘") {
    val fs = service(
      ProfileDescriptions(
        friendBio = Map("u1" -> "我是老林"),
        groupRemark = Map("g1" -> "球局群"),
        groupDescription = Map("g1" -> "每周六晚八点"),
        selfBio = "我在写 Nebflow"
      )
    )
    for
      _ <- fs.setFriendBio("u1", "   ")
      _ <- fs.setGroupRemark("g1", "")
      _ <- fs.setGroupDescription("g1", " ")
      _ <- fs.setSelfBio("  ")
      snap <- fs.descriptions
      onDisk <- ProfileDescriptionStore.load
    yield
      assertEquals(snap, ProfileDescriptions.empty, s"空串即清除：四个键都必须消失，不留空串值：$snap")
      assertEquals(onDisk, ProfileDescriptions.empty, "清除必须落盘（否则重启后描述复活）")
  }

  test("D4 两字段互不覆盖：改 A 不影响 B（写 → 读的唯一判据）") {
    val fs = service()
    for
      _ <- fs.setFriendBio("u1", "自我介绍甲")
      afterBio <- fs.descriptions
      // 再写同主体的另一字段（好友 remark 走既有 FriendRemarkStore，此处以群双字段对证）
      _ <- fs.setGroupRemark("g1", "我的群备注乙")
      afterRemark <- fs.descriptions
      _ <- fs.setGroupDescription("g1", "群介绍丙")
      afterDesc <- fs.descriptions
      // 覆写其一：另一个必须逐字不动
      _ <- fs.setGroupRemark("g1", "我的群备注乙-改")
      afterRemarkEdit <- fs.descriptions
      disk <- ProfileDescriptionStore.load
    yield
      assertEquals(afterBio.friendBio, Map("u1" -> "自我介绍甲"))
      assertEquals(afterBio.groupRemark, Map.empty[String, String], "写 friendBio 不得写 groupRemark")
      assertEquals(afterRemark.friendBio, Map("u1" -> "自我介绍甲"), "写 groupRemark 不得动 friendBio")
      assertEquals(afterRemark.groupDescription, Map.empty[String, String], "写 groupRemark 不得动 groupDescription")
      assertEquals(
        afterDesc.groupRemark,
        Map("g1" -> "我的群备注乙"),
        "写 groupDescription 不得动 groupRemark（两个独立字段、两个独立写面）"
      )
      assertEquals(afterDesc.groupDescription, Map("g1" -> "群介绍丙"))
      assertEquals(
        afterRemarkEdit.groupDescription,
        Map("g1" -> "群介绍丙"),
        "改其一不得连带覆盖另一个（作者原话的机械落点）"
      )
      assertEquals(afterRemarkEdit.groupRemark, Map("g1" -> "我的群备注乙-改"))
      assertEquals(disk, afterRemarkEdit, "每步都落盘：磁盘 = 最新 Ref")
  }

  test("D3 未知 id 照存（纯本地态：零上游校验，不因好友/群列表此刻读不到而丢用户输入）") {
    val fs = service()
    for
      _ <- fs.setFriendBio("u-not-in-roster", "陌生好友介绍")
      _ <- fs.setGroupDescription("g-not-in-roster", "陌生群介绍")
      snap <- fs.descriptions
    yield
      assertEquals(snap.friendBio, Map("u-not-in-roster" -> "陌生好友介绍"))
      assertEquals(snap.groupDescription, Map("g-not-in-roster" -> "陌生群介绍"))
  }

  test("D3 重启等价路径：启动期 load → Ref 后四个字段立刻可用") {
    val a = service()
    for
      _ <- a.setGroupDescription("g1", "每周六晚八点")
      seeded <- ProfileDescriptionStore.load
      b = service(seeded)
      seenByB <- b.descriptions
    yield assertEquals(seenByB.groupDescription, Map("g1" -> "每周六晚八点"))
  }

  // ── D5 合并优先级（上游真源 vs 本地缓存）────────────────────────────────

  /** 上游桩：好友 u-up 自带 bio，u-cache 不带；群 g-up 自带 description，g-cache 不带；
   *  上游**故意**给两个群带 remark 值（`GroupSummary.remark`），用来证明群备注恒取本地。 */
  private val upstreamFriends = FriendListResponse(
    friends = List(
      FriendSummary("u-up", "up", "上游有介绍", bio = Some("  上游自我介绍  ")),
      FriendSummary("u-cache", "cache", "只有本地缓存"),
      FriendSummary("u-none", "none", "两侧都没有")
    )
  )

  private val upstreamGroups = List(
    GroupSummary("g-up", "上游介绍群", description = Some("  上游群介绍  ")),
    GroupSummary("g-cache", "本地介绍群"),
    // 上游若将来带上 remark，本仓仍必须以本地为准（remark 的真源在本地）
    GroupSummary("g-upstream-remark", "上游带备注的群", remark = Some("上游塞进来的备注"))
  )

  private def upstreamClient: NeblinkClient =
    new NeblinkClient(
      NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "n", secret = "s"),
      serverPort = 1
    ):
      override def listFriends: IO[Either[String, FriendListResponse]] =
        IO.pure(Right(upstreamFriends))
      override def listGroups: IO[Either[String, List[GroupSummary]]] =
        IO.pure(Right(upstreamGroups))

  test("D5 好友 bio：上游在场 ⇒ 上游赢（trim 后）；上游缺席 ⇒ 回落本地缓存；两侧空 ⇒ None") {
    val fs = service(
      ProfileDescriptions(friendBio = Map("u-up" -> "本地缓存（应被上游覆盖）", "u-cache" -> "本地缓存介绍")),
      client = Some(upstreamClient)
    )
    for
      out <- fs.listFriends
      bios <- IO.delay(out.map(_.friends.map(f => f.userId -> f.bio).toMap))
    yield
      val m = bios.getOrElse(fail("listFriends must succeed"))
      assertEquals(m("u-up"), Some("上游自我介绍"), "bio 的语义真源在上游：上游在场时本地缓存不得覆盖")
      assertEquals(m("u-cache"), Some("本地缓存介绍"), "上游缺席 ⇒ 回落本地缓存（「本地缓存·未同步」的那一份）")
      assertEquals(m("u-none"), None, "两侧都空 ⇒ None（不写空串，前端无需容错第二种形态）")
  }

  test("D5 群双描述：description 上游优先 / 本地兜底；remark 恒取本地（上游值不得穿透）") {
    val fs = service(
      ProfileDescriptions(
        groupDescription = Map("g-up" -> "本地缓存（应被上游覆盖）", "g-cache" -> "本地群介绍"),
        groupRemark = Map("g-cache" -> "我的群备注")
      ),
      client = Some(upstreamClient)
    )
    for
      out <- fs.listGroups
      rows <- IO.delay(out.map(gs => gs.map(g => g.groupId -> g).toMap))
    yield
      val m = rows.getOrElse(fail("listGroups must succeed"))
      assertEquals(m("g-up").description, Some("上游群介绍"), "群介绍真源在上游群管理：上游在场时上游赢")
      assertEquals(m("g-cache").description, Some("本地群介绍"), "上游缺席 ⇒ 本地缓存兜底")
      assertEquals(
        m("g-cache").remark,
        Some("我的群备注"),
        "我的群备注是纯本地真源：上游即便带了 remark 也必须被本地值取代"
      )
      assertEquals(m("g-up").remark, None, "无本地备注 ⇒ None（不写空串）")
      assertEquals(
        m("g-upstream-remark").remark,
        None,
        "上游带了 remark 也不得穿透：我的备注真源在本仓（上游值被本仓本地值取代，无本地值即 None）"
      )
  }

  test("D5 好友 remark 走既有 FriendRemarkStore 同一出口：与 bio 同批合并、互不覆盖") {
    val fs = new FriendService(
      IO.pure(Some(upstreamClient)),
      AgentMessagingConfig(),
      remarkRef = Ref.unsafe[IO, Map[String, String]](Map("u-cache" -> "我给对方的备注")),
      descriptionsRef = Ref.unsafe[IO, ProfileDescriptions](
        ProfileDescriptions(friendBio = Map("u-cache" -> "对方自我介绍"))
      )
    )
    for
      out <- fs.listFriends
      rows <- IO.delay(out.map(_.friends.map(f => f.userId -> f).toMap))
      _ <- fs.setFriendBio("u-cache", "自我介绍-改")
      after <- fs.listFriends
      afterRows <- IO.delay(after.map(_.friends.map(f => f.userId -> f).toMap))
    yield
      val before = rows.getOrElse(fail("listFriends must succeed"))
      assertEquals(before("u-cache").remark, Some("我给对方的备注"))
      assertEquals(before("u-cache").bio, Some("对方自我介绍"))
      val updated = afterRows.getOrElse(fail("listFriends must succeed"))
      assertEquals(updated("u-cache").bio, Some("自我介绍-改"), "改 bio 后回读一致")
      assertEquals(updated("u-cache").remark, Some("我给对方的备注"), "改 bio 不得覆盖 remark")
  }

end ProfileDescriptionStoreSpec
