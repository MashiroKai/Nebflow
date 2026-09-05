# FlowMap 归档面板 v3 · D-QA1 修复版 — 视觉评审报告（第三轮·重派3 第二环）

- 日期：2026-09-04 05:2x · 评审人：design-engineer（视觉评审·第三轮，独立取证，零产品改动、零 git 写操作）
- 评审对象：worktree `.nebflow/worktrees/flowmap-archive-panel` 分支 `flowmap-archive-panel` @ **15c171b7**（= dfae15ce merge main → f7fbeccd D-QA1 修复（RO 落定重判）→ 15c171b7 resize-follow 动态断言 spec）
- 前置链：重派1 QA FAIL（D1/D2/D3）→ 修复 → 重派2 视觉 PASS / QA v2 FAIL（**D-QA1**：resize 事件时刻读 flex-basis 0.32s 过渡前旧宽 → dock 形态锁死，两形态）→ 本第三轮修复（上游 n-f66b7d81）→ 本评审
- 依据：规格 `20260903_flowmap-archive-panel-spec.md` §5.8b（含 2026-09-04 修订注记）+ QA v2 报告 §1 缺陷精确形态 + nebflow/visual-style 铁律
- 取证纪律：全部第一方自跑（route 拦截引 worktree 真实 js/css + 产品夹具，零端口绑定 `localhost:1`），未触 8080/8091/8092/宿主 PID 77854；Playwright 全程一次通过（两处探针自身缺陷经归因复测证伪，见 §4）；评审后 worktree 恢复洁净（临时 spec 已删，`.sgit status` 零改动）

---

## 0. 判定

**PASS** —— D-QA1 两个锁死形态双灭（几何实测 + 截图 + QA v2 探针复跑三重证据），D2 即时判定语义零回归（click→dock 置位 Δ=11.1ms 同步判定），host 口径交叉实证四向全过（含 RO 独有的「无 resize 事件纯 host 尺寸变化重判」新能力实证），D1/D3/链语义/mailbox/hover/双主题/reduced-motion 无回归，上游自评逐项核实无虚报。下游 QA 节点闸门放行。

## 1. D-QA1 闭合（本轮核心，任务书必做项 1+2）

### 修复机制实码复核 ✓
`flowMapArchive.js`（f7fbeccd，+54/−7 零越界）：`observeHost` 对 host（`ctx.layer.parentElement`）挂模块单例 `ResizeObserver(rejudgeAllDocks)`——RO 每帧布局落定后回调、终帧即最终宽度，天然免疫 transition 时序；**RO 纯作触发时机，宽度仍读 live `clientWidth`，`DOCK_MIN_HOST_W=780` 判定式零触碰**（直接开面板/开详情路径语义不变）；无 RO 环境降级 = resize 立即重判 + 双 rAF + window 级 `transitionend` 过滤 `flex-basis`；生命周期 4 处惰性清扫点统一 `dropCtx`（停观察+出册），同 host 多 ctx 保留观察防误停。与上游报告 §选型 逐字吻合。

### V1 · resize 序列 1280→1920→1280（两锁死形态双灭，本评审第一方探针）
落定口径：≥500ms + 双 rAF + detail `right` 值两帧收敛（上限 2.5s）。

| 步骤 | 实测 | 判定 |
|---|---|---|
| @1280 基线 | host 470，dock=false，right=16px，详情 [893..1253] 在 host [799..1269] 内，elementFromPoint 中心命中详情自身 | ✓ overlay 正常 |
| **→1920 落定后** | host 790≥780，**dock=true，right=404px，面板×详情交叉面积=0**（间隙 8px），双双在 host 内，命中自身 | ✓ **症状①（overlay 锁死）灭** |
| **→1280 落定后** | host 470，**dock=false，right=16px**，详情 [893..1253] 完整回 host 内，命中自身 | ✓ **症状②（dock 锁死/详情悬出被裁）灭** |
| 序列零 JS 错误 | clean | ✓ |

截图：`…-visual3-v1-1920-dock.png`（并排形态）、`…-visual3-v1-1280-overlay.png`（**反锁死证据**：详情完整可见——对照 QA v2 `RESIZE-LATCH-BUG.png` 同位点详情整体不可见，现为健康形态）。

