package nebflow.core.flow

import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.entity.{EntityLoader, FlowDagDef, FlowNode, FlowStructure, NodeRoute}

/**
 * R8-P2 load-time structural validation (FlowStructure.validate, wired into
 * EntityLoader.parseFlowJson) + the three onComplete JSON forms.
 */
class FlowStructureSpec extends FunSuite:

  private def goto(t: String): NodeRoute = NodeRoute.Goto(t)
  private val ret: NodeRoute = NodeRoute.Return

  private def flowOf(nodes: (String, FlowNode)*): FlowDagDef =
    FlowDagDef(name = "f", description = "d", nodes = nodes.toMap, entry = nodes.head._1)

  // ------------------------------------------------------------
  // validate: rejects
  // ------------------------------------------------------------

  test("single-node flow rejected — one agent is an agent + skill, not a flow"):
    val f = flowOf("solo" -> FlowNode("a", "$task", ret))
    val errs = FlowStructure.validate(f)
    assert(errs.exists(_.contains("at least 2 nodes")), s"got: $errs")

  test("pure cycle rejected — no termination path"):
    val f = flowOf(
      "a" -> FlowNode("w", "$task", goto("b")),
      "b" -> FlowNode("w", "$task", goto("a"))
    )
    val errs = FlowStructure.validate(f)
    assert(errs.exists(_.contains("no termination path")), s"got: $errs")

  test("serial multi-in-edge loop (code-review shape) is VALID — no fan, no barrier"):
    // reviewer has 2 in-edges (scanner + fixer back-edge) but no parallel:
    // unarmed arrivals are plain serial walks; rejecting this would kill the
    // 4 healthy legacy flows.
    val f = flowOf(
      "scanner" -> FlowNode("w", "$task", goto("reviewer")),
      "reviewer" -> FlowNode(
        "w", "$task",
        NodeRoute.Switch("$reviewer.verdict", Map("pass" -> ret, "fix" -> goto("fixer")))
      ),
      "fixer" -> FlowNode("w", "$task", goto("reviewer"))
    )
    assertEquals(FlowStructure.validate(f), Nil, s"serial loop must stay valid, got: ${FlowStructure.validate(f)}")
    assertEquals(FlowStructure.joinNodes(f), Set("reviewer"), "in-degree ≥2 node detected as join candidate")

  test("two switch cases to one node (no fan) is valid — one fires at a time"):
    val f = flowOf(
      "a" -> FlowNode("w", "$task", NodeRoute.Switch("$a.verdict", Map("x" -> goto("b"), "y" -> goto("b")))),
      "b" -> FlowNode("w", "$task", ret)
    )
    assertEquals(FlowStructure.validate(f), Nil, s"got: ${FlowStructure.validate(f)}")

  test("barrier join with a serial in-edge OUTSIDE the fan rejected — mixed arrivals corrupt the count"):
    // fan(r1, r2) converges on j; but x ALSO routes to j — x's serial arrival
    // would decrement a counter it never contributed to.
    val f = flowOf(
      "p" -> FlowNode("w", "$task", NodeRoute.Parallel(List("r1", "r2"))),
      "r1" -> FlowNode("w", "t", goto("j")),
      "r2" -> FlowNode("w", "t", goto("j")),
      "x" -> FlowNode("w", "t", goto("j")),
      "j" -> FlowNode("w", "t", ret)
    )
    val errs = FlowStructure.validate(f)
    assert(errs.exists(_.contains("mixes parallel and serial arrivals")), s"got: $errs")

  test("fan wider than maxFanout rejected"):
    val f = flowOf(
      "p" -> FlowNode("w", "$task", NodeRoute.Parallel(List("r1", "r2", "r3", "r4", "r5"))),
      "r1" -> FlowNode("w", "t", goto("j")),
      "r2" -> FlowNode("w", "t", goto("j")),
      "r3" -> FlowNode("w", "t", goto("j")),
      "r4" -> FlowNode("w", "t", goto("j")),
      "r5" -> FlowNode("w", "t", goto("j")),
      "j" -> FlowNode("w", "t", ret)
    )
    val errs = FlowStructure.validate(f)
    assert(errs.exists(_.contains("exceeds maxFanout 4")), s"got: $errs")

  test("parallel branch that can reach $return before a join rejected"):
    val f = flowOf(
      "p" -> FlowNode("w", "$task", NodeRoute.Parallel(List("r1", "r2"))),
      "r1" -> FlowNode("w", "t", goto("j")),
      "r2" -> FlowNode("w", "t", ret), // early return — ambiguous
      "j" -> FlowNode("w", "t", ret)
    )
    val errs = FlowStructure.validate(f)
    assert(errs.exists(_.contains("before converging at a join")), s"got: $errs")

  // ------------------------------------------------------------
  // validate: accepts
  // ------------------------------------------------------------

  test("research-style diamond with revise back-edge is valid"):
    val f = FlowDagDef(
      name = "research",
      description = "d",
      entry = "planner",
      maxLoop = 2,
      maxFanout = 3,
      nodes = Map(
        "planner" -> FlowNode("w", "$task", NodeRoute.Parallel(List("r1", "r2", "r3"))),
        "r1" -> FlowNode("w", "t", goto("verify")),
        "r2" -> FlowNode("w", "t", goto("verify")),
        "r3" -> FlowNode("w", "t", goto("verify")),
        "verify" -> FlowNode(
          "w", "t",
          NodeRoute.Switch(
            "$verify.verdict",
            Map(
              "pass" -> goto("writer"),
              "revise" -> NodeRoute.Parallel(List("r1", "r2", "r3"))
            ),
            Some(goto("writer"))
          )
        ),
        "writer" -> FlowNode("w", "t", ret)
      )
    )
    assertEquals(FlowStructure.validate(f), Nil, "healthy diamond passes all checks")

  // ------------------------------------------------------------
  // structure analysis units
  // ------------------------------------------------------------

  test("inDegrees counts edge multiplicity; joinNodes = in-degree ≥ 2"):
    val f = flowOf(
      "p" -> FlowNode("w", "$task", NodeRoute.Parallel(List("r1", "r2"))),
      "r1" -> FlowNode("w", "t", goto("j")),
      "r2" -> FlowNode("w", "t", goto("j")),
      "j" -> FlowNode("w", "t", ret)
    )
    assertEquals(FlowStructure.inDegrees(f).getOrElse("j", 0), 2)
    assertEquals(FlowStructure.joinNodes(f), Set("j"))

  test("displayEdges expands parallel fans and switch-case parallel values"):
    val f = flowOf(
      "p" -> FlowNode("w", "$task", NodeRoute.Parallel(List("r1", "r2"))),
      "r1" -> FlowNode("w", "t", goto("j")),
      "r2" -> FlowNode("w", "t", goto("j")),
      "j" -> FlowNode(
        "w", "t",
        NodeRoute.Switch(
          "$j.verdict",
          Map("pass" -> ret, "revise" -> NodeRoute.Parallel(List("r1", "r2")))
        )
      )
    )
    val edges = FlowStructure.displayEdges(f).map((from, to, _) => s"$from->$to").sorted
    assertEquals(edges, List("j->" + "$return", "j->r1", "j->r2", "p->r1", "p->r2", "r1->j", "r2->j").sorted)

  // ------------------------------------------------------------
  // NodeRoute decoder: three onComplete forms
  // ------------------------------------------------------------

  test("onComplete decodes string / switch / parallel forms"):
    assertEquals(decode[NodeRoute]("\"reviewer\""), Right(NodeRoute.Goto("reviewer")))
    assertEquals(decode[NodeRoute]("\"$return\""), Right(NodeRoute.Return))
    assertEquals(
      decode[NodeRoute]("""{"parallel": ["r1", "r2"]}"""),
      Right(NodeRoute.Parallel(List("r1", "r2"), NodeRoute.OnFailMode.Abort))
    )
    assertEquals(
      decode[NodeRoute]("""{"parallel": ["r1"], "onFail": "collect"}"""),
      Right(NodeRoute.Parallel(List("r1"), NodeRoute.OnFailMode.Collect))
    )

  test("switch case values accept the parallel form (recursively)"):
    val json =
      """{
        |  "switch": "$verify.verdict",
        |  "cases": { "pass": "writer", "revise": {"parallel": ["r1","r2","r3"], "onFail": "collect"} },
        |  "default": "$return"
        |}""".stripMargin
    decode[NodeRoute](json) match
      case Right(NodeRoute.Switch(_, cases, default, _)) =>
        assertEquals(cases("pass"), NodeRoute.Goto("writer"))
        assertEquals(
          cases("revise"),
          NodeRoute.Parallel(List("r1", "r2", "r3"), NodeRoute.OnFailMode.Collect)
        )
        assertEquals(default, Some(NodeRoute.Return))
      case other => fail(s"expected Switch, got: $other")

  test("onFail rejects unknown values"):
    val err = decode[NodeRoute]("""{"parallel": ["r1"], "onFail": "explode"}""")
    assert(err.isLeft, s"unknown onFail must fail decode, got: $err")

  test("FlowDagDef decodes maxFanout and defaults it to 4"):
    val withFan =
      """{"name":"f","description":"d","entry":"p","nodes":{"p":{"agent":"w","input":"$task","onComplete":{"parallel":["r1","r2"]}},"r1":{"agent":"w","input":"t","onComplete":"$return"},"r2":{"agent":"w","input":"t","onComplete":"$return"}},"maxFanout":8}"""
    decode[FlowDagDef](withFan) match
      case Right(f) => assertEquals(f.maxFanout, 8)
      case Left(e)  => fail(s"decode failed: $e")
    val withoutFan =
      """{"name":"f","description":"d","entry":"p","nodes":{"p":{"agent":"w","input":"$task","onComplete":"r1"},"r1":{"agent":"w","input":"t","onComplete":"$return"}}}"""
    decode[FlowDagDef](withoutFan) match
      case Right(f) => assertEquals(f.maxFanout, 4)
      case Left(e)  => fail(s"decode failed: $e")

  // ------------------------------------------------------------
  // EntityLoader integration: parseFlowJson applies structural validation
  // ------------------------------------------------------------

  test("loadFlow rejects a single-node flow.json at load time"):
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    try
      os.makeDir.all(tmp / "flows" / "broken")
      os.write(
        tmp / "flows" / "broken" / "flow.json",
        """{"name":"broken","description":"d","entry":"solo","nodes":{"solo":{"agent":"w","input":"$task","onComplete":"$return"}}}"""
      )
      PathUtil.setDataRoot(tmp)
      val loaded = EntityLoader.loadFlow("broken").unsafeRunSync()
      assertEquals(loaded, None, "structurally invalid flow must not load")
    finally
      PathUtil.setDataRoot(prevRoot)
      os.remove.all(tmp)

  test("loadFlow accepts a structurally valid flow.json"):
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    try
      os.makeDir.all(tmp / "flows" / "healthy")
      os.write(
        tmp / "flows" / "healthy" / "flow.json",
        """{"name":"healthy","description":"d","entry":"a","nodes":{"a":{"agent":"w","input":"$task","onComplete":"b"},"b":{"agent":"w","input":"t","onComplete":"$return"}}}"""
      )
      PathUtil.setDataRoot(tmp)
      val loaded = EntityLoader.loadFlow("healthy").unsafeRunSync()
      assert(loaded.isDefined, "valid flow loads")
      assertEquals(loaded.map(_.name), Some("healthy"))
    finally
      PathUtil.setDataRoot(prevRoot)
      os.remove.all(tmp)

end FlowStructureSpec
