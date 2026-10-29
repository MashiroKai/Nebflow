# settings-panel-fourfix 批报告（作者 2026-10-29 10:35–10:36 两张截图报障 + 口头指令，console 渠道）

分支 `delegate/delegate-nebflow-73f5fdd8`；四件独立 commit；在产实例（:8080/:6138、`~/.stand`）零触碰——LIVE 验证全部走隔离实例（`/tmp/nb-fourfix-home`，:8097，seal=ON 自建构建）。

## ① 思考配置收归全局（commit d59f3a6b1）

- 根因：thinking-config Phase 2（2026-10-29 晨）把思考档位做成了每模型行内下拉 + 「模型强制开启思考」徽标——作者令「不要每个模型都能配置思考模式，思考档位只在设置里统一的一个地方配置」。
- 改动：删 `js/sidebar/modelThinkingSelect.js` 整件；模型行只剩 名称+vision 位（无任何 select/徽标）；CSS 块与两份 locale 的 per-model 五键同步退役；全局唯一配置点 = 设置页「思考模式」`#cfg-thinking-effort`（写路 `setThinking` 全局键，引擎腿不变）。强制思考徽标一并移除（信息展示位随配置件退役，行面归简）。
- 钉：`tests/settings-thinking-global-only.spec.mjs`（G1 模块文件不存在 / G2 模型行模板零 select / G3 locale 零键 / G4 全局配置点在位且写路为全局键）。
- 证据：`evidence/20261029_121500_…/BEFORE-authors-llm-providers-page.jpg`（改前）vs `live-ui/F1-provider-cards-after.png`（改后 LIVE）。

## ② 连接性测试钮：归位 + 结果可见化（commit c4b1546c4 + 031b9cde0）

- 根因（显示面）：测试钮在设置页 provider 卡模型行（层级不对）；结果进行内 220px 摘要位（`text-overflow: ellipsis`）→ 「HTTP 401: Invalid…」截断；测试中只有钮内小 spinner。
- 改动：测试钮迁入 provider 编辑弹窗（每模型行尾一枚）；弹窗级结果块 `.cfg-test-results` 每模型一行——绿 ✓+延迟 / 红 ✗+**全量错误文本换行不截断** / 测试中 spinner+「测试中…」；弹窗级委托绑定（动态加行覆盖）；`POST /api/provider/test` 增可选覆写键 `baseUrl/apiKey/protocol`（弹窗表单现值可测未保存配置/未入库 provider；存储腿旧形状逐字兼容；脱敏池 = 覆写 key + stored key 双池；单飞闸与 20s 有界超时不变）。
- 钉：`tests/provider-model-test-button.spec.mjs` 重写（T1 归位 / T2 不重入 / T3 绿+延迟 / T4 全量不截断 / T5 表单覆写形状 / T6 存储腿旧形状）。
- 证据：`live-ui/F2-provider-test-{zhipu,kimi,cmdcode}.png`（LIVE，API Key 带已脱敏遮盖）。

### cmdcode HTTP 401 诊断结论：**key/账户态在 provider 侧失效，非代码缺陷，未硬修**

- 测试钮实际发的请求 = openai 面 `{base}/chat/completions` + `Authorization: Bearer <stored>` + 1-token 最小体——与 2026-10-29 晨 `reports/20261029_thinking-config-probe-evidence.md` C1/C2 **逐字同形**（当时 200，`created:1793203624` ≈ 00:07）。
- 今日（10:47 起）同请求 401 `Invalid 'Authorization' header or token.`；排查矩阵：`GET /models` + 同 Bearer = **200**（key 字串本身被接受）；`/chat/completions`、`/responses`、`/v1/messages` × {Bearer, x-api-key, 双头, 裸 Authorization, 浏览器 UA, max_tokens 1/16, 多模型} = **全部 401**；端点路径经厂家 404 报文与昨日 C3 报文双证正确。
- 判定：key 对列举有效、对推理一律拒绝 ⇒ provider 侧 key 权限/配额/账户态变更（昨绿今红、请求形状零 diff）。修到绿需作者在 cmdcode 侧重置 key/套餐后把新 key 录入 provider 配置页——新结果块会完整显示成功或新的可诊断错误。

## ③ 生图配置面板重做（commit 7533cf068）

- 根因：初版面板是引擎内部视角——keyRef 指针行 + 「keyRef 是 secrets/ 下的密钥名（指针）…」解释、「graceful degrade，无半可用态」长文案、「保存密钥/保存」双钮、providers 空时无任何新增入口。
- 改动：一枚「保存」走完「填 API Key 值 → 保存」；keyRef 指针名对用户不可见（引擎投影携带的原样沿用，缺席按提供方名派生 `imagegen-<name>-key`，secrets 写面/整节 PUT 语义后端零改动）；提供方通用化（既有下拉 + 「＋新增提供方…」全空新增面 + 「删除此提供方」两段式确认）；locale 整链清除 keyRef/指针字眼与 engine 措辞，留一句「填入 API Key 并保存即可；未配 key 时生图停用。」。
- 钉：`tests/settings-imagegen-two-forms.spec.mjs` 重写（B2 DOM+locale 零 keyRef 字眼 / B3 新用户全空且零 zhipu/cogview-3-flash/zhipu-api-key 值 / B4 派生密钥名保存形状 / B5 重录 / B6 整节保留 / B7 删除 / B8 徽标单源）。
- 证据：`BEFORE-authors-imagegen-page.jpg` vs `live-ui/F3-imagegen-new-user-blank.png`（新用户全空 LIVE）+ `live-ui/F3-imagegen-configured.png`（配置后 LIVE）。

## ④ 生图可用性实测（LIVE）

- keyRef 考古：`imagegen.providers.zhipu.keyRef = zhipu-api-key` 在 `~/.stand/secrets/` **无值**（面板「待录入」属实）；key 值本体在 LLM 腿 `llm.providers.zhipu.apiKey`（在位）。密钥值全程零打印/零落报告。
- 隔离实例（拷贝 provider 配置 + 播种 `secrets/zhipu-api-key` 0600）端到端：**cogview 走后端 LIVE 成功**——`POST /api/imagegen` → HTTP 200，`/tmp/nb-fourfix-home/imagegen/mcp-1793246431309-74555.jpeg`（74,555 字节，JPEG magic 实证），耗时 **9.5s**；nf-file 票据链取回同字节（image/jpeg，前端 `<img>` 取图同一条路）。产物拷贝 = `live-ui/F4-imagegen-live-output.jpeg`。
- 前端占位卡换真图链（腿① WS 帧）本批未改动，未在根会话驱动；后端生成、落盘、票据服务三段 LIVE 已验证。

## 验证台账

- checkJs：256 < baseline 260 PASS；check-provider-modellist：5 OK。
- playwright：thinking-global-only 4/4、provider-model-test-button 6/6、settings-imagegen-two-forms 8/8（截图随 evidence 入库）。
- scala：`ProviderModelProbeSpec` + `ImageGenSettingsSpec` 12/12 PASS；`sbt compile`（seal ON）PASS。
- LIVE e2e：`tests/settings-fourfix-live.mjs` 14 检查点全 PASS（隔离 :8097；模型行零控件、三家五模型测试钮 LIVE、新用户全空、UI 配置流落盘、生图端到端）。
- 根巡检：主库 home RED「白名单缺失」为**既有状态**（本批零写入主库 `.nebflow`）。
