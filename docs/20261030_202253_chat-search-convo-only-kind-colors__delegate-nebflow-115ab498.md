---
type: design
status: implemented
---

# 聊天记录搜索优化批：只搜对话 + 用户/AI 异色（2026-10-30 作者令）

## 裁定

① 搜索结果只搜对话（用户/AI 消息），排除工具消息——tool call/tool result
不入索引、不入结果。② 结果列表里用户消息与 AI 消息用不同颜色区分，跟随
面板明暗主题 token，对比度达标。

## 最小改法（现状 → 变更）

### ① 排除工具消息

| 面 | 位置 | 改法 |
|---|---|---|
| 索引抽取 | `ChatSearchAdapter.extractDocs` | `UiMessage.Tool` 不再产出 `kind="tool"` 文档；其 Pop 载荷仍产出 media/file/link 条目（工件面数据源不动） |
| 查询出口 | `ChatSearchRoutes` | kinds 参数剥除 `"tool"`（剥后无谓词 ⇒ 503 回落旧全扫）；出结果前再过滤旧库残留的 tool 行（total 按页内剔除数减扣，残留随会话重建收敛为零） |
| 前端快路 | `chatSearch.js SEARCH_KINDS_ALL` | `['message','tool']` → `['message']` |
| 前端兜底 | `chatSearch.js runSearch` | 关键词匹配循环 `kind==='tool'` 跳过（与快路同口径） |
| 手机角色档 | `mobile/searchFace.js KIND_OPTIONS` | 删「工具」档（常驻空档不再列出；桌面工具 select 保留——浏览流仍按工具过滤） |

**不动**：浏览流（游空档非搜索，tool 行照列）；media/files/links 分类 tab
（popMediaIndex 条目 ≠ tool call/result 文本）；浮窗定位机制度（浏览流点击
tool 行仍可达）；通用 `SearchIndexService`（tool 片能力保留，chat 面不再消费）。

### ② 用户/AI 异色

`base.css` 新增 `--search-kind-user/-bg`、`--search-kind-ai/-bg` 四枚 token
（明暗双档；徽标 10px 小字按 AA 4.5:1 判读：亮 user 5.4 / ai 5.2，暗
user 8.6 / ai 6.1；旧 sapphire 单值亮 4.0 暗 4.1 顺带抬达标）；`modal.css`
按既有 `type-{kind}` 类上色：user = 微信绿族压深，ai/agent = sapphire 族
（agent 属 AI 侧同色），tool 徽标沿用默认 sapphire 不动。`resultElement`
markup 零改动（`type-*` 类本就是 QA 断言面）。

## 判据面

- Scala：`ChatSearchAdapterSpec`（tool 行不入索引、Pop 条目照产）、
  `ChatSearchRoutesSpec`（kinds=tool ⇒ 503；残留 tool 行过滤 + total 减扣）、
  `SearchIndexServiceSpec`（通用面不动，原样全绿）。
- 前端：tests/search-realshape.spec.mjs T1/T3/T4（2 条结果 + 排除断言）、
  mobile-p2-search T2/T3（角色档 4 项、2 条结果）、pr41（换代：tool 关键词
  0 结果 + 浮窗 user 定位 zh/en 两腿）、card-persist-fe-search（换代：E3-6
  工件面迁「文件」tab，退化条目判别更强）。
- i18n：无新增文案（zh/en search.* 53 键零漂移）。

## 挂账

- `msg-search-v3-smoke.mjs` 的 `A4 Enter closes modal` 在基线即 FAIL
  （v2 浮窗裁定后 Enter 开浮窗不关面板，smoke 期望未跟进）——非本批引入，
  未修（反增额）。

关联：chatSearch.js v3.x 头注沿革（datalayer-spec 批 C1-C6）、[[20261030_191500_reader-p0-implementation__delegate-nebflow-a1e28e75.zh-CN]]（同批并行线）。
