# thinking-config 实施批 REPORT——两段迁移 + 探测定性 + cmdcode 旁支修复（2026-10-29）

批：作者 2026-10-28 23:28 拍板「思考模式的也派实施」。权威设计 =
`docs/design/20261028_215449_thinking-config-research__delegate-nebflow-ca5f09f4.zh-CN.md`（§8 为本批回填）。
分支：`delegate/delegate-nebflow-0d8cb864`（6 commits，外科手术面 = 8 功能文件 + 3 spec 文件 + 2 docs/evidence 文件）。

## 1. 交付清单（commit ↔ 任务项）

| commit | 内容 | 任务项 |
|---|---|---|
| `5868fe96e` | wire 翻译表收敛 `AnthropicAdapter.wireThinkingFragment` 单点 + `ThinkingMode.scala`（能力类/族启发/档位/三层继承/per-model patch）+ schema 扩展（旧键零破坏）+ registry passback 族判定 + interface 每候选解析 + WS/CLI 面 | 第一段 1/2/3 |
| `51c062f38` | 撤回 worktree scalafmt 偏斜的 382 文件重排（保持合并面外科手术） | 纪律 |
| `a13874a43` | effort 点亮门按探测定性置位 + OpenAiAdapter 直发档位 | 第二段 5 |
| `5721f828a` | 设置面板每模型思考档位下拉（设计 §5 最小面）+ i18n + CSS | 第二段 5 |
| `cf40facc3` | 三份 spec 47 断言（wire 逐族钉 W1-W14 + E2E E1-E4；继承/零破坏 I1-I5b；openai 面 O1-O5） | 验证 |
| `3143dec9b` | 设计文档 §2.3/§2.4/§6/§8 回填 + 探测证据落库 | 探测腿 4 |

## 2. wire 翻译表（最终态，spec 钉）

| 族（模型 id 判据） | enabled 线上形态 | off 线上形态 | effort 档位 |
|---|---|---|---|
| glm*（含 glm-5.3-flash） | `thinking:{type:enabled}`（budget 不再发） | 兜底 enabled（强制思考） | 顶层 `reasoning_effort`，medium→high（门开） |
| kimi-for-coding/k2.x | `thinking:{type:enabled}` | 兜底 enabled | 无 effort 面（永不发） |
| k3*（k3.1-pro 等） | **无 thinking 键**（官方明示移除） | 无 | 顶层 `reasoning_effort`，medium→high（门开） |
| deepseek*（anthropic 面） | `thinking:{type:enabled}`（budget 官方忽略） | `thinking:{type:disabled}` | `output_config.effort`（门关：无送达面） |
| cmdcode（openai 协议，实况） | 顶层 `reasoning_effort`（budgetToEffort；effort 配置则直发三档映射值） | 省略 | openai 面语义 |
| 其它/claude（Budget 兜底） | `thinking:{type:enabled,budget_tokens:N}`（既有形态逐字保留） | **省略键**（`thinking:null` 消灭） | schema 预留（真 Anthropic 接入时启用） |

显式 `thinkingKind` 配置永远赢（W14）；per-model/provider 层 `enabled=false` 只能降不能升（master off 不可被 layer 复活）。

## 3. 探测定性结论（14 探针；原文脱敏 = `reports/20261029_thinking-config-probe-evidence.md`）

1. **glm 疑点（§6-2）**：budget==max_tokens 不被拒（无 Anthropic 不变式校验）+ 生产 1020 turn max outputTokens=20760<32000 ⇒ **无饥饿证据**；第一段起 glm 不发 budget，疑点不可达。族帽 32768 按令未动。
2. **effort 接受度（§6-1）**：zhipu 顶层 reasoning_effort high/medium 200；api.kimi.com k3.1 同 ⇒ 两门点亮。cmdcode `/v1/messages` 400「Model not supported…Use /provider/v1/chat/completions」⇒ **无可服务 anthropic 面** ⇒ deepseek 门保持关。
3. **cmdcode 勘误（§2.4）**：live 为 **openai 协议**——budget_tokens 从未在 cmdcode 链发出；passback 风暴结构性不可达。
4. **新发现**：k3.1 旧 budget 形态在 api.kimi.com/coding **40s 零字节超时×3**；无 thinking 键/顶层 effort 秒回 ⇒ 第一段 K3 变更的直接佐证。
5. **kimi-for-coding disabled=200**（K2）：该端点比 platform.kimi.com 官方 k2.7 文档宽松；`thinking:null` 修复定性为「形态收敛」而非救火。

