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
   *   the task text used as the decision `state`. The caller truncates it to
   *   the provider budget before calling.
   * @param catalog
   *   the capability catalog, in the order the allocation face should use.
   *   Order matters: the methodology's question ids are derived from it, and
   *   a stable order keeps the rendered question set reproducible.
   */
  def allocate(taskText: String, catalog: List[CatalogEntry]): IO[JevAllocation]

/**
 * One capability catalog entry as the allocation face sees it.
 *
 * `name` is the package name written back into `NodeDef.plugins`; `description`
 * is what the model reads. Deliberately a small record rather than a plugin
 * type: `core.plugin.PluginRegistry` holds the authority for what is installed
 * and trusted, and this is only the rendering input.
 */
final case class CatalogEntry(name: String, description: String)
