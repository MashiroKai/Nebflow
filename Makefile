.PHONY: compile run assembly clean install \
  lint fmt fix fmt-check check quality-gate

# Auto-detect fastest Maven mirror (Aliyun for China, Maven Central otherwise)
# Only runs when COURSIER_REPOSITORIES is not already set by the user
MIRROR := $(if $(COURSIER_REPOSITORIES),,$(shell scripts/detect-mirror.sh))
export COURSIER_REPOSITORIES := $(MIRROR)

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

install: assembly
	@echo "Installing nebflow v$(VERSION)..."
	@mkdir -p $(HOME)/.local/bin
	@cp target/$(SCALA_DIR)/nebflow-assembly-$(VERSION).jar $(HOME)/.local/bin/nebflow.jar
	@echo '#!/bin/sh' > $(HOME)/.local/bin/nebflow
	@echo 'exec java --add-opens java.base/java.lang=ALL-UNNAMED -jar "$(HOME)/.local/bin/nebflow.jar" "$$@"' >> $(HOME)/.local/bin/nebflow
	@chmod +x $(HOME)/.local/bin/nebflow
	@echo "Installed to $(HOME)/.local/bin/nebflow"
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

