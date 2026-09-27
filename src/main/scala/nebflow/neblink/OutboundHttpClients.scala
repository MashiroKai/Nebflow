package nebflow.neblink

import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Single, auditable source of truth for the gateway's **short-lived** outbound
 * HTTP clients (D5 收敛，2026-09-13 clientperf 批).
 *
 * Why this exists — the structural fact, not a performance claim: the JDK
 * connection pool is a field of the `HttpClient` instance itself
 * (`HttpClientImpl.java:336` `private final ConnectionPool connections;`,
 * assigned per instance at `:502`). The four sites converged here used to
 * build a brand-new client on **every call** (device-flow polls once per
 * beat, avatar once per cache miss, provider `/models` probe, `proxyPost`) —
 * so their connection reuse was structurally **zero**, and every call also
 * spun up a fresh selector thread pool + executor. One memoized instance per
 * policy makes reuse non-zero and makes the transport policy readable and
 * reviewable in exactly one place.
 *
 * What this deliberately does NOT do:
 *   - it does **not** touch the HTTP version decision — both policies pin
 *     `HTTP_1_1`, byte-for-byte the same value the four call sites used
 *     before. No h1/h2 decision moves in this batch (D4(a): 本轮不切 h2);
 *   - it does **not** cover the long-lived clients (`NeblinkClient`'s
 *     class-field client) or any of the un-pinned outbound sites
 *     (`shared/http.scala`, `RemoteExecutor`, `NeblinkService`,
 *     `DropboxService`, `SttService`, `TtsService`, `mcp/transports`,
 *     `NeblinkPresenceService`) — those keep their own construction.
 *
 * Evidence (what is actually measured) + judge-red for the version pin are
 * recorded at each call site; the canonical note is on
 * `NeblinkClient.httpClient`.
 */
object OutboundHttpClients:

  /**
   * The explicit transport policies. One line per policy = the whole policy
   * surface a reviewer has to read.
   *
   * `Direct15s` — direct connection (system proxy explicitly bypassed) with a
   * 15 s connect timeout. Used for the neblink-server / Logto / avatar-origin
   * calls, which are all LAN-or-WAN reachable without a proxy.
   *
   * `SystemProxy10s` — the builder is left on the JDK default proxy selector
   * (honouring `java.net.useSystemProxies` / `http(s).proxyHost`), 10 s
   * connect timeout. Used by the provider `/models` probe so the probe sees
   * the same network path a real completion takes. The proxy selector is
   * deliberately **not** re-bound here: today's `fetchProviderModels` did not
   * call `.proxy(...)` either, and this batch changes structure, not routing.
   */
  enum Policy(val label: String):
    case Direct15s extends Policy("direct-15s")
    case SystemProxy10s extends Policy("system-proxy-10s")

  private val clients = new ConcurrentHashMap[Policy, HttpClient]()
  private val constructionCount = new AtomicLong(0L)

  /**
   * The shared client for `policy` — one instance per policy per JVM. Safe to
   * call from any thread; `computeIfAbsent` runs the factory at most once.
   */
  def client(policy: Policy): HttpClient =
    clients.computeIfAbsent(
      policy,
      p =>
        constructionCount.incrementAndGet()
        build(p)
    )

  /**
   * How many clients this factory has actually built — the reuse observation
   * point (per-call sites: 1 construction per call; here: 1 per policy).
   * `private[neblink]` because it is an observability seam, not API.
   */
  private[neblink] def constructedClients: Long = constructionCount.get()

  private def build(policy: Policy): HttpClient =
    policy match
      case Policy.Direct15s =>
        HttpClient
          .newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .proxy(java.net.ProxySelector.of(null))
          .connectTimeout(Duration.ofSeconds(15))
          .build()
      case Policy.SystemProxy10s =>
        HttpClient
          .newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build()

end OutboundHttpClients
