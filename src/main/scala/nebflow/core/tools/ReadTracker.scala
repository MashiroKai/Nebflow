package nebflow.core.tools

import cats.effect.{IO, Ref}

import java.nio.file.Path

class ReadTracker private (state: Ref[IO, Set[Path]]):
  def recordRead(path: Path): IO[Unit] = state.update(_ + path)
  def hasBeenRead(path: Path): IO[Boolean] = state.get.map(_.contains(path))
  def clear(): IO[Unit] = state.set(Set.empty)

object ReadTracker:

  def create: IO[ReadTracker] =
    Ref.of[IO, Set[Path]](Set.empty).map(new ReadTracker(_))
