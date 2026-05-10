package nebflow.core.hooks

import io.circe.syntax.*
import io.circe.{Json, JsonObject, parser}
import nebflow.core.NebflowLogger

/** Loads and parses hooks config from nebflow.json. */
object HooksConfigLoader:

  private val logger = NebflowLogger.forName("nebflow.hooks")

  /**
   * Load hooks config from the project's nebflow.json.
   * Returns HooksConfig.empty if no hooks are configured or on parse error.
   */
  def load(projectRoot: os.Path): HooksConfig =
    val configPath = projectRoot / "nebflow.json"
    if !os.exists(configPath) then HooksConfig.empty
    else
      try
        val content = os.read(configPath)
        parser.parse(content) match
          case Left(err) =>
            logger.warn(s"Failed to parse nebflow.json for hooks: ${err.getMessage}")
            HooksConfig.empty
          case Right(json) =>
            json.hcursor.downField("hooks").as[Map[String, Json]] match
              case Left(err) =>
                logger.debug(s"No hooks section in nebflow.json")
                HooksConfig.empty
              case Right(rawMap) =>
                val parsed = rawMap.flatMap { case (eventName, jsonValue) =>
                  HookEvent.fromString(eventName) match
                    case Some(event) =>
                      parseRules(jsonValue).map(rules => eventName -> rules)
                    case None =>
                      logger.warn(s"Unknown hook event: $eventName, skipping")
                      None
                }
                val config = HooksConfig(parsed.toMap)
                val eventCount = config.hooks.values.map(_.length).sum
                if eventCount > 0 then
                  logger.info(s"Loaded ${eventCount} hook rules across ${config.hooks.size} events")
                config
      catch
        case e: Exception =>
          logger.warn(s"Failed to load hooks config: ${e.getMessage}")
          HooksConfig.empty

    end if

  end load

  private def parseRules(json: Json): Option[List[HookRule]] =
    json.asArray.map { arr =>
      arr.flatMap(parseRule).toList
    }

  private def parseRule(json: Json): Option[HookRule] =
    json.asObject.map { obj =>
      val matcher = obj("matcher").flatMap(_.asString).getOrElse("*")
      val hooks = obj("hooks").flatMap(_.asArray).map(_.flatMap(parseHookDef).toList).getOrElse(Nil)
      HookRule(matcher, hooks)
    }

  private def parseHookDef(json: Json): Option[HookDef] =
    json.asObject.map { obj =>
      HookDef(
        `type` = obj("type").flatMap(_.asString).getOrElse("command"),
        command = obj("command").flatMap(_.asString).getOrElse(""),
        timeout = obj("timeout").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(60),
        continueOnError = obj("continueOnError").flatMap(_.asBoolean).getOrElse(true)
      )
    }
end HooksConfigLoader
