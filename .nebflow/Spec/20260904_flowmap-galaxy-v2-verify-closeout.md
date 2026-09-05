> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Flow Map Galaxy v2.0 原型验证收尾——19/19 全绿 + M15 原生滚动陷阱根因报告

> **日期**：2026-09-04 · **产出**：design-engineer · **性质**：被宿主重启打断的 delegate 会话（sessions/delegate-design-engineer-115dda77.json）恢复收尾
> **对象**：Galaxy v2.0 星体原型 `assets/20260903_flowmap-graphview-galaxy/prototype.html`（12 模拟节点、A1-A19 断言版）
> **状态关系**：Galaxy 视觉方向已由作者于 2026-09-03 09:40 裁定舍弃、转 v3 方向（见 `20260903_flowmap-graphview-galaxy-design.md` 头部封存注记与 `20260903_flowmap-graphview-v3-final.md`）。本文仅是 v2.0 原型验证的**事实归档与教训沉淀**，不改写封存状态；其中 M15 陷阱对 v3 及一切固定视口 UI 仍然有效（见 §4 风险提示）。

---

## 1. 验证终态

`verify-galaxy-proto.mjs`（Playwright，亮/暗双主题 + reduced-motion 三上下文）：

```
===== 19/19 PASS =====   控制台零错误
```

完整输出：`assets/20260903_flowmap-graphview-galaxy/verification-final.log`。19 条 = A1-A11 + A13-A18 + A17b + A15d。截图证据 8 张（亮暗 × default/hover/select + WS 后 + reduced-motion）：`assets/20260903_flowmap-graphview-galaxy/shots/`。

恢复前断点：17/19，剩 A15（视差 Δ=0）与 A14（视口外 focus 带入失败）。上一会话结论原话：「不猜了，直接探针定位」——本次以三个探针完成定位（均已归档）：

| 探针 | 用途 | 关键发现 |
|---|---|---|
| `probe-galaxy.mjs` | 带仪器复现 verify 前置序列（包 focusNode/animateCamera/fit 记调用轨迹） | A14 的 focusin→focusNode→animateCamera 链路本身**完好**，相机精确收敛到目标 (288,−785)，但星体实测在 (−344,−42) |
| `probe-galaxy2.mjs` | 逐帧对照 cam vs world 计算矩阵 vs 星体实测坐标 | 相机矩阵与 cam 始终一致，星体在 Y 方向恒偏 −385——**偏移发生在 cam 模型之外** |
| `probe-chain.mjs` | 逐级打印 viewport 祖先链 rect/transform | `#viewport`（absolute inset:0、transform:none）rect x=−483，父级 `.fm-stage` 却在 x=0 → **`.fm-stage` 被编程滚动 scrollLeft=483**；触发点=键盘选中循环（focus n11→Enter→Esc） |

## 2. 两条失败断言的根因与修法

### A14「视口外 focus 带入」— 根因：原生 focus scroll-into-view 污染相机模型

- `#viewport` 是 `overflow:hidden`——**仍可被程序化滚动**。`el.focus()` / Tab 聚焦触发浏览器原生 scroll-into-view，在 viewport 上产生 scroll 偏移（本例 scrollTop=385），与 cam 的 transform 平移**叠加**，焦点带入动画数学全对、视觉落点却偏出视口。
- 该机制只咬「聚焦目标在视口外」的场景——恰好是 focusNode 带入存在的意义，所以 A5（点击可见节点）从未暴露它。
- **修法（原型层，三处）**：① `focusin` 处理器开头 `viewport.scrollLeft = viewport.scrollTop = 0` 即时归零，保证带入数学以 cam 为唯一真源；② viewport 挂 `scroll` 监听兜底归零（Tab 真实键盘导航无法 preventScroll）；③ 程序化焦点归还改 `focus({ preventScroll: true })`。

### A15「视差 Δ=0」— 根因：幻影溢出区让 `.fm-stage` 被 scroll-into-view 推偏 483px

- 关闭态详情面板 `transform: translateX(calc(100% + 24px))` 移出右缘——**transform 计入可滚动溢出区**，`.fm-stage`（overflow:hidden）因此成为可编程滚动容器。
- 键盘选中（Enter）时面板正从收起位向屏内过渡，`dClose.focus()` 落在屏外坐标 → 浏览器对 stage 做 scroll-into-view → scrollLeft=483 → **整个舞台（含 viewport）左移出窗**。
- 测试侧的表象：A15 拖拽起点 (r10.x+300, r10.y+300) = (−182, 346) 落在窗口外，`elementFromPoint` 返回 null，pointerdown 根本没到达 viewport → 拖拽未发生 → dust Δ=0。**视差机制本身零缺陷**（探针在干净状态下测得 Δ=−10.0 精确命中 −0.05×200）。
- **修法（原型层）**：滚动防线从 viewport 扩展到 `.fm-stage`（同模式 scroll 监听归零）+ `dClose.focus({ preventScroll: true })`。舞台从此不再可被推偏，测试落点恒在窗内。
- 测试脚本侧无需改口径：根因消除后原断言（Δ≈−10±3）直接通过。

### 设计规则沉淀（M15，写给实现方）

> **固定视口 + 相机 transform 模型下，`overflow:hidden` ≠ 不可滚动。** 原生 focus scroll-into-view 会编程滚动任何 overflow:hidden 祖先；离屏面板的 transform 位移还会制造幻影溢出区。凡「内容位移全由相机/transform 承担」的容器链（stage/viewport/任何 positioned 祖先），必须挂 scroll 归零防线 + 程序化 focus 一律 `preventScroll:true`。

## 3. 产物清单

| 产物 | 路径 |
|---|---|
| 原型（已修，19/19） | `~/.nebflow/docs/Nebflow/assets/20260903_flowmap-graphview-galaxy/prototype.html` |
| 验证脚本（A1-A19） | 同目录 `verify-galaxy-proto.mjs` |
| 探针 ×3 | 同目录 `probe-galaxy.mjs` / `probe-galaxy2.mjs` / `probe-chain.mjs` |
| 终态日志 | 同目录 `verification-final.log` |
| 截图证据 ×8 | 同目录 `shots/`（light/dark × default/hover/select + light-after-ws + dark-reduced-motion） |
| 规格书 | `~/.nebflow/docs/Nebflow/20260903_flowmap-graphview-galaxy-design.md`（v2.2 **sealed**，本文为其 v2.0 原型线的验证附记） |

## 4. 对 v3（已采纳方向）的风险提示

`assets/20260903_flowmap-graphview/prototype-v3.html` 已有 viewport 级 scroll 归零防线（line 833，「v3.2 防线」注记——后续会话曾独立踩中 viewport 层），但：① `.fm-stage`（line 106 `overflow:hidden`）**未挂防线**，dClose 幻影溢出场景（本报告 §2-A15）仍可推偏舞台；② line 1028 `dClose.focus()` 未带 `preventScroll:true`；③ focusin 带入处理器（line 1031）未在读数前归零 scroll。建议 v3 实现方补这三处（同 §2 修法），主仓实施时一并落实 M15。
