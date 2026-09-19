# NOTICE — 种子插件来源与授权台账（seed plugin provenance）

本件 = **仓内来源 / 授权依据 / 条款处置形态登记件**（仓根 `NOTICE.md`，随仓库跟踪）。

登记缘起（现读）：仓内此前**无**来源台账件 —— `git ls-files | grep -iE 'license|notice|third|provenance|attribution'` 命中仅：本仓 `LICENSE`（MIT）、`src/main/resources/seed/plugins/slideblocks/skills/slideblocks/LICENSE`（上游随包件）、以及三处与来源无关的代码/Spec 文件。故本批三包按「无 ⇒ 在最近似台账位置登记」的口径，以本件为登记落点（`docs/**` 现读被 `.gitignore:30` 忽略 ⇒ 不作落点）。

## 1. 本批三包的来源与落点

| 包 | 来源项目 / 版本 | 源目录（建位本机，非 git 仓） | 仓内落点 | 件数（现读） |
|---|---|---|---|---|
| `desktop-control` | `computer-use` 0.5.14 | `/Users/kaiyu/.zcode/cli/plugins/cache/zcode-plugins-official/computer-use/0.5.14` | `src/main/resources/seed/plugins/desktop-control/**` | 7 |
| `browser-interaction` | `browser-use` 0.4.2 | `…/zcode-plugins-official/browser-use/0.4.2` | `src/main/resources/seed/plugins/browser-interaction/**` | 12 |
| `document-production` | `document-skills` 0.1.4 | `…/zcode-plugins-official/document-skills/0.1.4` | `src/main/resources/seed/plugins/document-production/**` | 107 + 4 件条款原文（见 §3） |

三包来源同属一个上游渠道（`zcode-plugins-official`，本机缓存目录，非 git 仓、无 commit 基线）。

## 2. 进仓依据（作者授权 · 逐字）

- **进仓依据 = 非商业用途 + 后期可移除**（作者 2026-09-19 18:11 授权，适用于本批三轨全部内容）。
- 授权原话要义（逐字照录，未改写）：「我们目前不盈利，不是商业的范围，可以进仓，后期我们可以移除」。

## 3. 处置形态 · 条款原文随包保留

`document-skills` 0.1.4 的 `skills/{docx,pdf,pptx,xlsx}/LICENSE.txt`（4 件，各 745 B，四件内容逐字节相同）**随包逐字节原样**落入对应 skill 目录（上游相对路径原样保留）：

| 落点（`src/main/resources/seed/plugins/document-production/` 之下） | 件 sha256（源件 = 落地件） |
|---|---|
| `skills/docx/LICENSE.txt` | `0e576d5fcab4050e3c34697b6d4ea64195df3212adaa1c47803fc3a26d8100a2` |
| `skills/pdf/LICENSE.txt` | `0e576d5fcab4050e3c34697b6d4ea64195df3212adaa1c47803fc3a26d8100a2` |
| `skills/pptx/LICENSE.txt` | `0e576d5fcab4050e3c34697b6d4ea64195df3212adaa1c47803fc3a26d8100a2` |
| `skills/xlsx/LICENSE.txt` | `0e576d5fcab4050e3c34697b6d4ea64195df3212adaa1c47803fc3a26d8100a2` |

🔴 条款文字**零改写**：`sha256(落地件) == sha256(源件)` 逐件相等（读数见落地节点证据 `license-fidelity.tsv`）；措辞、编号、换行、抬头一律未动。

## 4. 处置形态 · 仓内标注（三项要素齐备）

**① 来源项目**：`document-skills` 0.1.4（Z.ai 官方 zcode 插件缓存 `zcode-plugins-official`，本机路径见 §1）。另两包来源同渠道（`computer-use` 0.5.14 / `browser-use` 0.4.2）；现读扫描（`find <源目录> -type f \( -iname '*license*' -o -iname '*copying*' -o -iname '*notice*' \)`）⇒ **0 命中**（仅其 `node_modules/` 内第三方件），故该两包无条款原文件。

**② 条款要点**（🔴 原文以 §3 四件逐字件为准；本处只作要点登记，不改写条款文字）：

- 授权范围 = personal / educational / **non-commercial** use only；
- 商用 = strictly prohibited without prior written permission from the author；
- 为商业目的的 copying / modification / **distribution** 均被禁止；
- 「何谓商用」的最终判定权归作者；
- 无担保（AS IS）与责任限制条款；
- 界定权声明：本件**不判断**「落 seed / 分发」是否触发该条款（属作者面裁项，本处只登记，不下结论）。

**③ 承诺**：本仓对该内容的立场 = **非商业用途，后期可能整体移除**（作者 2026-09-19 18:11 授权原话要义同上 §2）。

## 5. 移除面（「后期可移除」的落点）

移除 = 删除整目录：`src/main/resources/seed/plugins/document-production/`（含 §3 四件条款原文）；另两包同法按目录删除：`…/seed/plugins/desktop-control/`、`…/seed/plugins/browser-interaction/`。删除后本件 §1/§3 同批更新。

## 6. 边界与未做项

- 本件不改写、不摘要、不替换任何源许可条款文字；不删改上游已落的条款/署名件。
- 与 `scripts/seed-upstream.json`（slideblocks 上游跟随基线表）**无写入关系**：该表按 git 基线字段登记（`upstreamRepo` / `upstreamDir` / `baseline` / `baselineFull`），本批三包来源为**本机缓存目录（非 git 仓、无 commit）** ⇒ 按现读体例无法登记，且**不臆造字段**；故本件为本批三包的登记落点。
- 本件不是许可证本身，也不替代 §3 的四件条款原文。
