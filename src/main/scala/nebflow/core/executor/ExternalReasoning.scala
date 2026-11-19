package nebflow.core.executor

import io.circe.Json
import io.circe.parser.parse
import nebflow.shared.{ThinkingConfig, ThinkingEffort}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.{Executors, TimeUnit}

import scala.util.Try

/** Shared rank mapping and Codex catalog discovery, resolved independently for every turn. */
private[executor] object ExternalReasoning:
  final case class Catalog(levels: List[String], source: String, timestamp: String)

  final case class Decision(
    requested: Option[String],
    actual: Option[String],
    direction: String,
    source: String,
    timestamp: String,
    reason: String
  ):

    def trace: String =
      s"external-reasoning requested=${requested.getOrElse("unset")} actual=${actual.getOrElse("unset")} direction=$direction source=$source timestamp=$timestamp reason=$reason"

  private val tiers =
    List(ThinkingEffort.Minimal, ThinkingEffort.Low, ThinkingEffort.Medium, ThinkingEffort.High, ThinkingEffort.Max)

  def map(effort: ThinkingEffort, advertised: List[String]): Option[String] =
    val index = tiers.indexOf(effort)
    if advertised.isEmpty then None
    else if index == 0 then advertised.headOption
    else if index == tiers.size - 1 then advertised.lastOption
    else if advertised.contains(effort.wireName) then Some(effort.wireName)
    else Some(advertised((index * (advertised.size - 1) + tiers.size - 2) / (tiers.size - 1)))

  def decide(config: ThinkingConfig, model: Option[String], catalog: Option[Catalog]): Decision =
    val requested = Option.when(config.enabled)(config.effort).flatten
    val reason =
      if !config.enabled then "thinking off; using Codex default"
      else if requested.isEmpty then "effort unset; using Codex default"
      else if model.forall(_.trim.isEmpty) then "model unknown; using Codex default"
      else if catalog.forall(_.levels.isEmpty) then "catalog unavailable; using Codex default"
      else
        "mapped against advertised levels" + (if catalog.exists(c =>
                                                  c.source == "cache" && !c.levels.contains(requested.get.wireName)
                                                )
                                              then "; catalog may be stale"
                                              else "")
    val actual =
      if requested.isDefined && model.exists(_.trim.nonEmpty) then catalog.flatMap(c => map(requested.get, c.levels))
      else None
    val direction = (requested, actual, catalog) match
      case (Some(req), Some(out), Some(c)) if req.wireName == out => "exact"
      case (Some(req), Some(out), Some(c)) =>
        // Compare against the requested name when advertised; otherwise compare
        // normalized rank positions. This is a rank indication, not proof that
        // differently named model levels are semantically equivalent.
        val baseline = c.levels.indexOf(req.wireName)
        val actualIndex = c.levels.indexOf(out)
        if baseline >= 0 then if actualIndex > baseline then "up" else "down"
        else if req == ThinkingEffort.Minimal then "up"
        else if req == ThinkingEffort.Max then "up"
        else if actualIndex * (tiers.size - 1) >= tiers.indexOf(req) * (c.levels.size - 1) then "up"
        else "down"
      case _ => "none"
    Decision(
      requested.map(_.wireName),
      actual,
      direction,
      catalog.map(_.source).getOrElse("none"),
      catalog.map(_.timestamp).getOrElse("unknown"),
      reason
    )

  end decide

  def argv(args: List[String], decision: Decision): List[String] =
    decision.actual.fold(args)(value => args.take(1) ++ List("-c", s"model_reasoning_effort=$value") ++ args.drop(1))

  def catalog(bin: String, model: String): Option[Catalog] =
    runtime(bin, model).orElse(cache(model))

  private def cache(model: String): Option[Catalog] = Try {
    val home =
      sys.env.get("CODEX_HOME").map(Paths.get(_)).getOrElse(Paths.get(System.getProperty("user.home"), ".codex"))
    val file = home.resolve("models_cache.json")
    val json = parse(Files.readString(file, StandardCharsets.UTF_8)).toOption.get
    val entries = json.hcursor.downField("models").as[List[Json]].toOption.getOrElse(Nil)
    val entry = entries.find(_.hcursor.get[String]("slug").toOption.contains(model)).get
    val levels = entry.hcursor
      .downField("supported_reasoning_levels")
      .as[List[Json]]
      .toOption
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("effort").toOption)
      .filter(_.nonEmpty)
    val timestamp =
      json.hcursor.get[String]("fetched_at").toOption.getOrElse(Files.getLastModifiedTime(file).toInstant.toString)
    Catalog(levels, "cache", timestamp)
  }.toOption.filter(_.levels.nonEmpty)

  private[executor] def runtime(bin: String, model: String): Option[Catalog] =
    val executor = Executors.newSingleThreadExecutor()
    var process: Process = null
    try
      process = new ProcessBuilder(bin, "app-server").redirectError(ProcessBuilder.Redirect.DISCARD).start()
      val child = process
      val future = executor.submit(
        new java.util.concurrent.Callable[Option[Catalog]]:
          def call(): Option[Catalog] =
            val out = new java.io.PrintWriter(child.getOutputStream, true, StandardCharsets.UTF_8)
            val in =
              new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream, StandardCharsets.UTF_8))
            def send(s: String): Unit = out.println(s)
            def response(id: Int): Option[Json] =
              Iterator
                .continually(in.readLine())
                .takeWhile(_ != null)
                .flatMap(s => parse(s).toOption)
                .find(_.hcursor.get[Int]("id").toOption.contains(id))
            send("""{"id":1,"method":"initialize","params":{"clientInfo":{"name":"stand","version":"1"}}}""")
            val initialized = response(1).flatMap(_.hcursor.downField("result").focus).isDefined
            if initialized then send("""{"method":"initialized","params":{}}""")
            var cursor: Option[String] = None
            var page = 0
            var found: Option[Catalog] = None
            var continue = initialized
            while continue && page < 20 && found.isEmpty do
              page += 1
              val params = Json.obj(
                "limit" -> Json.fromInt(100),
                "includeHidden" -> Json.True,
                "cursor" -> cursor.fold(Json.Null)(Json.fromString)
              )
              send(
                Json
                  .obj("id" -> Json.fromInt(page + 1), "method" -> Json.fromString("model/list"), "params" -> params)
                  .noSpaces
              )
              val result = response(page + 1).flatMap(_.hcursor.downField("result").focus)
              val data = result.flatMap(_.hcursor.get[List[Json]]("data").toOption).getOrElse(Nil)
              found = data.find(j => j.hcursor.get[String]("model").toOption.contains(model)).flatMap { j =>
                val levels = j.hcursor
                  .get[List[Json]]("supportedReasoningEfforts")
                  .toOption
                  .getOrElse(Nil)
                  .flatMap(_.hcursor.get[String]("reasoningEffort").toOption)
                  .filter(_.nonEmpty)
                Option.when(levels.nonEmpty)(Catalog(levels, "runtime", Instant.now().toString))
              }
              cursor = result.flatMap(_.hcursor.get[Option[String]]("nextCursor").toOption.flatten)
              continue = cursor.nonEmpty
            end while
            found
          end call
      )
      future.get(8, TimeUnit.SECONDS)
    catch case _: Exception => None
    finally
      if process != null then process.destroyForcibly()
      executor.shutdownNow()
    end try
  end runtime
end ExternalReasoning
