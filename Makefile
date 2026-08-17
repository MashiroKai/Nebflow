.PHONY: compile run assembly clean install \
  lint fmt fix fmt-check check quality-gate

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
	@echo "Installing $(PRODUCT_NAME) v$(VERSION)..."
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