## 4. cmdcode 双保险（§6-4）

代码面：缺省判据 `providerId=="deepseek"` → 「providerId 或任一模型 id 命中 DeepSeek 族」（`ThinkingFamily.requiresPassback`，I5/I5b 钉）。live 面：`nebflow.json` cmdcode 节显式补 `"requireThinkingPassback" : true`（+42 字节单行插入，前缀/后缀字节逐一验证未动；备份 `/tmp/nebflow.json.backup-thinking-20261029_000405`；openai 协议下惰性，未来切 anthropic 协议即预装保护）。引擎未重启。

## 5. 验证证据

- **thinking 专项 spec 47/47 绿**（`AnthropicThinkingWireSpec` + `ThinkingInheritanceSpec` + `OpenAiThinkingEffortSpec` + 既有 `AnthropicThinkingReplaySpec` 回归绿）。
- **checkJs 257 < 260 基线 PASS**（新文件 `modelThinkingSelect.js` 零错误）。
- **全量 suite（6 chunk，每 chunk 独立 sbt JVM + MaxMetaspaceSize=3g）**：shared+llm 503/504（唯一红 = `DataRootPlaceholderSpec`）；gateway/cli/agent chunk 10 红。**11 红全部逐一定性为 main 基线既有红，与本批零交集**：
  - 端口漂移（main 10-25 起 `GatewayPort=6138`，spec 仍钉 8080）：`HealthRoutesSpec`（base 字节级取证：`git show cb0d2f75d` 两文件即 6138/8080 分歧）、`StatusCommandSpec`、`DataRootPlaceholderSpec`；
  - L2 rebrand 字面（"nebflow"→"stand"）：`ConfigEditHeadlessSpec`、`ProviderCommandSpec`；
  - 环境依赖：`DetachedApplySpec`（launchctl uid 空）、`EmptyShellNotifySpec`（judge 判词）；
  - 源扫描漂移：`UserTextGateSpec`×2、`PasteAttachmentGuardSpec`、`SubAgentInboxMirrorSpec`（emitInjectedUserEvent 计数）、`NfFileDenyLogSpec`×2（票判据）。
  - 判定依据：11 个 spec 文件与其被钉的生产主体文件**均不在本批 diff**（`git diff cb0d2f75d HEAD --name-only` = 本批 8 功能文件），测试结果 = f(spec, 主体, 环境)，两输入逐字节不变 ⇒ 结果与 base 相同；其中端口/rebrand 两类已直接 base 取证。
- 本批文件 scalafmt 干净、scalafix 零违规（项目级 lint 失败均为 main 既有状态）。

## 6. 遗留 / 边界

- 引擎未重启：新 wire 形态/门位/面板写路待下次重启或配置热加载生效（纪律：零触碰在产引擎）。
- `output_config.effort`（deepseek anthropic 面）无送达面（cmdcode 无 anthropic 面、官方端点无凭据）——门保持关，接新端点时按官方文档+探测置真。
- 档位「接受 ≠ 行为有效性实测」（16-token 极小请求测不了思考深度）；点亮充分条件 = 官方原生面语义 + 兼容面接受。
- 11 个基线红属各自主批的收尾债（端口/rebrand/源扫描），不在本批外科手术面内，建议另派收尾批。
- Anthropic 预留（任务项 6）：schema effort 字段已入（全局/层节点），`type:"adaptive"` 不实现（§6-6，无此 provider）。
