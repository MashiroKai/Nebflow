.PHONY: compile run assembly clean install \
  lint fmt fix fmt-check check quality-gate check-visual visual-baseline frontend-gates component-contract \
  flat-buttons glass toggle toggle-geometry scroll-pill input-surface mobile-canvas-strip badge-words \
  plugin-seed-parity

# Auto-detect fastest Maven mirror (Aliyun for China, Maven Central otherwise)
# Respects user-set COURSIER_REPOSITORIES; only runs detection if unset
ifndef COURSIER_REPOSITORIES
  COURSIER_REPOSITORIES := $(shell scripts/detect-mirror.sh)
endif
export COURSIER_REPOSITORIES

compile:
	sbt compile

run:
	sbt run

assembly:
	sbt assembly

clean:
	sbt clean

VERSION := $(shell cat VERSION)
SCALA_DIR := $(notdir $(wildcard target/scala-*))

# L2 rebrand: brand values from repo-root brand.conf (single edit point).
# `\#` 转义（self-explaining-cleanup 批 2026-11-04 修）：sed 模式里的裸 `#` 会让
# GNU make 把它当**注释起点**，吞掉后面的 `)` ⇒ 整份 Makefile 直接
# `*** unterminated call to function 'shell'` 拒绝解析，**任何** target 都跑不了
# （含本批新加的 frontend-gates）。转义后 make 与 sed 都解析为字面 `#`。
LOWER_NAME := $(shell sed -n 's/^lowerName[[:space:]]*=[[:space:]]*//p' brand.conf | sed 's/[[:space:]]\#.*$$//; s/[[:space:]]*$$//' | head -1)
PRODUCT_NAME := $(shell sed -n 's/^productName[[:space:]]*=[[:space:]]*//p' brand.conf | sed 's/[[:space:]]\#.*$$//; s/[[:space:]]*$$//' | head -1)

install: assembly
	@echo "Installing $(PRODUCT_NAME) $(VERSION)..."
	@mkdir -p $(HOME)/.local/bin
	@cp target/$(SCALA_DIR)/$(LOWER_NAME)-assembly-$(VERSION).jar $(HOME)/.local/bin/$(LOWER_NAME).jar
	@echo '#!/bin/sh' > $(HOME)/.local/bin/$(LOWER_NAME)
	@echo 'exec java --add-opens java.base/java.lang=ALL-UNNAMED -jar "$(HOME)/.local/bin/$(LOWER_NAME).jar" "$$@"' >> $(HOME)/.local/bin/$(LOWER_NAME)
	@chmod +x $(HOME)/.local/bin/$(LOWER_NAME)
	@echo "Installed to $(HOME)/.local/bin/$(LOWER_NAME)"
	@echo "Make sure $(HOME)/.local/bin is in your PATH"

# Quality control targets

lint:
	sbt "scalafmtCheckAll" "scalafix --check"

fmt:
	sbt scalafmtAll

fix:
	sbt "scalafix"

fmt-check:
	sbt scalafmtCheckAll

check: compile fmt-check lint frontend-gates plugin-seed-parity
	node scripts/check-component-contract.mjs
	@echo "All automated checks passed."

# 插件种子对账门禁（seed-parity 批 2026-11-05）
# 动机（实测）：visual-report 的 ⑥⑦⑧ 条款 + R6/R7/R8/R9 + 允许清单块 + 49 例回归夹具
# 全部只落在运行实例，仓库种子停在五条款时代。种子是 fresh home 的安装源
# （SeedService.seedPlugin 整目录复制），旧种子 = 新装实例自带旧标准；实例一旦被
# re-approve，digest 仲裁还会把种子覆盖回实例。「记得同步」不是机制，这条腿是。
# 两条腿：腿 A 自检种子自带批准形态（CI 同此形态，不需要实例）；腿 B 逐文件比对
# 实例 ↔ 种子在规范/脚本/测试三类别上的漂移，未登记的漂移即红（登记面 =
# tools/plugin-seed-parity-known-drift.json，条目必须仍在漂移，否则判陈旧）。
# 只读：本 target 从不改写任何一侧；方向永远是「批准的那一侧 → 另一侧」。
plugin-seed-parity:
	@command -v python3 >/dev/null 2>&1 || { echo "python3 not found — 种子对账门禁需要 python3"; exit 2; }
	python3 tools/plugin-seed-parity --self-check
	python3 tools/plugin-seed-parity --parity
	@echo "Plugin seed parity gate passed (self-check + drift leg)."

