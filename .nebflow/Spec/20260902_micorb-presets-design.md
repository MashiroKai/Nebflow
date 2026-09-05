# micOrb 液态光球配色预设系统 — 设计说明

- 日期：2026-09-02
- 作者需求：语音气泡 micOrb 液态光球配色暗沉（暗色模式尤甚），需 ①一组配色预设（星云、海洋必做）②作者自定义配色 ③先出真实渲染可视化预览页确认后再实施。
- **本阶段只做设计，不改任何产品代码。**
- 可视化预览页（真实 WebGL 渲染，shader 与预乘 alpha 管线逐字复制自 micOrb.js v8.2.12）：
  `~/.nebflow/docs/Nebflow/20260902_micorb-presets-preview.html`

---

## 1. 渲染器现状（通读 micOrb.js v8.2.12 的结论）

### 1.1 调色板结构 — 3 槽 uniform，无第四槽

```js
const PAL_DEFAULT = { a: [0.471, 0.627, 0.863], b: [0.549, 0.490, 0.839], c: [0.200, 0.251, 0.502] };
// micOrb.js L114；shader 内 uniform vec3 palA/palB/palC
```

shader（`draw()`）内的实际用法 = 槽位语义：

| 槽位 | shader 变量 | 语义 | 派生用量 |
|---|---|---|---|
| **a / palA** | `c1` | **球体基色**：主体色调 | 流场主混色 `c1*1.04`；核心高光 `mix(c1,C_ICE,0.4)`；边缘亮环 `mix(c1,C_ICE,0.5)`；焦散 `mix(c1,C_ICE,0.3)` |
| **b / palB** | `c2` | **流纹副色**：第二色相涡流 | 以 0.55 权重混入流场 `mix(col,c2,…)*0.55` |
| **c / palC** | `c3` | **暗纹深色**：暗部基底 | 暗区 `c3*1.22`；亮色主题边缘 `c3*1.10` |

固定常量（非槽位，预设不可改）：`C_ICE=vec3(0.845,0.905,0.985)` 冰白高光、近白镜面高光、光向 `(-0.42,0.50,0.66)`、alpha 羽化带 `0.96–1.00 r0`。

注意：产品现状是**单板双主题**（`PAL_DEFAULT` 同时用于亮/暗，靠 `isLight` uniform 的着色分支区分），错误态板也是单板。本设计升级为**每预设 dark/light 双板**（单板是双板的特例），`isLight` 管线不动。

### 1.2 渲染管线关键点（预览页必须一致的部分）

- **预乘 alpha（2c0758a8，v8.2.12）**：shader 末尾 `gl_FragColor=vec4(col.rgb*col.a,col.a)`；context 属性 `{alpha:true, premultipliedAlpha:true}`；**无 blending、无 preserveDrawingBuffer、无逐帧 clear**（全屏 quad 每帧覆写）。任何预览/实施必须保持这套语义，否则复现 Chrome 残影/跨浏览器边缘污染。
- **色板已 uniform 化（v8 错误态先例）**：错误态红/琥珀板通过 `setStateCfg(s)` → `this.target.pal = s.pal || PAL_DEFAULT`（L488）传入，渲染循环以 0.04/帧 lerp 平滑过渡（L570-573）——**预设切换直接复用同一条路径，天然获得无闪烁过渡**。
- **采样与几何**：backing store = `size × dpr`（dpr 钳位 [2,3] 超采样），`iResolution` 取 backing 像素，uv 归一化——板与分辨率无关，只与色板数据有关。
- **无闪烁时序（v8.2.1）**：着色相位 `phaseTime += dt·timeScale` 累积；主题切换（`setTheme`）只更新 uniform、**不得重跑 `setStateCfg`**（会重触发 transitionPulse = 闪烁，v8.2.1 F5 约束，见 bindObservers 注释）。

### 1.3 初始化接口

