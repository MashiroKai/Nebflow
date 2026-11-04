package nebflow.pilogic

import cats.effect.Sync
import cats.effect.kernel.Ref
import cats.syntax.all.*

/** 序域（设计 §Ⅱ.1）：TurnNumber / MessageSeq 单调值对象，从 1 起。
  * 分配器由循环本体（单写者）持有——不暴露自增 API 给调用方，调用方只读；
  * 每 run 重置、run 内单调（L-8 采纳推荐档）。
  */
final case class TurnNumber(value: Long) extends AnyVal

final case class MessageSeq(value: Long) extends AnyVal

object Sequence:

  /** 单写者单调分配器（run-local；从 1 起）。
    * Ref 经 `Ref.of` 安全构造——模块内禁 `Ref.unsafe`（验收 A-4 grep 门）。 */
  final class Allocator[F[_]] private[Sequence] (ref: Ref[F, Long]):
    def next: F[Long] = ref.modify(n => (n + 1L, n + 1L))
    def peek: F[Long] = ref.get

  def allocator[F[_]: Sync]: F[Allocator[F]] =
    Ref.of[F, Long](0L).map(new Allocator(_))
