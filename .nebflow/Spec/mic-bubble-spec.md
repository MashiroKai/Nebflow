> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# 麦克风液态光球（Mic Bubble）基础样式设计规格书

> 域：Nebflow · 文件：`mic-bubble-spec.md` · 可视化设计稿：`mic-bubble-visual-v7.html`（v7 材质基础）/ `mic-bubble-visual-v8.html`（v8 9 态预览，同目录，WebGL 真渲染）
> 状态：**draft v8.2.2 · 错误态晶莹修复（红/琥珀色板并入 WebGL 同一晶莹结构）· 待用户确认冻结**（2026-08-26 用户反馈「麦克风V8.2在错误状态下的显示不是一个晶莹的气泡」→ 根因 = STATES `css:true` 分支绕过 WebGL 渲染路径退回平涂 CSS 球，修复见 §10.7；含 v8.2.1 相位连续累积闪烁修复 §10.6）
> 实现方：nebflow-project / nebflow-rust Frontend · 评审：design-engineer（视觉评审）+ qa-frontend（§8/§10.4 可断言点转 Playwright）
> 前置裁定：用户 2026-08-25 21:34（长按改 toggle / 类 Siri 气泡 / 状态承载 Nebula 活动）→ **21:47 打回首版**（「做的也太丑了」「Siri气泡感做哪去了」）→ 22:00 重做指令（**先单独设计基础样式**：idle 静止 + listening 震动两核心态；冻结语义修正：「冻结不让说话，我们不是有个跳过本次的按钮吗？跳过了才让说」）。
> v2.0 修订：首版平面玻璃球（v1 单层 `--glass-bg` + 1px 边框）**作废** → 本轮 = **Siri 液态光球材质**（WebGL shader 真渲染，jarvis 同源）作为「基础样式规范」；9 态枚举用户已认可，状态叠加层（busy 光晕 / bg-agents 轨道 / processing / error / offline）**下一轮再做**，数据源调研结论（§9）保留。
> **v3.0 修订（用户 2026-08-25 22:08 二次打回：「质感，现在是空心的我要实心的」）**：v2 = jarvis 原版 shader 逐行移植，其 draw() 在深色背景上**天然空心**——`v3=smoothstep(0.6,0.8,len)` 把半径 0.6 内部乘成 0（darkCol=0）+ `innerFade` 杀死中心 v0 → 中心透明、边缘亮环（发光环而非实心球）。v3 = **重写填充与 alpha 为实心体积光球**：§5.1 层叠结构重构（L1-L11），`bodyA` 半径决定 alpha（内部=1，仅边缘 ~12% 羽化）替代 `extractAlpha(max(rgb))` + 双层 smoothstep；中心高亮 + 核心泛白；球面法线 × 左上光源体积明暗；镜面高光/轨道柔光斑降级为表面细节；rim light + 暗角厚度；外部 halo alpha 外扩（主题自适应）。调色板 / 音量形变映射 / 冻结降级 / 呼吸全部保持 v2 已确认参数。
> **v4.0 修订（用户 2026-08-25 22:25 三次打回：「少了泡泡的清透感，光斑过大」）**：v3 实心达成但**内部不透明油漆感**、**光斑过大**（镜面高光 attn35 白心 ~7px@48px + 轨道柔光斑 attn8 大块亮斑——hero 150px 取证：spec ≥230 白心面积 784px²、环带 ≥215 亮斑面积 290px²）。v4 = **实心基础上加「清透感」+ 光斑收敛**：① 内部材质改 glass 语义——L2 折射采样扰动（`uvP=uv+(nr-0.5)·0.04`，光在内部游动）、L3 pow3 焦散细亮丝（细纹非面斑）、L8 核心自发光改**发射光 post-shade**（不被表面明暗调制，光从内部透出）、L7 玻璃外壳（壳层半透明 `shell·0.32`）+ 底部背光透射（`backGlow·0.10`，边缘光聚集=玻璃签名）；② 光斑收敛——L5 镜面高光改线性衰减紧凑亮点（`clamp(1-d/0.14)·0.50`，白心 ~0.06uv≈1.4px@48px，实测半径 4×/面积 15×；取证 784→51px²）、L6 轨道柔光斑 → 2 枚内部细碎光点（attn 8→55/70，轨道 0.70→0.45/0.62r0 进内部，亮度 0.35→0.30/0.24；取证 290→0px²）；③ 平衡——L4 暗部放宽 `shade 0.52→0.60`、L7 暗角收敛 `0.45→0.38`（半透明流体不压死）。实心 alpha 模型（bodyA）与体积明暗保持。取证数据：中心 alpha 255、0.3/0.6/0.9r alpha 255、spec 白心 51px²、环带亮斑 0、边缘带均值(122) > 壳带(113)（v3 为 128<137 单调）、console 0 error。
> **v5.0 修订（用户 2026-08-25 22:43 四次打回：「还是不太行，特别是浅色模式下，看起来很浑浊。让有视觉能力的来做」）**：v4 深色效果用户未否定（**深色保持**），但**浅色半透明分层糊在一起（浑浊）**。根因 = v4 为「融入页面」而做的 **bgLuminance 灰白稀释**——`colBase=mix(colBase,vec3(bgLuminance),bgLuminance·0.22)`（浅色 bgLum≈0.96 → 把球体向 #f6f6f6 灰白混 21%，夺走宝石饱和色 → 灰紫塑料）+ 壳层 `shellCol=mix(mid,bg,0.30)·1.18` 向白再提亮（边缘冲白）。v5 = **浅色分支独立调参**：① 删除浅色灰白稀释 → 改「提饱和 + 略加深 + 核心更亮」（`mix(vec3(lum),colBase,1.22)·isLight·0.55` + `mix(color3·1.06)·isLight·0.10` + `core·pulse·isLight·0.10`）——保持蓝紫宝石色，在浅底上晶莹透亮而非灰团；② 壳层浅色不再向白冲（`mix(0.30,0.05,isLight)`+`·mix(1.18,1.06,isLight)`，应用权重 `·mix(0.32,0.24,isLight)`）；③ halo 浅色极弱 `mix(0.30,0.030,bgLum)`（防白底光晕=浑浊），暗色 halo 配方**保持 v4 原值**（`smoothstep(1.03,1.12)·exp(-(len-1.06)·9)`，仅暗色幅度不变）；④ `isLight=smoothstep(0.5,0.75,bgLum)` 为 0=深 1=浅 分支开关，**深色分支逐位等同 v4**（实测 meanRGB 74.8/83.4/121.5、chroma 47.4 与 v4 基线一致）。CSS 降级层同步浅色调参（§5.2）。取证（hero 150px）：浅色 meanChroma **33.6→45.1**（+34%）、muddyGray 4.2%→3.3%、deep 色 meanRGB 195/201/228→177/185/222（更饱和蓝紫）、深色逐位稳定、console 0 error。
> **v6.0 修订（用户 2026-08-25 23:58 裁定：「麦克风 · 液态光球 重做，用kimi」→ 五轮打回后的彻底重做，非微调）**：v2-v5 的反复打回暴露**结构性根因**——① 材质被越叠越厚（v4/v5 11 层 additive 叠加：壳层/背光/核心/光斑层层相加 → 层次互糊，浅色浑浊）；② 浅色用 bgLuminance 启发式混灰（补丁式，治标不治本）；③ **design-engineer 从未真正看到渲染结果**（v5 连 Read 图都被拒）= 盲调。v6 = **不基于 v5 修补，材质整体重写**：① **结构收敛** 11 层 → 8 个克制组件（C1-C8，各司其职、强度收敛，见 §5.1）；② **Siri 液态感** 内部着色从「角向渐变转圈」改**域扭曲 simplex 流体**（domain-warped noise 大理石纹流动）；③ **晶莹感** `pow5` 细亮丝 + 紧致高光（pow90/140，白心~2px@48px）+ 小型底部焦散，**非面状光斑**；④ **浅色独立分支**：显式 `isLight` uniform（JS 从 data-theme 直读，弃 bgLuminance 启发式）——提饱和 ×1.28 + 整体微抬 ×1.10 + 暗部 0.62 + **fresnel 向深色混 0.42（白底全反射暗边）**，**全程零灰白稀释**；⑤ **边缘语义分主题**：深色=fresnel 发光边，浅色=饱和宝石深边；**halo 深色专属**（浅色=0 + CSS box-shadow 发光关闭）；⑥ **混合修正**：输出改**非预乘 alpha**（v2-v5 的 `rgb·a` 预乘输出 + SRC_ALPHA 混合 = 边缘双重变暗 bug）；⑦ **视觉闭环**：vision=kimi，渲染→截图（深/浅）→**亲自看图自评**→调参→再看，迭代达标再交付（修复前 5 轮盲调）。调色板 / 音量形变映射 / 冻结降级 / 呼吸 / 9 态枚举全部保持。

> **v7.0 修订（用户 2026-08-26 打回 v6：「还是缺少了透明感啊。参考我们第一版的obs，那多好看啊」→ 仅修复透明感，非重做）**：v6 结构（8 组件 / 域扭曲流体 / isLight 分支）用户未否定，缺的是**透明感**。v7 = **把 v1 obs（jarvis orb）的透明机制融合回 v6 结构**：① **extractAlpha**（jarvis L1618-1621）：alpha 由材质亮度导出，v7 用 `a=pow(max(rgb)·1.10,1.45)·shape`——亮处实（高光/细丝/中心 α≈1）、暗处虚（暗部 α 低 → 背景透出 = 液态玻璃）；② **背景混合**（jarvis L1662）：**透明 canvas + 非预乘 SRC_ALPHA 混合**，浏览器按 `(1-a)` 把背景色自动混入暗部——球是**半透明液体**不是自发光实心塑料；③ **背景亮度感知**（jarvis L1640/1657/1664）：沿用 v6 显式 `isLight`——深色少透（Siri 发光体量）+ 弱 halo 0.16，浅色更透明（`a×0.96` 多混背景）+ 提饱和 ×1.42 + 深边 fresnel 0.50 + 边缘 alpha 抬升（白底定义感，不浑浊）；④ **预乘输出判定**（jarvis L1682）：**保持 v6 非预乘输出**（v2-v5 预乘+SRC_ALPHA=边缘双重变暗 bug 不回归），透明感全靠「暗部 α 低 + 背景透出」达成。⑤ **防空心**：与 v3 空心剖面不同，v6 中心材质本就明亮 → 亮度→α 中心实；另加轮廓膜 `shape=1-smoothstep(r0·0.98,r0·1.14,len)` 保边界（不散雾、边缘干净）。可视化设计稿 `mic-bubble-visual-v7.html`（深/浅截图 `/tmp/orb-v7-{dark,light}.png` 视觉闭环）。

---

## 0 · 一句话目标

把输入栏麦克风按钮升级为一个 **48px 的 Siri 液态光球**——**晶莹实心光球**（实心体积 + 清透质感：内部 alpha=1 体积保持，但内部材质为**域扭曲晶莹流体**——蓝紫大理石纹流动 + pow5 细亮丝 + 克制内核透光 + 分主题边缘 + 紧致高光 + 小型底部焦散，光斑收敛为小亮点而非大块亮斑）；同一材质在不同状态下有机演化：idle 静止时蓝紫 marble 微呼吸流动、listening 时随语音振幅有机形变、frozen 时置灰冻结 + 「跳过本次」解锁；质感对标 jarvis 的 WebGL 液态球（非 CSS 平面玻璃），deep 色模式 = fresnel 发光边 + 柔 halo，浅色 = 饱和宝石色 + 全反射深边（清透不浑浊），同时不破坏 Nebflow 低调克制的专业基调。

---

## 1 · 参考与依据

| 来源 | 引用 | 提炼可执行规则 |
|---|---|---|
| **用户打回原话**（最高优先级信号） | 2026-08-25 21:47「做的也太丑了」「参考的 jarvis.html 参考到哪去了」「Siri气泡感做哪去了」 | v1 平面玻璃球不合格；**液态光球质感是硬要求**——多层渐变（内部流体/中心高亮/边缘羽化）、有机形变、光晕层次，不许平面色块 |
| **Jarvis WebGL Orb shader**（Siri 质感真源） | `~/.nebflow/projects/voice-recognition-test/zhipu-demo/jarvis.html` L1482-1830（React Bits original shader）+ L142-180（orb-canvas/ripple）+ L1744-1799（setVolume→hover/shake、state 驱动 hue/scale/rotSpeed、呼吸） | ① `snoise3` simplex 噪声调制半径 → 液态有机边缘（`r0 = mix(..., n0)`）② 角向双色渐变旋转（`mix(color1,color2,cos(θ+t))`）③ 轨道高光 `light2(1.5,5)` 绕球运动 ④ 双层 smoothstep 边缘羽化 + `extractAlpha` ⑤ UV 正弦扰动 = 音量形变（`uv += hover·0.1·sin(uv·10+t)`）⑥ 呼吸 `1+sin(t·0.8)·0.02` ⑦ 状态 = hue 旋转 + scale + rotSpeed |
| **Nebflow visual-style 铁律** | `~/.nebflow/skills/nebflow/visual-style/SKILL.md` | 可交互控件玻璃质感；中度字重；总基调低调克制专业感。**注意**：铁律约束的是弹窗/按钮/字体/配色体系；麦克风气泡是用户点名要「类 Siri」的 hero 元素，其液态材质按用户裁定执行（§5.1 mic-orb 家族为明确授权的材质扩展） |
| Nebflow 冻结材质（08-24 已实现） | `web/css/input.css` `.frozen`(L149-167) · `web/js/main.js` `freezeWindowState()`(L429-449) + 60s tick | 冻结 = 冷蓝宝石玻璃：bg `rgb(91,127,191/0.10)`、border `rgb(91,127,191/0.45)`、3px 霜环、`rgb(120,160,220/0.22)` 霜晕。**「跳过本次」按钮当前代码中不存在**（已 Grep 确认）——本轮新增，视觉同族 |
| Nebflow 状态色/token | `web/css/sapphire.css` · `web/css/base.css` | `--sapphire:91 127 191` · `--sapphire-glow:120 160 220` · `--color-primary:#07c160` · `--color-bg:#13161c`（深） |