- `OrbRenderer(canvas, {size, reducedMotion})`（L345）：**本身可多实例化**；单例的是 DOM 控制器 `MicOrb`（绑定 `#voice-btn .orb-canvas`，index.html L257-261）。
- 状态入口 `setStateCfg(s)`；主题入口 `setTheme(isLight)`（`prefers-color-scheme` matchMedia 驱动）；音量入口 `setVolume(v)`（voiceEngine RMS → listening 抖动）。
- 9 态机：`STATES` 表（L93-103）以 hue/sat/lum 相对旋转 + rot/ts/scale 表达状态差异；错误态走 `pal` 覆盖。

---

## 2. 配色预设（8 个方向，星云/海洋必做）

### 2.1 设计规则（针对「暗色模式暗沉」）

参考 Jarvis 液态光球 / Siri 液态光效用色，全部预设遵循：

1. **高明度内核**：暗板 `a` 槽最大通道 ≥ 0.85、整体明度 ≥ 0.55——深色环境下球体「发亮」而非「发灰」。
2. **暗纹高饱和**：`c` 槽允许深、禁止灰——HSL 饱和度 ≥ 0.55、明度 0.12–0.48 的**彩色深色**（深空靛/深海蓝/酒红等）。灰调 c 槽是当前默认板暗沉的主因之一（`#334080` 偏灰蓝，低值流场区大片浑浊）。
3. **双 hue 对流**：a↔b 色相差 40°–120°，保证液态涡流的层次可读。
4. **亮板加深**：light 板整体降低明度 8–15%，防止白底 washout（shader 亮色分支自带对比提升，板色需压住）。

### 2.2 预设清单与色值表

每预设 dark / light 双板；色值 = hex + shader 浮点（v/255，3 位小数）。槽位顺序统一为 a 基色 / b 流纹 / c 暗纹。

**0. 当前默认（对照基准，保留于预览页）** — 单板双主题
| 槽位 | hex | r,g,b |
|---|---|---|
| a | #78A0DC | 0.471, 0.627, 0.863 |
| b | #8C7DD6 | 0.549, 0.490, 0.839 |
| c | #334080 | 0.200, 0.251, 0.502 |

**1. 星云 Nebula** — 深空紫蓝泛起洋红星云，暗夜里安静发光的星际尘埃。神秘梦幻。
| 主题 | a 基色 | b 流纹 | c 暗纹 |
|---|---|---|---|
| dark | #8FA2FF (0.561,0.635,1.000) | #DC7CF0 (0.863,0.486,0.941) | #2A1E5C (0.165,0.118,0.361) |
| light | #6B7FE8 (0.420,0.498,0.910) | #C75FD8 (0.780,0.373,0.847) | #221750 (0.133,0.090,0.314) |

**2. 海洋 Ocean** — 碧青浪芯翻涌深海蓝，清凉通透的热带浅海。
| 主题 | a | b | c |
|---|---|---|---|
| dark | #3FE0D0 (0.247,0.878,0.816) | #2E86E8 (0.180,0.525,0.910) | #063A66 (0.024,0.227,0.400) |
| light | #1FBFBB (0.122,0.749,0.733) | #1F6FD0 (0.122,0.435,0.816) | #05304F (0.020,0.188,0.310) |

**3. 极光 Aurora** — 极光绿与冰蓝紫对流，冷冽夜空中流动的光幕。
| 主题 | a | b | c |
|---|---|---|---|
| dark | #4BE3A0 (0.294,0.890,0.627) | #6E7BF2 (0.431,0.482,0.949) | #0B4A46 (0.043,0.290,0.275) |
| light | #2ECC8A (0.180,0.800,0.541) | #5A6AE0 (0.353,0.416,0.878) | #093B37 (0.035,0.231,0.216) |

**4. 日落 Sunset** — 珊瑚橙芯染开玫红晚霞，温暖明艳的黄昏余晖。
| 主题 | a | b | c |
|---|---|---|---|
| dark | #FFA26B (1.000,0.635,0.420) | #F060A0 (0.941,0.376,0.627) | #7A2050 (0.478,0.125,0.314) |
| light | #F58A50 (0.961,0.541,0.314) | #DE4E8C (0.871,0.306,0.549) | #661A42 (0.400,0.102,0.259) |

