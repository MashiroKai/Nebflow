# llm-provider 可信基化第一段：审计范围计划（开工第一动作）

- 类型：过程件（审计计划，非交付件）
- 轮次：零代码轮——只出全貌审计件 + 重筑设计方案；实现=作者过目后的第二段
- 作者裁定背景（2026-11-04 22:50）：pilogic✓db✓ 为真可信基；llm-provider 目前是「被持久化加固过的存量」而非可信基——fallback 链/重试语义未重构、病灶在册
- 交件：`docs/20261104_<HHMMSS>_scala-llm-provider-audit-redesign__delegate-nebflow-0bd60c6b.md`（审计+设计方案同件）

## 0. 边界与铁律

- 轮 A（pi-logic 接主链）并行在飞，独占 AgentSessionExecution / AgentProcessing / pilogic 包——本作业零触碰，引用只读
- 本作业领域=provider / fallback / 健康 / 流式相关文件
- 轮 B 产物（HealthMonitor 避坑/熔断 guard 档：softAvoid/markDown/markUp 写穿 db、重启不归零、坏档 300s 有界保守）——衔接吸收，不许推翻
- 零代码：不 commit 任何 src/ 变更；纯 docs 产物；不合 main（留分支）；不 push

## 1. 待亲读核验的病灶锚（#144 导航有 1 处证伪前科，逐条以实况记录）

| # | 锚 | 病灶主张 | 核验问题 |
|---|---|---|---|
| A1 | `fallback.scala:81` | MaxTurnLlmCalls=4 只数失败重试，真轮次上限 NOT FOUND | 实读该行及调用面，确认语义 |
| A2 | main 腿 `unsafeRunSync:2740/2752-2753` | 阻塞点 | 实读该行号域，确认是否仍在/行为 |
| A3 | HealthMonitor 避坑/熔断 guard 档（轮 B 今日落库） | 已写穿 db、重启不归零、坏档 300s 有界 | 确认现状形状，只吸收不推翻 |

## 2. 审计范围（七面，每条=file:line+行为+后果，按严重度排序）

1. 接入面清点：provider 链数（zhipu/qwen/别的）、配置面、每条请求/响应路径
2. fallback 与切换语义：链序、触发条件、切换后回切、与避坑 guard 的分工
3. 重试/退避/配额：429/5xx 处理、退避常数、重试上限（MaxTurnLlmCalls 核验）、幂等性
4. 流式：chunk 处理、中断/断流恢复、与 db 事件流（KernelEvent）的衔接
5. 超时与取消：请求超时、取消传播、unsafeRunSync 阻塞点核验
6. 成本/预算：token 计量、轮帽缺失实锤（对 pilogic RunSeed.policy 三元上限——provider 侧怎么接）
7. 错误分类：可重试/不可重试/需换链/需熔断四类现状覆盖度

## 3. 设计方案节（同件）

- 重筑 trait 面（最小可信基口径：上层单方向引用、不引新三方依赖、接口按日后可抽进程形状）
- 故障语义矩阵：错误类别 × 处置（重试/退避/换链/熔断/上报）× 持久化（guard 档/事件流）
- 三向边界表：pilogic（循环重试策略接缝）/ db（guard/事件）/ 主链（调用点）——哪些归基、哪些留上层
- 待拍板清单（编号+选项+推荐，不代裁）

## 4. 步骤

1. ✅ 本计划落盘 + commit（本 commit）
2. 亲读 provider/fallback/health/streaming 全域文件（`src/main/scala/nebflow/llm/`、`shared/fallback.scala`、`shared/HealthTypes.scala`、cli/gateway 相关、主链调用点只读）
3. 逐条核验三锚 + 七面病灶清单成文（能力级叙述：行为+后果+证据 file:line）
4. 设计方案节成文（矩阵/边界表/待拍板）
5. wiki 校验：`check_wiki_docs.py --root docs`，基线实测为准、新增断链 0、本件违规 0
6. 收尾：分支内 commit 交付件，出收尾报告（不 merge 不 push）

## 5. 关键文件清单（初扫 Glob 所得，亲读为度）

- `src/main/scala/nebflow/llm/fallback.scala`、`llm/HealthMonitor.scala`、`llm/SearchProviderResolver.scala`
- `src/main/scala/nebflow/llm/providers/`（整目录）
- `src/main/scala/nebflow/llm/decision/`（LlmAllocator/DecisionHealthMonitor/DecisionProviders/LayaProvider/TypesafeJevProvider/JevFallback）
- `src/main/scala/nebflow/shared/fallback.scala`、`shared/HealthTypes.scala`
- `src/main/scala/nebflow/core/LlmRuntimePort.scala`、`core/LlmLogWriter.scala`、`core/jev/JevFallback.scala`
- `src/main/scala/nebflow/gateway/ProviderProbe.scala`、`ProviderModelProbe.scala`、`HealthRoutes.scala`
- `src/main/scala/nebflow/cli/ProviderCommand.scala`、`HealthCommand.scala`
- 主链调用点（AgentSessionExecution/AgentProcessing——只读引用，不触碰）
- `src/main/scala/nebflow/pilogic/Provider.scala`（只读：RunSeed.policy 接缝）
