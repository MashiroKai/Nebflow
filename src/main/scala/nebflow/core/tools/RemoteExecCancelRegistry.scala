package nebflow.core.tools

import cats.effect.IO
import nebflow.core.util.ProcessTree

import java.util.concurrent.ConcurrentHashMap

/**
 * 对端 remote-exec 进程的 **jobId → OS 进程** 登记表（后台任务生命周 P2 批 ·
 * 单任务取消的远端腿）。
 *
 * == 为什么需要它 ==
 *
 * host 经设备通道下发的后台 Bash 在**对端**以 remote-exec **前台**形态执行
 * （`stripLocalBgFlags` 摘除本侧生命周期旗标——对端不识别 host 的后台语义），
 * 对端因此没有对应的 BgTaskRegistry 行 / backgroundJobs 项，host 侧 jobId 在
 * 对端无处可寻。精确取消（N 条杀 1 条）要求对端持有「jobId → 进程」的映射：
 * host 在下发参数里带 `bgJobId`（派发时生成、全局唯一），对端
 * `ShellSession.runProcess` 见 `execKey` 即把进程登记进本表；host 取消时经
 * `bg-cancel` 动作（remote-exec 通道的扩展动作，与 FileTransfer/Notify 同表
 * 同安全面）携 jobId 下发，对端 [[nebflow.neblink.BgCancelAction]] 查表杀树。
 *
 * == 语义 ==
 *
 *  - `register`/`unregister`：进程生命周期括号（runProcess bracket use/release
 *    对称点）；重复 register 以新代旧（同名键理论上唯一，防御性覆盖）。
 *  - `cancel`：**取走式**（ConcurrentHashMap.remove 原子判空）——同一 jobId
 *    的并发取消只有一个赢家，输家拿到 false（天然幂等，不重杀）。杀树用既有
 *    [[ProcessTree.killProcessTree]]（硬化杀：SIGTERM→SIGKILL 全树，含重定父母
 *    的孤儿）；进程已自然退出时杀树为幂等 no-op，返回 true（取消请求已尽义）。
 *  - 进程内存态（对端重启即清）——host 侧取消发现对端「not found」时按
 *    「对端进程已不在（版本偏斜/重启/已退出）」如实回执，host 本地终态照收
 *    （first-terminal-wins 由行取走判据承担，见 BgTaskCancel）。
 *
 * 安全：本表只被对端自己的 remote-exec 执行面与 BgCancelAction 触碰；
 * `bg-cancel` 动作走 remote-exec 已鉴权通道（verifyPeerAccess / relay 隧道
 * 会话鉴权——与下发 Bash 的通道**同一条**，杜绝「能下发不能取消」的权限缝）。
 */
object RemoteExecCancelRegistry:

  private val procs = new ConcurrentHashMap[String, Process]()

  /** 登记（幂等覆盖；空键 no-op）。 */
  def register(key: String, p: Process): IO[Unit] =
    IO.whenA(key != null && key.nonEmpty)(IO(procs.put(key, p)).void)

  /** 注销（bracket release 对称点；空键 no-op）。 */
  def unregister(key: String): IO[Unit] =
    IO.whenA(key != null && key.nonEmpty)(IO(procs.remove(key)).void)

  /** 在读探针（death-watch/测试断言面）：键在册且进程可验证存活。 */
  def isLive(key: String): IO[Boolean] =
    IO {
      val p = procs.get(key)
      p != null && p.isAlive
    }

  /**
   * 取走式取消：键不在册 ⇒ false（已取消/未登记/已退出）；在册 ⇒ 杀整树后
   * true。杀树异常只 WARN（簿记面纪律——取消语义由「不在册」表达）。
   */
  def cancel(key: String): IO[Boolean] =
    IO(Option(procs.remove(key))).flatMap {
      case None => IO.pure(false)
      case Some(p) =>
        ProcessTree.killProcessTree(p).attempt.flatMap {
          case Right(_) => IO.pure(true)
          case Left(e) =>
            nebflow.shared.NebflowLogger
              .forName("nebflow.remote-exec-cancel")
              .warn(s"killProcessTree for remote-exec key '$key' failed: ${e.getMessage}")
              .as(true)
        }
    }

  /** 表项数（spec 断言面）。 */
  private[tools] def size: IO[Int] = IO(procs.size())

  /** 清空（spec 固件用——neblink 包 BgCancelActionSpec 跨包访问；正常路径绝不调用——零删除纪律）。 */
  private[nebflow] def clear(): IO[Unit] = IO(procs.clear()).void