**5. 翡翠 Emerald** — 翡翠绿芯闪金绿流纹，沉稳透亮的宝石质感。
| 主题 | a | b | c |
|---|---|---|---|
| dark | #35D98A (0.208,0.851,0.541) | #B8E05A (0.722,0.878,0.353) | #0A5230 (0.039,0.322,0.188) |
| light | #22B870 (0.133,0.722,0.439) | #9CC43E (0.612,0.769,0.243) | #084327 (0.031,0.263,0.153) |

**6. 熔岩 Magma** — 熔金橙芯下涌动岩浆红，炽热的能量感。
| 主题 | a | b | c |
|---|---|---|---|
| dark | #FF9A3D (1.000,0.604,0.239) | #F0483C (0.941,0.282,0.235) | #7A1220 (0.478,0.071,0.125) |
| light | #F0822E (0.941,0.510,0.180) | #D83A30 (0.847,0.227,0.188) | #650E1A (0.396,0.055,0.102) |

**7. 晨曦 Dawn** — 蜜桃粉晕染天际蓝，柔和清新的清晨光。
| 主题 | a | b | c |
|---|---|---|---|
| dark | #FFB3A0 (1.000,0.702,0.627) | #8FA0F5 (0.561,0.627,0.961) | #5C3A78 (0.361,0.227,0.471) |
| light | #F89484 (0.973,0.580,0.518) | #7A8CE8 (0.478,0.549,0.910) | #4E3066 (0.306,0.188,0.400) |

**8. 霓虹 Neon** — 电光青撞品红流纹，赛博感最强、暗色下最亮的一板。
| 主题 | a | b | c |
|---|---|---|---|
| dark | #3DF2F2 (0.239,0.949,0.949) | #F05AD8 (0.941,0.353,0.847) | #1A1A66 (0.102,0.102,0.400) |
| light | #28D0D8 (0.157,0.816,0.847) | #D848C2 (0.847,0.282,0.761) | #151552 (0.082,0.082,0.322) |

方向取舍说明：曾考虑「琥珀 Amber」（与熔岩撞型）、「曜石 Obsidian」（黑玻璃体与 offline 灰态难区分），弃之；补入「霓虹」补齐高刺激赛博向。

### 2.3 状态机与错误态策略

- 状态差异沿用现状：`STATES` 的 hue/sat/lum 是**相对偏移**（listening −18° 冷移、processing +22° 暖移、frozen 饱和度×0.4、offline 去饱和×0 等），叠加在任何预设板上都成立，无需逐预设调状态。
- **错误态（mic-error 红 / frozen-error 琥珀）保持产品固定语义板，不随预设变化**——状态语义可识别性 > 主题一致性。可选进阶（不建议首期做）：按预设色相微调错误板边缘色。
- 风险点：自定义板若明度过低，frozen（lum×0.74）/offline（sat×0）叠加后可能不可辨。实施时在 `setPalette` 入口 clamp：`lum` 乘积下限 0.6，或校验 a 槽明度 ≥ 0.45（低则拒绝保存并提示）。

---

## 3. 自定义调色板 — 数据结构设计（纸面，不实现 UI）

### 3.1 存储形态（localStorage 单 key）

```jsonc
// localStorage key: key('micOrb.palette')   （key() 为项目现有命名空间工具，同 i18n 的 key('locale')）
{
  "mode": "preset",              // "preset" | "custom"
  "presetId": "ocean",           // mode=preset 时生效；mode=custom 时作为覆盖基底
  "override": {                  // mode=custom 时的槽位覆盖，浅合并；未给的槽位继承 base 同主题槽位
    "dark":  { "a": "#3FE0D0", "c": "#063A66" },
    "light": { }                 // 亮板可整体不覆盖 → 完全继承 base 亮板
  }
}
```

- 字段 = micOrb 实际色板槽位：`a`（基色）/ `b`（流纹）/ `c`（暗纹），hex 字符串存储、加载时转 float 数组。
- **预设 id 引用 + 字段覆盖**：custom 必带 `presetId` 基底，用户只改动过的槽位——预设更新时自定义不失效，且 UI 可高亮「已改动的槽位」。
- 亮暗两套独立表达（`dark`/`light` 子对象）；解析规则 `board(theme) = merge(PRESETS[presetId][theme], override[theme])`。
- 读不到 / 解析失败 / 未知 presetId → 回落 `PAL_DEFAULT`（与产品现状一致）。

