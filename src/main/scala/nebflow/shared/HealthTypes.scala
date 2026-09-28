/* 严格DAG第⑥步第一批:健康面 ADT 下沉 shared(行为保持,2026-09-26)。 */
package nebflow.shared

/** Health state of a single provider+model combination. */
enum HealthState:
  case Up
  case Down(reason: String, since: Long)

/**
 * Health of the Tier 2a standalone search API (P2, 2026-08-25) — tracked
 * INDEPENDENTLY of model-provider health so layered health output can show
 * the decoupling the user asked for ("模型配额 DOWN ≠ 搜索 DOWN").
 */
enum SearchApiHealth:
  case Unconfigured
  case Up
  case Down(reason: String, since: Long)
