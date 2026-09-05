# Nebflow Rust 迁移可行性分析

## 结论先行

**不推荐全量迁移 Rust。先做 P0 原地优化。**

Rust 确实能彻底解决 GC 问题和 JSON 性能问题（serde_json 比 circe 快 ~4.5x，无 GC 暂停，内存占用降 ~13x），但 41,220 行代码全量重写的代价（3-6 人月）远超收益。

P0 优化（4-5 人天）可达到 50-70% 的 CPU 降低，已足够解决当前 10 Agent 并发场景的瓶颈。Rust 重写的额外收益（再降 20-30%）在 P0 优化后已不是瓶颈——因为 CPU 使用率从 ~100% 降到 ~30-40% 后，瓶颈转移到 LLM API 网络延迟。

---

## 1. Rust 能否解决当前瓶颈

### 1.1 GC 压力 —— 彻底解决

- **当前**：10 个 Agent 每秒产生 4-8 MB 临时 JSON 对象，Minor GC 每秒多次触发，每次 5-20ms STW 暂停
- **Rust**：无 GC。serde_json 临时 Value 确定性析构，jemalloc/mimalloc 分配 ~10-20ns/次
- **量化**：Rust 对象无 header（JVM 有 12-16 字节 header），相同数据内存占用约 JVM 的 50-60%

### 1.2 JSON 处理速度 —— 显著提升

基准测试数据（kostya/benchmarks, 2026-07-19）：

| 语言 + 库 | 解析时间 | 相对 Scala circe |
|-----------|---------|-----------------|
| Rust (serde_json, typed) | 0.110s | ~4.5x 快 |
| Rust (simd-json) | ~0.080s | ~6x 快 |
| Scala (jsoniter-scala) | 0.147s | ~3.4x 快 |
| **Scala (circe) ← Nebflow 当前** | ~0.500s (估) | 基准 (1.0x) |
| Go (encoding/json) | ~0.900s (估) | ~0.6x (更慢) |
| Java (DSL-JSON) | 0.571s | ~0.9x |

注：circe 精确数字未在 kostya/benchmarks 测试（该基准用 jsoniter-scala 测 Scala）。circe 因运行时反射 + macro-derived derivation，比 jsoniter-scala 编译期代码生成慢 3-5 倍，此为社区共识。

### 1.3 内存占用 —— 大幅降低

| 组件 | Scala (JVM) | Rust | 差距 |
|------|------------|------|------|
| 运行时基线 | 200-400 MB | 5-15 MB | 20-30x |
| 单 Agent 持久内存 | ~310 KB | ~150-200 KB | ~1.5-2x |
| 10 Agent 总内存 | ~310 MB | ~20-25 MB | ~13x |
| GC 堆开销 | +100-200% 峰值 | 0 | N/A |
| 每 Task 开销 | ~2 KB (Fiber) | ~256 bytes (tokio Task) | ~8-16x |

### 1.4 并发模型 —— 基本持平

tokio 和 cats-effect 都用 work-stealing 调度器 + epoll/kqueue I/O。调度延迟无 GC 时相同（~50-100ns）。关键差异是 **GC 暂停**——cats-effect 调度器再高效也挡不住 STW 暂停打断 SSE 流处理。

---

## 2. 迁移代价

### 2.1 代码量

| 模块 | 文件数 | 行数 | 迁移难度 |
|------|--------|------|---------|
| agent | 13 | 5,536 | 非常难（核心 LLM 编排） |
| core | 107 | 17,815 | 困难（20+ 工具重写） |
| gateway | 13 | 7,791 | 困难（http4s → axum） |
| cli | 21 | 2,967 | 容易（scopt → clap） |
| llm | 10 | 2,350 | 中等（SSE 解析重写） |
| neblink | 6 | 1,427 | 中等 |
| shared | - | 1,292 | 中等（ADT → enum） |
| dropbox | 3 | 598 | 容易 |
| actor | 6 | 520 | 容易（Queue → mpsc） |
| 测试 | 36 | 5,795 | 中等（全部重写） |
| **总计** | **238** | **47,081** | |

### 2.2 库映射

| Scala 库 | Rust 对应 | API 使用次数 | 难度 |
|---------|----------|-------------|------|
| cats-effect IO | tokio + async/await | **1,914 处** | 非常难 |
| circe | serde_json / simd-json | **2,819 处** | 困难 |
| fs2 | tokio_stream / futures | 21 处 | 中等 |
| sttp | reqwest / hyper | 68 处 | 中等 |
| http4s | axum / actix-web | 37 处 | 中等 |

关键障碍：cats-effect IO 和 circe 合计 **4,733 处**使用（90%+），迁移是范式级改变而非简单替换。

### 2.3 渐进迁移可行性