# 前端组件契约门禁（单独一条腿，不依赖 sbt）
component-contract:
	node scripts/check-component-contract.mjs
	@echo "Frontend component contract gate passed."

quality-gate:
	sbt compile scalafmtCheckAll "scalafix --check"
	@echo "Quality gate passed."

# ── 前端静态门禁（self-explaining-cleanup 批 2026-11-04 归口）────────────────
# 四道 node 静态哨兵，零依赖零端口（不需要浏览器、不碰宿主实例 6138）：
#   · design-tokens   设计字面量 → 令牌（存量基线只降不升 + 严格面零跑偏）
#   · msgstyle        消息面同值字面量单源（共享层 = css/sapphire.css）
#   · self-explaining 页面文案自解释（铁律 8：禁能力说明/路径指引/行为教学/重复说明；
#                     存量基线只降不升、新语言包零容忍）
#   · circular        静态 import 图无环（Tarjan SCC；dynamic import() 是处方逃逸口）
# 归口动机（本批实测）：design-tokens 与 msgstyle 早就存在，但既不在 `make check`
# 也不在 CI —— 等于「写了门禁没人跑」，规则回退零成本。本 target 是**唯一入口**，
# CI 的 `Frontend static gates` 步调的就是它（单一真源，别在 CI 里另抄一遍命令）。
# circular 入列（循环门禁修复批 2026-11-04）：它一直在 CI（ci.yml js-types job）里跑，
# 但**本地**这条腿没有它 —— 断环的人跑 `make check` 看不到读数，存量环就是这么红着
# 过夜的（CI 侧实证见批报告）。CI 那条腿照旧，这里补的是本地防回退。
# 只跑前端门禁：`make frontend-gates`；全量静态检查：`make check`。
# api-routes 入列（empty-state-must-not-lie 批 · 件三，2026-11-05）：2026-11-05 事故
# = 后端把 `GET /api/conversations` 的 case 误删，前端 404 被吞成「暂无会话」，
# **没有任何断言发现「前端调了但后端没有」**。本闸静态对照两侧（前端 /api 字面量
# 集合 × 网关路由注册面），前端调了后端没有 ⇒ 红。
# （Makefile 热点区口径：合并两边门禁并列保留——mobile-canvas-strip / badge-words / settings-ios-rows / panel-unify 来自 main 侧批次，provider-panel 来自 provider-add-tabs 批。）
FRONTEND_GATES := css-structure design-tokens msgstyle bubble-side self-explaining circular fixture-secrets api-routes freeze-state freeze-silence flat-buttons glass canvas-hint toggle scroll-pill input-surface mobile-canvas-strip badge-words settings-ios-rows panel-unify provider-panel

