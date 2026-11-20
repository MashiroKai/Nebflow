package nebflow.neblink.social

import cats.effect.IO
import cats.syntax.all.*
import nebflow.neblink.core.MessageSummary

import scala.concurrent.duration.*

/** Bounded, explicitly partial social search; never stores message bodies on disk. */
private[nebflow] object SocialSearchScan:
  final case class Hit(conversationId: String, message: MessageSummary)
  final case class Result(hits: List[Hit], pages: Int, truncated: Boolean, failed: Boolean)
  private val PageSize = 200
  private val MaxPagesPerConversation = 10
  private val MaxConversations = 24
  private val Concurrency = 6

  def search(
    ids: List[String],
    query: String,
    fetch: (String, Long, Int) => IO[Either[String, List[MessageSummary]]]
  ): IO[Result] =
    val needle = query.toLowerCase(java.util.Locale.ROOT)
    def scan(id: String, after: Long, pages: Int): IO[Result] =
      if pages >= MaxPagesPerConversation then IO.pure(Result(Nil, pages, true, false))
      else
        fetch(id, after, PageSize).flatMap {
          case Left(_) => IO.pure(Result(Nil, pages + 1, true, true))
          case Right(msgs) =>
            val hits = msgs.filter(m => m.body.toLowerCase(java.util.Locale.ROOT).contains(needle)).map(Hit(id, _))
            if hits.nonEmpty then IO.pure(Result(hits, pages + 1, true, false))
            else if msgs.size < PageSize then IO.pure(Result(Nil, pages + 1, false, false))
            else if msgs.last.id <= after then IO.pure(Result(Nil, pages + 1, true, true))
            else scan(id, msgs.last.id, pages + 1)
        }
    val selected = ids.distinct.take(MaxConversations)
    selected
      .grouped(Concurrency)
      .toList
      .traverse { group =>
        group.parTraverse(id => scan(id, 0L, 0).timeoutTo(8.seconds, IO.pure(Result(Nil, 0, true, true))))
      }
      .map { batches =>
        val results = batches.flatten
        Result(
          results.flatMap(_.hits),
          results.map(_.pages).sum,
          ids.distinct.size > MaxConversations || results.exists(_.truncated),
          results.exists(_.failed)
        )
      }
      .timeoutTo(15.seconds, IO.pure(Result(Nil, 0, true, true)))
  end search
end SocialSearchScan