### 3.2 运行时解析（新文件 `web/js/orbPresets.js`）

```
PRESETS            // 预设注册表（§2.2 色值表即数据源，dark/light 双板）
loadSaved()        // 读 localStorage → {mode, presetId, override}（容错回落）
resolveBoard(sel, isLight) // merge 出当前主题的 {a,b,c} float 板
serialize(sel) / validate(board)  // 持久化 / 明度饱和度校验（§2.3 风险点）
```

---

## 4. 设置入口建议

现状：设置弹窗 `#settings-modal > #settings-content`（sidebar.js L712 起）按 `settings-section` 卡片流渲染，现有区块顺序：neblink → 运行时 runtime → TTL → Providers → Presets → MCP → 高级 → JSON 编辑器 → 关于。语音气泡本体在 `#input-bar` 的 `#mic-orb-wrap`（index.html L257）。

**建议：新增「外观」section，插在「TTL」之后、「Providers」之前**（视觉偏好中高频，不该沉到高级区；又不打断「连接类配置」的前半区）。区块内容：

1. 预设选择器：横向小卡片列表（92px 真实渲染小球 + 名称，同预览页网格，见 §6 联动方案）。
2. 「自定义」入口：展开 HSL 编辑器（§5）。
3. 即时生效，无需保存按钮（与 locale 切换同交互范式）。

次选入口（可选加分项，不做进首期）：`#mic-orb-wrap` 右键菜单「自定义光球…」直达设置弹窗对应 section 并高亮。

i18n：新增 `settings.appearance`、`settings.appearance.orbPalette`、`settings.appearance.customize` 等 key（zh/en 两份，沿用 `chat.micOrb.*` 同文件风格）。

---

## 5. HSL / 色板选择交互草案

**两级结构：预设选择器（一级）+ 槽位编辑器（二级）**

1. **预设选择器**：横向滚动卡片，每卡 = 92px 实时 WebGL 小球（idle 态）+ 名称 + 3 色点。点选即全局切换（0.04/帧 lerp 过渡，复用错误态先例）。
2. **自定义编辑器**（点「自定义」展开，基于当前预设基底）：
   - 顶部 1 个 84px 实时预览小球（独立 OrbRenderer 实例，应用「未保存的编辑中板」）+ 亮/暗背景切换小开关（预览球背后的卡片底色切换）。
   - 三行槽位编辑器，每行 = 槽位名 + 语义说明（a 基色 / b 流纹 / c 暗纹）：
     - 色相 Hue：0–360 滑杆（带色相渐变轨道）
     - 饱和 Saturation：0–100 滑杆
     - 亮度 Lightness：0–100 滑杆
     - hex 输入框（双向同步 HSL ↔ hex）
     - 「还原此槽位」按钮（清除该槽 override）
   - **暗纹守护**：c 槽 L < 0.12 或 S < 0.55 时行内黄字提示「过暗/过灰，暗色模式会发闷」（校验规则与 §3.2 validate 一致，软提示不阻断）。
   - 「随机灵感」按钮：在 §2.1 规则域内（a 明度 ≥0.55、c 饱和 ≥0.55、ΔH(a,b) 40°–120°）随机生成一组，配「再来一次」。
3. **联动数据流**：滑杆 input 事件 → 更新编辑中板 → `previewRenderer.setPalette(board)`（新 API，见 §6）实时重渲；关闭编辑器或点「使用此配色」→ 写 localStorage + 主 orb `setPalette`；点「放弃」→ 回落已存值。
4. 实时预览小球共用 OrbRenderer 类，与产品渲染管线完全同源——编辑器里看到什么，输入框上就是什么。

---

## 6. 实施方案 — micOrb.js 改动点清单（文件/函数级）

主仓只读现状下的**未来实施**清单（实施时再动代码）：