frontend-gates:
	@command -v node >/dev/null 2>&1 || { echo "node not found — 前端门禁需要 node"; exit 2; }
	@# CSS 结构完整性门禁（image-expand-disappears 批 2026-11-07 · 作者报障
	@# 「为什么现在图片一点展开，就消失了？」）：css/*.css 必须块闭合、注释闭合、且
	@# **零意外嵌套** —— 事故形态 = design-system.css 的 `:root {` 漏了收括号，其后
	@# 全部规则被浏览器按 CSS 嵌套解析（`& .ds-*`），全文件特异性凭空 +1 类名，压过了
	@# chat.css 的态规则 ⇒ 叠卡「展开 N」点下去两面俱隐、图片整组消失。既有 CSS 门禁
	@# 全按选择器字面量判，无人看块结构，故整整一天全绿。判据 S1/S2/S3 + --selftest
	@# 自证 4/4；改前/改后同一份脚本：--root=<另一棵 web 树>。
	node scripts/check-css-structure.mjs
	node scripts/check-design-tokens.mjs
	node scripts/check-msgstyle-single-source.mjs
	@# 气泡归属方底色门禁（bubble-side-color 批 2026-11-06，作者令「我们这方发的……
	@# 一律绿，对方发过来的白」）：气泡底色只准两档（--nf-bub-ours-bg 我方绿 /
	@# --nf-bub-theirs-bg 对方白），第三色（sapphire 蓝 tint / etched 灰 / 中性灰）
	@# 在气泡面上零命中；单点 = css/glass.css 单块 + js/bubbleSide.js 单函数。
	node scripts/check-bubble-side.mjs
	node scripts/check-self-explaining.mjs
	@# 徽标词单源门禁（术语统一批 2026-11-06 作者令「统一 根据英文」）：身份/类型徽标
	@# 词（kernel / dispatcher / node / subagent / delegate / AgentFlow）只准从
	@# js/termWords.js 取；徽标槽写死词面、语言包译名、同一概念第二词面即红。
	node scripts/check-badge-words.mjs
	node scripts/check-circular.mjs
	@# 证据/夹具是高熵长令牌的落点（2026-11-04 两小件批 · 件三）：`tests/fixtures/` 与
	@# `reports-evidence/` 里出现「敏感键名 ∧ 值 ≥32 字符 ∧ 字符集 ≥3 类」的字面量即红
	@# （占位词豁免）。判据与运行期脱敏词表同族（`nebflow.shared.SecretKeys`）。
	node scripts/check-fixture-secrets.mjs
	@# 路由存在性哨兵（empty-state-must-not-lie 批 · 件三）：前端 `/api/` 调用面 ×
	@# 网关 `*Routes.scala` 注册面静态对照，前端调了但后端没注册 ⇒ 红。
	node scripts/check-api-routes.mjs
	@# 服务商面板唯一性哨兵（provider-add-tabs 批 2026-11-06）：旧入口零回潮 / 面板与
	@# 类型标签唯一 / 前后端枚举单源 / 面板家族溢出处置合规（判据 P1–P6 见脚本头注）。
	node scripts/check-provider-panel.mjs
	@# 冻结态呈现门禁（冻结态误报批 2026-11-05）：可自动恢复的冻结不得写「已冻结」/不得
	@# 给按钮；需人介入的冻结必须有恢复入口；恢复失败必须可见；迁移日志在场。
	node scripts/check-freeze-state.mjs
	@# 按钮扁平化门禁（flat-button-everywhere 批 2026-11-05，作者令「我们的按钮扁平化
	@# 风格要求要落实到各处 不管是hover还是点击 或者按下」）：按钮面选择器里出现
	@# 投影/inset 浮雕/渐变/drop-shadow/translateY 按压位移即红。例外清单在规范文件
	@# web/.../design-language/references/flat-button-spec.md（不在脚本里）。
	@# 盘点模式：node scripts/check-flat-buttons.mjs --inventory；机器读：--json。
	node scripts/check-flat-buttons.mjs
	@# 毛玻璃唯一性门禁（glass-unify 批 2026-11-05 立判据；glass-recalibrate 批
	@# 2026-11-05 18:13 重定标 —— 作者原话「我要的是主会话的输入框的这种，不透明，
	@# 但是能反映背景内容颜色的」）：除 css/glass.css 外任何文件出现非 none 的
	@# backdrop-filter ⇒ 红；glass.css 内的值必须是 var(--nf-glass-filter)；
	@# --nf-glass-bg 的 α 必须落在 [0.45, 0.75]（低了内容透底、高了死白，两端都红）；
	@# --nf-glass-blur ≥ 32px 且 --nf-glass-saturate ≥ 1.3（配方不许被降档）；
	@# --nf-glass-* 只许在 stand-tokens.css 定义；canvas ask 的气泡/输入条必须挂 .nf-glass。
	@# glass-top-overlays 批 2026-11-06 加 G7（作者令「所有悬浮在顶部 可以透出背景的
	@# 都用毛玻璃效果 比如消息和联系人的手机端的搜索框 这个要成为标准」）：
	@# G7a 顶部悬浮 + 半透明（α ∈ (0,.90)）+ 无模糊的面 ⇒ 红（sticky 带 top / 顶带 /
	@# 顶带子件三类锚定）；G7b 规范 §7.1「顶部悬浮面清单」逐行机检材质漂移；
	@# G7c 例外清单理由为空或指向已不存在的面 ⇒ 红。手机搜索条与聊天气泡上方的
	@# sticky 等待条即本批由 G7 抓出并整改的两面。
	@# 规范正文与例外登记：web/.../design-language/references/glass-spec.md。
	@# A/B 证据装置：node scripts/capture-glass-ab.mjs --root <web> --tag <before|after>。
	@# 盘点模式：node scripts/check-glass-unify.mjs --inventory；机器读：--json。
	node scripts/check-glass-unify.mjs
	@# 冻结通知策略门禁（冻结通知策略批 2026-11-05）：provider 故障 = 基础设施故障 ⇒
	@# delegate/flow 两条向上通知腿零产出、面板显形（统计 + 待你处理 + 唤醒按钮）、
	@# 引擎自动续跑走既有唤醒腿（不花重启预算，有上限 + 递增间隔）、同波一条聚合告警。
	node scripts/check-freeze-silence.mjs
	@# 冻结**重放面**门禁（修复批 2026-11-06，作者原话「llm 不稳定 不需要向上通知…现在还是
	@# 在给你报障」）：既有上面那道只看**投递**腿（main 上已全绿，作者仍被刷）；本道补
	@# **解冻重放**腿——冻结窗积在邮箱里的回执不许在解冻时逐条冒泡，同因只留一条状态行，
	@# 可恢复（等待恢复中）与真失败（待你处理）词色分开。
	node scripts/check-freeze-replay-silence.mjs
	@# Canvas 提示文字令牌唯一性门禁（canvas-hint-text 批 2026-11-05，作者令「canvas
	@# 内所有的提示文字，要统一制作规范，不然会跟悬浮在上面的按钮/胶囊重叠」）：
	@# ① 规范层 css/canvasHint.css 三字档令牌齐备且声明区零裸 px / 零裸色值；
	@# ② 四边安全区令牌 --cv-safe-{top,right,bottom,left,gap} 全在场且取令牌值；
	@# ③④⑤ 盘点清单 29 条提示文字规则：零裸值 / 字档取三字档 / 行高取三档令牌；
	@# ⑥ 安全区容器 .cv-safe-pad 四边只取 --cv-safe-*；⑦ data-cv-hint 形态白名单；
	@# ⑧ 占位 ::placeholder 色走令牌且透明度显式归 1。
	@# 真几何腿（零端口、真 Chromium）另跑：node tests/canvas-hint-safe-area.spec.mjs
	@# 改前/改后对照：--web-root <另一棵 web 树>；验红：--mutate=drop-face-safe-top。
	node scripts/check-canvas-hint-tokens.mjs
	@# 开关唯一性门禁（toggle-unify 批 2026-11-05，作者裁定 19:55「A 胶囊滑块」）：
	@# 行为单点 = js/toggle.js（`role="switch"` 只许在这里出现）、造型单点 =
	@# css/sidebar.css 的 `.nb-toggle`（其它文件不许再画开关头像：圆钮 ::after +
	@# border-radius:50% / 滑行 translateX / :checked+translateX 三条签名）、
	@# 数值单点 = stand-tokens.css 的 `--nf-toggle-*`（40×22 / 16 圆钮 / 999 圆角 /
	@# 18 行程 / 180ms 曲线，组件节不许写死几何）。
	@# 盘点：node scripts/check-toggle-unify.mjs --inventory；机器读：--json。
	node scripts/check-toggle-unify.mjs
	@# 会话面胶囊居中几何门禁（作者令 2026-11-06 08:05「不要靠边 就保持之前的居中」）：
	@# 一族三消费（新消息通知 / 回到底部 / 搜索回跳）共用 .scroll-new-pill，形态单源
	@# = chat.css 该段；缺 left:50% + translateX(-50%)、任一态丢居中位移、出现
	@# right:/justify-self:end 之类右靠边手法、或冒出第二套 scroll-*-pill 形态类 ⇒ 红。
	@# 几何真值（水平中心偏差 ≤0.5px）由 tests/scroll-pill-family.spec.mjs 的
	@# S1/S2/S4/S5 四条实测腿给出（需浏览器，不在静态门禁里跑）。
	@# 盘点：node scripts/check-scroll-pill-center.mjs --json。
	node scripts/check-scroll-pill-center.mjs
	@# 输入面唯一性门禁（input-surface-unify 批 2026-11-06；作者裁定「输入框的这个
	@# 样式也要成为统一标准 不可以出现第二种」）：① --nf-field-* 只在 stand-tokens.css
	@# 定义；② 输入面选择器上的底/边/圆角/影/描边只许在 css/inputSurface.css；
	@# ③ 标准文件里的造型值必须取 var(--nf-field-*)；④ 蓝族令牌不许进输入面（唯一
	@# 开口是 keyboard 模式下的压灰环）；⑤ JS 不许内联给输入件写造型；⑥ 豁免只能
	@# 在规范文件 input-surface-spec.md 的例外清单里具名登记（空理由 = 红）。
	@# 盘点：node scripts/check-input-surface.mjs --inventory；机器读：--json。
	node scripts/check-input-surface.mjs
	@# 手机 canvas 顶部环带（方案 C）门禁（mobile-canvas-tabs-c 批 2026-11-06；作者 08:33
	@# 逐字「采用方案c 主胶囊要采用毛玻璃底 然后背景的文字不使用胶囊 但是还有电脑端
	@# hover 上下选项的那种模糊效果 文字两边不要渐隐」）四条判据：
	@#   S1 玻璃单源 —— 环带脸上任何 α ∈ (0,.90) 的可透出面必须进 glass.css 成员表；
	@#      且玻璃带层 `.mf-strip::before` 与主胶囊 `.mf-strip-chip.on::after` 必须
	@#      在场且走玻璃（半透明但没走玻璃 ⇒ 红）；
	@#   S2 禁止水平渐隐 mask —— 环带脸上出现 90deg/270deg/to left/to right 的
	@#      mask-image、或 label 上任何非 none 的 mask、或 JS 写 mask/--rfade ⇒ 红；
	@#   S3 背景的文字不使用胶囊 —— 签视觉层基础档必须零材质（胶囊只在 .on 档）、
	@#      正文宿主（.canvas-md-scroll / .mf-cv-host / .canvas-tab-pane）零底零 pill；
	@#   S4 下边缘渐隐厚度 = 28px —— `.mf-strip::after` 在场、取 --ds-fade-veil、
	@#      height 的**令牌算术**恰好 28px、top 恰好等于玻璃带高 64px。
	@# 改前/改后同一份脚本：node scripts/check-mobile-canvas-strip.mjs --root=<改前树>；
	@# 机器读：--json。真渲染读数（375/1440 × 亮暗）另跑 scripts/capture-mobile-canvas-tabs.mjs。
	node scripts/check-mobile-canvas-strip.mjs
	@# 面板设计语言唯一性门禁（panel-language 批 2026-11-06，作者令 3(c)(d)(e)）：
	@# (c)「面板宽度与形态：不要做成弹窗，要运用整个面板的宽度」/(d)「按钮去重：
	@# 不要保留功能重复的按钮，比如已经有返回键了，就不要再放叉号键（X）」/(e)「规范
	@# 统一：顶部文字大小等面板设计……统一整理出一套面板的设计语言」。判据：P1 同类
	@# 面板标题只能取规定令牌（--ds-panel-title-* 等，裸字面量即红）；P2 有返回键的面
	@# 不许同时有 X 键（dom-guard 档：锚形 ±3 行内必须见 popSheet.isMobileLayer 守卫；
	@# css-hide 档：2026-10-30 清查批隐藏规则必须在场）；P3 例外表卫生（空理由/陈旧
	@# 例外 = 红）；P4 登记令牌必须在场。登记表与例外都在规范文件
	@# web/.../design-language/references/panel-spec.md（不在脚本里，改判据先改规范）。
	@# 改前/改后同一份脚本：node scripts/check-panel-unify.mjs --root=<改前树>
	@# （本批改前 7 红 → 改后 0）。真渲染读数：tests/panel-language.spec.mjs。
	@# 盘点模式：node scripts/check-panel-unify.mjs --inventory；机器读：--json。
	node scripts/check-panel-unify.mjs
	@# 设置页 iOS 行族门禁（settings-ios-rows 批 2026-11-06，作者令「能点击下一个面板的
	@# 样式都统一成苹果的这种样式 … 底部的重启服务去掉 … 可点击的按钮 布局规整一点」）：
	@# R1 重启服务零痕 / R2 可点进行唯一样式（setNavRow 单源 + chevron 单点）/
	@# R3 行内右侧不重复同一数值 / R4 动作钮走 --set-* 令牌。
	@# 盘点模式：node scripts/check-settings-ios-rows.mjs --json；
	@# 改前红证据：--web-root <另一棵 web 树>。
	node scripts/check-settings-ios-rows.mjs
	@echo "Frontend static gates passed ($(FRONTEND_GATES))."

