# Pop 多文件叠卡「微信扇卡式」重设计 — 过程报告

作者令 2026-10-30 12:47（逐字）：「参考一下微信的叠卡的设计 首先是会强制放缩所有尺寸
的卡片到一个大小 其次是叠卡的层次感设计它是像扑克一样轻微旋转展开的 而不是我们这样
重叠的设计」

参考图：`/Users/kaiyu/.stand/uploads/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/484649095440416_IMG_1625.jpg`
（微信「Expand 3」：正面卡完整，后卡如扑克扇形轻微旋开、边缘错落可见。）

## 改造点 → 落点

| 令 | 落点 | 实现 |
|---|---|---|
| 统一卡尺寸 | `src/main/resources/web/css/chat.css:4419-4429` | `.pop-stack-view` 固定 **220×280（11/14）** 卡面，取代「front 媒体比例驱动盒」（zcode-484 D1 的栈面语义就此退役）；裁切由栈域作用域 `.pop-stack-view .pop-img/.pop-video { object-fit: cover }`（chat.css:4474-4480）承担——只裁收起态叠卡，展开行/单文件仍 `contain`+自身比例（展开态不受扇形影响）。 | 
| | `src/main/resources/web/js/popArtifacts.js:571-586` | `buildMediaStack` 删除对 view 的 `fitLayer`（栈面不再随媒体比例变），自然比例回调只服务展开行；`applyLayers` 去掉 frontRatio 回写；`ratios` 数组整体退役。 |
| 扇形层次 | `chat.css:4431-4450` | 后层不再直重叠：`layer-mid = translateX(14px) rotate(2deg)`、`layer-back = translateX(26px) rotate(3.5deg)`（±2~4° 交错 + 水平错开，同尺寸扑克展开）；退役 translate+scale 重叠与 brightness 压暗。纯 CSS transform，零新库。用户行镜像：`chat.css:4782-4783`（`-14px rotate(-2deg)` / `-26px rotate(-3.5deg)`，扇入对话内侧）。 |
| 层级≤3 全露 | 不动 | `is-buried`（4+ 收进「展开 N」）语义照旧：`popArtifacts.js:606-622`。 |
| 交互保留 | 不动 | 点卡开 Canvas（front 跟随滑动索引，A-1）、「展开 N」平铺/「收起」回叠（含 0.42s 高度过渡）、单文件不叠、拖划/双指横滑翻页——全部未动；扇形只改 CSS 变换。 |
| 端一致 | `chat.css` 全域规则 | 无媒体查询分支，桌面/移动同一份变换（spec 双视口验证）。 |
| 伴随几何 | `chat.css:4752,4785` | 扇形外接矩形比旧重叠深（back  painted ≈ box 右缘 +34.3px，旧 +24.3px），A-2/uifix3① 的 pill 边距 20px→32px 保住「零重叠 + ≥8px 净空」不变式（spec-c2 双端实测 ≥13.7px）。 |

## 证据

- 修前：`before-desktop.png` / `before-mobile.png` —— front 是 1600×400 宽图，塌成 220×55
  矮条；后层仅直直右错（重叠设计，正是作者批的形态）。
- 修后：`after-desktop.png` / `after-mobile.png` —— 同一三异比例图组（1600×400 / 400×1600 /
  800×600）全部归一到 220×280 一张卡面（cover 裁切），后两层右上扇形旋开、边缘错落
  可辨，pill 净空保持。
- 截图为同一 harness 同一夹具（`tests/capture-stack-fan.mjs`，桌面 1200×900 + 移动 390×844）。

## Spec 读数

- `tests/pop-stack-fan.spec.mjs`（新，7 用例，桌面+移动双视口）：**7/7 绿**。
  - AC-F1：三异比例 layer box 统一 220×280；front natRatio 4.0 ≠ 面 0.786（cover 裁切实质存在）；栈面三层的 objectFit 全为 `cover`。
  - AC-F2：mid/back 矩阵旋转 2°/3.5°（非零、≤4.5°），translateX 14/26px 且 back > mid（错落）；z-index 3/2/1。
  - AC-F2b：5 图只画 3 层，4/5 位 `is-buried` 收进「展开 N」。
  - AC-F3：单宽图扁卡仍自身比例 + contain + 无栈件；真鼠标点扇卡正面 → 唯一一次 workspace-open-item，absPath=/tmp/pop/fan-0.png；展开三行高度两两不同（自身比例）、transform 全 none、contain；收起后扇形复原、pill 回「展开 3」。
- 回归 `tests/pop-media-alignment.spec.mjs`（38 用例，含 A-2 净空 / uifix3① 出界钉 / A-1 点击跟随滑动）：**全绿**。
- 回归 `tests/pop-artifacts.spec.mjs`：**37/38 绿**。唯一失败 UI-G P7（subagent 窗 rAF 计数 ≤1，实测 2）——
  与本批零代码交集（测的是 chat.js thinking 点 rAF 合并，不触 pop 栈），**在干净的基线树上
  复现同样失败**（stash 源文件后重跑仍挂），判为预存 flake，不在本批修复范围。

## 偏离申报

1. **U1 契约改写**（`tests/pop-artifacts.spec.mjs:525-569`）：旧 U1 钉「栈盒随 front 媒体比例、
   全族 contain 不裁切」（zcode-484 D1）。作者 2026-10-30 令明确退役该语义，U1 改为钉新契约
   （固定 11/14 面 + 栈面 cover；展开行 contain 由 U3 继续钉）。属「令 > 旧 spec」，已回写批注。
2. **harness `#popup-chat` 遮盖**（移动视口）：空占位列在 390px 视口下盖住整列吃掉点击——
   真机该容器在屏外（getHiddenRoot），属夹具形态；新 spec 的 newPage 里将其 display:none。
3. **卡面比例取值 11/14（220×280）**：微信参考卡约 3:4~4:5 之间，取 0.786；旧上限高 320，
   新面值在旧 220×320 之内。若作者要更小/更方，改 chat.css 一处即可。

## 开放项

- UI-G P7 预存 flake（基线复现），建议另批处理。
- `STACK_MAX` 常量名保留（现只约束 buildMediaFlat 宽上限），未改名以免噪音；注释已说明。

## 运行方式

```
node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/pop-stack-fan.spec.mjs
node tests/capture-stack-fan.mjs evidence/20261030_pop-stack-fan <tag>   # 对照截图
```
