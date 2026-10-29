# errorfrozen-dialog-minimal 批报告（作者令 2026-10-29 13:51「错误态/冻结态对话框……往简约的方向走 结合stand的风格 要极简」，console 渠道）

分支 `delegate/delegate-nebflow-76727b4a`；独立 commit；在产实例零触碰（验证全部走自足静态 harness，playwright-core 隔离端口）。规格 = `docs/design/20261029_errorfrozen-dialog-minimal-spec.zh-CN.md`。

## 一、盘点清单（11 面，处置 5 / 盘点留档 6）

改动（5）：
1. **调度冻结输入条** `#input-bar.frozen`/`.fa-input-bar.frozen`（css/input.css）
2. **错误恢复输入条+条带** `.frozen-error`+`.error-recovery-strip`（css/input.css；DOM 不动）
3. **升级决策卡** `.error-escalation-card`（css/chat.css；DOM 不动）
4. **会话过期横幅** `#stand-session-gate`（js/sessionGate.js，加行首红图标节点）
5. **子代理冻结 tile** `.flow-agent-footer.frozen[.error]`（js/flowAgentPopup.js POPUP_CSS）

盘点不改动（6，均已达标态或越界面）：错误 toast（2026-09-03 毛玻璃批达标，toast-glass 回归护）、断连横幅（玻璃+warn 图标）、bgTaskOutputPopup 失败行（ui-status-redesign 极简范式本体）、接管/删除/冲突模态（状态对话框非错误态）、陈旧标签页提示（非错误态）、/model 加载失败 + onboarding 错误行 + mic-orb + updateCard。移动端：mobile.css/mobileLayer.css 对上述面零覆盖（grep 证），零文件抢占。

## 二、逐处改动（纯减法 + 材质收敛，行为契约零改动）

1. **冻结条**：3px frost ring、18px 外发光、placeholder 光晕（text-shadow）、顶缘冰线提亮全部退役；状态 = 1px 蓝宝描边(α .35)+淡蓝底(α .07)+冰蓝 placeholder；材质阴影回落常态玻璃卡三件套。「跳过本次」唯一动作不变。
2. **错误条+条带**：同上减法（amber 档 α .38/.07）；条带图标脉冲（`error-pulse`）退役；按钮从实底/描边块收敛为 `skip-freeze-btn` 同族静音文本 chip——「立即重试」amber 字（主操作），「取消任务」中性字（次级，hover 淡底）；禁用态（无可重发）语义不变。
3. **升级决策卡**：琥珀大底+琥珀描边+内高光+外发光 → 中性 etched 玻璃底 + 琥珀唯一载体 = 2px 左缘描边 + 行首静态图标（bgTaskOutputPopup `.bgt-error` 同款极简范式）；图标脉冲退役；三真实出口保留（重启并续跑/取消任务/继续等待）收敛为文本 chip，主操作 amber 字、其余中性字。
4. **会话过期横幅**：实色红条（#b62324 大面积铺红）→ 深霜玻璃 chip（rgba(28,30,38,.92)+blur，staleTabHint 同族材质）+ 行首一枚 `--status-error-icon` 红图标（唯一红载体）+ 中性白字；自愈 reload 行为与契约属性（id/data-session-gate/data-source/role=alert）逐字保留。
5. **子代理 tile**：12px 外发光、状态点 3px 光环、错误图标脉冲退役；色相（底/描边/点/字）即状态。
6. **`error-pulse` 动画整链退役**（定义 + 三消费点），`prefers-reduced-motion` 规则随消费点同步。

Token 纪律：零新 token 零新字面色值；全部走 `--sapphire`/`--amber`/`--glass-etched-*`/`--status-error-icon`/`--status-word`/`--color-frame-hover`；唯一字面删除 = `#b62324`。

## 三、验证台账

- **极简结构断言**：`tests/errorfrozen-minimal.spec.mjs` **39/39 PASS**（S1 冻结条无 ring/glow/光晕+结构；S2 条带 1图标+1行+≤2 chip+abandon 非红；S3 卡 etched 底逐值相等+2px 左缘唯一琥珀+≤3 borderless chip+≤4 块；S4 横幅非红底+唯一红图标+≤2 节点+契约属性；S5 tile 无发光/光环/脉冲；S6 源级 token 纪律）。
- **回归**：toast-glass **27/27**、session-gate **23/23**、conn-banner **ALL PASS**、console-takeover **ALL PASS**、wait-timeout-frozen-timer **3/3**（playwright runner）。
- **checkJs**：触及 JS（sessionGate.js / flowAgentPopup.js）零错；total 256 < baseline 260。唯 FAIL 项 = `canvas.js: new file with 2 errors` —— **既有欠账，非本批引入**（canvas.js 最后改动 = 分支基点 6a9f17241 本机加速批，本批零触碰；基线 tests/type-baseline.json 未随该批入账）。
- **build-web**：193 源全图 orphans 0 PASS。
- **前后对比截图**：亮暗双模 × {全景、冻结条、错误条、升级卡、tile、toast、过期横幅} = 14+14 张，`evidence/20261029_142700_errorfrozen-dialog-minimal__delegate-nebflow-76727b4a/{before,after}/`（驱动 `tests/errorfrozen-capture.mjs`，harness `tests/fixtures/errorfrozen-minimal-harness.html` = 真实生产 CSS 链 + 真实渲染器：errorRecovery.js applyErrorFrozen/renderEscalationCard、sessionGate.js noteAuthRejected、modal.js showToast、flowAgentPopup.js POPUP_CSS 注入）。

## 四、未做 / 开放项

- canvas.js 的 checkJs「new file」欠账待其归属批重锚基线（本批不越权代偿）。
- 错误 toast/takeover 模态如需进一步减法（如 toast 收敛为单行无图标档），属新裁定范围，未动。
