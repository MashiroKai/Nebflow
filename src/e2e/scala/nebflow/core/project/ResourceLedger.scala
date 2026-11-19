package nebflow.core.project

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import nebflow.db.DbHost

/**
 * E2E fixture 统一资源账本（四类：db / port / temp_dir / handle）。
 *
 * 登记纪律——开点登记、关点注销，且注销只发生在关闭被**证实**之后：
 *  - db：权威登记面是 [[DbHost]] 句柄注册表（open 登记、release 在 close 成功后
 *    注销；close 抛错 ⇒ 不注销，清点如实非零）。快照按本 fixture 数据根查询，
 *    不在本账本重复记账。
 *  - handle：权威登记面是 NodeStarter 的 detachedStarts 屏障（forkStart 登记、
 *    fiber 终态后注销）；快照计数由调用方在屏障排空后传入。
 *  - port / temp_dir：由 fixture 在开点（bind/listen 成功、目录创建）显式登记，
 *    在关点（close 证实、递归删除 + exists=false 证实）显式注销。
 *
 * E01 fixture 不打开监听 socket（ActorSystem 无网络面），port 类目保持 0；
 * adapter 侧探针 listener 以同法登记注销（见 plugin 树 isolated_harness.py）。
 * 已知边界：forkStart 之外的未知后台 fiber 不在本账本面内（不强证零）。
 */
object ResourceLedger:

  private val ports = Ref.unsafe[IO, Set[Int]](Set.empty)
  private val tempDirs = Ref.unsafe[IO, Set[String]](Set.empty)

  /** 监听 socket 开点登记（bind/listen 成功后调用）。 */
  def registerPort(port: Int): IO[Unit] = ports.update(_ + port)

  /** 监听 socket 关点注销（close 证实后调用；未证实不注销）。 */
  def deregisterPort(port: Int): IO[Unit] = ports.update(_ - port)

  /** 临时目录创建点登记。 */
  def registerTempDir(path: String): IO[Unit] = tempDirs.update(_ + path)

  /** 临时目录关点注销（递归删除 + exists=false 证实后调用）。 */
  def deregisterTempDir(path: String): IO[Unit] = tempDirs.update(_ - path)

  /** 退出时四类计数：db/handle 读权威登记面，port/temp_dir 读本账本。 */
  def snapshot(dbRoot: java.nio.file.Path, handlesRemaining: Int): IO[Json] =
    for
      openPorts <- ports.get
      dirs <- tempDirs.get
    yield Json.obj(
      "db" -> (if DbHost.isOpen(dbRoot) then 1 else 0).asJson,
      "port" -> openPorts.size.asJson,
      "temp_dir" -> dirs.size.asJson,
      "handle" -> handlesRemaining.asJson
    )

end ResourceLedger
