.PHONY: compile run assembly clean install \
  lint fmt fix fmt-check check quality-gate check-visual visual-baseline frontend-gates component-contract

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

check: compile fmt-check lint frontend-gates
	node scripts/check-component-contract.mjs
	@echo "All automated checks passed."

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
FRONTEND_GATES := design-tokens msgstyle self-explaining circular fixture-secrets freeze-state

frontend-gates:
	@command -v node >/dev/null 2>&1 || { echo "node not found — 前端门禁需要 node"; exit 2; }
	node scripts/check-design-tokens.mjs
	node scripts/check-msgstyle-single-source.mjs
	node scripts/check-self-explaining.mjs
	node scripts/check-circular.mjs
	@# 证据/夹具是高熵长令牌的落点（2026-11-04 两小件批 · 件三）：`tests/fixtures/` 与
	@# `reports-evidence/` 里出现「敏感键名 ∧ 值 ≥32 字符 ∧ 字符集 ≥3 类」的字面量即红
	@# （占位词豁免）。判据与运行期脱敏词表同族（`nebflow.shared.SecretKeys`）。
	node scripts/check-fixture-secrets.mjs
	@# 冻结态呈现门禁（冻结态误报批 2026-11-05）：可自动恢复的冻结不得写「已冻结」/不得
	@# 给按钮；需人介入的冻结必须有恢复入口；恢复失败必须可见；迁移日志在场。
	node scripts/check-freeze-state.mjs
	@echo "Frontend static gates passed ($(FRONTEND_GATES))."

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

