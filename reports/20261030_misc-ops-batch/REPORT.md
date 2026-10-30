# 杂项运维小批三件 · 批报告（2026-10-30）

- 批次：杂项运维三件（cmdcode 摘除 / flowmap KEEP 清理 / design-language 建仓）
- 工作分支：`delegate/delegate-nebflow-dcc13390`，commit `773119052`（仅 docs 两件落仓；实例配置与数据面改动不入 git，符合作者约束）
- 引擎：未重启、未 kill 任何进程；gateway PID 63637 全程存活

## 件一 · cmdcode 死 key 摘除

**摘除落点**（全部本机实例配置，已执行）：

| 落点 | 动作 |
|---|---|
| `~/.stand/nebflow.json` `llm.providers.cmdcode` | 经 REST `PATCH /api/config`（200 `{"updated":true}`）增补 `disabled:true` + `disabledNote:"厂家重置 key 后启用"`；provider 记录整体保留（baseUrl/apiKey/protocol/models 逐字未动） |
| `~/.stand/agents/Nebula/agent.json` | `model.fallbacks` 摘除 `cmdcode/deepseek/deepseek-v4.1-flash`（备份 `.bak-20261030-cmdcode`） |
| `~/.stand/model-presets.json` `presets.general` | `fallbacks` 摘除 cmdcode 兜底 + description 注明（备份 `.bak-20261030-cmdcode`） |

改后全量扫描：**无任何活动链引用 cmdcode**（agents/*/agent.json、model-presets.json 审计输出 NONE）。同名不同 provider 无误伤面：provider 键按 id 精确匹配，全库仅一个 `cmdcode` provider。

**热读结论**：provider 面 = **热生效无需重启**——`ConfigRoutes.scala:303` / `WsConfigHandlers.scala:1008` 的 updateConfig 落盘后同帧 `ProviderRegistry.reloadConfig`（`registry.scala:483-507`，configRef 整体换新 + adapter 缓存清空 + 失效 session override 丢弃）；agent/preset 链面每请求读盘（`SchemePolicy.resolveModel`），下一请求生效。⚠️ 已知低危残留：储备层 `reserveTier`（`registry.scala` ~:446-468）与全局链构建**不读 `disabled` 标记**（引擎无该字段语义，circe 宽松解码忽略）⇒ 面板候选列表可能仍显示 cmdcode，但请求路径单发链已不含它，**不会发请求**。列为引擎层开放项。

**启用说明落点**：`docs/20261030_cmdcode-provider-removal.md`（含换 key 后 PATCH 全量对象重新启用步骤、回滚、误伤防线）。注意：PATCH 只发增量会被 ProviderConfig 校验拒（实测报 Base URL/Protocol/models required）——须全量随帧，已写进说明。

## 件二 · flowmap KEEP 目录清理

**基线**：R3 批（2026-10-29）dry-run KEEP 37 个（manifest `20261029_202740`）。本批逐个对照 delegate 记录 / flow-map 节点 / 台账事件做活性复核（数据面只动本批点名对象，不碰 sessions/logs 其它部分）。

**结果：归档 2 个（mv，不硬删），保留并报 35 个**：

| 处置 | 数量 | 明细 |
|---|---|---|
| 归档 | 2 | `delegate-nebflow-3a220d12`（stand，36,904 B）、`delegate-nebflow-a2d25ad9`（stand，12,964 B）——manifest 原判 `no-record`（无 delegate 记录、无活动节点、无 blocked、无取消请求），本次复核零活会话/活节点/未结任务引用 |
| 保留 | 35 | 逐目录均有活性引用（见下） |

- **归档去向**：`~/.stand/archive/flowmap-keep-20261030/{stand}/<id>/`（数据根 archive 区，mv 非删）。
- **空间回收**：49,868 B（仅 2 个可清项）；清单内 35 个保留项合计 ~8.9 MB 未动。
- **机器清单**：`reports/20261030_flowmap-keep-archive/20261030_flowmap-keep-archive-manifest.json`。

**35 个保留项及原因**（不猜、拿不准即留）：

| 原因类别 | 数量 | 代表 |
|---|---|---|
| `record:not-archived`（delegate 记录未终态归档——ready-to-merge 停靠/待判合并） | 22 | 0b640e55、3a877cec、56930fbd、79cc79eb、7dc83f66、a336d824、c79ba0e3、dca51539、f3af8589、ddbdde82、fe4f891e、af984340、b6c1b0cc、1cc53dfe、52308ee4、3879148f、017653fb、4f58672f、75b0ba04、35b31668、8312db45、e153d31a |
| 终态 finish 但链上分支仍在仓内（合并证据未闭环，勿擅杀） | 13 | 109d8ab6、233e3a44、3ad3478e、52fbf252、6d7097a6、88974c4d（R3 批自身）、a464fdc0、b3020083、b3e88eb0、ca33e103、f7679358、35b31668*、1cc53dfe*（*兼两类） |
| `report:blocked`（合并冲突挂起态，verbatim 记录在案） | 1 | aab5503d |
| `cancelRequested` 未闭环 | 2 | 8312db45、e153d31a |

> 说明：manifest 原判 37 KEEP 中 `3a220d12`/`a2d25ad9` 为「no-record 无主目录」——本次复核仍无记录、无节点、无引用，按作者「可以清」归档；其余 35 个均有上述活性引用，全数保留。与作者「37 个可以清」预期的偏差源于：R3 manifest 是「不擅杀」口径的保守清单，本批逐目录活性复核后只 2 个真正无主——**如实申报，未为凑数清任何有引用项**。

## 件三 · design-language 建仓

- **仓地址**：https://github.com/MashiroKai/design-language （**private**，REST 创建响应 `private:True` 核实）
- **内容源**：种子 `src/main/resources/seed/plugins/design-language/`（Plugin.md、plugin.json v1.0.0、SKILL.md、assets/{tokens.css,components.css}，6 文件全量）——该包 10-29 批仅种子无实例，当时不在 23 仓面，本批补齐
- **推送 commit**：`d7e45a6704fcd94bcea763224aad0e6ef9f98bf9`（main，含 annotated tag `v1.0.0`，peel 解到同一 commit）；远端验证 `git ls-remote` main/HEAD/tag 三 ref 一致
- **registry 文档行**：`docs/20261029_175151_plugin-repo-registry.md` §1a 增补段（仓 URL/private/tag/内容源/导入 commit + gh CLI 因 PAT 权限被拒改用 REST 的偏离申报）
- 凭据：`~/.stand/secrets/github-pat`（0600）env 注入经 7890 代理；**未 push Stand 仓 main**（本批唯一仓内 commit 在 delegate 分支 `773119052`）

## 总体偏离申报

1. 件二归档数 2/37 而非 37/37——逐目录活性复核结论，红线「拿不准留着」执行结果（§件二）。
2. 件一 `disabled` 标记引擎无消费语义（面板候选列表残留）——数据面无害、请求面已断，引擎根治需独立批。
3. 件三 `gh repo create` 被 PAT 权限拒（`Resource not accessible by personal access token`），改用 REST `POST /user/repos` + per-command credential.helper，与 10-29 批 `gh auth token` 路径不同，已在 registry 文档注明。

## 开放项

- 引擎层：`ProviderConfig` 增 `disabled` 语义并在 `reserveTier`/链出口过滤（面板残留根治，独立批）。
- 35 个保留 KEEP 目录：待各自 merge 判合/分支清理后随下轮卫生批再核。
