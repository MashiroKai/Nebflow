package nebflow.core.jev

import cats.effect.IO

/**
 * Narrow port of the JeV allocation face (Face C / P2).
 *
 * ==Why a port and not a direct call==
 * The dependency direction is `shared <- core <- cli/gateway`; `core` has ZERO
 * `llm` imports today and must keep it that way. The allocation face lives in
 * `llm.decision` (it is a provider), while its CALL SITES are in `core`
 * (node creation, kernel trigger). The port is the seam, and it follows the
 * established inverted-narrow-port pattern (`core.ProviderHealthPort`,
 * `core.AgentRuntimePort`).
 *
 * ==Why the signature carries no llm type==
 * The result is [[JevAllocation]], defined here in `core`. A decision failure
 * is flattened to its classified diagnostic text at this boundary, so no llm
 * type ever reaches a core call site and the failure classification stays a
 * detail of the provider layer.
 */
trait JevAllocatorPort:

  /**
   * Score a task against the capability catalog and return the chosen set.
   *
   * @param taskText
   *   the task text fed into the decision `state`. 🔴 Hand over the task text
   *   UNTRUNCATED: composing the provider-facing `state` — including the
   *   provider-budget truncation of this text — belongs to the implementation,
   *   which is the only party that knows the composed layout (preamble, brief,
   *   catalog section). Truncating here as well would put the same budget under
   *   two owners and would cut the text before a section that must survive the
   *   cut.
   * @param catalog
   *   the capability catalog, in the order the allocation face should use.
   *   Order matters twice over: the methodology's question ids are derived from
   *   it, and the rendered `state` presents the same entries in the same order,
   *   so a stable order keeps both the question set and the model-visible text
   *   reproducible. Both the `name` and the `description` of an entry are
   *   model-visible (see [[CatalogEntry]]).
   */
  def allocate(taskText: String, catalog: List[CatalogEntry]): IO[JevAllocation]

/**
 * One capability catalog entry as the allocation face sees it.
 *
 * `name` is the package name written back into `NodeDef.plugins`; `description`
 * is what the MODEL reads — the implementation renders it into the `state` of
 * the decision call, so dropping it would leave the model judging packages by
 * name alone. Both fields are therefore load-bearing inputs, not metadata:
 * `name` keys the questions, `description` carries the capability statement.
 * Deliberately a small record rather than a plugin type:
 * `core.plugin.PluginRegistry` holds the authority for what is installed and
 * trusted, and this is only the rendering input.
 */
final case class CatalogEntry(name: String, description: String)
