package nebflow.core.entity

import nebflow.core.entity.NodeRoute.{Goto, Parallel, Return, Switch}

/**
 * Static structure analysis of a FlowDagDef — single source of truth shared by
 * the loader (structural validation) and the executor (barrier arming).
 *
 * The flow DSL has no explicit edges field: edges derive from each node's
 * onComplete. This object centralizes that derivation, including the R8-P2
 * parallel fan-out, so every consumer (executor barriers, WS DAG rendering,
 * registry edges, load-time validation) agrees on the graph.
 */
object FlowStructure:

  /** Terminal pseudo-target marking a flow exit edge. */
  val ReturnNode = "$return"

  /**
   * All route targets of `route`, tagged with the switch case (or None for
   * unconditional routes). Switch `default` routes carry "default" — display
   * edges omit them (legacy behavior), reachability analysis includes them.
   */
  def routeTargets(route: NodeRoute, cond: Option[String] = None): List[(String, Option[String])] =
    route match
      case Goto(t)      => (t, cond) :: Nil
      case Return       => (ReturnNode, cond) :: Nil
      case Parallel(fan, _) => fan.map(t => (t, cond))
      case Switch(_, cases, default, _) =>
        cases.toList.sortBy(_._1).flatMap { (k, r) => routeTargets(r, Some(k)) } ++
          default.toList.flatMap(r => routeTargets(r, Some("default")))

  /** Display edges (from, to, condition) — as the legacy emitter: cases only, no default. */
  def displayEdges(flow: FlowDagDef): List[(String, String, Option[String])] =
    flow.nodes.toList.sortBy(_._1).flatMap { (nodeId, node) =>
      node.onComplete match
        case Goto(t)          => (nodeId, t, None) :: Nil
        case Return           => (nodeId, ReturnNode, None) :: Nil
        case Parallel(fan, _) => fan.map(t => (nodeId, t, None))
        case Switch(_, cases, _, _) =>
          cases.toList.sortBy(_._1).flatMap { (cond, r) =>
            routeTargets(r, Some(cond)).map((to, c) => (nodeId, to, c))
          }
    }

  /**
   * Full analysis edges INCLUDING switch defaults — used for reachability /
   * termination analysis. Condition values are irrelevant here.
   */
  private def analysisEdges(flow: FlowDagDef): List[(String, String)] =
    flow.nodes.toList.flatMap { (nodeId, node) =>
      routeTargets(node.onComplete).map((to, _) => (nodeId, to))
    }

  /** Static in-degree per node, counting edge multiplicity. */
  def inDegrees(flow: FlowDagDef): Map[String, Int] =
    analysisEdges(flow).groupBy(_._2).view.mapValues(_.size).toMap

  /** Nodes with in-degree ≥ 2 — candidate barrier joins. */
  def joinNodes(flow: FlowDagDef): Set[String] =
    inDegrees(flow).filter(_._2 >= 2).keySet

  /**
   * Forward reachability from `start`, NOT expanding through `stopAt` nodes
   * (they count as reached but their successors are not explored). Returns
   * (reached node ids ∪ stopAt hits, whether $return is reachable).
   */
  def reachFrom(flow: FlowDagDef, start: String, stopAt: Set[String]): (Set[String], Boolean) =
    val succ: Map[String, List[String]] =
      analysisEdges(flow).groupBy(_._1).view.mapValues(_.map(_._2).distinct).toMap
    def go(frontier: List[String], seen: Set[String], foundReturn: Boolean): (Set[String], Boolean) =
      frontier match
        case Nil => (seen, foundReturn)
        case next :: rest =>
          if next == ReturnNode then go(rest, seen, foundReturn = true)
          else if seen.contains(next) then go(rest, seen, foundReturn)
          else if stopAt.contains(next) then go(rest, seen + next, foundReturn)
          else go(succ.getOrElse(next, Nil) ++ rest, seen + next, foundReturn)
    go(start :: Nil, Set.empty, false)

  /** Every Parallel route declared anywhere in the flow (with its owner node). */
  def parallelRoutes(flow: FlowDagDef): List[(String, Parallel)] =
    def routesOf(r: NodeRoute): List[Parallel] =
      r match
        case p: Parallel  => p :: Nil
        case Switch(_, cases, default, _) =>
          cases.values.toList.flatMap(routesOf) ++ default.toList.flatMap(routesOf)
        case _ => Nil
    flow.nodes.toList.sortBy(_._1).flatMap { (id, n) => routesOf(n.onComplete).map(id -> _) }

  /**
   * Structural validation (R8-P2 load checks). Returns a list of reject
   * reasons; empty = structurally valid. Agent-existence checks stay in
   * EntityLoader.validateFlow (they need the agent library).
   *
   * Checks:
   *  1. at least 2 nodes (single-agent flows should be an agent + skill)
   *  2. a termination path exists — at least one route to $return anywhere
   *     (pure-cycle flows can only ever end in "Max loop exceeded")
   *  3. every parallel fan ≤ maxFanout
   *  4. every fan branch converges at a join BEFORE $return is reachable
   *     (mid-branch $return is ambiguous: first-come-first-served loses results)
   *  5. barrier joins must not MIX serial and fan arrivals: a node armed by a
   *     fan (≥ 2 branches converging on it) whose other in-edges originate
   *     outside the fan's downstream would decrement a counter it never
   *     contributed to — the barrier could fire before all branches arrived.
   *     Pure serial multi-in-edge nodes (no fan upstream — e.g. loop back-edges
   *     in code-review) are NOT joins and stay valid: the executor treats
   *     unarmed arrivals as plain serial walks.
   */
  def validate(flow: FlowDagDef): List[String] =
    val errors = List.newBuilder[String]

    // 1. minimum size
    if flow.nodes.size < 2 then
      errors += s"flow must have at least 2 nodes (got ${flow.nodes.size}) — a single agent is an agent + skill, not a flow"

    // 2. termination path
    val hasReturn = flow.nodes.values.exists(n => routeTargets(n.onComplete).exists(_._1 == ReturnNode))
    if !hasReturn then
      errors += "flow has no termination path: no node routes to $return (a pure cycle can only end in 'Max loop exceeded')"

    val joins = joinNodes(flow)

    // 3. fanout cap
    parallelRoutes(flow).foreach { (owner, p) =>
      if p.fan.size > flow.maxFanout then
        errors += s"parallel fan of node '$owner' has ${p.fan.size} branches — exceeds maxFanout ${flow.maxFanout}"
      // 4. mid-branch $return
      p.fan.distinct.foreach { branch =>
        val (_, reachesReturn) = reachFrom(flow, branch, joins)
        if reachesReturn then
          errors += s"parallel branch '$branch' (fan of '$owner') can reach $$return before converging at a join — branches must converge; early return is ambiguous"
      }
    }

    // 5. mixed arrivals on barrier joins. For each join, the set of nodes a
    // walker may legitimately arrive FROM = downstream of any fan that arms
    // it (the fan branches themselves + everything they traverse). An
    // in-edge from outside every such set is a serial arrival that corrupts
    // the barrier count.
    val fans = parallelRoutes(flow).map(_._2)
    val inEdges: Map[String, List[String]] =
      analysisEdges(flow).filter(_._2 != ReturnNode).groupBy(_._2).view.mapValues(_.map(_._1)).toMap
    joins.toList.sorted.foreach { j =>
      val armingFans = fans.filter { p =>
        p.fan.distinct.count { b =>
          reachFrom(flow, b, joins)._1.contains(j)
        } >= 2
      }
      if armingFans.nonEmpty then
        // valid arrival sources: downstream of any fan that arms this join
        val validSources =
          armingFans.flatMap(p => p.fan.distinct.flatMap(b => reachFrom(flow, b, joins)._1.toList)).toSet + j
        inEdges.getOrElse(j, Nil).distinct.foreach { src =>
          if !validSources.contains(src) then
            errors += s"join '$j' mixes parallel and serial arrivals: in-edge '$src->$j' originates outside the parallel fan's downstream — a serial arrival would corrupt the barrier count"
        }
    }

    errors.result()

end FlowStructure