# ── 身份/类型徽标词单源：单闸入口（术语统一批 2026-11-06）────────────────────
# 判据 = 徽标词只来自 js/termWords.js 一张表（A）+ 徽标渲染槽零字面量（B）+ 产词
# 函数收敛（C）+ 语言包译名退役（D）+ 同一概念单一词面（E）。真渲染腿 =
# tests/term-unify-badge-words.spec.mjs（W1-W5，桌面三层 + 手机层）。
badge-words:
	node scripts/check-badge-words.mjs

# ── 按钮扁平化：单闸入口（CI 同名 step 调的就是它，单一真源别在 CI 里另抄一遍）──
flat-buttons:
	node scripts/check-flat-buttons.mjs

# ── 毛玻璃：单闸入口（与 flat-buttons 同形；CI 同名 step 调的就是它）──────────
glass:
	node scripts/check-glass-unify.mjs

# ── 面板设计语言：单闸入口（与 flat-buttons / glass 同形；CI 同名 step 调的就是它）──
panel-unify:
	node scripts/check-panel-unify.mjs

# ── 会话面胶囊居中：单闸入口（与 flat-buttons / glass 同形）────────────────────
scroll-pill:
	node scripts/check-scroll-pill-center.mjs

# ── 手机 canvas 顶部环带（方案 C）：单闸入口（与 flat-buttons / glass 同形）─────
mobile-canvas-strip:
	node scripts/check-mobile-canvas-strip.mjs

