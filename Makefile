.PHONY: compile run assembly clean install

compile:
	sbt compile

run:
	sbt run

assembly:
	sbt assembly

clean:
	sbt clean

install: assembly
	@echo "Installing nebflow..."
	@mkdir -p $(HOME)/.local/bin
	@cp target/scala-3.5.2/nebflow-assembly-1.0.0.jar $(HOME)/.local/bin/nebflow.jar
	@echo '#!/bin/sh' > $(HOME)/.local/bin/nebflow
	@echo 'exec java -jar "$(HOME)/.local/bin/nebflow.jar" "$$@"' >> $(HOME)/.local/bin/nebflow
	@chmod +x $(HOME)/.local/bin/nebflow
	@echo "Installed to $(HOME)/.local/bin/nebflow"
	@echo "Make sure $(HOME)/.local/bin is in your PATH"
