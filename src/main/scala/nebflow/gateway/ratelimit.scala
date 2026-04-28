package nebflow.gateway

import cats.effect.{IO, Ref}

class RateLimiter private (
  maxRequests: Int,
  windowMs: Long,
  countersRef: Ref[IO, Map[String, (Long, Int)]]
):
  def check(key: String): IO[Boolean] =
    val now = System.currentTimeMillis()
    countersRef.modify { counters =>
      val (windowStart, count) = counters.getOrElse(key, (now, 0))
      if now - windowStart > windowMs then
        (counters.updated(key, (now, 1)), true)
      else if count < maxRequests then
        (counters.updated(key, (windowStart, count + 1)), true)
      else
        (counters, false)
    }

object RateLimiter:
  def create(maxRequests: Int = 60, windowMs: Long = 60_000): IO[RateLimiter] =
    Ref.of[IO, Map[String, (Long, Int)]](Map.empty).map(new RateLimiter(maxRequests, windowMs, _))