# ── Canvas 提示文字：单闸入口（静态腿）+ 真几何腿 ────────────────────────────
# 静态腿 = 令牌唯一性（零依赖零端口）；几何腿 = 逐面 getBoundingClientRect 零交叠
# （需 playwright，不在默认 check 里跑 —— 与其它「要浏览器」的腿同一编排口径）。
canvas-hint:
	node scripts/check-canvas-hint-tokens.mjs
	@echo "Canvas hint text token gate passed."

canvas-hint-geometry:
	node tests/canvas-hint-safe-area.spec.mjs

# ── 开关唯一性：单闸入口（与 flat-buttons / glass 同形）──────────────────────
# 静态腿（本 target）= 行为/造型/数值唯一性 + 「居中三式必须是推导式」（禁手填）。
toggle:
	node scripts/check-toggle-unify.mjs

# ── 开关居中：真几何腿（像素级）───────────────────────────────────────────────
# toggle-center 批 2026-11-06（作者原话：「中间的按钮相对于边框都没有居中」）。
# 判据：|上距−下距| ≤ 0.5px、|左距(关)−右距(开)| ≤ 0.5px；覆盖桌面档（40×22）
# + 手机档（body.mobile-layer，51×31）× 亮/暗两主题；读数两路互校（浏览器布局盒
# 经 CDP 取伪元素 border box + DSF=4 哨兵色像素包围盒）。
# 需 playwright（与 canvas-hint-geometry 同一编排口径：不在默认 check 里跑）。
# 验红：--mutate=prev-mobile-inset|travel-ignores-inset（判据必须转红，否则 exit 3）。
toggle-geometry:
	node scripts/check-toggle-geometry.mjs

