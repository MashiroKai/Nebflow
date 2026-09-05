# send-btn 绿色身份强化 — 交付说明（合并作者交付）

- 日期：2026-09-03 · 交付：Coder（合并节点）· 作者目检用
- merge commit：**18a50432ae8d50514257b9e7651635e6c3e27ce0**（main，--no-ff，ort 策略零冲突）
- 分支链：`sendbtn-green` = 基线 c0e67306 + merge main(7710190c)（e3f1491c）+ 实施 commit **127e7faf**（input.css 单文件 54+/18−），已合并后删除分支/worktree/软链
- 视觉验收：**PASS**（design-engineer 独立 vision 逐张过目 10/10，六项全过）——报告 `~/.nebflow/docs/Nebflow/20260903_sendbtn-green-visual-review.md`（~/.nebflow commit 2b9f6c0）
- 实施上游：`~/.nebflow/docs/Nebflow/20260903_sendbtn-green-impl.md`

## 一、变更说明

本批把 `#send-btn` 从洗白淡薄荷（bg alpha 0.42，评审确认在部分背景读作禁用）改为 **logo 微信绿 #07C160 玻璃族内分层加深**：enabled/hover/active 三档逐级加深（亮 0.55→0.68→0.75 / 暗 0.62→0.72→0.78），全部态 alpha<1 且 `blur(8px) saturate(1.3)`、双内缘、1px 细边框玻璃锚点分毫未动；配 0.2 级克制绿色 glow 与 active 按压内阴影。disabled/disconnected 采纳 PR #44 的淡绿 0.16 族（svg 0.45），并新增 frozen 态图标不淡化守卫（`#input-bar.frozen #send-btn[disabled] svg{opacity:1}`，send=wake 语义保留）。改动仅 `src/main/resources/web/css/input.css` 一个文件、规则族外零触碰（`#stop-btn` 同材质注释未动）。

## 二、逐态定值表（暗 / 亮）

主题机制：`prefers-color-scheme` 媒体查询（跟随系统）；亮=基础规则（input.css ~L766-838），暗=媒体块覆盖（~L843-847）。全部态 `backdrop-filter: blur(8px) saturate(1.3)` 生效、alpha<1。

| 态 | 亮主题 | 暗主题 |
|---|---|---|
| enabled bg | rgba(7,193,96,**0.55**) | rgba(7,193,96,**0.62**) |
| enabled border | rgba(7,193,96,0.30) | rgba(7,193,96,0.34) |
| enabled glow | `0 1px 4px rgba(7,193,96,.25)` + `0 0 12px rgba(7,193,96,.20)` | 同亮 |
| hover bg | rgba(7,193,96,**0.68**) | rgba(7,193,96,**0.72**) |
| hover border | 0.40 | 0.44 |
| active bg | rgba(7,193,96,**0.75**) | rgba(7,193,96,**0.78**) |
| active border | 0.45 | 0.48 |
| active 按压影 | `inset 0 1px 3px rgba(0,0,0,0.15)` | 同亮 |
| disabled bg / border | rgba(7,193,96,**0.16**) / 0.08 | 同亮 |
| disabled svg opacity | **0.45** | 同亮 |
| disconnected | 淡绿 0.16 族 + svg 0.45 + `:hover` 守卫回 0.16 | 同亮 |
| frozen 守卫 | `#input-bar.frozen #send-btn[disabled] svg { opacity: 1 }`（图标不淡化，L236-238） | 同（媒体查询无关） |

以上 12 数值已与合并后 main 实码（input.css L766-847、L236-238）逐值核对一致。

## 三、与 PR #44 原案差异对照（pr-44-send-btn @ 2f95bea6）

| 态 | PR #44 原案 | 本批（已合并） | 裁定 |
|---|---|---|---|
| enabled | rgba(7,193,96,**0.92**) 近实心 + border 0.45 | 亮 0.55 / 暗 0.62 + border 0.30/0.34 | **否决其值**——按作者裁定在玻璃族内加深，保住通透材质 |
| enabled glow | `0 1px 4px .35` + `0 2px 10px .25` | `.25` + `0 0 12px .20` | **收窄**，克制不喧宾 |
| hover | **#07C160 实色** | 亮 0.68 / 暗 0.72 半透明 | **否决实色**，改为玻璃内加深一档 |
| active | **#06A952 实色** | 亮 0.75 / 暗 0.78 + inset 按压影 | **否决实色**，改为再深一档 + 按压内阴影 |
| disabled | 淡绿 0.16 + border 0.08 + svg 0.45 | 同值照搬 | **采纳** |
| disconnected | 0.16 族 + svg 0.45 + `:hover` 守卫 0.16 | 同值照搬（守卫保留） | **采纳** |
| 冻结守卫 | 无（其 svg 淡化会波及冻结态） | 新增 frozen svg opacity:1 守卫 | **新增**（评审提醒项落地） |

## 四、合并后验证与清理

- **check-js-types**：合并前后输出逐字节一致（main@7710190c 临时 worktree 对照实测 `===IDENTICAL===`）——`343 errors (baseline 326)` 的门红为 **main 既有基线漂移**（flowAnim/flowMapTab/micOrb/orbSettingsUI 4 新文件 + chat.js 7>6），本批 CSS-only 零新增。
- **spec**：涉 #send-btn 的既有 spec 仅 `tests/smoke.spec.mjs`（class 状态断言，非视觉断言），需运行中服务（8080=宿主，纪律禁触）未跑，以 harness computed-style 断言 10/10 PASS 兜底；未跑 sbt（纯 CSS）。
- **清理确认**：worktree `.nebflow/worktrees/sendbtn-green` 已 remove；软链 `.nebflow/sendbtn-green` 已 rm；分支已 `git branch -d`（曾为 e3f1491c，正常接受）。`git worktree list` / `git branch` 复查均零残留。
- 未 push、未动 origin、未碰宿主进程（PID 87216 / 端口 8080）。

## 五、生效说明

**改动尚未生效**：仅落在 main 源码（input.css），需前端产物重建 + 宿主应用重启后浏览器端可见——按本批纪律两者均未执行，待作者安排。

## 六、取证截图（10 张，五态 × 双主题）

- enabled：`20260903_sendbtn-green-enabled-dark.png` / `-enabled-light.png`
- hover：`20260903_sendbtn-green-hover-dark.png` / `-hover-light.png`
- active：`20260903_sendbtn-green-active-dark.png` / `-active-light.png`
- disabled：`20260903_sendbtn-green-disabled-dark.png` / `-disabled-light.png`
- frozen：`20260903_sendbtn-green-frozen-dark.png` / `-frozen-light.png`
（均在 `~/.nebflow/docs/Nebflow/` 下；交付 Pop 以 enabled/hover/active/frozen × 双主题 8 张为主，disabled 2 张随文档路径备查）

## 七、遗留问题（非阻断）

1. 亮主题 hover 0.68 / active 0.75 在近白后景接近饱和观感（验收裁定区间内不构成 FAIL）——建议生效后在真实聊天后景以作者眼感终审，可微调 0.65/0.70；
2. frozen 态背景随 `:disabled` 变淡绿 0.16（验收确认可接受；若作者要保灰底，一行覆盖即可）；
3. main 上 check-js-types 基线漂移（343>326）为既有债务，与本批无关，建议另行处理。