**冲突取舍（v2.0 修正）**：v1 取「放弃高饱和 cyan/violet → 零新 token 用绿色/蓝宝石 alpha 变体」——这正是平面化的根因（蓝绿窄色域 + 单层材质 = 玻璃按钮，不是光球）。**v2.0 裁定：该取舍作废**。Siri 液态质感需要紫↔蓝宽色域渐变，新增 **mic-orb 家族 2 色**（orb-violet、orb-deep，见 §5.1，sapphire 家族同色系衍生，非引入无关色相）；orb-blue 直接复用 `--sapphire-glow` 零新增。其余（圆角/边框/阴影/毛玻璃/字重）仍全部沿用既有设计语言。

---

## 2 · 布局与信息架构

**位置**：替换现有 `#voice-btn`（`.icon-btn`，36px），位于输入栏 `#input-bar` 最左（`index.html` L238）。

| 参数 | 值 | 说明 |
|---|---|---|
| 命中区（`<button>`） | `54×54px` 圆形 | ≥44px 触摸目标（Apple HIG） |
| idle 核心径 | `48px` | canvas CSS 尺寸 |
| listening 核心径 | `54px`（生长） | `transition: width/height .25s var(--ease)` |
| canvas 内部像素 | `尺寸 × dpr`（dpr 上限 2） | 高 DPI 清晰噪声 |
| 麦克风图标 | 本轮不叠加图标（纯光球本体） | 图标/均衡器等叠加元素下一轮设计 |

**DOM 结构（新增 canvas）**：

```html
<button.micbubble>            <!-- 54px 命中区 -->
  <span.frost-ring>           <!-- 冻结霜环（CSS 叠加，仅 frozen 可见） -->
  <canvas.orb-canvas>         <!-- WebGL 液态光球（主渲染） -->
  <div.css-orb>               <!-- CSS 液态降级（WebGL 不可用/静止帧） -->
</button>
```

**信息结构（两轴模型保留，本轮只做轴 A 基础材质）**：
- 轴 A · 语音通道（气泡本体）：`idle / listening` 本轮定稿材质；`processing / error` 材质下一轮叠加。
- 轴 B · Nebula 活动（叠加层）：`busy / bg-agents / frozen / frozen-error / offline` 下一轮叠加；**frozen 语义本轮修正**（§3.2）。

---

## 3 · 交互状态机

### 3.1 本轮定稿：基础材质三态

| 状态 | 气泡形态 | 材质 | 动画 | 可交互 |
|---|---|---|---|---|
| **idle** | 48px 液态光球（**实心**：内部 alpha=1，中心高亮 + 体积明暗） | mic-orb 蓝紫 iridescent（§5.1） | 微呼吸（±2%，7.9s）+ 内部流体缓旋 + 噪声表面流动 | ✅ 点击→listening |
| **listening** | 54px（生长） | 同一材质 + 微 hue 偏青（-18°） | 音量形变（§5.3）+ 流动加速 + 呼吸 | ✅ 点击/Esc→停止 |
| **frozen** | 48px 置灰冻结 | 同材质去饱和（sat 0.35 + lum 0.72，含光晕）+ CSS opacity .72 + 3px 霜环 | **静止**（timeScale=0，RAF 停） | ❌（见 3.2 跳过） |

其余 6 态（processing / nebula-busy / bg-agents / frozen-error / error / offline）数据源已调研完毕（§9），材质叠加下一轮。

### 3.2 冻结语义（用户 08-25 21:47 修正，替代 v1 的「不可点击 + 发送文字可唤醒」被动模型）

```
frozen ──点击「跳过本次」──▶ 解锁（本地覆盖）──▶ idle（可说话，说话即唤醒）
   ▲                                                          │
   └──────────── 窗口到 resumeAt 自然恢复 ──────────────────────┘
```

- **frozen 时麦克风禁用**：气泡置灰（§3.1）+ `cursor:not-allowed` + `aria-disabled="true"`，点击不触发 toggle。
- **「跳过本次冻结」按钮**（**新增**，当前代码无此按钮——已 Grep `freeze|skip|跳过` 确认 08-24 只有 `input-bar.frozen` 材质与 `chat.frozenNoTime` 等文案 key）：
  - **位置**：输入栏 `#input-bar.frozen` 内、placeholder 文案区右侧（mockup 已演示）。
  - **样式**：glass-control 同族 + sapphire tint——bg `rgb(var(--sapphire)/0.12)`、border `rgb(var(--sapphire)/0.38)`、`blur(10px) saturate(1.2)`、内嵌高光/底缘、文字 `rgb(var(--sapphire-glow))`、字号 11.5px 字重 500、圆角 999px。
  - **行为**：点击 → 本地覆盖标记 `state.skipFrozenUntil = resumeAt`（来自 `freezeWindowState()`）→ 移除 `#input-bar.frozen` 类 + 气泡 frozen 类 + 解除 `aria-disabled` + 隐藏按钮 → 麦克风恢复可点击。说话即发送消息 = 唤醒 agent（**复用既有 wake 语义**——input.js L609-614 frozen 豁免队列注释「user message WAKES the agent」，零新增机制）。
  - **窗口本身不变**：workSchedule 不动；`applyLocalFreeze()` 60s tick 检查 `skipFrozenUntil`，未到 resumeAt 前不加回冻结类，到点自然恢复。
  - **i18n**：新增 key `chat.frozenSkip`（跳过本次冻结）、解锁后文案 `chat.frozenSkipped`（已跳过本次 · 可说话）。
  - **零后端**：skip 是纯前端本地覆盖，无 WS 消息、无 schedule 修改。

### 3.3 toggle 流转（沿用 v1，仅材质更新）

```
idle ──点击──▶ listening ──点击/Esc──▶ processing ──转写落定──▶ idle
```

---

## 4 · 动效规范（本轮基础材质）

> 全部动画在 `@media (prefers-reduced-motion: reduce)` 下降级为静止帧（RAF 停 + shader `timeScale=0` 渲染一帧）。

| 动效 | 触发 | 时长/周期 | 参数 | 实现 |
|---|---|---|---|---|
| **呼吸**（idle/listening） | 常驻 | 7.9s（±2%） | `scale = 1 + sin(t·0.8)·0.02` | canvas CSS transform |
| **内部流体旋转**（idle） | 常驻 | 0.05 rad/s | 角向渐变 `cl=cos(θ+t·2)` | shader L2 |
| **噪声表面流动**（idle） | 常驻 | 噪声演化 `t·0.5` | `n0 = snoise3(uv·0.65, t·0.5)` | shader L1/L5 |
| **音量形变**（listening） | 常驻 | 随 RMS | §5.3 映射表 | shader L6（hover uniform） |
| **生长**（idle→listening） | 状态切换 | 0.25s | 48→54px | CSS transition |
| **冻结** | 进入 frozen | 0.3s | sat/lum/timeScale lerp 0.08/帧 | shader uniforms |
| **跳过解锁** | 点击按钮 | 0.3s | 反向 lerp 回 sat 1/lum 1 + 呼吸恢复 | shader + CSS |

---

## 5 · 视觉规格（基础样式材质规范——本轮核心）

### 5.1 WebGL 液态光球材质（生产主路径）

**技术路线（本轮裁定）**：移植 jarvis L1484-1830 shader（~110 行 GLSL）为生产实现。理由：① 质感保证——jarvis 即 Siri 质感真源，移植 = 上限可交付，CSS 模拟在 48px 下风险高（用户已打回一次）；② 性能——48px×dpr2=96×96 全屏四边形 + simplex 噪声 ≈ **<0.3ms/帧 GPU**，RAF 60fps 常驻成本可忽略（jarvis 在 320px 上同样流畅）；③ 多气泡——同时可见最多 2 球（chat + flow popup）= 2 个 WebGL context（浏览器上限 8-16，安全）；若未来 >8 场景再升级「单 canvas 复合渲染器」（多个球渲染进一个 context，spec 备注）。降级路径见 §5.2/§6。

**调色板（mic-orb 家族，sapphire 衍生）**：

| 名称 | 归一化 RGB | hex | 来源 |
|---|---|---|---|
| `orb-blue`（color1） | (0.471, 0.627, 0.863) | `#78A0DC` | `--sapphire-glow`（120,160,220）**零新增** |
| `orb-violet`（color2） | (0.549, 0.490, 0.839) | `#8C7DD6` | **新增**（Siri 紫蓝渐变锚点，sapphire 同色系衍生） |
| `orb-deep`（color3） | (0.200, 0.251, 0.502) | `#334080` | **新增**（体积暗底，防平面化） |

**层叠结构（v6.0 重写，自内而外——11 层 → 8 克制组件）**：

> v3.0 关键变更：**删除** jarvis 的「双层 smoothstep（v2/v3）+ innerFade + extractAlpha(max rgb)」空心组合（其效果 = 半径 0.6 内部 alpha≈0、边缘亮环）。**新增** `bodyA` 半径填充（内部 alpha=1）+ 球面体积明暗 + 中心高亮/核心泛白 + rim light/暗角 + 外部 halo。
> v4.0 关键变更（三次打回「少了泡泡的清透感，光斑过大」）：**内部材质不透明油漆 → 半透明晶莹流体**——L2 折射采样扰动、L3 焦散细亮丝、L8 核心自发光改**发射光 post-shade**（不被表面明暗调制）、L7 玻璃外壳 + 底部背光透射（边缘光聚集）；**光斑收敛**——L5 镜面高光 light2 大软斑 → 线性衰减紧凑亮点（白心 784→51px² 取证）、L6 轨道柔光斑 → 2 枚内部细碎光点（环带亮斑 290→0px² 取证）。实心 alpha（bodyA）与体积明暗保持。
> **v6.0 关键变更（用户 23:58「重做，用kimi」）**：v4/v5 的 11 层 additive 叠加是「层次互糊、浅色浑浊」的结构性根因 → **材质整体重写为 8 克制组件**。内部着色从「角向渐变转圈（L2/L3）」改**域扭曲流体**（Siri marble 流动）；`pow5` 细亮丝；内核透光收敛；**分主题边缘**（深=fresnel 发光边，浅=饱和宝石深边）；光斑收敛为**紧致高光点 + 小型底部焦散**（移除 L6 双轨道细碎光点）；**halo 深色专属**（浅色=0）；**输出改非预乘 alpha**（修复 v2-v5 边缘双重变暗 bug）。
> **v7.0 关键变更（用户 08-26「还是缺少了透明感，参考第一版obs」）**：不推翻 v6 结构，把 jarvis **extractAlpha** 透明机制融合回——alpha 由**材质亮度**导出（亮实暗虚）替代 v6 的 bodyA 恒 1 半径填充；**透明 canvas + 非预乘 SRC_ALPHA** 让浏览器按 `(1-a)` 混入背景色（暗部透出 = 液态玻璃）；**轮廓膜 shape** 保边界防空心/散雾；浅色提饱和 ×1.42 + 深边 fresnel 0.50 + 边缘 alpha 抬升（白底定义不浑浊）；halo 弱化 0.16。8 组件 / 域扭曲流体 / isLight 分支全保留。

| 组件 | 作用 | shader 实现 | 参数 |
|---|---|---|---|
| C1 液态轮廓 | **全盘填充**（非环形）：噪声调制有机半径，半径内部 alpha=1、中心最亮，仅边缘 ~10% 羽化 | `nEdge=snoise3(uv·0.9,t·0.4)·0.5+0.5` · `r0=mix(0.80,0.94,nEdge)` · `bodyA=1-smoothstep(r0,r0·1.10,len)` | 半径 0.80-0.94 · 羽化 1.10 |
| C2 域扭曲流体 | **domain-warped simplex 双层噪声 → 蓝紫大理石纹流动**（Siri 液态感；取代 v2-v5 角向渐变转圈） | `w=vec2(snoise3(uv·1.3+…),snoise3(uv·1.3+…))` · `p=uv+0.30·w` · `f1=snoise3(p·1.5,t·0.40)·0.5+0.5` · `col=mix(c3·1.22,c1·1.04,smoothstep(0.20,0.80,f1))` · `col=mix(col,c2,smoothstep(0.35,0.85,f1·0.5+f2·0.5)·0.55)` | warp 0.30 · 频率 1.5/3.2 · t 0.40/0.55 |
| C3 晶莹细丝 | **pow5 锐化噪声 → 细亮纹**（光折射亮丝，非面状光斑） | `f2=snoise3(p·3.2+vec2(2.7),t·0.55)·0.5+0.5` · `col+=C_ICE·pow(f2,5.0)·mix(0.38,0.30,isLight)` | pow5 · 深 0.38 / 浅 0.30 |
| C4 体积明暗 | **球面法线 × 左上光源** → 中心受光、边缘转暗（3D 球感；暗部 0.62 保持明亮不死黑） | `R=r0·0.985` · `z=√(R²-len²)` · `N=normalize(uv,z)` · `Ld=normalize(-0.42,0.50,0.66)`（注：uv.y 上正=屏幕上方） · `shade=mix(0.62,1.12,pow(diff,0.9))` | 光左上 · 暗部 0.62 / 亮部 1.12 |
| C5 内核透光 | 克制的发光芯（发射光不被表面明暗调制；光从内部透出，幅度收敛） | `core=1-smoothstep(0,r0·0.58,len)` · `pulse=0.88+0.12·snoise3(uv·2.2,t·0.8)` · `col+=mix(c1,C_ICE,0.4)·core·pulse·mix(0.28,0.20,isLight)` | 半径 0.58r0 · 深 0.28 / 浅 0.20 |
| C6 分主题边缘 | **深色=fresnel 发光边**（Siri luminous）；**浅色=饱和宝石深边**（玻璃弹珠白底全反射暗边=定义感，不向白冲） | `fres=pow(1-clamp(N.z,0,1),2.4)` · 深 `col+=rimBright·fres·(1-isLight)·0.50`（`rimBright=mix(c1,C_ICE,0.5)`）· 浅 `col=mix(col,c3·1.10,fres·isLight·0.42)` + `col+=rimBright·fres·isLight·0.10` | fres^2.4 · 深 0.50 / 浅 暗边 0.42 |
| C7 收敛高光 | **紧致镜面小点** + 微晕 + **小型底部焦散**（光透过球底聚焦；移除 v4 双轨道光点） | `H=normalize(Ld+vec3(0,0,1))` · `ndh=clamp(dot(N,H),0,1)` · `col+=rgb(1,0.99,0.97)·pow(ndh,mix(90,140,isLight))·mix(0.75,0.85,isLight)` · `col+=C_ICE·pow(ndh,8.0)·0.10` · `ca=1-smoothstep(0,r0·0.30,distance(uv,vec2(0.10,-0.42)·r0))` · `col+=mix(c1,C_ICE,0.3)·ca·ca·mix(0.20,0.14,isLight)` | 高光 pow 90/140 · 白心 ~2px@48px · 焦散 0.30r0 · 深0.20/浅0.14 |
| C8 光晕·**v8.2.8 移除** | ~~halo bloom~~（**v8.2.8 用户「外围那一圈光晕/荧光整个不要，只保留中间亮的气泡本体」→ 外圈 bloom + 宽 skirt 全删**）。v8.2.6/7 暗色渐变荧光（exp 2.8 / 幅度 0.13 / alpha 窗 1.46r0 / dark skirt 1.44r0）正是外围荧光环来源；v8.2.8 删 halo 混色（`outCol=col`）+ skirt 收紧至 **1.06r0**（本体剪影 ~0.14r0 羽化）。发光语义交由 C6 fresnel 边承担。 | ~~halo=… · haloA=… · outCol=mix(col,haloCol,…)~~（删除）→ `vec3 outCol=col;` · `float skirtEnd=r0*1.06;` | alpha 由 colNoRim 导出（v8.2.4）· 本体 ≤0.92r0 不透明（B9）· 无 bloom → 无亮环/无白底座 |
| 形变/冻结 | listening 音量驱动 UV 双向扰动；冻结 = 去饱和 + 降亮 + 静止（含光晕） | `uv+=hover·hoverIntensity·0.1·sin(uv·9+tt)` · `outCol=mix(vec3(gray),outCol,sat)·lum` + `timeScale=0` | hoverIntensity=1.0 · sat 0.35 · lum 0.72 |