### V2 · 单发 resize 仿真（真实最大化/还原，无中间步）
| 步骤 | 实测 | 判定 |
|---|---|---|
| 1280 →(一步) 1920 落定 | host 790，dock=true，right=404px，交叉面积=0，命中自身 | ✓ |
| 1920 →(一步) 1280 落定 | host 470，dock=false，right=16px，详情完整在 host 内 | ✓ |
截图：`…-visual3-v2-single-1920-dock.png` / `…-visual3-v2-single-1280-overlay.png`。

### QA v2 探针复跑（交叉验证，三方证据）
QA v2 `qa-probe.spec.mjs` 原样复跑（仅改截图输出目录/文件名）：**1 passed**。
- QA-2 @1920：`dock=true, right=404px, overlap=false`（症状①转绿）✓
- QA-3 回 @1280：`dock=false, right=16px, detailInHost=true, detailCenterHitSelf=true`——探针打印「dock 撤除正常（未复现锁死）」（症状②转绿）✓
- 法证侧证：`__qaResizeReg` 从 5 降为 **4**——RO 主路径下 window resize 兜底绑定按设计整体旁路，与修复说明吻合。
- 截图：`…-visual3-qaprobe-{overlay470,dock790,latch-fixed,375}.png`（latch-fixed = QA 原 RESIZE-LATCH-BUG 同位点，现详情完整可见）。

### 上游新 spec 独立复跑
`flowmap-archive-resize-follow.spec.mjs`（RO 主路径序列 + delete RO 强制降级单发）：**2/2 passed**（9.9s），与上游自评一致。

## 2. D2 语义不回归（任务书必做项 3）

### V3a · 即时判定时序探针（无等过渡延迟感知）
成员元素捕获真实 click 时刻 + MutationObserver 记录 dock 置位时刻：**click@2205.5ms → dock 置位@2216.6ms，Δ=11.1ms**——判定在 click 处理同步路径内完成（毫秒级），非等 220ms/320ms 过渡。直接开详情 = 即时判定语义零回归 ✓。落定后几何：dock=true，right=404px，交叉面积=0 ✓。截图 `…-visual3-v3-direct-open-dock.png`。

### V3b · host 钉宽交叉实证（四向全过）
| 实证 | 实测 | 判定 |
|---|---|---|
| 视口 1920 不动，host 钉 500（**不派发 window resize**） | RO 直接观察 host 尺寸变化自动重判 → dock 撤（right=16px） | ✓ **RO 独有新能力**（覆盖 col-resizer 拖宽缺口，QA v2 指出的同类暴露） |
| host 钉 900 | dock 复（dock=true） | ✓ |
| 视口 1920→1280 而 host 钉 900 不变 | dock 不变（hostW=900） | ✓ 口径只随 host |
| 视口 700（旧媒体查询 <800 域）· host 钉 900 | dock 仍在，right=404px | ✓ 旧视门口口径此处必失效，新口径成立 |
截图 `…-visual3-v3-vp700-host900-dock.png`。

## 3. 无回归抽查（任务书必做项 4，逐项截图留证）

| 项 | 判定 | 证据 |
|---|---|---|
| D1 · 375 零左裁 | PASS | host [73..391] 宽 318，面板/详情均 [89..375] 落在 host 与视口内，hostScrollW=318=clientWidth 无横滚，elementFromPoint 命中；截图 `…-visual3-r1-375.png` |
| D3 · 四形态零遮挡 | PASS | t1 基线亮（sumR=1853=fabL=1853 贴而不交）/ t4 暗色 / t5 混合（链未齐保留+toast，徽章 10）/ 全归档空态（徽章 12，sumR=1853 贴而不交）——截图 `…-r2-f1-t1-light/-r2-f3-t5-mixed/-r2-f4-all-archived/-r3-f2-t4-dark.png`；375 形态同过（R1b） |
| 链语义 fm-exit 退场 | PASS | r2 完成注入后 150ms 内 `.fm-node.fm-exit`=[r1,r2]（链齐同帧退场），1.4s 后移除且零残留；基线态 3 终态保留卡 opacity=0.78 + 代理边 `i1=>i3` class 含 delivered |
| mailbox 形态 | PASS | 条目 8 = 徽章 8；N 节点标/状态 svg 图标全在；「即将过期」橙标在（TTL 演示链）；摘要单行省略零溢出（8 条 scrollW−clientW 全 0） |
| hover 联动 | PASS | 真实鼠标 hover「Logto 邮件链」→ x1 获 `.fm-adj`；hover 他条零误亮；截图 `…-visual3-r2-hover.png` |
| 双主题 | PASS | 暗色玻璃不发闷、面板/详情/徽章可读、dock 并排零重叠；截图 `…-visual3-r3-f2-t4-dark.png` |
| reduced-motion | PASS | t7 详情展开形态零遮挡（截图 `…-visual3-r3-f5-t7-detail-rm.png`）；**rm 下 resize 双方向跟随正确**（1280↔1920 各落定断言全绿——RO 路径无 transition 依赖，rm 场景免疫） |

