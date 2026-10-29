---
type: design-note
status: implemented
---

# 模型不可用时段规则 spec（fallback 链时段闸 / model-schedule-unavailable）

- 批次：model-schedule-unavailable 批（2026-10-29 17:44 作者令逐字：「能不能让我们使用模型时的fallback链支持更复杂的规则？比如下午2点到6点，我不想用zhipu和kimi的模型，因为会加倍收费。应该设置一个，模型不可用时间规则就行了。设计好了顺便落实。」）

相关件：[[glossary]]（fallback 链 / 候选链术语出处）

## 1. 需求语义

给 provider / 模型配「不可用时段」规则：时段内该模型视为**计划性不可用**，fallback 链构建时直接剔除（主模型进窗 ⇒ 请求落到链上下一个候选，对受限 provider **零请求**）。首个用例 = 14:00–18:00 禁用 zhipu + kimi（加倍收费时段），cmdcode（deepseek）照常承接。规则可多条、provider 或模型粒度、支持跨午夜窗口（23:00-07:00）与星期选择。

与既有机制的分界：

| 机制 | 语义 | 与本批关系 |
|---|---|---|
| 健康层 markDown / 软回避窗（ProviderHealthMonitor） | **被动**：失败后的驱逐/回避，恢复靠探测 | 本批是**主动**时间闸，先于任何请求；不写健康状态、不进探测集 |
| 冻结调度 workSchedule（FreezeSchedule，#337） | 全局「agent 不发下一轮 LLM」 | 本批只影响**选哪个模型**，不冻结回合；时间数学同构（HH:mm、[start,end)、跨午夜）故判据形态对齐 |
| #1071 在飞批（fallback 链是否随 /model 锚派生） | 候选链**来源**问题 | 本批只做候选链**内容过滤**，与来源正交：无论链来自 agentModel 随行（现行为，modelcfg 批）还是 /model 锚派生（#1071 若落），过滤都踩在「链已解析出」之后 ⇒ 天然组合。截至开工 main tip（b3930acde）#1071 未落任何 fallback 相关 commit |

## 2. 配置面（nebflow.json）

```jsonc
"llm": { "providers": {
  "zhipu": {
    "baseUrl": "…", "apiKey": "…", "protocol": "anthropic",
    "unavailableWindows": [ { "from": "14:00", "to": "18:00" } ],
    "models": [
      { "id": "glm-5.3-flash", "contextWindow": 1000000,
        "unavailableWindows": [ { "from": "23:00", "to": "07:00", "days": [6, 7] } ] }
    ]
  }
} }
```

- 键名 = `unavailableWindows`，挂在 `ProviderConfig`（provider 粒度，对该 provider 全部模型生效）与 `ModelConfig`（模型粒度）两层；**生效窗口 = 两层并集**（同 model 同时命中任一即受限）。
- `UnavailableWindow { from: "HH:mm", to: "HH:mm", days?: [1..7] }`：`days` = ISO 星期（1=周一…7=周日），缺席 = 每天。
- **缺省 = 无限制（零行为变化）**：字段全 Option（`Option[List[UnavailableWindow]] = None`——🔴 形状纪律：`deriveDecoder` **不认 case class 默认值**（ensureLlmDefaults 批实测教训，本批首版用 `List = Nil` 即抛 Missing required field，被链家族 spec 当场拦下后改 Option 形），存量 nebflow.json（无本字段）解码逐字节不变；无 Encoder（config 走 raw-JSON 深合并写路径，同 `vision`/`maxTokens` 先例）。
- 深合并兼容性（关键工程事实）：`ConfigService.mergeConfig` 对**对象键**「incoming 未提及 ⇒ 保留既有」——provider 编辑弹窗保存只重建 `{baseUrl, apiKey, protocol, models}`，provider 级 `unavailableWindows` 在深合并下天然存活；`models` 是**数组**（整体替换），模型级 `unavailableWindows` 必须由弹窗保存路径显式携带（§6），否则每次 provider 保存都会静默抹掉。

## 3. 判定层（单点谓词）

`nebflow.shared.ModelAvailability`（纯函数，注入 now，时区 = 本机时区 `ZoneId.systemDefault()`，与 FreezeSchedule 同侧）：

- `parseHHmm`：`^([01]\d|2[0-3]):([0-5]\d)$` → 当日分钟数（与 `FreezeSchedule.parseHHmm` 同形；shared 不依赖 core，故复制不引用，注释注明同源）。
- 段判定（分钟粒度，**[from, to)**——14:00 整即禁、18:00 整即解）：
  - `from < to` 常规段：`today ∈ days ∧ from ≤ m < to`；
  - `from > to` 跨午夜段（23:00-07:00 = [23:00,24:00)∪[00:00,07:00)）：`(today ∈ days ∧ m ≥ from) ∨ (yesterday ∈ days ∧ m < to)`——清晨腿归**昨天**的星期（周一 23:00 开窗 → 周二 06:59 仍禁，判据 = 昨天是周一）；
  - `from == to` 非法（零长度；也**不**定义为 24h 全禁——误配即全禁过于危险，同 FreezeSchedule D6 口径）：fail-safe 永不命中；
  - 任一端解析失败：该条 fail-safe 永不命中（非法配置 = 关闭该条，不抛错不挡启动）。