**浅色分支（v6.0 明确）**：`isLight` 为**显式 uniform**（JS 从 `data-theme` 直读，0=深 / 1=浅），弃 v4/v5 的 `bgLuminance` 启发式。浅色专属参数：饱和度 ×1.28（`mix(vec3(lum),col,1.28)`）、整体微抬 ×1.10、暗部 0.62、内核/细丝/焦散一并略弱（0.20/0.30/0.14）、fresnel 向 `c3·1.10` 混 0.42（深边定义）、halo=0。**全程零灰白稀释**（不再向 #f6f6f6 混，宝石色不被夺走）。

**透明度/混合/合成（v7.0 修正，融合 jarvis extractAlpha）**：alpha **由材质亮度导出**（非 v6 的 bodyA 半径填充恒 1）——`a = glassA · shape`，`glassA = pow(clamp(max(r,g,b)·1.10,0,1),1.45)`（**extractAlpha**：亮处实、暗处虚 → 暗部透出背景 = 液态玻璃）；`shape = 1-smoothstep(r0·0.98, r0·1.14, len)`（轮廓膜保球边界，不散雾、边缘干净）。**防空心**：v6 中心材质本就明亮（核心透光+流体）→ 亮度→α 中心实，与 v3「中心透空+边缘亮环」剖面不同（v3 中心被 innerFade 杀死）。**背景混合**（jarvis L1662）：canvas `getContext('webgl', { alpha:true, premultipliedAlpha:false })` + `blendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)` + **非预乘输出** `gl_FragColor = vec4(col.rgb, col.a)` —— 浏览器按 `(1-a)` 把背景色自动混入暗部，球是半透明液体（v2-v5 的 `rgb·a` 预乘输出 + SRC_ALPHA = 边缘双重变暗 bug，不回归）。**主题分支（v6/v7 明确）**：`isLight` 为显式 uniform。深色分支 = fresnel 发光边 + halo 0.16（弱化）+ `a=mix(a,a,isLight)` 少透（Siri 发光体量）；**浅色独立分支**——提饱和 ×1.42 + 微抬 ×1.10 + 暗部 0.62 + fresnel 宝石深边 0.50 + **边缘 alpha 抬升**（`+smoothstep(r0·0.80,r0·1.00,len)·isLight·0.12`，白底深边定义）+ `a×0.96` 更透明（多混背景）+ **halo=0 + CSS box-shadow 发光关闭**。**全程零灰白稀释**（不浑浊）。

**Canvas 尺寸与 scale**：`width = 尺寸·dpr`（dpr≤2）；CSS 尺寸 48/54px；状态 scale（idle 1.0 / listening 1.08 / frozen 1.0）与呼吸叠加在 canvas `style.transform`。

### 5.2 CSS 液态分层材质（降级路径 / 静止帧兜底）

WebGL 不可用（context 创建失败/丢失）或 reduced-motion 时用此配方，与 shader **同源视觉**（同色板、同层序；v4 起含清透分层）：

| 层 | CSS 实现 | 参数 |
|---|---|---|
| 流体内部 | `radial-gradient(120% 120% at 32% 28%, var(--orb-violet), var(--orb-blue) 45%, var(--orb-deep) 100%)` | 球心偏上（光从左上） |
| 壳层透亮 + 收敛小高光 | `::before` 双 radial：`radial-gradient(72% 62% at 50% 42%, rgba(255,255,255,0.07), transparent 62%)`（壳层微亮）+ `radial-gradient(15% 10% at 63% 31%, rgba(255,255,255,0.85), rgba(255,255,255,0.20) 52%, transparent 78%)`（小亮点，取代 v3 大块 42%x32% 高光） | 高光面积收敛 ~9× |
| 焦散亮丝 + 背光 + 细碎光点 | `::after` 五 radial：焦散亮丝（34% 60% / 28% 58% 两丝）+ 底部背光（`50% 34% at 50% 92%` sapphire 0.22）+ 细碎光点（64% 66% / 42% 78% 白 0.5/0.34）`mix-blend-mode:screen`，动画 `css-flow` 9s | 模拟 L2/L3/L7 |
| 体积暗部 | `box-shadow: inset 0 0 8px rgba(255,255,255,0.10), inset -3px -5px 14px rgba(20,26,60,0.42)` | 内阴影（比 v3 轻） |
| 外部光晕 | `box-shadow: 0 0 14px rgba(120,160,220,0.28), 0 2px 8px rgba(0,0,0,0.22)`（**浅色主题清除；v6 shader halo 已成深色专属，CSS 不再补**） | 羽化感 |
| listening 形变 | `border-radius` blob 4 关键帧（56/44/47/53…）+ 流动加速 | 1.4s ease-in-out |
| frozen | `filter: saturate(.35); opacity:.72; animation:none` | 同 shader 参数 |

### 5.3 音量 → 形变映射（listening 核心，真实实现）

| 输入（数据源） | 映射 | 输出 |
|---|---|---|
| **RMS v ∈ [0,1]**（`voiceEngine.onMicChunk` 现成能量计算，L157-165，零新增采集） | `targetHover = v`（JS lerp 0.1/帧）→ shader `hover` uniform | UV 形变幅度 ±(v·0.1)·10/2π ≈ **±v×2.4px**（48px 球），响应时间 ~150ms |
| **包络频率 f**（音节节律 2-5Hz） | `timeScale = 0.5 + v·1.5` → shader 时间演化 | 响时噪声/流体流动演化加快 1.5-2× |
| 状态切换 | `stateScales: idle 1.0 / listening 1.08` | canvas transform |
| 音量归零（静音） | hover lerp 回落 0.08（idle 微扰水平） | 恢复呼吸态 |

mockup 用模拟 RMS（音节节律 3.2Hz × 慢起伏 0.23Hz × 抖动）演示；生产直接用 `onMicChunk` 的 RMS 赋值（写 CSS 变量或直接 setVolume 调用）。

---

## 6 · 边界与异常

| 场景 | 处理 |
|---|---|
| **WebGL 不可用**（context 创建失败） | `canvas.style.display:none` + `.css-orb` 显示（§5.2 配方）；状态 pill/console 提示 |
| **context lost**（`webglcontextlost` 事件） | 阻止默认 + 切 CSS 降级层；`webglcontextrestored` 后恢复 canvas |
| **多气泡并发** | ≤2 球（chat + flow popup）= 2 context 安全；>8 需单 canvas 复合渲染器（未来备注，不阻塞） |
| **冻结 tick 竞态** | `applyLocalFreeze()` 60s tick 检查 `state.skipFrozenUntil`，跳过窗口内不加回冻结类；到 resumeAt 自然恢复 |
| **跳过按钮在弹窗输入栏**（`.fa-input-bar.frozen`） | 同结构同样式（`.fa-input-bar` 共享 `.frozen` 材质，input.css L149-167） |
| **reduced-motion** | RAF 渲染一帧后停 + `timeScale=0`；CSS 层 animation:none |
| 标签页隐藏 | RAF 自动暂停（浏览器行为），恢复自动继续 |
| 主题切换 | 背景色 uniform 更新（shader 按 bgLuminance 重混），CSS 层随 `data-theme` 变量 |
| 窄视口 375px | 气泡固定 48/54px 不缩放；跳过按钮文案短（「跳过本次」）不换行 |

---

## 7 · 无障碍

- **焦点**：`<button>` 原生可达；`:focus-visible` 2px outline + 2px offset，色 `--sapphire-glow`（frozen 态同）。
- **ARIA**：`aria-pressed`（toggle 语义）、`aria-disabled="true"`（frozen）、`aria-label` 每状态一句话（「空闲，点击开始说话」「正在聆听，点击停止」「冻结中，不可说话」）；跳过按钮文本「跳过本次冻结」。
- **键盘**：Space/Enter toggle；Escape 停止录音（input.js 已有）；跳过按钮 Tab 可达。
- **动效减少**：§4 全覆盖降级；液态流动降为静态帧 + 文案表达状态。
- **对比度**：跳过按钮文字 `rgb(120,160,220)` on `rgb(91,127,191/0.12)`+深底 ≈ **7:1** ✅；状态文案沿用 `--color-frame-text`/`--sapphire-glow` 系 ≥4.5:1。
- **canvas 本身**：`aria-hidden="true"`（装饰性），状态由按钮 aria-label 表达，不靠 canvas 读屏。

---

## 8 · 可断言验收点（本轮基础样式，供 qa-frontend 转 Playwright）

> 二值判断 + 是否需截图。本轮只覆盖基础材质三态；状态叠加断言（v1 的 A1-A13）下一轮随叠加层恢复。

| # | 断言 | 内容 | 截图 |
|---|---|---|---|
| B1 | 渲染路径 | `#mic-canvas` 存在且 `getContext('webgl')` 返回非 null（或降级 `.css-orb` 可见）；canvas 内部分辨率 = **64×dpr（v8.2.6 放大 canvas：v8.2.5 的 48px canvas 被 orb body 填满至 ~0.94uv，只余 ~4px 给 glow→glow 被 canvas 边界裁切=硬边根因；64px 给 bloom 渐变留场）** | 否 |
| B2 | shader 健康 | 页面 console **0 error**（shader 编译/链接失败会 console.error） | 否 |
| B3 | **实心 + 半透明分层渐变** | 合成截图（element screenshot）中心 0.45 半径区内：**中心像素 alpha ≥ 235/255（实心非透明）** 且 `rgbVariance > 100`（多层半透明分层，非纯色/非平面） | 是 |
| B4 | idle 呼吸流动 | 间隔 450ms 两张合成截图像素差 > 阈值（动画运行）；`prefers-reduced-motion` 下差 = 0 | 是 |
| B5 | frozen 静止置灰 | frozen 态间隔 450ms 两帧差 = 0；canvas computed `filter: saturate(0.35)` + `opacity: 0.72`；`cursor: not-allowed` + `aria-disabled="true"`，点击不触发 toggle | 是 |
| B6 | 跳过按钮 | frozen 态 `.skip-freeze-btn` 可见（computed display ≠ none）；点击 → frozen 类移除 + aria-disabled 移除 + 按钮隐藏 + 文案切换 | 否 |
| B7 | listening 音量联动 | `setVolume(1)` 后 500ms 内帧像素差 > `setVolume(0.05)` 时（形变幅度 ∝ RMS；mockup 用模拟 RMS，生产用 onMicChunk RMS 注入） | 是 |
| B8 | 无外部依赖 | Playwright 记录请求 0 外域（自包含单文件） | 否 |
| B9 | 实心 alpha 剖面（**v8.2.3 收紧羽化 skirt**） | 合成截图沿半径采样 alpha：内部（0.3r0）alpha ≥ 0.9；0.92r0（仍在球内）alpha ≥ 0.9；1.00r0（羽化带）alpha ∈ (0.05, 0.75)；**1.06r0 外与 1.15r0 alpha ≤ 0.02（v8.2.3 白底座修复：skirt 0.96r0-1.05r0，1.05r0 外彻底透明——毛玻璃面板上无「白色圆盘」外圈）**。合成口径补充：球缘外环带（1.06-1.25r0）与无 orb 基线像素均差 <15、扰动像素（ΔRGB>20）占比 <5%。**内部不透明 + 边缘平滑衰减，无「中心透明 + 边缘亮环」空心剖面** | 是 |
| B10 | 中心高亮 | 中心像素亮度 > 半径 0.8 处亮度（发射光核心透亮、边缘转暗的体积感，且中心 alpha 仍 ≥ 0.9 = 实心高亮而非挖空） | 是 |
| **B11** | **光斑收敛（v6 修订）** | 取证窗口 = spec 实际渲染位左上高光点（`(cx-0.30s, cy-0.20s)`，**注：v6 光改屏幕左上 → uv 坐标 y 上正，故 cy-0.20s 为上方**）±16px、排除核心 r<0.35r0：**亮度 ≥230 白心面积 ≤ 40px²**（v6 高光 pow90/140 比 v4 的 0.14 线性再收，白心~2px@48px；v4 基线 51px²，v6 预计更小——阈值需 qa-frontend 用 v6 真实渲染重取样） | 是 |
| **B12** | **清透·分主题边缘（v6 修订，替代 v4 边缘回亮）** | 8 方向径向采样：**深色**边缘带(0.88-0.96r)亮度均值 ≥ 壳带(0.70-0.88r)亮度均值（fresnel 发光边 = Siri luminous；v6 边缘向 rimBright 混 0.50）；**浅色**边缘带亮度均值 ≤ 壳带均值（fresnel 向深色混 0.42 = 饱和深边；全反射暗边） | 是 |
| **B13** | **细碎光点 → 底部焦散（v6 修订）** | 底部焦散区（`(cx+0.10s, cy+0.42s)`（uv.y 上正 → +0.42s 为屏幕下方）±0.15r0，排除高光窗口 ±20px）：**亮度 ≥215 面积 ≤ 60px²**（v6 单焦散 0.30r0、深0.20/浅0.14，比 v4 双轨道 100px² 更收敛）；其余 annulus maxLum < 230（无 255 过曝块） | 是 |

