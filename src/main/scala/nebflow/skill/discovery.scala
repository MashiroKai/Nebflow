package nebflow.skill

import cats.effect.IO
import nebflow.core.NebflowLogger
import nebflow.llm.VectorInjectionConfig

case class SkillMatch(skillName: String, description: String, filePath: String, score: Double)

class SkillDiscovery(
  qdrant: QdrantClient,
  embedding: EmbeddingService,
  config: VectorInjectionConfig
):
  private val logger = NebflowLogger.forName("nebflow.skill.discovery")

  def findRelevantSkill(userInput: String): IO[Option[SkillMatch]] =
    if userInput.trim.isEmpty then IO.pure(None)
    else
      embedding.embed(userInput).flatMap { vector =>
        if vector.isEmpty then IO.pure(None)
        else
          qdrant.search(config.collection, vector, config.topK, config.threshold).map { results =>
            results.headOption.flatMap { hit =>
              val skillName = hit.payload.getOrElse("skill_id", "")
              val description = hit.payload.getOrElse("description", "")
              val filePath = hit.payload.getOrElse("file_path", "")
              // Skip results with missing required fields
              if skillName.isEmpty || filePath.isEmpty then None
              else
                val match_ = SkillMatch(skillName, description, filePath, hit.score)
                logger.info(s"match: ${match_.skillName}, score: ${"%.2f".format(match_.score)}")
                Some(match_)
            }
          }
      }
end SkillDiscovery