| # | 文件 | 位置/函数 | 改动 |
|---|---|---|---|
| 1 | `web/js/orbPresets.js` | 新文件 | 预设注册表（§2.2 表）+ `loadSaved/resolveBoard/serialize/validate`。独立文件避免 micOrb.js 膨胀、便于预览页/设置页共用 |
| 2 | `web/js/micOrb.js` | `OrbRenderer` 类 | 新增字段 `this.activePalette`（构造参数 `opts.palette` 可注入）与 **`setPalette(pal)` API**：设 `activePalette` + `target.pal`（复用 L570-573 的 0.04 lerp，无闪烁）；**`setStateCfg` L488** `s.pal \|\| PAL_DEFAULT` → `s.pal \|\| this.activePalette` |
| 3 | `web/js/micOrb.js` | `MicOrb.constructor`（L662-680） | 构造后 `orbPresets.loadSaved()` → `renderer.setPalette(resolveBoard(sel, isLight))` |
| 4 | `web/js/micOrb.js` | `applyTheme`（L689-692）+ bindObservers 主题监听（L815-819） | 主题切换时除 `setTheme(isLight)` 外，**只**调 `renderer.setPalette(resolveBoard(sel, isLight))` 换双板——遵守 v8.2.1 F5：不重跑 `setStateCfg`（不触发脉冲） |
| 5 | `web/js/micOrb.js` | `spawnRipple`（L739-752） | ripple 色 `--orb-dot` 回落色随预设（小改，非必须） |
| 6 | `web/js/micOrb.js` | css-orb fallback（`.s-*` CSS 变量） | WebGL 不可用的兜底球色不随预设（低优先，WebGL 失败才可见；如做，由 orbPresets 输出 CSS 变量） |
| 7 | `web/js/sidebar.js` | 设置面板渲染（L712 起） | 新增「外观」section（§4）：预设卡片选择器 + 自定义展开 + 事件绑定 |
| 8 | `web/js/i18n.js` | 新 keys | `settings.appearance.*` zh/en |
| 9 | 新增 | 实时预览小球 | 设置面板内 1 个 84px OrbRenderer 实例（编辑器联动，§5.3） |

**不改**：VS/FS shader 全文、context 属性、预乘输出、`phaseTime` 时序、`STATES` 表、错误态板、`setTheme` 的 uniform-only 语义。

### 持久化裁定建议

**选 localStorage（`key('micOrb.palette')`），不进后端配置。** 依据：

- 项目先例：`i18n.js` 的 locale 即纯前端视觉偏好 → localStorage 单 key 存储（`key('locale')`，读失败回落默认）——orb 配色与其完全同类。
- `persistence.js` 的「后端真源 + localStorage 写后缓存」模式适用于会话/消息等业务数据；orb 配色无后端字段、无跨设备同步需求，进后端徒增 API 面。
- 数据量 ~200 字节，无配额压力（`safeSetItem` 式容错即可，可复用 persistence.js 的安全写法思路）。

### 工作量估算

| 项 | 估算 |
|---|---|
| orbPresets.js 注册表 + `setPalette` API + 双板主题接入（改动点 1-4） | 0.5 人日 |
| 设置面板预设选择器 + 持久化（改动点 7-8） | 0.5 人日 |
| 自定义 HSL 编辑器 + 实时预览联动（改动点 9） | 1.0–1.5 人日 |
| a11y（aria-label）+ 回归（9 态 × 亮暗 × Chrome/Safari，含 WebGL fallback 路径） | 0.5–1 人日 |
| **合计** | **≈2.5–3.5 人日（约 4–6 个执行会话）** |

首期可拆：预设系统（0.5+0.5+回归 0.5 ≈ 1.5–2 人日）先行发布；自定义编辑器独立二期。

### 验收点（实施时的硬校验）

1. 每预设暗板 a 槽明度 ≥ 0.55、c 槽 HSL 饱和度 ≥ 0.55（数据层断言，已在 §2.2 表满足）。
2. 预设/主题切换无闪烁（palette 走 lerp，不触发 transitionPulse；切主题不重跑 setStateCfg）。
3. 错误态两板在任意预设下不变（红/琥珀）。
4. 预乘 alpha 语义零改动：shader 尾行、context 属性、无 blend/clear 与 v8.2.12 逐字节一致。
5. localStorage 损坏数据回落默认板，主流程不抛错。