> **v8.2.8 口径调和（重要）**：v8.2.8（用户「外围光晕整个不要」）**移除 halo bloom + 收紧 skirt 至 1.06r0**，外圈荧光环不再存在——断言从「bloom 渐变」族（§10.10 A1-A4）回到「**无外圈**」语义，固定半径带重新适用。
> - **体内部不变量依旧成立**（0.3r0 / 0.92r0 处 alpha ≥ 0.9；中心 alpha ≥235 实心、B10 中心高亮）——body 材质（≤0.92r0）逐位未动（径向 profile 0-0.58 归一化半径前后逐点重合实证）。
> - **外圈零区重新落在固定半径**——1.06r0 外（缩小尺寸下）alpha ≤ 0.02；1.06-1.25r0 环带与无 orb 基线像素均差 <15 且扰动像素占比 <5%（B9 v8.2.3 口径恢复，含 stateScale 放大近似）。V17 的「硬真零区 1.30 r0-est」作为 stateScale 放大后的保守上界仍成立。

---

## 9 · 参考链接

- Nebflow visual-style skill：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`
- **Jarvis Orb shader（移植源）**：`/Users/dev/.nebflow/projects/voice-recognition-test/zhipu-demo/jarvis.html` L142-180（DOM/ripple）、L1482-1830（shader）、L1362-1392（state→hue/scale）、L1744-1799（setVolume→hover/shake、render lerp、呼吸）
- 冻结材质实现：`web/css/input.css` `.frozen`（L149-167）；`web/js/main.js` `freezeWindowState()`（L429-449）、`applyLocalFreeze()` tick（L470-471）；wake 语义 `web/js/input.js` L609-614
- 状态数据源：`web/js/state.js`（`busySessionIds`/`workSchedule`/`frozenSessions`/`sessionBgAgents`）；`web/js/ws.js`；`web/js/voiceEngine.js`（`classifyMicError`/`onState`/`onMicChunk` L157 RMS）
- 相关既有规格：`docs/Nebflow/freeze-schedule-spec.md`（交互豁免 D11）；`docs/Nebflow/20260825_subagents-panel-spec.md`

---

## 数据源调研结论（v1 保留，全部成立）

| 状态 | 数据源（WS 事件 / 前端状态） | 现状 | 备注 |
|---|---|---|---|
| idle | 默认 | ✅ 现成 | — |
| listening | `voiceEngine.onState('listening')` | ✅ 现成 | 已实现（`updateVoiceUI`，仅差新样式） |
| processing | voiceEngine 停止后未 final 期间 emit `'processing'` | ⚠️ 需前端小改 | 纯前端推断（`segInFlight \|\| segQueue.length>0` 停止后），零后端 |
| nebula 工作中 | `state.busySessionIds`（`setBusy/clearBusy`） | ✅ 现成 | — |
| 后台 agent 工作 | WS `activeAgents` 快照 → `sessionBgAgents`/`agentStates` | ✅ 现成 | — |
| 冻结中 | `state.workSchedule` + `freezeWindowState()` + `frozen` 事件 | ✅ 现成 | **本轮新增跳过按钮**（§3.2） |
| 冻结·错误恢复 | `frozen` 事件 `reason≠schedule`（errorRecovery `isErrorReason`） | ✅ 现成 | 下一轮叠加 |
| 错误态 | `voiceEngine.classifyMicError` → `onState('error', i18n)` | ✅ 现成 | #stt-hotfix 已实现 |
| 离线 | `state.connected`（ws.js onclose/onerror） | ✅ 现成 | 同 `#send-btn.disconnected` |

**结论（v2.0 复核）**：全部 9+ 状态均已有或仅需本地微调的数据源，**零后端契约变更**。本轮前端改动点：① 气泡 canvas + shader（新组件）；② 跳过按钮 + `skipFrozenUntil` 覆盖（纯前端，~15 行）；③ listening 音量注入（复用 onMicChunk RMS，1 行）。`processing` 状态 emit 延后至下一轮叠加层时一并做。

---

## 10 · v8.1 状态表达修订（v8.0「纯 Orb」裁定 + 08-26 07:31/07:32 五条反馈迭代）

> **本轮第一依据**：v8.0 已裁定放弃 v1-v7 的 9 态复杂动画叠加（听写震动 / 后台 agent 蓝轨道点环绕 / 霜环 / busy 绿光晕等**全部叠加层不做**）；orb 本体保留 v7 透明感液态光球。v8.1 在 v8.0 基础上五条修订：**① orb 下方文字标签整个去掉**（状态纯靠颜色 + 动作分层双通道表达，§10.2 作废）；**② 清透感再提升、颜色浅一档**（§10.1 注④）；**③ 点击波纹展开**（§10.3）；**④ 动作感分层**——工作态有动作、非工作态安静（§10.3 分层表）；**⑤ 颜色过渡 0.3s→0.7s ease** 平滑插值。可视化预览 `mic-bubble-visual-v8.html`（v8.1：9 态纯颜色同框 + 点击波纹 + 亮/暗主题）。

### 10.1 9 态颜色映射表

语义主基调：正常态蓝、工作态亮/暖、冻结灰/冷、错误红/琥珀、离线灰。orb 为渐变液态球（非平色），下表「dominant」= 球体主导色相（归一化代表色），实现上以 sapphire 家族（C_BLUE `#78A0DC` / C_VIOLET `#8C7DD6`）+ `hue/sat/lum` 变换产出（production mechanism），`hue` 为色轮旋转度数。**mic-error / frozen-error 用色板 uniform 覆盖**（红/琥珀，v8.2.2 起走 WebGL 同一晶莹结构，见 §10.1 注）。

| # | 状态 | 语义 | 生产参数 `hue/sat/lum` | dominant·深色 | dominant·浅色 | orb 呈现差异（深→浅） |
|---|------|------|------------------------|--------------|--------------|----------------------|
| 1 | **idle** | 待命·蓝（默认） | `0 / 1.0 / 1.0` | `#78A0DC` | `#5B84D8` | 深=蓝紫大理石 + fresnel 发光边；浅=提饱和宝石蓝 + 全反射深边（不浑浊） |
| 2 | **listening** | 活跃·亮蓝（克制脉动） | `-18 / 1.08 / 1.05` | `#7FB3EA` | `#4E86DC` | 偏青更亮（输入活跃感，与 idle 拉开）；见 §10.3 脉动 |
| 3 | **processing** | 处理·靛蓝 | `+22 / 1.06 / 0.94` | `#8B86D8` | `#6A68C8` | 蓝→靛紫过渡（STT 中间态，与 idle 拉开） |
| 4 | **nebula-busy** | 编排忙·亮暖紫 | `+34 / 1.16 / 1.06` | `#A98BE8` | `#8F6AD6` | 最亮最暖（编排者=主工作态，读感「暖/亮」） |
| 5 | **bg-agents** | 后台忙·沉靛（弱） | `+8 / 0.80 / 0.92` | `#9099CB` | `#606AA8` | 比 #4 冷且弱（后台=次工作态，不抢主） |
| 6 | **frozen** | 暂停·冷灰蓝 | `0 / 0.40 / 0.74` | `#7E88A2` | `#6E7A8F` | 去饱和偏冷蓝灰（暂停≠错误，仍带蓝宝石血统） |
| 7 | **frozen-error** | 冻结+异常·暖琥珀 | **色板 uniform** `#B08468`/`#A8765A`（§10.7） | `#B08468` | `#A8765A` | 暖琥珀 + 琥珀光晕（与 #6 冷灰形成「冷停/暖警」对比）；晶莹结构与其他态一致 |
| 8 | **mic-error** | 错误·浮出红 | **色板 uniform** `#E5484D`/`#C9363A`（§10.7） | `#E5484D` | `#C9363A` | 高对比红 + 红光晕（必须浮出，不静默）；晶莹结构与其他态一致 |
| 9 | **offline** | 断连·中性灰 | `0 / 0 / 0.85` | `#8A8F98` | `#80858E` | 零饱和中性灰（与 #6 区分：#6 带蓝，#9 纯灰） |

**§10.1 注（实现口径）**：① 7 个蓝/紫/灰态沿用 v7 shader 的 `adjustHue + sat + lum` 机制（renderer `stateCfg` 已支持），浅色由 v7 `isLight` 分支提饱和 ×1.42 + 微抬 ×1.10 自动更亮更饱和；② **mic-error（红）/ frozen-error（琥珀）** 色相旋转不可达（蓝→红/琥珀跨色轮）——**v8.2.2 起实现 = shader 色板 uniform**（`palA/palB/palC` 替换原 C_BLUE/C_VIOLET/C_DEEP 常量，错误态传红/琥珀色板，走同一晶莹层叠结构，见 §10.7）；CSS 降级层用同结构红/琥珀渐变 + 同色系 inset 暗影；③ 全部色值为 sapphire/violet 家族衍生 + 错误专属红/琥珀，**不引入微信绿**（`#07C160` 仅限 logo，不用于本 orb）；④ **v8.1 清透提亮**（用户「再提升一点清透感，颜色浅一点点」）：shader alpha 曲线 `pow(max(rgb)·1.10, 1.45)` → `pow(max(rgb)·1.06, 1.58)`（中段更透）、shade 下限 0.62→0.65、整体亮度 `·mix(1.03, 1.12, isLight)`（原 1.00/1.10）、浅色 alpha `·0.96→·0.94`；CSS 降级层 inset 暗部 `rgba(20,26,60,.42)→.30` + 基底渐变三档提亮 + 错误色板同步提亮。**v7.1 的 a³ 压暗修复不回归**（不动 extractAlpha 幂次族结构与暗色边缘弱化逻辑，仅调曲线参数；dominant 色语义不变，9 态色相/饱和关系保持）；⑤ **v8.1 色相方向修正**（视觉自验抓获的 v8.0 遗留 bug）：YIQ `adjustHue` 正向旋转 = 蓝→青，与 CSS `hue-rotate`（蓝→紫）**方向相反**，导致 WebGL 路径 nebula-busy（+34）渲染成暗青而非规格表「亮暖紫」、listening（-18）偏紫而非偏青——v8.1 将 shader `adjustHue` 旋转取反与 CSS 约定对齐，§10.1 的 `hue` 参数表不变（语义色板不变，仅实现符号修正）。

### 10.2 文字标签规格 —— **v8.1 起移除（用户裁定）**

> 用户 08-26 07:31：「orb 下方不要文字了」——v8.0 的 11.5px 状态文字标签（字重 500 / 语义色 / 6px 色点）**整个去掉**，orb 正下方无任何文字元素。状态表达 = **纯颜色（§10.1）+ 动作分层（§10.3）** 双通道。
>
> **可区分性补偿**：① 9 态颜色间距经 V1 断言保证两两可区分；② 动作分层提供第二通道（工作态动 / 非工作态静，色盲/弱光下仍可分辨「是否在工作」）；③ **无障碍不降级**：orb 保留 `role="img"` + `aria-label` = 运行时 `t()` 语境文案（读屏可获状态语义，视觉上零文字）；tooltip title 可选（P2）。
>
> v8.0 §10.2 原文字规格表作废留档（见 git 历史 v8.0 版本）。

### 10.3 状态间过渡 + 点击波纹 + 动作感分层（v8.1 核心）

**① 颜色过渡（⑤ 用户「颜色过渡自然」）**：状态切换时 orb 颜色**平滑插值，不硬切**——`0.7s ease`（替代 v8.0 的 0.3s）。生产 = shader `hue/sat/lum/timeScale` lerp 因子 0.06–0.08 → **0.035–0.04**/帧（@60fps 时间常数 ≈0.42–0.48s，~0.7s 视觉收敛，中间经过自然中间色）；CSS 降级 = `filter/box-shadow/background` transition `0.7s ease`。无位移、无缩放。

**② 点击波纹（③ 用户「点击时有波纹展开效果」）**：参考 jarvis `orb-ripple`（jarvis.html L157-176）——点击 orb 时从球缘展开一圈圆形波纹：`scale 0.8→1.5 + opacity 0.6→0`，`0.8s ease-out`，2px 描边圆环，颜色 = 当前态语义色（--dot），`pointer-events:none`，动画结束移除 DOM。`prefers-reduced-motion` 下禁用。

**③ 动作感分层（④ 用户「多一点动作感…用来突出在工作」）**：动作强度 L0-L3 四级，**动画服务表达、克制不炫技**；工作态 = 有动作，非工作态 = 安静（只有颜色）。动作全部发生在 orb **内部/光晕**（流体流速 / 亮度脉动 / 发光节奏 / 微旋转），**不形变、不位移**（避免 v1 被裁定的震动类观感）。

| 状态 | 工作? | 动作强度 | 动作内容（生产 = shader；CSS 降级同族） | 周期/参数 |
|------|:---|:---:|---|---|
| **listening** | ✅ | **L3 活跃** | 内部流体加速（timeScale 1.6）+ 亮度脉动 ±6% + 微旋转 rot 0.25 | 脉动 1.6s ease-in-out；CSS `css-flow` 4s |
| **nebula-busy** | ✅ | **L3 活跃** | 内部流体加速（ts 1.2）+ **发光节奏脉动**（lum ±5% / 光晕半径 12→26px）+ 微旋转 rot 0.15 | 脉动 2.2s ease-in-out；CSS `css-flow` 5s |
| **processing** | ✅ | **L2 流动** | 内部流体加速（ts 1.3）+ 微旋转 rot 0.18（无脉动） | CSS `css-flow` 4.5s |
| **bg-agents** | ✅ | **L2 流动（弱）** | 缓流体（ts 0.8）+ 微脉动 ±3% + 微旋转 rot 0.08（次工作态，不抢主） | 脉动 3.0s；CSS `css-flow` 7s |
| **idle** | ❌ | **L1 极轻** | 缓流体（ts 0.5）+ 呼吸 ±2%（v7 既有，仅 idle 保留整体缩放） | 呼吸 7.9s；CSS `css-flow` 9s |
| **frozen** | ❌ | **L0 静止** | 内部流体冻结（ts 0），纯颜色；CSS `::after` animation 关闭 | — |
| **frozen-error** | ❌ | **L0 静止** | 同上（独立琥珀色板，CSS 静态） | — |
| **mic-error** | ❌ | **L0 静止** | 同上（独立红色板，CSS 静态；不闪不烁，错误靠颜色浮出） | — |
| **offline** | ❌ | **L0 静止** | 内部流体冻结（ts 0，v8.0 的 0.2 归零），纯颜色 | — |