- 多条规则取并集；provider 级 ∪ 模型级。
- `isUnavailable(windows, now)`：单点谓词；`describeWindows` 产出 "14:00-18:00" 形态的可读串。

## 4. 接入链（fallback 链构建时剔除）

**双出口形态**（端到端 spec 逼出来的修正：全受限终局判定需要完整受限清单——若链构建先滤，接口只见到空链，流面会落进 120s 全灭闸等待而非带原因快失败）：

- **请求路径（唯一裁决点）= `LlmInterface.send / sendStream`**：取**未滤** agent 链（`getCandidatesForAgentUnfiltered`，含储备层）合并会话 override 后，`applyScheduleGate` 单点过滤——窗内候选剔除、**链序原样保留**、受限候选单行 WARN、**全受限才显式报错**（`ModelScheduleRestrictedError`，带窗口与受限清单）。会话级 override 同闸拦下（否则 13:50 选中的 override 会在 14:00 后继续打受限 provider，恰是作者要避免的加倍收费）。
- **读面出口 = `ProviderRegistry.getCandidates() / getCandidatesForAgent()`**：过滤后交付（`/model` 面板 `current`、判定机缺省腿、HealthRoutes 等只读消费方）——`current` 天然只反映未受限模型。
- **判定机 utility ref 补滤** = `LlmAllocator.candidateFrom` / `DelegateJudgeMachine.utilityCandidate`：显式 ref 命中受限 ⇒ WARN + 落到 `getCandidates().headOption`（已过滤的全局链首选）。
- `getCandidateForRef` 保持**纯构造**不过滤——`HealthMonitor.downCandidates()` 的探测集靠它取回候选；若在此过滤，窗内被标 Down 的模型将永远无探测恢复路径。

### 全受限策略（作者令倾向，采纳）

仍按链序尝试未受限模型（过滤即此语义）；**候选全受限（含储备层）才显式报错，不静默**：`ModelScheduleRestrictedError`，消息带原因与窗口（「当前时段（14:00–18:00）全部候选模型受限：zhipu/glm-5.3-flash、kimi/kimi-for-coding」）。流面在健康检查**之前**快失败（不进 120s 全灭闸等待——时间闸等不来，同配额类「等不如换」逻辑；全受限时换无可换才报）。错误分类落 Unknown/Transient ⇒ agent 层既有 llm-fail 重试（MaxTurnLlmCalls）内每轮重过闸——18:00:01 的重试自然成功，这是时间闸期望的自愈形态。

## 5. 可见性（不静默）

- **日志**：请求过滤掉任一候选时单行 WARN（`schedule-restricted skipped: zhipu/glm-5.3-flash (14:00-18:00), …`）；全受限错误消息自带原因。
- **REST**：`GET /api/agents/:name/model` 增 `scheduleRestricted: [{ref, windows: ["14:00-18:00"], active: bool}]`（effectiveChain 逐 ref；active = 当前在窗内）。`current`（healthy.head）经链过滤后天然只反映未受限模型。
- **/model 面板**（modelPanel.js）：链行（编辑器）对 active 受限 ref 灰显（复用 `.mp-row-unavailable`）+ title 说明窗口；链 chips 与添加下拉对配置了窗口的 ref 标注「14:00-18:00 不可用」。i18n 键 zh-CN/en 双份。
- **设置面板**：provider 编辑弹窗增「不可用时段」文本框（`14:00-18:00` 逗号分隔多条，跨午夜直接写 23:00-07:00）；保存时解析校验（非法 → toast 拒存，同 validateContextWindow 先例），清空 = 显式 `null`（深合并删键）。模型行的 `unavailableWindows` 保存时按 id 原样携带（防数组整体替换抹掉，§2）。

## 6. 改动面