## 4. 探针伪影归因记录（首轮教训「裸看单次数值会误报，须稳态复测」再次践行）

本评审探针首跑 26/29，3 项 FAIL 全部归因为本评审自身探针缺陷并复测证伪，**均非产品缺陷**：

| 首跑 FAIL | 根因 | 复测 |
|---|---|---|
| V3a 即时判定 443ms | 探针把条目展开等待计入计时区间 | 改成员元素捕获真实 click 时刻：Δ=11.1ms 同步判定 ✓ |
| V3a2 直接开交叉面积 8960 | 判定后即刻量几何，采到 220ms right 过渡中间态（规格 §5.8b 明示该动画，过程态合法） | 落定后交叉面积=0 ✓ |
| R2-F3/F4 fabHit=true | 探针双击 toggle 致面板关闭 + click 后鼠标停留 fab 上 → `.fm-fab:hover scale(1.05)` 40→42px 各外扩 1px（上轮已记录的同类伪影） | 焦点探针净态（鼠标移离）：sumR=1853 = fabL=1853 贴而不交 ✓ |
| R2d 代理边 proxy='' | 断言跑在 i1 已归档的错误状态（代理边数据源已离场） | 基线态复测：3 终态卡 0.78 + proxy delivered ✓ |

## 5. 上游自评一致性核对（逐项独立复跑，无虚报）

| 上游声称 | 本评审独立复跑 | 一致性 |
|---|---|---|
| resize-follow spec 2/2 | **2 passed** | ✓ |
| QA v2 探针复跑 1 passed（双症状转绿） | **1 passed**（§1 原始数据逐项吻合） | ✓ |
| 归档产品 spec 8 passed / 1 failed（唯一红 T6） | **8 passed / 1 failed**，唯一红=T6 同一等待点（`data-open-flowmap` 90s 超时，既有文档化红） | ✓ |
| 静态护栏 26/26 | **26 passed / 0 failed** | ✓ |
| check-js-types 0 errors（baseline 326 零新增） | **0 errors** | ✓ |
| commit 清单（dfae15ce 零冲突 merge / f7fbeccd +54−7 单文件 / 15c171b7 +236 单文件） | 影子库 log + diffstat 逐一核对，逐字吻合；修复 diff 零夹带零越界（updateDetailDock 判定式未触碰） | ✓ |
| 修复截图 4 张 + 探针复跑 4 张 | 已过目，与本评审自截图同构 | ✓ |
| 测试时序注记（600ms→900ms 落定裕量，335.342px=动画 82% 中间态，非产品缺陷） | 与本评审 V3a2 伪影归因（220ms right 过渡中间态）机理一致，成立 | ✓ |

**无虚报、无漏报**。tasklist-nodes 6/6、canvas-html-interactive 17/17、i18n sweep 11/11 属 QA 域全量项，本评审未重复（留下游 QA 节点复验）；已抽验的 QA 域项（产品 spec 全量/静态护栏/check-js-types）全部与上游一致。

## 6. 证据清单（与报告同目录，`/tmp/nb-visual3-record/deliverables/docs/Nebflow/`）

