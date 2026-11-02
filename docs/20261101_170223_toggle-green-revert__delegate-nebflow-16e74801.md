---
type: batch
status: done
related: "[[20261030_201846_button-system-monochrome-redesign__delegate-nebflow-fa3d4d6f.zh-CN]]"
---

# 开关回绿批（2026-11-01 作者 16:11 令「把我们 toggle 按钮的颜色 重新回到绿色」）

> 批令：开关（toggle/switch）配色回退绿色。验收：深浅双主题 × 双引擎实拍（开/关/禁用三态）；零 console error。

## 1. 考古（git 逐段实证）

`.nb-toggle`（js/toggle.js 共享开关，设置页/插件页/流图/社交/orb/生图八处同源）ON 档配方三代：

| 代 | 批 | ON 档配方 |
|---|---|---|
| 一 | 6a13d8b9c（09-05 插件面板体验批） | sapphire 35%/50% + sapphire 实心钮 |
| 二 | 5836d6f3b（**09-06 作者裁定**「开关品牌绿」） | `rgb(var(--brand-green)/.35)` 轨 + `/.5` 描边 + 实心 `--brand-green`（#07c160）钮 |
| 三 | c91de8456（10-30 按钮体系批 **B9**，「按钮不要绿色像 SpaceX」） | 墨族 `--btn-primary-tint`（亮 rgb(17 24 39/.35)/暗 rgb(230 237 243/.35)）+ `--btn-ink-bg` 实心钮 |

本批 = 撤销第三代（B9 的开关面向），恢复第二代（09-06 作者裁定的原绿配方）。B9 设计稿 [[20261030_201846_button-system-monochrome-redesign__delegate-nebflow-fa3d4d6f.zh-CN]] §5.2 B9 行与 §6 例外登记①「开关 ON=墨 tint」自本批起以本批为准；绿降级为身份/状态色的总裁定对开关这个豁免点不含。

## 2. 改动（4 文件位，零 JS）

1. **css/sidebar.css «nb-toggle» 节**：`.nb-toggle.on` 轨道 `rgb(var(--brand-green)/.35)`、描边 `/.5`；`.nb-toggle.on::after` 实心 `rgb(var(--brand-green))`（几何/动画零动）。节头注补回绿记录。
2. **css/sidebar.css `.cfg-toggle.on`**（退役留档规则，无 JS 用点）：随批回绿实心 `--brand-green`（原 `var(--color-primary)` 现冻结=同值 #07c160，直取 brand-green 三元组），注明若复活改走共享组件。
3. **css/sapphire.css 方向 A 开关口径块**：hover 描边递进以 `:not(.on)` 限定 OFF 档——sapphire.css 最后加载，同特异性后者会把 ON 的绿描边洗回玻璃描边（B9 引入的口径，本批修正为原配方行为：ON 悬停保持绿描边）；块注更新回绿口径。
4. **token 注释两张**：`--btn-primary-tint`（sapphire.css:933 + stand-tokens.css:134）回绿后零消费者，留作墨 tint 语义位，注释改真话（不删，避免与在飞按钮玻璃批的 token 面冲突）。

落点清单（同 class 同色，一处改全家）：设置页 8 处（工作日程/TTL/STT/LLM 日志/自启/JeV/生图/orb 自定义色）、插件页分发开关（plugins.js:453）、流图视图过滤（flowMapTab.js:1318）、社交面开关（socialPanel.js:808）——全仓 `role="switch"` 唯一实现 = toggle.js，CSS 唯一本体 = sidebar.css 节。

## 3. 验证（隔离实例 :8765 + 双引擎×双主题×三态）

harness：`tests/toggle-green-revert.spec.mjs`（隔离 home `/tmp/nb-toggle-green-batch`，宿主 gateway 零接触；Bearer file token → console-ticket 每上下文独立 mint）。覆盖：

