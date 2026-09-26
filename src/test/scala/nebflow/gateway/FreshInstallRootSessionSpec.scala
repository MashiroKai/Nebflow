package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * fresh-install 交付窗「装完首次会话号非空」断言（方案件 §4.1 L1，纯 spec、零实例）。
 *
 * 守卫的判据（本批红线）：`rootSessionId` **只有在非空时才可作为键/归属根出现**。
 * 启动挂载根（`GatewayMain` 的 `startupMount`）自本批起 = 「开机补建」的产物
 * （`ensureActiveAgentSession("Nebula")` 的返回值），不再现查索引 + `getOrElse("")`。
 * 本 spec 钉住该产物的**非空性**与「它确实被设为 activeId」——本批前测试面对此
 * **零守卫**（全仓 96 个含 `rootSessionId` 的 spec 无一断言其非空）。
 *
 * 变异锚（方案件 §4.3 V1）：把补建腿换掉（`ensureActiveAgentSession` → `IO.unit`）
 * 或把挂载根改回 `getOrElse("")`，本 spec 的 `meta.id.nonEmpty` 必须红；
 * 第二个 test 是**正控**——即使 V1 变异下它仍须绿（证明 harness 真的跑到了
 * fresh-install 态：`_index.json` 已落盘且恰一个 Nebula 会话），从而排除
 * 「编译时序假象/空跑」造成的假红假绿。
 *
 * 数据根隔离：`PathUtil.setDataRoot` 是全局态 ⇒ 本 spec 用 `withFreshHome`
 * 重定向到临时目录，`guarantee` 里恢复原值并清盘（与既有 spec 同款）。
 */
class FreshInstallRootSessionSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private val UuidShape =
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  /** Redirect the global dataRoot to an empty temp dir for the duration of `f`. */
  private def withFreshHome[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-freshinstall-rootsession-")
    IO(PathUtil.setDataRoot(tmp)) *>
      f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  test("fresh install: boot backfill yields a NON-EMPTY Nebula session id (first-session root non-empty)") {
    withFreshHome { home =>
      val store = new SessionStore(home / "sessions", home / "tasks")
      for
        _ <- store.load
        meta <- store.ensureActiveAgentSession("Nebula")
        activeId <- store.getActiveId
        sessions <- store.listSessionsByAgent("Nebula")
      yield
        assert(
          meta.id.nonEmpty,
          "boot backfill (ensureActiveAgentSession(\"Nebula\")) returned an EMPTY session id — " +
            "the startup mount root would be empty (this is the V1 red anchor)"
        )
        assert(UuidShape.matches(meta.id), s"session id must be a UUID, got '${meta.id}'")
        assertEquals(activeId, meta.id, "the backfilled session must also be the active session")
        // 启动挂载根（M1：bootRoot.id）与旧的现查取法都不允许为空串
        assertEquals(sessions.headOption.map(_.id), Some(meta.id))
        assert(
          sessions.headOption.map(_.id).getOrElse("").nonEmpty,
          "startup mount root must be non-empty"
        )
      end for
    }
  }

  test("control: the harness really reached the fresh-install state (_index.json with exactly one Nebula session)") {
    withFreshHome { home =>
      val store = new SessionStore(home / "sessions", home / "tasks")
      for
        _ <- store.load
        _ <- store.ensureActiveAgentSession("Nebula")
        indexFile <- IO(home / "sessions" / "_index.json")
        raw <- IO(os.read(indexFile))
        sessions <- store.listSessionsByAgent("Nebula")
      yield
        assert(os.exists(indexFile), "_index.json must be written on a fresh home")
        assert(
          raw.contains("\"activeId\""),
          s"_index.json must carry the activeId key (this is the positive control): $raw"
        )
        assertEquals(sessions.size, 1, "exactly one Nebula session after the backfill")
    }
  }
end FreshInstallRootSessionSpec
