```mermaid
stateDiagram-v2
    [*] --> Idle

    state "BEFORE — client round" as BeforeRound {
        [*] --> Preload
        Preload: row face (1 request)
        Preload --> FanOut
        FanOut: N x GET /api/chains/<key>   (N = every row)
        FanOut --> ChildFanOut
        ChildFanOut: M x GET /.../children   (M = every internal row)
        ChildFanOut --> Interrupted
        Interrupted: tunnel-per-ip-cap exceeded -> 429 (1385 in prod log)
        Interrupted --> RetryLoop
        RetryLoop: next WS frame re-enters FanOut (no budget, no cap)
        RetryLoop --> FanOut
    }

    state "AFTER — client round" as AfterRound {
        [*] --> RowFace
        RowFace: row face (1 request, ETag/304 when unchanged)
        RowFace --> DigestFace
        DigestFace: bulk digest (1 request; ?since= -> 57B unchanged)
        DigestFace --> Select
        Select: candidates = uncached OR digest moved OR active row past TTL
        Select --> Budgeted
        Budgeted: <= 24 details + <= 16 child legs, active rows first
        Budgeted --> Gated
        Gated: shared semaphore MAX_INFLIGHT=4
        Gated --> Broadcast
        Broadcast: mirror updated only on a real delta
    }

    Idle --> BeforeRound: reconnect (mirror discarded)
    Idle --> AfterRound: reconnect (mirror + digests reused)

    AfterRound --> RateLimited: 429
    RateLimited --> Backoff: per route class, exponential + jitter, Retry-After floor
    Backoff --> RowFace: next round after the window (never a tight loop)
    RateLimited --> Broadcast: remaining legs of this round stand down

    Interrupted --> [*]: panel never completes (author's symptom)
    Broadcast --> [*]: panel completes
```
