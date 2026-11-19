.PHONY: compile run assembly clean install \
  lint fmt fix fmt-check check quality-gate check-visual visual-baseline frontend-gates component-contract \
  flat-buttons glass toggle toggle-geometry scroll-pill input-surface mobile-canvas-strip badge-words \
  ui-structure copy-budget button-source \
  plugin-seed-parity check-static check-sdd test-scripts lint-tools

# Auto-detect fastest Maven mirror (Aliyun for China, Maven Central otherwise)
# Respects user-set COURSIER_REPOSITORIES; only runs detection if unset
ifndef COURSIER_REPOSITORIES
  COURSIER_REPOSITORIES = $(shell scripts/detect-mirror.sh)
endif

# Resolve mirrors only in JVM recipes; offline Node targets never probe the network.
JVM_ENV = COURSIER_REPOSITORIES="$(COURSIER_REPOSITORIES)"

compile:
	$(JVM_ENV) sbt compile

run:
	$(JVM_ENV) sbt run

assembly:
	$(JVM_ENV) sbt assembly

clean:
	$(JVM_ENV) sbt clean

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
	$(JVM_ENV) sbt "scalafmtCheckAll" "scalafix --check"

fmt:
	$(JVM_ENV) sbt scalafmtAll

fix:
	$(JVM_ENV) sbt "scalafix"

fmt-check:
	$(JVM_ENV) sbt scalafmtCheckAll

check: compile fmt-check lint check-static
	@echo "All automated checks passed."

# Installed-plugin parity is an explicit local check, not a checkout gate.
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
	$(JVM_ENV) sbt compile scalafmtCheckAll "scalafix --check"
	@echo "Quality gate passed."

# Shared checkout-only registry. No browser, JVM, live home or mirror probe.
frontend-gates:
	node scripts/run-checks.mjs --profile frontend

check-static:
	npm run check:static

check-sdd:
	npm run check:sdd

test-scripts:
	npm run test:scripts

lint-tools:
	npm run lint:tools

# ── 身份/类型徽标词单源：单闸入口（术语统一批 2026-11-06）────────────────────
# 判据 = 徽标词只来自 js/termWords.js 一张表（A）+ 徽标渲染槽零字面量（B）+ 产词
# 函数收敛（C）+ 语言包译名退役（D）+ 同一概念单一词面（E）。真渲染腿 =
# tests/term-unify-badge-words.spec.mjs（W1-W5，桌面三层 + 手机层）。
badge-words:
	node scripts/check-badge-words.mjs

# ── 按钮扁平化：单闸入口（CI 同名 step 调的就是它，单一真源别在 CI 里另抄一遍）──
flat-buttons:
	node scripts/check-flat-buttons.mjs

# ── 前端设计要求四条规范：单闸入口（ui-design-requirements 批 2026-11-18）────────
# 规范正文（改判据改规范，脚本只是执行器）唯一一份在 stand-dev 插件包内
# （<包根>/references/frontend/：disclosure-dedup-spec.md 渐进式披露 + 无重复 ·
#  explanatory-copy-spec.md 解释性文字 · flat-button-spec.md §5/§6 统一按钮来源）；
# 本仓无第二份拷贝 —— 包根定位见 scripts/lib/design-spec-source.mjs，
# 缺席时闸以退出码 2 结束（`npm run check:static` 记为 SKIP）。
ui-structure:
	node scripts/check-ui-structure.mjs

copy-budget:
	node scripts/check-copy-budget.mjs

button-source:
	node scripts/check-button-source.mjs

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