# ── 输入面唯一性（input-surface-unify 批 2026-11-06；作者裁定「输入框的这个样式
#    也要成为统一标准 不可以出现第二种」）──────────────────────────────────────
# 造型单源 = css/inputSurface.css + stand-tokens.css 的 --nf-field-*；
# 别处写底/边/圆角/影/描边 ⇒ 红（I2）；蓝族令牌不许进输入面（I4）；
# 豁免只在规范文件例外清单里登记（I6）。盘点：--inventory；机器读：--json。
input-surface:
	node scripts/check-input-surface.mjs

# 真几何腿（零端口、真 Chromium，改前/改后对照 + 指针态/键盘态双通道读数）
input-surface-geometry:
	node tests/input-surface.spec.mjs

# ── 路由存在性：运行期腿（empty-state-must-not-lie 批 · 件三②，2026-11-05）──────
# 静态腿判「源码里注册了没」，运行期腿判「启动后真的答不答」——后者才是事故的
# 现场判据：今天 `GET /api/conversations` 被误删时前端拿到 404，面板渲染成
# 「暂无会话」，**没有任何断言**发现「前端在调、实例上没有」。
# 判据：沙箱实例起来后，逐条打前端调用面的每条路径（清单由
# `check-api-routes.mjs --emit-paths` 单一真源给出），**任一路径全 method 404 ⇒ 红**。
# 隔离：数据根 mktemp -d（--home + STAND_HOME + NEBFLOW_HOME 三重注入）、端口 8097，
# 拒绝 6138/8080/<1024，端口已占用则拒绝复用；结束只 kill 自己 spawn 的进程组。
# 为什么不塞进 frontend-gates：它需要「先编译再起实例」（分钟级 + 端口），
# 混进每次编辑都会跑的静态腿里会被绕开；CI 侧作为独立 step 跑（见 .github/workflows/ci.yml）。
api-routes-live:
	@command -v node >/dev/null 2>&1 || { echo "node not found — 运行期哨兵需要 node"; exit 2; }
	node scripts/check-api-routes-live.mjs

