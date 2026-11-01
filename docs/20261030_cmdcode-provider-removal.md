---
type: report
status: done
---

# cmdcode provider 摘除与换 key 重新启用说明

- 状态：current（2026-10-30 执行并验证）
- 适用：本机实例（gateway PID 63637，端口 6138）；插件仓面背景见 [[20261029_175151_plugin-repo-registry]]

## 1. 已执行动作（摘除落点）

厂家侧 key 已失效（设置面板 provider test 报 HTTP 401）。处置 = 停发请求、保留记录：

| 落点 | 动作 | 证据 |
|---|---|---|
| `~/.stand/nebflow.json` → `llm.providers.cmdcode` | 增补 `disabled: true`、`disabledNote: "厂家重置 key 后启用"`；记录整体保留（baseUrl/apiKey/protocol/models 逐字不动） | PATCH `/api/config` 200 `{"updated":true}` |
| `~/.stand/agents/Nebula/agent.json` | `model.fallbacks` 删除 `cmdcode/deepseek/deepseek-v4.1-flash`（备份 `agent.json.bak-20261030-cmdcode`） | 改后 chain = preferred `zhipu/glm-5.3-flash` + fallbacks `kimi/kimi-for-coding` |
| `~/.stand/model-presets.json` → `presets.general` | `fallbacks` 删除 cmdcode 兜底，description 注明摘除原因（备份 `model-presets.json.bak-20261030-cmdcode`） | 改后 fallbacks = `["kimi/kimi-for-coding"]` |

全量扫描确认：改后**无任何活动链引用 cmdcode**（agents/*/agent.json、model-presets.json、model 链审计脚本输出 NONE）。

## 2. 生效方式（热读结论）

- **provider 摘除请求路径 = 热生效，无需重启。** 证据：`ConfigRoutes.scala:303` 与 `WsConfigHandlers.scala:1008`——REST `PATCH /api/config` / WS `updateConfig` 落盘后**同帧**调 `ProviderRegistry.reloadConfig`，`configRef` 整体换新 + adapter 缓存清空 + 失效 session override 丢弃（`registry.scala:483-507`）。本次 PATCH 走的就是这条路径。
- ⚠️ 已知残留（低危，面板面）：`registry.scala` 的**储备层** `reserveTier`（约 :446-468）与 `getCandidatesUnfiltered` 的全局链构建**不读 `disabled` 标记**（引擎尚无该字段的语义支持，配置按 circe 宽松解码忽略未知键）⇒ cmdcode 模型在「面板候选列表」里仍可能出现；但**请求路径**（`LlmInterface` 单发链）已不含它（Nebula 链 + preset 链均已摘除），所以**不会向厂家发请求**。储备层构建不读 disabled 属引擎层缺口，列为开放项（§5）。
- `agents/Nebula/agent.json` 与 `model-presets.json` 的链改动：请求路径**每请求读盘**（`SchemePolicy.resolveModel` 读 agent.json；`getCandidatesUnfiltered` 注释明载 "/model edits apply on the next call"）⇒ 下一个请求即生效，同样无需重启。

## 3. 换 key 后重新启用（操作步骤）

厂家重置 key 后，按序执行：

1. **更新 key 并摘掉 disabled**：对运行中的 gateway 发一次 PATCH（`Authorization: Bearer $(cat ~/.stand/auth.json | tr -d '"')`，body 的 `config` 内嵌整 provider 对象，携带新 `apiKey`，**去掉 `disabled` 与 `disabledNote` 两键**——注意 ProviderConfig 校验要求 baseUrl/protocol/models 全量随帧携带，仅发部分字段会被拒：实测报 `Base URL is required; Protocol is required; At least one model is required`）：

   ```bash
   curl -s -X PATCH http://127.0.0.1:6138/api/config \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"config":"{\"llm\":{\"providers\":{\"cmdcode\":{\"requireThinkingPassback\":true,\"baseUrl\":\"https://api.commandcode.ai/provider/v1/\",\"apiKey\":\"<新key>\",\"models\":[{\"id\":\"deepseek/deepseek-v4.1-flash\",\"contextWindow\":1000000,\"modelMaxContext\":1000000}],\"protocol\":\"openai\"}}}}"}'
   ```

   PATCH 走 `updateConfig` 深合并：仅提及的键被改写，未提及的 provider（zhipu/kimi）逐字不动。响应 `{"updated":true}` = 已热生效。

2. **（可选，若该模型要回链）把 cmdcode 加回分配候选**：
   - 全局链：`~/.stand/model-presets.json` 的 `presets.general.fallbacks` 末尾追加 `"cmdcode/deepseek/deepseek-v4.1-flash"`；
   - 按 agent 链：`~/.stand/agents/<Name>/agent.json` 的 `model.fallbacks` 同样追加。
   两处都是每请求读盘，追加后下一请求进链。

3. **连通性自检**：设置面板 LLM 服务商页对 cmdcode 跑 provider test（REST `POST /api/provider/models`），应列模型而非 401。

> 误伤防线：provider 键名按 **id 精确匹配**（`cmdcode`），与其它 provider（zhipu/kimi）零交集；模型 ref 形如 `cmdcode/deepseek/deepseek-v4.1-flash`，全库扫描确认无同名不同 provider 的碰撞。

## 4. 回滚

- `~/.stand/nebflow.json`：删除 `llm.providers.cmdcode` 的 `disabled`/`disabledNote` 两键（或再发一次 PATCH 全量对象不带这两键）；备份 `nebflow.json.bak-alloc-key-20261019` 为更早形态，勿直接回拷（会丢后续配置）。
- `agents/Nebula/agent.json` ← `agent.json.bak-20261030-cmdcode`；`model-presets.json` ← `model-presets.json.bak-20261030-cmdcode`。

## 5. 开放项

- 引擎层 `disabled` 语义缺口：储备层/全局链构建不读该标记（§2），面板候选列表可能仍显示 cmdcode。属引擎代码改动，不在本运维批范围——如要根治需在 `ProviderConfig` 增字段并在 `reserveTier`/链出口过滤，单独立批。