**④ prefers-reduced-motion**：transition/脉动/波纹 ≤0.01s 或禁用，流体/呼吸降静止帧（全局降级惯例不变）。

### 10.4 v8.1 可断言验收点（供 qa-frontend 转 Playwright；二值判断 + 是否需截图）

| # | 断言 | 截图 |
|---|------|------|
| V1 | 9 态 dominant 色两两可区分（纯颜色表达的可区分性兜底）：9 orbs 并排截图，任意两态 dominant 像素在 HSV 上差（色相 ≥15° 或 饱和度差 ≥0.2 或 明度差 ≥0.15）——含 frozen(冷蓝灰) vs offline(中性灰) vs bg-agents(沉靛) 三组近态可分辨 | 是 |
| V2 | **无文字**：orb 容器内及相邻兄弟节点**不存在**状态文字元素（v8.0 的 11.5px 标签已移除）；orb 保留 `role="img"` 且 `aria-label` = 运行时 `t()` 语境文案（非字面硬编码） | 是 |
| V3 | 颜色过渡 0.7s：状态切换后 ~700ms 到达目标色、~350ms 处为中间值（非瞬跳、非硬切）；computed `transition-duration` ∈ [0.6s, 0.8s]；`prefers-reduced-motion` 下 ≤0.01s | 否 |
| V4 | orb 透明感保留且更清透（继承 v7 + v8.1 提亮）：合成截图中心 0.45r 内 alpha ≥235（实心）且 rgbVariance>100（非平色）；边缘暗部 alpha < 中心（透出背景）；浅色主题无灰白稀释浑浊（meanChroma 不低于 v7 基线） | 是 |
| V5 | 冻结语义区分：frozen 态 computed 为冷蓝灰（sat≈0.4）且无红光晕；frozen-error 为暖琥珀（色相偏暖）；二者 dominant 像素可区分 | 是 |
| V6 | 零叠加层：9 态 DOM 中**不存在** `.frost-ring` / bg-agent 轨道 / busy 光晕等叠加元素；mic-error 态为纯红渐变覆盖（非叠加环）；唯一允许的瞬态元素 = 点击波纹 `.orb-ripple`（动画结束即移除） | 是 |
| V7 | **动作分层**：工作态有动作 / 非工作态安静——listening/nebula-busy 态 orb 存在运行中的脉动动画（computed animation-name 非 none 或 shader lum 脉动钩子生效）；frozen/frozen-error/mic-error/offline 态 orb 及其伪元素 computed `animation-name: none`（CSS 降级路径）；idle 仅呼吸 ±2% 无脉动 | 是（动帧对比） |
| V8 | **点击波纹**：点击 orb 后 200ms 内出现 `.orb-ripple` 元素，computed animation = `orb-ripple-anim 0.8s ease-out`，border-color = 当前态语义色；800ms 后元素被移除（DOM 中不存在） | 是 |
| V9 | 清透提亮不回归：v8.1 vs v8.0 同态截图对比，orb 中心区域明度提升（浅一档）且暗色边缘无 a³ 压暗回归（边缘 alpha 过渡平滑无暗环） | 是 |

### 10.5 v8.2 动作范式移植（用户「动作感参考 jarvis：听写随声波震动、processing 波纹旋转；这一版还是没区分好」）

> **根因定性**：v8.1 的「动作」太弱——rot 0.08-0.25 rad/s（processing 一圈 35s，肉眼不可见）、listening 只有 ±6% 亮度脉动（无形变）、hover 恒 0.08 无音量联动 → 用户「反正我没看到」。v8.2 = **逐项移植 jarvis OrbRenderer 动作机制**（jarvis.html L1484-1830），v8.1 已确认项（去文字/清透/波纹/0.7s 过渡/动作分层语义）全部保留。

**① 音量驱动震动（jarvis `hover:'volume'`，L1524/L1744-1750/L1675-76）**：listening 态 `hover` 由麦克风 RMS 驱动——`setVolume(v)` 注入（生产接 `voiceEngine.onMicChunk` 现成 RMS，1 行），`targetHover = 0.10 + v·0.90`（静音回落微扰、大声强扭曲），lerp 0.1/帧（jarvis L1777），shader 同源公式 `uv += hover·hoverIntensity·0.1·sin(uv·9+tt)`（listening hoverIntensity 提至 1.4 保证可见，其余态 1.0）→ **orb 表面随声波震动/形变**。音量归零自动回微扰态。

**② rotSpeed 波纹旋转分层（jarvis `stateConfig.rotSpeed`，L1522-1528）**：v8.1 的 0.08-0.25 过弱 → 按 jarvis 比例重标定：

| 状态 | rotSpeed (rad/s) | 一圈耗时 | 语义 |
|------|:---:|:---:|---|
| **processing** | **1.0** | ≈6.3s | **波纹明显旋转**（= idle 20×，jarvis 同值） |
| **nebula-busy** | 0.6 | ≈10s | 亮暖紫 + 旋转 + 发光脉动 |
| **listening** | 0.3 | ≈21s | 旋转 + 音量震动双通道 |
| **bg-agents** | 0.15 | ≈42s | 弱旋转（次工作态不抢主） |
| **idle** | 0.05 | ≈126s | 几乎静止（仅呼吸 L1） |
| frozen/frozen-error/mic-error/offline | **0** | — | 全静止 L0（rot 0 + ts 0） |

**③ stateScales（jarvis L1515）**：listening **1.08** / processing **1.05** / nebula-busy 1.03 / 其余 1.0，`scale` lerp 0.08（jarvis L1782-83）作用于 canvas transform；idle 呼吸 ±2% 仍仅此态叠加。

**④ transitionPulse（jarvis L1761/L1788-1793）**：状态切换瞬间 `pulse=1.0`、每帧 ×0.92 衰减，`effectiveHover = min(1, hover + pulse·0.3)`——切换有一下触感脉冲，然后收敛到目标态动作。

**⑤ lerp 平滑（jarvis L1773-1783 与 v8.1 ⑤ 合并）**：hover 0.1 / scale 0.08（jarvis 原值）；hue/sat/lum/timeScale 保持 v8.1 的 0.035-0.04（0.7s 颜色过渡裁定不回归）。

**动作分层语义不变**（用户 08-26 07:32 裁定）：工作态（listening/processing/nebula-busy/bg-agents）动、非工作态静（idle 仅 L1 呼吸；frozen/frozen-error/mic-error/offline 全静止 L0）。变化的是**动作的可见性与语义对齐**：震动 = 听写专属（音量驱动）、旋转 = 处理/编排专属（rotSpeed 区分强弱）。

**预览页演示方案**（`mic-bubble-visual-v8.html`）：① **模拟音量面板**——自动声波（音节节律 3.2Hz × 慢起伏 0.23Hz × 抖动，§5.3 mockup 曲线）/ 手动滑块 / 实时音量表，仅 listening 态生效；② **自动循环演示**按钮——9 态每 2.4s 逐个切换；③ URL `?vol=0..1` 固定音量（高/低音量帧对比截图验收用）。

**v8.2 新增可断言验收点**：

| # | 断言 | 截图 |
|---|------|------|
| V10 | **音量震动**：listening 态 `setVolume(0.95)` vs `setVolume(0.05)` 各取一帧，orb 区域显著差异像素占比 ≥3%（v8.2 实测 9.5%）；非 listening 态 setVolume 不改变画面（hover 不受音量影响） | 是（双帧对比） |
| V11 | **波纹旋转**：processing 态间隔 ~2s 两帧 orb 区域显著差异 ≥5%（v8.2 实测 16.2%，rot 1.0 rad/s ≈ 114°）；idle 态同间隔差异 << processing（rot 0.05 = 5.7°，仅流体缓动）；frozen/offline 两帧差 = 0（L0 静止不回归） | 是（双帧对比） |
| V12 | **stateScale + 切换脉冲**：listening 态 canvas boundingBox ≈ 1.08×idle（±0.02）；状态切换后 300ms 内 hover 有脉冲凸起（effectiveHover > 稳态 target），2s 内收敛 | 否（DOM/bbox 断言） |

### 10.6 v8.2.1 闪烁修复（用户 08-26 08:23「orb 突然刷新一下，不流畅」）

> **根因定性（代码级，逐条排查 6 疑似点后确认）**：主根因 = **shader 相位跳变**——FS `float t = iTime·timeScale`（v8.2 L386/L448）中 `iTime` 是**绝对 wall time**（JS L550），而 `timeScale` 按 0.04/帧 lerp 切态（idle 0.5 ↔ listening 1.6，Δ=1.1）→ `Δt = iTime·Δts`，页面开 60s 后切一次态 t 瞬跳 ~66s，**noise 流体场整体瞬移到全新随机构型** = 用户所见「突然刷新」，每次切态必现、开得越久闪越狠。排除项：canvas.width 仅 init 设一次（无 resize/DPR 问题）；preserveDrawingBuffer:false 在连续 rAF 下无影响；无 context loss；音量模拟 50ms + hover lerp 0.1 平滑。

**修复 5 处（最小改动，动作语义零回归）**：

| # | v8.2 问题（代码位置） | v8.2.1 修复 |
|---|---|---|
| F1 | `gl.uniform1f(iTime, time)` 传绝对时间 × shader `iTime·timeScale` → 切态相位瞬跳（**主根因**） | `phaseTime += dt·timeScale` 连续累积后传 uniform，timeScale 恒 1——流速 lerp 只改速度、不跳相位；dt 钳制 ≤0.05 防 tab 切回大步跳 |
| F2 | `transitionPulse = 1.0` 瞬间赋值（setStateCfg）→ 首帧 effHover 阶跃 +0.3 | 软启动：`pulseTarget=1` → rise（lerp 0.3，~0.25s 到峰）→ ×0.92 衰减 |
| F3 | lum 脉动按 `this.state` 硬切乘子（L580-82）→ 切态瞬间 ≤6% 亮度跳变（取决于 sin 相位） | 幅度 lerp 0.06 淡入淡出 + `pulsePhase += dt·2π/period` 相位连续累积 |
| F4 | `hoverIntensity` 1.0↔1.4 硬切（L576）→ 40% 扭曲幅度瞬跳 | lerp 0.08 平滑 |
| F5 | 主题切换调 `select(cur)` 重跑 setStateCfg → transitionPulse 误触发再闪 | 标签色刷新独立成 `refreshHeroLabel()`，主题切换只刷标签 + isLight |

**自验（Playwright 逐帧像素差分，orb 区域显著差异像素占比，idle→processing 切态）**：v8.2 切态 300ms 窗口峰值 **14.74%**（基线均值 0.68%，22× 尖峰 = 闪烁帧）；v8.2.1 峰值 **1.41%**（10× 下降，仅余软脉冲缓变）、切态后均值 0.05、console 0 error；音量震动回归 PASS（vol 0.05 vs 0.95 帧显著不同，V10 语义保留）。

**v8.2.1 新增可断言验收点**：

| # | 断言 | 截图 |
|---|------|------|
| V13 | **切态无闪烁帧**：idle→processing 切态后 300ms 内，orb 区域逐帧显著差异像素占比峰值 ≤3%（v8.2 实测 14.74%，v8.2.1 实测 1.41%）；切态后 2s 均值 ≤0.5% | 是（逐帧差分） |
| V14 | **主题切换无脉冲**：dark↔light 切换后 `transitionPulse` 保持 0（不触发 setStateCfg），orb 区域无单帧 >3% 跳变 | 否（逐帧差分） |

### 10.7 v8.2.2 错误态晶莹修复（用户 08-26「麦克风V8.2在错误状态下的显示不是一个晶莹的气泡」）

> **根因定性（代码级）**：**状态分支绕过渲染层**——`STATES` 中 mic-error/frozen-error 标 `css:true`，hero `select()` 命中该分支直接 `heroCanvas.style.display='none'`、改用 CSS 降级 orb + `--orb-bg` 平涂渐变覆盖。后果：域扭曲流体（C2）/ extractAlpha 透光（暗部不透背景 = 实心哑光）/ fresnel 边缘（C6）/ 紧致高光与焦散（C7）**全部丢失** → 哑光气球，与其他 7 态的晶莹玻璃球质感断裂。次要问题：CSS 错误态渐变中段饱和平涂 + inset 暗部沿用蓝黑影 `rgba(20,26,60)`（红球蓝影 = 灰闷）。

**修复（4 处，其余 7 态零回归）**：

| # | 修复 | 内容 |
|---|---|---|
| E1 | **shader 色板 uniform 化** | `C_BLUE/C_VIOLET/C_DEEP` 常量 → `palA/palB/palC` uniform，默认值逐位等同原常量（7 个正常态渲染逐像素不变）；错误态传红板 `a:(0.929,0.451,0.427) b:(0.910,0.353,0.388) c:(0.541,0.180,0.200)`（c3 明度对齐 C_DEEP ≈0.28，防内部暗流发黑）/ 琥珀板 `a:(0.878,0.663,0.482) b:(0.812,0.580,0.396) c:(0.427,0.286,0.184)`——**错误态 = 同一晶莹气泡 + 警示色**，流体/折射/细丝/高光/边缘层次全保留 |
| E2 | **色板 lerp 过渡** | `this.pal` 9 分量 0.04/帧 lerp 向 `target.pal`（与 hue/sat/lum 同节奏 ≈0.7s）——进/出错误态颜色平滑，不硬切 |
| E3 | **渲染路径统一** | `STATES` 删除 `css:true`；hero `select()` 仅 `!heroOK` 时才走 CSS 降级；错误态获得 hue/sat/lum/rot/ts/scale 完整参数（L0 静止语义不变：rot 0 / ts 0） |
| E4 | **CSS 降级层晶莹化** | 错误态 `--orb-bg` 改 `--orb-base` 同结构（左上亮高光区→中段→深边）；新增 `--orb-inset` 变量，红/琥珀态 inset 暗影改同色系（`rgba(64,16,20)` / `rgba(58,38,22)`）取代蓝黑影；`::before/::after` 高光/细丝层全部保留（`mix-blend-mode:screen` 不动） |

