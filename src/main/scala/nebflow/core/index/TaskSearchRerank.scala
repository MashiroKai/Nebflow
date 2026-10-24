package nebflow.core.index

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

/**
 * TaskSearch 重排注册器端口（task-rag 批 #1227 / §Batch 7「可选 Jev 重排」）。
 *
 * 分层纪律（core 零 `nebflow.llm` import——`core/jev/JevAllocatorPort` 与
 * `core/delegate/DelegateJudge.DelegateJudgePort` 同款）：core 只见本注册器；
 * 执行体在 `nebflow.llm.decision.JevTaskSearchRerank`；注册点 =
 * `agent.SharedResources` 构造行（DelegateJudgePort 先例）。
 *
 * ==缺省语义＝跳过重排（原文「可选」的落地）==
 * 未注册时 `rerank` = `IO.pure(None)`（无害 no-op 兜底，同 DelegateJudgePort
 * 「判定机缺席」形态）；执行体返回 `None`（Jev 未配 / 判定失败 / 接口不合）
 * 同样是跳过——检索照常按余弦/词法序返回。重排是**纯序换**：绝不增删命中
 * （返回列表必须是候选 id 的排列），消费点对 `None` 与异常一律保持原序。
 */
object TaskSearchRerank:

  /** 一条候选：`entryId` + 展示文本（title/snippet 合成，供重排面判读）。 */
  final case class Candidate(id: String, text: String)

  trait Face:
    /**
     * Reorder the candidates by relevance to the query.
     *
     * @return
     *   `Some(ordering)` = a PERMUTATION of the candidate ids (never a
     *   subset — reranking must not drop hits); `None` = skip reranking
     *   (face unavailable / degraded — the caller keeps the incoming order).
     */
    def rerank(query: String, candidates: List[Candidate]): IO[Option[List[String]]]
  end Face

  private val face = new AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))

  def clear(): Unit = face.set(None)

  def isInstalled: Boolean = face.get.isDefined

  /** 未注册/执行异常 → `None`（跳过重排，照常返回）——绝不抛出检索面。 */
  def rerank(query: String, candidates: List[Candidate]): IO[Option[List[String]]] =
    face.get match
      case None => IO.pure(None)
      case Some(f) =>
        f.rerank(query, candidates).handleErrorWith { e =>
          IO(
            nebflow.shared.NebflowLogger
              .forName("nebflow.index.rerank")
              .warnSync(s"task search rerank failed (${e.getClass.getSimpleName}) — skipped, keeping incoming order")
          ).as(None)
        }

end TaskSearchRerank
