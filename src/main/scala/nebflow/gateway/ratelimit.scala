package nebflow.gateway

import cats.effect.{IO, Ref}

class RateLimiter private (
  maxRequests: Int,
  windowMs: Long,
  timestampsRef: Ref[IO, Map[String, List[Long]]]
):
  def check(key: String): IO[Boolean] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      timestampsRef.modify { timestamps =>
        val window = timestamps.getOrElse(key, Nil).filter(t => now - t < windowMs)
        if window.length < maxRequests then
          (timestamps.updated(key, now :: window), true)
        else
          (timestamps.updated(key, window), false)
      }
    }

object RateLimiter:
  def create(maxRequests: Int = 60, windowMs: Long = 60_000): IO[RateLimiter] =
    Ref.of[IO, Map[String, List[Long]]](Map.empty).map(new RateLimiter(maxRequests, windowMs, _))
