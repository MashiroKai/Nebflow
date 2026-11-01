---
type: batch
status: published
date: 2026-11-01
scope: dock 中键 agent 头像边缘与顶部 Muse 会话头像边缘统一（单一 token 口径）
---

# dock 头像边缘统一批（作者令 2026-11-01 22:3x）——同 token 边缘语言，尺寸不动

关联批：[[20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb]]（dock 悬浮胶囊批：本批只换中键头像边缘，胶囊形态零触碰）；[[20261101_222500_composer-mobile-slim__delegate-nebflow-6e995fd9]]（在飞批邻位，零交集）。历史基准：收形批 2026-10-31 22:50「中键边缘收简 = 纯色零浮影零光环」（本批令文取代该口径）。

## 令文与口径

作者令：「dock 的 agent 头像边缘和顶部（Muse 式会话顶栏）agent 头像的边缘保持一致。」

口径逐条：边缘 = 描边/光环那一圈；统一成**同一份边缘语言 = 同 token**（宽度/颜色/透明度/主题映射一致，顶部若有渐层则同渐层）；**尺寸可以不同但边缘规格逐字一致**；自定义头像与默认头像**两态都成立**；深浅双主题验证。dock 其余形态（胶囊、位置、尺寸）**零触碰**。

修前事实：dock 中键头像自带头像边缘一套（`.dock-center-img` 内缩 2.5px 露出 sapphire 底 = 旧蓝描边环 + 零浮影），顶部头像另一套（`chat.css #agent-avatar-btn`：1px `--glass-control-border` 描边 + inset 高光 + `0 2px 8px` 浮影）——两套边缘语言并存即本批病。

## 改动（生产代码单文件，全部 `body.mobile-layer` 作用域）

`web/css/mobileLayer.css` 中键块（dock 中键 = 对话 home 键块）：

1. `.dock-center` 增边缘（与 `chat.css #agent-avatar-btn` **逐字同 token 同值**）：

   ```css
   border: 1px solid var(--glass-control-border);
   box-shadow:
     inset 0 1px 0 var(--glass-control-highlight),
     0 2px 8px var(--glass-control-shadow);
   ```

   三个 token 的深浅映射现役在 sapphire.css `:root` / `prefers-color-scheme: dark`（亮档 border `.90` / 高光 `.95` / 浮影 `rgba(0,0,0,.12)`；暗档 `.32` / `.35` / `rgba(0,0,0,.50)`）——零新造色，双主题自适应。

2. `.dock-center-img` 尺寸 `calc(100% - 5px)` → `100%`：旧 2.5px sapphire 蓝描边环机制退役，img 铺满 content 盒（= `.agent-avatar-img` 同构）；sapphire/深档 fill 保留为 img 缺载兜底底色（铺满后常态不可见）。

**几何账**：`base.css` 全局 `* { box-sizing: border-box }` ⇒ 1px 描边收进 44px 足印内，**中键外径恒 44×44**（实测 44×44），圆仍收在胶囊边界内（top 755.0 ≥ dockTop 750.0），呼吸缝恒 10px（`--dock-gap`）；顶部头像 78×78 形态零触碰。收形批④「shadow == none」契约随令退役（旧契约持有方 mobile-dock.spec D10 / mobile-dock-shape-shots 探针④ 同步换新判据）。

## 验收证据（`tests/dock-avatar-edge-unify-shots.mjs`，375×812 双主题 × 双头像态，29/29 ALL PASS）

产出 → `evidence/20261101_dock-avatar-edge-unify/`：同帧实拍（顶部 78px 大头像 + dock 中键 44px 同框）`edge-sameframe-<scheme>-<state>.png` + 并排特写帧 `zoom-dock-*/zoom-top-*`（dark/light × avatar/logo 共 12 帧）。

| 探针 | 判据 | 实测 |
|---|---|---|
| ① 边缘一致性（核心） | `.dock-center` 与 `#agent-avatar-btn` 计算样式逐字等值（宽/样式/描边色/光环） | 暗档双面 `1px solid rgba(255,255,255,.32)` + `rgba(255,255,255,.35) 0 1px 0 inset, rgba(0,0,0,.5) 0 2px 8px`；亮档双面 `rgba(255,255,255,.9)` + `rgba(255,255,255,.95) 0 1px 0 inset, rgba(0,0,0,.12) 0 2px 8px`——全等 |
| ② 边缘非空 | 1px 描边 + 非零光环 | PASS（收形批④ zero-shadow 退役实证） |
| ③ 双主题映射 | 两档全成立且档间 computed 互异 | PASS（证明走 token 映射非写死） |
| ④ 两态成立 | 头像态 img 铺满 content 盒 ≈42px（旧 2.5px 蓝环退役）；logo 态双面 stand logo | `imgW=42`（暗/亮同值）；logo 腿 `logo=true topLogo=true` |
| ⑤ 其余形态零触碰 | 中键 44×44、收进胶囊、呼吸缝 10px、顶部 78px + 同帧可见 | 44×44 / gap=10.00 / 78×78 inFrame=true |

回归（全部 PASS）：
- `tests/mobile-dock.spec.mjs` 21/21（D10 判据换新边缘 token 探针；D11 头像/回落链、D12 亮档 fill、D13 胶囊几何照旧）
- `tests/mobile-dock-shape-shots.mjs` ALL PASS（探针④ 翻转为「中键边缘 = 顶部同款」逐字等值；余五项原样）
- `tests/mobile-avatar-chip-title-gap.spec.mjs` ALL PASS（双引擎 × 双主题：dock 芯片底/logo 居中/墨迹扫描/自定义头像态 sapphire 底零回归 + 桌面腿 56px 原值）
- `tests/cs1030-coldstart-batch.mjs` 31 PASS / 0 FAIL（腿D 44×44 + gap 10 照旧）
- `tests/mobile-v3.spec.mjs` 6 passed / 1 failed —— T2「任务键徽标 10 vs 8」为**既有红**：main（bba413fc0）同树同测同报错，与本批 diff 无交集（本批不触 JS/任务链），留档不修。

零 console error：新证据脚本四条腿全 `R0 零 JS/console 错误`（每条腿独立监听 pageerror + console.error）。

## 未做 / 开放项

- 桌面端 `#agent-avatar-btn`（28px 形态）与 `.ap-pane .ap-avatar-box`（档案预览 72px）本批零触碰：本令只点 dock 中键对齐顶部，桌面既有多套尺寸共用同一份 token 边缘语言，无需再动。
- `tests/mobile-v3.spec.mjs` T2 任务徽标计数既有红（main 同红），不在本批范围。
- 在飞批工作区（fm 三面标准化 / 附件图标化 / dev 插件 / LLM 兼容续跑）零触碰：本批仅 `mobileLayer.css` 中键块 + 两处既有探针换判据 + 新增证据脚本。

## 文件

- 生产：`src/main/resources/web/css/mobileLayer.css`（中键块 + 文件头皮批注）
- 验收：`tests/dock-avatar-edge-unify-shots.mjs`（新）、`tests/mobile-dock.spec.mjs`（D10）、`tests/mobile-dock-shape-shots.mjs`（探针④）
- 证据：`evidence/20261101_dock-avatar-edge-unify/`（12 帧）、`evidence/20261101_dock-shape-post-muse/`、`evidence/20261030_coldstart-batch/`、`evidence/20261101_144702_avatar-chip-title-gap/`（复跑再生产物）
