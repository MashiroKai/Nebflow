# Replace Qdrant with In-Memory Vector Index

## Problem

Nebflow currently depends on an external Qdrant instance for skill vector storage and retrieval. This introduces several operational burdens:

- **Heavy dependency**: Users must run a Qdrant container or service separately.
- **External API calls**: All vector operations (upsert, search, scroll, delete) go through HTTP to `localhost:6333`.
- **Configuration overhead**: Users need to configure `qdrantUrl` in `nebflow.json`.
- **Overkill for the use case**: The skill vector dataset is tiny (typically dozens to hundreds of vectors from `~/.nebflow/skills/`). A full vector database is unnecessary.

## Proposed Solution

Replace `QdrantClient` with a lightweight, pure in-memory vector index. Given the data scale, a brute-force cosine similarity scan over a `Vector[MemoryPoint]` is more than sufficient and eliminates all external dependencies.

## Design

### 1. Core Data Structure

```scala
case class MemoryPoint(
  id: String,
  vector: Array[Float],
  payload: Map[String, String]
)

class InMemoryVectorIndex:
  @volatile private var points: Vector[MemoryPoint] = Vector.empty

  def init(name: String, vectorSize: Int): IO[Unit]
  def rebuild(newPoints: List[MemoryPoint]): IO[Unit]
  def search(vector: Array[Float], limit: Int, threshold: Double): IO[List[SearchResult]]
  def deleteByFilter(field: String, value: String): IO[Unit]
  def getAllPayloads(groupField: String, mtimeField: String): Map[String, Long]
  def clear(): IO[Unit]
```

**Search implementation**: cosine similarity via dot product and L2 norms. Complexity is `O(n * d)` where `n` is point count and `d` is embedding dimension. With `n < 1000` and `d <= 1536`, this completes in sub-millisecond time.

### 2. Indexing Strategy

Switch from **incremental** to **full rebuild on startup**:

- Scan all skill files in `~/.nebflow/skills/`.
- Generate tags via LLM.
- Embed tags via the configured embedding provider (with cache, see below).
- Build a single `List[MemoryPoint]` and call `index.rebuild(points)`.

**Rationale**: The dataset is small enough that a full rebuild is fast. This eliminates the need for:
- `getIndexedMtimes()` / `scrollPayloads()` (no longer needed to compare disk vs. index state).
- Per-skill delete-then-upsert logic.
- Persistent vector storage.

### 3. Embedding Cache (Required)

To avoid re-calling the embedding API on every restart, implement a **local file-based cache**:

**Cache key**: `(skill_id, tag, skill_mtime)` — mtime ensures cache invalidation when the skill file changes.

**Storage layout**:
```
~/.nebflow/.cache/
  embeddings/
    <skill-name>/
      <tag-hash>.json   # { "vector": [0.1, -0.2, ...], "mtime": 1234567890 }
```

**Lookup flow**:
1. For each `(skill, tag)`, compute cache key (e.g., SHA-256 of `skill_name + "\0" + tag`).
2. Check if cache file exists and its stored `mtime` matches the skill file's current `mtime`.
3. If hit: read vector from cache, skip API call.
4. If miss/stale: call embedding API, write result to cache file.

**Benefits**:
- Startup time drops from ~2-5s (API calls) to ~50ms (cache reads) in the common case.
- API cost reduced to near zero for unchanged skills.
- Cache is self-invalidating via mtime; no manual cleanup needed.
- Simple JSON files; no database dependency.

**Edge cases**:
- Cache directory can be safely deleted; it will be rebuilt on next startup.
- Tag generation itself is not cached (fast, no external call), only embedding.

### 4. Configuration Changes

Remove from `VectorInjectionConfig`:
- `qdrantUrl: String`

Add (optional):
- `rebuildOnStart: Boolean = true`
- `embeddingCachePath: Option[String] = None`

### 5. Files to Modify

| File | Change |
|------|--------|
| `src/main/scala/nebflow/skill/qdrant.scala` | Delete or deprecate `QdrantClient` |
| `src/main/scala/nebflow/skill/indexer.scala` | Rewrite `SkillIndexer` to use `InMemoryVectorIndex`; remove incremental logic |
| `src/main/scala/nebflow/skill/discovery.scala` | Update constructor to accept `InMemoryVectorIndex` |
| `src/main/scala/nebflow/llm/config.scala` | Remove `qdrantUrl` from `VectorInjectionConfig` |
| `src/main/scala/nebflow/gateway/GatewayMain.scala` | Update initialization: create `InMemoryVectorIndex`, call `rebuildIndex` on startup |

## Performance Estimate

| Metric | Value |
|--------|-------|
| Typical vector count | 100 skills x 5 tags = 500 |
| Memory footprint | 500 x 768 x 4 B ~= 1.5 MB |
| Search latency | ~0.1 - 1 ms |
| Startup rebuild time (cold) | ~2-5 s (API calls for new/changed skills) |
| Startup rebuild time (warm) | ~50 ms (cache hits for unchanged skills) |

## Benefits

- **Zero external dependencies**: No Docker, no extra process, no port conflicts.
- **Simpler configuration**: Remove `qdrantUrl` from user config.
- **Faster search**: No HTTP round-trip; pure in-memory computation.
- **Simpler code**: ~180 lines of HTTP client code replaced with ~60 lines of math.
- **Easier testing**: No need to mock or spin up a Qdrant instance for tests.

## Migration Path

1. Implement `InMemoryVectorIndex`.
2. Implement `EmbeddingCache` (file-based, keyed by `skill_id + tag + mtime`).
3. Update `SkillIndexer` to use `InMemoryVectorIndex` + `EmbeddingCache`; remove incremental logic.
4. Update `SkillDiscovery` and `GatewayMain`.
5. Update config schema and documentation.
6. Remove Qdrant-related dependencies from `build.sbt` if any (currently Qdrant is accessed via raw HTTP, so no extra library to remove).
7. Update user-facing docs to remove Qdrant setup instructions.

## Alternatives Considered

| Approach | Pros | Cons |
|----------|------|------|
| **SQLite + vectors** | File-based persistence | No native vector index; search still brute-force. Adds unnecessary disk I/O for tiny data. |
| **ChromaDB (embedded)** | Has HNSW index | Requires Python runtime; heavy for a Scala project. |
| **LanceDB** | Columnar, fast | Native/JNI dependency; overkill for this scale. |

The in-memory approach is the sweet spot for Nebflow's skill discovery use case.
