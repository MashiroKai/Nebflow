# 模型选择顺序节精简批 · 批次报告

作者令 2026-11-01 17:23（前派因上游模型 provider 故障阵亡，本批重派；实拍
`~/.stand/uploads/…/674002411036583_截屏2026-11-01 17.21.40.jpg`）。

> ① 刷新列表 / 手动输入 / 清空链三交互整块移除（handlers / i18n 键 / 样式全清，不留死代码）；
> ② 只保留「添加模型」；③ 面板标题「模型回退链」→「模型选择顺序」；④ 标题下说明压到一行
> 以内（超长删繁就简，不再折行）；⑤ 三行模型行排版、拖拽/删除、其余设置面板零触碰。

## 一、改动面

| 件 | 处置 |
|---|---|
| `js/modelPanel.js` `buildChainEditor` | 删「刷新列表」钮、「手动输入」钮 + `.mp-manual` 输入框及其 click/keydown 处理器；`.mp-addrow` 只剩 `.mp-add-select` 一件 |
| `js/modelPanel.js` `buildNebulaChainFace` | 删「清空链」foot（`mp-clear` 钮 + `__showConfirm` 腿 + `putChain('Nebula', null)`） |
| `js/modelPanel.js` `refreshModelList` / `flashManualInput` / `probedModels` | **整块删除**（唯一消费方 = 已退役的刷新钮）；`getAllModelRefs()` 回到单源（configured providers 的模型表），picker 数据面不变 |
| `js/locales/{zh-CN,en}.js` | 删 6 键：`model.refreshList` / `model.refreshFailed` / `model.manualInput` / `model.manualPlaceholder` / `model.clearChain` / `model.clearChainConfirm`；改：`settings.modelChainTitle` = 模型选择顺序 / Model selection order，`settings.modelChainSectionHint` = 「首选在前，拖动排序即存，与 /model 同步。」/「First row is primary — drag to reorder, saves on change.」 |
| `css/modal.css` | 删 `.mp-manual` 规则（唯一消费方已退役）；`.mp-addrow` / `.mp-add-select` / `.mp-foot` 原封保留（其余消费方仍在） |
| `tests/config-drill-unify.spec.mjs` | U2「手动输入加模型」腿 → 下拉 `selectOption` 腿（夹具补 `glm/glm-4` 链外模型）；U2 新增「三交互零复活」DOM 钉；U3 同步腿改 `change` 事件直驱 |
| `tests/model-chain-slim.spec.mjs` | 新建双引擎独立 spec（`node tests/model-chain-slim.spec.mjs`，`ENGINE=chromium\|webkit` 可单跑） |

零触碰：三行链行的 `chainRowsHtml`（行族/手柄/位次徽标/时段闸/灰显）、拖拽与删除处理器、
`/model` 面板其余面（模式钮、follower 分叉/恢复跟随、降级面）、设置页其它节。

## 二、验证（197 PASS / 0 FAIL）

`node tests/model-chain-slim.spec.mjs`（双引擎 Chromium+WebKit）：

| 面 | 帧数 | 判据 |
|---|---|---|
| 桌面 1440×900 × 深/浅 × 双引擎 | 8 | 标题双语、说明单行、三交互零残留、只余「添加模型」、三行链原样 |
| 手机 390×844 × 深/浅 × 双引擎 | 8 | 说明最窄 326px 盒宽下自然宽 236px（zh）/315px（en）——不折行、不被截断 |
| 英文腿（桌面 + 手机，双引擎） | 4 | 双语键在册、`Add model` 首项、位次徽标 Primary/Fallback |
| 功能腿（Chromium 桌面暗色） | — | 删除 / 手柄拖拽排序 / 下拉加模型三路真点真存（PUT 载荷逐字节断言） |

- **④ 实测量值**：说明块 `height = line-height = 18px`（单行），`naturalWidth ≤ clientWidth`、
  `scrollWidth = clientWidth`（零折行零 ellipsis 截断），双引擎 × 双主题 × 桌面/手机全一致。
- **①② DOM 钉**：`#model-chain-section` 内 `.mp-refresh / .mp-manual-toggle / .mp-manual /
  .mp-clear` 计数全 0、节内 `<button>` 计数 0、`.mp-addrow` 子元素唯一 = `.mp-add-select`。
- **i18n**：新旧键 zh/en 双向核对（6 退役键两侧零残留；标题/说明双语在册；删键对称——两字典
  存量差 7 键与本批无关，未变动）。
- **零 console error / 零 pageerror**：11 腿全清（NOISE 仅滤 favicon/网络层/ResizeObserver 既有音）。
- **既有守卫**：`playwright test tests/config-drill-unify.spec.mjs` **6/6 通过**（U1 生图下钻 /
  U2 链节加模型 / U2b 拖拽 / U3 双向同步 / U4 seed fork / U5 降级面）。

## 三、口径与假设

1. **退役面 = 共享内容面**：设置页节与 `/model` 面板的 Nebula 链面是同一
   `buildNebulaChainFace`（历史「逐字节同源」口径），故三交互在两侧同时退役——这是「不留
   死代码 + i18n 键全清」的唯一自洽解（保留一侧就得留键留样式）。
2. **Nebula 显式链回 seed 态的入口随之消失**：清空链钮是 Nebula 侧唯一回 seed 的 UI 路径；
   follower 侧「恢复跟随」保留（`putChain(role, null)` 通道未动）。若作者仍要保留该能力，
   建议单开一件（如把回 seed 收进独立配置钮的二次确认），不在本批夹带。
3. **`POST /api/provider/models` 未动**：服务商配置弹窗走自己的 `providerModels.js` 探针，
   本次删除的只是 modelPanel 内的重复实现。
4. 截图证据 `evidence/20261101_model-chain-slim/`（11 帧，deviceScaleFactor 2）。
