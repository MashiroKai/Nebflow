.PHONY: compile run assembly clean install \
  lint fmt fix fmt-check check quality-gate check-visual visual-baseline

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
LOWER_NAME := $(shell sed -n 's/^lowerName[[:space:]]*=[[:space:]]*//p' brand.conf | sed 's/[[:space:]]#.*$$//; s/[[:space:]]*$$//' | head -1)
PRODUCT_NAME := $(shell sed -n 's/^productName[[:space:]]*=[[:space:]]*//p' brand.conf | sed 's/[[:space:]]#.*$$//; s/[[:space:]]*$$//' | head -1)

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

check: compile fmt-check lint
	@echo "All automated checks passed."

quality-gate:
	sbt compile scalafmtCheckAll "scalafix --check"
	@echo "Quality gate passed."

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

visual-baseline:
	node scripts/visual-baseline.mjs --update
	@echo "Baselines written — 人工评审 .nebflow/visual-baseline/<platform>/ 后再提交 tests/visual/baseline/<platform>/。"