| 文件 | 内容 |
|---|---|
| …-visual3-v1-1920-dock.png / …-v1-1280-overlay.png | **D-QA1 序列双灭**：@1920 并排零重叠 / 回 @1280 详情完整可见（反锁死） |
| …-visual3-v2-single-{1920-dock,1280-overlay}.png | 单发最大化/还原（无中间步）形态正确 |
| …-visual3-v3-direct-open-dock.png / …-v3-vp700-host900-dock.png | D2 即时判定 + 旧媒体查询域（vp700）host 口径仍成立 |
| …-visual3-qaprobe-{overlay470,dock790,latch-fixed,375}.png | QA v2 探针复跑（latch-fixed=原 RESIZE-LATCH-BUG 位点现健康） |
| …-visual3-r1-375.png | D1：375 面板+详情零左裁 |
| …-visual3-r2-f1-t1-light / r2-f3-t5-mixed / r2-f4-all-archived / r3-f2-t4-dark / r3-f5-t7-detail-rm.png | D3 五形态零遮挡 + 双主题 + reduced-motion |
| …-visual3-r2-hover.png | hover 联动（TTL 即将过期橙标同帧在证） |
| …-visual3-probe.mjs / -output.txt | 主探针源码 + 原始输出（29 项全记录） |
| …-visual3-focus.mjs / -output.txt · …-f4clean.mjs / -output.txt | 伪影归因焦点探针 + 原始输出 |

## 7. 宿主侧落地命令（沙箱 ~/.nebflow 只读为已知前提，产物已全部落 /tmp）

```bash
mkdir -p ~/.nebflow/docs/Nebflow
cp /tmp/nb-visual3-record/deliverables/docs/Nebflow/20260904_archive-panel-v3-visual3-* ~/.nebflow/docs/Nebflow/
cd ~/.nebflow && git add \
  docs/Nebflow/20260904_archive-panel-v3-dqa1-visual3-review.md \
  docs/Nebflow/20260904_archive-panel-v3-visual3-v1-1920-dock.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-v1-1280-overlay.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-v2-single-1920-dock.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-v2-single-1280-overlay.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-v3-direct-open-dock.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-v3-vp700-host900-dock.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-r1-375.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-r2-f1-t1-light.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-r2-f3-t5-mixed.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-r2-f4-all-archived.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-r2-hover.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-r3-f2-t4-dark.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-r3-f5-t7-detail-rm.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-qaprobe-overlay470.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-qaprobe-dock790.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-qaprobe-latch-fixed.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-qaprobe-375.png \
  docs/Nebflow/20260904_archive-panel-v3-visual3-probe.mjs \
  docs/Nebflow/20260904_archive-panel-v3-visual3-probe-output.txt \
  docs/Nebflow/20260904_archive-panel-v3-visual3-focus.mjs \
  docs/Nebflow/20260904_archive-panel-v3-visual3-focus-output.txt \
  docs/Nebflow/20260904_archive-panel-v3-visual3-f4clean.mjs \
  docs/Nebflow/20260904_archive-panel-v3-visual3-f4clean-output.txt \
  && git commit -m "归档面板 v3 D-QA1 修复版视觉评审 PASS（第三轮）：resize 双锁死形态双灭 + D2 即时判定/交叉实证 + 无回归抽查（17 截图 + 3 探针源码与原始输出）"
# INDEX.md 追加一行（可并入上笔）：
# | [20260904_archive-panel-v3-dqa1-visual3-review.md](20260904_archive-panel-v3-dqa1-visual3-review.md) | 归档面板 v3 D-QA1 修复版视觉评审（重派3）：ResizeObserver 落定重判修复独立实证——1280→1920→1280 序列与单发最大化/还原双锁死形态双灭（交叉面积=0/详情完整在 host 内）、click→dock Δ=11.1ms 即时判定零回归、host 钉宽四向交叉实证（含无 resize 事件 RO 自动重判）、D1/D3/fm-exit/mailbox/hover/双主题/rm 无回归；3 项探针伪影归因复测证伪；上游自评逐项核实无虚报 | PASS（下游 QA 放行） | 2026-09-04 |
```

## 8. 遗留（不阻塞本判定）

1. T6 重载恢复态红（既有文档化，三论定性不变，静态 A17a/b+A18a-d 兜底绿）——待恢复态专项。
2. flowmap-realtime T1 过期断言——按 QA v2 定性由合并节点携带重写。
3. 375 下 host 右缘溢出视口 16px（分栏 min-width，首轮 §4 附带项，超出本特性面）。
4. 观察记录（非缺陷）：全归档空态下面板自动收起、fab 失 active（F4 净态实测）——与前轮同构行为，未列入规格断言，记录备查。
