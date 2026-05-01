package nebflow.skill

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.llm.VectorInjectionConfig
import nebflow.shared.*

import java.nio.charset.StandardCharsets
import java.util.UUID

class SkillIndexer(
  qdrant: QdrantClient,
  embedding: EmbeddingService,
  config: VectorInjectionConfig
):
  private val logger = NebflowLogger.forName("nebflow.skill.indexer")

  def indexSkill(skill: SkillFile, llm: LlmHandle[IO]): IO[Unit] =
    for
      _ <- IO(logger.info(s"Indexing skill: ${skill.name}"))
      tags <- SkillTagGenerator.generateTags(llm, skill.name, skill.description, skill.language)
      _ <- IO(logger.info(s"Generated ${tags.length} tags for ${skill.name}: ${tags.take(5).mkString(", ")}"))
      vectors <- embedding.embedBatch(tags)
      points = tags.zip(vectors).zipWithIndex.map { case ((tag, vec), idx) =>
        QdrantPoint(
          id = UUID.nameUUIDFromBytes(s"${skill.name}-$idx".getBytes(StandardCharsets.UTF_8)).toString,
          vector = vec,
          payload = Map(
            "skill_id" -> skill.name,
            "tag" -> tag,
            "description" -> skill.description,
            "file_path" -> skill.filePath.toString,
            "mtime" -> skill.mtime.toString
          )
        )
      }.filter(_.vector.nonEmpty)
      _ <- qdrant.deleteByFilter(config.collection, "skill_id", skill.name)
      _ <- qdrant.upsertPoints(config.collection, points)
      _ <- IO(logger.info(s"Indexed ${points.length} vectors for skill '${skill.name}'"))
    yield ()

  /** Incremental index: only process skills that are new or modified. */
  def indexIncremental(skillsDir: os.Path, llm: LlmHandle[IO]): IO[Unit] =
    SkillFile.scanDir(skillsDir).flatMap { skills =>
      if skills.isEmpty then
        IO(logger.info(s"No skill files found in $skillsDir"))
      else
        getIndexedMtimes().flatMap { indexedMtimes =>
          // Skills to index: new files or modified files (mtime changed)
          val toIndex = skills.filter { s =>
            indexedMtimes.get(s.name) match
              case None => true // new skill
              case Some(mtime) => s.mtime != mtime // modified
          }
          // Skills to delete: indexed but no longer on disk
          val onDisk = skills.map(_.name).toSet
          val toDelete = indexedMtimes.keys.filterNot(onDisk).toList

          if toIndex.isEmpty && toDelete.isEmpty then
            IO(logger.info(s"All ${skills.length} skills up-to-date, nothing to index"))
          else
            val deleteIO = toDelete.traverse_ { name =>
              IO(logger.info(s"Deleting removed skill: $name")) *>
                qdrant.deleteByFilter(config.collection, "skill_id", name)
            }
            val indexIO = toIndex.traverse_(skill => indexSkill(skill, llm).handleErrorWith { e =>
              IO(logger.warn(s"Failed to index skill '${skill.name}': ${e.getMessage}"))
            })
            IO(logger.info(s"Incremental index: ${toIndex.length} to index, ${toDelete.length} to delete, ${skills.length} total")) *>
              deleteIO *> indexIO
        }
    }

  /** Fetch mtime for each indexed skill from Qdrant payloads via scroll. */
  private def getIndexedMtimes(): IO[Map[String, Long]] =
    qdrant.scrollPayloads(config.collection, "skill_id", "mtime")
end SkillIndexer