| 层 | 文件 | 内容 |
|---|---|---|
| 配置 | `shared/config.scala` | `UnavailableWindow` + 两层字段（decoder 派生） |
| 判定 | `shared/availability.scala`（新） | 谓词 + `ModelScheduleRestrictedError` |
| 链 | `llm/registry.scala` | `scheduleWindowsFor` / `filterScheduleRestricted` / `scheduleRestrictionFor(ref)`；两条链构建出口过滤 |
| 接入 | `llm/interface.scala` | send/sendStream 合并列表补滤 + skip 日志 + 全受限快失败 |
| 接入 | `llm/decision/LlmAllocator.scala` `DelegateJudgeMachine.scala` | utility ref 受限回落 |
| REST | `gateway/PresenceRoutes.scala` | model 链读面增 `scheduleRestricted` |
| 前端 | `web/js/modelPanel.js` `sidebar/providersUi.js` `locales/{zh-CN,en}.js` `css/sidebar.css` | 灰显/标注 + 弹窗字段 + 携带 |
| spec | `shared/ModelAvailabilitySpec.scala` `llm/ScheduleRestrictionChainSpec.scala` `llm/ScheduleChainSwitchSpec.scala` | 谓词纯函数 / 链过滤 / 端到端换链三跑 |

## 7. spec 与实测口径

- 谓词：边界（14:00 含 / 18:00 不含）、跨午夜两腿（含星期归属）、多规则并集、days 过滤、非法/零长 fail-safe、缺省零限制。
- 链过滤：窗内剔除且**序不变**、窗外零变化（缺省零行为铁律）、provider 级 vs 模型级、储备层承接、全受限形态。
- **端到端（作者实测令的对价）**：真实 `LlmInterface.sendStream` + 真 Anthropic 协议 mock（QuotaChainSwitchSpec 同台）：窗口设成「当前时刻 ±10 分钟」（运行时计算）⇒ 受限 mock 服务 **HTTP 命中数 = 0**（绕过的决定性读数）且答案由不受限方交付；全受限 ⇒ <3s 快失败且错误文本带「受限」；窗外 ⇒ 受限方照常承接（零变化腿）。
- 回归：链家族既有 spec（ProviderChainDepth / QuotaChainSwitch / FormatErrorNoEvict / HealthMonitor / AllCandidatesFormatLoop）全绿 = 缺省零变化证词。

## 8. 边界与不做

- 不做秒级/日期区间窗口（HH:mm + 星期足够覆盖用例；需求出现再立批）。
- 不做 per-agent 时段差异（窗口是 provider/模型属性，全 agent 一致——与「加倍收费」语义同构）。
- 不动 Jev/搜索/embedding 等非候选链 LLM 面（各自 provider 配置独立；需求出现另行接入同一谓词）。
- 面板不做「时段内倒计时/自动刷新」——重开面板/下一请求即现读（可见性口径 = 现读非预测）。

## 9. 跨批证据补充（2026-10-29 18:5x 作者令；fail-open 相关性对齐——只读取数，归属双模式批 52fbf252）

作者证据补充令：fail-open 根因方向修正（撞上 provider 限流）+ 与本批合流判定。本节点做了只读对齐（零写入零触碰）：

- **429 风暴窗（真源 = `~/.stand/logs/stand.log`）**：zhipu/glm-5.3-flash 账户级限流两段——16:3x 段（429 **code 1308** 计划性额度闸：「已达到 5 小时的使用上限…18:06:52 重置」，16:33:31 kimi 同窗 RateLimit）与 18:30–18:49 段（429 **code 1302** 频率类风暴，18:44:41 `marked DOWN` + recovered 反复 flap）。
- **fail-open 审计对齐（真源 = `~/.stand/chains/*.json` delegate-spawn 台账，mtime 证时）**：
  - **#1071**（`1071.json`，16:33:28，delegate-nebflow-233e3a44，allocation=fail-open）⇔ 恰落 16:3x 额度闸窗内（同分钟 16:33:47 zhipu 429-1308 permanent + 16:33:31 kimi RateLimit）——**对齐成立**；
  - **#1089**（`1089.json`，18:41:44，delegate-nebflow-8312db45，allocation=fail-open）⇔ 恰落 18:30–18:49 风暴窗内——**对齐成立**。
- 结论：作者假设①（mode-decision LLM 腿在 429 窗内失败被错分类成 fail-open）在两个样本上均获时间窗对齐支持。**②（`provider-429-retryable` 独立 leg 词 + 退避重试穷尽才 fail-open）归属双模式补全批（worktree delegate-nebflow-52fbf252）的分类面，本批不越界实施**；晚高峰复现窗现成（作者令）。
- **③与本批的合流（零新代码）**：「账户级限流 = 该模型此刻不可用」在引擎里**已有动态态**——健康层配额软回避窗（`softAvoid(QuotaAvoidWindowMs)`，403/429-1308 计划性额度）+ markDown + `filterCandidates`，与本批静态时段闸天然叠层（时段闸先滤 → 健康层再滤，两层都先于任何 provider 请求）。把动态回避态喂进 `isModelAvailable` 做统一可见性（面板同灰显）登记为扩展候选，不进本批（会把纯时间谓词耦合到运行时健康态）。