# ── 视觉回归基线门禁（tests/visual/，2026-11-01 采纳批）──────────────────────
# 按**宿主平台键**取基准图目录 tests/visual/baseline/<platform>/（platform 由脚本
# 自算：`${process.platform}-${browserLabel}`，如 darwin-chromium-151 /
# win32-msedge-156）。已入库两套：win32-msedge-156（Windows+Edge 宿主采集，归
# **Windows runner 腿**）与本机 darwin-chromium-151（采纳批实测录入）。在别的宿主
# 上跑 --check 会逐张 MISS 而非误判为「像素漂移」——MISS 的正确处置是在**该宿主**
# 跑 `make visual-baseline` 人工评审后入库一套本平台基准，不是放宽阈值。
# 零端口零进程（page.route 从磁盘注入 + MockWS），结构上不可能碰到宿主实例 6138。
# 不挂进 `make check`：make check 是 Scala 静态门禁（compile/fmt/lint），视觉腿
# 依赖浏览器且按平台分库，属「改动后自检」腿（前端批验收清单第五项）。
#
# 阈值口径（显式放宽，非默认放宽）：2026-11-01 采纳批在本机实测——10/12 场景跨
# 轮逐字节 SAME，两个 canvas 场景（monaco 页签，552x874）在 1px 页签边框上出现
# 12–16 px 的随机反走样漂移（0.002–0.003%，同轮双拍自身一致 ⇒ 不是产品抖动，是
# 宿主渲染噪声）。因此本 target 显式给 0.05% 上限（≈241 px）兜这类噪声，同时
# 一整个字形/一整行边框量级的真变化仍会红。想把关到零差异：`make check-visual
# VISUAL_MAX_RATIO=0`（Windows runner 腿建议保持 0 —— 它的基准就是零噪声采集的）。
VISUAL_MAX_RATIO ?= 0.0005
check-visual:
	@command -v node >/dev/null 2>&1 || { echo "node not found — 视觉门禁需要 node + playwright"; exit 2; }
	node scripts/visual-baseline.mjs --check --max-ratio $(VISUAL_MAX_RATIO)
	@echo "Visual baseline gate passed (host-matched baselines, max-ratio $(VISUAL_MAX_RATIO))."

# ── 两段式入库（2026-11-04 修陷阱批）────────────────────────────────────────
# 改造前：`make visual-baseline`（= --update）**直接覆盖** tests/visual/baseline/，
# 而本节的注释说「评审 .nebflow/ 评审区后再提交入库」—— 那句当时不成立：草稿没有
# 独立落点，一跑 --update 入库基准就已经变了，"评审"只能事后 git diff 才看见。
# 现在拆两段：
#   make visual-baseline           → 只写评审区草稿 .nebflow/visual-baseline/<platform>/baseline-draft/
#   make visual-baseline-promote   → 人工评审通过后，把草稿推进 tests/visual/baseline/<platform>/
# 跑 promote 这个 target 本身即「已人眼评审」的声明（脚本对裸 CLI 调用仍强制
# --i-reviewed）。忘了评审就 promote 的后果是入库图变了你没看过 —— 与改造前的
# 区别是：现在它必须是**一次有名字的动作**，而不是顺手 --update 的副作用。
visual-baseline:
	node scripts/visual-baseline.mjs --update
	@echo "Draft written — 入库基准未触碰。评审 .nebflow/visual-baseline/<platform>/baseline-draft/ 后用 make visual-baseline-promote 入库。"

visual-baseline-promote:
	node scripts/visual-baseline.mjs --promote --i-reviewed

