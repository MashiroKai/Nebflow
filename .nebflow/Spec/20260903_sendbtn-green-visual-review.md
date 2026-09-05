# send-btn 绿色身份强化 — 视觉验收报告（下游合并闸门）

- 日期：2026-09-03 · 评审人：design-engineer（视觉评审角色，独立 vision 逐张看图，不信任上游自评）
- 对象：worktree `.nebflow/worktrees/sendbtn-green` 分支 `sendbtn-green`（CSS commit **127e7faf** + merge e3f1491c）
- 上游：实施报告 `20260903_sendbtn-green-impl.md`（节点 n-c4b4f204）；评审依据：PR #44 三维评审 `20260903_pr44-review.md`（§b 材质锚点证据：sapphire.css:158 / input.css #stop-btn 同材质引用）、作者裁定五条、亮暗双主题
- 取证方式：① Read 全部 10 张截图逐张 vision 过目；② Python/PIL 裁剪放大按钮区生成 10 格对照图复核层次细节；③ 对照 PR #44 被否版本 before/after 截图；④ `git diff 7710190c..127e7faf -- input.css` 逐值核对定值表（只读，主仓与 worktree 产品代码零改动）

## 一、逐项评审表（1-6）

| # | 项 | 判定 | 证据描述 |
|---|---|---|---|
| 1 | enabled/hover/active × 双主题均为可透玻璃质感（一票否决项） | **PASS** | 代码层：全部 6 态 bg alpha<1（0.55/0.62/0.68/0.72/0.75/0.78），`blur(8px) saturate(1.3)` 与双内缘、1px 细边框全部保留（diff 逐行确认）。视觉层（放大对照图）：enabled/hover 可见顶部内缘高光、绿色 glow 光晕与细边框环——玻璃语言三要素俱在；active 可见按压内阴影造成的顶部微暗层次。无任何一态呈现实心色块（对照 PR #44 after 的 0.92 近实心盘：无高光无层次，差异一目了然） |
| 2 | enabled 一眼读出 logo 绿且明确可点；glow 克制 | **PASS** | 亮主题 0.55 呈明亮薄荷绿、暗主题 0.62 呈明确深 logo 绿，均与「读作禁用」的 0.42（pr44-before 截图：洗白淡薄荷）拉开显著差距，可点性无歧义。glow 为 0.20/0.25 级柔和光晕（0 0 12px），贴按钮边缘收敛，不侵入输入栏整体——克制达标 |
| 3 | enabled→hover→active 三档层级清晰 | **PASS** | 亮：亮薄荷 → 饱和绿 → 深饱和绿+按压内阴影；暗：深绿 → 更饱和深绿 → 最深+内阴影。两主题三档逐级加深在放大对照图中逐格可辨，active 内阴影提供「按下」语义，反馈层级明确 |
| 4 | disabled 淡绿 0.16+图标淡化；frozen 图标不淡化 | **PASS** | disabled 两主题均呈极淡绿幽灵圈+图标淡化至 0.45（明确「暂不可用」）。**frozen 重点核对通过**：放大对照图中 frozen-light/frozen-dark 的纸飞机图标为全强度白，与 disabled 的淡化图标对比显著——`#input-bar.frozen #send-btn[disabled] svg{opacity:1}` 守卫生效的直接视觉证据（与上游 computed 断言 svgOpacity=1 一致） |
| 5 | 双主题质量与相邻 UI 协调 | **PASS** | 暗主题不发闷（0.62 补 alpha 策略生效，绿色身份明确）；亮主题不刺眼（柔边+光晕而非高饱和平涂）；绿色身份两主题一致（同色相 #07C160 族，仅明度随主题适配）。相邻 UI：attach 纸夹未动、frozen 态蓝色冻结环与淡绿钮无冲突；#stop-btn 截图中不出现（仅流式时显示），同材质关系由代码锚点注释保留（input.css #stop-btn 注释未动）佐证 |
| 6 | 上游自评与实图/实码一致性抽查 | **PASS** | 定值表 12 个数值与 `git diff` 实际值**逐值一致**（含 dark 覆盖块 0.62/0.72/0.78 与 frozen 守卫新增）；声称「全部态 alpha<1 且 blur 保留」「frozen svgOpacity=1」「规则族外零触碰」均经独立核实为真（diff --stat 仅 input.css 一个文件为本批改动，其余为 main merge 带入）。未发现实图/实码与声称矛盾 |