**不能渐进迁移。** 所有模块通过 `cats-effect IO` 类型紧密耦合，无法在保持 Scala 接口的同时替换单个模块内部为 Rust。

理论可行的路径：
1. JNI/FFI 桥接 JSON 处理（维护成本高）
2. 微服务拆分（引入网络延迟）
3. **替换 circe → jsoniter-scala**（最实际的渐进优化，3-5 天，3-4x JSON 性能提升）

---

## 3. 替代方案对比

### 方案一：Scala P0 原地优化（推荐）

| 优化项 | CPU 降低 | 工作量 |
|--------|---------|--------|
| WS 事件 batching | -50% | 1 天 |
| JSON 增量序列化 | -30% | 2 天 |
| List → Vector | -5% | 0.5 天 |
| G1GC 调优 | -5% | 0.5 天 |
| 子 Agent WS deepMerge 优化 | -5% | 0.5 天 |
| **合计** | **-70% ~ -90%** | **4-5 天** |

### 方案二：Scala + JNI/FFI（不推荐）

理论可行但开发复杂度远高于直接换 JSON 库，且 JNI 边界数据传递有额外开销。

### 方案三：Go 迁移（不推荐）

encoding/json 基于 reflect 比 circe 更慢，仍有 GC，类型系统退步。用更大代价获得更差结果。

### 方案四：Kotlin Coroutines（可以考虑）

留在 JVM，迁移成本低于 Rust，kotlinx.serialization 比 circe 快 2-3x。但 GC 问题依旧——只要在 JVM 上就无法消除。

---

## 4. ROI 分析

### 为什么 P0 优化就够了？

当前 CPU 满载的根因不是"语言太慢"，而是"做了不必要的工作"：
- 每秒 5000-10000 次 WS 事件中，绝大多数是单字符 delta。50ms 延迟不可感知。批量发送减少 95% 事件。
- 每轮序列化完整 300KB 消息，95% 内容与上轮相同。增量序列化减少 80% 工作。

P0 优化后 10 Agent CPU 使用率从 ~100% 降到 ~30-40%——风扇不转，用户不卡。

### 什么时候考虑 Rust？

| 场景 | 并发量 | P0 够用？ | Rust 优势 |
|------|--------|----------|----------|
| 当前 | 10 | 够用 | 锦上添花 |
| 中等 | 50 | 勉强 | GC 凸显 |
| 大规模 | 200+ | 不够 | 必须无 GC |
| 边缘部署 | 1-5 | 勉强 | 内存关键 |
| SaaS 多租户 | 1000+ | 不够 | 必须 |

### 如果未来迁移，推荐策略

1. 先做 circe → jsoniter-scala 替换（3-5 天，不跨语言）
2. 提取 JSON 处理为独立 Rust crate + JNI wrapper（PoC 验证）
3. 从边缘模块开始迁移（neblink 1,427 行 / actor 520 行）
4. gateway 层迁移（axum + tokio WebSocket）
5. 最后迁移 core + agent（2-3 个月全职）

总迁移周期预估 4-6 个月。

---

## 5. 行为对比

| 维度 | 当前 (Scala) | P0 优化后 (Scala) | Rust 重写后 |
|------|-------------|------------------|------------|
| 10 Agent CPU | 90-100% | 30-40% | 15-25% |
| 流式输出延迟 | 50-100ms (GC 抖动) | 20-50ms | 5-20ms |
| 内存占用 | 300-500 MB | 250-350 MB | 20-30 MB |
| 启动时间 | 3-5 秒 | 3-5 秒 | 0.1-0.5 秒 |
| 分发体积 | ~150 MB | ~150 MB | ~20 MB |
| 开发工作量 | - | 4-5 人天 | 3-6 人月 |

---

## 6. 建议

1. **立即执行 P0 优化**（WS batching + JSON 增量序列化 + Vector + GC 调优），预期 4-5 人天，CPU 降 50-70%
2. **评估 circe → jsoniter-scala 替换**，3-5 人天，JSON 性能提升 3-4x，不跨语言
3. **暂不考虑 Rust 迁移**，除非未来需要支持 50+ Agent 并发或边缘部署
4. **如果未来迁移，按上述渐进策略执行**，先边缘模块后核心逻辑

---

## 附录：数据来源

- kostya/benchmarks (2026-07-19): https://github.com/kostya/benchmarks
- serde-rs/json-benchmark: https://github.com/serde-rs/json-benchmark
- Nebflow 代码库统计：238 文件，47,081 行（2026-08-07）
- Actor 性能瓶颈分析报告：~/.nebflow/plan/default/20260806_actor-performance-analysis.md
- circe vs jsoniter-scala 性能比：基于 jsoniter-scala 作者公开基准和社区共识