- **A 契约**（computed-style，真实 toggleHTML 模块渲染 ON/OFF/DIS-ON/DIS-OFF 四参考行）：ON 轨 `rgba(7,193,96,.35)` / 描边 `rgba(7,193,96,.5)` / 圆钮 `rgb(7,193,96)`；OFF 轨玻璃（暗 `rgba(255,255,255,.14)`）零绿；disabled opacity 0.5。
- **B hover 口径**：OFF 悬停描边递进（→ muted）、ON 悬停保持绿描边（回归钉）。
- **C 真实开关**：LLM 日志开关真点击翻转，配色随态（乐观翻转真链路）。
- **D 落点**：插件页开关在场同色、流图视图过滤软探针；截图 12+ 张（三态参考行/真实开关行/插件行/面板全景 × 双引擎双主题）。
- **G console**：零 error / pageerror——唯二例外 = virgin-home 固有数据缺失 404（Nebula avatar/agent.json、portal status，与 CSS 批零关，response URL 双通道归因）。

### 对比度读数（readings.json，sRGB 合成数学，最终轮 48/48）

| 主题 | 圆钮 vs 轨道 | 轨道 vs 底 | ON 轨 vs OFF 轨（状态轴） |
|---|---|---|---|
| 暗 | **3.55:1** ✔ | 1.95:1 | 1.26:1 |
| 亮 | 1.72:1 | 1.38:1 | 1.38:1 |

暗主题圆钮/轨道 3.55:1 达非文本 3:1 限；亮主题 1.72:1 与 09-06→10-30 存续八周、作者亲裁的原配方逐值相同——绿实心钮 + 淡绿 tint 轨的绿色族内部对比在色相学上不可达 3:1（同族近亮度），状态承载由「轨道绿/中性相变 + 50% 绿描边 + 圆钮位移」三通道共担（iOS 原生开关同构；亮主题状态轴 ON/OFF 轨 1.38:1 + 色相通道）。若作者要亮主题数字达标，需改圆钮色（如白钮）= 新设计裁定，本批不擅动。

### 验证结果（最终轮 exit 0，48/48 PASS）

四上下文（chromium/webkit × light/dark）全绿：A 契约 6 项（ON=rgba(7,193,96,.35) 轨 + .5 描边 + rgb(7,193,96) 实心钮；OFF 零绿；disabled 0.5）、B hover 口径 2 项、C 真实开关翻转 2 项、D 插件面同色 1 项/上下文、G console 卫生（唯二放行 = virgin-home 固有 404 ×5，response URL 双通道归因）。实拍 15 张（三态参考行/真实开关行/插件详情行/面板全景 ×4 上下文）落 `evidence/.../shots/`。流图视图过滤软探针未挂上（`fm-view-filter` 需项目卡流图入口，virgin home 项目面形态不同，fail-soft 记录）——该开关与设置/插件开关同 class 同 CSS 节，色彩契约已由组件级断言覆盖。

### 验证工程坑（五轮归因，后续 harness 复用）

1. **console-ticket 一次性**：多上下文复用同一 ticket ⇒ 后续上下文 WS 鉴权全失效（boot 闸不落，`ws=0` 实例日志铁证）——每上下文独立 mint。
2. **设置抽屉 scrim**：抽屉开着时活动栏钮全部被拦截（click 30s 超时假象）——先 Esc（sidebar.js:208 closeSettingsPanel）再点活动栏。
3. **virgin home 行差**：工作日程开关随 persona/Soul.md 门控缺席；TTL 行 webkit 下落折叠段 hidden——真翻靶选双引擎恒可见的 LLM 日志开关。
4. **webkit resource-error 文本不带 URL**：console 归因须走 `m.location().url` + response 404 双通道。
5. **WS 重渲风暴**：活动会话流式事件驱动 renderPlugins 整面板重建，手风琴展开态随时被折回（间隔可 <400ms）——locator.click 稳定性检查直接 30s 超时；解法 = 「查→未中即展开」紧凑环（探测与同步 DOM click 同循环、evaluate 往返毫秒级抢窗）+ 原子探针（发现/读数/aria 单次 evaluate 出）。

## 4. 交付面

- 提交：本 worktree `delegate/delegate-nebflow-16e74801`，commit hash 待合并报告。
- 证据：`evidence/20261101_toggle-green-revert__delegate-nebflow-16e74801/`（shots ×12、readings.json、console ×4、results.json）。
- spec：`tests/toggle-green-revert.spec.mjs`（可复跑）。
