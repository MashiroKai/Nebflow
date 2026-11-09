package nebflow.llm.decision

import cats.effect.IO
import nebflow.llm.{ModelCandidate, ProviderRegistry}
import nebflow.shared.AgentModelConfig

/** Shared per-call route resolution for decision calls backed by the provider registry. */
object DecisionRouteResolver:

  def candidates(registry: ProviderRegistry, callType: String, legacyRef: Option[String] = None): IO[List[ModelCandidate]] =
    for
      configured <- registry.decisionRoute(callType)
      explicit = configured.flatMap(_.preferred).orElse(legacyRef)
      route <- explicit match
        case Some(ref) =>
          registry.getCandidateForRef(ref).map(_.toList)
        case None =>
          registry.getCandidatesForAgent(nebflow.core.SchemePolicy.nebulaChain())
    yield route

  def configured(registry: ProviderRegistry, callType: String): IO[Option[AgentModelConfig]] =
    registry.decisionRoute(callType)

end DecisionRouteResolver
