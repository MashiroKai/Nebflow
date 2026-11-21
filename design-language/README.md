---
type: spec-index
status: active
---

# design-language —— 前端设计规范正文（本仓单源）

本目录是 Stand 前端**设计规范正文的唯一一份**，由 8 道静态闸直接读取（改判据改这里，
脚本只是执行器）。作者裁定（2026-11-21）：「先查源仓 → 其次是落仓内」——退役提交
`af404938c`（2026-11-18）把正文「唯一一份」外置进 stand-dev 插件包，而主线从未收到
（插件 v1.5.0 里一份都没有，8 道闸只能记 SKIP）；本目录把它取回仓内，闸随之实跑。

## 目录

```
design-language/
  references/frontend/          规范条文（7 篇）
    flat-button-spec.md         按钮扁平化 + 统一来源（§5/§6 族登记表）
    glass-spec.md               毛玻璃唯一形态 + §9 运行时判据 R1–R3
    input-surface-spec.md       输入面唯一造型
    panel-spec.md               面板族版式
    disclosure-dedup-spec.md    渐进式披露 · 单一来源无重复
    explanatory-copy-spec.md    解释性文字写作与长度预算
    design-tokens-ledger.md     几何刻度账本（四表 + 迁移清单 + 取值流）
  assets/design-system/
    tokens.css                  账本镜像（`--dl-*`，与运行时 `--ds-*` 逐值对位）
    components.css              固定尺寸组件原型（§5.4 建族先查的那份）
```

## 谁读它（8 道静态闸 → 正文落点）

| 闸 | 读的正文 |
|---|---|
| `scripts/check-flat-buttons.mjs` | `references/frontend/flat-button-spec.md`（§1–§4 禁厚度项 + 例外清单） |
| `scripts/check-button-source.mjs` | `references/frontend/flat-button-spec.md`（§5/§6 族登记表） |
| `scripts/check-glass-unify.mjs` | `references/frontend/glass-spec.md` |
| `scripts/check-input-surface.mjs` | `references/frontend/input-surface-spec.md` |
| `scripts/check-panel-unify.mjs` | `references/frontend/panel-spec.md` |
| `scripts/check-ui-structure.mjs` | `references/frontend/disclosure-dedup-spec.md`（§2/§3 三张表） |
| `scripts/check-copy-budget.mjs` | `references/frontend/explanatory-copy-spec.md`（§2 长度预算表） |
| `scripts/check-design-spec-sync.mjs` | `references/frontend/design-tokens-ledger.md` + `assets/design-system/tokens.css` |

定位器 = `scripts/lib/design-spec-source.mjs`：**仓内本目录**是唯一真源；
`--spec-root=<dir>` 只作测试/一次性覆盖（契约测试把规范拷进一次性包根跑同一判据）。

**已知检查项缺口（诚实口径）**：`disclosure-dedup-spec.md` §3.5（U2b「信息不重复」，设计期
要求，作者令 2026-11-21）**尚无对应机检项** —— 摘要区 / 全文区是否重复同一事实、「一处读数
是否可由另一处推导」（D1/D3/D5）需要语义标注，静态源码判不出。当前效力来自设计期自检问句
与该文 §4 人工评审项；机检只覆盖同文 §3.2 表 2 的「信息组渲染点」计数（U2）。

## 为什么不在 `src/main/resources/seed/plugins/`（原路径）

退役前的原路径是 `src/main/resources/seed/plugins/design-language/skills/design-language/`，
那是**种子插件包**路径（引擎启动时装进数据根的插件）。设计语言插件已由 `af404938c` 退役，
把正文放回 seed 树会让它重新变成「随引擎分发的自研种子包」：K9-b（seed 树每个目录必须是
含 `plugin.json` 的合法插件目录）与 K9-d（自研包不得进 seed 树）会同时变红，且与退役裁定
直接冲突。故落仓位置取**仓库根的规范目录**，保留插件包内的相对形状
（`references/frontend/` + `assets/design-system/`）——8 道闸的相对路径零改动，正文里的
交叉引用也不改语义。

## 来源与内容合成

- 7 篇条文 + 2 份 css：stand-dev 源仓旁支 `chore/merge-design-language-20261118`（tip
  `3579e3f`）的 blob 直取（源仓只读，未 checkout、未合入、未改动）。
- `glass-spec.md`：取 stand 仓退役前历史版（`af404938c^`，含 §9 运行时判据 R1–R3；
  旁支版缺 §9），尾链按旁支版的改名结果换掉（`[[design-language]]` → `[[input-surface-spec]]`
  / `[[design-tokens-ledger]]`）。
- `design-tokens-ledger.md`：取旁支版（多 §7「取值、取形与立槽」，原执行面独有条目并入）。
- `tokens.css` / `components.css`：与退役前逐字节相同（`R100` 纯目录改名）。
- 文内路径一律改成**仓内相对本目录**的形状（`references/frontend/…`、
  `assets/design-system/…`、本仓 `scripts/…`）；仍住在 stand-dev 插件包内的件
  （`visual-style.md`、`SKILL.md`）显式标注「stand-dev 插件包」，不留死链。

## 纪律

- 改判据 / 改预算 / 改登记表：**先改本目录的正文**，再改执行脚本；反向（先改代码后补表）
  会让闸红一次，这是设计意图。
- 同一主题只许一份正文：插件包内不得再出现同名第二份（本目录即唯一一份）。
- 本目录不是插件：无 `plugin.json`、无 `Plugin.md`、无 `SKILL.md`，不被种子树枚举。

相关：[[glass-spec]]、[[input-surface-spec]]、[[panel-spec]]、[[flat-button-spec]]、
[[disclosure-dedup-spec]]、[[explanatory-copy-spec]]、[[design-tokens-ledger]]。
