package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import nebflow.actor.AgentDef
import nebflow.shared.PathUtil

class ConversationSearchToolSpec extends munit.FunSuite:
  private val root = os.pwd / "target" / "conversation-search-tool-spec"
  private val oldRoot = PathUtil.dataRoot
  private val cli = os.pwd / "tools" / "stand-recall"
  private val sentinel = "synthetic-fixture-privacy-4821"

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(root)
    os.remove.all(root)
    os.makeDir.all(root / "sessions")
    os.write(
      root / "sessions" / "_index.json",
      Json
        .obj(
          "sessions" -> Json.arr(
            Json.obj(
              "id" -> Json.fromString("main-current-fixture"),
              "name" -> Json.fromString("Synthetic root fixture"),
              "agentName" -> Json.fromString("Nebula"),
              "createdAt" -> Json.fromLong(1L),
              "updatedAt" -> Json.fromLong(2L)
            ),
            Json.obj(
              "id" -> Json.fromString("worker-decoy-fixture"),
              "name" -> Json.fromString("Synthetic worker fixture"),
              "agentName" -> Json.fromString("nebflow"),
              "createdAt" -> Json.fromLong(1L),
              "updatedAt" -> Json.fromLong(2L)
            )
          )
        )
        .noSpaces
    )
    os.write(
      root / "sessions" / "worker-decoy-fixture.json",
      Json
        .arr(
          Json.obj(
            "role" -> Json.fromString("user"),
            "timestamp" -> Json.fromLong(1790000000000L),
            "content" -> Json.fromString(s"worker-only $sentinel")
          )
        )
        .noSpaces
    )
    os.write(
      root / "sessions" / "main-current-fixture.json",
      Json
        .arr(
          Json.obj(
            "role" -> Json.fromString("user"),
            "timestamp" -> Json.fromLong(1790000000000L),
            "content" -> Json.fromString(s"main fixture remembers $sentinel")
          ),
          Json.obj(
            "role" -> Json.fromString("assistant"),
            "timestamp" -> Json.fromLong(1790000001000L),
            "content" -> Json.fromString(s"main fixture recorded $sentinel")
          )
        )
        .noSpaces
    )
    val indexed = os
      .proc(Seq("python3", cli.toString, "--update", "--data-root", root.toString))
      .call(cwd = os.pwd, check = false, mergeErrIntoOut = true)
    assertEquals(indexed.exitCode, 0, indexed.out.text())
  end beforeAll

  override def afterAll(): Unit = PathUtil.setDataRoot(oldRoot)

  private val rootCtx = ToolContext(
    projectRoot = "/tmp",
    agentDef = Some(AgentDef(name = "Nebula", description = "root")),
    sessionId = Some("main-current-fixture"),
    depth = 0
  )

  test("registered tool returns bounded JSON hits from synthetic main-session corpus only") {
    assert(ToolRegistry.TOOL_MAP.contains("ConversationSearch"))
    val result =
      ConversationSearchTool.call(Json.obj("query" -> Json.fromString(sentinel)).asObject.get, rootCtx).unsafeRunSync()
    val text = result.toOption.get
    val json = parse(text).toOption.get.asArray.get
    assertEquals(json.size, 2)
    assert(json.forall(_.hcursor.get[String]("session_id").toOption.contains("main-current-fixture")))
    assert(json.forall(_.hcursor.get[String]("text").toOption.exists(_.contains(sentinel))))
    assert(text.length <= ConversationSearchTool.MaxOutputChars)
  }

  test("fail-closed for non-root and rejects arbitrary selector, path, database, or mutation arguments") {
    val input = Json.obj("query" -> Json.fromString(sentinel)).asObject.get
    val denied = ConversationSearchTool
      .call(input, rootCtx.copy(agentDef = Some(AgentDef(name = "general", description = ""))))
      .unsafeRunSync()
      .swap
      .toOption
      .get
      .message
    assert(denied.contains("CONVERSATION_SEARCH_FORBIDDEN"))
    List("db", "data-root", "session", "update", "rebuild", "shell").foreach { key =>
      val unsafe = ConversationSearchTool
        .call(input.add(key, Json.fromString("/not-allowed")), rootCtx)
        .unsafeRunSync()
        .swap
        .toOption
        .get
        .message
      assert(unsafe.contains("unsupported parameter"), s"unexpectedly accepted $key")
    }
    val registry = ToolRegistry.ALL_TOOLS.find(_.name == "ConversationSearch").get
    assertEquals(registry.inputSchema("additionalProperties").flatMap(_.asBoolean), Some(false))
  }

  test("stand-recall readonly mode requires existing db and cannot combine with index or stats operations") {
    val missing = os
      .proc(Seq("python3", cli.toString, "--readonly", "--db", (root / "missing.db").toString, "needle"))
      .call(cwd = os.pwd, check = false, mergeErrIntoOut = true)
    assert(missing.exitCode != 0)
    assert(!os.exists(root / "missing.db"))
    val mutation = os
      .proc(Seq("python3", cli.toString, "--readonly", "--update", "--data-root", root.toString))
      .call(cwd = os.pwd, check = false, mergeErrIntoOut = true)
    assert(mutation.exitCode != 0)
    assert((mutation.out.text() + mutation.err.text()).contains("--readonly is only valid"))
  }
end ConversationSearchToolSpec
