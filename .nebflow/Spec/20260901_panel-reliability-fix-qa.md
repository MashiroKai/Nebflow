# #27 面板渲染可靠性三缺陷修复 — QA 复验报告（PASS 放行）

- **日期**：2026-09-01
- **验收人**：qa-frontend
- **产出者**：Frontend
- **交付**：分支 `feat/agentmd-panel-fix` @ **6c1d8fda**（基于 main @9bb38304，含 a508f011；未合并）
- **复验基座**：**隔离真实 http4s 实例** `http://localhost:8093`（java PID **6573**，cwd=`/private/tmp/nb-agentmd-panel`，health 200）
- **宿主保护**：8080 LISTEN = PID 61573 = 我所在宿主，全程未触碰；8093 已 PID 验身为独立 java 实例（cwd 确认）
- **结论**：**PASS —— 放行。** checkJs 门禁红已修复，功能全绿，无回归，附加 minor 一并修复。

---

## ① checkJs 合并门禁：✅ PASS（上轮唯一 FAIL 项，已修复）

```
$ cd /tmp/nb-agentmd-panel && node scripts/check-js-types.mjs
checkJs: 326 errors (baseline 326, zero-gate files: 3)
checkJs PASS: no new errors above baseline.
```

- `npx tsc -p jsconfig.json --pretty false` 定位：**`flowMapTab.js` 报 0 错**（上轮 `TS2339 dataset on Element` 已消失），总误差 **326 = baseline**。
- 修复代码（flowMapTab.js L30-35）：`querySelectorAll` 加 `/** @type {NodeListOf<HTMLElement>} */` 断言，覆盖整条链，`.dataset` 合法。
- `flowMapTab.js` 为无 baseline 条目新文件 → 按门禁「new/touched files must be checkJs-clean」现**零错**，达标。

## ② 功能三断言（我 f82854a 取证反转）：✅ 全部确认

| 断言 | 修复前 | 复验实测 | 判定 |
|---|---|---|---|
| `#team-canvas-style` 注入 | false | `styleInjected:true` / `styleInHead:true` / `overlayCssPresent:true` | ✅ |
| 保存按钮 elementFromPoint 命中 | pane 拦截 | `hitIsButton:true`、`hitId:flow-agentfile-save`、`pointerEvents:auto`、`z-index:60` | ✅ |
| 卡片 5×reload 恒定 | 时 0 | **`ready:2 ×5`** 恒定 | ✅ |

**产者 harness 独立复跑：14/14 PASS**（`/tmp/qa-panel27/verify.mjs`）
S1 首开 / S2 恢复 / S3 真实 404 未挂载 / S4 稳定性×5 / 控制台无错，全绿。

## ③ 我的独立盲区探针：12/12 PASS（`/tmp/qa-panel27/qa-blindspots.mjs`）

| 探针 | 结果 |
|---|---|
| **B1 样式生命周期**（根因#2 真正证明）：关闭注入样式标签页后 FLOW_CSS 存活 | ✅ `inHead:true`、`overlayRule:true`、pane 已移除仍存活 |
| B1 幂等（无重复 id） | ✅ `dupes:1` |
| **B2 节点卡片不塌陷（a508f011）**：label 高度 / 内容不溢出 | ✅ 三节点 labelH=15、cardH=133/134/94、`resultInside:true` |
| **B5 仅 flow-map 标签页恢复**（无 projects 标签页）→ 独立渲染 | ✅ `fmState:nodes`、3 节点、样式在 head |
| **B4 /api/projects 500** → 显式 error 态 | ✅ `state:error`「项目列表加载失败」，非卡 loading |
| **B3 flow-map archived 态** | ✅ `fmState:archived`「1 个节点已到期归档…」 |
| **B3 flow-map error 态**（500） | ✅ `fmState:error`「Flow Map 加载失败」 |
| **B6 真实鼠标坐标点击**保存（page.mouse.click） | ✅ status「✓ 已保存」 |
| B6 视觉：弹窗无 overlay 遮罩 | ✅ `overlayBg: rgba(0,0,0,0)`、`backdropFilter:none` |
| B6 视觉：面板毛玻璃 | ✅ `blur(24px) saturate(1.15)`、`bg rgba(255,255,255,0.55)` |
| 探针期间 JS 错误 | ✅ 无 |

## ④ C1 静态资源合同：✅ PASS

```
node scripts/verify-web-assets.mjs http://localhost:8093
checked=276 failures=0 → C1 PASS
```

## ⑤ 附加 minor（.fm-result-summary 横向溢出）：✅ 一并修复

根因：`max-width:132px` 写死，卡片内容宽仅 104px（124 − 左右各 10 padding）→ 必然左右各溢 4px。改 `max-width:100%` 贴内沿收边。
独立几何复测（`qa-geo.mjs`）：
```
n1 resultW=102 overflowLeft/Right=-11（负值=内缩，不再溢出；修复前 +4）
n2 resultW=50  overflowLeft/Right=-37
```
**溢出已消除**（负值即内缩），长摘要照旧省略号截断（labelScroll 正常）。此修复无回归，属收尾干净。

---

## 复验确认：实例在跑新代码（非 JVM 快照陈旧）
- served `js/flowMapTab.js` 含 `NodeListOf<HTMLElement>` ✓
- served `css/flowMap.css` 含 `max-width: 100%`（无旧 `132px` 残留）✓
- health 200 ✓

## 结论
**PASS —— 功能全绿、门禁达标、无回归、附加 minor 干净收尾。** 可合并。
- branch：`feat/agentmd-panel-fix` @ 6c1d8fda
- 本次复验改动范围呈最小（diff a508f011..6c1d8fda = 2 文件 / 7+/2-，恰为 checkJs 断言 + CSS max-width 两处）

报告：~/.nebflow/docs/Nebflow/20260901_panel-reliability-fix-qa.md（~/.nebflow commit d98bdea）