## 二、逐张截图核验清单（10/10 已过目）

| 截图 | 过目 | 要点 |
|---|---|---|
| enabled-light | ✓ | 明亮薄荷绿玻璃，顶部内缘高光+glow 光晕可见，明确可点 |
| enabled-dark | ✓ | 深 logo 绿，不闷，白图标 crisp，glow 收敛 |
| hover-light | ✓ | 比 enabled 明显加深一档，玻璃边缘仍在，非实心 |
| hover-dark | ✓ | 饱和加深，光晕微升，层级清晰 |
| active-light | ✓ | 最深档+按压内阴影；饱和度接近家族上限但 alpha=0.75 仍半透明（见观察备注 1） |
| active-dark | ✓ | 最深+内阴影，按压语义明确 |
| disabled-light | ✓ | 极淡绿 0.16 幽灵圈+图标淡化，「暂不可用」无歧义 |
| disabled-dark | ✓ | 淡绿描边圈+灰化图标，暗底可读 |
| frozen-light | ✓ | 蓝调冻结栏内淡绿钮，**图标全强度白**（守卫生效） |
| frozen-dark | ✓ | 同上，图标不淡化，与 disabled-dark 淡化图标对比显著 |

另生成放大对照图（/tmp/sendbtn-zoom-sheet.png，10 格）与 PR #44 对照（pr44-before/after 截图）作为复核中间产物。

## 三、修正建议（非阻断，数值级，供作者/维护者参考）

1. **亮主题 hover/active 饱和度观察项**：在 harness 近白输入栏背景上，hover 0.68 / active 0.75 视觉已接近饱和绿（玻璃通透感在低对比后景下不易显现）。当前取值在作者裁定区间内（hover ~0.7 级、active 再深一档），不构成 FAIL；若作者日后希望亮主题玻璃感更强，可数值微调 hover 0.68→0.65、active 0.75→0.70。真实聊天后景下 backdrop blur 的通透层次会比 harness 白底更明显，建议合并后在真实使用中以作者眼感终审。
2. **frozen 态背景色变化（上游遗留 #2，确认可接受）**：冻结钮背景随 `:disabled` 新族从灰玻璃变为淡绿 0.16。视觉评审结论：淡绿与「不可用」语义一致、与冻结蓝环无冲突，且评审裁定仅要求图标不淡化（已满足）——**不需要**补背景覆盖；若作者坚持冻结保灰底，一行 `#input-bar.frozen #send-btn[disabled]{background:rgba(170,170,170,0.3)}` 即可。
3. **截图取证局限说明**：10 张截图的按钮后景为 harness 中性渐变/输入栏白底，非真实聊天内容；玻璃通透度在真实消息流后景上表现会不同。该局限由上游 computed-style 断言（bg/border/svgOpacity/blur 10/10 PASS）兜底，本评审认可此覆盖组合。

## 四、判定与依据

清单 1-6 全 PASS，一票否决项（任何态实心化）不成立：全部态 alpha<1、blur/内缘/细边框玻璃锚点完整保留，PR #44 被否理由（实心替换玻璃）在本批彻底消除，同时其两条被采纳贡献（淡绿 disabled 族、disconnected hover 守卫）与一条评审提醒（frozen 守卫）均正确落地。

**最终判定：PASS（可合并）**

下游「合并-sendbtn进main」可执行：合并 `sendbtn-green`（127e7faf）回 main + 前端产物重建 + 应用重启后生效。