**主题分支**：浅色由 shader `isLight` 显式分支自动生效（提饱和 ×1.42 + 深边 fresnel + halo=0），红/琥珀板同样走该分支——浅色错误态 = 红宝石/琥珀玻璃 + 全反射深边，零灰白稀释；CSS 降级层浅色错误态同步覆盖 `--orb-bg`/`--orb-inset`。

**视觉自验（vision 读截图，6 张）**：mic-error 深/浅、frozen-error 深/浅 hero = 透光玻璃球（内部流体暗流可见、左上紧致高光、边缘层次保留），与 idle 深色 hero 同族；9 态同框（深/浅）CSS 降级层错误态 = 玻璃质感与其他态一致；idle 深色回归对比无变化；console 0 error（ReadPixels 警告为截图工具自身行为）。

**v8.2.2 新增可断言验收点**：

| # | 断言 | 截图 |
|---|------|------|
| V15 | **错误态晶莹结构保留**：mic-error / frozen-error 态 hero 走 WebGL canvas（`#heroOrb` computed display ≠ none，不存在 `css:true` 类状态分支）；orb 中心 0.45r 内 rgbVariance > 100（多层流体分层，非平涂）且存在亮度 ≥230 的高光像素（紧致高光保留）；边缘暗部 alpha < 中心（extractAlpha 透光保留）；dominant 色 = 红/琥珀（警示语义不丢） | 是 |
| V16 | **错误态过渡平滑**：idle→mic-error 切态后 ~700ms 到达目标红色板、~350ms 为中间色（色板 lerp 0.7s 与 V3 同节奏）；逐帧无 >3% 跳变（V13 不回归） | 是（逐帧差分） |

---

### 10.8 v8.2.3 白色底座修复（用户 08-27「WebGL 光球下有白色圆形底座，不随环境变化」）

**现象**：v8.2.2 光球在真实 app 的毛玻璃输入栏上外缘包一圈恒白雾环（暗色主题扎眼、浅色融底不易察觉）= 用户所见「白色圆形底座」。

**根因（取证定论，非 DOM 层）**：DOM 逐层排除（`.micbubble`/`.orb-canvas` background 全透明、css-orb display:none、无伪元素底座）→ 白盘来自 **canvas 内 shader 的球缘外圈**：① C8 halo 混色把球缘外圈 rgb 提亮（haloCol=c1/c2×1.15）；② extractAlpha `glassA=pow(max(rgb)·1.06,1.58)` **从被 halo 提亮的 rgb 导出 alpha** → 外圈 alpha 抬升；③ skirt 衰减带过宽（`shape=1-smoothstep(0.98r0,1.14r0)`，雾环带 16% 球宽）+ 浅色边缘 alpha 抬升窗到 1.00r0。三者合成 = 一圈 rgb 白/alpha 中等的实心雾环。demo 深底上不显眼、毛玻璃面板（亮于纯黑 + blur 内容变化）上露馅。

**修复（方案 a「底座彻底透明」，只动 alpha 轮廓两行、材质光学零触碰）**：① skirt 衰减带 `smoothstep(0.98r0,1.14r0)` → `smoothstep(0.96r0,1.05r0)`——1.05r0 外 alpha 彻底归零，球缘羽化保留 9% 宽渐变；② 浅色边缘 alpha 抬升窗 `smoothstep(0.80r0,1.00r0)` → `smoothstep(0.80r0,0.96r0)`（原窗在 1.00-1.14r0 区间恒 +0.12 = 浅色雾环残留源）。halo 代码保留不动（B9/C8 同步修订：halo 只混 rgb，外显 alpha 由 skirt 门控归零）。

**验证（合成截图取证法 + 对照组证明断言效力）**：基线（隐藏 orb）/实拍同 clip 逐像素对比——修复前环带（1.06-1.25r0）扰动面积 32.8%(dark)/34.7%(light)、canvas 边缘 readback alpha 58-76；修复后 3.0%/3.2%、dark aEdge=0；球体零回退（coreMeanD/chroma 与修复前逐位一致 135/80、56.7/107——材质未动的实证）；明暗两主题截图目检白盘消失、液态质感/高光/状态色完整。

**v8.2.3 可断言验收点**（并入 B9）：

| # | 断言 | 截图 |
|---|------|------|
| B9(v8.2.3) | 环带（1.06-1.25r0）与无 orb 基线像素均差 <15 且扰动像素（ΔRGB>20）占比 <5%；1.06r0 外 alpha ≤0.02；球内 0.45r0 处 coreMeanD/chroma 与基线一致（不回退） | 是（明暗两主题 × 毛玻璃面板） |

### 10.9 v8.2.4 边缘抠图感修复（用户 08-28 01:01「语音气泡的边缘像抠图不干净一样，不好看」）

**现象**：v8.2.3 光球球缘有一圈半透明白色描边（暗色主题 = 贴纸白边）/ 浅灰色晕染（浅色主题），随 r0 噪声摆动厚薄不均——即「抠图不干净」观感。

**根因（取证三命中，任务定性 ①-④ 中命中 ①②③）**：
- **① alpha 渐变与亮边错位**：fresnel rim 峰值在自然球面 0.985r0，正落在 alpha skirt 衰减带（0.96-1.05r0）上——亮边被裙边裁成半透明白描边，且 r0 噪声（0.80-0.94）使描边厚薄不均。
- **② alpha-from-rgb 耦合（§10.8 同族）**：extractAlpha `glassA=pow(max(rgb)·1.06,1.58)` 从被 rim/halo 提亮的 rgb 导出 alpha——边缘 rgb 越亮 alpha 越高，亮色轮廓被「加粗」到轮廓线上。
- **③ 浅色方形薄纱**：浅色边缘 alpha 抬升 `smoothstep(0.80r0,0.96r0,len)·0.12` 过 0.96r0 后恒 1（smoothstep 高端饱和）→ orb 外整个 canvas 涂 0.12 常数 alpha，在 48px 方形 canvas 边界硬切断 = 浅色面板的方形抠图边。
- ④ premultiplied 未命中（context 本就 `premultipliedAlpha:false` + 直线 alpha 输出，配置正确）。

**修复（三处手术，球体内部 ≤0.92r0 材质逐位不动）**：
1. **rim 收入轮廓内**：fresnel 改在收缩球 `Rf=0.90r0` 上求值（zf/Nf），乘 `1-smoothstep(0.92r0,1.00r0,len)` 衰减窗——rim 峰值满强度保留（0.90-0.92r0 平台），1.00r0 前归零，不再与裙边带交叠。
2. **alpha 与 rim 解耦**：rim 块前快照 `colNoRim`（含核心/镜面/焦斑/亮度/饱和/lum 全链，仅不含 fres rim 与 halo），glassA 改从 colNoRim 导出——核心高光区 alpha 不变（V4 ≥235 不回归），边缘 alpha 只随体色衰减。
3. **浅色抬升门控**：抬升项乘 `shape`——0.80-0.96r0 内部不变，裙边带随 shape 衰减，1.05r0 外严格为 0（薄纱消失）。
4. halo 混色保留但加 `1-smoothstep(0.98r0,1.05r0,len)` 窗（rgb 柔光死在裙边内，不参与 alpha）。

**v8.2.4 可断言验收点**（并入 §10.4 V 族与 B9）：

| # | 断言 | 截图 |
|---|------|------|
| V17 | **裙边外真零区**：硬真零区从 **1.30 r0-est** 起算（越过最大裙边端 0.987uv + 摆动幅度 + **stateScale canvas transform 放大**——listening×1.08 将羽化带放大至 ~1.24 r0-est，1.15-1.24 带属合法液态羽化不入断言）与无 orb 基线逐像素均差 <4 且扰动像素（ΔRGB>12）占比 <1%，9 态 × 明暗两主题全过 | 是（基线差分） |
| V18 | **边缘无亮色描边**：明暗两主题 idle/listening/frozen/mic-error 3x 放大截图目检——无半透明白/灰轮廓线，rim 高光完整保留在轮廓内（vision 目检项） | 是（前后对比） |

**验证实录**：边缘探针（probe-edge.mjs）fixed 树 10/10；qa 修正几何探针（qa-edge-indep.mjs，扰动像素半径定位 + 硬真零区 1.30 r0-est 起算）fixed 树 20/20（1.30 外 10 组合全零、扰动收在 ≤1.208/1.179 与 stateScale 理论值 1.24 吻合）——V17 口径以 qa 修正为准（原 1.15 起算未计入 stateScale canvas transform 对羽化带的屏幕空间放大，light/listening 会确定性假 FAIL）；v8.2.3 白底座回归（verify-orb-base.mjs）14/14 且环带扰动进一步降至 0.87%（暗）/1.87%（亮）、core chroma 135/80·57/107 逐位持平（内部材质零改动实证）、浅色 aEdge 27→0（方形薄纱消除的决定性信号）；§10 断言套件 35/35（V4 核心 alpha 238、V15 错误态晶莹、V13/V16 过渡平滑全绿）；6 组 3x 放大前后对比 vision 目检白描边/灰晕消失。证据 `docs/Nebflow/assets/mic-orb-v824/`（含 zoom-*.png 前后对照、qa 修正探针）。

### 10.10 v8.2.6 荧光渐变过渡（用户 08-30「麦克风状态气泡的背景荧光改为渐变过渡，边缘看不出硬边」）

**背景**：v8.2.5 的 `shape=1-smoothstep(r0*0.96,r0*1.05,len)` 把 glow 限制在 ~0.09r0 窄带内（径向 profile 暗 idle r1.00=21.3→r1.05=2.7 骤降，maxDelta=**14.75** 硬 cliff）；且 48px canvas 被 orb body 填满至 ~0.94uv（uv±1.0），只剩 ~4px 给 bloom——身体边即 canvas 边，glow 被 canvas 边界**硬裁切**（v8.2.3 verify-orb-base P4 canvas 边缘 alpha=**51** 铁证），必然重现硬边。

**修复（暗/亮双主题，body 材质 ≤0.92r0 逐位未动）**：
① **放大 canvas 48→64px + shader r0 缩至 0.56-0.66 uv**——uv±1.0 对应 64px，r0≈0.61uv（≈39px 身体）→ skirtEnd ≤1.28r0=0.78uv < 1.0uv，bloom 有 ~14px field 落在 canvas 内不被裁；身体视觉尺寸保持 ~40px（不因放大变胖）。
② **暗色宽 bloom skirt**——`skirtEnd = r0*mix(1.28,1.15,isLight)`，`shape=1-smoothstep(r0*0.92, skirtEnd, len)`：暗色 0.92-1.28r0 宽羽化（荧光渐变过渡）；**亮色 0.92-1.15r0 收窄**（防雾环/白底座回归）。身体边缘（≤0.92r0）保持不透明（B9）。
③ **halo 慢衰减释出**——exp 9.0→**4.5**、haloA 0.16→**0.18**、窗 `smoothstep(1.02,1.30r0)`、haloCol ×1.15→**×1.12**（C8 同步）；alpha 仍从 **colNoRim**（v8.2.4）导出——bloom 永不亮环/白底座回归；C6 fresnel 明边保留（身体/glow 分离可辨）。

> `web/js/micOrb.js`（OrbRenderer size→64、r0、skirtEnd、halo）+ `web/css/input.css`（`.micbubble`/`.orb-canvas` 54/48px → 64/64px）。

**v8.2.6 可断言验收点**（供 qa-frontend 转 Playwright；`edge-assert.mjs` 明暗双主题 × 4 态，R0_EST=0.61，canvas half=32）：

| # | 断言 | 内容 | 截图 |
|---|---|---|---|
| A1' | **无外圈光晕**（**v8.2.8 取代 A1-A4「glow 渐变」族**——用户「外围光晕整个不要，只保留本体」） | 径向 profile：`terminus`（最后一处贡献 >4 的半径）≤ **1.06 r0-est × stateScale 放大**（idle/frozen/mic-error ≈1.06、listening ≈1.14）；[1.06,1.30]r0 之外平均贡献 ≤ 2.5（外圈荧光环不存在） | 是（差分） |
| A3' | **本体羽化平滑（无硬 cliff）** | 本体边缘 [0.90,1.06]r0 过渡最大径向 delta ≤ 12 且单调（本体 ~0.14r0 羽化；无 bloom 后轮廓干净，分离 v8.2.5 硬 cliff delta≈14.75） | 是（差分） |

**验证实录**：edge-assert.mjs（明暗 × idle/listening/frozen/mic-error）**34/34**；基线 main@c0690a63 短 glow/硬 cliff 判别显著（v8.2.5 硬 cliff maxDelta=14.75 > A3 阈值 10.0）。径向 profile：暗 idle glow 骤降 maxDelta 14.75（v8.2.5）→ 2.90（v8.2.6）、mono 100%；暗 listening glowΔ=8.93@1.05、mono 90%。body 不回归：A1 bodyEdge 107（暗 idle）/76（亮 idle）远高于 glowPeak 41/43；body 材质（暗/亮色板）逐位持平。证据 `docs/Nebflow/assets/mic-edge-fade/`（dark/light-compare.png 前后对照 + edge-assert.mjs / radial-profile.mjs）。

---

### 10.11 v8.2.8 外圈光晕移除（用户 08-30「麦克风状态气泡外围那一圈光晕/荧光整个不要，只保留中间亮的气泡本体」）

**背景**：v8.2.6/v8.2.7 为满足「背景荧光渐变过渡」把暗色 halo bloom 调成 exp 2.8 / 幅度 0.13 / alpha 窗 1.46r0 + skirt 加宽至 1.44r0（light 1.15r0）——渐变调优后外围荧光环仍存在且暗色下难看（用户截图取证：外圈包围中间亮球）。本轮不再调优，**直接移除外圈**。

