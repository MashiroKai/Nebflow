package nebflow.core

/** NEBFLOW_HEADLESS=1 — deterministic benchmark mode, read once at JVM start.
  *
  * Benchmark containers (Terminal-Bench etc.) drive Nebflow unattended, so the
  * same task must yield the same context with no interactive waits: memory
  * blocks that persist across runs, AskUser prompts that hang forever, and
  * telemetry traffic are all benchmark noise. Each touchpoint reads this flag
  * statically — the val is frozen at object init, and the switch is
  * process-wide on purpose: a benchmark container IS headless, no per-request
  * granularity (design doc "Headless 一次性执行入口" §3.4 / §D5).
  */
object HeadlessMode:
  val enabled: Boolean = Branding.env("HEADLESS").contains("1")
