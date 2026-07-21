package nebflow.core.flow

import cats.effect.IO
import nebflow.core.{NebflowLogger, PathUtil}

/**
 * Persists per-flow learning memory.
 *
 * Each flow accumulates knowledge across runs — user preferences, verification
 * patterns, lessons learned — in a Markdown file alongside its YAML definition.
 *
 * Loaded at mount/reload time, injected into step prompts.
 * Updated by the reflect agent after pipeline completion.
 *
 * Storage: ~/.nebflow/flows/<flowName>.memory.md
 */
object FlowMemoryStore:
  private val logger = NebflowLogger(getClass)

  private def flowsDir: os.Path = PathUtil.dataRoot / "flows"

  private def memoryFile(flowName: String): os.Path =
    flowsDir / s"$flowName.memory.md"

  /** Load flow memory. Returns empty string if file doesn't exist. */
  def load(flowName: String): IO[String] =
    val file = memoryFile(flowName)
    IO.blocking {
      if os.exists(file) then os.read(file)
      else ""
    }

  /** Save flow memory (atomic write). */
  def save(flowName: String, content: String): IO[Unit] =
    val file = memoryFile(flowName)
    val tmp = flowsDir / s"$flowName.memory.md.tmp"
    IO.blocking {
      if !os.exists(flowsDir) then os.makeDir.all(flowsDir)
      os.write.over(tmp, content)
      os.move.over(tmp, file, replaceExisting = true)
    }.void

end FlowMemoryStore