**修复（两处手术，本体 ≤0.92r0 材质逐位未动）**：
1. **删 halo bloom**：`halo/haloA/haloCol` 三条计算 + `outCol=mix(col,haloCol,…)` 删除 → `vec3 outCol=col;`（outCol 即本体颜色，无亮色外扩；sat/lum/clamp 链保持）。
2. **skirt 收紧至本体剪影**：`skirtEnd=r0*mix(1.44,1.15,isLight)` → `r0*1.06`（本体 ~0.14r0 羽化，1.06r0 外 alpha 归零）；无 bloom 后无亮环/白底座回归。同步删除已无用 `bodyA`。

**验证（Playwright 真 shader + 系统 Chrome，before/after 同参数截图）**：暗/亮各一张前后对比——外圈荧光环消失、本体（流体/中心高光/rim/镜面）保留清晰；径向 profile 实证：**0-0.58 归一化半径（本体）前后逐点重合**（body 材质零改动），外圈尾肩（dark ~0.717）被移除（after contentRadius 0.625）；console 0 error（GL 无错）。

**v8.2.8 可断言验收点**（取代 A1-A4，见上表 A1'/A3'；并入 B9 固定半径零区恢复）：外圈无荧光环（A1'）；本体羽化平滑无硬 cliff（A3'）；体内部不变量（B3/B10/V4 核心 alpha 实心 / 中心高亮）全过；V17 硬真零区 1.30 r0-est 作为 stateScale 放大后保守上界成立。

### 10.12 v8.4.0 音量响应形变（用户 2026-09-03「听写中（listening 态）的 micOrb 气泡要跟随语音输入的实时音量——说话声音越大，边缘震动/形变幅度越大；边缘扭曲保留正弦思路但不要整齐的正弦」）

> **v8.2 基础上的增量**：§10.5 的 hover='volume' 震动保留（幅度语义不变）；本轮新增**独立的音量幅度管线 + 多谐波有机形变**，两者叠加。只动形变层——STATES 表、色板/预乘管线、phaseTime 累积零改动。

**① 音量源（一路一源，绝不开双麦流）**：

| STT 路径 | 电平源 | 代码证据 |
|---|---|---|
| 云 STT（`state.stt.sttConfigured`） | **复用现成链**：`voiceEngine.onMicChunk` 逐音频块算 RMS → `volumeListener((rms−0.01)×10)` → micOrb 构造时注册的 `setMicVolumeListener` → `setVoiceLevel` | voiceEngine.js L192 / micOrb.js 构造器；`syncVoiceTap` 检测 cloudFeeds 时**不开** orb 自有 tap |
| 浏览器 Web Speech（默认） | **orb 自开只读 VoiceTap**：进入 listening 态 `getUserMedia({audio})` → `AnalyserNode`（fftSize 512）→ rAF 逐帧 `getFloatTimeDomainData` 算 RMS，同一归一化 `(rms−0.01)×10`；**刻意不连 ctx.destination**——听不见、不回授、不干扰并发运行的 SpeechRecognition | micOrb.js `VoiceTap` 类；归一化与 voiceEngine 同参（SILENCE_RMS 0.01 / ×10 增益） |

生命周期：listening 态进/退即开/停（`MicOrb.apply` → `syncVoiceTap`；门控 = listening ∧ WebGL 可用 ∧ 非 reduced-motion ∧ 云路径未供电平 ∧ 测试旗标 `__MICORB_TAP_DISABLE__` 未置）；退出/销毁（`__resetMicOrbForTest`）停轨道 + close AudioContext + 电平归零；轨道意外 ended（拔设备）自动停。被拒/设备忙/headless → catch 静默降级**固定基础幅度**（level 0），STT 与渲染不受影响，不抛未捕获异常（spec 测试逐例断言 0 pageerror）。

**② 幅度管线（attack 快 / release 慢）**：`setVoiceLevel(v)` 注入原始电平（生产两源 + **测试注入选缝**——node/Playwright 绕过麦克风直接喂合成序列）；`drawFrame` 帧率无关指数平滑 `level += (target−level)·(1−e^(−dt/τ))`：**τ_attack = 70ms**（起音跟手，设计带 50-100ms）、**τ_release = 280ms**（松尾柔和，设计带 200-400ms）；dt 共用 §10.6 F1 钳制（≤0.05），后台页不可跳变。平滑结果 `voiceLevel` 即 **uVol uniform**。**状态门控**：`volDriven`（仅 listening 为 true）为假时平滑目标强制 0 → 注入电平永不泄漏到其他八态；frozen（ts=0）相位冻结语义不变。

**③ 有机波形（不要整齐的正弦）**：边缘位移 = 径向多谐波场 `uv += dir·(uVol·amp·wave)`，`wave(θ,t) = Σᵢ wᵢ·jᵢ(t)·sin(kᵢθ + driftᵢ·t + φᵢ)`：

| 谐波 | 角波数 k | 时间漂移 drift (rad/s) | 权重 w | 慢幅度抖动 jᵢ（×0.72..1.00） |
|---|:---:|:---:|:---:|:---:|
| 1 | 3 | +4.7 | 0.46 | 1.31 rad/s, φ 0.7 |
| 2 | 5 | −6.9 | 0.34 | 2.09 rad/s, φ 2.1 |
| 3 | 8 | +11.3 | 0.20 | 3.73 rad/s, φ 0.3 |

`amp = 0.062`（uv 单位，uVol=1 时最大径向位移，≈9% 半径），`jitterDepth = 0.28`。设计依据：**角波数必须取整数**（sin(kθ) 2π 周期性——非整数 k 会在 atan2 回卷处撕裂固定接缝，实测非整数 k 接缝幅差 1.6）；「无理数比/形态有机」落在**时间维**——漂移速率先两两不通约（4.7:6.9:11.3）、抖动率再次不通约（1.31:2.09:3.73），三个时间基永不重对齐 → 波形形状持续演化、无固定波形循环、无单频「整齐正弦」瞬间稳定可见。叠加关系：voice 场作用在 hover 正弦（§10.5 ①，保留）之后、`draw()` 噪声形变（w 场 + r0 nEdge）之前——三者共同决定边缘，全层共用同一扭曲后轮廓（§10.10 #20 统一轮廓性质保持）。**单一事实源**：`VOICE_WAVE` 常量同时（a）生成 GLSL 块（模板字面量内插同一批字面量）（b）导出 `voiceWaveAt(θ,t)` 供测试镜像采样——两者不可能漂移。

**④ 参数表**：τ_attack 70ms / τ_release 280ms / amp 0.062 / jitterDepth 0.28 / 谐波与漂移见上表 / AnalyserNode fftSize 512 / 归一化 (rms−0.01)×10 clamp [0,1]（与 voiceEngine 云路径一致）。导出常量：`VOICE_WAVE`、`VOICE_TAU_ATTACK`、`VOICE_TAU_RELEASE`、`stepVoiceLevel()`（纯函数，node 路线可断言）。

**⑤ 约束（回归即 FAIL）**：F1 phaseTime 连续累积与 dt 钳制不回归（voice 波形时间 = phaseTime，切态连续）；F5 applyTheme 不重跑 setStateCfg（主题切换在 listening+voice 下 pulse 恒 0）；预乘 alpha 管线/边缘羽化不回归（最大形变下被位移剪影外环带逐像素纯背景——羽化随位移轮廓走）；九态映射/冰山预设/色板层数据零改动（`orbPresets.js` 与 STATES 表不在本轮 diff）；uVol=0（全部非 listening 态）时 GLSL 分支整体跳过、渲染逐位不变。

**⑥ v8.4.0 可断言验收点**（`tests/micorb-volume.spec.mjs`，SwiftShader WebGL 实测 6/6 PASS，20.7s）：

| # | 断言 | 截图 |
|---|------|------|
| V18 | **幅度单调跟随**：注入合成电平 0.15→0.5→0.9，平滑后 uVol 幅度严格递增且逐档落在目标 ±0.06 带（attack 700ms 内收敛 ≥99.9%）；`setVolume` 生产别名与 `setVoiceLevel` 同路（raw 回读一致） | 否（状态回读） |
| V19 | **归零回落**：0.9 稳态注零后 1.4s 幅度 ≤0.04（τ_release=280ms，e^−5） | 否（状态回读） |
| V20 | **非 listening 态注入不生效**：processing/nebula-busy/bg-agents/frozen/frozen-error/mic-error/offline/idle 八态注入 0.9，幅度恒 ≤0.02；同会话 listening 对照组仍升 >0.8 | 否（状态回读） |
| V21 | **非整齐正弦**：时间平均角向 DFT 能量恰落 {3,5,8} 三个波数 bin（整数 k 零泄漏）、top bin 份额 <0.75（单频对照 >0.99 = 判据可分）；1.07s/2.41s 间隔波形剖面相关 <0.98（形态持续演化）；\|w(π)−w(−π)\|<1e-6（2π 连续无接缝） | 否（数学断言） |
| V22 | **e2e 渲染差异**：listening @0.15 vs @0.9 合成截图 orb 区显著差异像素 ≥3%（V10 同族，实测通过） | 是（`20260903_micorb-volume-{small,large}.png`） |
| V23 | **约束保全**：跨 idle→listening→frozen→processing 切换 phaseTime 单调且每 50ms 窗 Δ≤0.5、frozen 收敛平台（尾 350ms Δ<0.08）；listening+voice 下主题切换 pulse=0（F5）；最大形变（0.9）下被位移剪影外环带（uv r≥0.78 > 0.66+0.062）逐像素纯背景（预乘）；清障旗标撤除后真实 getUserMedia 尝试降级无异常、渲染器存活 | 是（环带取证在 spec 内） |

**生效说明**：需前端产物重建（`npm run build`，esbuild 打包）+ 宿主重启方可在线上生效；本轮验证均基于源码直载（静态服务 + 真 WebGL 渲染），未做构建与重启。

---

## 版本日志

