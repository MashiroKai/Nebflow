package nebflow.skill

import cats.effect.IO
import nebflow.llm.Config

import scala.util.matching.Regex

case class SkillFile(name: String, description: String, language: String, filePath: os.Path, mtime: Long)

object SkillFile:
  private val FrontmatterRegex: Regex = "(?s)^---\\s*\\n(.*?)\\n---\\s*\\n(.*)".r

  def parse(path: os.Path): Option[SkillFile] =
    if !os.exists(path) || !path.last.endsWith(".md") then None
    else
      val content = os.read(path)
      val mtime = os.stat(path).mtime.toMillis
      content match
        case FrontmatterRegex(frontmatter, _) =>
          val fields = parseSimpleYaml(frontmatter)
          val name = fields.getOrElse("name", path.last.stripSuffix(".md"))
          val desc = fields.getOrElse("description", "")
          val lang = fields.getOrElse("language", "zh")
          Some(SkillFile(name, desc, lang, path, mtime))
        case _ => None

  def scanDir(dir: os.Path): IO[List[SkillFile]] =
    IO.blocking {
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .flatMap { entry =>
            if os.isDir(entry) then
              // Folder-based skill: look for skill.md or any .md inside
              val mds = os.list(entry).filter(_.last.endsWith(".md"))
              mds.headOption.map(parse).getOrElse(None)
            else if entry.last.endsWith(".md") then
              // Flat file (backward compat)
              parse(entry)
            else None
          }
          .toList
    }

  private def parseSimpleYaml(text: String): Map[String, String] =
    text.linesIterator
      .map { line =>
        // Strip inline comments (but not inside quoted values)
        val trimmed = line.trim
        if trimmed.startsWith("#") then None // comment line
        else
          val idx = trimmed.indexOf(':')
          if idx >= 0 then
            val key = trimmed.substring(0, idx).trim
            var value = trimmed.substring(idx + 1).trim
            // Strip inline comment only if value is unquoted
            if !value.startsWith("\"") && !value.startsWith("'") then
              val commentIdx = value.indexOf('#')
              if commentIdx >= 0 then value = value.substring(0, commentIdx).trim
            // Strip surrounding quotes
            value = value.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")
            if key.nonEmpty then Some(key -> value) else None
          else None
      }
      .collect { case Some(k -> v) => k -> v }
      .toMap
end SkillFile