| 版本 | 日期 | 修订 |
|---|---|---|
| v1.0 | 2026-08-25 | 两轴模型 + 10 态速览 + A13 断言（用户 21:47 打回） |
| **v2.0** | 2026-08-25 | **打回重做**：基础样式轮——WebGL 液态光球材质（§5.1）替代平面玻璃；冻结语义修正（禁用 + 跳过按钮，§3.2）；B1-B9 断言替换；v1「零新 token」取舍作废（mic-orb 家族 2 新色，§1 冲突取舍） |
| **v3.0** | 2026-08-25 | **二次打回（用户 22:08「现在是空心的我要实心的」）**：v2/jarvis 原版在深色背景天然空心（v3 smoothstep 零化内部 + innerFade 杀中心 v0）→ 重写 §5.1 为实心体积光球（L1-L11：bodyA 半径填充 alpha / 体积明暗 / 中心高亮 / 镜面高光 / rim light+暗角 / halo 外扩）；§8 B3/B9 改为实心断言 + 新增 B10 中心高亮；调色板、音量形变、冻结降级保持 |
| **v4.0** | 2026-08-25 | **三次打回（用户 22:25「少了泡泡的清透感，光斑过大」）**：实心基础上加清透 + 光斑收敛——L2 折射采样扰动 / L3 焦散细亮丝 / L8 核心发射光 post-shade / L7 玻璃外壳+底部背光透射（边缘回亮）；L5 镜面高光改线性衰减紧凑亮点（白心 784→51px²）、L6 轨道柔光斑 → 2 枚内部细碎光点（环带亮斑 290→0px²）；暗部放宽 shade 0.52→0.60、暗角收敛 0.45→0.38；§8 新增 B11（光斑收敛）/B12（边缘回亮）/B13（内部细碎光点）；§5.2 CSS 配方同步清透分层 |
| **v5.0** | 2026-08-25 | **四次打回（用户 22:43「还是不太行，特别是浅色模式下，看起来很浑浊。让有视觉能力的来做」）**：深色保持 v4（逐位稳定），**浅色分支独立调参**——根因 v4 的 bgLuminance 灰白稀释（向 #f6f6f6 混 21% = 浑浊）→ 删除；改「提饱和 + 略加深 + 核心更亮」（`mix(vec3(lum),colBase,1.22)·isLight·0.55` / `mix(color3·1.06)·isLight·0.10` / `core·pulse·isLight·0.10`）；壳层浅色不再向白冲（`mix(0.30,0.05,isLight)·mix(1.18,1.06,isLight)`，权重 `·mix(0.32,0.24,isLight)`）；halo 浅色极弱 `mix(0.30,0.030,bgLum)`，暗色 halo 原值保持；新增 `isLight` 分支开关；§5.2 CSS 降级层同步浅色调参。取证：浅色 meanChroma 33.6→45.1、muddyGray 4.2%→3.3%、深色 meanRGB 74.8/83.4/121.5 与 v4 一致、console 0 error |
| **v6.0** | 2026-08-26 | **五轮打回后彻底重做（用户 08-25 23:58「麦克风 · 液态光球 重做，用kimi」）**：不基于 v5 修补。① **结构收敛** 11 层→8 克制组件（§5.1 C1-C8；移除 v4 壳层/背光/双轨道光点，各组件职责单一）；② **Siri 液态感** 内部着色改域扭曲 simplex 流体（C2 大理石纹，取代角向转圈）；③ **晶莹感** pow5 细亮丝（C3）+ 紧致高光 pow90/140（C7，白心~2px@48px）+ 小型底部焦散（移除 L6 双光点）；④ **浅色独立分支** 显式 `isLight` uniform（弃 bgLuminance 启发式）——提饱和×1.28 + 微抬×1.10 + 暗部0.62 + fresnel 宝石深边0.42，**全程零灰白稀释**；⑤ **分主题边缘** 深=fresnel 发光边0.50，浅=饱和深边0.42；**halo 深色专属**（浅色=0 + CSS box-shadow 发光关闭）；⑥ **混合修正** 输出去预乘（修复 v2-v5 边缘双重变暗）；⑦ **视觉闭环** vision=kimi，截图（深/浅）→亲自看图自评→调参→再看（修复前 5 轮盲调）。调色板/音量形变/冻结降级/呼吸/9 态枚举保持。视觉自评（真实看到）：深色 = 左上晶体高光 + 蓝紫大理石纹流动 + 发光边（Siri luminous）；浅色 = 饱和宝石大理石 + 全反射深边 + 微晶体高光，**清透不浑浊** |
| **v7.0** | 2026-08-26 | **用户打回 v6（「还是缺少了透明感啊。参考我们第一版的obs，那多好看啊」）→ 仅修复透明感，非重做**：把 v1 obs（jarvis orb）透明机制融合回 v6 结构。① **extractAlpha**（jarvis L1618-1621）alpha 由材质亮度导出（`a=pow(max(rgb)·1.10,1.45)·shape`，亮实暗虚）；② **背景混合**（jarvis L1662）透明 canvas + 非预乘 SRC_ALPHA，浏览器按 `(1-a)` 混入背景色（暗部透出 = 液态玻璃）；③ **背景亮度感知**（jarvis L1640/1657/1664）沿用 isLight——深色少透（Siri 发光体量）+ halo 弱化 0.16，浅色更透明 `a×0.96` + 提饱和×1.42 + 深边 fresnel 0.50 + 边缘 alpha 抬升（白底定义不浑浊）；④ **预乘输出判定**（jarvis L1682）保持 v6 非预乘（边缘 bug 不回归），透明感靠「暗部 α 低 + 背景透出」；⑤ **防空心** 与 v3 空心剖面不同（v6 中心材质明亮 → 中心实），轮廓膜 `shape` 保边界（不散雾、边缘干净）。8 组件 / 域扭曲流体 / isLight 分支全保留。视觉自评（vision 读截图）：深色 = 发光磨砂玻璃球，暗部虚化透背景；浅色 = 饱和蓝宝石玻璃（右/底部透白背景**不浑浊**）+ 深边定义 + 实心高光 |
| **v8.0** | 2026-08-26 | **用户裁定（08-26 01:11「还是用纯Orb吧，然后Orb用颜色和文字来显示状态」）→ 状态表达重做（§10）**：放弃 v1-v7 的 9 态复杂动画叠加（听写震动 / 后台 agent 蓝轨道点环绕 / 霜环 / busy 绿光晕**全部叠加层不做**）；orb 本体保留 v7 透明感液态材质；状态表达 = **orb 颜色变化（§10.1 9 态颜色映射表，sapphire/violet 家族衍生 + 错误专属红/琥珀）+ 文字标签（§10.2，11.5px/500/语义色≥4.5:1/6px 色点，冻结态简洁）**；状态间过渡 0.3s ease + 听写中克制亮度脉动（±6%，替代被裁定的震动，§10.3）；mic-error/frozen-error 用独立红/琥珀色板覆盖（色相旋转不可达）；新增断言 V1-V6（9 态两两可区分/文字对比度/0.3s 过渡/透明感继承/冻结语义区分/零叠加层）。可视化 `mic-bubble-visual-v8.html`（9 态切换 + 亮/暗主题） |
| **v8.1** | 2026-08-26 | **用户五条反馈合并迭代（08-26 07:31/07:32）**：① **文字标签整个去掉**（§10.2 作废）——状态纯靠颜色，可区分性靠 9 态颜色间距 + 动作分层双通道，aria-label 保留给读屏；② **清透感再提升、颜色浅一档**（§10.1 注④：alpha 曲线 1.45→1.58 中段更透 / shade 0.62→0.65 / 亮度微抬 ×1.03·×1.12 / CSS inset 暗部 0.42→0.30 + 基底提亮；v7.1 a³ 压暗修复不回归）；③ **点击波纹展开**（§10.3②，jarvis orb-ripple 范式 scale 0.8→1.5 / opacity 0.6→0 / 0.8s ease-out，语义色描边）；④ **动作感分层 L0-L3**（§10.3③：工作态 listening/nebula-busy L3 流体加速+脉动、processing/bg-agents L2 流动、idle L1 极轻呼吸、frozen/frozen-error/mic-error/offline L0 静止；动作全部在 orb 内部/光晕，不形变不位移）；⑤ **颜色过渡 0.3s→0.7s ease**（shader lerp 0.06-0.08→0.035-0.04）。断言更新 V1-V9（V2 改无文字断言 / V3 改 0.7s / 新增 V7 动作分层 / V8 波纹 / V9 清透提亮不回归）。**视觉自验抓获并修复 v8.0 遗留 bug**：shader YIQ `adjustHue` 旋转方向与 CSS `hue-rotate` 相反（nebula-busy WebGL 路径渲染成暗青而非亮暖紫），取反对齐（§10.1 注⑤）。可视化 `mic-bubble-visual-v8.html` 同步 v8.1 |
| **v8.2** | 2026-08-26 | **用户反馈「动作感参考 jarvis——听写随声波震动、processing 波纹旋转；这一版还是没区分好」（§10.5）**：根因 = v8.1 动作过弱（rot 0.08-0.25 肉眼不可见、listening 仅亮度脉动无形变、hover 恒 0.08 无音量联动）。逐项移植 jarvis OrbRenderer 机制：① **hover='volume'**（L1524/L1744-50）listening 态 hover 由 RMS 驱动（`0.10+v·0.90`，hoverIntensity 1.4）→ orb 随声波震动；② **rotSpeed 分层**（L1522-28）processing 1.0（=idle 20×，波纹明显旋转）/ nebula-busy 0.6 / listening 0.3 / bg-agents 0.15 / idle 0.05 / L0 态 0；③ **stateScales**（L1515）listening 1.08 / processing 1.05，scale lerp 0.08；④ **transitionPulse**（L1761/L1788-93）切换瞬间 hover+0.3 脉冲 ×0.92 衰减；⑤ hue/sat/lum 保持 0.7s 过渡不回归。预览页新增模拟音量面板（自动声波 3.2Hz×0.23Hz+抖动 / 手动滑块 / 音量表 / `?vol=` 固定）+ 自动循环演示（9 态 2.4s 逐个）。新增断言 V10（音量震动双帧 ≥3%，实测 9.5%）/ V11（processing 旋转双帧 ≥5%，实测 16.2%；L0 态两帧差=0）/ V12（stateScale bbox + 脉冲收敛）。v8.1 已确认项全部保留。视觉闭环：截图 6 张（震动高低帧/旋转两帧/波纹/亮暗）vision 自验全 PASS、console 0 error |
| **v8.2.1** | 2026-08-26 | **用户反馈「orb 突然刷新一下，不流畅」（08-26 08:23，§10.6）**：主根因 = shader `t=iTime·timeScale` 中 iTime 为绝对时间，切态 Δts 致 t 瞬跳 `iTime·Δts`（60s 后切 idle↔listening 跳 ~66s）→ noise 流体场整体瞬移。修复 5 处：① `phaseTime += dt·timeScale` 连续累积（timeScale 恒 1，只改流速不跳相位）+ dt 钳制 0.05；② transitionPulse 软启动 rise→decay（消除首帧 effHover +0.3 阶跃）；③ lum 脉动幅度 lerp + 相位累积（消除 ≤6% 亮度硬切）；④ hoverIntensity lerp 0.08（消除 1.0↔1.4 硬切）；⑤ 主题切换不再误调 select 重触发脉冲。自验（Playwright 逐帧像素差分 idle→processing）：切态 300ms 峰值 14.74%→1.41%（10×）、console 0 error、音量震动回归 PASS。新增断言 V13（切态 300ms 峰值 ≤3%）/V14（主题切换无脉冲）。动作语义零回归 |
| **v8.2.2** | 2026-08-26 | **用户反馈「麦克风V8.2在错误状态下的显示不是一个晶莹的气泡」（§10.7）**：根因 = STATES `css:true` 状态分支绕过 WebGL 渲染层 → 错误态退回平涂 CSS 渐变球（流体/透光/fresnel/高光全丢 = 哑光气球）。修复 4 处：① shader 色板常量 → `palA/palB/palC` uniform（默认值逐位等同原常量，7 正常态零回归），错误态走红/琥珀色板同一晶莹结构；② 色板 lerp 0.04/帧（0.7s 过渡不硬切）；③ 渲染路径统一（删 `css:true`，仅 WebGL 不可用才降级）；④ CSS 降级层晶莹化（`--orb-base` 同结构渐变 + `--orb-inset` 同色系暗影）。新增断言 V15（错误态晶莹结构保留）/V16（错误态过渡平滑）。视觉自验 6 截图全 PASS（mic-error/frozen-error 深+浅 hero、idle 回归、9 态同框） |
| **v8.2.3** | 2026-08-27 | **用户反馈「WebGL 光球下有白色圆形底座，不随环境变化」（§10.8）**：根因 = halo 混色提亮球缘外圈 rgb + extractAlpha 从 rgb 导出 alpha + skirt 衰减带过宽（0.98-1.14r0）三者合成实心白雾环（demo 深底不显眼、毛玻璃面板露馅）。修复（方案 a 底座透明，只动 alpha 轮廓两行、材质光学零触碰）：skirt 收紧 0.96-1.05r0（1.05r0 外 alpha 归零）+ 浅色边缘抬升窗收到 0.96r0；halo 代码保留、外显由 skirt 门控（C8/B9 同步修订）。验证：环带扰动 32.8%→3.0%（暗）/34.7%→3.2%（亮）、球体 chroma 逐位持平、明暗目检白盘消失 |
| **v8.2.4** | 2026-08-28 | **用户反馈「语音气泡的边缘像抠图不干净一样，不好看」（§10.9）**：三处边缘链路手术，球体内部材质（≤0.92r0）逐位不动。① rim/fresnel 改在**收缩球**（Rf=0.90r0）上求值并加 0.92-1.00r0 衰减窗——亮边完整收入不透明轮廓内，不再骑跨 alpha skirt（旧实现 fres 峰值 0.985r0 正落在裙边带 0.96-1.05r0 上，亮边被 alpha 裁成半透明白描边=贴纸抠图感）；② alpha 从**去 rim 的体色**（colNoRim）导出——rim/halo 提亮不再经 extractAlpha 耦合抬升边缘 alpha（§10.8 同族根因）；③ 浅色 alpha 抬升项 `·shape` 门控——旧 smoothstep 过 0.96r0 后恒 1，在整个 canvas 上 orb 外涂 0.12 常数 alpha 方形薄纱（硬方形边界=浅色抠图感）。验证：边缘探针（裙边外真零区 1.15-1.60r0）10/10；v8.2.3 白底座回归 14/14（环带扰动进一步降至 0.87% 暗/1.87% 亮、core chroma 逐位持平）；§10 断言 35/35；6 组 3x 放大前后对比目检白描边/灰晕消失、rim 高光保留在轮廓内。证据 docs assets mic-orb-v824/ |
| **v8.2.6** | 2026-08-30 | **用户反馈「麦克风状态气泡的背景荧光改为渐变过渡，边缘看不出硬边」（§10.10）**：根因 = v8.2.5 的 `shape=1-smoothstep(r0*0.96,r0*1.05,len)` 把 glow 限制在 ~0.09r0 窄带（径向 maxDelta=**14.75** 硬 cliff）+ 48px canvas 被 body 填满 ~0.94uv 只剩 ~4px→glow 被 canvas 边界硬裁切（canvas 边缘 alpha=**51** 铁证）。修复：① canvas 48→64px + shader r0 缩至 0.56-0.66uv（uv±1.0=64px，skirtEnd ≤1.28r0=0.78uv <1.0uv，bloom 有 ~14px field 不被裁；身体视觉尺寸保持 ~40px）；② 暗色宽 bloom skirt 0.92-1.28r0、亮色收窄 0.92-1.15r0（防雾环/白底座），身体 ≤0.92r0 不透明（B9）；③ halo exp 9.0→4.5、窗 1.02-1.30r0、幅度 0.18、×1.12（C8），alpha 仍从 colNoRim 导出（v8.2.4，bloom 不亮环/不白底座）。验证：edge-assert（A1-A4 明暗 × 4 态）34/34；径向 maxDelta 14.75→2.90（暗 idle）/8.93（暗 listening）、mono 90-100%；body chroma 逐位持平、A1 bodyEdge 107/76 > glowPeak 41/43。证据 docs assets mic-edge-fade/ |
| **v8.2.8** | 2026-08-30 | **用户反馈「外围那一圈光晕/荧光整个不要，只保留中间亮的气泡本体」（§10.11）**：v8.2.6/7 暗色渐变荧光调优后外圈仍难看 → **直接移除外圈**。修复：① 删 halo bloom（裸 `outCol=col`）；② skirt 收紧至 **1.06r0**（本体剪影 ~0.14r0 羽化）；③ 同步删无用 bodyA。本体 ≤0.92r0 材质逐位未动（径向 profile 0-0.58 前后重合实证）。验证：暗/亮前后对比外圈消失、本体保留、console 0 error。断言：A1'/A3'（无外圈 + 本体羽化平滑）取代 A1-A4；B9 固定半径零区恢复。证据 docs assets mic-orb-v828/ |
| **v8.3.0** | 2026-09-02 | **配色预设系统**（§10 版本族外，design 20260902_micorb-presets-design.md）：9 预设（含 09-03 裁定第 9 预设冰山）+ 状态-配色映射 + 设置面板外观节；hue 列归零（配色=状态语言）；PAL_DEFAULT 降级为回退。spec 佐证 tests/micorb-presets.spec.mjs 6 断言 |
| **v8.4.0** | 2026-09-03 | **用户需求「听写中音量越大形变越大、波形有机不要整齐正弦」（§10.12）**：云 STT 路径复用 onMicChunk 现成 RMS，Web Speech 路径 orb 自开只读 VoiceTap（AnalyserNode，不接 destination，退态即停，被拒降级基础幅度）；`setVoiceLevel` 注入 + attack 70ms/release 280ms 帧率无关平滑 = uVol uniform（非 listening 态目标强制 0，注入不泄漏）；边缘位移 = 3 角向谐波（整数波数 3/5/8 无接缝）× 不通约时间漂移（4.7/−6.9/11.3 rad/s）× 慢幅度抖动（depth 0.28）× amp 0.062，VOICE_WAVE 单源同生成 GLSL 与 voiceWaveAt 测试镜像；叠加 §10.5 hover 震动与噪声形变。断言 V18-V23（tests/micorb-volume.spec.mjs 6/6 PASS，SwiftShader 实测）；F1/F5/预乘/九态/色板零回归（micorb-presets 6/6 复跑全绿）。小/大音量对比截图 docs 20260903_micorb-volume-{small,large}.png。真机听写观感留作者复验 |
